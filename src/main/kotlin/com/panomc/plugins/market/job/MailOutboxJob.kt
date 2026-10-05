package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.time.Backoff
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.mail.MailAttachments
import com.panomc.plugins.market.mail.MailComposition
import com.panomc.plugins.market.mail.MailGateway
import com.panomc.plugins.market.mail.MailSendResult
import com.panomc.plugins.market.mail.OutboundMail
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.MailOutboxService
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Works the mail outbox (12 section 4.3): cadence 15 s on `MarketScheduler` (MK-078, which only calls [runOnce]), batch
 * [BATCH], rows one after the other. Delivery is at least once: a crashed send leaves a `SENDING` row whose claim runs out
 * and is picked up again as a failed attempt (a duplicate mail is accepted, a lost one is not).
 *
 * No transaction wraps the SMTP call; every state write is one conditional update. Every row is handled in its own
 * `catch (Throwable)`, so one broken row (or a `LinkageError` of an older host) never stops the tick (00 section 8.5).
 *
 * Fail closed: [mailEnabled] has no default (the wiring binds the platform switch), [mailOptionsAvailable] reads the host
 * probe of `MarketRuntime` (false until `onStart` found X-4), the composition is a port (`UnwiredMailComposition` fails every row).
 */
class MailOutboxJob(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val service: MailOutboxService,
    private val gateway: MailGateway,
    private val composition: MailComposition,
    private val sqlClient: suspend () -> SqlClient,
    private val mailEnabled: () -> Boolean,
    private val mailOptionsAvailable: () -> Boolean = { MarketRuntime.capabilities.mail },
    private val backoff: Backoff = RETRY_BACKOFF,
    private val random: Random = Random.Default,
    private val sendTimeoutMs: Long = SEND_TIMEOUT_MS
) {
    /** One tick: returns the number of rows claimed and handled. */
    suspend fun runOnce(): Int {
        val client = sqlClient()
        val due = service.due(clock.now(), BATCH, client)
        var handled = 0
        for (seen in due) {
            try {
                if (process(seen, client)) handled++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("mail outbox row {} failed unexpectedly: {}", seen.id, t.toString())
            }
        }
        return handled
    }

    private suspend fun process(seen: MarketMailOutbox, client: SqlClient): Boolean {
        val now = clock.now()
        val wasStale = seen.status == MailStatus.SENDING
        val row = service.claim(seen, now, client) ?: return false
        // An attempt that never reported back counts; the tenth one is the last (a stale claim of attempt 10 is exhausted).
        if (wasStale && seen.attempts >= MAX_ATTEMPTS) {
            service.finish(row, MailStatus.FAILED, seen.attempts, null, seen.lastError ?: "STALE_CLAIM", null, client)
            return true
        }
        val before = row.attempts - 1

        if (!mailEnabled()) return skip(row, before, "MAIL_DISABLED", client)
        if (!mailOptionsAvailable()) return skip(row, before, "HOST_TOO_OLD", client)
        if (row.createdAt < now - STALE_AFTER_MS) return skip(row, before, "STALE", client)

        val forced = isForced(row)
        val content = try {
            if (!forced && composition.isObsolete(row, client)) return skip(row, before, "OBSOLETE", client)
            composition.compose(row, client)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            service.finish(row, MailStatus.FAILED, row.attempts, null, "RENDER_ERROR: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(400)}", null, client)
            return true
        }

        val attachments = try {
            composition.attachments(row, client)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            MailAttachments(invoiceRenderFailed = true)
        }

        val message = OutboundMail(
            recipient = row.recipient,
            locale = row.locale,
            content = content,
            replyTo = config().mailReplyTo.takeIf { it.isNotBlank() },
            attachments = attachments.files
        )

        val result = try {
            withTimeout(sendTimeoutMs) { gateway.send(message) }
        } catch (e: TimeoutCancellationException) {
            return fail(row, "TimeoutCancellationException: no answer within $sendTimeoutMs ms", client)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return fail(row, "${t.javaClass.simpleName}: ${t.message.orEmpty()}", client)
        }

        when (result) {
            MailSendResult.SENT -> service.finish(
                row, MailStatus.SENT, row.attempts, null,
                if (attachments.invoiceRenderFailed) INVOICE_RENDER_FAILED else null, clock.now(), client
            )
            MailSendResult.DISABLED -> service.finish(row, MailStatus.SKIPPED, before, null, "MAIL_DISABLED", null, client)
        }
        return true
    }

    private suspend fun skip(row: MarketMailOutbox, attemptsBefore: Int, reason: String, client: SqlClient): Boolean {
        service.finish(row, MailStatus.SKIPPED, attemptsBefore, null, reason, null, client)
        return true
    }

    /** Retryable failure of the attempt that just ran (12 section 4.3.8). */
    private suspend fun fail(row: MarketMailOutbox, error: String, client: SqlClient): Boolean {
        when (val next = decideFailure(row.attempts, clock.now(), error, backoff, random)) {
            is Failure.Retry -> service.finish(row, MailStatus.PENDING, row.attempts, next.nextAttemptAt, next.error, null, client)
            is Failure.Exhausted -> service.finish(row, MailStatus.FAILED, row.attempts, null, next.error, null, client)
        }
        return true
    }

    private fun isForced(row: MarketMailOutbox): Boolean =
        runCatching { JsonObject(row.params.ifBlank { "{}" }).getBoolean("forced", false) }.getOrDefault(false)

    sealed class Failure {
        /** Back to `PENDING`, due at [nextAttemptAt]. */
        class Retry(val nextAttemptAt: Long, val error: String) : Failure()

        /** `attempts` reached [MAX_ATTEMPTS]: `FAILED`. */
        class Exhausted(val error: String) : Failure()
    }

    companion object {
        const val BATCH = 20
        const val MAX_ATTEMPTS = 10
        const val SEND_TIMEOUT_MS = 60_000L
        const val STALE_AFTER_MS = 7L * 24 * 3_600_000
        const val INVOICE_RENDER_FAILED = "INVOICE_RENDER_FAILED"

        /** Base 60 s, factor 2, jitter +-20 %, cap 6 h (12 section 4.3.8): about 1, 2, 4, ... 256 minutes. */
        val RETRY_BACKOFF = Backoff(baseMs = 60_000L, factor = 2.0, capMs = 6L * 3_600_000, jitter = 0.2)

        private val logger = LoggerFactory.getLogger(MailOutboxJob::class.java)

        /** The pure part of 12 section 4.3.8: what a failed attempt number [attempts] (counted from 1) does to the row. */
        fun decideFailure(attempts: Int, now: Long, error: String, backoff: Backoff = RETRY_BACKOFF, random: Random = Random.Default): Failure =
            if (attempts < MAX_ATTEMPTS) Failure.Retry(now + backoff.delayMs(attempts, random), error)
            else Failure.Exhausted(error)
    }
}
