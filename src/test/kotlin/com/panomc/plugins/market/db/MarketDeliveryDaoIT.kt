package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
import com.panomc.plugins.market.db.impl.MarketDeliveryDaoImpl
import com.panomc.plugins.market.db.impl.MarketMailOutboxDaoImpl
import com.panomc.plugins.market.db.impl.MarketServerStateDaoImpl
import com.panomc.plugins.market.db.impl.MarketWebhookDeliveryDaoImpl
import com.panomc.plugins.market.db.impl.MarketWebhookEndpointDaoImpl
import com.panomc.plugins.market.db.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_delivery`, `market_server_state`, `market_webhook_endpoint`, `market_webhook_delivery`, `market_mail_outbox` (01 section 9). */
class MarketDeliveryDaoIT : MarketDaoITBase() {
    private val deliveries = MarketDeliveryDaoImpl()
    private val servers = MarketServerStateDaoImpl()
    private val endpoints = MarketWebhookEndpointDaoImpl()
    private val hooks = MarketWebhookDeliveryDaoImpl()
    private val mails = MarketMailOutboxDaoImpl()

    private fun delivery(key: String = "1:a1:0:GRANT:0:0") = MarketDelivery(
        sourceType = DeliverySourceType.ORDER_ITEM, orderId = 11, orderItemId = 12, sourceId = 13, entitlementId = 14, subscriptionId = 15,
        phase = DeliveryPhase.GRANT, actionId = "a1", actionType = DeliveryActionType.COMMAND, unitIndex = 2, attemptGroup = 1, serverId = 3,
        idempotencyKey = key, status = DeliveryStatus.SCHEDULED, requiresOnline = true, playerUsername = "Steve",
        playerUuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5", payload = "{\"commands\":[\"say hi\"]}", result = "{\"executedAt\":1}",
        transport = DeliveryTransport.MARKET_MC, guaranteed = true, attempts = 4, runAfter = 100, nextAttemptAt = 200, cancelRequestedAt = 210,
        waitUntil = 220, claimToken = "tok", claimedUntil = 230, sentAt = 240, confirmedAt = 250, lastErrorCode = "SERVER_OFFLINE",
        lastError = "offline", createdAt = 10, updatedAt = 20
    )

    // --- delivery -----------------------------------------------------------------------------------------------

    @Test
    fun `a delivery round-trips every column`(): Unit = runBlocking {
        val id = deliveries.add(delivery(), pool)!!
        val row = deliveries.getById(id, pool)!!
        assertEquals(DeliverySourceType.ORDER_ITEM, row.sourceType)
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), listOf(row.orderId, row.orderItemId, row.sourceId, row.entitlementId, row.subscriptionId))
        assertEquals(DeliveryPhase.GRANT, row.phase)
        assertEquals("a1", row.actionId)
        assertEquals(DeliveryActionType.COMMAND, row.actionType)
        assertEquals(listOf(2L, 1L, 3L), listOf(row.unitIndex, row.attemptGroup, row.serverId).map { it.toLong() })
        assertEquals("1:a1:0:GRANT:0:0", row.idempotencyKey)
        assertEquals(DeliveryStatus.SCHEDULED, row.status)
        assertTrue(row.requiresOnline)
        assertTrue(row.guaranteed)
        assertEquals("Steve", row.playerUsername)
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", row.playerUuid)
        assertEquals("{\"commands\":[\"say hi\"]}", row.payload)
        assertEquals("{\"executedAt\":1}", row.result)
        assertEquals(DeliveryTransport.MARKET_MC, row.transport)
        assertEquals(listOf(4L, 100L, 200L, 210L, 220L), listOf(row.attempts.toLong(), row.runAfter, row.nextAttemptAt, row.cancelRequestedAt, row.waitUntil))
        assertEquals(listOf("tok", 230L, 240L, 250L), listOf(row.claimToken, row.claimedUntil, row.sentAt, row.confirmedAt))
        assertEquals("SERVER_OFFLINE", row.lastErrorCode)
        assertEquals("offline", row.lastError)
        assertEquals(10L, row.createdAt)
        assertEquals(20L, row.updatedAt)
    }

    @Test
    fun `a minimal delivery gets the column defaults`(): Unit = runBlocking {
        sql(
            "INSERT INTO `pano_market_delivery` (`phase`, `actionId`, `actionType`, `idempotencyKey`, `playerUsername`, `payload`, `runAfter`, `createdAt`, `updatedAt`) " +
                "VALUES ('GRANT', 'a', 'CREDIT', 'k-min', 'Alex', '{}', 0, 1, 1)"
        )
        val row = deliveries.getByIdempotencyKey("k-min", pool)!!
        assertEquals(DeliverySourceType.ORDER_ITEM, row.sourceType)
        assertEquals(DeliveryStatus.PENDING, row.status)
        assertEquals(0L, row.serverId)
        assertEquals(listOf(0, 0, 0), listOf(row.unitIndex, row.attemptGroup, row.attempts))
        assertFalse(row.requiresOnline)
        assertFalse(row.guaranteed)
        assertNull(row.transport)
        assertNull(row.result)
    }

    @Test
    fun `a second delivery with the same idempotency key is refused and nothing is overwritten`(): Unit = runBlocking {
        val first = deliveries.add(delivery("dup"), pool)
        assertNotNull(first)
        // every other column differs
        val second = deliveries.add(
            MarketDelivery(orderId = 99, phase = DeliveryPhase.REVOKE, actionId = "z", actionType = DeliveryActionType.CREDIT, idempotencyKey = "dup", playerUsername = "Other"), pool
        )
        assertNull(second)
        assertEquals(1L, count("market_delivery"))
        assertEquals("Steve", deliveries.getByIdempotencyKey("dup", pool)!!.playerUsername)
        // a different key is accepted, and the manual re-run key of the next attempt group too
        assertNotNull(deliveries.add(delivery("dup:g1"), pool))
        assertEquals(2L, count("market_delivery"))
    }

    @Test
    fun `the database itself rejects a duplicate idempotency key`(): Unit = runBlocking {
        deliveries.add(delivery("raw"), pool)
        val failure = runCatching {
            sql("INSERT INTO `pano_market_delivery` (`phase`, `actionId`, `actionType`, `idempotencyKey`, `playerUsername`, `payload`, `runAfter`, `createdAt`, `updatedAt`) VALUES ('GRANT', 'a', 'CREDIT', 'raw', 'x', '{}', 0, 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1L, count("market_delivery"))
    }

    @Test
    fun `delivery lookups by order, item, due time and server`(): Unit = runBlocking {
        val a = deliveries.add(delivery("l1"), pool)!!
        val b = deliveries.add(MarketDelivery(orderId = 11, orderItemId = 12, phase = DeliveryPhase.EXPIRE, actionId = "a2", actionType = DeliveryActionType.PERMISSION, idempotencyKey = "l2", playerUsername = "Steve", serverId = 3, status = DeliveryStatus.PENDING, nextAttemptAt = 150), pool)!!
        deliveries.add(MarketDelivery(orderId = 77, orderItemId = 78, actionId = "a3", idempotencyKey = "l3", playerUsername = "Alex", status = DeliveryStatus.PENDING, nextAttemptAt = 500), pool)
        assertEquals(listOf(a, b), deliveries.getByOrderId(11, pool).map { it.id })
        assertEquals(listOf(a, b), deliveries.getByOrderItemId(12, pool).map { it.id })
        assertEquals(listOf(b), deliveries.getDue(DeliveryStatus.PENDING, 300, 10, pool).map { it.id })
        assertEquals(0, deliveries.getDue(DeliveryStatus.PENDING, 100, 10, pool).size)
        assertEquals(2, deliveries.getDue(DeliveryStatus.PENDING, 1_000, 10, pool).size)
        assertEquals(1, deliveries.getDue(DeliveryStatus.PENDING, 1_000, 1, pool).size)
        assertEquals(listOf(b), deliveries.getByServerAndStatus(3, DeliveryStatus.PENDING, 10, pool).map { it.id })
        assertEquals(listOf(a), deliveries.getByServerAndStatus(3, DeliveryStatus.SCHEDULED, 10, pool).map { it.id })
    }

    @Test
    fun `a delivery transition is a compare and set`(): Unit = runBlocking {
        val id = deliveries.add(delivery("t1").let { MarketDelivery(idempotencyKey = "t1", actionId = "a", playerUsername = "Steve", status = DeliveryStatus.PENDING) }, pool)!!
        assertTrue(deliveries.transition(id, DeliveryStatus.PENDING, DeliveryStatus.SENDING, 5, null, null, null, pool))
        // the row is no longer PENDING: a second claimer loses
        assertFalse(deliveries.transition(id, DeliveryStatus.PENDING, DeliveryStatus.SENDING, 6, null, null, null, pool))
        assertTrue(deliveries.transition(id, DeliveryStatus.SENDING, DeliveryStatus.PENDING, 7, 900, "DB_ERROR", "boom", pool))
        val row = deliveries.getById(id, pool)!!
        assertEquals(DeliveryStatus.PENDING, row.status)
        assertEquals(900L, row.nextAttemptAt)
        assertEquals("DB_ERROR", row.lastErrorCode)
        assertEquals(7L, row.updatedAt)
        assertFalse(deliveries.transition(9_999, DeliveryStatus.PENDING, DeliveryStatus.SENDING, 8, null, null, null, pool))
    }

    // --- server state -------------------------------------------------------------------------------------------

    @Test
    fun `server state upsert writes the sync columns and never touches the settings`(): Unit = runBlocking {
        servers.upsertSync(MarketServerState(serverId = 4, mcComponentVersion = "1.0.0", capabilities = "market-delivery,luckperms", platform = "PAPER", protocol = 3, queuedCount = 2, lastSeenAt = 100, createdAt = 1, updatedAt = 1), pool)
        assertTrue(servers.updateSettings(4, "{\"announce\":false}", 50, pool))
        servers.upsertSync(MarketServerState(serverId = 4, mcComponentVersion = "1.0.1", capabilities = "market-delivery", platform = "FOLIA", protocol = 4, queuedCount = 0, lastSeenAt = 200, settings = "{\"must\":\"be ignored\"}", createdAt = 999, updatedAt = 60), pool)
        assertEquals(1L, count("market_server_state"))
        val row = servers.getByServerId(4, pool)!!
        assertEquals("1.0.1", row.mcComponentVersion)
        assertEquals("market-delivery", row.capabilities)
        assertEquals("FOLIA", row.platform)
        assertEquals(listOf(4L, 0L, 200L), listOf(row.protocol!!.toLong(), row.queuedCount!!.toLong(), row.lastSeenAt))
        assertEquals("{\"announce\":false}", row.settings)
        assertEquals(1L, row.createdAt)
        assertEquals(60L, row.updatedAt)
        assertNull(servers.getByServerId(5, pool))
        assertFalse(servers.updateSettings(5, null, 1, pool))
        assertTrue(servers.updateSettings(4, null, 70, pool))
        assertNull(servers.getByServerId(4, pool)!!.settings)
        servers.upsertSync(MarketServerState(serverId = 2), pool)
        assertEquals(listOf(2L, 4L), servers.getAll(pool).map { it.serverId })
    }

    @Test
    fun `a second server state row for one server is refused by the database`(): Unit = runBlocking {
        servers.upsertSync(MarketServerState(serverId = 8), pool)
        val failure = runCatching { sql("INSERT INTO `pano_market_server_state` (`serverId`, `createdAt`, `updatedAt`) VALUES (8, 1, 1)") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1L, count("market_server_state"))
    }

    // --- webhook endpoint ---------------------------------------------------------------------------------------

    private fun endpoint(name: String = "Discord") = MarketWebhookEndpoint(
        name = name, url = "https://example.com/hook", events = "[\"order.paid\",\"order.refunded\"]", format = WebhookFormat.DISCORD,
        signing = WebhookSigning.HMAC_SHA256, secret = "v1:secret", headers = "v1:headers", template = "{\"embeds\":[]}", enabled = true, maxAttempts = 5,
        createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a webhook endpoint round-trips, updates and deletes`(): Unit = runBlocking {
        val id = endpoints.add(endpoint(), pool)
        val row = endpoints.getById(id, pool)!!
        assertEquals("Discord", row.name)
        assertEquals("[\"order.paid\",\"order.refunded\"]", row.events)
        assertEquals(WebhookFormat.DISCORD, row.format)
        assertEquals(WebhookSigning.HMAC_SHA256, row.signing)
        assertEquals("v1:secret", row.secret)
        assertEquals("v1:headers", row.headers)
        assertEquals("{\"embeds\":[]}", row.template)
        assertTrue(row.enabled)
        assertEquals(5, row.maxAttempts)
        assertEquals(0, row.failureCount)

        assertTrue(endpoints.update(MarketWebhookEndpoint(id = id, name = "Renamed", url = "https://example.com/2", events = "[\"*\"]", enabled = false, maxAttempts = 3, disabledReason = "ADMIN"), 99, pool))
        val updated = endpoints.getById(id, pool)!!
        assertEquals(listOf("Renamed", "https://example.com/2", "[\"*\"]"), listOf(updated.name, updated.url, updated.events))
        assertEquals(WebhookFormat.JSON, updated.format)
        assertNull(updated.secret)
        assertFalse(updated.enabled)
        assertEquals("ADMIN", updated.disabledReason)
        assertEquals(99L, updated.updatedAt)
        assertFalse(endpoints.update(MarketWebhookEndpoint(id = 9_999), 1, pool))

        endpoints.add(endpoint("Second"), pool)
        assertEquals(listOf("Renamed", "Second"), endpoints.getAll(pool).map { it.name })
        assertTrue(endpoints.delete(id, pool))
        assertFalse(endpoints.delete(id, pool))
        assertNull(endpoints.getById(id, pool))
    }

    @Test
    fun `fifty consecutive failures disable an endpoint and one success resets the counter`(): Unit = runBlocking {
        val id = endpoints.add(endpoint(), pool)
        repeat(49) { assertTrue(endpoints.recordOutcome(id, false, 500, 1_000L + it, MarketWebhookEndpointDao.DEFAULT_DISABLE_AFTER, pool)) }
        var row = endpoints.getById(id, pool)!!
        assertEquals(49, row.failureCount)
        assertTrue(row.enabled)
        assertNull(row.disabledReason)
        assertEquals(500, row.lastStatusCode)

        assertTrue(endpoints.recordOutcome(id, true, 200, 2_000, MarketWebhookEndpointDao.DEFAULT_DISABLE_AFTER, pool))
        row = endpoints.getById(id, pool)!!
        assertEquals(0, row.failureCount)
        assertTrue(row.enabled)
        assertEquals(200, row.lastStatusCode)
        assertEquals(2_000L, row.lastDeliveryAt)

        repeat(50) { endpoints.recordOutcome(id, false, null, 3_000L + it, MarketWebhookEndpointDao.DEFAULT_DISABLE_AFTER, pool) }
        row = endpoints.getById(id, pool)!!
        assertEquals(50, row.failureCount)
        assertFalse(row.enabled)
        assertEquals(MarketWebhookEndpointDao.AUTO_DISABLED_REASON, row.disabledReason)
        assertNull(row.lastStatusCode)
        assertFalse(endpoints.recordOutcome(9_999, false, 500, 1, 50, pool))
    }

    // --- webhook delivery ---------------------------------------------------------------------------------------

    private fun hook(eventId: String = "0b0e5b1e-0000-4000-8000-000000000001") = MarketWebhookDelivery(
        endpointId = 3, deliveryId = 4, eventId = eventId, event = "order.paid", orderId = 11, url = "https://example.com/hook", format = WebhookFormat.DISCORD,
        signing = WebhookSigning.HMAC_SHA256, secret = "v1:s", body = "{\"a\":1}", status = WebhookDeliveryStatus.PENDING, attempts = 1, maxAttempts = 6,
        nextAttemptAt = 100, claimedUntil = 110, lastStatusCode = 503, lastError = "e", lastResponse = "r", durationMs = 12, deliveredAt = null, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a webhook delivery round-trips and the event id is unique`(): Unit = runBlocking {
        val id = hooks.add(hook(), pool)!!
        val row = hooks.getById(id, pool)!!
        assertEquals(listOf(3L, 4L), listOf(row.endpointId, row.deliveryId))
        assertEquals("0b0e5b1e-0000-4000-8000-000000000001", row.eventId)
        assertEquals("order.paid", row.event)
        assertEquals(11L, row.orderId)
        assertEquals(WebhookFormat.DISCORD, row.format)
        assertEquals(WebhookSigning.HMAC_SHA256, row.signing)
        assertEquals("v1:s", row.secret)
        assertEquals("{\"a\":1}", row.body)
        assertEquals(WebhookDeliveryStatus.PENDING, row.status)
        assertEquals(listOf(1, 6), listOf(row.attempts, row.maxAttempts))
        assertEquals(listOf(100L, 110L), listOf(row.nextAttemptAt, row.claimedUntil))
        assertEquals(listOf(503, 12), listOf(row.lastStatusCode, row.durationMs))
        assertEquals(listOf("e", "r"), listOf(row.lastError, row.lastResponse))

        // same event id, everything else different
        assertNull(hooks.add(MarketWebhookDelivery(eventId = "0b0e5b1e-0000-4000-8000-000000000001", event = "order.refunded", url = "https://other.example", body = "x", endpointId = 9), pool))
        assertEquals(1L, count("market_webhook_delivery"))
        assertEquals("order.paid", hooks.getByEventId("0b0e5b1e-0000-4000-8000-000000000001", pool)!!.event)
        assertNotNull(hooks.add(hook("0b0e5b1e-0000-4000-8000-000000000002"), pool))
        assertEquals(2L, count("market_webhook_delivery"))
        assertNull(hooks.getByEventId("nope", pool))
    }

    @Test
    fun `the database itself rejects a duplicate webhook event id`(): Unit = runBlocking {
        hooks.add(hook("0b0e5b1e-0000-4000-8000-0000000000aa"), pool)
        val failure = runCatching {
            sql("INSERT INTO `pano_market_webhook_delivery` (`eventId`, `event`, `url`, `format`, `signing`, `body`, `createdAt`, `updatedAt`) VALUES ('0b0e5b1e-0000-4000-8000-0000000000aa', 'e', 'u', 'JSON', 'NONE', 'b', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1L, count("market_webhook_delivery"))
    }

    @Test
    fun `webhook delivery lookups and the result update`(): Unit = runBlocking {
        val a = hooks.add(hook("e-1"), pool)!!
        val b = hooks.add(hook("e-2").let { MarketWebhookDelivery(endpointId = 3, eventId = "e-2", event = "order.paid", orderId = 11, url = "u", body = "b", status = WebhookDeliveryStatus.FAILED, nextAttemptAt = 50) }, pool)!!
        hooks.add(MarketWebhookDelivery(endpointId = 8, eventId = "e-3", event = "x", url = "u", body = "b", nextAttemptAt = 10), pool)
        assertEquals(listOf(b, a), hooks.getByEndpointId(3, 10, pool).map { it.id })
        assertEquals(listOf(b), hooks.getByEndpointId(3, 1, pool).map { it.id })
        assertEquals(listOf(a, b), hooks.getByOrderId(11, pool).map { it.id })
        assertEquals(listOf(b), hooks.getDue(WebhookDeliveryStatus.FAILED, 60, 10, pool).map { it.id })
        assertEquals(0, hooks.getDue(WebhookDeliveryStatus.FAILED, 40, 10, pool).size)

        assertTrue(hooks.markResult(b, WebhookDeliveryStatus.FAILED, WebhookDeliveryStatus.SUCCEEDED, 2, null, 204, null, "ok", 31, 70, 71, pool))
        val done = hooks.getById(b, pool)!!
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, done.status)
        assertEquals(listOf(2, 204, 31), listOf(done.attempts, done.lastStatusCode, done.durationMs))
        assertNull(done.nextAttemptAt)
        assertEquals(70L, done.deliveredAt)
        // the row left FAILED: a second writer holding the old state loses
        assertFalse(hooks.markResult(b, WebhookDeliveryStatus.FAILED, WebhookDeliveryStatus.DEAD, 3, null, 500, "e", null, null, null, 80, pool))
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, hooks.getById(b, pool)!!.status)
    }

    // --- mail outbox --------------------------------------------------------------------------------------------

    private fun mail(kind: MailKind = MailKind.ORDER_CONFIRMATION, refKey: String = "", recipient: String = "a@example.com", refId: Long = 11) = MarketMailOutbox(
        kind = kind, refType = MailRefType.ORDER, refId = refId, refKey = refKey, orderId = 11, userId = 7, recipient = recipient, locale = "tr",
        params = "{\"total\":\"1,00\"}", status = MailStatus.PENDING, attempts = 1, nextAttemptAt = 100, claimedUntil = 110, lastError = "e", sentAt = null,
        createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a mail round-trips and the mail key is unique across its five columns`(): Unit = runBlocking {
        val id = mails.add(mail(), pool)!!
        val row = mails.getById(id, pool)!!
        assertEquals(MailKind.ORDER_CONFIRMATION, row.kind)
        assertEquals(MailRefType.ORDER, row.refType)
        assertEquals(11L, row.refId)
        assertEquals("", row.refKey)
        assertEquals(listOf(11L, 7L), listOf(row.orderId, row.userId))
        assertEquals(listOf("a@example.com", "tr", "{\"total\":\"1,00\"}"), listOf(row.recipient, row.locale, row.params))
        assertEquals(MailStatus.PENDING, row.status)
        assertEquals(listOf(1L, 100L, 110L), listOf(row.attempts.toLong(), row.nextAttemptAt, row.claimedUntil))
        assertEquals("e", row.lastError)

        // the identical key with every other column different
        val dup = MarketMailOutbox(
            kind = MailKind.ORDER_CONFIRMATION, refType = MailRefType.ORDER, refId = 11, refKey = "", recipient = "a@example.com", locale = "ru",
            params = "{}", status = MailStatus.SENT, orderId = 99, userId = 1
        )
        assertNull(mails.add(dup, pool))
        assertEquals(1L, count("market_mail_outbox"))
        assertEquals("tr", mails.getByKey(MailKind.ORDER_CONFIRMATION, MailRefType.ORDER, 11, "", "a@example.com", pool)!!.locale)

        // changing any one of the five key columns makes a new mail
        assertNotNull(mails.add(mail(kind = MailKind.ORDER_DELIVERED), pool))
        assertNotNull(mails.add(mail(refKey = "p2"), pool))
        assertNotNull(mails.add(mail(recipient = "b@example.com"), pool))
        assertNotNull(mails.add(mail(refId = 12), pool))
        assertNotNull(mails.add(MarketMailOutbox(kind = MailKind.ORDER_CONFIRMATION, refType = MailRefType.PAYMENT, refId = 11, recipient = "a@example.com"), pool))
        assertEquals(6L, count("market_mail_outbox"))
        assertNull(mails.add(mail(refKey = "p2"), pool))
        assertEquals(6L, count("market_mail_outbox"))
    }

    @Test
    fun `the database itself rejects a duplicate mail key`(): Unit = runBlocking {
        mails.add(mail(), pool)
        val failure = runCatching {
            sql("INSERT INTO `pano_market_mail_outbox` (`kind`, `refType`, `refId`, `recipient`, `locale`, `params`, `createdAt`, `updatedAt`) VALUES ('ORDER_CONFIRMATION', 'ORDER', 11, 'a@example.com', 'en-US', '{}', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure) // refKey defaults to '' and equals the stored one
        assertEquals(1L, count("market_mail_outbox"))
    }

    @Test
    fun `mail lookups, due list and the status transition`(): Unit = runBlocking {
        val a = mails.add(mail(), pool)!!
        val b = mails.add(mail(kind = MailKind.ORDER_DELIVERED).let { MarketMailOutbox(kind = MailKind.ORDER_DELIVERED, refType = MailRefType.ORDER, refId = 11, orderId = 11, recipient = "a@example.com", nextAttemptAt = 40) }, pool)!!
        assertEquals(listOf(a, b), mails.getByOrderId(11, pool).map { it.id })
        assertEquals(listOf(b), mails.getDue(MailStatus.PENDING, 50, 10, pool).map { it.id })
        assertEquals(listOf(b, a), mails.getDue(MailStatus.PENDING, 500, 10, pool).map { it.id })

        assertTrue(mails.transition(b, MailStatus.PENDING, MailStatus.SENT, 1, null, null, 60, 61, pool))
        val sent = mails.getById(b, pool)!!
        assertEquals(MailStatus.SENT, sent.status)
        assertEquals(60L, sent.sentAt)
        assertNull(sent.nextAttemptAt)
        assertFalse(mails.transition(b, MailStatus.PENDING, MailStatus.FAILED, 2, null, "late", null, 70, pool))
        assertEquals(MailStatus.SENT, mails.getById(b, pool)!!.status)
        assertNull(mails.getByKey(MailKind.EXPIRY_REMINDER, MailRefType.ENTITLEMENT, 1, "", "x", pool))
    }
}
