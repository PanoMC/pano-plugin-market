package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.CreditsProvider
import com.panomc.plugins.market.provider.FreeProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakeMcComponent
import com.panomc.plugins.market.support.FakeMcLink
import com.panomc.plugins.market.support.SeqIds
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WIRE-3: the web purchase announcement, the open seam of MK-103 / MC-04 ("nothing calls `McSyncService.announce` after the order transaction of O2 commits"), driven
 * through the real services on a real MariaDB (evidence/WIRE-3.md). Both sides are real: `OrderService` stamps the order paid and hands `AfterCommit.OrderPaid`
 * to `PaymentService`, which asks [PurchaseAnnouncements], which calls the real [McSyncService]; the component is [FakeMcComponent] over the real wire.
 */
class Wire3IT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: CheckoutHarness
    private lateinit var d: DeliveryWorld
    private lateinit var link: FakeMcLink
    private lateinit var sync: McSyncService
    private lateinit var mc: FakeMcComponent
    private lateinit var payments: PaymentService
    private lateinit var review: OrderReviewService
    private lateinit var locks: Locks
    private val announceCalls = java.util.concurrent.atomic.AtomicInteger()
    private val announceFails = AtomicBoolean(false)
    private val vertx: Vertx = Vertx.vertx()
    private val version = "1.4.0"
    private val ready = AtomicBoolean(true)

    override val poolSize: Int = 24

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        ready.set(true)
        announceCalls.set(0)
        announceFails.set(false)
        w = TestWiring(pool, ids = SeqIds())
        h = CheckoutHarness(w, vertx)
        d = DeliveryWorld(w)
        locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
        w.configure {
            MarketConfig(
                currency = "EUR", vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", mcBroadcast = true,
                mcBroadcastTemplate = "&a{player} &7bought &f{product} x{quantity} &8@ {store}", storeName = "Shop"
            )
        }
        link = FakeMcLink().also { it.add(7) }
        d.roster.granted = listOf(7L)
        sync = McSyncService(
            w.db, d.locks, w.clock, { w.config }, w.deliveries, w.serverStates, w.orders, w.orderItems, d.service, link, { version }, { ready.get() },
            storeName = { w.config.storeName }
        )
        mc = FakeMcComponent(sync, 7, w.clock, version)
        rebuild()
        runBlocking { w.fixtures.paymentMethod("fake") }
    }

    /** The order service and the payment service as the composition root builds them, with the announcer of `buildPaymentService` over the real [McSyncService]. */
    private fun rebuild() {
        val db = com.panomc.plugins.market.db.tx.MarketDb({ w.pool }, w.clock)
        val redemptions = RedemptionService(w.clock, locks, w.redemptions)
        val orderService = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            reservations = ReservationService(w.clock, locks, redemptions, w.orders), settlement = LedgerSettlement(w), foreign = ForeignEffects { _, _, _ -> },
            webhooks = PaidWebhooks { _, _ -> },
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { "EUR" }
        )

        payments = PaymentService(
            db = db, locks = locks, clock = w.clock, ids = w.ids, config = { h.config.toConfig() }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates,
            lookup = StaticProviderLookup(listOf(h.fake, FreeProvider(), CreditsProvider(), BankTransferProvider())), cipher = SecretCipher(ByteArray(32) { (it + 9).toByte() }),
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orderService, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
            announcer = PurchaseAnnouncements(w.orders, w.orderItems) { order, items ->
                announceCalls.incrementAndGet()

                if (announceFails.get()) throw IllegalStateException("the announcement failed")

                sync.announce(order, items)
            }
        )
        review = OrderReviewService(db, locks, w.orders, w.payments, w.orderEvents, w.clock, orderService, { false }, { after -> payments.runAfterCommit(after, w.pool) })

        h.useStarter(payments)
    }

    private suspend fun buy(vararg extra: Pair<String, Any?>): MarketOrder {
        val product = w.fixtures.product("vip-${System.nanoTime()}", price = 1000)
        val result = h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake", *extra))

        return w.orders.getByPublicId(result.order.getString("publicId"), pool)!!
    }

    private suspend fun attempt(orderId: Long): MarketPayment = w.payments.getByOrderId(orderId, pool).last()

    private suspend fun pay(order: MarketOrder, amount: Long? = null) {
        val a = attempt(order.id)

        payments.applyEvent(order.id, a.id, PaymentAttemptEvent.Succeeded(amount ?: a.amount, a.currency))
    }

    private suspend fun order(id: Long): MarketOrder = w.orders.getById(id, pool)!!

    @Test
    fun `O2 of a storefront order announces the purchase to the ready server once the transaction committed, and only once`(): Unit = runBlocking {
        mc.sync()

        val order = buy()

        assertEquals(0, mc.sync().broadcasts.size(), "an unpaid order is announced to nobody")
        assertEquals(0, announceCalls.get())

        pay(order)

        assertEquals(OrderStatus.COMPLETED, order(order.id).status)
        assertEquals(1, announceCalls.get())

        val reply = mc.sync()

        assertEquals(1, reply.broadcasts.size())

        val text = reply.broadcasts.getJsonObject(0).getString("text")

        assertTrue(text.startsWith("§a${order.recipientUsername.ifBlank { order.playerUsername }} §7bought §fvip-"), text)
        assertTrue(text.endsWith(" x1 §8@ Shop"), text)
        assertEquals(0, mc.sync().broadcasts.size(), "an announcement leaves in exactly one response")
    }

    @Test
    fun `a buyer who ticked do not announce, a switched off broadcast and a server without a ready session are not announced`(): Unit = runBlocking {
        mc.sync()

        val hidden = buy("hideFromBroadcast" to true)

        pay(hidden)

        assertEquals(OrderStatus.COMPLETED, order(hidden.id).status)
        assertEquals(1, announceCalls.get(), "the hook runs, the filter of McSyncService.announce answers 0")
        assertEquals(0, mc.sync().broadcasts.size())

        // the per-server override beats the panel default
        w.serverStates.updateSettings(7, """{"mcBroadcast":false}""", w.clock.now(), pool)

        pay(buy())

        assertEquals(0, mc.sync().broadcasts.size())

        w.serverStates.updateSettings(7, """{"mcBroadcast":true}""", w.clock.now(), pool)

        // no component has synced since a stale session: nobody to tell
        w.clock.advance(McSyncService.SESSION_TTL_MS + 1)

        pay(buy())

        assertEquals(0, sync.broadcasts.size(7))

        mc.sync()
        pay(buy())

        assertEquals(1, mc.sync().broadcasts.size(), "a fresh session of a server whose broadcast is on is told")
    }

    @Test
    fun `orders that are no web purchase of the player are not announced here`(): Unit = runBlocking {
        mc.sync()

        for (source in listOf("INGAME", "RENEWAL", "PANEL", "GIFT_CODE", "EXTERNAL")) {
            val order = buy()

            sql("UPDATE `pano_market_order` SET `source` = ? WHERE `id` = ?", source, order.id)

            pay(order)

            assertEquals(OrderStatus.COMPLETED, order(order.id).status)
            assertEquals(0, mc.sync().broadcasts.size(), "source $source is not announced by the paid hook")
        }

        assertEquals(0, announceCalls.get(), "the filter sits before the announcement, not inside it")
    }

    @Test
    fun `a failing announcement never touches the paid order`(): Unit = runBlocking {
        mc.sync()
        announceFails.set(true)

        val order = buy()

        pay(order)

        assertEquals(1, announceCalls.get())
        assertEquals(OrderStatus.COMPLETED, order(order.id).status)
        assertEquals(0, mc.sync().broadcasts.size())

        // the next order is announced again: the failure did not poison the hook
        announceFails.set(false)

        pay(buy())

        assertEquals(1, mc.sync().broadcasts.size())
    }

    @Test
    fun `an accepted review is announced when the order is paid, the review opening is not`(): Unit = runBlocking {
        mc.sync()

        val order = buy()
        val a = attempt(order.id)

        // the gateway reports another amount than the order: O3, the order waits for a human and nothing is announced
        pay(order, amount = a.amount - 1)

        assertEquals(OrderStatus.REVIEW, order(order.id).status)
        assertEquals(0, announceCalls.get())
        assertEquals(0, mc.sync().broadcasts.size())

        review.review(order.id, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = 77L)

        assertEquals(OrderStatus.COMPLETED, order(order.id).status)
        assertEquals(1, announceCalls.get())
        assertEquals(1, mc.sync().broadcasts.size())
    }

    @Test
    fun `a rejected review and a cancelled order are never announced`(): Unit = runBlocking {
        mc.sync()

        val order = buy()
        val a = attempt(order.id)

        pay(order, amount = a.amount - 1)
        review.review(order.id, ReviewDecision.REJECT, refund = false, force = false, note = null, adminUserId = 77L)

        assertEquals(0, announceCalls.get())
        assertEquals(0, mc.sync().broadcasts.size())
    }

    @Test
    fun `the composition root hands the paid hook to the payment service and the mc sync service to the announcer`() {
        val root = Path.of("src/main/kotlin/com/panomc/plugins/market/routes/api/order/OrderRouteSupport.kt")
        val source = Files.readString(root)

        assertTrue(source.contains("announcer = PurchaseAnnouncements(orderDao"), "buildPaymentService must pass the announcer")
        assertTrue(source.contains("mcSyncService(plugin).announce(order, items)"), "the announcer calls the plugin's McSyncService")

        val service = Files.readString(Path.of("src/main/kotlin/com/panomc/plugins/market/service/PaymentService.kt"))

        assertTrue(service.contains("is AfterCommit.OrderPaid -> announcer.paid(item.orderId, sqlClient)"))
    }
}
