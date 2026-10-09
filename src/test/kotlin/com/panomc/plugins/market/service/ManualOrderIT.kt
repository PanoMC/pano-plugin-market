package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.pricing.ShippingCharge
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketAddress
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.support.ErrorBodies

/**
 * Manual orders from the panel on a real MariaDB (MK-094; 06 section 14.3, 05 section 12, 04 section 7 `POST /orders` and `/orders/quote`, P-19 twin):
 * `source = PANEL`, `createdBy`, method `manual`, no attempt, `markPaid` (O2 with actor `ADMIN` in the creating transaction), `runDeliveries` /
 * `sendMail`, `priceOverride`, `force`, idempotency, the payers (account or name only), the shipping fields, the errors. The invariants I1 to I22 are
 * checked after every test by the base class.
 */
class ManualOrderIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var rig: BankRig
    private val vertx: Vertx = Vertx.vertx()
    private val admin = 77L

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        rig = BankRig(w, vertx)
    }

    private val fx get() = w.fixtures
    private val ph get() = rig.ph

    // ------------------------------------------------------------------------------------------------------ helpers

    private fun line(product: MarketProduct, quantity: Int = 1) = CartLine(product.id, 0, quantity, emptyMap(), null)

    private fun request(
        vararg lines: CartLine,
        player: String = "Steve",
        key: String = rig.nextKey(),
        hash: String = "body-1",
        markPaid: Boolean = false,
        runDeliveries: Boolean = true,
        sendMail: Boolean = true,
        priceOverride: Long? = null,
        force: Boolean = false,
        recipient: String? = null,
        email: String? = null,
        label: String? = null,
        note: String? = null,
        shippingAddress: JsonObject? = null,
        shippingMethodId: Long? = null,
        shippingPrice: Long? = null
    ) = ManualOrderRequest(
        playerUsername = player, recipientUsername = recipient, email = email, items = lines.toList(), priceOverride = priceOverride, markPaid = markPaid,
        paymentLabel = label, runDeliveries = runDeliveries, sendMail = sendMail, note = note, force = force, shippingAddress = shippingAddress,
        shippingMethodId = shippingMethodId, shippingPrice = shippingPrice, idempotencyKey = key, bodyHash = hash, orderLocale = "en-US"
    )

    private suspend fun create(request: ManualOrderRequest): ManualOrderResult = rig.checkout.createManualOrder(request, admin, pool)

    private suspend fun order(id: Long): MarketOrder = ph.order(id)

    private suspend fun stock(product: MarketProduct): Int? = w.products.getById(product.id, pool)!!.stock

    private suspend fun timeline(orderId: Long) = w.orderEvents.getByOrderId(orderId, pool)

    private suspend fun user(name: String = "Alex", email: String = "$name@example.com") = fx.user(name).also { rig.emails[it.id] = email }

    private suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject {
        val e = try {
            block()

            null
        } catch (e: Error) {
            e
        } ?: error("expected $code, nothing was thrown")

        assertEquals(code, e.getErrorCode(), "error code, body ${e.encode()}")
        assertEquals(status, e.getStatusCode())

        return ErrorBodies.details(e)
    }

    // ==================================================================================================== P-19 twin

    @Test
    fun `P-19 twin markPaid with runDeliveries is a paid PANEL order with actor ADMIN and no attempt`(): Unit = runBlocking {
        val alex = user()
        val product = fx.product(price = 1000, stock = 5)
        val result = create(request(line(product, 2), player = "Alex", markPaid = true, note = "paid in cash", label = "Cash"))
        val order = order(result.id)

        assertEquals(result.publicId, order.publicId)
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(OrderSource.PANEL, order.source)
        assertEquals(admin, order.createdBy)
        assertEquals("manual", order.paymentMethodId)
        assertEquals("Cash", order.paymentLabel)
        assertEquals("paid in cash", order.note)
        assertEquals(alex.id, order.userId)
        assertEquals("u:${alex.id}", order.buyerKey)
        assertEquals("alex@example.com", order.email)
        assertEquals("Alex", order.playerUsername)
        assertEquals("Alex", order.recipientUsername)
        assertFalse(order.isGift)
        assertFalse(order.testMode)
        assertNull(order.clientIp)
        assertNull(order.userAgent)
        assertEquals(2000, order.totalPrice)
        assertEquals(order.totalPrice, order.gatewayAmount)
        assertEquals(order.gatewayAmount, order.paidAmount, "paidAmount = gatewayAmount")
        assertNull(order.paymentId)
        assertNotNull(order.paidAt)
        assertNull(order.expiresAt)
        assertEquals(ReservationState.COMMITTED, order.reservationState)
        assertEquals(0, ph.attempts(order.id).size, "a manual order has no attempt row")
        assertEquals(3, stock(product))
        assertEquals(2, w.products.getById(product.id, pool)!!.soldCount)
        assertEquals(1, ph.effects.of(order.id).count { it == "QueueGrantDeliveries" }, "deliveries run")
        assertEquals(1, ph.effects.of(order.id).count { it == "QueueMail" })
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })

        val created = timeline(order.id).single { it.type == OrderEventType.CREATED }
        val data = JsonObject(created.data!!)

        assertEquals(OrderActorType.ADMIN, created.actorType)
        assertEquals(admin, created.actorUserId)
        assertEquals("PANEL", data.getString("source"))
        assertTrue(data.getBoolean("runDeliveries"))
        assertTrue(data.getBoolean("sendMail"))
        assertTrue(data.getBoolean("markPaid"))

        val status = timeline(order.id).single { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "COMPLETED" }

        assertEquals(OrderActorType.ADMIN, status.actorType)
        assertEquals(admin, status.actorUserId)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `runDeliveries false and sendMail false are stored and honoured at O2, entitlements and the invoice still happen`(): Unit = runBlocking {
        val product = fx.product(price = 1000, stock = 5)
        val quiet = order(create(request(line(product), runDeliveries = false, sendMail = false, markPaid = true)).id)
        val loud = order(create(request(line(product), runDeliveries = true, sendMail = true, markPaid = true)).id)

        assertEquals(OrderStatus.COMPLETED, quiet.status)

        val quietEffects = ph.effects.of(quiet.id)

        assertTrue("GrantEntitlements" in quietEffects, "entitlements are created: $quietEffects")
        assertTrue("IssueInvoice" in quietEffects)
        assertFalse("QueueGrantDeliveries" in quietEffects, "no GRANT delivery rows: $quietEffects")
        assertFalse(quietEffects.any { it == "QueueMail" }, "no mail: $quietEffects")

        val flags = ManualFlags.of(timeline(quiet.id).single { it.type == OrderEventType.CREATED }.data)

        assertFalse(flags.runDeliveries)
        assertFalse(flags.sendMail)
        assertTrue("QueueGrantDeliveries" in ph.effects.of(loud.id))
        assertTrue("QueueMail" in ph.effects.of(loud.id))

        assertTrue(ManualFlags.of(null).runDeliveries)
        assertTrue(ManualFlags.of("not json").sendMail)
        assertTrue(ManualFlags.of("""{"source":"STOREFRONT"}""").runDeliveries)
    }

    @Test
    fun `markPaid false leaves a PENDING order that reserves stock and expires after bankTransferExpiryHours, the payer pays it from the order page`(): Unit = runBlocking {
        rig.config = rig.config(bankHours = 48)
        fx.paymentMethod("fake")

        val alex = user()
        val product = fx.product(price = 1000, stock = 3)
        val order = order(create(request(line(product), player = "Alex", runDeliveries = false)).id)

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals(order.createdAt + 48L * 60 * 60 * 1000, order.expiresAt)
        assertEquals(0, ph.attempts(order.id).size)
        assertNull(order.paidAt)
        assertEquals(2, stock(product))
        assertEquals("manual", order.paymentMethodId)
        assertTrue(ph.effects.of(order.id).isEmpty())

        // the payer (logged in, owner by userId) pays with any method; the method of the order becomes the real one
        ph.fake.onStart = { req -> StartPaymentResult.Redirect("https://gateway.invalid/pay/${req.attempt.reference}") }

        val start = rig.payments.pay(order, PayRequest("fake", null, null), PayCaller(), pool)

        assertEquals("REDIRECT", start!!.getString("kind"))
        assertEquals("fake", order(order.id).paymentMethodId)

        val attempt = ph.attempts(order.id).single()

        ph.succeed(order.id, attempt)

        val done = order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(alex.id, done.userId)
        assertFalse("QueueGrantDeliveries" in ph.effects.of(order.id), "runDeliveries = false travels with the order and is honoured at its O2")
    }

    // ==================================================================================================== idempotency

    @Test
    fun `P-19 twin the same Idempotency-Key and body answers the first order, another body is IDEMPOTENCY_CONFLICT`(): Unit = runBlocking {
        val product = fx.product(price = 1000, stock = 5)
        val key = rig.nextKey()
        val first = create(request(line(product), key = key, markPaid = true))
        val second = create(request(line(product), key = key, markPaid = true))

        assertEquals(first.id, second.id)
        assertEquals(first.publicId, second.publicId)
        assertTrue(second.replay)
        assertFalse(first.replay)
        assertEquals(1L, count("market_order"))
        assertEquals(4, stock(product), "the replay reserved nothing")
        assertEquals(1, ph.effects.of(first.id).count { it == "QueueGrantDeliveries" })

        expect("IDEMPOTENCY_CONFLICT", 409) { create(request(line(product, 2), key = key, hash = "body-2", markPaid = true)) }
        assertEquals(1L, count("market_order"))
    }

    @Test
    fun `ten concurrent requests with one key make one order, one reservation, one set of effects`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = fx.product(price = 1000, stock = 20)
            val key = rig.nextKey()
            val before = count("market_order")
            val results = Race.run(10) { create(request(line(product), key = key, markPaid = true)) }

            assertTrue(results.all { it.isSuccess }, "${results.mapNotNull { it.exceptionOrNull() }}")

            val ids = results.map { it.getOrThrow().id }.toSet()

            assertEquals(1, ids.size)
            assertEquals(before + 1, count("market_order"))
            assertEquals(19, stock(product))
            assertEquals(1, ph.effects.of(ids.single()).count { it == "QueueGrantDeliveries" })
            assertEquals(1, results.count { !it.getOrThrow().replay }, "exactly one request created it")
        }
    }

    // ==================================================================================================== pricing

    @Test
    fun `priceOverride is the order total, spread over the lines, and above the list is 400 BAD_REQUEST`(): Unit = runBlocking {
        val a = fx.product(price = 1000, stock = 5)
        val b = fx.product(price = 3000, stock = 5)
        val result = create(request(line(a), line(b), priceOverride = 2000, markPaid = true))
        val order = order(result.id)
        val items = w.orderItems.getByOrderIds(listOf(order.id), pool)

        assertEquals(2000, order.totalPrice)
        assertEquals(2000, order.gatewayAmount)
        assertEquals(2000, items.sumOf { it.lineTotal })
        assertEquals(2000, order.paidAmount)
        assertEquals(0, order.paymentFee)
        assertEquals(0, order.shippingTotal)
        assertEquals(2000, order.subtotal - order.discountTotal, "the override is what the list lost: ${order.subtotal} - ${order.discountTotal}")

        expect("BAD_REQUEST", 400) { create(request(line(a), priceOverride = 1001, key = rig.nextKey())) }
        expect("BAD_REQUEST", 400) { rig.checkout.quoteManual(request(line(a), priceOverride = 1001), pool) }

        val free = order(create(request(line(a), priceOverride = 0, markPaid = true)).id)

        assertEquals(0, free.totalPrice)
        assertEquals(OrderStatus.COMPLETED, free.status, "a zero total takes the same path")
    }

    @Test
    fun `the quote prices what creation charges, automatic discounts apply and are counted, codes never do`(): Unit = runBlocking {
        val product = fx.product(price = 1000, stock = 5)
        val discount = fx.discount(value = 1000, unit = DiscountUnit.PERCENT, usageLimit = 3)
        val quote = rig.checkout.quoteManual(request(line(product, 2)), pool)

        assertEquals(1800, quote.total)
        assertEquals(200, quote.discountTotal)
        assertTrue(quote.canCheckout, "${quote.messages.map { it.code }}")
        assertEquals(0, quote.paymentFee)
        assertTrue(quote.paymentMethods.isEmpty())

        val order = order(create(request(line(product, 2), markPaid = true)).id)

        assertEquals(quote.total, order.totalPrice)
        assertEquals(quote.discountTotal, order.discountTotal)
        assertEquals(1, w.discounts.getById(discount.id, pool)!!.usedCount)
        assertNull(order.couponId)
        assertEquals(0, order.couponDiscount)
        assertEquals(1, w.redemptions.getByOrderId(order.id, pool).count { it.kind == RedemptionKind.DISCOUNT })
    }

    // ==================================================================================================== skipped rules

    @Test
    fun `no guest setting, no minimum amount, no block list, no legal text and no payment method stand in an admin's way, a blocked buyer is a warning`(): Unit = runBlocking {
        rig.config = rig.config(guests = false)
        rig.blockedNames = setOf("badguy")

        val product = fx.product(price = 100, stock = 5)
        val result = create(request(line(product), player = "BadGuy", markPaid = true))
        val order = order(result.id)

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals("g:badguy", order.buyerKey, "no Pano account: a guest style key")
        assertNull(order.userId)
        assertEquals(listOf("BUYER_BLOCKED"), result.warnings.map { it.code })
        assertNull(order.legalTextId)
        assertNull(order.billingInfo)
        assertNull(order.email)
    }

    @Test
    fun `the payer is an account or a name, a Bedrock name is fine, an unreadable name or e-mail is refused`(): Unit = runBlocking {
        val product = fx.product(price = 100, stock = 20)
        val unknown = order(create(request(line(product), player = "Nobody_1", markPaid = true)).id)

        assertEquals("g:nobody_1", unknown.buyerKey)
        assertNull(unknown.userId)

        val bedrock = order(create(request(line(product), player = ".Steve*", markPaid = true, email = "Steve@Example.com")).id)

        assertEquals("g:.steve*", bedrock.buyerKey)
        assertEquals(".Steve*", bedrock.playerUsername)
        assertEquals("steve@example.com", bedrock.email)

        for (bad in listOf("", "a b", "x".repeat(33), "Steve;DROP", "Ste\nve")) {
            assertTrue(runCatching { create(request(line(product), player = bad)) }.exceptionOrNull() is com.panomc.plugins.market.error.RequestValueException, "'$bad'")
        }

        expect("BUYER_INFO_REQUIRED", 400) { create(request(line(product), player = "Steve", email = "not an email")) }

        val alex = user("Alex")
        val account = order(create(request(line(product), player = "ALEX", markPaid = true)).id)

        assertEquals("u:${alex.id}", account.buyerKey, "the name is matched case-insensitively and stored as the account has it")
        assertEquals("Alex", account.playerUsername)
        assertEquals("alex@example.com", account.email, "the account's e-mail")

        val override = order(create(request(line(product), player = "Alex", email = "other@example.com", markPaid = true)).id)

        assertEquals("other@example.com", override.email, "an explicit e-mail wins")
    }

    @Test
    fun `a gift is allowed whatever the store says, limits are judged for the recipient`(): Unit = runBlocking {
        val alex = user("Alex")
        val bob = user("Bob")
        val limited = fx.product(price = 100, stock = 20, columns = mapOf("limitPerPlayer" to 1, "allowGift" to false))

        // GIFT_NOT_ALLOWED never applies to an admin
        val gift = order(create(request(line(limited), player = "Alex", recipient = "Bob", markPaid = true)).id)

        assertTrue(gift.isGift)
        assertEquals(bob.id, gift.recipientUserId)
        assertEquals("Bob", gift.recipientUsername)
        assertEquals(alex.id, gift.userId)

        // the recipient has used the product's limit, the payer has not
        expect("PURCHASE_LIMIT_REACHED", 409) { create(request(line(limited), player = "Alex", recipient = "Bob")) }
        expect("PURCHASE_LIMIT_REACHED", 409) { create(request(line(limited), player = "Bob")) }
        create(request(line(limited), player = "Alex", markPaid = true))

        // the quote says the same for the named player
        val quote = rig.checkout.quoteManual(request(line(limited), player = "Bob"), pool)

        assertFalse(quote.canCheckout)
        assertTrue(quote.lines.single().errors.contains("PURCHASE_LIMIT_REACHED"))
    }

    @Test
    fun `an unknown recipient name is INVALID_RECIPIENT`(): Unit = runBlocking {
        val product = fx.product(price = 100, stock = 20)

        expect("INVALID_RECIPIENT", 400) { create(request(line(product), player = "Alex", recipient = "bad name!")) }
        assertEquals(0L, count("market_order"))
    }

    // ==================================================================================================== force

    @Test
    fun `limits are enforced unless force, force sells an inactive product and clamps the stock at 0`(): Unit = runBlocking {
        user("Alex")

        val limited = fx.product(price = 100, stock = 20, columns = mapOf("limitPerPlayer" to 1))

        create(request(line(limited), player = "Alex", markPaid = true))
        expect("PURCHASE_LIMIT_REACHED", 409) { create(request(line(limited), player = "Alex")) }

        val forced = order(create(request(line(limited), player = "Alex", force = true, markPaid = true)).id)

        assertEquals(OrderStatus.COMPLETED, forced.status)

        // stock
        val last = fx.product(price = 100, stock = 1)

        expect("OUT_OF_STOCK", 409) { create(request(line(last, 2), player = "Alex")) }
        assertEquals(1, stock(last), "a refused order reserves nothing")

        val clamped = order(create(request(line(last, 3), player = "Alex", force = true, markPaid = true)).id)
        val item = w.orderItems.getByOrderIds(listOf(clamped.id), pool).single()

        assertEquals(0, stock(last), "GREATEST(stock - 3, 0)")
        assertEquals(1, item.stockReserved, "min(requested, stock before)")
        assertEquals(3, item.quantity)

        // an inactive (not deleted) product
        val inactive = fx.product(price = 100, stock = 5, status = MarketStatus.INACTIVE)

        expect("INVALID_CART", 400) { create(request(line(inactive), player = "Alex")) }

        val sold = order(create(request(line(inactive), player = "Alex", force = true, markPaid = true)).id)

        assertEquals(OrderStatus.COMPLETED, sold.status)
        assertEquals(4, stock(inactive))
    }

    @Test
    fun `a required permission and a login are never asked of an admin`(): Unit = runBlocking {
        val product = fx.product(price = 100, stock = 20, columns = mapOf("requiredPermission" to "pano.some.node"))
        val order = order(create(request(line(product), player = "Nobody", markPaid = true)).id)

        assertEquals(OrderStatus.COMPLETED, order.status)
    }

    // ==================================================================================================== errors

    @Test
    fun `EMPTY_CART, INVALID_CART and SUBSCRIPTION_MUST_BE_ALONE leave nothing behind`(): Unit = runBlocking {
        val product = fx.product(price = 100, stock = 5)
        val sub = fx.product(price = 600, columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))

        expect("EMPTY_CART", 400) { create(request()) }
        expect("INVALID_CART", 400) { create(request(CartLine(987_654, 0, 1, emptyMap(), null))) }
        expect("SUBSCRIPTION_MUST_BE_ALONE", 400) { create(request(line(sub))) }
        expect("SUBSCRIPTION_MUST_BE_ALONE", 400) { create(request(line(sub), force = true)) }
        expect("SUBSCRIPTION_MUST_BE_ALONE", 400) { create(request(line(sub), line(product))) }

        assertEquals(0L, count("market_order"))
        assertEquals(5, stock(product))
    }

    // ==================================================================================================== shipping

    private suspend fun physical(price: Long = 2500) =
        fx.product("shirt-${System.nanoTime()}", price = price, stock = 5, columns = mapOf("physical" to true, "weightGrams" to 500))

    private fun address() = JsonObject().put("firstName", "Ayşe").put("lastName", "Yılmaz").put("country", "TR").put("city", "Ankara").put("line1", "Atatürk Bulvarı 1").put("postalCode", "06000")

    @Test
    fun `a physical line needs an address, the payer's default one is used, neither is SHIPPING_ADDRESS_REQUIRED`(): Unit = runBlocking {
        val alex = user("Alex")
        val shirt = physical()

        expect("SHIPPING_ADDRESS_REQUIRED", 400) { create(request(line(shirt), player = "Alex")) }
        assertEquals(0L, count("market_order"))

        w.addresses.add(MarketAddress(userId = alex.id, label = "Home", isDefault = true, firstName = "Ali", country = "TR", city = "Izmir", line1 = "Cumhuriyet 5", postalCode = "35000"), pool)

        val fromDefault = order(create(request(line(shirt), player = "Alex", markPaid = false)).id)
        val stored = JsonObject(fromDefault.shippingAddress!!)

        assertTrue(fromDefault.requiresShipping)
        assertEquals(ShippingStatus.PENDING, fromDefault.shippingStatus)
        assertEquals("Izmir", stored.getString("city"))
        assertEquals(0, fromDefault.shippingTotal, "no method: shippingTotal = 0")
        assertNull(fromDefault.shippingMethodId)
        assertEquals(2500, fromDefault.totalPrice)

        // a name without an account has no default address
        expect("SHIPPING_ADDRESS_REQUIRED", 400) { create(request(line(shirt), player = "Stranger")) }

        val inline = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), markPaid = true)).id)

        assertEquals("Ankara", JsonObject(inline.shippingAddress!!).getString("city"))
        assertEquals(OrderStatus.COMPLETED, inline.status)
    }

    @Test
    fun `shippingPrice replaces the price of the chosen method, the rate engine prices the rest`(): Unit = runBlocking {
        val shirt = physical()

        rig.shippingResult = ShippingQuote(
            ShippingCharge(500, null), methodId = 9, address = address(), methodName = "Standard", snapshot = JsonObject().put("zoneId", 1), weightGrams = 500
        )

        val quoted = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), shippingMethodId = 9, markPaid = true)).id)

        assertEquals(500, quoted.shippingTotal)
        assertEquals(3000, quoted.totalPrice)
        assertEquals(9L, quoted.shippingMethodId)
        assertEquals("Standard", quoted.shippingMethodName)
        assertEquals(500, quoted.shippingWeightGrams)

        val overridden = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), shippingMethodId = 9, shippingPrice = 300, markPaid = true)).id)

        assertEquals(300, overridden.shippingTotal)
        assertEquals(2800, overridden.totalPrice)

        val free = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), shippingMethodId = 9, shippingPrice = 0, markPaid = true)).id)

        assertEquals(0, free.shippingTotal)
        assertEquals(2500, free.totalPrice)

        // a method the engine cannot offer
        rig.shippingResult = ShippingQuote(null, messages = listOf(QuoteMessage("SHIPPING_UNAVAILABLE", "error", reason = "NO_ZONE")), reason = "NO_ZONE")

        expect("SHIPPING_UNAVAILABLE", 400) { create(request(line(shirt), player = "Stranger", shippingAddress = address(), shippingMethodId = 9)) }
    }

    @Test
    fun `shippingPrice is gross also in a VAT-exclusive store, shippingTotal equals the typed price`(): Unit = runBlocking {
        rig.config = rig.config(showVat = false)

        val shirt = fx.product("shirt-vat-${System.nanoTime()}", price = 2500, stock = 50, columns = mapOf("physical" to true, "weightGrams" to 500))

        // the method carries its own 20 percent VAT; the admin types the gross amount
        rig.shippingResult = ShippingQuote(
            ShippingCharge(500, 2000), methodId = 9, address = address(), methodName = "Standard", snapshot = JsonObject().put("zoneId", 1), weightGrams = 500
        )

        val items = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), markPaid = true)).id).totalPrice

        assertTrue(items > 2500, "the VAT-exclusive store adds VAT on top of the item basis")

        for (typed in listOf(300L, 360L, 301L, 1L, 7L, 1200L)) {
            val o = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), shippingMethodId = 9, shippingPrice = typed, markPaid = true)).id)

            assertEquals(typed, o.shippingTotal, "shippingTotal is the typed gross price $typed")
            assertEquals(items + typed, o.totalPrice, "totalPrice = items + shippingPrice for $typed")
            assertEquals(o.totalPrice, o.paidAmount, "the paid amount is the gross total for $typed")
        }

        // 999 gross has no 20 percent basis (832 -> 998, 833 -> 1000): the nearest grossing basis is used, never more than one minor unit away
        val odd = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), shippingMethodId = 9, shippingPrice = 999, markPaid = true)).id)

        assertTrue(Math.abs(odd.shippingTotal - 999) <= 1, "unreachable gross 999 is within one minor unit, was ${odd.shippingTotal}")
        assertEquals(items + odd.shippingTotal, odd.totalPrice)

        // no override: the engine's figure is a basis and VAT is added on top
        val quoted = order(create(request(line(shirt), player = "Stranger", shippingAddress = address(), shippingMethodId = 9, markPaid = true)).id)

        assertEquals(600, quoted.shippingTotal)
    }

    @Test
    fun `a digital order ignores the shipping fields`(): Unit = runBlocking {
        val product = fx.product(price = 100, stock = 5)
        val order = order(create(request(line(product), shippingAddress = address(), shippingMethodId = 9, shippingPrice = 700, markPaid = true)).id)

        assertFalse(order.requiresShipping)
        assertEquals(ShippingStatus.NOT_REQUIRED, order.shippingStatus)
        assertNull(order.shippingAddress)
        assertEquals(0, order.shippingTotal)
        assertEquals(100, order.totalPrice)
    }

    @Test
    fun `the label defaults to Manual payment in the order locale and is cut at 255 characters`(): Unit = runBlocking {
        val product = fx.product(price = 100, stock = 20)

        assertEquals("Manual payment", order(create(request(line(product), markPaid = true)).id).paymentLabel)
        assertEquals("Manuel ödeme", order(create(request(line(product), markPaid = true).let { r -> ManualOrderRequest(r.playerUsername, items = r.items, markPaid = true, idempotencyKey = rig.nextKey(), bodyHash = "x", orderLocale = "tr") }).id).paymentLabel)
        assertEquals(255, order(create(request(line(product), label = "L".repeat(400))).id).paymentLabel.length)
    }

    @Test
    fun `a quote creates nothing and reserves nothing`(): Unit = runBlocking {
        val product = fx.product(price = 100, stock = 5)

        rig.checkout.quoteManual(request(line(product), force = true), pool)

        assertEquals(0L, count("market_order"))
        assertEquals(5, stock(product))
    }
}
