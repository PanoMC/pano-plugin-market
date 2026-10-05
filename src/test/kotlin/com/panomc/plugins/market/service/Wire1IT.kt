package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketShippingMethod
import com.panomc.plugins.market.db.model.MarketShippingRate
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ShippingRateBasis
import com.panomc.plugins.market.db.model.ShippingRateSource
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.CreditsProvider
import com.panomc.plugins.market.provider.FreeProvider
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.routes.api.payment.AttemptLocks
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.SeqIds
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * WIRE-1: the seams whose both sides had landed, driven through the real services on a real MariaDB (evidence/WIRE-1.md).
 *
 * - `StartShipping` of O2 reaches [ShippingService.startShipping] through [ShippingEffects] (MK-076 seam 2 / MK-133), every other foreign effect still goes on
 *   to the next router;
 * - the status query, the reconcile query and `continue` of [PaymentService] run under the attempt lock of the inbound pipeline and judge an event like the
 *   pipeline does: another attempt's event and another environment's event are skipped, a `Succeeded` of another environment is a review (MK-077 seam 4).
 */
class Wire1IT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: CheckoutHarness
    private lateinit var webhooks: WebhookHarness
    private lateinit var shipping: ShippingService
    private lateinit var effects: RecordingEffects
    private lateinit var locks: Locks
    private lateinit var payments: PaymentService
    private lateinit var attemptLocks: AttemptLocks
    private lateinit var continuable: ContinuableFake
    private lateinit var labels: Path
    private val vertx: Vertx = Vertx.vertx()

    override val poolSize: Int = 24

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool, ids = SeqIds())
        h = CheckoutHarness(w, vertx)
        webhooks = WebhookHarness(w, vertx)
        labels = Files.createTempDirectory("market-wire1-labels")
        effects = RecordingEffects()
        attemptLocks = AttemptLocks()
        locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
        continuable = ContinuableFake(h.fake)
        shipping = ShippingService(
            clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers,
            currencyRates = w.currencyRates, addresses = w.addresses, lookup = StaticProviderLookup(shipping = listOf(ManualShippingProvider())),
            cipher = SecretCipher(ByteArray(32) { (it + 5).toByte() }),
            contexts = ShippingContexts { provider, settings, testMode -> TestContexts.shipping(provider.id, settings, vertx, testMode) },
            fulfilment = FulfilmentDeps(
                db = w.db, locks = locks, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
                shipments = w.shipments, shipmentItems = w.shipmentItems, shipmentEvents = w.shipmentEvents,
                mail = MailOutboxService({ w.config }, w.clock, w.mailOutbox, w.orderEvents), webhooks = webhooks.service, ids = w.ids, config = { w.config },
                labelsDir = labels, siteUrl = { "https://shop.example" }
            )
        )
        h.shipper = shipping
        rebuild()
        runBlocking { w.fixtures.paymentMethod("fake") }
    }

    /** The order service and the payment service as the composition root builds them: `StartShipping` to [ShippingEffects], the rest to [effects]. */
    private fun rebuild() {
        val db = MarketDb({ w.pool }, w.clock)
        val redemptions = RedemptionService(w.clock, locks, w.redemptions)
        val orderService = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            reservations = ReservationService(w.clock, locks, redemptions, w.orders), settlement = LedgerSettlement(w),
            foreign = ShippingEffects({ shipping }, effects),
            webhooks = PaidWebhooks { conn, orderId -> webhooks.service.emitOrderPaid(conn, orderId) },
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { "EUR" }
        )

        payments = PaymentService(
            db = db, locks = locks, clock = w.clock, ids = w.ids, config = { h.config.toConfig() }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates,
            lookup = StaticProviderLookup(listOf(continuable, FreeProvider(), CreditsProvider(), BankTransferProvider())), cipher = SecretCipher(ByteArray(32) { (it + 9).toByte() }),
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orderService, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
            attemptLocks = attemptLocks
        )

        h.useStarter(payments)
    }

    private val fx get() = w.fixtures
    private val fake get() = h.fake

    // ------------------------------------------------------------------------------------------------ fixtures

    private val de = mapOf("firstName" to "Hans", "lastName" to "Meier", "phone" to "+4915112345678", "country" to "DE", "city" to "Berlin", "line1" to "Strasse 1", "postalCode" to "10115")

    private suspend fun shirt(price: Long = 2000): MarketProduct =
        fx.product("shirt-${System.nanoTime()}", price = price, stock = 20, columns = mapOf("physical" to true, "weightGrams" to 250))

    private suspend fun shippingMethodId(): Long {
        val zone = fx.shippingZone("Everywhere", "[\"*\"]", 0)
        val now = w.clock.now()
        val id = w.shippingMethods.add(
            MarketShippingMethod(name = "Standard", providerId = "manual", rateSource = ShippingRateSource.RULES, handlingFee = 0, position = 0, createdAt = now, updatedAt = now),
            pool
        )

        w.shippingRates.add(
            MarketShippingRate(methodId = id, zoneId = zone.id, basis = ShippingRateBasis.FLAT, rangeFrom = 0, rangeTo = null, price = 500, perUnitPrice = 0, position = 0, createdAt = now, updatedAt = now),
            pool
        )

        return id
    }

    private suspend fun buyShirt(): MarketOrder {
        val shirt = shirt()
        val method = shippingMethodId()
        val result = h.checkout(h.body("items" to listOf(h.line(shirt)), "paymentMethodId" to "fake", "shippingAddress" to de, "shippingMethodId" to method))

        return w.orders.getByPublicId(result.order.getString("publicId"), pool)!!
    }

    private suspend fun buyDigital(): MarketOrder {
        val result = h.checkout(h.body("items" to listOf(h.line(fx.product(price = 1000))), "paymentMethodId" to "fake"))

        return w.orders.getByPublicId(result.order.getString("publicId"), pool)!!
    }

    private suspend fun order(id: Long): MarketOrder = w.orders.getById(id, pool)!!

    private suspend fun attempt(orderId: Long): MarketPayment = w.payments.getByOrderId(orderId, pool).last()

    private fun queryable() {
        fake.caps = PaymentCapabilities().also { it.statusQuery = true }
    }

    private fun succeeded(attempt: MarketPayment, testMode: Boolean? = null, target: Long = attempt.id) =
        PaymentEvent.Succeeded(PaymentTarget.Attempt(target), Money(attempt.amount, attempt.currency)).also { it.testMode = testMode }

    // ================================================================================================ StartShipping

    @Test
    fun `O2 of a paid physical order derives its shippingStatus through the shipping service, the other effects go on to the next router`(): Unit = runBlocking {
        val order = buyShirt()

        assertTrue(order.requiresShipping)

        // a manual order carries no PENDING from checkout: only the StartShipping effect can set it
        w.orders.let { sql("UPDATE `pano_market_order` SET `shippingStatus` = 'NOT_REQUIRED' WHERE `id` = ?", order.id) }

        assertEquals(ShippingStatus.NOT_REQUIRED, order(order.id).shippingStatus)

        val applied = payments.applyEvent(order.id, attempt(order.id).id, PaymentAttemptEvent.Succeeded(attempt(order.id).amount, attempt(order.id).currency))

        assertEquals(OrderStatus.COMPLETED, applied.orderStatus)
        assertEquals(ShippingStatus.PENDING, order(order.id).shippingStatus)

        val ran = effects.of(order.id)

        assertFalse("StartShipping" in ran, "StartShipping was consumed by the shipping router, ran=$ran")
        assertTrue("IssueInvoice" in ran, "the other foreign effects reach the next router, ran=$ran")
        assertTrue("GrantEntitlements" in ran && "QueueGrantDeliveries" in ran, "ran=$ran")
    }

    @Test
    fun `a digital order keeps NOT_REQUIRED and StartShipping on a second run changes nothing`(): Unit = runBlocking {
        val digital = buyDigital()

        payments.applyEvent(digital.id, attempt(digital.id).id, PaymentAttemptEvent.Succeeded(attempt(digital.id).amount, attempt(digital.id).currency))

        assertEquals(OrderStatus.COMPLETED, order(digital.id).status)
        assertEquals(ShippingStatus.NOT_REQUIRED, order(digital.id).shippingStatus)

        val physical = buyShirt()

        payments.applyEvent(physical.id, attempt(physical.id).id, PaymentAttemptEvent.Succeeded(attempt(physical.id).amount, attempt(physical.id).currency))

        assertEquals(ShippingStatus.PENDING, order(physical.id).shippingStatus)

        // the effect is a derivation: asking again is a no-op (null = unchanged) and writes nothing
        val before = order(physical.id).updatedAt
        val again = w.db.tx { conn -> shipping.startShipping(conn, physical.id) }

        assertEquals(null, again)
        assertEquals(before, order(physical.id).updatedAt)
    }

    @Test
    fun `an order that is not paid yet is not started, its shippingStatus is never PENDING by this effect`(): Unit = runBlocking {
        val order = buyShirt()

        sql("UPDATE `pano_market_order` SET `shippingStatus` = 'NOT_REQUIRED' WHERE `id` = ?", order.id)

        val changed = w.db.tx { conn -> shipping.startShipping(conn, order.id) }

        assertEquals(null, changed, "an unpaid order derives NOT_REQUIRED, the same as it has")
        assertEquals(ShippingStatus.NOT_REQUIRED, order(order.id).shippingStatus)
    }

    // ================================================================================================ query paths

    @Test
    fun `the status query waits for the attempt lock of the inbound pipeline and runs afterwards`(): Unit = runBlocking {
        queryable()

        val order = buyDigital()
        val attempt = attempt(order.id)

        fake.onQuery = { PaymentQueryResult.of(succeeded(attempt)) }

        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { attemptLocks.with(attempt.id) { held.complete(Unit); release.await() } }

        held.await()

        val status = async { payments.status(order, owner = true, pool) }

        delay(400)

        assertTrue(fake.calls(FakePaymentProvider.Op.QUERY).isEmpty(), "the provider was not asked while the inbound pipeline holds the attempt")
        assertFalse(status.isCompleted)
        assertEquals(OrderStatus.PENDING, order(order.id).status)

        release.complete(Unit)
        holder.await()

        val body = status.await()

        assertEquals("COMPLETED", body.getString("status"))
        assertEquals(1, fake.calls(FakePaymentProvider.Op.QUERY).size)
        assertEquals(0, attemptLocks.inUse())
    }

    @Test
    fun `the reconcile query takes the same lock`(): Unit = runBlocking {
        queryable()

        val order = buyDigital()
        val attempt = attempt(order.id)

        fake.onQuery = { PaymentQueryResult.of(succeeded(attempt)) }

        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { attemptLocks.with(attempt.id) { held.complete(Unit); release.await() } }

        held.await()

        val reconcile = async { payments.reconcileQuery(order(order.id), attempt, pool) }

        delay(400)

        assertTrue(fake.calls(FakePaymentProvider.Op.QUERY).isEmpty())
        assertFalse(reconcile.isCompleted)

        release.complete(Unit)
        holder.await()

        val result = reconcile.await()

        assertTrue(result is PaymentService.ReconcileQuery.Applied, "result=$result")
        assertEquals(OrderStatus.COMPLETED, order(order.id).status)
        assertEquals(0, attemptLocks.inUse())
    }

    @Test
    fun `continue takes the attempt lock too, and a continue against a newer attempt is refused`(): Unit = runBlocking {
        fake.onStart = { StartPaymentResult.Embedded(JsonObject().put("step", 1)) }

        val order = buyDigital()
        val attempt = attempt(order.id)

        assertEquals("EMBEDDED", attempt.startKind)

        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { attemptLocks.with(attempt.id) { held.complete(Unit); release.await() } }

        held.await()

        val next = async { payments.continuePayment(order, JsonObject().put("phone", "5551234567"), PayCaller(), pool) }

        delay(400)

        assertTrue(continuable.continued.isEmpty(), "the provider's continue did not run while the attempt is held")
        assertFalse(next.isCompleted)

        release.complete(Unit)
        holder.await()

        assertEquals("REDIRECT", next.await()!!.getString("kind"))
        assertEquals(1, continuable.continued.size)
        assertEquals(0, attemptLocks.inUse())
        assertEquals(0, payments.attemptLocksInUse())
    }

    @Test
    fun `a status query is judged like an inbound event - another attempt's event and another environment's event are skipped`(): Unit = runBlocking {
        queryable()

        val order = buyDigital()
        val attempt = attempt(order.id)

        // a Succeeded that names another attempt of the provider never completes this one
        fake.onQuery = { PaymentQueryResult.of(succeeded(attempt, target = attempt.id + 9_999)) }
        payments.status(order, owner = true, pool)

        assertEquals(OrderStatus.PENDING, order(order.id).status)
        assertEquals(PaymentStatus.PENDING, attempt(order.id).status)

        // a failure the gateway states for the other environment is not about this attempt's money
        w.clock.advance(11_000L)
        fake.onQuery = { PaymentQueryResult.of(PaymentEvent.Failed(PaymentTarget.Attempt(attempt.id), "DECLINED", null).also { it.final = true; it.testMode = !attempt.testMode }) }
        payments.status(order(order.id), owner = true, pool)

        assertEquals(PaymentStatus.PENDING, attempt(order.id).status)
        assertEquals(OrderStatus.PENDING, order(order.id).status)
        assertEquals(2, fake.calls(FakePaymentProvider.Op.QUERY).size)

        // the same failure in the attempt's own environment does apply
        w.clock.advance(11_000L)
        fake.onQuery = { PaymentQueryResult.of(PaymentEvent.Failed(PaymentTarget.Attempt(attempt.id), "DECLINED", null).also { it.testMode = attempt.testMode }) }
        payments.status(order(order.id), owner = true, pool)

        assertEquals(PaymentStatus.FAILED, attempt(order.id).status)
    }

    @Test
    fun `a Succeeded of another environment found by a query is a review with the money recorded, by the status query and by the reconcile query`(): Unit = runBlocking {
        queryable()

        val first = buyDigital()
        val firstAttempt = attempt(first.id)

        fake.onQuery = { PaymentQueryResult.of(succeeded(firstAttempt, testMode = !firstAttempt.testMode)) }
        payments.status(first, owner = true, pool)

        assertEquals(OrderStatus.REVIEW, order(first.id).status)
        assertEquals("OTHER", order(first.id).reviewReason)
        assertTrue(effects.of(first.id).isEmpty(), "nothing is delivered")
        assertEquals(PaymentStatus.REVIEW, attempt(first.id).status)
        assertEquals(firstAttempt.amount, attempt(first.id).paidAmount, "the money that arrived is recorded")
        assertTrue(attempt(first.id).adminMessage.orEmpty().contains("environment mismatch"), "adminMessage=${attempt(first.id).adminMessage}")

        val second = buyDigital()
        val secondAttempt = attempt(second.id)

        fake.onQuery = { PaymentQueryResult.of(succeeded(secondAttempt, testMode = !secondAttempt.testMode)) }

        val result = payments.reconcileQuery(order(second.id), secondAttempt, pool)

        assertTrue(result is PaymentService.ReconcileQuery.Applied, "result=$result")
        assertEquals(OrderStatus.REVIEW, order(second.id).status)
        assertEquals("OTHER", order(second.id).reviewReason)
        assertTrue(effects.of(second.id).isEmpty())
    }

    @Test
    fun `the composition root passes the shared registries, the source says so`() {
        val root = java.io.File("src/main/kotlin/com/panomc/plugins/market")
        val orderWiring = root.resolve("routes/api/order/OrderRouteSupport.kt").readText()
        val plugin = root.resolve("MarketPlugin.kt").readText()

        assertTrue("ShippingEffects({ shippingService(plugin) }" in orderWiring, "OrderRouteSupport routes StartShipping to the shipping service")
        assertTrue("attemptLocks = attemptLocks(plugin)" in orderWiring, "PaymentService gets the registry of the inbound pipeline")
        assertTrue("shippingAdminService(this).seed()" in plugin, "the start sequence seeds the shipping settings")
        assertNotNull(plugin.indexOf("seedShipping()").takeIf { it >= 0 })
    }
}
