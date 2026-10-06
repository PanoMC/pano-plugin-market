package com.panomc.plugins.market.notification

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.ThrottleScope
import com.panomc.plugins.market.service.ServerReadiness
import com.panomc.plugins.market.service.ServerView
import com.panomc.plugins.market.service.ThrottleService
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The panel alerts of MK-172 on a real MariaDB (08 section 8.5, 15 sections 2.6 and 8.1): notifications are sent only when the host registry exists, at most once
 * per server per 24 hours (in memory and durably in `market_throttle` scope `DELIVERY_ALERT`), review and refund / dispute alerts once per order and reason, the
 * urgent undo alert once per row without the server throttle, and the 5-minute sweep that finds them.
 */
class AlertNotificationIT : MarketDaoITBase() {
    /** Raw delivery rows without orders on purpose: the sweep only reads them. */
    override suspend fun assertInvariants() {}

    private class Recorder : PanelNotificationSink {
        val sent = mutableListOf<AlertMessage>()
        var failNext = false

        override suspend fun send(message: AlertMessage) {
            if (failNext) {
                failNext = false

                throw IllegalStateException("host is down")
            }

            sent += message
        }
    }

    private lateinit var w: TestWiring
    private lateinit var clock: FakeClock
    private lateinit var sink: Recorder
    private var enabled = true
    private var sinkAvailable = true

    @BeforeEach
    fun fresh() {
        runBlocking { resetState() }

        w = TestWiring(pool)
        clock = w.clock
        sink = Recorder()
        enabled = true
        sinkAvailable = true
    }

    private fun throttle() = ThrottleService(w.throttles, { pool }, clock)

    /** A new instance = a restart: nothing in memory, the durable half only. */
    private fun alerts() = MarketAlerts(clock, { enabled }, { if (sinkAvailable) sink else null }, throttle())

    private suspend fun alertRows() = count("market_throttle", "`scope` = ?", ThrottleScope.DELIVERY_ALERT)

    // ------------------------------------------------------------------------------------------------------------------ gate

    @Test
    fun `without the registry nothing is sent and no throttle row is written`() = runBlocking {
        enabled = false
        val a = alerts()

        a.reviewOpened(7, "LATE")
        a.alert(7, "OVER_REFUND", JsonObject())
        assertFalse(a.deliveryWaiting(3, "Lobby", 2))
        assertFalse(a.deliveryUrgent(7, 11, 3, "Lobby", true))

        assertTrue(sink.sent.isEmpty())
        assertEquals(0, alertRows())

        // the registry shows up later (host upgraded): the same alert is now sent, it was never consumed
        enabled = true

        assertTrue(a.deliveryWaiting(3, "Lobby", 2))
        assertEquals(1, sink.sent.size)
    }

    @Test
    fun `a sink that cannot be built sends nothing and consumes nothing`() = runBlocking {
        sinkAvailable = false

        assertFalse(alerts().deliveryWaiting(3, "Lobby", 2))
        assertEquals(0, alertRows())
    }

    // ------------------------------------------------------------------------------------------------------------------ throttle

    @Test
    fun `a waiting server is announced once per 24 hours`() = runBlocking {
        val a = alerts()

        assertTrue(a.deliveryWaiting(3, "Lobby", 2))
        assertFalse(a.deliveryWaiting(3, "Lobby", 5))
        assertTrue(a.deliveryWaiting(4, "Survival", 1), "another server has its own throttle")

        clock.advance(23 * 3_600_000L)

        assertFalse(a.deliveryWaiting(3, "Lobby", 5))

        clock.advance(2 * 3_600_000L)

        assertTrue(a.deliveryWaiting(3, "Lobby", 5))
        assertEquals(listOf(3L, 4L, 3L), sink.sent.map { (it.fields["serverId"] as Number).toLong() })
    }

    @Test
    fun `the throttle survives a restart through market_throttle`() = runBlocking {
        assertTrue(alerts().deliveryWaiting(3, "Lobby", 2))
        assertEquals(1, alertRows())

        val restarted = alerts()

        assertFalse(restarted.deliveryWaiting(3, "Lobby", 2))
        assertEquals(1, sink.sent.size)

        clock.advance(25 * 3_600_000L)

        assertTrue(restarted.deliveryWaiting(3, "Lobby", 2))
        assertEquals(2, sink.sent.size)
    }

    @Test
    fun `the durable lock is not purged by the housekeeping while it is in force`() = runBlocking {
        assertTrue(alerts().deliveryUrgent(7, 11, 3, "Lobby", true))

        clock.advance(3 * 86_400_000L)
        throttle().purge()

        assertFalse(alerts().deliveryUrgent(7, 11, 3, "Lobby", true), "30 days of silence for one row")
    }

    @Test
    fun `a send that fails gives the subject back so the next occurrence tries again`() = runBlocking {
        val a = alerts()

        sink.failNext = true

        assertFalse(a.deliveryWaiting(3, "Lobby", 2))
        assertEquals(0, alertRows())
        assertTrue(sink.sent.isEmpty())

        assertTrue(a.deliveryWaiting(3, "Lobby", 2))
        assertEquals(1, sink.sent.size)
    }

    // ------------------------------------------------------------------------------------------------------------------ messages

    @Test
    fun `review alert goes to the payment holders and links the order, once per reason`() = runBlocking {
        val a = alerts()

        a.reviewOpened(7, "LATE")
        a.reviewOpened(7, "LATE")
        a.reviewOpened(7, "AMOUNT_MISMATCH")
        a.reviewOpened(8, null)

        assertEquals(3, sink.sent.size)

        val first = sink.sent[0]

        assertEquals(AlertTypes.ORDER_REVIEW, first.type)
        assertEquals(AlertAudience.PAYMENTS, first.audience)
        assertEquals(7L, first.fields["orderId"])
        assertEquals("LATE", first.fields["reason"])
        assertEquals("/market/orders/detail/7", first.fields["href"])
    }

    @Test
    fun `refund and dispute codes become order alerts once per order and code`() = runBlocking {
        val a = alerts()

        a.alert(7, "OVER_REFUND", JsonObject().put("refundId", 1))
        a.alert(7, "OVER_REFUND", JsonObject().put("refundId", 2))
        a.alert(7, "CHARGEBACK_OPENED", JsonObject())

        assertEquals(listOf("OVER_REFUND", "CHARGEBACK_OPENED"), sink.sent.map { it.fields["code"] })
        assertTrue(sink.sent.all { it.type == AlertTypes.ORDER_ALERT && it.audience == AlertAudience.PAYMENTS })
    }

    @Test
    fun `delivery alerts go to the fulfilment holders`() = runBlocking {
        val a = alerts()

        a.deliveryWaiting(3, "Lobby", 4)
        a.deliveryUrgent(7, 11, 3, "Lobby", failed = false)

        val waiting = sink.sent[0]
        val urgent = sink.sent[1]

        assertEquals(AlertAudience.FULFILMENT, waiting.audience)
        assertEquals(4L, waiting.fields["count"])
        assertEquals(false, waiting.fields["urgent"])
        assertEquals(MarketAlerts.DELIVERIES_HREF, waiting.fields["href"])
        assertEquals("WAITING", waiting.fields["kind"])
        assertEquals("UNDO_WAITING", urgent.fields["kind"])
        assertEquals(true, urgent.fields["urgent"])
        assertEquals(false, urgent.fields["failed"])
        assertEquals(7L, urgent.fields["orderId"])
        assertEquals(11L, urgent.fields["deliveryId"])
        assertEquals("/market/orders/detail/7", urgent.fields["href"])
    }

    // ------------------------------------------------------------------------------------------------------------------ sweep

    private fun server(id: Long, name: String, state: ServerReadiness, waiting: Long) =
        ServerView(id, name, "SPIGOT", state != ServerReadiness.OFFLINE, false, null, "1.0.0", state, waiting, 0, "/dl", null, emptyList(), null)

    private suspend fun delivery(server: Long, status: String, phase: String = "GRANT", order: Long? = 7, age: Long) =
        Fixtures.insertRaw(
            pool, "market_delivery",
            mapOf("serverId" to server, "status" to status, "phase" to phase, "orderId" to order, "idempotencyKey" to "k-${System.nanoTime()}", "createdAt" to clock.now() - age)
        )

    private fun sweep(servers: List<ServerView>, alerts: MarketAlerts = alerts()) =
        DeliveryAlertSweep(clock, alerts, { enabled }, { prefix }, { pool }, { servers })

    private val minute = 60_000L

    @Test
    fun `the sweep announces a not ready server whose oldest waiting delivery is older than 10 minutes`() = runBlocking {
        delivery(3, "WAITING_SERVER", age = 11 * minute)
        delivery(4, "WAITING_SERVER", age = 9 * minute)
        delivery(5, "WAITING_SERVER", age = 60 * minute)

        val sent = sweep(
            listOf(
                server(3, "Lobby", ServerReadiness.COMPONENT_MISSING, 1),
                server(4, "Young", ServerReadiness.OFFLINE, 1),
                server(5, "Ready", ServerReadiness.READY, 1),
                server(6, "Empty", ServerReadiness.OFFLINE, 0)
            )
        ).runOnce()

        assertEquals(1, sent)
        assertEquals(3L, sink.sent.single().fields["serverId"])
        assertEquals("Lobby", sink.sent.single().fields["serverName"])
        assertEquals(1L, sink.sent.single().fields["count"])
    }

    @Test
    fun `the sweep repeats nothing within 24 hours and again after`() = runBlocking {
        delivery(3, "PENDING", age = 30 * minute)

        val s = sweep(listOf(server(3, "Lobby", ServerReadiness.VERSION_MISMATCH, 1)))

        assertEquals(1, s.runOnce())

        clock.advance(5 * minute)

        assertEquals(0, s.runOnce())

        clock.advance(24 * 3_600_000L)

        assertEquals(1, s.runOnce())
    }

    @Test
    fun `a failed undo is urgent at once, a waiting one after 10 minutes, once per row, grants are not urgent`() = runBlocking {
        val failed = delivery(3, "FAILED", "REVOKE", 7, age = 1 * minute)
        val waiting = delivery(3, "WAITING_SERVER", "EXPIRE", 8, age = 12 * minute)
        delivery(3, "WAITING_SERVER", "REVOKE", 9, age = 2 * minute)
        delivery(3, "FAILED", "GRANT", 10, age = 99 * minute)
        delivery(3, "CONFIRMED", "REVOKE", 11, age = 99 * minute)

        val s = sweep(listOf(server(3, "Lobby", ServerReadiness.READY, 0)))

        assertEquals(2, s.runOnce())
        assertEquals(setOf(failed, waiting), sink.sent.map { (it.fields["deliveryId"] as Number).toLong() }.toSet())

        val byRow = sink.sent.associateBy { (it.fields["deliveryId"] as Number).toLong() }

        assertEquals("UNDO_FAILED", byRow.getValue(failed).fields["kind"])
        assertEquals("UNDO_WAITING", byRow.getValue(waiting).fields["kind"])
        assertEquals(true, byRow.getValue(failed).fields["failed"])
        assertEquals(false, byRow.getValue(waiting).fields["failed"])
        assertEquals("Lobby", byRow.getValue(failed).fields["serverName"])
        assertEquals(0, s.runOnce(), "once per row")
    }

    @Test
    fun `the sweep reads and sends nothing without the registry`() = runBlocking {
        enabled = false
        delivery(3, "FAILED", "REVOKE", 7, age = 1 * minute)

        assertEquals(0, sweep(listOf(server(3, "Lobby", ServerReadiness.OFFLINE, 1))).runOnce())
        assertTrue(sink.sent.isEmpty())
        assertEquals(0, alertRows())
    }

    @Test
    fun `an urgent alert does not use up the server throttle`() = runBlocking {
        delivery(3, "FAILED", "REVOKE", 7, age = 20 * minute)
        delivery(3, "WAITING_SERVER", "GRANT", 8, age = 20 * minute)

        assertEquals(2, sweep(listOf(server(3, "Lobby", ServerReadiness.OFFLINE, 2))).runOnce())
        assertEquals(setOf(true, false), sink.sent.map { it.fields["urgent"] }.toSet())
        assertNull(sink.sent.firstOrNull { it.fields["urgent"] == false }?.fields?.get("deliveryId"))
    }
}
