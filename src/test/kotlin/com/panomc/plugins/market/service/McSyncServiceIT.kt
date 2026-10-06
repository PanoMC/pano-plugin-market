package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.DeliveryEvent
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.ResultStatus
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryTransport
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.job.DeliveryJob
import com.panomc.plugins.market.support.FakeMcComponent
import com.panomc.plugins.market.support.FakeMcLink
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.CurrencyType
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * `McSyncService` (08 section 8) driven by [FakeMcComponent] on a real MariaDB (MK-103; tests 56 to 63 of 08 section 20, 19 section 13): the offer of a due
 * server row (D8), the lost offer and the lost result, "requires online" (D11, D12), the version gate and what it keeps (results first), the readiness
 * (`COMPONENT_MISSING`, `OFFLINE`, `VERSION_MISMATCH`, D7), the cancel of a queued row (D17, D15), the re-offer budget and the late positive result (D9, D10, D12),
 * the removed server (D20), the runtime gate and the protocol gate, capacity, the 100 KB budget, the undo gate and D22 before an offer, the wait expiry (D14), `GET /servers` and the
 * purchase announcements. The component is the in-memory twin of the nine requirements of 08 section 8.2; both sides speak the real wire (Gson in, the
 * platform serializer out). The invariants I1 to I22 are checked after every test by the base class.
 */
class McSyncServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld
    private lateinit var link: FakeMcLink
    private lateinit var sync: McSyncService
    private lateinit var mc: FakeMcComponent
    private val ready = AtomicBoolean(true)

    private val version = "1.4.0"
    private val hour = 3_600_000L

    @BeforeEach
    fun wire() {
        ready.set(true)
        w = TestWiring(pool)
        d = DeliveryWorld(w)
        w.configure {
            MarketConfig(
                currency = CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", deliveryMaxAttempts = 5,
                deliveryOnlineWaitDays = 7, mcBroadcast = true, mcBroadcastTemplate = "&a{player} &7bought &f{product} x{quantity} &8@ {store}", storeName = "Shop"
            )
        }
        link = FakeMcLink().also { it.add(7) }
        d.roster.granted = listOf(7L)
        sync = newService()
        mc = FakeMcComponent(sync, 7, w.clock, version)
    }

    private fun newService(db: MarketDb = w.db): McSyncService = McSyncService(
        db, d.locks, w.clock, { w.config }, w.deliveries, w.serverStates, w.orders, w.orderItems, d.service, link, { version }, { ready.get() },
        storeName = { w.config.storeName }
    )

    private fun command(id: String = "c1", requiresOnline: Boolean = false, text: String = "give {username} diamond 1") =
        ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf(text), requiresOnline = requiresOnline)

    private fun revokeCommand() = ProductAction(id = "c2", type = DeliveryActionType.COMMAND, phase = DeliveryPhase.REVOKE, commands = listOf("take {username} diamond 1"))

    /**
     * The window between two transactions of one service: the pool supplier of its [MarketDb] calls [beforeTransaction] every time a transaction starts, and
     * the [action] runs when the [fireAt]-th one of the armed sync starts (a request of the same server that is applied at that moment).
     */
    private class TxTap {
        val calls = AtomicInteger()

        @Volatile
        var fireAt = -1

        @Volatile
        var action: (suspend () -> Unit)? = null

        @Volatile
        var fired = false

        suspend fun beforeTransaction() {
            if (calls.incrementAndGet() == fireAt) {
                fired = true
                action?.invoke()
            }
        }
    }

    private fun serverPermission(id: String, vararg nodes: String) =
        ProductAction(id = id, type = DeliveryActionType.PERMISSION, via = com.panomc.plugins.market.core.delivery.PermissionVia.SERVER, nodes = nodes.toList())

    /** A paid order of [actions] for a registered "Steve"; the server rows are `PENDING` on server 7. */
    private suspend fun buy(vararg actions: ProductAction, buyer: String = "Steve", quantity: Int = 1): Pair<Placed, List<MarketDelivery>> {
        val user = w.fixtures.user(buyer)
        val placed = d.place(buyer = buyer, user = user, actions = actions.toList(), quantity = quantity)

        d.pay(placed)

        return placed to d.rows(placed.order.id)
    }

    private suspend fun row(id: Long) = d.row(id)

    private suspend fun cancel(row: MarketDelivery, reason: String = DeliveryError.CANCELLED_BY_ADMIN) = w.db.txRestartingOnOrderChange { conn ->
        d.locks.forOrder(conn, row.orderId!!, OrderLockScope.PAYMENT) { d.service.apply(conn, row.id, DeliveryEvent.Cancel(reason)) }
    }

    /** What another request of the same server does when it applies [status] for [row]: the machine under the order lock, in a transaction of its own. */
    private suspend fun serverResult(row: MarketDelivery, status: ResultStatus) = w.db.txRestartingOnOrderChange { conn ->
        d.locks.forOrder(conn, row.orderId!!, OrderLockScope.PAYMENT) { d.service.apply(conn, row.id, DeliveryEvent.ServerResult(status)) }
    }

    // ===== offers, results ===============================================================================================

    @Test
    fun `an offer moves a due row to SENT, the component runs it once and its result confirms it (D8, D12)`(): Unit = runBlocking {
        val (placed, rows) = buy(command("c1", text = "give {username} diamond 3"))
        val r = rows.single()

        assertEquals(DeliveryTransport.MARKET_MC, r.transport)
        assertEquals(DeliveryStatus.PENDING, r.status)

        val first = mc.sync()

        assertTrue(first.accepted)
        assertNull(first.reason)
        assertEquals(version, first.wire.getString("marketVersion"))
        assertEquals(listOf(r.idempotencyKey), first.offeredKeys)
        assertEquals(5000L, first.pollAfterMs)

        val offer = first.deliveries.getJsonObject(0)

        assertEquals(r.id, offer.getLong("id"))
        assertEquals("COMMAND", offer.getString("kind"))
        assertEquals("GRANT", offer.getString("phase"))
        assertEquals("Steve", offer.getJsonObject("player").getString("username"))
        assertEquals(listOf("give Steve diamond 3"), offer.getJsonArray("commands").map { it as String })
        assertFalse(offer.getBoolean("requiresOnline"))
        assertEquals("market:order-${placed.order.id}", offer.getString("issuer"))
        assertEquals(placed.order.publicId, offer.getJsonObject("display").getString("orderPublicId"))
        assertEquals("VIP", offer.getJsonObject("display").getString("productName"))
        assertFalse(offer.getJsonObject("display").getBoolean("gift"))

        val sent = row(r.id)

        assertEquals(DeliveryStatus.SENT, sent.status)
        assertEquals(1, sent.attempts)
        assertEquals(w.clock.now(), sent.sentAt)
        assertEquals(w.clock.now() + 30_000L, sent.nextAttemptAt)
        assertEquals(listOf(r.idempotencyKey), mc.executedKeys)
        assertEquals(listOf("give Steve diamond 3"), mc.commandLog)

        val second = mc.sync()

        assertEquals(listOf(r.idempotencyKey), second.resultKeys)
        assertEquals(listOf(r.idempotencyKey), second.acked)
        assertTrue(second.offeredKeys.isEmpty())

        val done = row(r.id)

        assertEquals(DeliveryStatus.CONFIRMED, done.status)
        assertNotNull(done.confirmedAt)
        assertNull(done.lastErrorCode)
        assertEquals(1, JsonObject(done.result).getJsonArray("commands").size())
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)
        assertTrue(mc.sync().resultKeys.isEmpty(), "an acknowledged result is not reported again")
        assertEquals(1, mc.executedKeys.size)
    }

    @Test
    fun `a PERMISSION delivery via the server travels as kind PERMISSION with its node tuple`(): Unit = runBlocking {
        val (_, rows) = buy(serverPermission("p1", "group.vip", "essentials.fly"))
        val r = rows.single()

        val offer = mc.sync().deliveries.getJsonObject(0)

        assertEquals("PERMISSION", offer.getString("kind"))
        assertEquals(listOf<String>(), offer.getJsonArray("commands").map { it as String })
        assertEquals("ADD", offer.getJsonObject("permission").getString("op"))
        assertEquals(listOf("group.vip", "essentials.fly"), offer.getJsonObject("permission").getJsonArray("nodes").map { it as String })
        assertNull(offer.getJsonObject("permission").getLong("expiresAt"))
        assertEquals(listOf("ADD group.vip,essentials.fly"), mc.permissionLog)

        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
    }

    @Test
    fun `a lost offer is offered again after the acknowledgement time, with the same key, and runs once (D9)`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.loseNextResponse()

        val lost = mc.sync()

        assertFalse(lost.delivered)
        assertEquals(listOf(r.idempotencyKey), lost.offeredKeys)
        assertEquals(DeliveryStatus.SENT, row(r.id).status)
        assertTrue(mc.executedKeys.isEmpty(), "the component never saw the offer")

        // before the acknowledgement time nothing is offered again
        w.clock.advance(29_000L)
        assertTrue(mc.sync().offeredKeys.isEmpty())

        w.clock.advance(1_001L)

        val again = mc.sync()

        assertEquals(listOf(r.idempotencyKey), again.offeredKeys)
        assertEquals(2, row(r.id).attempts)
        // ackDelay(2) = 60 s
        assertEquals(w.clock.now() + 60_000L, row(r.id).nextAttemptAt)
        assertEquals(listOf(r.idempotencyKey), mc.executedKeys)

        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
        assertEquals(1, mc.executedKeys.size)
    }

    @Test
    fun `a lost result is reported again by the component, the key is not run twice and is not offered again`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.sync()
        assertEquals(DeliveryStatus.SENT, row(r.id).status)

        // the request that carries DONE never reaches Pano
        mc.loseNextRequest()
        assertFalse(mc.sync().reached)
        assertEquals(DeliveryStatus.SENT, row(r.id).status)

        // the ack time runs out: the result is still on the component, the next request carries it and Pano applies it before it selects offers
        w.clock.advance(31_000L)

        val next = mc.sync()

        assertEquals(listOf(r.idempotencyKey), next.resultKeys)
        assertTrue(next.offeredKeys.isEmpty(), "the result is applied before the offers are selected")
        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
        assertEquals(1, row(r.id).attempts)
        assertEquals(1, mc.executedKeys.size)
    }

    @Test
    fun `a key the component already ran is offered again by an admin retry and answered from its store, not run twice (D19)`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.sync()
        mc.muteResults = true
        mc.sync()
        assertEquals(DeliveryStatus.SENT, row(r.id).status)

        // D19: an immediate re-offer of a SENT row
        w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, r.orderId!!, OrderLockScope.PAYMENT) { d.service.apply(conn, r.id, DeliveryEvent.Retry) }
        }

        val again = mc.sync()

        assertEquals(listOf(r.idempotencyKey), again.offeredKeys)
        assertEquals(2, row(r.id).attempts)

        mc.muteResults = false
        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
        assertEquals(listOf(r.idempotencyKey), mc.executedKeys)
    }

    // ===== requires online ===============================================================================================

    @Test
    fun `requiresOnline is QUEUED on the component, then CONFIRMED once the player is online (D11, D12)`(): Unit = runBlocking {
        val (placed, rows) = buy(command(requiresOnline = true))
        val r = rows.single()

        assertEquals(w.clock.now() + 7 * 86_400_000L, r.waitUntil)

        val first = mc.sync()
        val offer = first.deliveries.getJsonObject(0)

        assertTrue(offer.getBoolean("requiresOnline"))
        assertEquals(r.waitUntil, offer.getLong("expiresAt"))
        assertEquals("QUEUED", mc.stateOf(r.idempotencyKey))
        assertTrue(mc.commandLog.isEmpty())

        mc.sync()

        val queued = row(r.id)

        assertEquals(DeliveryStatus.QUEUED, queued.status)
        assertNull(queued.nextAttemptAt, "a queued row is not re-offered")
        assertEquals(FulfillmentStatus.PENDING, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(1L, sync.servers().single().queuedDeliveries)

        // the ack time runs out: a QUEUED row is never re-offered
        w.clock.advance(2 * hour)
        assertTrue(mc.sync().offeredKeys.isEmpty())
        assertEquals(DeliveryStatus.QUEUED, row(r.id).status)

        mc.online("steve")

        assertEquals(listOf("give Steve diamond 1"), mc.commandLog)

        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(0L, sync.servers().single().queuedDeliveries)
        assertEquals(1, mc.executedKeys.size)
    }

    @Test
    fun `a queued row whose wait ran out an hour ago ends ONLINE_WAIT_EXPIRED (D14), and a component EXPIRED answer afterwards changes nothing`(): Unit = runBlocking {
        val (_, rows) = buy(command(requiresOnline = true))
        val r = rows.single()

        mc.sync()
        mc.sync()
        assertEquals(DeliveryStatus.QUEUED, row(r.id).status)

        // not yet: waitUntil + 1 h
        w.clock.advance(7 * 86_400_000L + hour)
        assertEquals(0, sync.expireWaits())
        assertEquals(DeliveryStatus.QUEUED, row(r.id).status)

        w.clock.advance(1_000L)
        assertEquals(1, sync.expireWaits())

        val failed = row(r.id)

        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertEquals(DeliveryError.ONLINE_WAIT_EXPIRED, failed.lastErrorCode)
        assertEquals(1L, count("market_order_event", "`type` = 'DELIVERY_FAILED'"))

        // the player joins late: the component expires the record itself and reports EXPIRED; a failed row ignores it
        mc.online("Steve")
        assertEquals("EXPIRED", mc.stateOf(r.idempotencyKey))

        val reply = mc.sync()

        assertEquals(listOf(r.idempotencyKey), reply.acked)
        assertEquals(DeliveryStatus.FAILED, row(r.id).status)
        assertTrue(mc.commandLog.isEmpty())
    }

    // ===== cancel ========================================================================================================

    @Test
    fun `the cancel of a QUEUED row goes out in the next response, the component drops it and the row ends CANCELLED (D17, D15)`(): Unit = runBlocking {
        val (placed, rows) = buy(command(requiresOnline = true))
        val r = rows.single()

        mc.sync()
        mc.sync()
        assertEquals(DeliveryStatus.QUEUED, row(r.id).status)

        val applied = cancel(r)

        assertEquals(DeliveryStatus.QUEUED, applied.row!!.status)
        assertNotNull(row(r.id).cancelRequestedAt)

        val withCancel = mc.sync()

        assertEquals(listOf(r.idempotencyKey), withCancel.cancel)
        assertTrue(withCancel.offeredKeys.isEmpty())
        assertEquals("CANCELLED", mc.stateOf(r.idempotencyKey))

        mc.sync()

        val cancelled = row(r.id)

        assertEquals(DeliveryStatus.CANCELLED, cancelled.status)
        assertEquals(DeliveryError.CANCELLED_BY_ADMIN, cancelled.lastErrorCode)
        assertTrue(mc.commandLog.isEmpty(), "nothing was ever run")
        assertTrue(mc.sync().cancel.isEmpty(), "a cancelled row is not in the cancel list again")
        assertEquals(FulfillmentStatus.NONE, d.order(placed.order.id).fulfillmentStatus)
    }

    @Test
    fun `a cancel for a key the component never received is answered UNKNOWN and the row still ends CANCELLED (D15)`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        // the offer is lost: the row is SENT, the component never heard of the key
        mc.loseNextResponse()
        mc.sync()
        assertEquals(DeliveryStatus.SENT, row(r.id).status)
        assertTrue(mc.knownKeys().isEmpty())

        cancel(r)

        val withCancel = mc.sync()

        assertEquals(listOf(r.idempotencyKey), withCancel.cancel)
        assertTrue(withCancel.offeredKeys.isEmpty(), "a row with a pending cancel is not offered again")

        val answer = mc.sync()

        assertEquals(listOf(r.idempotencyKey), answer.resultKeys)
        assertEquals("UNKNOWN", answer.request.results.single().status)
        assertEquals(DeliveryStatus.CANCELLED, row(r.id).status)
        assertEquals(DeliveryError.CANCELLED_BY_ADMIN, row(r.id).lastErrorCode)
        assertTrue(mc.executedKeys.isEmpty())
        assertTrue(mc.sync().resultKeys.isEmpty(), "the UNKNOWN answer was acknowledged")
    }

    @Test
    fun `a cancel that arrives after the component already ran the key loses and DONE confirms the row`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.sync()
        assertEquals(1, mc.executedKeys.size)

        // the result of the run is not at Pano yet when the admin cancels (D17: a request on a row in flight)
        cancel(r)
        assertNotNull(row(r.id).cancelRequestedAt)

        val reply = mc.sync()

        assertEquals(listOf(r.idempotencyKey), reply.resultKeys)
        assertTrue(reply.cancel.isEmpty(), "the result is applied first, the row is CONFIRMED and no longer in the cancel list")
        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
        assertNull(row(r.id).lastErrorCode)
    }

    // ===== failure codes (08 section 18, 19 section 6) =====================================================================

    @Test
    fun `component failure codes are kept, any other code becomes REJECTED with the original in lastError`(): Unit = runBlocking {
        val (placed, rows) = buy(command("c1"), command("c2"), command("c3"), command("c4"), command("c5"))
        val codes = listOf("COMMAND_ERROR", "LUCKPERMS_MISSING", "PERMISSION_ERROR", "INTERRUPTED", "DISABLED_LOCALLY")

        codes.forEach { mc.failNext(it) }

        mc.sync()
        mc.sync()

        val byCode = rows.indices.associate { codes[it] to row(rows[it].id) }

        for (known in listOf("COMMAND_ERROR", "LUCKPERMS_MISSING", "DISABLED_LOCALLY")) {
            val failed = byCode.getValue(known)

            assertEquals(DeliveryStatus.FAILED, failed.status, known)
            assertEquals(known, failed.lastErrorCode, known)
            assertEquals("scripted failure", failed.lastError, known)
        }

        // the codes the component produces that 08 section 18 does not list (MC-02): REJECTED, the original code first in lastError
        for (other in listOf("PERMISSION_ERROR", "INTERRUPTED")) {
            val failed = byCode.getValue(other)

            assertEquals(DeliveryStatus.FAILED, failed.status, other)
            assertEquals(DeliveryError.REJECTED, failed.lastErrorCode, other)
            assertEquals("$other: scripted failure", failed.lastError, other)
        }

        assertEquals(5L, count("market_order_event", "`type` = 'DELIVERY_FAILED'"))
        assertEquals(FulfillmentStatus.FAILED, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(setOf("FAILED"), rows.map { mc.stateOf(it.idempotencyKey) }.toSet())
        assertEquals(codes, rows.map { row(it.id) }.map { it.result }.map { JsonObject(it).getString("code") })
    }

    // ===== version gate, runtime gate, protocol gate ====================================================================

    @Test
    fun `a version that differs from the market version is refused, nothing is offered and the row waits (WAITING_SERVER)`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.version = "1.3.9"

        val refused = mc.sync()

        assertFalse(refused.accepted)
        assertEquals("VERSION_MISMATCH", refused.reason)
        assertEquals(version, refused.wire.getString("marketVersion"))
        assertTrue(refused.deliveries.isEmpty)
        assertEquals("VERSION_MISMATCH", mc.lastRefusal)
        assertEquals(DeliveryStatus.PENDING, row(r.id).status)
        assertEquals(ServerReadiness.VERSION_MISMATCH, sync.readiness(7))

        // the session time passes with the component still syncing: classify labels the row
        repeat(3) {
            w.clock.advance(21_000L)
            mc.sync()
        }

        assertEquals(1, sync.classify())

        val waiting = row(r.id)

        assertEquals(DeliveryStatus.WAITING_SERVER, waiting.status)
        assertEquals(DeliveryError.VERSION_MISMATCH, waiting.lastErrorCode)
        assertNull(waiting.nextAttemptAt)
        assertEquals(0, sync.classify(), "the label is not written again")

        val view = sync.servers().single()

        assertEquals(ServerReadiness.VERSION_MISMATCH, view.marketState)
        assertEquals(1L, view.waitingDeliveries)
        assertEquals("1.3.9", view.mcComponentVersion)
        assertEquals(version, view.requiredVersion)

        // the component is updated: the next accepted sync picks the row up straight from WAITING_SERVER (D8)
        mc.version = version

        val ok = mc.sync()

        assertTrue(ok.accepted)
        assertEquals(listOf(r.idempotencyKey), ok.offeredKeys)
        assertEquals(DeliveryStatus.SENT, row(r.id).status)
        assertNull(row(r.id).lastErrorCode)

        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
        assertEquals(ServerReadiness.READY, sync.readiness(7))
    }

    @Test
    fun `results are applied before the version check, so a component updated late loses nothing`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.sync()
        assertEquals(DeliveryStatus.SENT, row(r.id).status)

        // the component was replaced by another build before it could report DONE
        mc.version = "1.3.9"

        val refused = mc.sync()

        assertFalse(refused.accepted)
        assertEquals("VERSION_MISMATCH", refused.reason)
        assertEquals(listOf(r.idempotencyKey), refused.resultKeys)
        assertEquals(listOf(r.idempotencyKey), refused.acked)
        assertTrue(refused.deliveries.isEmpty)
        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
        assertTrue(mc.sync().resultKeys.isEmpty(), "the refusal's acknowledgement was honoured")
    }

    @Test
    fun `MARKET_NOT_READY while the store is not READY nothing is read, written or applied`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.sync()
        assertEquals(DeliveryStatus.SENT, row(r.id).status)
        assertEquals(1L, count("market_server_state"))

        ready.set(false)

        val refused = mc.sync()

        assertFalse(refused.accepted)
        assertEquals("MARKET_NOT_READY", refused.reason)
        assertEquals(listOf(r.idempotencyKey), refused.resultKeys, "the component still holds the result")
        assertTrue(refused.acked.isEmpty(), "the result was not applied, so it is not acknowledged")
        assertEquals(DeliveryStatus.SENT, row(r.id).status)

        ready.set(true)
        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(r.id).status)
    }

    @Test
    fun `an unsupported protocol is refused before anything is recorded or applied`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.protocol = 2

        val refused = mc.sync()

        assertFalse(refused.accepted)
        assertEquals("PROTOCOL_UNSUPPORTED", refused.reason)
        assertTrue(refused.deliveries.isEmpty)
        assertEquals(DeliveryStatus.PENDING, row(r.id).status)
        assertEquals(0L, count("market_server_state"))
        assertEquals(ServerReadiness.COMPONENT_MISSING, sync.readiness(7))
    }

    // ===== readiness =====================================================================================================

    @Test
    fun `no sync for 60 s is COMPONENT_MISSING and labels the waiting rows, a disconnect is SERVER_OFFLINE, a sync makes it READY`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        assertEquals(ServerReadiness.COMPONENT_MISSING, sync.readiness(7))

        // a fresh service judges nothing during its first session time (sessions are empty after a restart)
        w.clock.advance(59_000L)
        assertEquals(0, sync.classify())
        assertEquals(DeliveryStatus.PENDING, row(r.id).status)

        w.clock.advance(2_000L)
        assertEquals(1, sync.classify())
        assertEquals(DeliveryStatus.WAITING_SERVER, row(r.id).status)
        assertEquals(DeliveryError.COMPONENT_MISSING, row(r.id).lastErrorCode)

        link.disconnect(7)
        assertEquals(ServerReadiness.OFFLINE, sync.readiness(7))
        assertEquals(1, sync.classify())
        assertEquals(DeliveryError.SERVER_OFFLINE, row(r.id).lastErrorCode)
        assertEquals(DeliveryStatus.WAITING_SERVER, row(r.id).status, "a waiting server has no give-up")

        link.connect(7)

        val first = mc.sync()

        assertEquals(listOf(r.idempotencyKey), first.offeredKeys)
        assertEquals(ServerReadiness.READY, sync.readiness(7))

        // 60 s after the last sync is still READY, one millisecond more is not
        w.clock.advance(60_000L)
        assertEquals(ServerReadiness.READY, sync.readiness(7))
        w.clock.advance(1L)
        assertEquals(ServerReadiness.COMPONENT_MISSING, sync.readiness(7))
    }

    @Test
    fun `GET servers lists the accepted servers with their state, versions, waiting and queued counts and settings`(): Unit = runBlocking {
        link.add(8, connected = false)
        d.roster.granted = listOf(7L, 8L)

        buy(command("c1"), command("c2", requiresOnline = true))

        mc.platform = "FABRIC"
        mc.vault = true
        mc.sync()
        mc.sync()

        w.serverStates.updateSettings(7, """{"mcBroadcast":false}""", w.clock.now(), pool)

        val views = sync.servers().associateBy { it.id }
        val a = views.getValue(7)
        val b = views.getValue(8)

        assertEquals(2, views.size)
        assertEquals(ServerReadiness.READY, a.marketState)
        assertTrue(a.connected)
        assertEquals("server7", a.name)
        assertEquals("PAPER", a.type)
        assertFalse(a.proxy)
        assertEquals(version, a.mcComponentVersion)
        assertEquals(version, a.requiredVersion)
        assertEquals("FABRIC", a.platform)
        assertEquals(listOf("luckperms", "vault"), a.integrations)
        assertEquals(1L, a.queuedDeliveries)
        assertEquals(0L, a.waitingDeliveries)
        assertEquals("${McSyncService.DOWNLOAD_URL}?platform=fabric", a.downloadUrl)
        assertEquals(false, a.settings!!.getBoolean("mcBroadcast"))

        assertEquals(ServerReadiness.OFFLINE, b.marketState)
        assertFalse(b.connected)
        assertNull(b.mcComponentVersion)
        assertNull(b.platform)
        assertEquals(2L, b.waitingDeliveries)
        assertEquals(McSyncService.DOWNLOAD_URL, b.downloadUrl)
        assertNull(b.settings)

        val json = a.toJson()

        assertEquals("READY", json["marketState"])
        assertEquals(setOf(
            "id", "name", "type", "connected", "proxy", "mcComponentVersion", "requiredVersion", "marketState", "waitingDeliveries", "queuedDeliveries",
            "downloadUrl", "platform", "integrations", "settings"
        ), json.keys)
    }

    @Test
    fun `market_server_state is written from the sync request only, at most every 30 s while nothing changed`(): Unit = runBlocking {
        buy(command())

        mc.luckPerms = false
        mc.placeholderApi = true
        mc.sync()

        val first = w.serverStates.getByServerId(7, pool)!!

        assertEquals(version, first.mcComponentVersion)
        assertEquals("market-delivery,placeholderapi", first.capabilities)
        assertEquals("PAPER", first.platform)
        assertEquals(1, first.protocol)
        assertEquals(0, first.queuedCount)
        assertEquals(w.clock.now(), first.lastSeenAt)

        // nothing changed, 10 s later: no write (the row keeps its lastSeenAt)
        w.clock.advance(10_000L)
        mc.sync()
        assertEquals(first.lastSeenAt, w.serverStates.getByServerId(7, pool)!!.lastSeenAt)

        // a value changed: written at once
        mc.luckPerms = true
        mc.sync()

        val changed = w.serverStates.getByServerId(7, pool)!!

        assertEquals(w.clock.now(), changed.lastSeenAt)
        assertEquals("market-delivery,luckperms,placeholderapi", changed.capabilities)

        // unchanged for 30 s: written again
        w.clock.advance(30_000L)
        mc.sync()
        assertEquals(w.clock.now(), w.serverStates.getByServerId(7, pool)!!.lastSeenAt)
        assertEquals(1L, count("market_server_state"))
    }

    // ===== re-offer budget, removed server ==============================================================================

    @Test
    fun `the re-offer budget ends FAILED UNKNOWN_OUTCOME, a late DONE still confirms (D9, D10, D12), and the key ran once`(): Unit = runBlocking {
        val (placed, rows) = buy(command())
        val r = rows.single()

        // a component whose results never arrive: deliveryMaxAttempts (5) + 5 offers of the same key
        mc.muteResults = true

        repeat(10) {
            val reply = mc.sync()

            assertEquals(listOf(r.idempotencyKey), reply.offeredKeys, "offer ${it + 1}")

            w.clock.advance(hour + 1_000L)
        }

        assertEquals(10, row(r.id).attempts)
        assertEquals(DeliveryStatus.SENT, row(r.id).status)

        val last = mc.sync()

        assertTrue(last.offeredKeys.isEmpty())

        val failed = row(r.id)

        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertEquals(DeliveryError.UNKNOWN_OUTCOME, failed.lastErrorCode)
        assertEquals(1L, count("market_order_event", "`type` = 'DELIVERY_FAILED'"))
        assertEquals(FulfillmentStatus.FAILED, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(1, mc.executedKeys.size, "the re-offered key was answered from the store")

        // the component finally reports what it did: a positive result always wins
        mc.muteResults = false

        val late = mc.sync()

        assertEquals(listOf(r.idempotencyKey), late.acked)

        val confirmed = row(r.id)

        assertEquals(DeliveryStatus.CONFIRMED, confirmed.status)
        assertNull(confirmed.lastErrorCode)
        assertNotNull(confirmed.confirmedAt)
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(1, mc.executedKeys.size)
    }

    @Test
    fun `a removed server fails every unfinished row of it with SERVER_REMOVED (D20), finished rows stay`(): Unit = runBlocking {
        val (placed, rows) = buy(command("c1"), command("c2", requiresOnline = true), command("c3"))
        val (a, b, c) = rows

        // two rows per sync: a runs, b queues; the second sync confirms a, keeps b queued and offers c, whose result is held back
        mc.capacity = 2
        mc.sync()
        mc.sync()
        assertEquals(DeliveryStatus.CONFIRMED, row(a.id).status)
        assertEquals(DeliveryStatus.QUEUED, row(b.id).status)
        assertEquals(DeliveryStatus.SENT, row(c.id).status)

        mc.muteResults = true
        w.clock.advance(61_000L)
        link.remove(7)

        assertEquals(2, sync.classify())

        assertEquals(DeliveryStatus.CONFIRMED, row(a.id).status)

        for (id in listOf(b.id, c.id)) {
            val failed = row(id)

            assertEquals(DeliveryStatus.FAILED, failed.status)
            assertEquals(DeliveryError.SERVER_REMOVED, failed.lastErrorCode)
        }

        assertEquals(2L, count("market_order_event", "`type` = 'DELIVERY_FAILED'"))
        assertEquals(FulfillmentStatus.PARTIAL, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(0, sync.classify())
    }

    // ===== capacity, budget, bookkeeping ================================================================================

    @Test
    fun `capacity limits the offers, more waiting is pollAfterMs 0, and the capacity is clamped to 20`(): Unit = runBlocking {
        val u = w.fixtures.user("Steve")
        val placed = d.place(buyer = "Steve", user = u, actions = listOf(command("c1")), quantity = 1)

        d.pay(placed)

        // 24 more server rows of other orders
        repeat(24) {
            val other = d.place(buyer = "Steve", user = u, actions = listOf(command("c1")), quantity = 1)

            d.pay(other)
        }

        mc.capacity = 0

        val none = mc.sync()

        assertTrue(none.offeredKeys.isEmpty())
        assertEquals(0L, none.pollAfterMs, "work is waiting but the component has no room")

        mc.capacity = 99

        val first = mc.sync()

        assertEquals(20, first.offeredKeys.size)
        assertEquals(0L, first.pollAfterMs)

        // ascending id, the oldest first
        val ids = (0 until 20).map { first.deliveries.getJsonObject(it).getLong("id") }

        assertEquals(ids.sorted(), ids)

        mc.capacity = 20

        val second = mc.sync()

        assertEquals(5, second.offeredKeys.size)
        assertEquals(5000L, second.pollAfterMs)
        assertEquals(25L, count("market_delivery", "`status` = 'SENT'") + count("market_delivery", "`status` = 'CONFIRMED'"))
    }

    @Test
    fun `the response stays within 100 KB and offers that do not fit are not marked sent`(): Unit = runBlocking {
        val u = w.fixtures.user("Steve")
        val long = "x".repeat(990)
        val actions = (1..8).map { i -> ProductAction(id = "c$i", type = DeliveryActionType.COMMAND, commands = List(20) { n -> "say $long$i$n" }) }
        val placed = d.place(buyer = "Steve", user = u, actions = actions)

        d.pay(placed)

        assertEquals(8, d.rows(placed.order.id).size)

        val reply = mc.sync()
        val bytes = reply.wire.encode().toByteArray(Charsets.UTF_8).size

        assertTrue(bytes <= McSyncService.MAX_RESPONSE_BYTES + 512, "response was $bytes bytes")
        assertTrue(reply.offeredKeys.size in 1..6, "some, not all, were offered: ${reply.offeredKeys.size}")
        assertEquals(0L, reply.pollAfterMs, "the rest is waiting")

        val sent = d.rows(placed.order.id).filter { it.status == DeliveryStatus.SENT }.map { it.idempotencyKey }

        assertEquals(reply.offeredKeys.toSet(), sent.toSet())
        assertEquals(1, d.rows(placed.order.id).filter { it.status == DeliveryStatus.SENT }.maxOf { it.attempts })

        var guard = 0

        while (d.rows(placed.order.id).any { it.status != DeliveryStatus.CONFIRMED } && guard++ < 10) mc.sync()

        assertTrue(d.rows(placed.order.id).all { it.status == DeliveryStatus.CONFIRMED })
        assertEquals(8, mc.executedKeys.size)
    }

    @Test
    fun `an unknown key and a key of another server are acknowledged and change nothing`(): Unit = runBlocking {
        link.add(8)
        d.roster.granted = listOf(8L)

        val (_, rows) = buy(command())
        val other = rows.single()

        assertEquals(8L, other.serverId)

        // component 7 invents results for a key of server 8 and for a key nobody planned
        val forged = FakeMcComponent(sync, 7, w.clock, version)
        val server8 = FakeMcComponent(sync, 8, w.clock, version)

        server8.sync()
        assertEquals(DeliveryStatus.SENT, row(other.id).status)

        forged.invent(other.idempotencyKey, "DONE")
        forged.invent("no-such-key:a1:7:0:GRANT:0", "FAILED")

        val reply = forged.sync()

        assertEquals(setOf(other.idempotencyKey, "no-such-key:a1:7:0:GRANT:0"), reply.acked.toSet())
        assertEquals(DeliveryStatus.SENT, row(other.id).status)
        assertNull(row(other.id).confirmedAt)
    }

    @Test
    fun `an unknown result status is acknowledged and ignored, results beyond 100 are ignored and not acknowledged`(): Unit = runBlocking {
        val (_, rows) = buy(command())
        val r = rows.single()

        mc.sync()

        val forged = FakeMcComponent(sync, 7, w.clock, version)

        forged.invent(r.idempotencyKey, "SOMETHING_NEW")

        val reply = forged.sync()

        assertEquals(listOf(r.idempotencyKey), reply.acked)
        assertEquals(DeliveryStatus.SENT, row(r.id).status)

        val many = FakeMcComponent(sync, 7, w.clock, version)

        many.maxResults = 1000

        repeat(105) { many.invent("ghost-$it", "DONE") }

        val big = many.sync()

        assertEquals(105, big.resultKeys.size)
        assertEquals(100, big.acked.size)
        assertEquals((0 until 100).map { "ghost-$it" }, big.acked)
    }

    // ===== undo rows =====================================================================================================

    @Test
    fun `an undo row is held while its grant is in flight and offered once the grant result arrived (11_4)`(): Unit = runBlocking {
        val revokeCommand = ProductAction(id = "c2", type = DeliveryActionType.COMMAND, phase = DeliveryPhase.REVOKE, commands = listOf("take {username} diamond 1"))
        val (placed, rows) = buy(command("c1"), revokeCommand)
        val grant = rows.single()

        mc.sync()
        mc.muteResults = true
        assertEquals(DeliveryStatus.SENT, row(grant.id).status)

        d.revoke(placed)

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertEquals(DeliveryStatus.PENDING, undo.status)

        // the grant is in flight: the undo is not offered
        val held = mc.sync()

        assertTrue(held.offeredKeys.isEmpty())
        assertEquals(DeliveryStatus.PENDING, row(undo.id).status)

        // the result arrives: the grant is CONFIRMED and the undo goes out in the same response
        mc.muteResults = false

        val released = mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(grant.id).status)
        assertEquals(listOf(undo.idempotencyKey), released.offeredKeys)
        assertEquals("REVOKE", released.deliveries.getJsonObject(0).getString("phase"))

        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(undo.id).status)
        assertEquals(listOf("give Steve diamond 1", "take Steve diamond 1"), mc.commandLog)
    }

    @Test
    fun `an undo row of a grant that never took effect is cancelled instead of offered (D22)`(): Unit = runBlocking {
        val revokeCommand = ProductAction(id = "c2", type = DeliveryActionType.COMMAND, phase = DeliveryPhase.REVOKE, commands = listOf("take {username} diamond 1"))
        val (placed, rows) = buy(command("c1"), revokeCommand)
        val grant = rows.single()

        // refund before the component ever asked: the unsent grant is cancelled, the explicit undo would remove goods that were never given
        d.revoke(placed)

        assertEquals(DeliveryStatus.CANCELLED, row(grant.id).status)

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertEquals(DeliveryStatus.PENDING, undo.status)

        val reply = mc.sync()

        assertTrue(reply.offeredKeys.isEmpty())
        assertEquals(DeliveryStatus.CANCELLED, row(undo.id).status)
        assertEquals(DeliveryError.NOTHING_TO_REVOKE, row(undo.id).lastErrorCode)
        assertTrue(mc.commandLog.isEmpty())
    }

    @Test
    fun `undo rows with an open gate on a server that is not ready do not keep a ready server from its D22 check (starvation)`(): Unit = runBlocking {
        val user = w.fixtures.user("Steve")
        val away = FakeMcComponent(sync, 8, w.clock, version)

        link.add(8)
        d.roster.granted = listOf(8L)

        // 101 orders on server 8: every grant is run and CONFIRMED, then every order is refunded: 101 open-gate REVOKE rows whose grant took effect
        val far = (1..101).map { d.place(buyer = "Steve", user = user, actions = listOf(command("c1"), revokeCommand())).also { d.pay(it) } }

        do {
            val reply = away.sync()
        } while (reply.offeredKeys.isNotEmpty() || reply.resultKeys.isNotEmpty())

        assertEquals(101, away.executedKeys.size)

        for (p in far) d.revoke(p)

        link.disconnect(8)

        val held = far.map { p -> d.rows(p.order.id).single { it.phase == DeliveryPhase.REVOKE } }

        assertTrue(held.all { it.serverId == 8L && it.status == DeliveryStatus.PENDING })
        assertTrue(far.all { p -> d.rows(p.order.id).single { it.phase == DeliveryPhase.GRANT }.status == DeliveryStatus.CONFIRMED })

        // server 7: a refund before the component ever asked; its undo row is newer than all 101 of server 8
        d.roster.granted = listOf(7L)

        val near = d.place(buyer = "Steve", user = user, actions = listOf(command("c1"), revokeCommand()))

        d.pay(near)
        d.revoke(near)

        val undo = d.rows(near.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertEquals(7L, undo.serverId)
        assertEquals(DeliveryStatus.PENDING, undo.status)
        assertTrue(undo.id > held.maxOf { it.id })

        val reply = mc.sync()

        assertTrue(reply.offeredKeys.isEmpty(), "the revoke of a grant that never ran must not be offered")
        assertEquals(DeliveryStatus.CANCELLED, row(undo.id).status)
        assertEquals(DeliveryError.NOTHING_TO_REVOKE, row(undo.id).lastErrorCode)
        assertTrue(mc.commandLog.isEmpty())
        assertTrue(held.all { row(it.id).status == DeliveryStatus.PENDING }, "the rows of the server that is not ready are left alone")
    }

    @Test
    fun `a queued requiresOnline grant that the component cancels takes its undo row with it in the same sync (08 section 20, 60 and 65)`(): Unit = runBlocking {
        val (placed, rows) = buy(command("c1", requiresOnline = true), revokeCommand())
        val grant = rows.single()

        mc.sync()
        mc.sync()
        assertEquals(DeliveryStatus.QUEUED, row(grant.id).status)

        // the refund: the cancel of the queued grant is requested, the undo row waits behind it
        d.revoke(placed)

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertNotNull(row(grant.id).cancelRequestedAt)
        assertEquals(DeliveryStatus.PENDING, undo.status)

        val withCancel = mc.sync()

        assertEquals(listOf(grant.idempotencyKey), withCancel.cancel)
        assertTrue(withCancel.offeredKeys.isEmpty(), "the undo row is held while its grant is queued")
        assertEquals("CANCELLED", mc.stateOf(grant.idempotencyKey))
        assertEquals(DeliveryStatus.PENDING, row(undo.id).status)

        // the sync that carries the component's answer: the grant ends CANCELLED and the undo row NOTHING_TO_REVOKE, nothing runs
        val answer = mc.sync()

        assertEquals(listOf(grant.idempotencyKey), answer.resultKeys)
        assertTrue(answer.offeredKeys.isEmpty())
        assertEquals(DeliveryStatus.CANCELLED, row(grant.id).status)
        assertEquals(DeliveryStatus.CANCELLED, row(undo.id).status)
        assertEquals(DeliveryError.NOTHING_TO_REVOKE, row(undo.id).lastErrorCode)
        assertTrue(mc.commandLog.isEmpty(), "neither the grant nor the revoke command ran")
        assertTrue(mc.executedKeys.isEmpty())
        assertTrue(mc.sync().offeredKeys.isEmpty())
    }

    @Test
    fun `an undo row whose gate opens after the D22 check is not offered in that sync, the next sync checks it (race)`(): Unit = runBlocking {
        val tap = TxTap()
        val tapped = newService(MarketDb({ tap.beforeTransaction(); pool }, w.clock))
        val component = FakeMcComponent(tapped, 7, w.clock, version)
        val (placed, rows) = buy(command("c1", requiresOnline = true), revokeCommand())
        val grant = rows.single()

        component.sync()
        component.sync()
        assertEquals(DeliveryStatus.QUEUED, row(grant.id).status)

        d.revoke(placed)

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertNotNull(row(grant.id).cancelRequestedAt)
        assertEquals(DeliveryStatus.PENDING, undo.status)

        // syncs that report nothing: the component takes the cancel (the first one writes the changed queue length to market_server_state) and the undo row
        // stays held. Once that settled, the third one shows how many transactions such a sync opens; the last of them is the offer.
        component.muteResults = true

        repeat(2) { component.sync() }
        tap.calls.set(0)

        val quiet = component.sync()
        val perSync = tap.calls.get()

        assertTrue(quiet.offeredKeys.isEmpty())
        assertEquals(DeliveryStatus.PENDING, row(undo.id).status)

        // the same sync again, but just before its offer transaction another request of the server applies the component's CANCELLED answer: the gate of
        // the undo row opens after the check looked at it
        tap.calls.set(0)
        tap.fireAt = perSync
        tap.action = { serverResult(grant, ResultStatus.CANCELLED) }

        val raced = component.sync()

        assertTrue(tap.fired, "the answer of the other request was applied inside the window")
        assertEquals(DeliveryStatus.CANCELLED, row(grant.id).status)
        assertTrue(raced.offeredKeys.isEmpty(), "an undo row that was not checked in this sync is not offered")
        assertEquals(DeliveryStatus.PENDING, row(undo.id).status)
        assertTrue(component.commandLog.isEmpty())

        // the next sync checks it: the grant never took effect
        tap.action = null
        component.muteResults = false

        val next = component.sync()

        assertTrue(next.offeredKeys.isEmpty())
        assertEquals(DeliveryStatus.CANCELLED, row(undo.id).status)
        assertEquals(DeliveryError.NOTHING_TO_REVOKE, row(undo.id).lastErrorCode)
        assertTrue(component.commandLog.isEmpty())
        assertTrue(component.executedKeys.isEmpty())
    }

    @Test
    fun `an undo row that was offered and lost is offered again with the same key (re-offer needs no new check)`(): Unit = runBlocking {
        val (placed, rows) = buy(command("c1"), revokeCommand())
        val grant = rows.single()

        mc.sync()
        mc.sync()
        assertEquals(DeliveryStatus.CONFIRMED, row(grant.id).status)

        d.revoke(placed)

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        mc.loseNextResponse()
        assertEquals(listOf(undo.idempotencyKey), mc.sync().offeredKeys)
        assertEquals(DeliveryStatus.SENT, row(undo.id).status)
        assertTrue(mc.knownKeys().none { it == undo.idempotencyKey })

        w.clock.advance(31_000L)

        val again = mc.sync()

        assertEquals(listOf(undo.idempotencyKey), again.offeredKeys)
        assertEquals(2, row(undo.id).attempts)

        mc.sync()

        assertEquals(DeliveryStatus.CONFIRMED, row(undo.id).status)
        assertEquals(listOf("give Steve diamond 1", "take Steve diamond 1"), mc.commandLog)
    }

    // ===== concurrency ===================================================================================================

    @Test
    fun `two syncs of one server at the same moment offer each row once`(): Unit = runBlocking {
        val user = w.fixtures.user("Steve")

        repeat(Race.rounds) { round ->
            val keys = ArrayList<String>()

            repeat(6) {
                val placed = d.place(buyer = "Steve", user = user, actions = listOf(command("c1")))

                d.pay(placed)
                keys += d.rows(placed.order.id).single().idempotencyKey
            }

            val a = FakeMcComponent(sync, 7, w.clock, version)
            val b = FakeMcComponent(sync, 7, w.clock, version)
            val results = Race.run(2) { i -> (if (i == 0) a else b).sync() }
            val replies = results.map { it.getOrThrow() }
            val offered = replies.flatMap { it.offeredKeys }

            assertEquals(6, offered.size, "round $round: every row is offered, none twice")
            assertEquals(6, offered.toSet().size)
            assertTrue(offered.containsAll(keys))

            for (key in keys) assertEquals(1, w.deliveries.getByIdempotencyKey(key, pool)!!.attempts, "round $round: $key")

            // leave the server clean for the next round: both components report, the rows are CONFIRMED
            a.sync()
            b.sync()
            w.clock.advance(hour)
        }
    }

    // ===== the job ==============================================================================================================

    @Test
    fun `DeliveryJob drives the server rows with classify after 60 s and expire waits, and does nothing when no server service is given`(): Unit = runBlocking {
        val (_, rows) = buy(command("c1"), command("c2", requiresOnline = true))
        val (a, b) = rows
        val job = DeliveryJob(d.service, w.clock, servers = sync)
        val plain = DeliveryJob(d.service, w.clock)

        // the first classify of the job is due 60 s after its creation; the sessions of the sync service are empty until then too
        w.clock.advance(61_000L)
        plain.runOnce()
        assertEquals(DeliveryStatus.PENDING, row(a.id).status, "a job without a server service never touches server rows")

        job.runOnce()

        assertEquals(DeliveryStatus.WAITING_SERVER, row(a.id).status)
        assertEquals(DeliveryError.COMPONENT_MISSING, row(a.id).lastErrorCode)
        assertEquals(DeliveryStatus.WAITING_SERVER, row(b.id).status)

        // the component comes up, both rows go out, the second is queued
        mc.sync()
        mc.sync()
        assertEquals(DeliveryStatus.CONFIRMED, row(a.id).status)
        assertEquals(DeliveryStatus.QUEUED, row(b.id).status)

        // far past waitUntil + 1 h: expire waits runs (every 60 s) in the same job
        w.clock.advance(8 * 86_400_000L)
        job.runOnce()

        assertEquals(DeliveryStatus.FAILED, row(b.id).status)
        assertEquals(DeliveryError.ONLINE_WAIT_EXPIRED, row(b.id).lastErrorCode)
    }

    // ===== purchase announcements ===============================================================================================

    @Test
    fun `an announcement is offered once per ready server whose broadcast is on, rendered with colour codes and a clean name`(): Unit = runBlocking {
        val (placed, _) = buy(command(), buyer = "Steve")

        // no server has synced yet: nobody to tell
        assertEquals(0, sync.announce(placed.order, placed.items))

        mc.sync()

        assertEquals(1, sync.announce(placed.order, placed.items))

        val reply = mc.sync()

        assertEquals(1, reply.broadcasts.size())
        assertEquals("§aSteve §7bought §fVIP x1 §8@ Shop", reply.broadcasts.getJsonObject(0).getString("text"))
        assertTrue(reply.broadcasts.getJsonObject(0).getLong("id") > 0)
        assertEquals(0, mc.sync().broadcasts.size(), "an announcement leaves in exactly one response")
        assertEquals(0, sync.broadcasts.size(7))
    }

    @Test
    fun `an announcement is skipped for hidden and test orders and where the server override or the default turns it off`(): Unit = runBlocking {
        val (placed, _) = buy(command())

        mc.sync()

        val order = placed.order

        assertEquals(0, sync.announce(com.panomc.plugins.market.db.model.MarketOrder(id = order.id, playerUsername = "Steve", recipientUsername = "Steve", hideFromBroadcast = true), placed.items))
        assertEquals(0, sync.announce(com.panomc.plugins.market.db.model.MarketOrder(id = order.id, playerUsername = "Steve", recipientUsername = "Steve", testMode = true), placed.items))
        assertEquals(0, sync.announce(order, emptyList()))

        // the per-server override beats the panel default in both directions
        w.serverStates.updateSettings(7, """{"mcBroadcast":false}""", w.clock.now(), pool)
        assertEquals(0, sync.announce(order, placed.items))

        w.configure { MarketConfig(currency = CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", mcBroadcast = false) }
        assertEquals(0, sync.announce(order, placed.items))

        w.serverStates.updateSettings(7, """{"mcBroadcast":true}""", w.clock.now(), pool)
        assertEquals(1, sync.announce(order, placed.items))
        assertEquals(1, mc.sync().broadcasts.size())
    }

    @Test
    fun `the broadcast queue of a server holds at most 50 announcements and drops the oldest`(): Unit = runBlocking {
        val outbox = McBroadcastOutbox(w.clock)
        val ids = (1..60).map { outbox.offer(7, "text $it") }

        assertEquals(60, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
        assertEquals(50, outbox.size(7))

        val all = outbox.drain(7)

        assertEquals((11..60).map { "text $it" }, all.map { it.text })
        assertEquals(0, outbox.size(7))
        assertTrue(outbox.drain(8).isEmpty())
    }

    @Test
    fun `the sync offers server rows only, inline rows stay with the delivery job`(): Unit = runBlocking {
        // a server row of server 7 and an inline credit row of the same order
        val credit = ProductAction(id = "a1", type = DeliveryActionType.CREDIT, credit = 500)
        val (_, rows) = buy(credit, command("c1"))
        val server = rows.single { it.transport == DeliveryTransport.MARKET_MC }
        val inline = rows.single { it.transport == DeliveryTransport.INLINE }

        val reply = mc.sync()

        assertEquals(listOf(server.idempotencyKey), reply.offeredKeys)
        assertEquals(DeliveryStatus.PENDING, row(inline.id).status)
    }
}
