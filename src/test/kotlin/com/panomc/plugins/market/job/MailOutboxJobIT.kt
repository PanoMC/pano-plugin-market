package com.panomc.plugins.market.job

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.time.Backoff
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.mail.MailAttachment
import com.panomc.plugins.market.mail.MailAttachments
import com.panomc.plugins.market.mail.MailSendResult
import com.panomc.plugins.market.mail.UnavailableMailGateway
import com.panomc.plugins.market.mail.UnwiredMailComposition
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.support.FakeMailComposition
import com.panomc.plugins.market.support.FakeMailGateway
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The mail outbox on a real MariaDB (MK-141; 12 sections 4.2 and 4.3, T-DB-4 and T-DB-5): the enqueue half
 * ([MailOutboxService]) and the sending half ([MailOutboxJob]) with a [FakeMailGateway], a scripted composition and a
 * `FakeClock`. Rows are created with the real `enqueue`, state is read from the table.
 */
class MailOutboxJobIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var gateway: FakeMailGateway
    private lateinit var composition: FakeMailComposition
    private var config = MarketConfig()
    private var mailEnabled = true
    private var optionsAvailable = true

    private lateinit var service: MailOutboxService
    private lateinit var job: MailOutboxJob

    override val poolSize: Int = 24

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        gateway = FakeMailGateway()
        composition = FakeMailComposition()
        config = MarketConfig()
        mailEnabled = true
        optionsAvailable = true
        service = MailOutboxService({ config }, w.clock, w.mailOutbox, w.orderEvents)
        job = newJob()
    }

    @AfterEach
    fun resetRuntime() {
        MarketRuntime.reset()
    }

    private fun newJob(sendTimeoutMs: Long = MailOutboxJob.SEND_TIMEOUT_MS) = MailOutboxJob(
        config = { config },
        clock = w.clock,
        service = service,
        gateway = gateway,
        composition = composition,
        sqlClient = { pool },
        mailEnabled = { mailEnabled },
        mailOptionsAvailable = { optionsAvailable },
        random = Random(7),
        sendTimeoutMs = sendTimeoutMs
    )

    private suspend fun enqueue(
        kind: MailKind = MailKind.ORDER_CONFIRMATION,
        refType: MailRefType = MailRefType.ORDER,
        refId: Long = 1,
        refKey: String = "",
        orderId: Long? = refId,
        userId: Long? = null,
        recipient: String = "buyer@example.com",
        locale: String = "en-US",
        params: JsonObject = JsonObject(),
        force: Boolean = false
    ): Long? = service.enqueue(pool, kind, refType, refId, refKey, orderId, userId, recipient, locale, params, force)

    private suspend fun row(id: Long): MarketMailOutbox = w.mailOutbox.getById(id, pool)!!

    private suspend fun setRow(id: Long, vararg values: Pair<String, Any?>) =
        sql("UPDATE `pano_market_mail_outbox` SET ${values.joinToString(", ") { "`${it.first}` = ?" }} WHERE `id` = ?", *values.map { it.second }.toTypedArray(), id)

    // ------------------------------------------------------------------------------------------------ enqueue

    @Test
    fun `enqueue writes one pending row due now with the recipient lower-cased and the locale kept`(): Unit = runBlocking {
        val id = enqueue(recipient = "  Buyer@Example.COM ", locale = "tr", userId = 7, params = JsonObject().put("expiresAt", 5))!!
        val r = row(id)
        assertEquals(MailKind.ORDER_CONFIRMATION, r.kind)
        assertEquals(MailRefType.ORDER, r.refType)
        assertEquals(1L, r.refId)
        assertEquals("", r.refKey)
        assertEquals(1L, r.orderId)
        assertEquals(7L, r.userId)
        assertEquals("buyer@example.com", r.recipient)
        assertEquals("tr", r.locale)
        assertEquals(5, JsonObject(r.params).getInteger("expiresAt"))
        assertEquals(MailStatus.PENDING, r.status)
        assertEquals(0, r.attempts)
        assertEquals(w.clock.now(), r.nextAttemptAt)
        assertNull(r.claimedUntil)
        assertNull(r.lastError)
        assertNull(r.sentAt)
    }

    @Test
    fun `one row per kind refType refId refKey and recipient`(): Unit = runBlocking {
        val first = enqueue()!!
        assertEquals(first, enqueue())
        assertEquals(first, enqueue(recipient = "BUYER@example.com"))
        assertEquals(1, count("market_mail_outbox"))

        val others = listOf(
            enqueue(kind = MailKind.ORDER_RECEIVED),
            enqueue(refType = MailRefType.PAYMENT),
            enqueue(refId = 2),
            enqueue(refKey = "1700000000000"),
            enqueue(recipient = "other@example.com")
        )
        assertTrue(others.all { it != null && it != first })
        assertEquals(5, others.toSet().size)
        assertEquals(6, count("market_mail_outbox"))
    }

    @Test
    fun `a duplicate changes nothing on the existing row and writes no second timeline event`(): Unit = runBlocking {
        val id = enqueue(params = JsonObject().put("a", 1))!!
        setRow(id, "status" to "SENT", "attempts" to 1, "sentAt" to 5)
        assertEquals(id, enqueue(params = JsonObject().put("a", 2)))
        val r = row(id)
        assertEquals(MailStatus.SENT, r.status)
        assertEquals(1, r.attempts)
        assertEquals(1, JsonObject(r.params).getInteger("a"))
        assertEquals(1, w.orderEvents.getByOrderId(1, pool).count { it.type == OrderEventType.MAIL_QUEUED })
    }

    @Test
    fun `a queued mail of an order writes MAIL_QUEUED with kind and outbox id, a mail without order writes none`(): Unit = runBlocking {
        val id = enqueue(kind = MailKind.ORDER_REFUNDED, refType = MailRefType.REFUND, refId = 9, orderId = 4)!!
        val event = w.orderEvents.getByOrderId(4, pool).single()
        assertEquals(OrderEventType.MAIL_QUEUED, event.type)
        val data = JsonObject(event.data)
        assertEquals("ORDER_REFUNDED", data.getString("kind"))
        assertEquals(id, data.getLong("outboxId"))

        enqueue(kind = MailKind.EXPIRY_REMINDER, refType = MailRefType.ENTITLEMENT, refId = 3, orderId = null)!!
        assertEquals(1, count("market_order_event"))
    }

    @Test
    fun `enqueue joins the transaction of the caller, a rollback leaves no row and no event`(): Unit = runBlocking {
        val db = marketDb(clock = w.clock)

        val committed = db.tx { conn -> service.enqueue(conn, MailKind.ORDER_CONFIRMATION, MailRefType.ORDER, 1, "", 1, null, "a@example.com", "en-US") }
        assertNotNull(committed)
        assertEquals(1, count("market_mail_outbox"))

        val failure = runCatching {
            db.tx { conn ->
                service.enqueue(conn, MailKind.ORDER_CONFIRMATION, MailRefType.ORDER, 2, "", 2, null, "b@example.com", "en-US")
                // not visible to anybody else before the commit
                assertEquals(1, count("market_mail_outbox"))
                error("the transition failed after the mail was queued")
            }
        }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(1, count("market_mail_outbox"))
        assertEquals(1, count("market_order_event"))
    }

    @Test
    fun `a duplicate inside a transaction does not poison it`(): Unit = runBlocking {
        val first = enqueue()!!
        val db = marketDb(clock = w.clock)
        val ids = db.tx { conn ->
            val dup = service.enqueue(conn, MailKind.ORDER_CONFIRMATION, MailRefType.ORDER, 1, "", 1, null, "buyer@example.com", "en-US")
            val next = service.enqueue(conn, MailKind.ORDER_RECEIVED, MailRefType.ORDER, 1, "", 1, null, "buyer@example.com", "en-US")
            listOf(dup, next)
        }
        assertEquals(first, ids[0])
        assertNotNull(ids[1])
        assertEquals(2, count("market_mail_outbox"))
    }

    @Test
    fun `parallel enqueues of one mail make one row and agree on its id`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val results = Race.run(12) { enqueue(refId = 100L + round) }
            assertTrue(results.all { it.isSuccess }, "$results")
            assertEquals(1, results.map { it.getOrNull() }.toSet().size)
            assertEquals(1, count("market_mail_outbox", "`refId` = ?", 100L + round))
        }
    }

    @Test
    fun `a bad recipient queues nothing and leaves a timeline note`(): Unit = runBlocking {
        for (bad in listOf("no-at-sign", "a@b@c.com", "has space@example.com", "x@y\r\nBcc: evil@example.com", "@example.com", "a@", "${"x".repeat(65)}@example.com")) {
            assertNull(enqueue(recipient = bad), bad)
        }
        assertEquals(0, count("market_mail_outbox"))
        val notes = w.orderEvents.getByOrderId(1, pool)
        assertEquals(7, notes.size)
        assertTrue(notes.all { it.type == OrderEventType.NOTE && it.message == "MAIL_NOT_QUEUED:ORDER_CONFIRMATION:INVALID_RECIPIENT" })

        assertNull(enqueue(recipient = "  ", orderId = 2))
        assertEquals("MAIL_NOT_QUEUED:ORDER_CONFIRMATION:NO_RECIPIENT", w.orderEvents.getByOrderId(2, pool).single().message)
        assertNull(enqueue(recipient = "bad", orderId = null))
        assertEquals(0, count("market_mail_outbox"))
    }

    @Test
    fun `mailDisabledKinds and sendEmailAfterPurchase suppress only what 12 4_2 says, force bypasses both`(): Unit = runBlocking {
        config = MarketConfig(mailDisabledKinds = listOf("ORDER_RECEIVED"), sendEmailAfterPurchase = false)
        for (kind in MailOutboxService.ORDER_MAILS) assertNull(enqueue(kind = kind, refId = 1), kind.name)
        assertEquals(0, count("market_mail_outbox"))
        assertEquals(0, count("market_order_event"))

        // service mails are never suppressed by the purchase-mail switch
        for ((i, kind) in listOf(MailKind.BANK_TRANSFER_INSTRUCTIONS, MailKind.EXPIRY_REMINDER, MailKind.SUBSCRIPTION_REMINDER, MailKind.SUBSCRIPTION_PAYMENT_FAILED, MailKind.SUBSCRIPTION_CANCELLED, MailKind.SUBSCRIPTION_ENDED).withIndex()) {
            assertNotNull(enqueue(kind = kind, refId = 10L + i), kind.name)
        }
        assertEquals(6, count("market_mail_outbox"))

        config = MarketConfig(mailDisabledKinds = listOf("EXPIRY_REMINDER", "ORDER_RECEIVED"))
        assertNull(enqueue(kind = MailKind.EXPIRY_REMINDER, refId = 77))
        assertNull(enqueue(kind = MailKind.ORDER_RECEIVED, refId = 77))
        assertNotNull(enqueue(kind = MailKind.ORDER_CONFIRMATION, refId = 77))

        val forced = enqueue(kind = MailKind.ORDER_RECEIVED, refId = 77, force = true)
        assertNotNull(forced)
        assertEquals(true, JsonObject(row(forced!!).params).getBoolean("forced"))
    }

    @Test
    fun `force resets a finished row, merges the params and leaves a row that is being sent alone`(): Unit = runBlocking {
        val id = enqueue(params = JsonObject().put("keep", 1), locale = "en-US")!!
        setRow(id, "status" to "FAILED", "attempts" to 10, "nextAttemptAt" to null, "lastError" to "boom")

        w.clock.advance(5_000)
        assertEquals(id, enqueue(params = JsonObject().put("x", 2), locale = "tr", force = true))
        val r = row(id)
        assertEquals(MailStatus.PENDING, r.status)
        assertEquals(0, r.attempts)
        assertEquals(w.clock.now(), r.nextAttemptAt)
        assertNull(r.lastError)
        assertEquals("tr", r.locale)
        val p = JsonObject(r.params)
        assertEquals(1, p.getInteger("keep"))
        assertEquals(2, p.getInteger("x"))
        assertEquals(true, p.getBoolean("forced"))
        assertEquals(2, w.orderEvents.getByOrderId(1, pool).count { it.type == OrderEventType.MAIL_QUEUED })

        setRow(id, "status" to "SENDING", "claimedUntil" to w.clock.now() + 1000)
        assertNull(enqueue(force = true))
        assertEquals(MailStatus.SENDING, row(id).status)
    }

    // ------------------------------------------------------------------------------------------------ sending

    @Test
    fun `a due row is sent once with the recipient, locale, subject and the configured reply-to`(): Unit = runBlocking {
        config = MarketConfig(mailReplyTo = "support@shop.example")
        val id = enqueue(recipient = "Guest@Example.com", locale = "tr", userId = null)!!

        assertEquals(1, job.runOnce())

        val r = row(id)
        assertEquals(MailStatus.SENT, r.status)
        assertEquals(1, r.attempts)
        assertEquals(w.clock.now(), r.sentAt)
        assertNull(r.lastError)
        assertNull(r.claimedUntil)

        val m = gateway.sent.single()
        assertEquals("guest@example.com", m.recipient)
        assertEquals("tr", m.locale)
        assertEquals("Subject ORDER_CONFIRMATION", m.content.subject)
        assertEquals("support@shop.example", m.replyTo)
        assertTrue(m.attachments.isEmpty())

        assertEquals(0, job.runOnce())
        assertEquals(1, gateway.calls.size)
    }

    @Test
    fun `reply-to is left out when none is configured and rows not yet due are left alone`(): Unit = runBlocking {
        val id = enqueue()!!
        setRow(id, "nextAttemptAt" to w.clock.now() + 60_000)
        assertEquals(0, job.runOnce())
        assertEquals(MailStatus.PENDING, row(id).status)

        w.clock.advance(60_000)
        assertEquals(1, job.runOnce())
        assertNull(gateway.sent.single().replyTo)
    }

    @Test
    fun `a host without MailOptions skips the rows as HOST_TOO_OLD and the tick survives`(): Unit = runBlocking {
        optionsAvailable = false
        val a = enqueue(refId = 1)!!
        val b = enqueue(refId = 2)!!

        assertEquals(2, job.runOnce())

        for (id in listOf(a, b)) {
            val r = row(id)
            assertEquals(MailStatus.SKIPPED, r.status)
            assertEquals("HOST_TOO_OLD", r.lastError)
            assertEquals(0, r.attempts)
            assertNull(r.claimedUntil)
        }
        assertEquals(0, gateway.calls.size)
        assertEquals(0, job.runOnce())
    }

    @Test
    fun `the default host probe is the MarketRuntime capability and starts closed`(): Unit = runBlocking {
        MarketRuntime.reset()
        val closed = MailOutboxJob(
            config = { config }, clock = w.clock, service = service, gateway = gateway, composition = composition,
            sqlClient = { pool }, mailEnabled = { true }
        )
        val id = enqueue()!!
        assertEquals(1, closed.runOnce())
        assertEquals("HOST_TOO_OLD", row(id).lastError)
        assertEquals(0, gateway.calls.size)
    }

    @Test
    fun `the platform mail switch off skips the rows as MAIL_DISABLED, so does a DISABLED answer`(): Unit = runBlocking {
        mailEnabled = false
        val a = enqueue(refId = 1)!!
        assertEquals(1, job.runOnce())
        assertEquals(MailStatus.SKIPPED, row(a).status)
        assertEquals("MAIL_DISABLED", row(a).lastError)
        assertEquals(0, gateway.calls.size)

        mailEnabled = true
        val b = enqueue(refId = 2)!!
        gateway.answerNext(MailSendResult.DISABLED)
        assertEquals(1, job.runOnce())
        assertEquals(MailStatus.SKIPPED, row(b).status)
        assertEquals("MAIL_DISABLED", row(b).lastError)
        assertEquals(0, gateway.sent.size)
    }

    @Test
    fun `a failing gateway retries with doubling backoff and finally sends`(): Unit = runBlocking {
        val id = enqueue()!!
        gateway.failNext(2)

        assertEquals(1, job.runOnce())
        var r = row(id)
        assertEquals(MailStatus.PENDING, r.status)
        assertEquals(1, r.attempts)
        assertTrue(r.lastError!!.startsWith("IOException: smtp unreachable"), r.lastError)
        assertNull(r.claimedUntil)
        val first = r.nextAttemptAt!! - w.clock.now()
        assertTrue(first in MailOutboxJob.RETRY_BACKOFF.bounds(1), "first delay $first")

        // not due yet
        assertEquals(0, job.runOnce())
        w.clock.advance(first)
        assertEquals(1, job.runOnce())
        r = row(id)
        assertEquals(MailStatus.PENDING, r.status)
        assertEquals(2, r.attempts)
        val second = r.nextAttemptAt!! - w.clock.now()
        assertTrue(second in MailOutboxJob.RETRY_BACKOFF.bounds(2), "second delay $second")

        w.clock.advance(second)
        assertEquals(1, job.runOnce())
        r = row(id)
        assertEquals(MailStatus.SENT, r.status)
        assertEquals(3, r.attempts)
        assertNull(r.lastError)
        assertEquals(w.clock.now(), r.sentAt)
        assertEquals(3, gateway.calls.size)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun `ten failed attempts end FAILED and the delays never pass the cap`(): Unit = runBlocking {
        val id = enqueue()!!
        gateway.failAlways(java.io.IOException("535 auth"))
        repeat(9) { n ->
            assertEquals(1, job.runOnce(), "attempt ${n + 1}")
            val r = row(id)
            assertEquals(MailStatus.PENDING, r.status)
            assertEquals(n + 1, r.attempts)
            val delay = r.nextAttemptAt!! - w.clock.now()
            assertTrue(delay in MailOutboxJob.RETRY_BACKOFF.bounds(n + 1), "attempt ${n + 1} delay $delay")
            assertTrue(delay <= 6L * 3_600_000 * 12 / 10)
            w.clock.advance(delay)
        }
        assertEquals(1, job.runOnce())
        val r = row(id)
        assertEquals(MailStatus.FAILED, r.status)
        assertEquals(10, r.attempts)
        assertNull(r.nextAttemptAt)
        assertTrue(r.lastError!!.contains("535 auth"))

        // FAILED rows are not picked up again
        w.clock.advance(30L * 24 * 3_600_000)
        assertEquals(0, job.runOnce())
        assertEquals(10, gateway.calls.size)
    }

    @Test
    fun `a send that never answers times out and is retried`(): Unit = runBlocking {
        val id = enqueue()!!
        gateway.hang = true
        val quick = newJob(sendTimeoutMs = 150)

        assertEquals(1, quick.runOnce())
        val r = row(id)
        assertEquals(MailStatus.PENDING, r.status)
        assertEquals(1, r.attempts)
        assertTrue(r.lastError!!.startsWith("TimeoutCancellationException"), r.lastError)
        assertNull(r.claimedUntil)
    }

    @Test
    fun `an Error from an older host (LinkageError) is a failed attempt and does not stop the tick`(): Unit = runBlocking {
        val a = enqueue(refId = 1)!!
        val b = enqueue(refId = 2)!!
        gateway.failNext(1, NoSuchMethodError("MailManager.sendMail"))

        assertEquals(2, job.runOnce())

        assertEquals(MailStatus.PENDING, row(a).status)
        assertTrue(row(a).lastError!!.startsWith("NoSuchMethodError"))
        assertEquals(MailStatus.SENT, row(b).status)
    }

    @Test
    fun `a compose failure ends FAILED at once as RENDER_ERROR and sends nothing`(): Unit = runBlocking {
        val id = enqueue()!!
        composition.composeFailures[id] = IllegalArgumentException("x".repeat(600))

        assertEquals(1, job.runOnce())

        val r = row(id)
        assertEquals(MailStatus.FAILED, r.status)
        assertEquals(1, r.attempts)
        assertTrue(r.lastError!!.startsWith("RENDER_ERROR: IllegalArgumentException: xxx"))
        assertTrue(r.lastError!!.length <= 512)
        assertEquals(0, gateway.calls.size)
        assertEquals(0, job.runOnce())
    }

    @Test
    fun `without a wired composer every row fails closed and nothing is sent`(): Unit = runBlocking {
        val wired = MailOutboxJob(
            config = { config }, clock = w.clock, service = service, gateway = gateway, composition = UnwiredMailComposition,
            sqlClient = { pool }, mailEnabled = { true }, mailOptionsAvailable = { true }
        )
        val id = enqueue()!!
        assertEquals(1, wired.runOnce())
        assertEquals(MailStatus.FAILED, row(id).status)
        assertTrue(row(id).lastError!!.startsWith("RENDER_ERROR: IllegalStateException: the mail composer is not wired"))
        assertEquals(0, gateway.calls.size)
    }

    @Test
    fun `the unavailable gateway never reports a delivery`(): Unit = runBlocking {
        val id = enqueue()!!
        val job = MailOutboxJob(
            config = { config }, clock = w.clock, service = service, gateway = UnavailableMailGateway, composition = composition,
            sqlClient = { pool }, mailEnabled = { true }, mailOptionsAvailable = { true }
        )
        assertEquals(1, job.runOnce())
        assertEquals(MailStatus.PENDING, row(id).status)
        assertTrue(row(id).lastError!!.contains("HOST_TOO_OLD"))
        assertNull(row(id).sentAt)
    }

    @Test
    fun `an obsolete row is skipped unless it was forced`(): Unit = runBlocking {
        val stale = enqueue(kind = MailKind.ORDER_RECEIVED, refId = 1)!!
        val forced = enqueue(kind = MailKind.ORDER_RECEIVED, refId = 2, force = true)!!
        composition.obsoleteIds += stale
        composition.obsoleteIds += forced

        assertEquals(2, job.runOnce())

        assertEquals(MailStatus.SKIPPED, row(stale).status)
        assertEquals("OBSOLETE", row(stale).lastError)
        assertEquals(0, row(stale).attempts)
        assertEquals(MailStatus.SENT, row(forced).status)
        assertEquals(listOf(stale), composition.obsoleteChecks.toList())
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun `a forced resend of a row older than seven days is sent, a plain old row is still STALE`(): Unit = runBlocking {
        val old = enqueue(refId = 1)!!
        val plain = enqueue(refId = 2)!!
        w.clock.advance(7L * 24 * 3_600_000 + 1)
        assertEquals(old, enqueue(refId = 1, force = true))

        assertEquals(2, job.runOnce())

        assertEquals(MailStatus.SENT, row(old).status)
        assertEquals(1, row(old).attempts)
        assertEquals(1, gateway.sent.size)
        assertEquals(MailStatus.SKIPPED, row(plain).status)
        assertEquals("STALE", row(plain).lastError)
    }

    @Test
    fun `a failing relevance check is a retryable attempt, not a RENDER_ERROR`(): Unit = runBlocking {
        val id = enqueue(kind = MailKind.ORDER_RECEIVED)!!
        composition.obsoleteFailures[id] = java.util.concurrent.ConcurrentLinkedQueue(listOf(java.io.IOException("pool timeout")))

        assertEquals(1, job.runOnce())
        var r = row(id)
        assertEquals(MailStatus.PENDING, r.status)
        assertEquals(1, r.attempts)
        assertTrue(r.lastError!!.startsWith("IOException: pool timeout"), r.lastError)
        assertNull(r.claimedUntil)
        val delay = r.nextAttemptAt!! - w.clock.now()
        assertTrue(delay in MailOutboxJob.RETRY_BACKOFF.bounds(1), "delay $delay")
        assertEquals(0, gateway.calls.size)

        w.clock.advance(delay)
        assertEquals(1, job.runOnce())
        r = row(id)
        assertEquals(MailStatus.SENT, r.status)
        assertEquals(2, r.attempts)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun `a relevance check that keeps failing ends FAILED only after ten attempts`(): Unit = runBlocking {
        val id = enqueue(kind = MailKind.ORDER_RECEIVED)!!
        composition.obsoleteFailures[id] = java.util.concurrent.ConcurrentLinkedQueue(List(10) { java.io.IOException("lock wait") })
        repeat(9) {
            assertEquals(1, job.runOnce())
            assertEquals(MailStatus.PENDING, row(id).status)
            w.clock.advance(row(id).nextAttemptAt!! - w.clock.now())
        }
        assertEquals(1, job.runOnce())
        assertEquals(MailStatus.FAILED, row(id).status)
        assertEquals(10, row(id).attempts)
        assertTrue(row(id).lastError!!.startsWith("IOException: lock wait"))
        assertEquals(0, gateway.calls.size)
    }

    @Test
    fun `a row older than seven days is skipped as STALE`(): Unit = runBlocking {
        val old = enqueue(refId = 1)!!
        w.clock.advance(7L * 24 * 3_600_000 + 1)
        val fresh = enqueue(refId = 2)!!

        assertEquals(2, job.runOnce())

        assertEquals(MailStatus.SKIPPED, row(old).status)
        assertEquals("STALE", row(old).lastError)
        assertEquals(MailStatus.SENT, row(fresh).status)
    }

    @Test
    fun `attachments travel with the message and a failed invoice render is kept on the SENT row`(): Unit = runBlocking {
        val withPdf = enqueue(refId = 1)!!
        val noPdf = enqueue(refId = 2)!!
        composition.attachmentsById[withPdf] = MailAttachments(listOf(MailAttachment("INV-1.pdf", "application/pdf", byteArrayOf(1, 2, 3))))
        composition.attachmentsById[noPdf] = MailAttachments(invoiceRenderFailed = true)

        assertEquals(2, job.runOnce())

        val m = gateway.sent.first { it.content.paragraphs == listOf("Row $withPdf") }
        assertEquals("INV-1.pdf", m.attachments.single().name)
        assertEquals("application/pdf", m.attachments.single().contentType)
        assertEquals(listOf<Byte>(1, 2, 3), m.attachments.single().data.toList())
        assertEquals(MailStatus.SENT, row(withPdf).status)
        assertNull(row(withPdf).lastError)

        assertEquals(MailStatus.SENT, row(noPdf).status)
        assertEquals("INVOICE_RENDER_FAILED", row(noPdf).lastError)
        assertTrue(gateway.sent.first { it.content.paragraphs == listOf("Row $noPdf") }.attachments.isEmpty())
    }

    @Test
    fun `a tick takes at most twenty rows, oldest schedule first`(): Unit = runBlocking {
        val ids = (1L..25L).map { n ->
            enqueue(refId = n)!!.also { setRow(it, "nextAttemptAt" to w.clock.now() - 1000 + n) }
        }
        assertEquals(20, job.runOnce())
        assertEquals(ids.take(20), ids.filter { row(it).status == MailStatus.SENT })
        assertEquals(5, job.runOnce())
        assertEquals(25, gateway.sent.size)
    }

    @Test
    fun `two job instances never send the same row twice`(): Unit = runBlocking {
        val ids = (1L..15L).map { enqueue(refId = it)!! }
        gateway.latencyMs = 40
        val other = newJob()

        val handled = Race.run(2) { i -> if (i == 0) job.runOnce() else other.runOnce() }

        assertTrue(handled.all { it.isSuccess }, "$handled")
        assertEquals(15, handled.sumOf { it.getOrThrow() })
        assertEquals(15, gateway.calls.size)
        assertEquals(15, gateway.calls.map { it.content.paragraphs.single() }.toSet().size)
        assertTrue(ids.all { row(it).status == MailStatus.SENT && row(it).attempts == 1 })
        assertTrue(gateway.maxConcurrent >= 2, "the two jobs ought to have overlapped")
    }

    @Test
    fun `a crashed send is recovered after its claim ran out and counts as an attempt`(): Unit = runBlocking {
        val id = enqueue()!!
        setRow(id, "status" to "SENDING", "attempts" to 2, "claimedUntil" to w.clock.now() + 1_000, "nextAttemptAt" to w.clock.now())

        // the claim is still valid: nobody touches the row
        assertEquals(0, job.runOnce())
        assertEquals(MailStatus.SENDING, row(id).status)
        assertEquals(0, gateway.calls.size)

        w.clock.advance(1_001)
        assertEquals(1, job.runOnce())
        val r = row(id)
        assertEquals(MailStatus.SENT, r.status)
        assertEquals(3, r.attempts)
        assertNull(r.claimedUntil)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun `a crashed tenth attempt is not tried again`(): Unit = runBlocking {
        val id = enqueue()!!
        setRow(id, "status" to "SENDING", "attempts" to 10, "claimedUntil" to w.clock.now() - 1, "lastError" to "IOException: before")

        assertEquals(1, job.runOnce())

        val r = row(id)
        assertEquals(MailStatus.FAILED, r.status)
        assertEquals(10, r.attempts)
        assertEquals("IOException: before", r.lastError)
        assertEquals(0, gateway.calls.size)
    }

    @Test
    fun `a state write only lands on a row that is still being sent`(): Unit = runBlocking {
        val id = enqueue()!!
        val seen = row(id)
        val claimed = service.claim(seen, w.clock.now(), pool)!!
        assertEquals(MailStatus.SENDING, claimed.status)
        assertEquals(1, claimed.attempts)
        assertEquals(w.clock.now() + MailOutboxService.CLAIM_MS, claimed.claimedUntil)

        // a second claimant with the same stale view loses
        assertNull(service.claim(seen, w.clock.now(), pool))

        // a resend moved the row away while it was being sent: the late result is dropped
        setRow(id, "status" to "PENDING", "claimedUntil" to null, "attempts" to 0)
        assertFalse(service.finish(claimed, MailStatus.SENT, 1, null, null, w.clock.now(), pool))
        assertEquals(MailStatus.PENDING, row(id).status)
        assertNotEquals(MailStatus.SENT, row(id).status)
    }

    @Test
    fun `the retry backoff of the job is 60 s doubling up to 6 h with 20 percent jitter`() {
        val b = MailOutboxJob.RETRY_BACKOFF
        assertEquals(listOf(60L, 120L, 240L, 480L, 960L, 1920L, 3840L, 7680L, 15360L), (1..9).map { b.baseDelayMs(it) / 1000 })
        assertEquals(6L * 3_600_000, b.baseDelayMs(20))
        assertEquals(Backoff(60_000L, 2.0, 21_600_000L, 0.2).bounds(3), b.bounds(3))

        val retry = MailOutboxJob.decideFailure(9, 1_000, "e", b, Random(1))
        assertTrue(retry is MailOutboxJob.Failure.Retry)
        assertTrue(MailOutboxJob.decideFailure(10, 1_000, "e") is MailOutboxJob.Failure.Exhausted)
    }
}
