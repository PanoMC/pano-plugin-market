package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.BillingInfoMode
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.order.RequestFingerprint
import com.panomc.plugins.market.core.pricing.ShippingCharge
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketCreditEntry
import com.panomc.plugins.market.db.model.MarketCreditTx
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.CreditsDisabled
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.error.PaymentMethodUnavailable
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.routes.api.checkout.parseCheckoutRequest
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.ServerDirectory
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.SeqIds
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.CurrencyType
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * `CheckoutService.checkout` on a real MariaDB (MK-075; 06 sections 4 to 8 and 14.2, 04 section 3, 11 section 4.1; the
 * tests 1 to 25 and 56 to 67 of 06 section 16 that do not need the payment, review or refund slices): the order
 * transaction (locks, price again under them, reservation, inserts), idempotency, snapshots, guest and gift, legal and
 * billing capture, and one case for every error code of `POST /api/market/checkout`. The global invariants (I1 to I22) are
 * checked after every test by the base class.
 *
 * The payment start (phase C), the credit hold and the pending subscription are seams that later slices fill: the harness
 * plugs scripted stand-ins into them ([ScriptedStarter], [LedgerHolds]) so the hooks are proven here.
 */
class CheckoutServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: CheckoutHarness
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        h = CheckoutHarness(w, vertx)
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private val fx get() = w.fixtures

    private fun json(vararg pairs: Pair<String, Any?>): JsonObject = h.body(*pairs)

    private fun line(product: MarketProduct, quantity: Int = 1, variant: Long = 0, values: Map<String, Any> = emptyMap(), server: Long? = null) =
        h.line(product, quantity, variant, values, server)

    private fun key(product: MarketProduct, quantity: Int = 1, variant: Long = 0) = CartLine(product.id, variant, quantity, emptyMap(), null).lineKey

    /** The error a checkout throws; the test fails when it does not throw one. */
    private suspend fun fails(block: suspend () -> Any?): Error {
        try {
            block()
        } catch (e: Error) {
            return e
        }

        error("expected an error, the checkout succeeded")
    }

    private fun extras(e: Error): JsonObject = JsonObject(e.encode())

    private suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject {
        val e = fails(block)

        assertEquals(code, e.getErrorCode(), "error code, body ${e.encode()}")
        assertEquals(status, e.getStatusCode(), "status of $code")

        return extras(e)
    }

    private suspend fun user(name: String = "Alex", email: String? = "$name@example.com", credit: Long = 0): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        if (email != null) h.emails[u.id] = email
        if (credit > 0) fx.credit(u, credit)

        return u to QuoteCaller(u.id)
    }

    private suspend fun order(publicId: String): MarketOrder = w.orders.getByPublicId(publicId, pool)!!

    private suspend fun orders(): List<MarketOrder> = sql("SELECT `id` FROM `pano_market_order` ORDER BY `id`").map { w.orders.getById(it.getLong("id"), pool)!! }

    private suspend fun stockOf(p: MarketProduct): Int? = sql("SELECT `stock` FROM `pano_market_product` WHERE `id` = ?", p.id).single().getInteger("stock")

    private suspend fun rows(table: String) = count(table)

    private suspend fun nothingWritten() {
        assertEquals(0, rows("market_order"), "orders")
        assertEquals(0, rows("market_order_item"), "items")
        assertEquals(0, rows("market_payment"), "attempts")
        assertEquals(0, rows("market_redemption"), "redemptions")
        assertEquals(0, rows("market_order_event"), "events")
    }

    // ============================================================================================ happy path (test 20)

    @Test
    fun `a checkout writes the order, its item, the redemption, both events and the first attempt, and starts the payment after the commit`(): Unit = runBlocking {
        val vip = fx.product("vip", price = 1000, stock = 5, actions = "[{\"id\":\"a1\",\"type\":\"CREDIT\",\"phase\":\"GRANT\",\"value\":1}]")
        val coupon = fx.coupon("TEN", DiscountUnit.PERCENT, 1000)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice")
        val result = h.checkout(
            json("items" to listOf(line(vip, 2)), "paymentMethodId" to "fake", "couponCode" to "TEN", "expectedTotal" to 18.0, "locale" to "tr"),
            caller = caller.copy(ip = "203.0.113.9", agent = "JUnit/1")
        )

        val order = order(result.order.getString("publicId"))

        assertEquals(Ids.PUBLIC_ID_REGEX.matches(order.publicId!!), true, "publicId of 20 Crockford characters")
        assertTrue(Regex("^[0-9a-f]{40}$").matches(order.accessToken!!), "40 hex characters")
        assertEquals(order.accessToken, result.orderToken, "the token is returned once, here")
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals("STOREFRONT", order.source.name)
        assertEquals(alice.id, order.userId)
        assertEquals("Alice", order.playerUsername)
        assertEquals("u:${alice.id}", order.buyerKey)
        assertEquals("u:${alice.id}", order.recipientKey)
        assertEquals(alice.id, order.recipientUserId)
        assertEquals("Alice", order.recipientUsername)
        assertFalse(order.isGift)
        assertEquals("Alice@example.com".lowercase(), order.email)
        assertEquals("tr", order.locale)
        assertEquals("203.0.113.9", order.clientIp)
        assertEquals("JUnit/1", order.userAgent)
        assertNotNull(order.idempotencyKey)
        assertEquals(64, order.idempotencyHash!!.length)
        assertEquals("EUR", order.currency)
        assertEquals("EUR", order.baseCurrency)
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(order.fxRate))
        assertEquals("MARKET", order.pricingMode.name)
        assertTrue(order.pricesIncludeVat)
        assertEquals(2000, order.subtotal)
        assertEquals(0, order.discountTotal)
        assertEquals(200, order.couponDiscount)
        assertEquals(1800, order.totalPrice)
        assertEquals(1800, order.gatewayAmount)
        assertEquals(0, order.creditAmount)
        assertEquals(0, order.creditValue)
        assertEquals(0, order.paymentFee)
        assertEquals(300, order.vatTotal, "1800 with 20 % inside: 300")
        assertEquals("fake", order.paymentMethodId)
        assertEquals("Fake payment", order.paymentLabel)
        assertEquals(w.clock.now() + 60 * 60_000L, order.expiresAt, "orderExpiryMinutes of the store (the fake provider has no window of its own)")
        assertEquals(coupon.id, order.couponId)
        assertEquals("TEN", order.couponCode)
        assertNull(order.creatorCodeId)
        assertFalse(order.testMode)
        assertFalse(order.requiresShipping)
        assertNull(order.legalTextId)
        assertNull(order.billingInfo)
        assertEquals(0, order.paidAmount)
        assertNull(order.paidAt)
        assertNull(order.paymentId)
        assertEquals(w.clock.now(), order.createdAt)

        // the invariant of 01 section 5.1
        assertEquals(order.totalPrice, order.gatewayAmount + order.creditValue)

        val items = w.orderItems.getByOrderIds(listOf(order.id), pool)

        assertEquals(1, items.size)

        val item = items.single()

        assertEquals(OrderItemKind.PRODUCT, item.kind)
        assertEquals(vip.id, item.productId)
        assertEquals("vip", item.productName)
        assertEquals(2, item.quantity)
        assertEquals(1000, item.listUnitPrice)
        assertEquals(1000, item.unitPrice)
        assertEquals(200, item.couponAmount)
        assertEquals(1800, item.lineTotal)
        assertEquals(300, item.vatAmount)
        assertEquals(2000, item.vatPercent)
        assertEquals(2, item.stockReserved)
        assertEquals(order.vatTotal, item.vatAmount)

        val snapshot = JsonObject(item.snapshot!!)

        assertEquals("vip", snapshot.getString("slug"))
        assertEquals("STANDARD", snapshot.getString("kind"))
        assertEquals("a1", snapshot.getJsonArray("actions").getJsonObject(0).getString("id"), "the product as sold, actions included")

        assertEquals(3, stockOf(vip))

        val redemptions = w.redemptions.getByOrderId(order.id, pool)

        assertEquals(1, redemptions.size)
        assertEquals(RedemptionKind.COUPON, redemptions.single().kind)
        assertEquals(RedemptionState.HELD, redemptions.single().state)
        assertEquals(200, redemptions.single().amount)
        assertEquals("u:${alice.id}", redemptions.single().buyerKey)
        assertEquals(1, w.coupons.getById(coupon.id, pool)!!.usedCount)

        assertEquals(listOf("CREATED", "PAYMENT_STARTED"), w.orderEvents.getByOrderId(order.id, pool).map { it.type.name })

        val attempt = w.payments.getByOrderId(order.id, pool).single()

        assertEquals(PaymentStatus.PENDING, attempt.status, "the scripted starter moved it to PENDING after the commit")
        assertEquals("fake", attempt.providerId)
        assertEquals(1800, attempt.amount)
        assertEquals("EUR", attempt.currency)
        assertEquals(0, attempt.feeAmount)
        assertEquals(0, attempt.creditAmount)
        assertEquals(1800, attempt.orderTotal)
        assertTrue(Ids.REFERENCE_REGEX.matches(attempt.reference))
        assertTrue(Regex("^[0-9a-f]{40}$").matches(attempt.token))
        assertEquals(w.clock.now() + 60 * 60_000L, attempt.expiresAt)

        // the answer
        assertEquals("REDIRECT", result.payment!!.getString("kind"))
        assertEquals(order.publicId, result.order.getString("publicId"))
        assertEquals(order.id, result.order.getLong("number"))
        assertEquals(18.0, result.order.getJsonObject("totals").getDouble("total"))
        assertEquals(2.0, result.order.getJsonObject("totals").getDouble("couponDiscount"))
        assertEquals("PENDING", result.order.getString("status"))
        assertEquals(1, result.order.getJsonArray("items").size())
        assertEquals(1, h.starter.started.size)
    }

    @Test
    fun `without a payment starter the order page takes over and payment is null`(): Unit = runBlocking {
        val p = fx.product(price = 500)

        fx.paymentMethod("fake")
        h.useStarter(PaymentStarter.NONE)

        val result = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"))

        assertNull(result.payment)
        assertEquals(PaymentStatus.CREATED, w.payments.getByOrderId(order(result.order.getString("publicId")).id, pool).single().status)
        assertNull(result.order.getJsonObject("payment").getValue("start"))
    }

    // ================================================================================== guest, gift (tests 8, 9, 10)

    @Test
    fun `a guest order has the buyer key g colon name and no user`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")

        val result = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "guest" to mapOf("username" to "Steve", "email" to " Steve@Example.COM ")))
        val order = order(result.order.getString("publicId"))

        assertNull(order.userId)
        assertEquals("g:steve", order.buyerKey)
        assertEquals("Steve", order.playerUsername)
        assertEquals("steve@example.com", order.email)
        assertEquals("g:steve", order.recipientKey)
        assertNull(order.recipientUserId)
        assertEquals("Steve", order.recipientUsername)
    }

    @Test
    fun `a guest who names a registered player lands the goods on that account but is not its buyer`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")

        val owner = fx.user("Notch")
        val result = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "guest" to mapOf("username" to "notch", "email" to "n@example.com")))
        val order = order(result.order.getString("publicId"))

        assertNull(order.userId, "never listed as bought by the account")
        assertEquals("g:notch", order.buyerKey)
        assertEquals("u:${owner.id}", order.recipientKey)
        assertEquals(owner.id, order.recipientUserId)
        assertEquals("Notch", order.recipientUsername, "the stored spelling")
        assertFalse(order.isGift)
    }

    @Test
    fun `a gift targets the recipient, the message is cleaned and stored`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice")
        val bob = fx.user("Bob")
        val result = h.checkout(
            json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "bob", "giftMessage" to "  hi\u0000 bob  "),
            caller = caller
        )
        val order = order(result.order.getString("publicId"))

        assertTrue(order.isGift)
        assertEquals(alice.id, order.userId)
        assertEquals("u:${alice.id}", order.buyerKey)
        assertEquals("u:${bob.id}", order.recipientKey)
        assertEquals(bob.id, order.recipientUserId)
        assertEquals("Bob", order.recipientUsername)
        assertEquals("hi bob", order.giftMessage)
        assertEquals(true, result.order.getBoolean("isGift"))
    }

    @Test
    fun `a gift message is dropped when the order is not a gift`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")
        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "ALICE", "giftMessage" to "x"), caller = caller).order.getString("publicId"))

        assertFalse(order.isGift)
        assertNull(order.giftMessage)
    }

    @Test
    fun `a gift to a player who never joined is a g key and not blocking`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")
        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "NewPlayer"), caller = caller).order.getString("publicId"))

        assertEquals("g:newplayer", order.recipientKey)
        assertNull(order.recipientUserId)
        assertTrue(order.isGift)
    }

    // ============================================================================ legal and billing capture (tests 17, 18)

    @Test
    fun `a required legal text must be accepted by id, a stale id is refused with the current one, the acceptance is stored`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")
        h.config = h.config.copy(legalTextRequired = true)

        val first = h.legal.publish("en-US", "Terms", "<p>one</p>", null)

        var extras = expect("LEGAL_ACCEPTANCE_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }

        assertEquals(first.id, extras.getLong("legalTextId"))

        extras = expect("LEGAL_ACCEPTANCE_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "acceptLegal" to true)) }
        assertEquals(first.id, extras.getLong("legalTextId"), "the id is part of the acceptance")

        val second = h.legal.publish("en-US", "Terms", "<p>two</p>", null)

        extras = expect("LEGAL_ACCEPTANCE_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "acceptLegal" to true, "legalTextId" to first.id)) }
        assertEquals(second.id, extras.getLong("legalTextId"), "a text published while the buyer was on the page: the current id comes back")

        extras = expect("LEGAL_ACCEPTANCE_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "acceptLegal" to false, "legalTextId" to second.id)) }
        nothingWritten()

        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "acceptLegal" to true, "legalTextId" to second.id)).order.getString("publicId"))

        assertEquals(second.id, order.legalTextId, "the version the buyer accepted")
        assertEquals(w.clock.now(), order.legalAcceptedAt)
    }

    @Test
    fun `a required text with no active text in any locale never stops a sale, and a text that is not required is not recorded`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")
        h.config = h.config.copy(legalTextRequired = true)

        val noText = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "acceptLegal" to true)).order.getString("publicId"))

        assertNull(noText.legalTextId)
        assertNull(noText.legalAcceptedAt)

        val text = h.legal.publish("en-US", "Terms", "<p>x</p>", null)

        h.config = h.config.copy(legalTextRequired = false)

        val optional = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "acceptLegal" to true, "legalTextId" to text.id)).order.getString("publicId"))

        assertNull(optional.legalTextId, "not required: both columns stay NULL even when the client sent acceptLegal")
        assertNull(optional.legalAcceptedAt)
    }

    @Test
    fun `the legal text is chosen by the order locale`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")
        h.config = h.config.copy(legalTextRequired = true)

        val en = h.legal.publish("en-US", "Terms", "<p>en</p>", null)
        val tr = h.legal.publish("tr", "Sartlar", "<p>tr</p>", null)

        val extras = expect("LEGAL_ACCEPTANCE_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "locale" to "tr")) }

        assertEquals(tr.id, extras.getLong("legalTextId"))

        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "locale" to "tr", "acceptLegal" to true, "legalTextId" to tr.id)).order.getString("publicId"))

        assertEquals(tr.id, order.legalTextId)
        assertNotNull(en.id)
    }

    @Test
    fun `billing info is validated, stored as a snapshot and only the validated keys are kept`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")
        h.config = h.config.copy(billingInfoMode = BillingInfoMode.REQUIRED)

        val missing = expect("BUYER_INFO_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }

        assertEquals(
            listOf("billingInfo.firstName", "billingInfo.lastName", "billingInfo.country", "billingInfo.city", "billingInfo.line1"),
            missing.getJsonArray("fields").map { it.toString() }
        )

        val bad = expect("BUYER_INFO_REQUIRED", 400) {
            h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "billingInfo" to mapOf("firstName" to "Ada", "lastName" to "L", "country" to "ZZ", "city" to "X", "line1" to "ab", "phone" to "123")))
        }

        assertEquals(setOf("billingInfo.country", "billingInfo.line1", "billingInfo.phone"), bad.getJsonArray("fields").map { it.toString() }.toSet())
        nothingWritten()

        val order = order(
            h.checkout(
                json(
                    "items" to listOf(line(p)), "paymentMethodId" to "fake",
                    "billingInfo" to mapOf("type" to "INDIVIDUAL", "firstName" to " Ada ", "lastName" to "Lovelace", "country" to "tr", "city" to "Ankara", "line1" to "Cankaya 1", "bogus" to "x", "identityNumber" to "10000000146")
                )
            ).order.getString("publicId")
        )
        val billing = JsonObject(order.billingInfo!!)

        assertEquals("Ada", billing.getString("firstName"))
        assertEquals("TR", billing.getString("country"))
        assertEquals("INDIVIDUAL", billing.getString("type"))
        assertEquals("10000000146", billing.getString("identityNumber"))
        assertFalse(billing.containsKey("bogus"))
    }

    @Test
    fun `mode OFF stores no billing info, a provider that requires a field makes it mandatory`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")
        h.config = h.config.copy(billingInfoMode = BillingInfoMode.OFF)

        val plain = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "billingInfo" to mapOf("firstName" to "Ada"))).order.getString("publicId"))

        assertNull(plain.billingInfo)

        h.fake.caps = PaymentCapabilities().apply { requiredBuyerFields = setOf(com.panomc.plugins.market.spi.payment.BuyerField.FIRST_NAME, com.panomc.plugins.market.spi.payment.BuyerField.PHONE) }

        val extras = expect("BUYER_INFO_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }

        assertEquals(listOf("billingInfo.firstName", "billingInfo.phone"), extras.getJsonArray("fields").map { it.toString() })

        val ok = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "billingInfo" to mapOf("firstName" to "Ada", "phone" to "+905551234567", "lastName" to "ignored"))).order.getString("publicId"))
        val billing = JsonObject(ok.billingInfo!!)

        assertEquals(setOf("firstName", "phone", "type"), billing.fieldNames(), "only the paths the provider requires are kept in mode OFF")
    }

    @Test
    fun `an account without an e-mail needs billingInfo email, which becomes the order e-mail`(): Unit = runBlocking {
        val p = fx.product(price = 700)

        fx.paymentMethod("fake")

        val (_, caller) = user("Mailless", email = null)
        val extras = expect("BUYER_INFO_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = caller) }

        assertEquals(listOf("email"), extras.getJsonArray("fields").map { it.toString() })

        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "billingInfo" to mapOf("email" to "Me@Example.com")), caller = caller).order.getString("publicId"))

        assertEquals("me@example.com", order.email)
    }

    // ========================================================================================== error codes (04 section 3)

    @Test
    fun `EMPTY_CART for no items and for an empty server cart`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        expect("EMPTY_CART", 400) { h.checkout(json("items" to emptyList<Any>(), "paymentMethodId" to "fake")) }

        val (_, caller) = user("Alice")

        expect("EMPTY_CART", 400) { h.checkout(json("paymentMethodId" to "fake"), caller = caller) }
        nothingWritten()
    }

    @Test
    fun `INVALID_CART carries the line codes, bad lines are never priced`(): Unit = runBlocking {
        val inactive = fx.product(slug = "off", price = 100, status = MarketStatus.INACTIVE)
        val needsNote = fx.product(slug = "needs", price = 100).also { fx.field(it, "note", ProductFieldType.TEXT, required = true) }
        val fine = fx.product(slug = "fine", price = 100, stock = 3)

        fx.paymentMethod("fake")

        val extras = expect("INVALID_CART", 400) { h.checkout(json("items" to listOf(line(inactive), line(needsNote), line(fine)), "paymentMethodId" to "fake")) }
        val lineErrors = extras.getJsonObject("lineErrors")

        assertEquals(listOf("PRODUCT_UNAVAILABLE"), lineErrors.getJsonArray(key(inactive)).map { it.toString() })
        assertEquals(listOf("FIELD_REQUIRED"), lineErrors.getJsonArray(key(needsNote)).map { it.toString() })
        assertFalse(lineErrors.containsKey(key(fine)))

        // MAX_QUANTITY is a line error of 400 class
        val capped = fx.product(slug = "capped", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("maxQuantityPerOrder" to 2)) }
        val over = expect("INVALID_CART", 400) { h.checkout(json("items" to listOf(line(capped, 3)), "paymentMethodId" to "fake")) }

        assertEquals(listOf("MAX_QUANTITY"), over.getJsonObject("lineErrors").getJsonArray(key(capped, 3)).map { it.toString() })
        assertEquals(3, stockOf(fine))
        nothingWritten()
    }

    @Test
    fun `INVALID_COUPON and INVALID_CREATOR_CODE with their reason`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")
        fx.coupon("USED", redeemLimit = 1)

        assertEquals("CODE_NOT_FOUND", expect("INVALID_COUPON", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "couponCode" to "NOPE")) }.getString("reason"))
        assertEquals("CODE_NOT_FOUND", expect("INVALID_CREATOR_CODE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "creatorCode" to "NOPE")) }.getString("reason"))

        // a coupon that someone else used up
        Fixtures.setColumns(pool, "market_coupon", w.coupons.getByCode("USED", pool)!!.id, mapOf("usedCount" to 1))
        assertEquals("CODE_LIMIT_REACHED", expect("INVALID_COUPON", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "couponCode" to "USED")) }.getString("reason"))

        // an expired coupon
        val expired = fx.coupon("OLD")

        Fixtures.setColumns(pool, "market_coupon", expired.id, mapOf("expiryDate" to w.clock.now() - 1))
        assertEquals("CODE_EXPIRED", expect("INVALID_COUPON", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "couponCode" to "OLD")) }.getString("reason"))
        Fixtures.setColumns(pool, "market_coupon", w.coupons.getByCode("USED", pool)!!.id, mapOf("usedCount" to 0))
        nothingWritten()
    }

    @Test
    fun `a creator code is recorded with its redemption and the creator may not use their own code`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val code = fx.creatorCode("STREAMER", "streamer", discount = 500, commissionPercent = 1000)
        val (_, alice) = user("Alice")
        val streamer = fx.user("Streamer").also { h.emails[it.id] = "streamer@example.com" }
        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "creatorCode" to "STREAMER"), caller = alice).order.getString("publicId"))

        assertEquals(code.id, order.creatorCodeId)
        assertEquals("STREAMER", order.creatorCode)
        assertEquals(50, order.creatorDiscount, "5 % of 10.00")
        assertEquals(950, order.totalPrice)

        val redemption = w.redemptions.getByOrderId(order.id, pool).single()

        assertEquals(RedemptionKind.CREATOR_CODE, redemption.kind)
        assertEquals(RedemptionState.HELD, redemption.state)
        assertEquals(50, redemption.amount)
        assertEquals(1, w.creatorCodes.getById(code.id, pool)!!.usedCount)

        assertEquals("CODE_NOT_FOUND", expect("INVALID_CREATOR_CODE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "creatorCode" to "STREAMER"), caller = QuoteCaller(streamer.id)) }.getString("reason"))
        assertEquals(1, rows("market_order"))
    }

    @Test
    fun `INVALID_RECIPIENT when gifts are off, for a bad name and for a credit pack to an unknown player`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val pack = fx.product(slug = "pack", price = 500, columns = mapOf("kind" to "CREDIT_PACK", "creditAmount" to 50000))

        fx.paymentMethod("fake")
        fx.user("Bob")

        val (_, caller) = user("Alice")

        h.config = h.config.copy(allowGiftPurchase = false)
        expect("INVALID_RECIPIENT", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "bob"), caller = caller) }

        h.config = h.config.copy(allowGiftPurchase = true)
        expect("INVALID_RECIPIENT", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "no way!"), caller = caller) }
        expect("INVALID_RECIPIENT", 400) { h.checkout(json("items" to listOf(line(pack)), "paymentMethodId" to "fake", "recipientUsername" to "Nobody"), caller = caller) }
        nothingWritten()
    }

    @Test
    fun `MINIMUM_ORDER_AMOUNT_NOT_REACHED with the minimum, and a free order is exempt`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val free = fx.product(slug = "free", price = 0)

        fx.paymentMethod("fake")
        h.config = h.config.copy(minimumOrderAmount = 50.0)

        assertEquals(50.0, expect("MINIMUM_ORDER_AMOUNT_NOT_REACHED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }.getDouble("minimum"))

        val order = order(h.checkout(json("items" to listOf(line(free)))).order.getString("publicId"))

        assertEquals(0, order.totalPrice)
    }

    @Test
    fun `BUYER_INFO_REQUIRED for a guest with a bad name or e-mail lists the offending paths`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")

        val both = expect("BUYER_INFO_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "guest" to mapOf("username" to "ab", "email" to "nope"))) }

        assertEquals(listOf("guest.username", "guest.email"), both.getJsonArray("fields").map { it.toString() })

        val none = expect("BUYER_INFO_REQUIRED", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "guest" to null)) }

        assertEquals(listOf("guest.username", "guest.email"), none.getJsonArray("fields").map { it.toString() })
        nothingWritten()
    }

    @Test
    fun `SHIPPING_ADDRESS_REQUIRED and SHIPPING_UNAVAILABLE for a physical cart, an accepted option is frozen on the order`(): Unit = runBlocking {
        val shirt = fx.product("shirt", price = 2000, stock = 10, columns = mapOf("physical" to true, "weightGrams" to 250))

        fx.paymentMethod("fake")

        // the seam says nothing: no address was sent
        val required = expect("SHIPPING_ADDRESS_REQUIRED", 400) { h.checkout(json("items" to listOf(line(shirt)), "paymentMethodId" to "fake")) }

        assertEquals(listOf("shippingAddress"), required.getJsonArray("fields").map { it.toString() })

        // an address but no option
        expect("SHIPPING_UNAVAILABLE", 400) {
            h.checkout(json("items" to listOf(line(shirt)), "paymentMethodId" to "fake", "shippingAddress" to mapOf("country" to "TR", "city" to "Ankara", "line1" to "Cankaya 1")))
        }.also { assertEquals("NO_METHOD", it.getString("reason")) }

        h.shippingResult = ShippingQuote(null, messages = listOf(QuoteMessage("SHIPPING_ADDRESS_REQUIRED", "error")), fields = listOf("line1", "postalCode"))
        assertEquals(
            listOf("line1", "postalCode"),
            expect("SHIPPING_ADDRESS_REQUIRED", 400) { h.checkout(json("items" to listOf(line(shirt)), "paymentMethodId" to "fake", "shippingAddress" to mapOf("country" to "TR"))) }.getJsonArray("fields").map { it.toString() }
        )

        h.shippingResult = ShippingQuote(null, messages = listOf(QuoteMessage("SHIPPING_UNAVAILABLE", "error")), reason = "NO_ZONE")
        assertEquals("NO_ZONE", expect("SHIPPING_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(shirt)), "paymentMethodId" to "fake", "shippingAddress" to mapOf("country" to "XX"))) }.getString("reason"))

        h.shippingResult = ShippingQuote(null, messages = listOf(QuoteMessage("SHIPPING_METHOD_REQUIRED", "error")))
        assertEquals("METHOD_REQUIRED", expect("SHIPPING_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(shirt)), "paymentMethodId" to "fake", "shippingAddress" to mapOf("country" to "TR"))) }.getString("reason"))
        nothingWritten()
        assertEquals(10, stockOf(shirt))

        val address = JsonObject().put("country", "TR").put("city", "Ankara").put("line1", "Cankaya 1")

        h.shippingResult = ShippingQuote(
            ShippingCharge(500, null), options = listOf(JsonObject().put("methodId", 7).put("name", "Standard")), methodId = 7, address = address, methodName = "Standard",
            snapshot = JsonObject().put("methodId", 7).put("minDays", 2).put("maxDays", 4), weightGrams = 250
        )

        val result = h.checkout(json("items" to listOf(line(shirt)), "paymentMethodId" to "fake", "shippingAddress" to mapOf("country" to "TR", "city" to "Ankara", "line1" to "Cankaya 1")))
        val order = order(result.order.getString("publicId"))

        assertTrue(order.requiresShipping)
        assertEquals(500, order.shippingTotal)
        assertEquals(2500, order.totalPrice)
        assertEquals("PENDING", order.shippingStatus.name)
        assertEquals(7L, order.shippingMethodId)
        assertEquals("Standard", order.shippingMethodName)
        assertEquals(250, order.shippingWeightGrams)
        assertEquals("Ankara", JsonObject(order.shippingAddress!!).getString("city"))
        assertEquals(4, JsonObject(order.shippingQuote!!).getInteger("maxDays"))
        assertTrue(w.orderItems.getByOrderIds(listOf(order.id), pool).single().physical)
        assertEquals("Standard", result.order.getJsonObject("shipping").getString("methodName"))
        assertEquals(4, result.order.getJsonObject("shipping").getInteger("maxDays"))
        assertEquals(9, stockOf(shirt))
    }

    @Test
    fun `PAYMENT_METHOD_UNAVAILABLE when none, an unknown one, a disabled one, a refused amount and a test-mode method are chosen`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")

        assertEquals("METHOD_REQUIRED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)))) }.getString("reason"))
        assertEquals("METHOD_NOT_OFFERED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "ghost")) }.getString("reason"))

        fx.paymentMethod("fake", enabled = false)
        assertEquals("METHOD_NOT_OFFERED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }.getString("reason"))

        fx.paymentMethod("fake", minAmount = 5000)
        assertEquals("AMOUNT_BELOW_MINIMUM", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }.getString("reason"))

        fx.paymentMethod("fake")
        h.config = h.config.copy(testMode = true)
        assertEquals("TEST_MODE", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }.getString("reason"))

        val (_, member) = user("Alice")

        assertEquals("TEST_MODE", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = member) }.getString("reason"))
        nothingWritten()

        // a holder of SET / PAY gets the order, flagged as a test order
        val admin = fx.user("Root").also { h.emails[it.id] = "root@example.com" }
        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = QuoteCaller(admin.id, canUseTestMode = true)).order.getString("publicId"))

        assertTrue(order.testMode)
        assertTrue(w.payments.getByOrderId(order.id, pool).single().testMode)
    }

    @Test
    fun `the method is not asked for when the gateway has nothing to collect, and the body method is ignored`(): Unit = runBlocking {
        val free = fx.product(slug = "free", price = 0)
        val p = fx.product(price = 1000)

        fx.coupon("FULL", DiscountUnit.PERCENT, 10000)
        fx.paymentMethod("fake", minAmount = 100000)

        val a = order(h.checkout(json("items" to listOf(line(free)), "paymentMethodId" to "ghost")).order.getString("publicId"))

        assertEquals("free", a.paymentMethodId)
        assertEquals(0, a.gatewayAmount)

        val b = order(h.checkout(json("items" to listOf(line(p)), "couponCode" to "FULL", "paymentMethodId" to "fake")).order.getString("publicId"))

        assertEquals("free", b.paymentMethodId, "a 100 % coupon makes a free order")
        assertEquals(0, b.totalPrice)
        assertEquals(1, w.payments.getByOrderId(b.id, pool).size)
        assertEquals("free", w.payments.getByOrderId(b.id, pool).single().providerId)
        assertEquals(0, w.payments.getByOrderId(b.id, pool).single().amount)
    }

    @Test
    fun `SUBSCRIPTION_MUST_BE_ALONE, a guest cannot buy one, and until the subscription service exists the sale is refused whole`(): Unit = runBlocking {
        val sub = fx.product("monthly", price = 600, columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))
        val other = fx.product("other", price = 100, stock = 4)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")

        expect("SUBSCRIPTION_MUST_BE_ALONE", 400) { h.checkout(json("items" to listOf(line(sub), line(other)), "paymentMethodId" to "fake"), caller = caller) }

        // a guest: 401
        val e = fails { h.checkout(json("items" to listOf(line(sub)), "paymentMethodId" to "fake")) }

        assertTrue(e is NotLoggedIn, e.encode())

        // alone and logged in: the sale needs the pending subscription row; the seam refuses it and everything rolls back
        h.subscriptionsAvailable = false
        assertEquals("RECURRING_NOT_SUPPORTED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(sub)), "paymentMethodId" to "fake"), caller = caller) }.getString("reason"))
        nothingWritten()
        assertEquals(4, stockOf(other))
    }

    @Test
    fun `a subscription line gets its pending subscription row through the seam, in the order transaction`(): Unit = runBlocking {
        val sub = fx.product("monthly", price = 600, columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice")
        val seen = CopyOnWriteArrayList<Long>()

        h.pendingSubscriptions = PendingSubscriptions { conn, order, item ->
            seen += item.productId!!

            w.subscriptions.add(
                com.panomc.plugins.market.db.model.MarketSubscription(
                    userId = alice.id, playerUsername = order.playerUsername, ownerKey = order.recipientKey, productId = item.productId!!, productName = item.productName,
                    initialOrderId = order.id, initialOrderItemId = item.id, providerId = "fake", mode = com.panomc.plugins.market.db.model.SubscriptionMode.MANUAL,
                    status = com.panomc.plugins.market.db.model.SubscriptionStatus.PENDING, price = order.totalPrice, currency = "EUR", createdAt = w.clock.now(), updatedAt = w.clock.now()
                ),
                conn
            )!!
        }
        h.subscriptionsAvailable = true

        val result = h.checkout(json("items" to listOf(line(sub)), "paymentMethodId" to "fake"), caller = caller)
        val order = order(result.order.getString("publicId"))

        assertEquals(listOf(sub.id), seen)
        assertNotNull(order.subscriptionId)
    }

    @Test
    fun `INSUFFICIENT_CREDITS for a full credit order and for a mixed one, with the balance and the most that could be applied`(): Unit = runBlocking {
        val p = fx.product(price = 1000, creditPrice = 10000, stock = 3)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice", credit = 5000)

        h.config = h.config.copy(allowMixedCreditPayment = true)

        val all = expect("INSUFFICIENT_CREDITS", 400) { h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true), caller = caller) }

        assertEquals(50.0, all.getDouble("balance"))
        assertFalse(all.containsKey("maxApplicable"))

        val mixed = expect("INSUFFICIENT_CREDITS", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "useCredits" to 80), caller = caller) }

        assertEquals(50.0, mixed.getDouble("balance"))
        assertEquals(9.99, mixed.getDouble("maxApplicable"), "the gateway keeps at least one cent")
        nothingWritten()
        assertEquals(3, stockOf(p))
        assertEquals(5000, fx.creditBalance(alice))
    }

    @Test
    fun `a full credit order holds the credits in the order transaction, a mixed one keeps a gateway remainder`(): Unit = runBlocking {
        val p = fx.product(price = 1000, creditPrice = 1000, stock = 3)

        fx.paymentMethod("fake")
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alice, caller) = user("Alice", credit = 5000)
        val full = order(h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true), caller = caller).order.getString("publicId"))

        assertEquals("credits", full.paymentMethodId)
        assertEquals(1000, full.creditAmount)
        assertEquals(1000, full.creditValue)
        assertEquals(0, full.gatewayAmount)
        assertEquals(1000, full.totalPrice)
        assertEquals(4000, fx.creditBalance(alice), "HOLD took it out of the balance")
        assertEquals(listOf(full.id to "order:${full.id}:hold"), h.holds.calls)
        assertEquals("credits", w.payments.getByOrderId(full.id, pool).single().providerId)
        assertEquals(1000, w.payments.getByOrderId(full.id, pool).single().creditAmount)
        assertEquals(0, w.payments.getByOrderId(full.id, pool).single().amount)

        val mixed = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "useCredits" to 4), caller = caller).order.getString("publicId"))

        assertEquals("fake", mixed.paymentMethodId)
        assertEquals(400, mixed.creditAmount)
        assertEquals(600, mixed.gatewayAmount)
        assertEquals(1000, mixed.totalPrice)
        assertEquals(3600, fx.creditBalance(alice))
        assertEquals(1, w.payments.getByOrderId(mixed.id, pool).single().creditAmount / 400)
        assertEquals(1, stockOf(p))
    }

    @Test
    fun `an order that spends credits is refused whole while there is no ledger, CREDITS_DISABLED`(): Unit = runBlocking {
        val p = fx.product(price = 1000, creditPrice = 1000, stock = 3)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice", credit = 5000)

        h.ledgerAvailable = false
        expect("CREDITS_DISABLED", 409) { h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true), caller = caller) }
        nothingWritten()
        assertEquals(3, stockOf(p), "the stock reserved before the hold is rolled back")
        assertEquals(5000, fx.creditBalance(alice))
    }

    @Test
    fun `a hold that fails rolls back the stock, the code counter and every row`(): Unit = runBlocking {
        val p = fx.product(price = 1000, creditPrice = 1000, stock = 3)
        val coupon = fx.coupon("TEN", DiscountUnit.PERCENT, 1000, redeemLimit = 5)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice", credit = 5000)

        // the balance changes between the quote and the hold: the ledger refuses
        h.holds.failWith = InsufficientCredits(1.0)

        expect("INSUFFICIENT_CREDITS", 400) { h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true, "couponCode" to "TEN"), caller = caller) }
        nothingWritten()
        assertEquals(3, stockOf(p))
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount)
        assertEquals(5000, fx.creditBalance(alice))
    }

    @Test
    fun `a deadlock inside the order transaction re-runs the whole block and leaves one order`(): Unit = runBlocking {
        val p = fx.product(price = 1000, creditPrice = 1000, stock = 3)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice", credit = 5000)

        h.holds.failOnceWith = MySQLException("Deadlock found when trying to get lock", 1213, "40001")

        val result = h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true), caller = caller)

        assertEquals(1, rows("market_order"))
        assertEquals(2, h.holds.attempts.get(), "the first run died, the second held")
        assertEquals(2, stockOf(p), "reserved once")
        assertEquals(4000, fx.creditBalance(alice))
        assertEquals(1, h.starter.started.size)
        assertNotNull(result.order.getString("publicId"))
    }

    @Test
    fun `INVALID_CREDIT_AMOUNT for a top-up below the minimum, above the maximum and when top-up is off`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")

        h.config = h.config.copy(creditTopUpEnabled = true, creditTopUpFreeAmount = true, creditTopUpMin = 5.0, creditTopUpMax = 100.0)

        val low = expect("INVALID_CREDIT_AMOUNT", 400) { h.checkout(json("creditTopUp" to 1.5, "paymentMethodId" to "fake"), caller = caller) }

        assertEquals("BELOW_MINIMUM", low.getString("reason"))
        assertEquals(5.0, low.getDouble("min"))
        assertEquals(100.0, low.getDouble("max"))
        assertEquals("ABOVE_MAXIMUM", expect("INVALID_CREDIT_AMOUNT", 400) { h.checkout(json("creditTopUp" to 500, "paymentMethodId" to "fake"), caller = caller) }.getString("reason"))

        h.config = h.config.copy(creditTopUpFreeAmount = false)
        assertEquals("TOPUP_DISABLED", expect("INVALID_CREDIT_AMOUNT", 400) { h.checkout(json("creditTopUp" to 10, "paymentMethodId" to "fake"), caller = caller) }.getString("reason"))
        nothingWritten()
    }

    @Test
    fun `a free-amount top-up is one CREDIT_TOPUP line, a guest is refused, the server cart is left alone, items are refused`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")

        h.config = h.config.copy(creditTopUpEnabled = true, creditTopUpFreeAmount = true, creditTopUpMin = 5.0, creditTopUpMax = 100.0, creditName = "Gems")

        // the server cart has a line: a top-up neither reads nor clears it
        h.cart.addItem(caller.userId!!, CartLine(p.id, 0, 1, emptyMap(), null))

        val result = h.checkout(json("creditTopUp" to 12.5, "paymentMethodId" to "fake"), caller = caller)
        val order = order(result.order.getString("publicId"))
        val items = w.orderItems.getByOrderIds(listOf(order.id), pool)

        assertEquals(1, items.size)
        assertEquals(OrderItemKind.CREDIT_TOPUP, items.single().kind)
        assertNull(items.single().productId)
        assertEquals("12.5 Gems", items.single().productName)
        assertEquals(1250, items.single().creditAmount)
        assertEquals(1, items.single().quantity)
        assertEquals(1250, order.totalPrice)
        assertEquals("CREDIT_TOPUP", JsonObject(items.single().snapshot!!).getString("kind"))
        assertEquals(1, w.cartItems.getByCartId(w.carts.getByUserId(caller.userId!!, pool)!!.id, pool).size, "the server cart is untouched")

        // a guest: 401; with items: BAD_REQUEST
        assertTrue(fails { h.checkout(json("creditTopUp" to 12.5, "paymentMethodId" to "fake")) } is NotLoggedIn)
        assertTrue(fails { h.checkout(json("creditTopUp" to 12.5, "items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = caller) } is BadRequest)
    }

    @Test
    fun `a gift top-up credits the recipient`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")
        val bob = fx.user("Bob")

        h.config = h.config.copy(creditTopUpEnabled = true, creditTopUpFreeAmount = true, creditTopUpMin = 5.0, creditTopUpMax = 100.0)

        val order = order(h.checkout(json("creditTopUp" to 20, "recipientUsername" to "bob", "paymentMethodId" to "fake"), caller = caller).order.getString("publicId"))

        assertEquals("u:${bob.id}", order.recipientKey)
        assertTrue(order.isGift)
    }

    @Test
    fun `NOT_LOGGED_IN when guest checkout is off and when a guest asks for credits`(): Unit = runBlocking {
        val p = fx.product(price = 1000, creditPrice = 1000)

        fx.paymentMethod("fake")

        h.config = h.config.copy(allowGuestCheckout = false)
        assertTrue(fails { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) } is NotLoggedIn)

        h.config = h.config.copy(allowGuestCheckout = true)
        assertTrue(fails { h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true)) } is NotLoggedIn)
        assertTrue(fails { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "credits")) } is NotLoggedIn)
        assertTrue(fails { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "useCredits" to 5)) } is NotLoggedIn)

        val pack = fx.product(slug = "pack", price = 500, columns = mapOf("kind" to "CREDIT_PACK", "creditAmount" to 50000))

        assertTrue(fails { h.checkout(json("items" to listOf(line(pack)), "paymentMethodId" to "fake")) } is NotLoggedIn)
        nothingWritten()
    }

    @Test
    fun `credit inputs that contradict each other are BAD_REQUEST, credits off is PAYMENT_METHOD_UNAVAILABLE`(): Unit = runBlocking {
        val p = fx.product(price = 1000, creditPrice = 1000)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice", credit = 5000)

        assertTrue(fails { h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true, "useCredits" to 5), caller = caller) } is BadRequest)
        assertTrue(fails { h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true, "paymentMethodId" to "fake"), caller = caller) } is BadRequest)

        h.config = h.config.copy(creditsEnabled = false)
        assertEquals("CREDITS_DISABLED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "payWithCredits" to true), caller = caller) }.getString("reason"))

        h.config = h.config.copy(creditsEnabled = true, allowMixedCreditPayment = false)
        assertEquals("MIXED_CREDIT_NOT_SUPPORTED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "useCredits" to 5), caller = caller) }.getString("reason"))

        h.config = h.config.copy(onlyAcceptCredits = true)
        assertEquals("CREDITS_REQUIRED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = caller) }.getString("reason"))
        nothingWritten()
    }

    @Test
    fun `BUYER_BLOCKED is 403 for the payer and again for the recipient, and a replay is not judged`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")
        fx.user("Bob")

        val (_, caller) = user("Alice")

        h.blocked = { payer, _, _, _, _ -> payer == "Steve" }
        expect("BUYER_BLOCKED", 403) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }.also { assertEquals(setOf("result", "error"), it.fieldNames(), "no detail about the rule") }

        h.blocked = { _, recipient, _, _, _ -> recipient == "Bob" }
        expect("BUYER_BLOCKED", 403) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "Bob"), caller = caller) }
        nothingWritten()

        h.blocked = { _, _, _, _, _ -> false }

        val first = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "fixed-key-0000001", caller = caller)

        h.blocked = { _, _, _, _, _ -> true }
        assertEquals(first.order.getString("publicId"), h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "fixed-key-0000001", caller = caller).order.getString("publicId"))
    }

    @Test
    fun `OUT_OF_STOCK names every failing line`(): Unit = runBlocking {
        val gone = fx.product(slug = "gone", price = 100, stock = 0)
        val last = fx.product(slug = "last", price = 100, stock = 1)
        val fine = fx.product(slug = "fine", price = 100, stock = 9)

        fx.paymentMethod("fake")

        val extras = expect("OUT_OF_STOCK", 409) { h.checkout(json("items" to listOf(line(gone), line(last, 1), line(fine, 2)), "paymentMethodId" to "fake")) }

        assertEquals(listOf(key(gone)), extras.getJsonArray("lines").map { it.toString() })

        // two lines of one product share its stock in cart order: the second finds it gone
        val shared = fx.product(slug = "shared", price = 100, stock = 1)
        val v1 = fx.variant(fx.product(slug = "vp", price = 100, stock = 0), "A", stock = 1)
        val two = expect("OUT_OF_STOCK", 409) { h.checkout(json("items" to listOf(line(shared), line(shared, 1, 0, mapOf("x" to "y"))), "paymentMethodId" to "fake")) }

        assertEquals(1, two.getJsonArray("lines").size())
        assertNotNull(v1)
        nothingWritten()
        assertEquals(1, stockOf(last))
        assertEquals(9, stockOf(fine))
    }

    @Test
    fun `PURCHASE_LIMIT_REACHED, COOLDOWN_ACTIVE and PRODUCT_REQUIREMENT_NOT_MET carry their extras`(): Unit = runBlocking {
        val limited = fx.product(slug = "limited", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("limitPerPlayer" to 1)) }
        val cool = fx.product(slug = "cool", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("cooldownSeconds" to 3600)) }
        val base = fx.product(slug = "base", price = 100)
        val dependent = fx.product(slug = "dependent", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("requiredProducts" to "[${base.id}]")) }

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")

        h.checkout(json("items" to listOf(line(limited)), "paymentMethodId" to "fake"), caller = caller)

        val limit = expect("PURCHASE_LIMIT_REACHED", 409) { h.checkout(json("items" to listOf(line(limited)), "paymentMethodId" to "fake"), caller = caller) }

        assertEquals(limited.id, limit.getLong("productId"))
        assertEquals(1, limit.getInteger("limit"))

        h.checkout(json("items" to listOf(line(cool)), "paymentMethodId" to "fake"), caller = caller)
        w.clock.advance(600_000)

        val cooldown = expect("COOLDOWN_ACTIVE", 409) { h.checkout(json("items" to listOf(line(cool)), "paymentMethodId" to "fake"), caller = caller) }

        assertEquals(cool.id, cooldown.getLong("productId"))
        assertEquals(3000, cooldown.getLong("retryAfter"), "3600 s minus the 600 s that passed")

        val requirement = expect("PRODUCT_REQUIREMENT_NOT_MET", 409) { h.checkout(json("items" to listOf(line(dependent)), "paymentMethodId" to "fake"), caller = caller) }

        assertEquals(dependent.id, requirement.getLong("productId"))

        // buying the prerequisite in the same cart satisfies it
        assertNotNull(h.checkout(json("items" to listOf(line(base), line(dependent)), "paymentMethodId" to "fake"), caller = caller).order.getString("publicId"))
    }

    // ==================================================================================== idempotency (tests 21 to 25)

    @Test
    fun `the same key and body replays the first response without a second order, another body is IDEMPOTENCY_CONFLICT, the key is free after a validation failure`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")
        val body = json("items" to listOf(line(p, 2)), "paymentMethodId" to "fake")
        val first = h.checkout(body, key = "replay-key-00000001", caller = caller)
        val again = h.checkout(body, key = "replay-key-00000001", caller = caller)

        assertEquals(first.order.getString("publicId"), again.order.getString("publicId"))
        assertEquals(first.orderToken, again.orderToken, "the caller proved possession of the key")
        assertEquals(first.payment, again.payment, "the stored start of the PENDING attempt is served again")
        assertEquals(1, rows("market_order"))
        assertEquals(3, stockOf(p), "one decrement")
        assertEquals(1, h.starter.started.size, "one attempt at the gateway")
        assertEquals(1, rows("market_payment"))

        // key order and whitespace of the body do not matter, one more number does
        val reordered = JsonObject(body.encode()).let { b -> JsonObject().put("paymentMethodId", "fake").put("items", b.getJsonArray("items")) }

        assertEquals(first.order.getString("publicId"), h.checkout(reordered, key = "replay-key-00000001", caller = caller).order.getString("publicId"))

        expect("IDEMPOTENCY_CONFLICT", 409) { h.checkout(json("items" to listOf(line(p, 3)), "paymentMethodId" to "fake"), key = "replay-key-00000001", caller = caller) }
        assertEquals(1, rows("market_order"))

        // the same key may be re-sent with a corrected body after a validation failure
        val other = fx.product(slug = "other", price = 100, stock = 0)

        expect("OUT_OF_STOCK", 409) { h.checkout(json("items" to listOf(line(other)), "paymentMethodId" to "fake"), key = "retry-key-000000001", caller = caller) }
        assertNotNull(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "retry-key-000000001", caller = caller).order.getString("publicId"))
        assertEquals(2, rows("market_order"))
    }

    @Test
    fun `the key is scoped to the buyer`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val (_, alice) = user("Alice")
        val (_, bob) = user("Bob")
        val body = json("items" to listOf(line(p)), "paymentMethodId" to "fake")
        val a = h.checkout(body, key = "shared-key-00000001", caller = alice)
        val b = h.checkout(body, key = "shared-key-00000001", caller = bob)

        assertTrue(a.order.getString("publicId") != b.order.getString("publicId"))
        assertEquals(2, rows("market_order"))
    }

    @Test
    fun `a replay while the first request is still starting the payment waits and serves the same start, then gives up with payment null`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 9)

        fx.paymentMethod("fake")
        h.replayWaitMs = 1500
        h.replayPollMs = 50
        h.rebuild()

        val (_, caller) = user("Alice")
        val body = json("items" to listOf(line(p)), "paymentMethodId" to "fake")

        // the first request holds the gateway for a while; the replay arrives meanwhile and waits for the start
        h.starter.delayMs = 400

        val first = async(kotlinx.coroutines.Dispatchers.IO) { h.checkout(body, key = "slow-key-000000001", caller = caller) }

        awaitRows("market_payment", 1)

        val replay = h.checkout(body, key = "slow-key-000000001", caller = caller)
        val firstResult = first.await()

        assertEquals(firstResult.payment, replay.payment, "the replay returned the same PaymentStart")
        assertEquals("REDIRECT", replay.payment!!.getString("kind"))
        assertEquals(1, rows("market_order"))
        assertEquals(1, h.starter.started.size)

        // a start that takes longer than the wait: the replay answers payment null and the order page takes over
        h.starter.delayMs = 2500

        val slow = async(kotlinx.coroutines.Dispatchers.IO) { h.checkout(body.copy().put("currency", "EUR"), key = "slower-key-00000001", caller = caller) }

        awaitRows("market_payment", 2)

        val gaveUp = h.checkout(body.copy().put("currency", "EUR"), key = "slower-key-00000001", caller = caller)

        assertNull(gaveUp.payment)
        assertEquals("slower-key-00000001", orders().last().idempotencyKey)
        assertNotNull(slow.await().payment)
    }

    @Test
    fun `a replay of an order whose start failed answers 502 with the order`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 9)

        fx.paymentMethod("fake")
        h.starter.failure = PaymentStartFailed("GATEWAY_REJECTED")

        val (_, caller) = user("Alice")
        val body = json("items" to listOf(line(p)), "paymentMethodId" to "fake")
        val first = expect("PAYMENT_PROVIDER_ERROR", 502) { h.checkout(body, key = "failed-key-00000001", caller = caller) }

        h.starter.failure = null

        val replay = expect("PAYMENT_PROVIDER_ERROR", 502) { h.checkout(body, key = "failed-key-00000001", caller = caller) }

        assertEquals("GATEWAY_REJECTED", replay.getString("code"))
        assertEquals(first.getJsonObject("order").getString("publicId"), replay.getJsonObject("order").getString("publicId"))
        assertEquals(first.getString("orderToken"), replay.getString("orderToken"))
        assertEquals(1, rows("market_order"))
        assertEquals(1, h.starter.started.size, "the gateway was not asked again")
    }

    @Test
    fun `PAYMENT_PROVIDER_ERROR carries the order and its token, the order stays PENDING and its reservation is kept`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 3)

        fx.paymentMethod("fake")
        h.starter.failure = PaymentStartFailed("GATEWAY_UNREACHABLE")

        val extras = expect("PAYMENT_PROVIDER_ERROR", 502) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }
        val order = orders().single()

        assertEquals("GATEWAY_UNREACHABLE", extras.getString("code"))
        assertEquals(order.publicId, extras.getJsonObject("order").getString("publicId"))
        assertEquals(order.accessToken, extras.getString("orderToken"))
        assertEquals("PENDING", extras.getJsonObject("order").getString("status"))
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals(2, stockOf(p))
        assertEquals(PaymentStatus.FAILED, w.payments.getByOrderId(order.id, pool).single().status)
        assertTrue(extras.getJsonObject("order").getJsonObject("payment").getString("status") == "FAILED")

        // any other throwable of the starter is the generic code, the order exists all the same
        h.starter.failure = null
        h.starter.crash = IllegalStateException("boom")

        assertEquals("INTERNAL", expect("PAYMENT_PROVIDER_ERROR", 502) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }.getString("code"))
        assertEquals(2, rows("market_order"))
    }

    @Test
    fun `a collision of the generated public id is regenerated, and so is the attempt reference`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 9)

        fx.paymentMethod("fake")

        val first = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"))
        val taken = first.order.getString("publicId")
        val takenReference = w.payments.getByOrderId(orders().single().id, pool).single().reference

        h.forcedIds.queuePublicIds(taken, taken)
        h.forcedIds.queueReferences(takenReference)

        val second = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"))

        assertTrue(second.order.getString("publicId") != taken)
        assertEquals(2, rows("market_order"))
        assertEquals(2, rows("market_payment"))
        assertTrue(w.payments.getByOrderId(orders().last().id, pool).single().reference != takenReference)
    }

    @Test
    fun `PRICE_CHANGED when the total the buyer confirmed is not the total now, with the fresh quote and nothing created`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val extras = expect("PRICE_CHANGED", 409) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "expectedTotal" to 9.99)) }

        assertEquals(10.0, extras.getJsonObject("quote").getDouble("total"))
        assertTrue(extras.getJsonObject("quote").getBoolean("canCheckout"))
        nothingWritten()
        assertEquals(5, stockOf(p))

        assertNotNull(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "expectedTotal" to 10.0)).order.getString("publicId"))
    }

    @Test
    fun `the price is computed again under the locks, a price that moved between phase A and B re-runs once and then asks the buyer`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        // without a stated total the order is simply priced at the new price (transparent re-run)
        h.afterPhaseA(once = true) { Fixtures.setColumns(pool, "market_product", p.id, mapOf("price" to 1500)) }

        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")).order.getString("publicId"))

        assertEquals(1500, order.totalPrice, "priced at the locked row, never at the phase A figure")
        assertEquals(1500, w.orderItems.getByOrderIds(listOf(order.id), pool).single().unitPrice)
        assertEquals(4, stockOf(p))

        // with the confirmed total the buyer is asked again: the quote of the new price comes back, nothing is created
        h.afterPhaseA(once = true) { Fixtures.setColumns(pool, "market_product", p.id, mapOf("price" to 2000)) }

        val extras = expect("PRICE_CHANGED", 409) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "expectedTotal" to 15.0)) }

        assertEquals(20.0, extras.getJsonObject("quote").getDouble("total"))
        assertEquals(1, rows("market_order"))
        assertEquals(4, stockOf(p))

        // a price that keeps moving: the second change in a row is PRICE_CHANGED too
        val moving = AtomicInteger(2000)

        h.afterPhaseA(once = false) { Fixtures.setColumns(pool, "market_product", p.id, mapOf("price" to moving.addAndGet(500))) }
        expect("PRICE_CHANGED", 409) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }
        assertEquals(1, rows("market_order"))
        h.afterPhaseA(once = true) { }
    }

    @Test
    fun `an automatic discount that expires between phase A and B is dropped, the order is priced without it`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)
        val discount = fx.discount(value = 5000, unit = DiscountUnit.PERCENT, usageLimit = 10)

        fx.paymentMethod("fake")

        assertEquals(5.0, h.quoteTotal(p), "half off to start with")

        h.afterPhaseA(once = true) { Fixtures.setColumns(pool, "market_discount", discount.id, mapOf("status" to "INACTIVE")) }

        val order = order(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")).order.getString("publicId"))

        assertEquals(1000, order.totalPrice)
        assertEquals(0, order.discountTotal)
        assertEquals(0, w.redemptions.getByOrderId(order.id, pool).size)
        assertEquals(0, w.discounts.getById(discount.id, pool)!!.usedCount)
    }

    @Test
    fun `an automatic discount is recorded as a redemption and its counter moves`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)
        val discount = fx.discount(value = 2000, unit = DiscountUnit.PERCENT, usageLimit = 10)

        fx.paymentMethod("fake")

        val order = order(h.checkout(json("items" to listOf(line(p, 2)), "paymentMethodId" to "fake")).order.getString("publicId"))

        assertEquals(2000, order.subtotal)
        assertEquals(400, order.discountTotal)
        assertEquals(1600, order.totalPrice)

        val redemption = w.redemptions.getByOrderId(order.id, pool).single()

        assertEquals(RedemptionKind.DISCOUNT, redemption.kind)
        assertEquals(discount.id, redemption.refId)
        assertEquals(400, redemption.amount)
        assertEquals(1, w.discounts.getById(discount.id, pool)!!.usedCount)
    }

    // ================================================================================= reservation and limits (26 to 30, 63, 64, 66)

    @Test
    fun `variant stock is used instead of the product stock, a bundle reserves its own and its children's stock multiplied, unlimited writes nothing`(): Unit = runBlocking {
        val crate = fx.product(slug = "crate", price = 400, stock = 0)
        val s = fx.variant(crate, "S", price = 400, stock = 4)
        val unlimited = fx.product(slug = "unlimited", price = 100)
        val a = fx.product(slug = "a", price = 100, stock = 10)
        val b = fx.product(slug = "b", price = 100, stock = 10)
        val bundle = fx.bundle(a to 2, b to 1, slug = "pack", price = 900).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("stock" to 7)) }

        fx.paymentMethod("fake")

        val order = order(h.checkout(json("items" to listOf(line(crate, 3, s.id), line(unlimited, 5), line(bundle, 2)), "paymentMethodId" to "fake")).order.getString("publicId"))
        val items = w.orderItems.getByOrderIds(listOf(order.id), pool)
        val byProduct = items.associateBy { it.productId to it.kind }

        assertEquals(1, sql("SELECT `stock` FROM `pano_market_product_variant` WHERE `id` = ?", s.id).single().getInteger("stock"), "variant stock 4 - 3")
        assertEquals(0, stockOf(crate), "the product stock of a product with variants is ignored")
        assertNull(stockOf(unlimited))
        assertEquals(5, stockOf(bundle), "the bundle's own stock 7 - 2")
        assertEquals(6, stockOf(a), "child a: 10 - 2 x 2")
        assertEquals(8, stockOf(b), "child b: 10 - 2 x 1")

        assertEquals(3, byProduct.getValue(crate.id to OrderItemKind.PRODUCT).stockReserved)
        assertEquals(0, byProduct.getValue(unlimited.id to OrderItemKind.PRODUCT).stockReserved)
        assertEquals(2, byProduct.getValue(bundle.id to OrderItemKind.BUNDLE).stockReserved)
        assertEquals(4, byProduct.getValue(a.id to OrderItemKind.BUNDLE_CHILD).stockReserved)
        assertEquals(2, byProduct.getValue(b.id to OrderItemKind.BUNDLE_CHILD).stockReserved)

        // the bundle line is first, children hang off it with the total child quantity (the limit and cooldown rule of MK-072 counts these)
        val bundleItem = byProduct.getValue(bundle.id to OrderItemKind.BUNDLE)

        assertEquals(bundleItem.id, byProduct.getValue(a.id to OrderItemKind.BUNDLE_CHILD).parentItemId)
        assertEquals(4, byProduct.getValue(a.id to OrderItemKind.BUNDLE_CHILD).quantity)
        assertEquals(0, byProduct.getValue(a.id to OrderItemKind.BUNDLE_CHILD).lineTotal)
        assertTrue(bundleItem.id < byProduct.getValue(a.id to OrderItemKind.BUNDLE_CHILD).id)
        assertEquals(1200 + 500 + 1800, order.totalPrice, "3 x 4.00 + 5 x 1.00 + 2 x 9.00")
    }

    @Test
    fun `a failed checkout leaves no row and no counter behind, whichever step fails`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 3)
        val q = fx.product(slug = "q", price = 100, stock = 1)
        val coupon = fx.coupon("TEN", DiscountUnit.PERCENT, 1000, redeemLimit = 5)

        fx.paymentMethod("fake")

        // the second line is out of stock after the first one's units were reserved in the same statement round
        expect("OUT_OF_STOCK", 409) { h.checkout(json("items" to listOf(line(p), line(q, 2)), "paymentMethodId" to "fake", "couponCode" to "TEN")) }
        nothingWritten()
        assertEquals(3, stockOf(p))
        assertEquals(1, stockOf(q))
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount)
    }

    @Test
    fun `a pending gift of a stranger neither uses the recipient's limit nor starts the cooldown, an own pending order does`(): Unit = runBlocking {
        val p = fx.product(slug = "once", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("limitPerPlayer" to 1, "cooldownSeconds" to 3600)) }

        fx.paymentMethod("fake")

        val (_, alice) = user("Alice")
        val (bob, _) = user("Bob")

        // Alice's gift to Bob is pending: it counts for nobody
        h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "Bob"), caller = alice)

        // Bob can still buy it himself; and now his own pending order uses the limit and starts the cooldown
        val own = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = QuoteCaller(bob.id))

        assertNotNull(own.order.getString("publicId"))
        expect("PURCHASE_LIMIT_REACHED", 409) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = QuoteCaller(bob.id)) }
        assertEquals(2, rows("market_order"))
    }

    @Test
    fun `the per-player limit is the recipient's, the coupon per-customer limit counts the payer and the recipient key`(): Unit = runBlocking {
        val p = fx.product(slug = "once", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("limitPerPlayer" to 1)) }
        val q = fx.product(slug = "plain", price = 100, stock = 20)

        fx.coupon("ONE", DiscountUnit.PERCENT, 1000, customerRedeemLimit = 1)
        fx.paymentMethod("fake")

        val (_, alice) = user("Alice")
        val bob = fx.user("Bob").also { h.emails[it.id] = "bob@example.com" }
        val (_, carol) = user("Carol")

        // Alice sends Bob one with the coupon: the redemption row names Alice as payer and Bob as recipient
        h.checkout(json("items" to listOf(line(q)), "paymentMethodId" to "fake", "recipientUsername" to "Bob", "couponCode" to "ONE"), caller = alice)

        // the limit counts the payer key and the recipient key (06 section 7.2): neither Alice nor Bob may use ONE again, an unrelated payer may
        assertEquals("CODE_LIMIT_REACHED", expect("INVALID_COUPON", 400) { h.checkout(json("items" to listOf(line(q)), "paymentMethodId" to "fake", "couponCode" to "ONE"), caller = alice) }.getString("reason"))
        assertEquals("CODE_LIMIT_REACHED", expect("INVALID_COUPON", 400) { h.checkout(json("items" to listOf(line(q)), "paymentMethodId" to "fake", "couponCode" to "ONE"), caller = QuoteCaller(bob.id)) }.getString("reason"))
        assertNotNull(h.checkout(json("items" to listOf(line(q)), "paymentMethodId" to "fake", "couponCode" to "ONE"), caller = carol).order.getString("publicId"))

        // the per-player limit of a product is the recipient's: Alice buys the limited product for Bob, then Bob (own order) hits Bob's limit
        h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "Bob"), caller = alice)
        assertNotNull(h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = QuoteCaller(bob.id)).order.getString("publicId"), "the pending gift of a stranger used none of Bob's allowance")
        expect("PURCHASE_LIMIT_REACHED", 409) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake", "recipientUsername" to "Bob"), caller = carol) }
    }

    @Test
    fun `a guest who changes the payer name but gifts to the same player gets a single-use coupon once`(): Unit = runBlocking {
        val q = fx.product(slug = "plain", price = 100, stock = 20)

        fx.coupon("ONE", DiscountUnit.PERCENT, 1000, customerRedeemLimit = 1)
        fx.paymentMethod("fake")

        val steve = fx.user("Steve")

        h.checkout(json("items" to listOf(line(q)), "paymentMethodId" to "fake", "couponCode" to "ONE", "recipientUsername" to "Steve", "guest" to mapOf("username" to "abc", "email" to "a@example.com")))

        assertEquals(
            "CODE_LIMIT_REACHED",
            expect("INVALID_COUPON", 400) {
                h.checkout(json("items" to listOf(line(q)), "paymentMethodId" to "fake", "couponCode" to "ONE", "recipientUsername" to "Steve", "guest" to mapOf("username" to "xyz", "email" to "x@example.com")))
            }.getString("reason")
        )
        assertEquals(1, rows("market_order"))
        assertNotNull(steve)
    }

    @Test
    fun `an owned TIMED product with limitPerPlayer 1 can be bought again`(): Unit = runBlocking {
        val timed = fx.product(
            slug = "rank", price = 800,
            columns = mapOf("billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 30, "limitPerPlayer" to 1)
        )

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice")
        val now = w.clock.now()

        Fixtures.insertRaw(
            pool, "market_entitlement",
            mapOf("ownerKey" to "u:${alice.id}", "userId" to alice.id, "productId" to timed.id, "status" to "ACTIVE", "startsAt" to now - 1000, "expiresAt" to now + 86_400_000)
        )

        assertNotNull(h.checkout(json("items" to listOf(line(timed)), "paymentMethodId" to "fake"), caller = caller).order.getString("publicId"))
    }

    @Test
    fun `a checkout from the server cart clears it, one with explicit items leaves it`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 9)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice")

        h.cart.addItem(alice.id, CartLine(p.id, 0, 2, emptyMap(), null))
        h.cart.replace(alice.id, CartService.Replacement(couponCode = CartService.Field("NOPE-IGNORED"), giftMessage = CartService.Field("hello")))

        // explicit items: the cart is not touched
        h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = caller)

        val cartId = w.carts.getByUserId(alice.id, pool)!!.id

        assertEquals(1, w.cartItems.getByCartId(cartId, pool).size)

        h.cart.replace(alice.id, CartService.Replacement(couponCode = CartService.Field<String?>(null), giftMessage = CartService.Field("hello")))

        // the server cart is the cart: items and cart-level fields go in the same transaction
        val result = h.checkout(json("paymentMethodId" to "fake"), caller = caller)

        assertEquals(0, w.cartItems.getByCartId(cartId, pool).size)
        assertNull(w.carts.getById(cartId, pool)!!.giftMessage)
        assertEquals(2, w.orderItems.getByOrderIds(listOf(order(result.order.getString("publicId")).id), pool).single().quantity)
    }

    @Test
    fun `a checkout that fails keeps the server cart`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 0)

        fx.paymentMethod("fake")

        val (alice, caller) = user("Alice")

        h.cart.addItem(alice.id, CartLine(p.id, 0, 1, emptyMap(), null))
        expect("OUT_OF_STOCK", 409) { h.checkout(json("paymentMethodId" to "fake"), caller = caller) }
        assertEquals(1, w.cartItems.getByCartId(w.carts.getByUserId(alice.id, pool)!!.id, pool).size)
    }

    // ============================================================================================ rate limit L1 (06 section 6.9)

    @Test
    fun `TOO_MANY_REQUESTS after the budget, the IP token is spent before the replay lookup and a replay spends no buyer token`(): Unit = runBlocking {
        val p = fx.product(price = 100, stock = 20)

        fx.paymentMethod("fake")

        h.config = h.config.copy(checkoutRateLimitPerMinute = 3)

        val (_, a) = user("Alice")
        val (_, b) = user("Bob")
        val from = { c: QuoteCaller -> c.copy(ip = "203.0.113.50") }

        // Alice: order (IP 1, buyer 1), replay (IP 2, no buyer token), replay (IP 3)
        val first = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "limit-key-00000001", caller = from(a))

        assertEquals(first.order.getString("publicId"), h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "limit-key-00000001", caller = from(a)).order.getString("publicId"))
        assertEquals(first.order.getString("publicId"), h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), key = "limit-key-00000001", caller = from(a)).order.getString("publicId"))

        // the IP is empty now: even Bob, whose own bucket is full, is refused with the wait in seconds
        val extras = expect("TOO_MANY_REQUESTS", 429) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = from(b)) }

        assertEquals(20, extras.getInteger("retryAfter"), "60 s / 3 per minute")
        assertEquals(1, rows("market_order"))

        // another address, the same buyer: his buyer bucket has 3 tokens, the replays used none of Alice's
        val (_, c) = user("Carol")
        val elsewhere = { n: Int -> c.copy(ip = "198.51.100.$n") }

        repeat(3) { n -> h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = elsewhere(n + 1)) }
        expect("TOO_MANY_REQUESTS", 429) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = elsewhere(9)) }
        assertEquals(4, rows("market_order"))
    }

    @Test
    fun `the limit is read from the config at call time, 0 switches it off, an unknown address is not limited`(): Unit = runBlocking {
        val p = fx.product(price = 100, stock = 50)

        fx.paymentMethod("fake")

        val (_, a) = user("Alice")

        h.config = h.config.copy(checkoutRateLimitPerMinute = 1)
        h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = a.copy(ip = "203.0.113.70"))
        expect("TOO_MANY_REQUESTS", 429) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = a.copy(ip = "203.0.113.70")) }

        // no address (untrusted proxy chain): only the buyer bucket applies, and Alice spent hers
        expect("TOO_MANY_REQUESTS", 429) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = a) }

        h.config = h.config.copy(checkoutRateLimitPerMinute = 0)
        repeat(3) { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake"), caller = a.copy(ip = "203.0.113.70")) }
        assertEquals(4, rows("market_order"))
    }

    // ====================================================================================== STORE_BUSY (503) and the hooks

    @Test
    fun `a lock that is never released ends in MarketBusyException after three attempts, nothing is written`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 3)

        fx.paymentMethod("fake")
        h.lockWaitSeconds = 1
        h.rebuild()

        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val locked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holder = async(kotlinx.coroutines.Dispatchers.IO) {
            w.db.tx { conn ->
                conn.preparedQuery("SELECT `id` FROM `pano_market_product` WHERE `id` = ? FOR UPDATE").execute(Tuple.of(p.id)).coAwait()
                locked.complete(Unit)
                release.await()
            }
        }

        locked.await()

        val failure = runCatching { h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")) }.exceptionOrNull()

        release.complete(Unit)
        holder.await()

        assertTrue(failure is MarketBusyException, "$failure")
        nothingWritten()
        assertEquals(3, stockOf(p))
    }

    @Test
    fun `a checkout built without the checkout wiring refuses to run, a quote does not need it`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.paymentMethod("fake")

        val quoteOnly = h.quoteOnlyService()
        val quote = quoteOnly.quote(QuoteInput(items = listOf(CartLine(p.id, 0, 1, emptyMap(), null)), guest = GuestInput("Steve", "s@example.com"), paymentMethodId = "fake"), QuoteCaller.GUEST, pool)

        assertTrue(quote.canCheckout)

        val failure = runCatching { quoteOnly.checkout(parseCheckoutRequest(json("items" to listOf(line(p))), "quote-only-key-0001"), QuoteCaller.GUEST, pool) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `a replay of a completed order answers payment kind COMPLETED`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")
        val body = json("items" to listOf(line(p)), "paymentMethodId" to "fake")
        val first = h.checkout(body, key = "done-key-000000001", caller = caller)
        val order = order(first.order.getString("publicId"))
        val attempt = w.payments.getByOrderId(order.id, pool).single()
        val now = w.clock.now()

        // what a payment event does (MK-077): the attempt succeeds, the order is paid and its reservation committed
        sql("UPDATE `pano_market_payment` SET `status` = 'SUCCEEDED', `paidAmount` = ?, `paidAt` = ?, `closedAt` = ? WHERE `id` = ?", order.gatewayAmount, now, now, attempt.id)
        sql(
            "UPDATE `pano_market_order` SET `status` = 'COMPLETED', `reservationState` = 'COMMITTED', `paidAt` = ?, `paymentId` = ?, `paidAmount` = ?, `updatedAt` = ? WHERE `id` = ?",
            now, attempt.id, order.gatewayAmount, now + 1, order.id
        )
        sql("UPDATE `pano_market_product` SET `soldCount` = `soldCount` + 1 WHERE `id` = ?", p.id)

        val replay = h.checkout(body, key = "done-key-000000001", caller = caller)

        assertEquals("COMPLETED", replay.payment!!.getString("kind"))
        assertEquals("COMPLETED", replay.order.getString("status"))
        assertEquals(first.orderToken, replay.orderToken)
        assertEquals(1, rows("market_order"))
        assertFalse(replay.order.getBoolean("canCancel"))
        assertFalse(replay.order.getBoolean("canRetryPayment"))
    }

    @Test
    fun `the owner view of a pending order says what the buyer may still do`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val view = h.checkout(json("items" to listOf(line(p)), "paymentMethodId" to "fake")).order

        assertFalse(view.getBoolean("limited"))
        assertTrue(view.getBoolean("canCancel"))
        assertTrue(view.getBoolean("canRetryPayment"))
        assertFalse(view.getBoolean("invoiceAvailable"))
        assertFalse(view.getBoolean("refundPending"))
        assertEquals(JsonArray(), view.getJsonArray("shipments"))
        assertEquals("steve@example.com", view.getString("email"))
        assertEquals("fake", view.getJsonObject("payment").getString("methodId"))
        assertEquals("Fake payment", view.getJsonObject("payment").getString("label"))
        assertEquals(10.0, view.getJsonArray("items").getJsonObject(0).getDouble("lineTotal"))
        assertEquals("NONE", view.getJsonArray("items").getJsonObject(0).getString("delivery"))
    }

    // -------------------------------------------------------------------------------------------------- polling helper

    private suspend fun awaitRows(table: String, expected: Long) {
        val deadline = System.nanoTime() + 10_000_000_000L

        while (count(table) < expected) {
            check(System.nanoTime() < deadline) { "no $expected rows in $table within 10 s" }
            delay(20)
        }
    }

}

/** The same caller from another address / agent. */
internal fun QuoteCaller.copy(ip: String? = this.clientIp, agent: String? = this.userAgent, test: Boolean = this.canUseTestMode) = QuoteCaller(userId, test, ip, agent)

// ================================================================================================== the harness

/**
 * The real object graph of a checkout test (17 section 5.3): the DAOs and the clock of a [TestWiring], the real services
 * ([CheckoutService], [OrderService], [ReservationService], [CartService]) and stand-ins for the seams later slices fill: the
 * payment start ([ScriptedStarter]), the credit hold ([LedgerHolds], a ledger posting like the one MK-091 writes), the pending
 * subscription, the block list and the shipping quote. Shared by [CheckoutServiceIT] and [CheckoutRaceIT].
 */
internal class CheckoutHarness(val w: TestWiring, private val vertx: Vertx) {
    @Volatile
    var config: Cfg = Cfg()

    @Volatile
    var replayWaitMs: Long = 5_000

    @Volatile
    var replayPollMs: Long = 500

    @Volatile
    var lockWaitSeconds: Int = 30

    val emails = java.util.concurrent.ConcurrentHashMap<Long, String>()
    val granted = java.util.concurrent.ConcurrentHashMap<Long, Set<String>>()

    @Volatile
    var blocked: (String?, String?, String?, String?, Long?) -> Boolean = { _, _, _, _, _ -> false }

    @Volatile
    var shippingResult: ShippingQuote? = null

    @Volatile
    var ledgerAvailable: Boolean = true

    @Volatile
    var subscriptionsAvailable: Boolean = false

    @Volatile
    var pendingSubscriptions: PendingSubscriptions = PendingSubscriptions.UNAVAILABLE

    @Volatile
    private var phaseAHook: (suspend () -> Unit)? = null

    @Volatile
    private var phaseAOnce = true
    private val phaseAFired = java.util.concurrent.atomic.AtomicBoolean(false)

    val fake = FakePaymentProvider()

    /** The fake provider with a hook in `checkEligibility`: phase A asks it once per checkout, phase B never does. */
    private val hooked = object : com.panomc.plugins.market.spi.payment.PaymentProvider by fake {
        override fun checkEligibility(ctx: com.panomc.plugins.market.spi.payment.PaymentContext, checkout: com.panomc.plugins.market.spi.payment.CheckoutSnapshot): com.panomc.plugins.market.spi.payment.Eligibility {
            val hook = phaseAHook

            if (hook != null && (!phaseAOnce || phaseAFired.compareAndSet(false, true))) runBlocking { hook() }

            return com.panomc.plugins.market.spi.payment.Eligibility.eligible()
        }
    }
    private val lookup = StaticProviderLookup(listOf(hooked))
    private val cipher = SecretCipher(ByteArray(32) { (it + 3).toByte() })
    val forcedIds = ForcedIds(w.ids)
    val holds = LedgerHolds(w)
    val starter = ScriptedStarter(w)

    @Volatile
    private var starterOverride: PaymentStarter? = null

    val legal = LegalTextService(w.db, w.clock, w.legalTexts, { "en-US" })
    lateinit var service: CheckoutService
        private set
    lateinit var cart: CartService
        private set

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = node in granted[userId].orEmpty()
    }

    /** The settings a checkout test turns; a data class so that a test writes `h.config = h.config.copy(legalTextRequired = true)`. */
    data class Cfg(
        val allowGuestCheckout: Boolean = true,
        val allowGiftPurchase: Boolean = true,
        val minimumOrderAmount: Double = 0.0,
        val creditsEnabled: Boolean = true,
        val allowMixedCreditPayment: Boolean = false,
        val onlyAcceptCredits: Boolean = false,
        val testMode: Boolean = false,
        val billingInfoMode: BillingInfoMode = BillingInfoMode.OPTIONAL,
        val legalTextRequired: Boolean = false,
        val creditTopUpEnabled: Boolean = false,
        val creditTopUpFreeAmount: Boolean = false,
        val creditTopUpMin: Double = 1.0,
        val creditTopUpMax: Double = 10000.0,
        val creditName: String = "",
        val checkoutRateLimitPerMinute: Int = 0
    ) {
        fun toConfig() = MarketConfig(
            currency = CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", allowGuestCheckout = allowGuestCheckout,
            allowGiftPurchase = allowGiftPurchase, minimumOrderAmount = minimumOrderAmount, creditsEnabled = creditsEnabled, allowMixedCreditPayment = allowMixedCreditPayment,
            onlyAcceptCredits = onlyAcceptCredits, testMode = testMode, billingInfoMode = billingInfoMode, legalTextRequired = legalTextRequired,
            creditTopUpEnabled = creditTopUpEnabled, creditTopUpFreeAmount = creditTopUpFreeAmount, creditTopUpMin = creditTopUpMin, creditTopUpMax = creditTopUpMax,
            creditName = creditName, checkoutRateLimitPerMinute = checkoutRateLimitPerMinute
        )
    }

    fun useStarter(s: PaymentStarter) {
        starterOverride = s
        rebuild()
    }

    /** Rebuilds the service graph (the limiters, the lock wait, the replay wait are fixed at construction). */
    fun rebuild() {
        val db = MarketDb({ w.pool }, w.clock, lockWaitSeconds)
        val locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
        val redemptions = RedemptionService(w.clock, locks, w.redemptions)
        val reservations = ReservationService(w.clock, locks, redemptions, w.orders)

        cart = CartService(db, w.clock, { config.toConfig() }, w.addresses, w.carts, w.cartItems, w.products, w.variants, w.fields)

        val orderService = OrderService(
            w.clock, forcedIds, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { conn, userId -> cart.clearAfterCheckout(conn, userId) },
            credits = CreditHolds { conn, userId, credits, orderId, key ->
                if (ledgerAvailable) holds.hold(conn, userId, credits, orderId, key) else CreditHolds.UNAVAILABLE.hold(conn, userId, credits, orderId, key)
            },
            subscriptions = PendingSubscriptions { conn, order, item ->
                if (subscriptionsAvailable) pendingSubscriptions.createPending(conn, order, item) else PendingSubscriptions.UNAVAILABLE.createPending(conn, order, item)
            }
        )
        val deps = CheckoutDeps(
            db = db, locks = locks, reservations = reservations, redemptions = redemptions, orders = orderService, payments = w.payments,
            providerMeta = w.providerMeta, starter = starterOverride ?: starter, replayWaitMs = replayWaitMs, replayPollMs = replayPollMs
        )

        service = build(deps)
    }

    fun quoteOnlyService(): CheckoutService = build(null)

    private fun build(deps: CheckoutDeps?) = CheckoutService(
        config = { config.toConfig() }, clock = w.clock, categories = w.categories, products = w.products, variants = w.variants, prices = w.prices,
        fields = w.fields, bundleItems = w.bundleItems, discounts = w.discounts, coupons = w.coupons, creatorCodes = w.creatorCodes,
        currencyRates = w.currencyRates, redemptions = w.redemptions, orders = w.orders, entitlements = w.entitlements,
        subscriptions = w.subscriptions, creditAccounts = w.creditAccounts, carts = w.carts, cartItems = w.cartItems,
        paymentMethods = w.paymentMethods, lookup = SwitchingLookup(lookup), cipher = cipher,
        contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
        legal = legal, users = directory, servers = ServerDirectory { _, _ -> emptySet() },
        blocks = BuyerBlocks { payer, recipient, email, ip, userId, _ -> blocked(payer, recipient, email, ip, userId) },
        shipping = ShippingQuoter { _, _ -> shippingResult ?: ShippingQuote(null) },
        checkout = deps
    )

    /**
     * Runs [action] once phase A has read and priced its rows (the provider is asked `checkEligibility` at that moment) and before the
     * order transaction: the window in which another request can change a price or a discount. [once]: only the first time.
     */
    fun afterPhaseA(once: Boolean, action: suspend () -> Unit) {
        phaseAOnce = once
        phaseAFired.set(false)
        phaseAHook = action
    }

    // ---- request building

    fun line(product: MarketProduct, quantity: Int = 1, variant: Long = 0, values: Map<String, Any> = emptyMap(), server: Long? = null): Map<String, Any?> =
        buildMap {
            put("productId", product.id)

            if (variant != 0L) put("variantId", variant)

            put("quantity", quantity)

            if (values.isNotEmpty()) put("fieldValues", values)
            if (server != null) put("targetServerId", server)
        }

    /** A checkout body as the route parses it; a guest identity is added unless the test passes `guest` itself or the caller is logged in. */
    fun body(vararg pairs: Pair<String, Any?>): JsonObject {
        val map = LinkedHashMap<String, Any?>()

        for ((k, v) in pairs) map[k] = v

        if (!map.containsKey("guest")) map["guest"] = mapOf("username" to "Steve", "email" to "steve@example.com")
        if (map["guest"] == null) map.remove("guest")

        return JsonObject(normalise(map) as Map<String, Any?>)
    }

    private fun normalise(value: Any?): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> value.forEach { (k, v) -> out[k.toString()] = normalise(v) } }
        is Iterable<*> -> value.map { normalise(it) }
        else -> value
    }

    private val keys = AtomicLong()

    fun nextKey(): String = "key-" + keys.incrementAndGet().toString().padStart(16, '0')

    suspend fun checkout(body: JsonObject, key: String = nextKey(), caller: QuoteCaller = QuoteCaller.GUEST): CheckoutResult =
        service.checkout(parseCheckoutRequest(if (caller.loggedIn) body.copy().also { it.remove("guest") } else body, key), caller, w.pool)

    suspend fun quoteTotal(product: MarketProduct): Double =
        service.quote(QuoteInput(items = listOf(CartLine(product.id, 0, 1, emptyMap(), null)), guest = GuestInput("Steve", "steve@example.com")), QuoteCaller.GUEST, w.pool).total / 100.0

    /** The provider list is read at call time. */
    private class SwitchingLookup(private val base: ProviderLookup) : ProviderLookup {
        override fun allPayment() = base.allPayment()

        override fun allShipping() = base.allShipping()

        override fun state(kind: com.panomc.plugins.market.provider.ProviderKind, id: String) = base.state(kind, id)

        override fun listing(kind: com.panomc.plugins.market.provider.ProviderKind) = base.listing(kind)
    }

    init {
        rebuild()
    }
}

/** [Ids] that hands out queued values first (a forced collision), then the deterministic sequence. */
internal class ForcedIds(private val base: SeqIds) : Ids {
    private val publicIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val references = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun queuePublicIds(vararg ids: String) {
        publicIds.addAll(ids)
    }

    fun queueReferences(vararg ids: String) {
        references.addAll(ids)
    }

    override fun publicId(): String = publicIds.poll() ?: base.publicId()

    override fun hexToken(bytes: Int): String = base.hexToken(bytes)

    override fun reference(): String = references.poll() ?: base.reference()

    override fun uuid(): String = base.uuid()
}

/**
 * Stand-in for `PaymentService` (MK-076): phase C of 06 section 9.2. Records the attempts it was asked to start; the result is a
 * `REDIRECT` start that moves the attempt `CREATED` to `PENDING` the way tx2 does, or a failure that moves it to `FAILED`.
 * [delayMs] holds the call open (the gateway is slow).
 */
internal class ScriptedStarter(private val w: TestWiring) : PaymentStarter {
    val started = CopyOnWriteArrayList<Long>()

    @Volatile
    var delayMs: Long = 0

    @Volatile
    var failure: PaymentStartFailed? = null

    @Volatile
    var crash: Throwable? = null

    override suspend fun start(order: MarketOrder, attempt: MarketPayment, sqlClient: SqlClient): JsonObject? {
        started += attempt.id

        if (delayMs > 0) delay(delayMs)

        val problem = failure ?: crash

        if (problem != null) {
            val code = (problem as? PaymentStartFailed)?.code ?: "INTERNAL"

            sqlClient.preparedQuery("UPDATE `pano_market_payment` SET `status` = 'FAILED', `failureCode` = ?, `closedAt` = ? WHERE `id` = ? AND `status` = 'CREATED'")
                .execute(Tuple.of(code, w.clock.now(), attempt.id)).coAwait()

            throw problem
        }

        val start = JsonObject().put("kind", "REDIRECT").put("url", "https://gateway.invalid/pay/${attempt.reference}").put("expiresAt", attempt.expiresAt)

        sqlClient.preparedQuery("UPDATE `pano_market_payment` SET `status` = 'PENDING', `startKind` = 'REDIRECT', `startPayload` = ?, `startedAt` = ? WHERE `id` = ? AND `status` = 'CREATED'")
            .execute(Tuple.of(start.encode(), w.clock.now(), attempt.id)).coAwait()

        return start
    }

    override suspend fun served(attempt: MarketPayment, sqlClient: SqlClient): JsonObject? = attempt.startPayload?.let { JsonObject(it) }
}

/**
 * Stand-in for `CreditService.hold` (MK-091, 07 section 5): a real ledger posting inside the order transaction. The user
 * account and the `HOLD` system account are locked in ascending id order, the user is debited with the non-negative guard
 * (`InsufficientCredits` when it refuses), `HOLD` credited, one `HOLD` tx with its two entries written under the key
 * `order:<id>:hold`. [failWith] / [failOnceWith] script a refusal or a deadlock.
 */
internal class LedgerHolds(private val w: TestWiring) {
    val calls = CopyOnWriteArrayList<Pair<Long, String>>()
    val attempts = AtomicInteger()

    @Volatile
    var failWith: Throwable? = null

    @Volatile
    var failOnceWith: Throwable? = null

    suspend fun hold(conn: SqlConnection, userId: Long, credits: Long, orderId: Long, key: String) {
        attempts.incrementAndGet()

        failOnceWith?.let {
            failOnceWith = null

            throw it
        }
        failWith?.let { throw it }

        val account = w.creditAccounts.getByUserId(userId, conn)!!
        val hold = w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, conn)!!

        w.creditAccounts.lockByIds(listOf(account.id, hold.id), conn)

        if (w.creditAccounts.addToBalance(account.id, -credits, true, conn) == 0) {
            throw InsufficientCredits(w.creditAccounts.getById(account.id, conn)!!.balance / 100.0)
        }

        w.creditAccounts.addToBalance(hold.id, credits, false, conn)

        val now = w.clock.now()
        val tx = w.creditTxs.add(MarketCreditTx(type = CreditTxType.HOLD, idempotencyKey = key, userId = userId, amount = credits, orderId = orderId, createdAt = now, updatedAt = now), conn)!!

        w.creditEntries.add(MarketCreditEntry(txId = tx, accountId = account.id, amount = -credits, balanceAfter = w.creditAccounts.getById(account.id, conn)!!.balance, createdAt = now, updatedAt = now), conn)
        w.creditEntries.add(MarketCreditEntry(txId = tx, accountId = hold.id, amount = credits, balanceAfter = w.creditAccounts.getById(hold.id, conn)!!.balance, createdAt = now, updatedAt = now), conn)

        calls += orderId to key
    }
}
