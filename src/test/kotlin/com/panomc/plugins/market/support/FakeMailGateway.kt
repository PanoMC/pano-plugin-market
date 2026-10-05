package com.panomc.plugins.market.support

import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.mail.MailAttachments
import com.panomc.plugins.market.mail.MailComposition
import com.panomc.plugins.market.mail.MailContent
import com.panomc.plugins.market.mail.MailGateway
import com.panomc.plugins.market.mail.MailSendResult
import com.panomc.plugins.market.mail.OutboundMail
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double of the outbound mail seam (17 section 4 S6, 5.5): records every call, and the next calls can be scripted to
 * fail, to answer `DISABLED`, to take time or to never answer.
 *
 * [calls] holds every attempt (also the failed ones), [sent] only the messages the gateway accepted.
 */
class FakeMailGateway : MailGateway {
    private sealed class Step {
        class Fail(val error: Throwable) : Step()
        class Answer(val result: MailSendResult) : Step()
    }

    val calls = CopyOnWriteArrayList<OutboundMail>()
    val sent = CopyOnWriteArrayList<OutboundMail>()
    private val script = java.util.concurrent.ConcurrentLinkedQueue<Step>()
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var alwaysFail: Throwable? = null

    /** Every call waits this long (real time) before it answers. */
    @Volatile
    var latencyMs: Long = 0

    /** The call never answers (a hung SMTP connection); only a timeout or a cancel ends it. */
    @Volatile
    var hang: Boolean = false

    /** The most calls that were inside [send] at the same moment. */
    @Volatile
    var maxConcurrent: Int = 0
        private set

    /** The next [times] calls throw [error]. */
    fun failNext(times: Int = 1, error: Throwable = IOException("smtp unreachable")) {
        repeat(times) { script.add(Step.Fail(error)) }
    }

    /** Every call from now on throws [error] (after the scripted steps). */
    fun failAlways(error: Throwable = IOException("smtp unreachable")) {
        alwaysFail = error
    }

    /** The next call answers [result] (`DISABLED` = the platform switch is off). */
    fun answerNext(result: MailSendResult) {
        script.add(Step.Answer(result))
    }

    fun recover() {
        alwaysFail = null
        script.clear()
        hang = false
    }

    override suspend fun send(message: OutboundMail): MailSendResult {
        val now = inFlight.incrementAndGet()
        synchronized(this) { if (now > maxConcurrent) maxConcurrent = now }
        try {
            calls.add(message)
            if (hang) awaitCancellation()
            if (latencyMs > 0) delay(latencyMs)
            when (val step = script.poll()) {
                is Step.Fail -> throw step.error
                is Step.Answer -> {
                    if (step.result == MailSendResult.SENT) sent.add(message)
                    return step.result
                }
                null -> Unit
            }
            alwaysFail?.let { throw it }
            sent.add(message)
            return MailSendResult.SENT
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

/**
 * Test double of the content port: every row composes to a small content built from the row; rows can be declared
 * obsolete, compose or attachments can fail.
 */
class FakeMailComposition : MailComposition {
    val obsoleteIds: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val composeFailures: MutableMap<Long, Throwable> = java.util.concurrent.ConcurrentHashMap()
    val attachmentsById: MutableMap<Long, MailAttachments> = java.util.concurrent.ConcurrentHashMap()
    val obsoleteChecks = CopyOnWriteArrayList<Long>()

    override suspend fun isObsolete(row: MarketMailOutbox, sqlClient: SqlClient): Boolean {
        obsoleteChecks.add(row.id)
        return row.id in obsoleteIds
    }

    override suspend fun compose(row: MarketMailOutbox, sqlClient: SqlClient): MailContent {
        composeFailures[row.id]?.let { throw it }
        return MailContent(subject = "Subject ${row.kind.name}", preheader = "pre", heading = "Heading ${row.kind.name}", paragraphs = listOf("Row ${row.id}"))
    }

    override suspend fun attachments(row: MarketMailOutbox, sqlClient: SqlClient): MailAttachments =
        attachmentsById[row.id] ?: MailAttachments.NONE
}
