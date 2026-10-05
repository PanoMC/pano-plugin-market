package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.time.Backoff
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.PaymentEventDirection
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.random.Random

/** What a panel replay did (11 section 4.3 IN-4). */
sealed interface ReplayResult {
    data object NotFound : ReplayResult

    /** Only `DEFERRED`, `FAILED` and `RECEIVED` older than 60 s are replayable: 409 `INVALID_STATE`. */
    class InvalidState(val status: PaymentEventStatus) : ReplayResult

    /** Somebody else (the retry job, another replay) took the row first. */
    data object Busy : ReplayResult

    class Done(val row: MarketPaymentEvent?) : ReplayResult
}

/**
 * What market does with an inbound request (02 section 7.3, normative); the route only adapts `RoutingContext`. Steps:
 * 1. resolve the attempt of a `NOTIFY` / `RETURN` from its token (unknown token or one of another provider: 404, nothing stored);
 * 2. store the raw request (`status = RECEIVED`, key `r:<uuid>`, headers and body verbatim) before any provider code runs, de-duplicating nothing;
 * 3. `handleInbound` (25 s, under the attempt lock for `NOTIFY` / `RETURN`): a throwable makes the row `FAILED` with `nextAttemptAt`, the answer 500
 *    (gateway retries) or 303 to the order page for a return, `ProviderException(CONFIGURATION)` answers 503;
 * 4. `verified == false`: `REJECTED`, events dropped, the provider's reply sent;
 * 5. a provider key: `PROCESSED` holder => this row `DUPLICATE`, nothing applied; in-flight holder (younger than 60 s) => `DUPLICATE` but the events
 *    still go through step 6; `FAILED` / `DEFERRED` / stale `RECEIVED` holder => `SUPERSEDED` and this row takes the key;
 * 6. the events in order ([PaymentEventApplier]); an infrastructure failure makes the row `FAILED`, the answer 500 instead of the provider's;
 * 7. `PROCESSED`, headers and body redacted in place, the provider's reply.
 *
 * Steps 3 to 7 can be run again on the stored raw request ([retry], [replay]): the retry job and the panel recover rows this way.
 * The provider's reply is sent verbatim, except for a `RETURN`: its answer is always the 303 to the order page (a browser must never be sent
 * anywhere a provider names; the one exception is the `STEP` hop of a gateway that needs an intermediate redirect, 02 section 3 `AttemptUrls.step`).
 */
class InboundDispatcher(
    private val store: InboundEventStore,
    private val attempts: InboundAttempts,
    private val providers: InboundProviders,
    private val applier: PaymentEventApplier,
    private val locks: AttemptLocks,
    private val clock: Clock,
    private val ids: Ids,
    /** `{base}` of the order page address, without the trailing slash. */
    private val baseUrl: () -> String,
    private val runtime: () -> MarketRuntime.State = { MarketRuntime.state },
    private val providerTimeoutMs: Long = PROVIDER_TIMEOUT_MS,
    private val backoff: Backoff = RETRY_BACKOFF,
    private val random: Random = Random.Default
) {
    /** The state of one processing run of one row. */
    private class Run(
        val id: Long,
        var eventKey: String,
        val createdAt: Long,
        val attempts: Int,
        /** The stored request, verbatim (what redaction rewrites when the row settles). */
        val headers: String?,
        val body: String?,
        val requestHash: String?
    )

    // ============================================================================================ a request arrives

    suspend fun handle(call: InboundCall): HttpReply {
        val state = runtime()

        // 00 section 8.9: not started yet / stopped: nothing is touched, the gateway retries, a browser lands on the store
        if (state == MarketRuntime.State.STOPPED || state == MarketRuntime.State.STARTING) return notReady(call)

        // 1. the attempt behind a notify / return token
        var attempt: MarketPayment? = null

        if (call.attemptToken != null) {
            attempt = try {
                attempts.byToken(call.attemptToken)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("the attempt behind a {} token of {} could not be read: {}", call.kind, call.providerId, t.javaClass.simpleName)

                return failureReply(call, null, 503)
            }

            if (attempt == null || attempt.providerId != call.providerId) return notFound()
        }

        val access = try {
            providers.resolve(call.providerId, attempt != null)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("provider {} could not be resolved: {}", call.providerId, t.javaClass.simpleName)

            return failureReply(call, attempt, 503)
        }

        if (access is ProviderAccess.Unknown) return notFound()

        val ready = access as? ProviderAccess.Ready
        val deferred = ready == null || state != MarketRuntime.State.READY

        // 2. the raw request is on disk before any provider code runs
        val now = clock.now()
        val redactor = ready?.redactor ?: Redactor()
        val headers = StoredRequestCodec.headersJson(call)
        val body = StoredRequestCodec.bodyText(call.body)
        val hash = InboundEventKey.requestHash(call.kind, call.channel, call.attemptToken, call.method, call.rawQuery, call.body)
        val key = InboundEventKey.received(ids.uuid())
        val row = MarketPaymentEvent(
            providerId = call.providerId, direction = PaymentEventDirection.IN, channel = call.kind.name, subChannel = subChannelOf(call), eventKey = key, requestHash = hash,
            paymentId = attempt?.id, orderId = attempt?.orderId, method = call.method.take(8),
            url = redactor.redactUrl(call.path + (call.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")).take(URL_MAX),
            headers = headers, body = body, remoteIp = call.remoteIp.take(REMOTE_IP_MAX),
            status = if (deferred) PaymentEventStatus.DEFERRED else PaymentEventStatus.RECEIVED, attempts = if (deferred) 0 else 1, createdAt = now, updatedAt = now
        )

        val id = try {
            store.insert(row)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // nothing may run on a request that is not stored: the gateway delivers again
            logger.error("an inbound {} request of {} could not be stored: {}", call.kind, call.providerId, t.javaClass.simpleName)

            return failureReply(call, attempt, 500)
        }

        // 02 section 11 / 00 section 8.9: a provider that is not there (or a degraded market) keeps the request for a replay
        if (ready == null || deferred) return deferredReply(call, attempt)

        return process(Run(id, key, now, 1, headers, body, hash), call, attempt, ready)
    }

    // ===================================================================================== steps 3 to 7, shared with a replay

    private suspend fun process(run: Run, call: InboundCall, attempt: MarketPayment?, access: ProviderAccess.Ready): HttpReply {
        val started = clock.now()

        // 3. the provider
        val result: InboundResult = try {
            // a NOTIFY / RETURN reads its attempt again once it holds the lock: the provider must see the attempt as another call left it, not as it was
            // before this call waited
            suspend fun invoke(current: MarketPayment?): InboundResult {
                val publicId = current?.let { attempts.publicIdOf(it.orderId) }.orEmpty()
                val request = PaymentInboundRequest(inboundRequest(call), current?.let { attempts.view(it, publicId) }, call.outcome, call.step)
                val context = access.context(current?.testMode)

                return withTimeout(providerTimeoutMs) { access.provider.handleInbound(context, request) }
            }

            if (attempt != null && call.kind != InboundKind.WEBHOOK) locks.with(attempt.id) { invoke(attempts.byId(attempt.id) ?: attempt) } else invoke(attempt)
        } catch (e: TimeoutCancellationException) {
            return failed(run, call, attempt, access, "TIMEOUT: the provider did not answer in ${providerTimeoutMs / 1000} s", 500, started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            return failed(run, call, attempt, access, "${e.code}: ${e.message}", if (e.code == ProviderErrorCode.CONFIGURATION) 503 else 500, started)
        } catch (t: Throwable) {
            return failed(run, call, attempt, access, "${t.javaClass.simpleName}: ${t.message.orEmpty()}", 500, started)
        }

        // 4. not authentic: the events are dropped (11 section 4.3 IN-1), the request is kept for the audit trail under its own key
        if (!result.verified) {
            settle(run, access, PaymentEventStatus.REJECTED, verified = false, applied = null, error = result.rejectReason, reply = result.reply, started = started, maxBody = REJECTED_BODY_MAX)

            return finalReply(call, result.reply, attempt)
        }

        if (call.kind != InboundKind.RETURN) {
            try {
                store.touchLastInbound(call.providerId, clock.now())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("lastInboundAt of {} could not be written: {}", call.providerId, t.javaClass.simpleName)
            }
        }

        // 5. the provider's delivery key
        var inFlight = false
        val eventKey = result.eventKey

        if (eventKey != null) {
            val claim = try {
                claimKey(run, call.providerId, eventKey, access.redactor)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return failed(run, call, attempt, access, "${t.javaClass.simpleName}: ${t.message.orEmpty()}", 500, started)
            }

            when (claim) {
                Claim.OWNED -> Unit

                Claim.PROCESSED -> {
                    // the events of this delivery were applied by the first copy: not again, but the reply is the provider's, never an error
                    settle(run, access, PaymentEventStatus.DUPLICATE, verified = true, applied = null, error = null, reply = result.reply, started = started)

                    return finalReply(call, result.reply, attempt)
                }

                Claim.IN_FLIGHT -> inFlight = true
            }
        }

        // 6. the events, in order, each under the order lock; an unknown target is skipped, a failure of the infrastructure ends the run
        val applied = try {
            applier.apply(access, result.events, InboundEventContext(run.id, call.providerId, eventKey, run.requestHash, call.receivedAt))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val text = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"

            if (inFlight) {
                // the first copy of this delivery still carries the fact (and the gateway delivers again on the 500): this row only records the failure
                settle(run, access, PaymentEventStatus.DUPLICATE, verified = true, applied = null, error = access.redactor.redact(text).take(ERROR_MAX), reply = null, started = started)

                return failureReply(call, attempt, 500)
            }

            return failed(run, call, attempt, access, text, 500, started)
        }

        // 7. settled: headers and body are redacted in place, the reply is the provider's
        settle(
            run, access, if (inFlight) PaymentEventStatus.DUPLICATE else PaymentEventStatus.PROCESSED, verified = true, applied = applied, error = null, reply = result.reply, started = started
        )

        return finalReply(call, result.reply, attempt ?: applied.first)
    }

    private enum class Claim { OWNED, PROCESSED, IN_FLIGHT }

    /** Step 5: who holds the key of this delivery (`uq_event`). */
    private suspend fun claimKey(run: Run, providerId: String, providerKey: String, redactor: Redactor): Claim {
        val key = InboundEventKey.provider(providerKey)

        // a re-run of a row that already holds it
        if (run.eventKey == key) return Claim.OWNED

        repeat(MAX_CLAIM_ROUNDS) {
            if (store.claimKey(run.id, key, clock.now())) {
                run.eventKey = key

                return Claim.OWNED
            }

            val holder = store.byKey(providerId, key) ?: return@repeat

            if (holder.id == run.id) return Claim.OWNED

            val now = clock.now()

            when {
                holder.status == PaymentEventStatus.PROCESSED -> {
                    store.bumpDuplicates(holder.id, now)

                    return Claim.PROCESSED
                }

                holder.status == PaymentEventStatus.RECEIVED && now - holder.createdAt < STALE_RECEIVED_MS -> {
                    store.bumpDuplicates(holder.id, now)

                    return Claim.IN_FLIGHT
                }

                else -> {
                    // FAILED, DEFERRED, a crashed RECEIVED (or a status that should not hold a key): this request takes the key over, so that a redelivery
                    // after a failure is applied, never swallowed. Zero rows: the row changed, read it again.
                    val headers = StoredRequestCodec.settledHeaders(holder.headers, redactor)
                    val body = StoredRequestCodec.settledBody(holder.body, holder.headers, redactor, SETTLED_BODY_MAX)

                    store.supersede(holder, headers, body, now)
                }
            }
        }

        throw IllegalStateException("the event key could not be claimed after $MAX_CLAIM_ROUNDS rounds")
    }

    /** A step 3 / 5 / 6 failure: the row is `FAILED` with its next retry, the gateway is told to deliver again. */
    private suspend fun failed(run: Run, call: InboundCall, attempt: MarketPayment?, access: ProviderAccess.Ready, error: String, status: Int, started: Long): HttpReply {
        val text = access.redactor.redact(error).take(ERROR_MAX)
        val now = clock.now()
        val next = if (run.attempts >= MAX_ATTEMPTS) null else now + backoff.delayMs(run.attempts, random)

        try {
            store.settle(
                run.id,
                Settlement(
                    PaymentEventStatus.FAILED, verified = null, eventTypes = null, paymentId = null, orderId = null, refundId = null, subscriptionId = null, responseStatus = status,
                    error = text, durationMs = elapsed(started), processedAt = null, nextAttemptAt = next, now = now
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the row stays RECEIVED: the retry job finds it once it is a minute old
            logger.error("the failure of inbound event {} could not be stored: {}", run.id, t.javaClass.simpleName)
        }

        logger.warn("inbound event {} of {} failed (run {}): {}", run.id, call.providerId, run.attempts, text.take(160))

        return failureReply(call, attempt, status)
    }

    private suspend fun settle(
        run: Run, access: ProviderAccess.Ready, status: PaymentEventStatus, verified: Boolean, applied: AppliedEvents?, error: String?, reply: HttpReply?, started: Long,
        maxBody: Int = SETTLED_BODY_MAX
    ) {
        val now = clock.now()
        val first = applied?.first

        try {
            store.settle(
                run.id,
                Settlement(
                    status, verified = verified, eventTypes = applied?.types, paymentId = first?.id, orderId = first?.orderId, refundId = null, subscriptionId = null,
                    responseStatus = reply?.status, error = error?.take(ERROR_MAX), durationMs = elapsed(started), processedAt = now, nextAttemptAt = null, now = now,
                    raw = RawRewrite(
                        StoredRequestCodec.settledHeaders(run.headers, access.redactor), StoredRequestCodec.settledBody(run.body, run.headers, access.redactor, maxBody)
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the events are applied (idempotent) and the reply is still right; a row left RECEIVED is re-run by the retry job and ends the same way
            logger.error("the outcome of inbound event {} could not be stored: {}", run.id, t.javaClass.simpleName)
        }
    }

    private fun elapsed(started: Long): Int = (clock.now() - started).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    // ================================================================================================ retry and replay

    /** A request can be run again when it is `DEFERRED`, `FAILED`, or `RECEIVED` for more than 60 s (a crashed run). */
    fun isReplayable(row: MarketPaymentEvent, now: Long = clock.now()): Boolean =
        row.direction == PaymentEventDirection.IN && (
            row.status == PaymentEventStatus.DEFERRED || row.status == PaymentEventStatus.FAILED ||
                (row.status == PaymentEventStatus.RECEIVED && now - row.createdAt >= STALE_RECEIVED_MS)
            )

    /** Claims one run of [row] and runs steps 3 to 7 again on its stored request; `false` when somebody else claimed it first. */
    suspend fun retry(row: MarketPaymentEvent): Boolean {
        if (!store.claimRetry(row, clock.now(), LEASE_MS)) return false

        rerun(store.get(row.id) ?: return false)

        return true
    }

    /** The panel's "replay" (11 section 4.3 IN-4): only a replayable row; the signature window is not applied again (`receivedAt` is the original time). */
    suspend fun replay(eventId: Long): ReplayResult {
        val row = store.get(eventId)?.takeIf { it.direction == PaymentEventDirection.IN } ?: return ReplayResult.NotFound

        if (!isReplayable(row)) return ReplayResult.InvalidState(row.status)

        if (!retry(row)) return ReplayResult.Busy

        return ReplayResult.Done(store.get(eventId))
    }

    private suspend fun rerun(row: MarketPaymentEvent) {
        val kind = runCatching { InboundKind.valueOf(row.channel) }.getOrNull()

        if (kind == null) {
            terminal(row, "the stored request has the unknown kind '${row.channel}'")

            return
        }

        val stored = StoredRequestCodec.parse(row.headers, row.body)
        val sub = row.subChannel
        val step = if (kind == InboundKind.RETURN && sub != null && sub.startsWith(STEP_PREFIX)) sub.removePrefix(STEP_PREFIX) else null
        val outcome = when {
            kind != InboundKind.RETURN -> null
            step != null -> ReturnOutcome.STEP
            else -> runCatching { ReturnOutcome.valueOf(sub.orEmpty().uppercase()) }.getOrNull() ?: ReturnOutcome.RESULT
        }
        val attempt = if (kind == InboundKind.WEBHOOK) null else row.paymentId?.let { attempts.byId(it) }

        if (kind != InboundKind.WEBHOOK && attempt == null) {
            terminal(row, "the attempt of the stored request is gone")

            return
        }

        val access = providers.resolve(row.providerId, attempt != null)

        if (access !is ProviderAccess.Ready) {
            // the provider is not there (any more): the request waits for it, a human replays it
            store.settle(
                row.id,
                Settlement(
                    PaymentEventStatus.DEFERRED, null, null, null, null, null, null, 503, "the provider is not available", null, null, null, clock.now()
                )
            )

            return
        }

        val call = InboundCall(
            kind, row.providerId, if (kind == InboundKind.RETURN) "default" else sub ?: "default", attempt?.token, outcome, step, row.method ?: "POST", row.url.orEmpty(), stored.rawQuery,
            queryOf(stored.rawQuery), stored.headers, stored.headers["content-type"]?.firstOrNull(), stored.body, stored.form, row.remoteIp.orEmpty(), row.createdAt
        )

        process(Run(row.id, row.eventKey, row.createdAt, row.attempts, row.headers, row.body, row.requestHash), call, attempt, access)
    }

    /** A stored request that cannot be run again at all: `FAILED` without a schedule (the panel can still replay it once the cause is fixed). */
    private suspend fun terminal(row: MarketPaymentEvent, reason: String) {
        store.settle(row.id, Settlement(PaymentEventStatus.FAILED, null, null, null, null, null, null, null, reason.take(ERROR_MAX), null, null, null, clock.now()))
    }

    // ===================================================================================================== the answers

    private fun inboundRequest(call: InboundCall): InboundRequest =
        InboundRequest(call.kind, call.channel, call.method, call.rawQuery, call.query, call.headers, call.contentType, call.body, call.remoteIp, call.receivedAt).also {
            it.formAttributes = call.formAttributes
        }

    private fun notFound(): HttpReply = HttpReply(404, null, ByteArray(0))

    /** Not started / stopped: the gateway retries, the browser goes to the store (the order cannot be looked up). */
    private fun notReady(call: InboundCall): HttpReply =
        if (call.kind == InboundKind.RETURN) HttpReply.redirect("${baseUrl()}/store") else HttpReply.retryLater(503)

    private suspend fun deferredReply(call: InboundCall, attempt: MarketPayment?): HttpReply =
        if (call.kind == InboundKind.RETURN) orderPage(attempt, call.outcome) else HttpReply.retryLater(503)

    private suspend fun failureReply(call: InboundCall, attempt: MarketPayment?, status: Int): HttpReply =
        if (call.kind == InboundKind.RETURN) orderPage(attempt, call.outcome) else HttpReply.retryLater(status)

    private suspend fun finalReply(call: InboundCall, provider: HttpReply, attempt: MarketPayment?): HttpReply = when {
        // the browser only ever lands on the order page; the outcome is an untrusted hint for the UI (never the access token)
        call.kind == InboundKind.RETURN && call.outcome != ReturnOutcome.STEP -> orderPage(attempt, call.outcome)

        call.kind == InboundKind.RETURN -> stepReply(provider, attempt)

        provider.orderPage -> orderPage(attempt, null)

        else -> sanitized(provider)
    }

    /** The intermediate hop of a gateway (02 section 3 `AttemptUrls.step`): the provider's own redirect to an absolute http(s) address, anything else goes to the order page. */
    private suspend fun stepReply(provider: HttpReply, attempt: MarketPayment?): HttpReply {
        val location = provider.headers.entries.firstOrNull { it.key.equals("location", ignoreCase = true) }?.value

        if (provider.orderPage || provider.status !in 300..399 || location == null || !ABSOLUTE_URL.matches(location)) return orderPage(attempt, null)

        return sanitized(provider)
    }

    private suspend fun orderPage(attempt: MarketPayment?, outcome: ReturnOutcome?): HttpReply {
        val base = baseUrl().trimEnd('/')
        val publicId = attempt?.let {
            try {
                attempts.publicIdOf(it.orderId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
        }
        val hint = when (outcome) {
            ReturnOutcome.SUCCESS -> "success"
            ReturnOutcome.CANCEL -> "cancel"
            ReturnOutcome.PENDING -> "pending"
            else -> null
        }

        return if (publicId.isNullOrEmpty()) HttpReply.redirect("$base/store")
        else HttpReply.redirect("$base/store/order/$publicId" + (hint?.let { "?return=$it" } ?: ""))
    }

    /** The provider's reply, minus the headers that belong to the connection or would set state on the site's origin. */
    private fun sanitized(reply: HttpReply): HttpReply {
        val kept = reply.headers.filterKeys { it.lowercase() !in DROPPED_HEADERS }

        if (kept.size == reply.headers.size) return reply

        return HttpReply(reply.status, reply.contentType, reply.body).also { it.headers = kept }
    }

    private fun subChannelOf(call: InboundCall): String? = when {
        call.kind == InboundKind.RETURN -> if (call.outcome == ReturnOutcome.STEP) "$STEP_PREFIX${call.step}" else call.outcome?.name?.lowercase()
        call.channel == DEFAULT_CHANNEL -> null
        else -> call.channel
    }?.take(SUB_CHANNEL_MAX)

    private fun queryOf(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()

        val out = LinkedHashMap<String, MutableList<String>>()

        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue

            val eq = pair.indexOf('=')
            val name = runCatching { java.net.URLDecoder.decode(if (eq < 0) pair else pair.substring(0, eq), "UTF-8") }.getOrDefault(pair)
            val value = if (eq < 0) "" else runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))

            out.getOrPut(name) { ArrayList() }.add(value)
        }

        return out
    }

    companion object {
        /** `handleInbound` deadline (02 section 10 guarantee 9). */
        const val PROVIDER_TIMEOUT_MS = 25_000L

        /** A `RECEIVED` row younger than this is "in flight"; older is a crashed run (02 section 7.3 step 5). */
        const val STALE_RECEIVED_MS = 60_000L

        /** At most 10 processing runs per row (01 section 6.3 `attempts`). */
        const val MAX_ATTEMPTS = 10

        /** A claimed retry holds the row this long before another runner may take it. */
        const val LEASE_MS = 60_000L

        /** Backoff 1 min, doubling, capped at 6 h (02 section 7.3 step 7). */
        val RETRY_BACKOFF = Backoff(baseMs = 60_000L, factor = 2.0, capMs = 6L * 3_600_000L, jitter = 0.1)

        const val SETTLED_BODY_MAX = 64 * 1024
        const val REJECTED_BODY_MAX = 2048
        const val ERROR_MAX = 512
        const val URL_MAX = 1024
        const val REMOTE_IP_MAX = 45
        const val SUB_CHANNEL_MAX = 64
        private const val MAX_CLAIM_ROUNDS = 8
        private const val STEP_PREFIX = "step:"
        private const val DEFAULT_CHANNEL = "default"
        private val ABSOLUTE_URL = Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE)
        private val DROPPED_HEADERS = setOf("set-cookie", "set-cookie2", "content-length", "transfer-encoding", "connection", "keep-alive", "host", "upgrade")
        private val logger = LoggerFactory.getLogger(InboundDispatcher::class.java)
    }
}
