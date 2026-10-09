package com.panomc.plugins.market.job

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.service.CreditReconciler
import com.panomc.plugins.market.service.ThrottleService
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `HousekeepingJob` on a real MariaDB (MK-153; 11 sections 12.1, 16 and 17, 07 section 16.2): the retention windows (network data of orders and payments after 400
 * days, event bodies after 180, `REJECTED` events after 14, expired blocks 30 days after their end), the purge of the throttle table, the cadence of the tasks (the
 * credit self-check 60 s after the first tick and then every 6 h, retention daily), a failing task that stops no other, and the cleanup of ended subscriptions
 * without an owner. The deferred half of the erasure and the marker retry are in [com.panomc.plugins.market.service.PlayerErasureIT].
 */
class HousekeepingJobIT : MarketDaoITBase() {
    private lateinit var w: TestWiring

    @BeforeEach
    fun fresh() {
        runBlocking { resetState() }
        w = TestWiring(pool)
    }

    private val day = HousekeepingJob.DAY_MS

    private fun job(reconciler: CreditReconciler? = null, throttle: ThrottleService? = ThrottleService(w.throttles, { pool }, w.clock)) =
        HousekeepingJob(w.clock, { "pano_" }, { pool }, throttle, reconciler, null, null)

    private suspend fun order(ageDays: Long, ip: String? = "203.0.113.9", agent: String? = "ua", status: String = "CANCELLED"): Long =
        Fixtures.insertRaw(
            pool, "market_order",
            mapOf("status" to status, "clientIp" to ip, "userAgent" to agent, "createdAt" to w.clock.now() - ageDays * day, "updatedAt" to w.clock.now(), "playerUsername" to "x", "reservationState" to "NONE")
        )

    private suspend fun attempt(orderId: Long, ageDays: Long, reference: String): Long =
        Fixtures.insertRaw(
            pool, "market_payment",
            mapOf(
                "orderId" to orderId, "providerId" to "fake", "status" to "CANCELLED", "reference" to reference, "token" to reference.padEnd(40, 'x'), "amount" to 0, "currency" to "EUR",
                "clientIp" to "203.0.113.9", "userAgent" to "ua", "createdAt" to w.clock.now() - ageDays * day, "updatedAt" to w.clock.now()
            )
        )

    private suspend fun event(key: String, ageDays: Long, status: String): Long =
        Fixtures.insertRaw(
            pool, "market_payment_event",
            mapOf(
                "providerId" to "fake", "direction" to "IN", "channel" to "WEBHOOK", "eventKey" to key, "body" to "{\"k\":1}", "headers" to "{\"h\":1}", "status" to status,
                "createdAt" to w.clock.now() - ageDays * day, "updatedAt" to w.clock.now()
            )
        )

    private suspend fun value(table: String, id: Long, column: String): Any? = sql("SELECT `$column` AS v FROM `pano_$table` WHERE `id` = ?", id).single().getValue("v")

    @Test
    fun `retention nulls the network data of orders and payments after 400 days and keeps younger rows`(): Unit = runBlocking {
        val old = order(401)
        val young = order(399)
        val oldAttempt = attempt(old, 401, "R-OLD")
        val youngAttempt = attempt(young, 399, "R-YOUNG")

        job().retention()

        assertNull(value("market_order", old, "clientIp"))
        assertNull(value("market_order", old, "userAgent"))
        assertNull(value("market_payment", oldAttempt, "clientIp"))
        assertNull(value("market_payment", oldAttempt, "userAgent"))
        assertEquals("203.0.113.9", value("market_order", young, "clientIp"))
        assertEquals("ua", value("market_order", young, "userAgent"))
        assertEquals("203.0.113.9", value("market_payment", youngAttempt, "clientIp"))

        // the row itself stays: it is a financial record
        assertEquals("CANCELLED", value("market_order", old, "status"))
    }

    @Test
    fun `retention nulls event bodies after 180 days and deletes REJECTED events after 14 days`(): Unit = runBlocking {
        val oldBody = event("e:old", 181, "PROCESSED")
        val youngBody = event("e:young", 179, "PROCESSED")
        val oldRejected = event("e:rej-old", 15, "REJECTED")
        val youngRejected = event("e:rej-young", 13, "REJECTED")

        job().retention()

        assertNull(value("market_payment_event", oldBody, "body"))
        assertNull(value("market_payment_event", oldBody, "headers"))
        assertEquals("{\"k\":1}", value("market_payment_event", youngBody, "body"))
        assertEquals(0L, count("market_payment_event", "`id` = ?", oldRejected))
        assertEquals(1L, count("market_payment_event", "`id` = ?", youngRejected))
        assertEquals(1L, count("market_payment_event", "`id` = ?", oldBody), "an old event row stays, only its body goes")
    }

    @Test
    fun `retention deletes block rows 30 days after they expired and keeps the others`(): Unit = runBlocking {
        val now = w.clock.now()
        val gone = Fixtures.insertRaw(pool, "market_block", mapOf("type" to "EMAIL", "value" to "a@x.test", "source" to "ADMIN", "expiresAt" to now - 31 * day))
        val recent = Fixtures.insertRaw(pool, "market_block", mapOf("type" to "EMAIL", "value" to "b@x.test", "source" to "ADMIN", "expiresAt" to now - 29 * day))
        val running = Fixtures.insertRaw(pool, "market_block", mapOf("type" to "EMAIL", "value" to "c@x.test", "source" to "ADMIN", "expiresAt" to now + day))
        val forever = Fixtures.insertRaw(pool, "market_block", mapOf("type" to "EMAIL", "value" to "d@x.test", "source" to "ADMIN"))

        job().retention()

        assertEquals(0L, count("market_block", "`id` = ?", gone))
        assertEquals(3L, count("market_block", "`id` IN (?, ?, ?)", recent, running, forever))
        assertEquals(3L, count("market_block"))
    }

    @Test
    fun `retention deletes expired provider state keys and leaves the webhook log to core`(): Unit = runBlocking {
        val now = w.clock.now()

        // the delivery log is core's now (it purges finished rows after 30 days itself): the market's retention does not touch it
        val old = Fixtures.insertRaw(
            pool, "webhook_delivery",
            mapOf(
                "eventId" to java.util.UUID.randomUUID().toString(), "source" to "market", "event" to "market.order.paid", "url" to "https://hooks.invalid/x", "format" to "JSON",
                "signing" to "NONE", "body" to "{}", "status" to "SUCCEEDED", "updatedAt" to now - 200 * day
            )
        )
        val expiredKey = Fixtures.insertRaw(pool, "market_provider_state", mapOf("kind" to "CACHE", "providerId" to "fake", "stateKey" to "a", "value" to "v", "expiresAt" to now - 1))
        val liveKey = Fixtures.insertRaw(pool, "market_provider_state", mapOf("kind" to "CACHE", "providerId" to "fake", "stateKey" to "b", "value" to "v", "expiresAt" to now + day))
        val foreverKey = Fixtures.insertRaw(pool, "market_provider_state", mapOf("kind" to "CACHE", "providerId" to "fake", "stateKey" to "c", "value" to "v"))

        job().retention()

        assertEquals(1L, count("webhook_delivery", "`id` = ?", old), "the market no longer purges the webhook log")
        assertEquals(0L, count("market_provider_state", "`id` = ?", expiredKey))
        assertEquals(2L, count("market_provider_state", "`id` IN (?, ?)", liveKey, foreverKey))
    }

    @Test
    fun `the throttle task deletes the rows that are older than two days and not locked`(): Unit = runBlocking {
        val now = w.clock.now()

        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "COUPON", "subject" to "old", "windowStart" to now - 3 * day))
        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "COUPON", "subject" to "young", "windowStart" to now - day))
        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "COUPON", "subject" to "locked", "windowStart" to now - 3 * day, "lockedUntil" to now + 1_000))

        assertEquals(1, job().run(HousekeepingJob.Task.THROTTLE))
        assertEquals(0L, count("market_throttle", "`subject` = 'old'"))
        assertEquals(1L, count("market_throttle", "`subject` = 'young'"))
        assertEquals(1L, count("market_throttle", "`subject` = 'locked'"))
    }

    @Test
    fun `the tasks run on their own cadence, the credit self-check 60 s after the first tick and then every 6 hours, retention daily`(): Unit = runBlocking {
        val reconciler = CreditReconciler(w.clock, "pano_", { pool }, recheckDelayMs = 0)
        val job = job(reconciler)

        // first tick: everything but the self-check
        val first = order(401)

        job.runOnce()

        assertNull(value("market_order", first, "clientIp"), "retention runs on the first tick")
        assertNull(reconciler.last, "the self-check waits 60 s")

        w.clock.advance(59_000)
        job.runOnce()
        assertNull(reconciler.last)

        w.clock.advance(1_000)
        job.runOnce()

        val firstCheck = reconciler.last

        assertNotNull(firstCheck)
        assertTrue(firstCheck!!.ok)

        // an hour later: no retention yet (daily), no self-check yet (6 h)
        val second = order(401)

        w.clock.advance(HousekeepingJob.HOUR_MS)
        job.runOnce()

        assertEquals("203.0.113.9", value("market_order", second, "clientIp"), "retention is daily")
        assertEquals(firstCheck.checkedAt, reconciler.last!!.checkedAt, "the self-check is every 6 hours")

        // 6 hours after the first check: the self-check again
        w.clock.advance(6 * HousekeepingJob.HOUR_MS)
        job.runOnce()
        assertTrue(reconciler.last!!.checkedAt > firstCheck.checkedAt)
        assertEquals("203.0.113.9", value("market_order", second, "clientIp"))

        // a day after the first retention
        w.clock.advance(day)
        job.runOnce()
        assertNull(value("market_order", second, "clientIp"))
    }

    @Test
    fun `a task that fails stops no other task and the tick does not throw`(): Unit = runBlocking {
        val now = w.clock.now()

        Fixtures.insertRaw(pool, "market_throttle", mapOf("scope" to "COUPON", "subject" to "old", "windowStart" to now - 3 * day))

        // retention fails for real: the table of the events is not there
        sql("RENAME TABLE `pano_market_payment_event` TO `pano_market_payment_event_away`")

        try {
            job().runOnce()
        } finally {
            sql("RENAME TABLE `pano_market_payment_event_away` TO `pano_market_payment_event`")
        }

        assertEquals(0L, count("market_throttle", "`subject` = 'old'"), "the throttle purge ran although retention failed")
    }

    @Test
    fun `an ended subscription without an owner loses its gateway data unless a gateway cancel is still queued`(): Unit = runBlocking {
        fun sub(name: String, state: String) = mapOf(
            "playerUsername" to name, "ownerKey" to "u:$name", "email" to "$name@x.test", "productId" to 1, "productName" to "p", "initialOrderId" to 1, "initialOrderItemId" to 1,
            "providerId" to "fake", "mode" to "GATEWAY", "status" to "CANCELLED", "intervalUnit" to "MONTH", "intervalCount" to 1, "price" to 0, "currency" to "EUR",
            "gatewaySubscriptionId" to "sub_$name", "gatewayCustomerId" to "cus_$name", "storedMethod" to "enc", "storedMethodLabel" to "Visa", "fieldValues" to "{}",
            "providerData" to "enc", "remoteCancelState" to state
        )

        val done = Fixtures.insertRaw(pool, "market_subscription", sub("a", "DONE"))
        val queued = Fixtures.insertRaw(pool, "market_subscription", sub("b", "PENDING"))
        val owned = Fixtures.insertRaw(pool, "market_subscription", sub("c", "DONE") + mapOf("userId" to 5))

        job().run(HousekeepingJob.Task.ERASURE)

        assertNull(value("market_subscription", done, "gatewayCustomerId"))
        assertNull(value("market_subscription", done, "providerData"))
        assertNull(value("market_subscription", done, "email"))
        assertNull(value("market_subscription", done, "storedMethod"))
        assertEquals("sub_a", value("market_subscription", done, "gatewaySubscriptionId"), "the gateway id stays: it is no personal data and refunds look it up")
        assertEquals("cus_b", value("market_subscription", queued, "gatewayCustomerId"), "the queued cancel still needs the customer")
        assertEquals("cus_c", value("market_subscription", owned, "gatewayCustomerId"), "an owned subscription is not touched")
    }
}
