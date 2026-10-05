package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketShippingCarrier
import com.panomc.plugins.market.db.model.MarketShippingMethod
import com.panomc.plugins.market.db.model.MarketShippingRate
import com.panomc.plugins.market.db.model.MarketShippingZone
import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.db.model.ShippingRateBasis
import com.panomc.plugins.market.db.model.ShippingRateSource
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.routes.user.address.AddressBookService
import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.shipping.AddressField
import com.panomc.plugins.market.spi.shipping.AddressResolution
import com.panomc.plugins.market.spi.shipping.QuoteResult
import com.panomc.plugins.market.spi.shipping.RateOption
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakeShippingProvider
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.SeqIds
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Shipping at checkout on a real MariaDB (MK-132; 10 sections 3 to 6 and 16 tests 27 to 40, SH-01 and SH-02 of 17 section 9.7):
 * the quote carries `requiresShipping` and the options, checkout freezes the validated address, the method and the quote
 * snapshot on the order, the live carrier rate has a 5 s timeout and falls back to the rule price, a displayed carrier price is
 * honoured for 30 minutes, a mixed digital + physical order sums up, and the saved addresses of a buyer (at most 10).
 *
 * The real [ShippingService] is plugged into the checkout harness (`h.shipper`), the carrier is the scriptable [FakeShippingProvider].
 */
class ShippingCheckoutIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: CheckoutHarness
    private lateinit var lookup: StaticProviderLookup
    private lateinit var shipping: ShippingService
    private lateinit var carrier: FakeShippingProvider
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
        carrier = FakeShippingProvider()
        lookup = StaticProviderLookup(shipping = listOf(ManualShippingProvider(), carrier))
        shipping = newShipping(5_000)
        h.shipper = shipping
        runBlocking { fx.paymentMethod("fake") }
    }

    private fun newShipping(timeoutMs: Long) = ShippingService(
        clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers,
        currencyRates = w.currencyRates, addresses = w.addresses, lookup = lookup, cipher = com.panomc.plugins.market.provider.SecretCipher(ByteArray(32) { (it + 3).toByte() }),
        contexts = ShippingContexts { provider, settings, testMode -> TestContexts.shipping(provider.id, settings, vertx, testMode) }, quoteTimeoutMs = timeoutMs
    )

    private val fx: Fixtures get() = w.fixtures

    // ------------------------------------------------------------------------------------------------ fixtures

    private suspend fun shirt(price: Long = 2000, weight: Int = 250, stock: Int? = 20, columns: Map<String, Any?> = emptyMap()): MarketProduct =
        fx.product("shirt-${System.nanoTime()}", price = price, stock = stock, columns = mapOf("physical" to true, "weightGrams" to weight) + columns)

    private suspend fun digital(price: Long = 1000): MarketProduct = fx.product("vip-${System.nanoTime()}", price = price, stock = null)

    private suspend fun zone(countries: String = "[\"*\"]", name: String = "Zone ${System.nanoTime()}", position: Int = 0, extra: Map<String, String?> = emptyMap()): MarketShippingZone {
        val z = fx.shippingZone(name, countries, position)

        if (extra.isNotEmpty()) {
            Fixtures.setColumns(pool, "market_shipping_zone", z.id, extra)
        }

        return w.shippingZones.getById(z.id, pool)!!
    }

    private suspend fun method(
        name: String = "Standard",
        zones: List<MarketShippingZone>,
        rows: List<Row> = listOf(Row()),
        source: ShippingRateSource = ShippingRateSource.RULES,
        providerId: String = "manual",
        serviceCode: String? = null,
        threshold: Long? = null,
        handling: Long = 0,
        vat: Long? = null,
        maxWeight: Int? = null,
        minDays: Int? = null,
        maxDays: Int? = null,
        position: Int = 0
    ): MarketShippingMethod {
        val now = w.clock.now()
        val id = w.shippingMethods.add(
            MarketShippingMethod(
                name = name, providerId = providerId, serviceCode = serviceCode, rateSource = source, freeShippingThreshold = threshold, handlingFee = handling,
                vatPercent = vat, minDeliveryDays = minDays, maxDeliveryDays = maxDays, maxWeightGrams = maxWeight, position = position, createdAt = now, updatedAt = now
            ),
            pool
        )

        for (z in zones) {
            rows.forEachIndexed { i, r ->
                w.shippingRates.add(MarketShippingRate(methodId = id, zoneId = z.id, basis = r.basis, rangeFrom = r.from, rangeTo = r.to, price = r.price, perUnitPrice = r.per, position = i, createdAt = now, updatedAt = now), pool)
            }
        }

        return w.shippingMethods.getById(id, pool)!!
    }

    private class Row(val basis: ShippingRateBasis = ShippingRateBasis.FLAT, val from: Long = 0, val to: Long? = null, val price: Long = 500, val per: Long = 0)

    private suspend fun enableCarrier(id: String = carrier.id, settings: String? = null): MarketShippingCarrier {
        val now = w.clock.now()

        w.shippingCarriers.add(MarketShippingCarrier(providerId = id, enabled = true, settings = settings, webhookToken = "a".repeat(40), createdAt = now, updatedAt = now), pool)

        return w.shippingCarriers.getByProviderId(id, pool)!!
    }

    /** The sender of the carrier: a quote needs one (10 section 8.1). */
    private suspend fun carrierWithSender(): MarketShippingCarrier {
        val settings = JsonObject().put("senderCountry", "TR").put("senderCity", "Istanbul").put("senderLine1", "Depo 1").put("senderPostalCode", "34000").encode()

        return enableCarrier(settings = settings)
    }

    private fun json(m: Map<String, Any?>) = JsonObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }

    private val tr = mapOf("firstName" to "Ayse", "lastName" to "Kaya", "phone" to "0532 123 45 67", "country" to "TR", "city" to "Ankara", "district" to "Cankaya", "line1" to "Ataturk Blv 1")
    private val de = mapOf("firstName" to "Hans", "lastName" to "Meier", "phone" to "+4915112345678", "country" to "DE", "city" to "Berlin", "line1" to "Strasse 1", "postalCode" to "10115")

    private fun rate(code: String, amount: Long, currency: String = "EUR", expiresAt: Long? = null, incl: Boolean = true, minDays: Int? = null) =
        RateOption(code, "Service $code", Money(amount, currency)).also {
            it.expiresAt = expiresAt
            it.priceIncludesTax = incl
            it.minDays = minDays
            it.maxDays = minDays?.plus(1)
        }

    private suspend fun quote(
        vararg products: Pair<MarketProduct, Int>, address: Map<String, Any?>? = null, methodId: Long? = null, caller: QuoteCaller = QuoteCaller.GUEST, addressId: Long? = null
    ): Quote = h.service.quote(
        QuoteInput(
            items = products.map { (p, q) -> CartLine(p.id, 0, q, emptyMap(), null) }, shippingAddress = address?.let { json(it) }, shippingAddressId = addressId,
            shippingMethodId = methodId, guest = if (caller.loggedIn) null else GuestInput("Steve", "steve@example.com")
        ),
        caller, pool
    )

    private fun Quote.message(code: String) = messages.firstOrNull { it.code == code }

    private fun body(vararg products: Pair<MarketProduct, Int>, address: Map<String, Any?>? = null, methodId: Long? = null, addressId: Long? = null, extra: Map<String, Any?> = emptyMap()) =
        h.body(
            *buildList<Pair<String, Any?>> {
                add("items" to products.map { (p, q) -> h.line(p, q) })
                add("paymentMethodId" to "fake")

                if (address != null) add("shippingAddress" to address)
                if (addressId != null) add("shippingAddressId" to addressId)
                if (methodId != null) add("shippingMethodId" to methodId)

                extra.forEach { (k, v) -> add(k to v) }
            }.toTypedArray()
        )

    private suspend fun fails(block: suspend () -> Any?): Error {
        try {
            block()
        } catch (e: Error) {
            return e
        }

        error("expected an error, the checkout succeeded")
    }

    private suspend fun expect(code: String, block: suspend () -> Any?): JsonObject {
        val e = fails(block)

        assertEquals(code, e.getErrorCode(), "error code, body ${e.encode()}")
        assertEquals(400, e.getStatusCode(), "status of $code")

        return JsonObject(e.encode())
    }

    private suspend fun order(result: CheckoutResult) = w.orders.getByPublicId(result.order.getString("publicId"), pool)!!

    private fun fieldsOf(extras: JsonObject): List<String> = extras.getJsonArray("fields").map { it.toString() }

    // ================================================================================================ SH-01 (test 35)

    @Test
    fun `SH-01 a physical order end to end - the quote carries the options, checkout freezes address, method, quote and weight`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()
        val std = method("Standard", listOf(z), rows = listOf(Row(ShippingRateBasis.WEIGHT, 0, 1000, 500), Row(ShippingRateBasis.WEIGHT, 1001, null, 500, 100)), minDays = 2, maxDays = 4)

        val noAddress = quote(shirt to 1)

        assertTrue(noAddress.requiresShipping)
        assertTrue(noAddress.shippingOptions.isEmpty())
        assertEquals("info", noAddress.message("SHIPPING_ADDRESS_REQUIRED")!!.level)
        assertFalse(noAddress.canCheckout)

        val q = quote(shirt to 1, address = de)

        assertTrue(q.requiresShipping)
        assertEquals(1, q.shippingOptions.size)
        assertEquals(std.id, q.shippingOptions[0].getLong("methodId"))
        assertEquals(5.0, q.shippingOptions[0].getDouble("price"))
        assertEquals("EUR", q.shippingOptions[0].getString("currency"))
        assertEquals("RULES", q.shippingOptions[0].getString("source"))
        assertEquals(false, q.shippingOptions[0].getBoolean("free"))
        assertEquals(2, q.shippingOptions[0].getInteger("minDays"))
        assertEquals(4, q.shippingOptions[0].getInteger("maxDays"))
        assertEquals(std.id, q.shippingMethodId, "the cheapest option is preselected")
        assertEquals("info", q.message("SHIPPING_METHOD_REQUIRED")!!.level)
        assertEquals(500L, q.shippingTotal)
        assertEquals(2500L, q.total)
        assertTrue(q.canCheckout)

        // without an address
        val missing = expect("SHIPPING_ADDRESS_REQUIRED") { h.checkout(body(shirt to 1)) }

        assertEquals(listOf("firstName", "lastName", "phone", "country", "city", "line1", "postalCode"), fieldsOf(missing))

        // with one
        val result = h.checkout(body(shirt to 1, address = de, methodId = std.id))
        val order = order(result)

        assertTrue(order.requiresShipping)
        assertEquals(500, order.shippingTotal)
        assertEquals(2500, order.totalPrice)
        assertEquals(ShippingStatus.PENDING, order.shippingStatus)
        assertEquals(std.id, order.shippingMethodId)
        assertEquals("Standard", order.shippingMethodName)
        assertEquals(250, order.shippingWeightGrams)
        assertEquals(2000L, order.shippingVatPercent, "20.00 percent as basis points")
        assertEquals(83L, order.shippingVatAmount, "VAT inside 5.00 at 20 percent")
        assertEquals(333L + 83L, order.vatTotal, "vatTotal = VAT inside the 20.00 item + the shipping VAT")

        val address = JsonObject(order.shippingAddress!!)

        assertEquals("DE", address.getString("country"))
        assertEquals("+4915112345678", address.getString("phone"))
        assertEquals("steve@example.com", address.getString("email"), "defaults to the order e-mail")
        assertFalse(address.containsKey("taxNumber"))

        val snapshot = JsonObject(order.shippingQuote!!)

        assertEquals(z.id, snapshot.getLong("zoneId"))
        assertEquals("manual", snapshot.getString("providerId"))
        assertEquals(5.0, snapshot.getDouble("price"))
        assertEquals("EUR", snapshot.getString("currency"))
        assertEquals("RULES", snapshot.getString("source"))
        assertEquals(false, snapshot.getBoolean("free"))
        assertEquals(20.0, snapshot.getDouble("vatPercent"))
        assertEquals(0.83, snapshot.getDouble("vatAmount"))
        assertNull(snapshot.getValue("carrierPrice"))
        assertNull(snapshot.getValue("rateRef"))
        assertEquals(2, snapshot.getInteger("minDays"))
        assertEquals(250, snapshot.getJsonArray("parcels").getJsonObject(0).getInteger("weightGrams"))
        assertEquals(w.clock.now(), snapshot.getLong("quotedAt"))
        assertTrue(w.orderItems.getByOrderIds(listOf(order.id), pool).single().physical)
        assertEquals("Standard", result.order.getJsonObject("shipping").getString("methodName"))
    }

    @Test
    fun `the weight rate rises per started kilogram and the whole cart weighs the sum of the lines`(): Unit = runBlocking {
        val shirt = shirt(weight = 250)
        val z = zone()

        method("Standard", listOf(z), rows = listOf(Row(ShippingRateBasis.WEIGHT, 0, 1000, 500), Row(ShippingRateBasis.WEIGHT, 1001, null, 500, 100)))

        assertEquals(500L, quote(shirt to 3, address = de).shippingTotal, "750 g")
        assertEquals(500L, quote(shirt to 4, address = de).shippingTotal, "1000 g")
        assertEquals(600L, quote(shirt to 5, address = de).shippingTotal, "1250 g: one started kg above 1001")
    }

    // ================================================================================================ SH-02 (test 39)

    @Test
    fun `SH-02 free shipping above the threshold and no zone`(): Unit = runBlocking {
        val shirt = shirt(price = 6000)
        val de = zone("[\"DE\"]", "Germany")
        val std = method("Standard", listOf(de), threshold = 5000, handling = 200)

        val q = quote(shirt to 1, address = this@ShippingCheckoutIT.de)

        assertEquals(true, q.shippingOptions.single().getBoolean("free"))
        assertEquals(0.0, q.shippingOptions.single().getDouble("price"))
        assertEquals(0L, q.shippingTotal, "the free option waives the handling fee too")

        val result = h.checkout(body(shirt to 1, address = this@ShippingCheckoutIT.de, methodId = std.id))
        val order = order(result)

        assertEquals(0, order.shippingTotal)
        assertEquals(6000, order.totalPrice)
        assertEquals(ShippingStatus.PENDING, order.shippingStatus, "a free-shipping order still ships")
        assertEquals(true, JsonObject(order.shippingQuote!!).getBoolean("free"))

        // one cent below the threshold is charged
        val cheaper = shirt(price = 4999)
        val charged = quote(cheaper to 1, address = this@ShippingCheckoutIT.de)

        assertEquals(false, charged.shippingOptions.single().getBoolean("free"))
        assertEquals(700L, charged.shippingTotal, "5.00 rate + 2.00 handling fee")

        // outside every zone
        val outside = quote(shirt to 1, address = tr)

        assertEquals("error", outside.message("SHIPPING_UNAVAILABLE")!!.level)
        assertEquals("NO_ZONE", outside.message("SHIPPING_UNAVAILABLE")!!.reason)
        assertFalse(outside.canCheckout)
        assertTrue(outside.shippingOptions.isEmpty())

        assertEquals("NO_ZONE", expect("SHIPPING_UNAVAILABLE") { h.checkout(body(shirt to 1, address = tr, methodId = std.id)) }.getString("reason"))
        assertEquals(0, count("market_order") - 1, "only the first order was written")
    }

    @Test
    fun `the free threshold counts the physical lines only`(): Unit = runBlocking {
        val shirt = shirt(price = 2500)
        val vip = digital(price = 20000)
        val z = zone()

        method("Standard", listOf(z), threshold = 5000)

        val q = quote(shirt to 1, vip to 1, address = de)

        assertEquals(false, q.shippingOptions.single().getBoolean("free"), "digital 200.00 + physical 25.00, threshold 50.00")
        assertEquals(500L, q.shippingTotal)
    }

    // ================================================================================================ quote messages (27, 28)

    @Test
    fun `no address - SHIPPING_ADDRESS_REQUIRED info, no options, canCheckout false, a digital cart never asks for one`(): Unit = runBlocking {
        val shirt = shirt()
        val vip = digital()

        zone().also { method("Standard", listOf(it)) }

        val q = quote(shirt to 1)

        assertEquals("info", q.message("SHIPPING_ADDRESS_REQUIRED")!!.level)
        assertEquals(listOf("firstName", "lastName", "phone", "country", "city", "line1", "postalCode"), q.message("SHIPPING_ADDRESS_REQUIRED")!!.fields)
        assertTrue(q.shippingOptions.isEmpty())
        assertFalse(q.canCheckout)

        val countryless = quote(shirt to 1, address = mapOf("city" to "Berlin"))

        assertEquals("info", countryless.message("SHIPPING_ADDRESS_REQUIRED")!!.level)

        val digitalOnly = quote(vip to 1, address = de)

        assertFalse(digitalOnly.requiresShipping)
        assertTrue(digitalOnly.shippingOptions.isEmpty())
        assertNull(digitalOnly.messages.firstOrNull { it.code.startsWith("SHIPPING") })
        assertTrue(digitalOnly.canCheckout)
    }

    @Test
    fun `a country only answers with a rule priced estimate, CARRIER methods absent, a fallback method shown as FALLBACK`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        carrierWithSender()
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }

        val rules = method("Rules", listOf(z), rows = listOf(Row(price = 700)), position = 0)
        val live = method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id, position = 1)
        val fallback = method("Fallback", listOf(z), rows = listOf(Row(price = 800)), source = ShippingRateSource.CARRIER_WITH_FALLBACK, providerId = carrier.id, position = 2)

        val q = quote(shirt to 1, address = mapOf("country" to "DE"))
        val m = q.message("SHIPPING_ADDRESS_INVALID")!!

        assertEquals("error", m.level)
        assertTrue("city" in m.fields!! && "line1" in m.fields!!, m.fields.toString())
        assertFalse(q.canCheckout)
        assertEquals(listOf(rules.id, fallback.id), q.shippingOptions.map { it.getLong("methodId") })
        assertEquals(listOf("RULES", "FALLBACK"), q.shippingOptions.map { it.getString("source") })
        assertEquals(0, carrier.quotes.size, "no carrier call for an estimate")
        assertFalse(q.shippingOptions.any { it.getLong("methodId") == live.id })

        // the same cart with a complete address calls the carrier
        val full = quote(shirt to 1, address = de)

        assertEquals(1, carrier.quotes.size)
        assertEquals(listOf("RULES", "CARRIER", "CARRIER"), full.shippingOptions.map { it.getString("source") })
        assertEquals(listOf(7.0, 9.0, 9.0), full.shippingOptions.map { it.getDouble("price") })
    }

    // ================================================================================================ checkout errors (36)

    @Test
    fun `checkout - a missing method id, a method of another zone and no method at all`(): Unit = runBlocking {
        val shirt = shirt()
        val germany = zone("[\"DE\"]", "Germany", position = 0)
        val everywhere = zone("[\"*\"]", "Everywhere", position = 1)
        val deMethod = method("DE only", listOf(germany))
        val world = method("World", listOf(everywhere))

        assertEquals("METHOD_REQUIRED", expect("SHIPPING_UNAVAILABLE") { h.checkout(body(shirt to 1, address = de)) }.getString("reason"))
        assertEquals("METHOD_NOT_OFFERED", expect("SHIPPING_UNAVAILABLE") { h.checkout(body(shirt to 1, address = tr, methodId = deMethod.id)) }.getString("reason"))
        assertEquals("METHOD_NOT_OFFERED", expect("SHIPPING_UNAVAILABLE") { h.checkout(body(shirt to 1, address = de, methodId = 99_999)) }.getString("reason"))
        assertEquals(0, count("market_order"))

        // a sent id that is not offered is a warning in the quote, no id an info
        val q = quote(shirt to 1, address = tr, methodId = deMethod.id)

        assertEquals("warning", q.message("SHIPPING_METHOD_REQUIRED")!!.level)
        assertEquals(world.id, q.shippingMethodId)

        // the method of another zone is dropped when the zone has none: NO_METHOD
        w.shippingMethods.softDelete(world.id, w.clock.now(), pool)
        assertEquals("NO_METHOD", expect("SHIPPING_UNAVAILABLE") { h.checkout(body(shirt to 1, address = tr, methodId = world.id)) }.getString("reason"))
        assertEquals(0, count("market_order"))
    }

    @Test
    fun `checkout - an incomplete address names the missing and invalid fields`(): Unit = runBlocking {
        val shirt = shirt()

        zone().also { method("Standard", listOf(it)) }

        val partial = expect("SHIPPING_ADDRESS_REQUIRED") { h.checkout(body(shirt to 1, address = mapOf("country" to "TR", "firstName" to "Ayse"), methodId = 1)) }

        assertEquals(listOf("lastName", "phone", "city", "district", "line1"), fieldsOf(partial))

        val badPostal = expect("SHIPPING_ADDRESS_REQUIRED") { h.checkout(body(shirt to 1, address = de + ("postalCode" to "1"), methodId = 1)) }

        assertEquals(listOf("postalCode"), fieldsOf(badPostal))

        val badCountry = expect("SHIPPING_ADDRESS_REQUIRED") { h.checkout(body(shirt to 1, address = de + ("country" to "ZZ"), methodId = 1)) }

        assertEquals(listOf("country"), fieldsOf(badCountry))
        assertEquals(0, count("market_order"))
    }

    @Test
    fun `a digital only cart ignores a sent address and method (test 37)`(): Unit = runBlocking {
        val vip = digital()

        zone().also { method("Standard", listOf(it)) }

        val result = h.checkout(body(vip to 1, address = de, methodId = 1))
        val order = order(result)

        assertFalse(order.requiresShipping)
        assertEquals(ShippingStatus.NOT_REQUIRED, order.shippingStatus)
        assertEquals(0, order.shippingTotal)
        assertNull(order.shippingAddress)
        assertNull(order.shippingMethodId)
        assertNull(order.shippingQuote)
    }

    // ================================================================================================ mixed order

    @Test
    fun `a mixed digital and physical order is one order, one payment, shipping on top (test 35)`(): Unit = runBlocking {
        val shirt = shirt(price = 2000)
        val vip = digital(price = 1000)
        val z = zone()
        val std = method("Standard", listOf(z), rows = listOf(Row(price = 600)))

        val result = h.checkout(body(shirt to 2, vip to 1, address = de, methodId = std.id))
        val order = order(result)
        val items = w.orderItems.getByOrderIds(listOf(order.id), pool)

        assertEquals(4000 + 1000 + 600L, order.totalPrice)
        assertEquals(600L, order.shippingTotal)
        assertEquals(2, items.size)
        assertEquals(500, order.shippingWeightGrams, "only the physical line weighs")
        assertEquals(1, items.count { it.physical })
        assertTrue(items.single { it.productId == shirt.id }.physical)
        assertFalse(items.single { it.productId == vip.id }.physical)
        assertEquals(ShippingStatus.PENDING, order.shippingStatus)
    }

    @Test
    fun `a bundle with physical children ships its children, the bundle value split over the units`(): Unit = runBlocking {
        val mug = shirt(price = 500, weight = 300)
        val cap = shirt(price = 500, weight = 100)
        val bundle = fx.bundle(mug to 2, cap to 1, slug = "kit", price = 1500)
        val z = zone()

        method("Standard", listOf(z), rows = listOf(Row(ShippingRateBasis.WEIGHT, 0, 700, 400), Row(ShippingRateBasis.WEIGHT, 701, null, 900)))

        val q = quote(bundle to 1, address = de)

        assertTrue(q.requiresShipping)
        assertEquals(400L, q.shippingTotal, "2 x 300 g + 1 x 100 g = 700 g")
        assertEquals(1900L, q.total)
    }

    // ================================================================================================ saved address

    @Test
    fun `a saved address is used by id, a foreign or missing id counts as no address (test 38)`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()
        val std = method("Standard", listOf(z))
        val (alice, aliceCaller) = user("Alice")
        val (bob, _) = user("Bob")
        val book = book()

        val mine = book.create(alice.id, input(de, label = "Home"))
        val bobs = book.create(bob.id, input(de, label = "Bob's"))

        val q = quote(shirt to 1, caller = aliceCaller, addressId = mine)

        assertEquals(1, q.shippingOptions.size)
        assertNull(q.message("SHIPPING_ADDRESS_REQUIRED"))

        val foreign = quote(shirt to 1, caller = aliceCaller, addressId = bobs)

        assertEquals("info", foreign.message("SHIPPING_ADDRESS_REQUIRED")!!.level)
        assertTrue(foreign.shippingOptions.isEmpty())
        assertEquals("info", quote(shirt to 1, caller = aliceCaller, addressId = 987_654).message("SHIPPING_ADDRESS_REQUIRED")!!.level)

        val result = h.checkout(body(shirt to 1, methodId = std.id, addressId = mine), caller = aliceCaller)

        assertEquals("DE", JsonObject(order(result).shippingAddress!!).getString("country"))

        expect("SHIPPING_ADDRESS_REQUIRED") { h.checkout(body(shirt to 1, methodId = std.id, addressId = bobs), caller = aliceCaller) }
    }

    // ================================================================================================ live carrier rates (29 to 32)

    @Test
    fun `a CARRIER method takes the cheapest of the services, or the service of its code, and ignores an expired rate`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        carrierWithSender()
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 1200, minDays = 1), rate("eco", 800, minDays = 4), rate("old", 100, expiresAt = w.clock.now() - 1))) }

        val cheapest = method("Cheapest", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id, position = 0)
        val express = method("Express", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id, serviceCode = "exp", position = 1)

        val q = quote(shirt to 1, address = de)

        assertEquals(1, carrier.quotes.size, "one call per provider per quote (10 section 5.4)")
        assertNull(carrier.quotes[0].serviceCode, "two carrier candidates of the provider: all services")
        assertEquals(listOf(cheapest.id, express.id), q.shippingOptions.map { it.getLong("methodId") })
        assertEquals(listOf(8.0, 12.0), q.shippingOptions.map { it.getDouble("price") })
        assertEquals(listOf("CARRIER", "CARRIER"), q.shippingOptions.map { it.getString("source") })
        assertEquals(4, q.shippingOptions[0].getInteger("minDays"), "carrier days when present")
        assertEquals(cheapest.id, q.shippingMethodId)

        // frozen on the order: the carrier facts
        val order = order(h.checkout(body(shirt to 1, address = de, methodId = express.id)))
        val snapshot = JsonObject(order.shippingQuote!!)

        assertEquals("exp", snapshot.getString("serviceCode"))
        assertEquals("Service exp", snapshot.getString("serviceName"))
        assertEquals(12.0, snapshot.getDouble("carrierPrice"))
        assertEquals("EUR", snapshot.getString("carrierCurrency"))
        assertEquals("CARRIER", snapshot.getString("source"))
        assertEquals(1200, order.shippingTotal)
    }

    @Test
    fun `the carrier request carries the sender, the buyer address, one parcel, the items and the order value`(): Unit = runBlocking {
        val shirt = shirt(price = 2000, weight = 250, columns = mapOf("lengthMm" to 300, "widthMm" to 200, "heightMm" to 100))
        val z = zone()

        carrierWithSender()
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 1000))) }
        method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id, serviceCode = "exp")

        quote(shirt to 2, address = de)

        val request = carrier.quotes.single()

        assertEquals("TR", request.from.country)
        assertEquals("DE", request.to.country)
        assertEquals("Berlin", request.to.city)
        assertEquals("exp", request.serviceCode, "exactly one carrier candidate: its service code")
        assertEquals(1, request.parcels.size)
        assertEquals(500, request.parcels[0].weightGrams)
        assertEquals(listOf(300, 200, 100), listOf(request.parcels[0].lengthMm, request.parcels[0].widthMm, request.parcels[0].heightMm))
        assertEquals(1, request.items.size)
        assertEquals(2, request.items[0].quantity)
        assertEquals(250, request.items[0].unitWeightGrams)
        assertEquals(2000L, request.items[0].unitValue.amount)
        assertEquals(4000L, request.orderValue.amount)
        assertEquals("EUR", request.currency)
    }

    @Test
    fun `a carrier timeout drops CARRIER, prices the fallback by the rules and costs no more than the limit (test 30)`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        shipping = newShipping(5_000).also { h.shipper = it }
        carrierWithSender()
        carrier.quoteDelayMs = 6_000
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }

        method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id, position = 0)
        val fallback = method("Fallback", listOf(z), rows = listOf(Row(price = 800)), source = ShippingRateSource.CARRIER_WITH_FALLBACK, providerId = carrier.id, position = 1)

        val started = System.nanoTime()
        val q = quote(shirt to 1, address = de)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 6_000, "the quote took $tookMs ms")
        assertTrue(tookMs >= 4_900, "the limit is 5 s, it took $tookMs ms")
        assertEquals(listOf(fallback.id), q.shippingOptions.map { it.getLong("methodId") })
        assertEquals("FALLBACK", q.shippingOptions.single().getString("source"))
        assertEquals(8.0, q.shippingOptions.single().getDouble("price"))
        assertEquals(1, carrier.quotes.size, "one call for both methods")
        assertNotNull(w.shippingCarriers.getByProviderId(carrier.id, pool)!!.lastError, "lastError of the carrier row")
    }

    @Test
    fun `a failing carrier, an unsupported one and an empty answer all fall back, and three failures open the breaker (test 30)`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        shipping = newShipping(400).also { h.shipper = it }
        carrierWithSender()
        method("Fallback", listOf(z), rows = listOf(Row(price = 800)), source = ShippingRateSource.CARRIER_WITH_FALLBACK, providerId = carrier.id)

        carrier.onQuote = { throw com.panomc.plugins.market.spi.common.ProviderException(com.panomc.plugins.market.spi.common.ProviderErrorCode.GATEWAY_UNREACHABLE, "boom") }

        repeat(3) {
            val q = quote(shirt to 1, address = de)

            assertEquals("FALLBACK", q.shippingOptions.single().getString("source"))
            w.clock.advance(1_000)
        }

        assertEquals(3, carrier.quotes.size)
        assertTrue(shipping.breaker.isOpen(carrier.id))

        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }

        assertEquals("FALLBACK", quote(shirt to 1, address = de).shippingOptions.single().getString("source"), "the open breaker answers at once")
        assertEquals(3, carrier.quotes.size, "the fourth quote makes no provider call")

        // 60 s after the third failure the breaker lets one call through; a success closes it
        w.clock.advance(61_000)

        assertEquals("CARRIER", quote(shirt to 1, address = de).shippingOptions.single().getString("source"))
        assertEquals(4, carrier.quotes.size)
        assertFalse(shipping.breaker.isOpen(carrier.id))

        // an empty answer and an unsupported one are "no rate" but no breaker failure
        shipping.cache.clear()
        carrier.onQuote = { QuoteResult(emptyList()) }
        assertEquals("FALLBACK", quote(shirt to 1, address = de).shippingOptions.single().getString("source"))
        carrier.onQuote = { QuoteResult.unsupported() }
        assertEquals("FALLBACK", quote(shirt to 1, address = de).shippingOptions.single().getString("source"))
        assertFalse(shipping.breaker.isOpen(carrier.id))
    }

    @Test
    fun `a second quote inside quoteCacheSeconds makes no call, checkout reuses the displayed price for 30 minutes (test 31)`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        carrierWithSender()
        carrier.caps = carrier.caps.also { it.quoteCacheSeconds = 600 }
        var price = 900L
        carrier.onQuote = { QuoteResult(listOf(rate("exp", price))) }

        val live = method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id)

        assertEquals(9.0, quote(shirt to 1, address = de).shippingOptions.single().getDouble("price"))
        assertEquals(1, carrier.quotes.size)

        w.clock.advance(5 * 60_000)
        quote(shirt to 1, address = de)
        assertEquals(1, carrier.quotes.size, "inside the 600 s window: no call")

        // the carrier now charges more; the buyer pays what was displayed, 20 minutes after the quote
        price = 1500
        w.clock.advance(15 * 60_000)

        val inHonourWindow = h.checkout(body(shirt to 1, address = de, methodId = live.id))

        assertEquals(1, carrier.quotes.size, "checkout inside the honour window makes no call")
        assertEquals(900, order(inHonourWindow).shippingTotal)

        // a quote outside the fresh window asks again; the new price is displayed
        assertEquals(15.0, quote(shirt to 1, address = de).shippingOptions.single().getDouble("price"))
        assertEquals(2, carrier.quotes.size)

        // 31 minutes after the last call: checkout re-quotes
        shipping.cache.clear()
        price = 900
        quote(shirt to 1, address = de)
        assertEquals(3, carrier.quotes.size)
        w.clock.advance(31 * 60_000)
        price = 1100

        val late = h.checkout(body(shirt to 1, address = de, methodId = live.id))

        assertEquals(4, carrier.quotes.size, "after 31 minutes the checkout asks the carrier again")
        assertEquals(1100, order(late).shippingTotal)
    }

    @Test
    fun `saving a carrier clears its cache and a quoteCacheSeconds of 0 never reuses`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        carrierWithSender()
        carrier.caps = carrier.caps.also { it.quoteCacheSeconds = 0 }
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }
        method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id)

        quote(shirt to 1, address = de)
        quote(shirt to 1, address = de)
        assertEquals(2, carrier.quotes.size, "0 = never reuse for quoting")

        // the entry the second call stored is still in the cache: with a window above 0 it is reused now
        carrier.caps = carrier.caps.also { it.quoteCacheSeconds = 600 }
        quote(shirt to 1, address = de)
        quote(shirt to 1, address = de)
        assertEquals(2, carrier.quotes.size)

        shipping.invalidate(carrier.id)
        quote(shirt to 1, address = de)
        quote(shirt to 1, address = de)
        assertEquals(3, carrier.quotes.size, "the saved carrier's entries are gone: one new call, then reused")

        // the other provider's entries stay
        shipping.invalidate("someone-else")
        quote(shirt to 1, address = de)
        assertEquals(3, carrier.quotes.size)
    }

    @Test
    fun `no sender address leaves no live rate, the manual carrier's sender is the fallback, a carrier that needs dimensions asks for them`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        val row = enableCarrier()
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }
        method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id)

        assertTrue(quote(shirt to 1, address = de).shippingOptions.isEmpty(), "no sender, nothing quoted")
        assertEquals(0, carrier.quotes.size)

        // the manual carrier's sender stands in (10 section 8.1)
        val now = w.clock.now()

        w.shippingCarriers.add(
            MarketShippingCarrier(
                providerId = "manual", enabled = true, webhookToken = "b".repeat(40), createdAt = now, updatedAt = now,
                settings = JsonObject().put("senderCountry", "TR").put("senderCity", "Izmir").put("senderLine1", "Depo 2").encode()
            ),
            pool
        )

        val q = quote(shirt to 1, address = de)

        assertEquals(1, q.shippingOptions.size)
        assertEquals("Izmir", carrier.quotes.single().from.city)

        // the carrier's own sender wins over the manual one
        Fixtures.setColumns(pool, "market_shipping_carrier", row.id, mapOf("settings" to JsonObject().put("senderCountry", "TR").put("senderCity", "Istanbul").put("senderLine1", "Depo 1").encode(), "updatedAt" to w.clock.now() + 1))
        shipping.cache.clear()
        quote(shirt to 1, address = de)
        assertEquals("Istanbul", carrier.quotes.last().from.city)

        // a provider that requires dimensions: a product without them has no live rate, a default parcel supplies them
        shipping.cache.clear()
        carrier.caps = carrier.caps.also { it.requiresDimensions = true }
        assertTrue(quote(shirt to 1, address = de).shippingOptions.isEmpty(), "no dimensions, no live rate")

        Fixtures.setColumns(
            pool, "market_shipping_carrier", row.id,
            mapOf("settings" to JsonObject().put("senderCountry", "TR").put("senderCity", "Istanbul").put("senderLine1", "Depo 1").put("defaultParcelLengthMm", 300).put("defaultParcelWidthMm", 200).put("defaultParcelHeightMm", 100).encode(), "updatedAt" to w.clock.now() + 2)
        )
        shipping.cache.clear()
        assertEquals(1, quote(shirt to 1, address = de).shippingOptions.size)
        assertEquals(300, carrier.quotes.last().parcels.single().lengthMm)
    }

    @Test
    fun `a carrier rate in another currency is converted through the base and an unknown currency is unusable`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        w.currencyRates.upsert(MarketCurrencyRate(currency = "USD", rate = BigDecimal("1.25"), mode = CurrencyRateMode.MANUAL, updatedAt = w.clock.now()), pool)
        carrierWithSender()
        method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id)

        carrier.onQuote = { QuoteResult(listOf(rate("exp", 1250, currency = "USD"))) }
        val usd = quote(shirt to 1, address = de)

        assertEquals(10.0, usd.shippingOptions.single().getDouble("price"), "12.50 USD at 1.25 per EUR")
        assertEquals("EUR", usd.shippingOptions.single().getString("currency"))

        shipping.cache.clear()
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 1000, currency = "GBP"))) }

        assertTrue(quote(shirt to 1, address = de).shippingOptions.isEmpty(), "no GBP rate: the carrier price cannot be converted")
    }

    // ================================================================================================ dropped methods (32)

    @Test
    fun `max weight, destination countries, a disabled and an unregistered provider drop a method, all dropped is NO_METHOD`(): Unit = runBlocking {
        val shirt = shirt(weight = 800)
        val z = zone()

        val heavy = method("Light", listOf(z), maxWeight = 500, position = 0)

        carrierWithSender()
        carrier.caps = carrier.caps.also { it.destinationCountries = setOf("TR") }
        val toTr = method("TR only", listOf(z), providerId = carrier.id, position = 1)
        val ghost = method("Ghost", listOf(z), providerId = "ghost", position = 2)
        val ok = method("Ok", listOf(z), position = 3)

        val q = quote(shirt to 1, address = de)

        assertEquals(listOf(ok.id), q.shippingOptions.map { it.getLong("methodId") }, "light ${heavy.id} toTr ${toTr.id} ghost ${ghost.id}")

        // a disabled carrier
        Fixtures.setColumns(pool, "market_shipping_carrier", w.shippingCarriers.getByProviderId(carrier.id, pool)!!.id, mapOf("enabled" to false))
        carrier.caps = carrier.caps.also { it.destinationCountries = null }
        assertEquals(listOf(ok.id), quote(shirt to 1, address = de).shippingOptions.map { it.getLong("methodId") })

        // nothing left
        w.shippingMethods.softDelete(ok.id, w.clock.now(), pool)

        val none = quote(shirt to 1, address = de)

        assertEquals("NO_METHOD", none.message("SHIPPING_UNAVAILABLE")!!.reason)
        assertFalse(none.canCheckout)
    }

    @Test
    fun `an inactive zone and a zone without a rate row for the method are not matched`(): Unit = runBlocking {
        val shirt = shirt()
        val inactive = zone("[\"DE\"]", "Inactive", position = 0)

        Fixtures.setColumns(pool, "market_shipping_zone", inactive.id, mapOf("status" to "INACTIVE"))

        val other = zone("[\"*\"]", "Everywhere", position = 1)
        val noRows = method("No rows", listOf(zone("[\"FR\"]", "France", position = 2)))
        val std = method("Std", listOf(other))

        val q = quote(shirt to 1, address = de)

        assertEquals(listOf(std.id), q.shippingOptions.map { it.getLong("methodId") })
        assertTrue(noRows.id != std.id)
    }

    // ================================================================================================ 3.4 address check (34)

    @Test
    fun `an address the carrier calls invalid blocks the quote, a provider exception is ignored, checkout checks from the cache only`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        carrierWithSender()
        carrier.caps = carrier.caps.also { it.addressResolve = true }
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }
        carrier.onResolve = { AddressResolution.invalid("no such street") }

        val live = method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id)
        val q = quote(shirt to 1, address = de, methodId = live.id)

        assertEquals("error", q.message("SHIPPING_ADDRESS_INVALID")!!.level)
        assertEquals(emptyList<String>(), q.message("SHIPPING_ADDRESS_INVALID")!!.fields)
        assertFalse(q.canCheckout)
        assertEquals(1, carrier.resolves.size)

        // checkout repeats the check from the cache: refused, no new call
        expect("SHIPPING_ADDRESS_REQUIRED") { h.checkout(body(shirt to 1, address = de, methodId = live.id)) }
        assertEquals(1, carrier.resolves.size)

        // an exception or a timeout is ignored: the address is accepted as typed
        shipping.cache.clear()
        carrier.onResolve = { throw IllegalStateException("down") }

        val ok = quote(shirt to 1, address = de, methodId = live.id)

        assertNull(ok.message("SHIPPING_ADDRESS_INVALID"))
        assertTrue(ok.canCheckout)

        // no cache entry at checkout: no network call, accepted
        shipping.cache.clear()
        carrier.onResolve = { AddressResolution.invalid("never asked") }

        val before = carrier.resolves.size
        val result = h.checkout(body(shirt to 1, address = de, methodId = live.id))

        assertEquals(before, carrier.resolves.size)
        assertNotNull(order(result))
    }

    @Test
    fun `a provider that needs the identity number asks for it, the others never keep it`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()

        carrierWithSender()
        carrier.caps = carrier.caps.also { it.requiredAddressFields = setOf(AddressField.IDENTITY_NUMBER) }
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }

        val live = method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id)
        val std = method("Std", listOf(z), position = 1)

        val q = quote(shirt to 1, address = de, methodId = live.id)

        assertEquals(listOf("identityNumber"), q.message("SHIPPING_ADDRESS_INVALID")!!.fields)
        assertEquals(listOf("identityNumber"), fieldsOf(expect("SHIPPING_ADDRESS_REQUIRED") { h.checkout(body(shirt to 1, address = de, methodId = live.id)) }))

        val withId = h.checkout(body(shirt to 1, address = de + ("identityNumber" to "AB12345"), methodId = live.id))

        assertEquals("AB12345", JsonObject(order(withId).shippingAddress!!).getString("identityNumber"))

        val plain = h.checkout(body(shirt to 1, address = de + ("identityNumber" to "AB12345"), methodId = std.id))

        assertFalse(JsonObject(order(plain).shippingAddress!!).containsKey("identityNumber"), "kept only when the selected provider requires it")
    }

    // ================================================================================================ VAT and currency

    @Test
    fun `the handling fee is added before VAT and a method VAT overrides the store rate`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()
        val zero = method("Zero VAT", listOf(z), rows = listOf(Row(price = 1000)), handling = 200, vat = 0, position = 0)
        val std = method("Std", listOf(z), rows = listOf(Row(price = 1000)), handling = 200, position = 1)

        val q = quote(shirt to 1, address = de)

        assertEquals(listOf(12.0, 12.0), q.shippingOptions.map { it.getDouble("price") })

        val o0 = order(h.checkout(body(shirt to 1, address = de, methodId = zero.id)))
        val o1 = order(h.checkout(body(shirt to 1, address = de, methodId = std.id)))

        assertEquals(0, o0.shippingVatAmount)
        assertEquals(0, o0.shippingVatPercent)
        assertEquals(1200 - 1000, o1.shippingVatAmount, "1200 gross at 20 percent contains 200")
        assertEquals(2000, o1.shippingVatPercent)
    }

    @Test
    fun `a store with prices excluding VAT charges the shipping VAT on top, the option shows what is paid (test 20 of 10 section 16)`(): Unit = runBlocking {
        h.config = h.config.copy(showVatInPrice = false)

        val shirt = shirt(price = 2000)
        val z = zone()
        val std = method("Std", listOf(z), rows = listOf(Row(price = 1000)))

        val q = quote(shirt to 1, address = de)

        assertEquals(12.0, q.shippingOptions.single().getDouble("price"), "100 net + 20 percent")
        assertEquals(1200L, q.shippingTotal)

        val order = order(h.checkout(body(shirt to 1, address = de, methodId = std.id)))

        assertEquals(1200, order.shippingTotal)
        assertEquals(200, order.shippingVatAmount)
        assertEquals(2400 + 1200L, order.totalPrice, "items 20.00 net + 4.00 VAT, shipping 12.00")
    }

    @Test
    fun `the order currency prices the shipping through the exchange rate and rounds a whole-unit currency up`(): Unit = runBlocking {
        h.config = h.config.copy(currencyMode = CurrencyMode.MULTI, additionalCurrencies = listOf("JPY"))
        w.currencyRates.upsert(MarketCurrencyRate(currency = "JPY", rate = BigDecimal("160"), mode = CurrencyRateMode.MANUAL, updatedAt = w.clock.now()), pool)

        val shirt = shirt(price = 2000)
        val z = zone()
        val std = method("Std", listOf(z), rows = listOf(Row(price = 733)), vat = 0)

        val q = h.service.quote(
            QuoteInput(items = listOf(CartLine(shirt.id, 0, 1, emptyMap(), null)), currency = "JPY", shippingAddress = json(de), guest = GuestInput("Steve", "steve@example.com")),
            QuoteCaller.GUEST, pool
        )

        assertEquals("JPY", q.currency)
        assertEquals("JPY", q.shippingOptions.single().getString("currency"))
        assertEquals(1173.0, q.shippingOptions.single().getDouble("price"), "7.33 EUR x 160 = 1172.80 yen, rounded up to a whole yen")
        assertEquals(117_300L, q.shippingTotal)
        assertEquals(std.id, q.shippingMethodId)
    }

    // ================================================================================================ gift

    @Test
    fun `a gift purchase of a physical product takes the recipient's address (10 section 6_3)`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()
        val std = method("Std", listOf(z))
        val (alice, aliceCaller) = user("Alice")
        val bob = fx.user("Bob")

        val result = h.checkout(body(shirt to 1, address = de, methodId = std.id, extra = mapOf("recipientUsername" to "Bob")), caller = aliceCaller)
        val order = order(result)

        assertTrue(order.isGift)
        assertEquals(bob.id, order.recipientUserId)
        assertEquals("DE", JsonObject(order.shippingAddress!!).getString("country"))
        assertEquals(alice.id, order.userId)
    }

    @Test
    fun `a gift code of a physical product is refused (PHYSICAL_NOT_SUPPORTED), a digital or credit one is not`(): Unit = runBlocking {
        val shirt = shirt()
        val vip = digital()

        assertEquals("PHYSICAL_NOT_SUPPORTED", ShippingService.giftCodeRefusal(listOf(vip, shirt))!!.let { JsonObject(it.encode()).getString("reason") })
        assertEquals(400, ShippingService.giftCodeRefusal(listOf(shirt))!!.getStatusCode())
        assertEquals("INVALID_GIFT_CODE", ShippingService.giftCodeRefusal(listOf(shirt))!!.getErrorCode())
        assertNull(ShippingService.giftCodeRefusal(listOf(vip)))
        assertNull(ShippingService.giftCodeRefusal(emptyList()))
    }

    // ================================================================================================ fail closed

    @Test
    fun `a physical product without a weight is never priced as if it weighed nothing`(): Unit = runBlocking {
        val broken = fx.product("broken-${System.nanoTime()}", price = 1000, columns = mapOf("physical" to true))
        val z = zone()

        method("Std", listOf(z))

        val q = quote(broken to 1, address = de)

        assertEquals("NO_METHOD", q.message("SHIPPING_UNAVAILABLE")!!.reason)
        assertFalse(q.canCheckout)
        assertEquals(0L, q.shippingTotal)
    }

    @Test
    fun `a cart heavier than 2 000 000 000 g has no method`(): Unit = runBlocking {
        val a = shirt(weight = 1_000_000, stock = null)
        val b = shirt(weight = 1_000_000, stock = null)
        val c = shirt(weight = 1_000_000, stock = null)

        method("Std", listOf(zone()))

        assertEquals("NO_METHOD", quote(a to 999, b to 999, c to 999, address = de).message("SHIPPING_UNAVAILABLE")?.reason, "2 997 000 000 g")
    }

    @Test
    fun `without a quoter wired in the quote of a physical cart is unavailable, never free`(): Unit = runBlocking {
        h.shipper = null
        h.shippingResult = null

        val shirt = shirt()

        val q = quote(shirt to 1, address = de)

        assertEquals(0L, q.shippingTotal)
        assertFalse(q.canCheckout, "ShippingQuoter.NONE-like answers can never be paid")
        assertNotNull(q.messages.firstOrNull { it.code.startsWith("SHIPPING_") })
    }

    // ================================================================================================ address book (04 section 4)

    private fun book() = AddressBookService(w.db, w.clock, w.addresses, w.carts)

    private fun input(a: Map<String, Any?>, label: String? = null, isDefault: Boolean? = null) =
        AddressBookService.parse(json(a).also { o -> label?.let { o.put("label", it) }; isDefault?.let { o.put("isDefault", it) } })

    private suspend fun user(name: String) = fx.user(name).let { h.emails[it.id] = "$name@example.com"; it to QuoteCaller(it.id) }

    @Test
    fun `the first saved address becomes the default, the default is unique, the list puts it first`(): Unit = runBlocking {
        val (alice, _) = user("Alice")
        val book = book()

        val a = book.create(alice.id, input(de, "Home"))
        val b = book.create(alice.id, input(tr, "Office"))

        assertEquals(listOf(a, b), book.list(alice.id).map { it.getLong("id") })
        assertEquals(listOf(true, false), book.list(alice.id).map { it.getBoolean("isDefault") })

        val c = book.create(alice.id, input(de + ("city" to "Hamburg"), "Cabin", isDefault = true))
        val list = book.list(alice.id)

        assertEquals(c, list[0].getLong("id"))
        assertEquals(1, list.count { it.getBoolean("isDefault") })

        book.update(alice.id, b, input(tr, "Office", isDefault = true))
        assertEquals(b, book.list(alice.id)[0].getLong("id"))
        assertEquals(1, book.list(alice.id).count { it.getBoolean("isDefault") })

        // un-defaulting the default promotes the oldest other address
        book.update(alice.id, b, input(tr, "Office", isDefault = false))
        assertEquals(a, book.list(alice.id)[0].getLong("id"))

        // deleting the default promotes the oldest remaining one
        book.delete(alice.id, a)
        assertEquals(1, book.list(alice.id).count { it.getBoolean("isDefault") })
        assertEquals(b, book.list(alice.id)[0].getLong("id"))
    }

    @Test
    fun `an address is normalised and validated before it is saved`(): Unit = runBlocking {
        val (alice, _) = user("Alice")
        val book = book()

        val id = book.create(alice.id, input(tr + ("phone" to "0 532 123 45 67") + ("firstName" to "  Ayse\r\n ") + ("country" to "tr"), label = "  Home  "))
        val saved = book.list(alice.id).single()

        assertEquals(id, saved.getLong("id"))
        assertEquals("+905321234567", saved.getString("phone"))
        assertEquals("Ayse", saved.getString("firstName"))
        assertEquals("TR", saved.getString("country"))
        assertEquals("Home", saved.getString("label"))
        assertFalse(saved.containsKey("taxNumber"))

        val missing = assertThrows(com.panomc.plugins.market.error.ShippingAddressRequired::class.java) { runBlocking { book.create(alice.id, input(mapOf("country" to "DE"))) } }

        assertEquals(setOf("firstName", "lastName", "phone", "city", "line1", "postalCode"), JsonObject(missing.encode()).getJsonArray("fields").map { it.toString() }.toSet())
        assertThrows(com.panomc.plugins.market.error.ShippingAddressRequired::class.java) { runBlocking { book.create(alice.id, input(de + ("country" to "ZZ"))) } }
        assertThrows(RequestValueException::class.java) { runBlocking { book.create(alice.id, input(de, label = "x".repeat(65))) } }
        assertThrows(RequestValueException::class.java) { AddressBookService.parse(JsonObject().put("city", 5)) }
        assertThrows(RequestValueException::class.java) { AddressBookService.parse(JsonObject().put("isDefault", "yes")) }
        assertEquals(1, book.list(alice.id).size)
    }

    @Test
    fun `at most 10 saved addresses per user, also for parallel creates`(): Unit = runBlocking {
        val (alice, _) = user("Alice")
        val (bob, _) = user("Bob")
        val book = book()

        repeat(9) { book.create(alice.id, input(de + ("line1" to "Street $it"))) }

        val outcomes = Race.run(6) { book.create(alice.id, input(de + ("line1" to "Race $it"))) }

        assertEquals(1, outcomes.count { it.isSuccess }, "exactly one of six parallel creates fits")
        assertTrue(outcomes.filter { it.isFailure }.all { it.exceptionOrNull() is RequestValueException })
        assertEquals(10, book.list(alice.id).size)

        val over = assertThrows(RequestValueException::class.java) { runBlocking { book.create(alice.id, input(de)) } }

        assertEquals("addresses: LIMIT_REACHED", over.message)

        // another user's book is separate
        book.create(bob.id, input(de))
        assertEquals(1, book.list(bob.id).size)
        assertEquals(1, book.list(alice.id).count { it.getBoolean("isDefault") })

        // deleting one makes room
        book.delete(alice.id, book.list(alice.id).last().getLong("id"))
        book.create(alice.id, input(de))
        assertEquals(10, book.list(alice.id).size)
    }

    @Test
    fun `a foreign or missing address is 404, deleting an address forgets it in the cart`(): Unit = runBlocking {
        val (alice, _) = user("Alice")
        val (bob, _) = user("Bob")
        val book = book()

        val mine = book.create(alice.id, input(de))
        val bobs = book.create(bob.id, input(de))

        assertThrows(NotFound::class.java) { runBlocking { book.update(alice.id, bobs, input(de)) } }
        assertThrows(NotFound::class.java) { runBlocking { book.delete(alice.id, bobs) } }
        assertThrows(NotFound::class.java) { runBlocking { book.update(alice.id, 424_242, input(de)) } }
        assertThrows(NotFound::class.java) { runBlocking { book.delete(alice.id, 424_242) } }
        assertEquals(1, book.list(bob.id).size, "bob's address is untouched")

        val cartId = w.carts.ensure(alice.id, w.clock.now(), pool)

        w.carts.updateFields(cartId, mapOf("shippingAddressId" to mine), w.clock.now(), pool)
        book.delete(alice.id, mine)

        assertNull(w.carts.getById(cartId, pool)!!.shippingAddressId)
        assertTrue(book.list(alice.id).isEmpty())
    }

    @Test
    fun `an update replaces the address`(): Unit = runBlocking {
        val (alice, _) = user("Alice")
        val book = book()
        val id = book.create(alice.id, input(de, "Home"))

        book.update(alice.id, id, input(tr, "Moved"))

        val saved = book.list(alice.id).single()

        assertEquals("TR", saved.getString("country"))
        assertEquals("Moved", saved.getString("label"))
        assertEquals("Ankara", saved.getString("city"))
        assertFalse(saved.containsKey("postalCode"), "the old postal code is gone")
        assertTrue(saved.getBoolean("isDefault"), "an absent flag keeps it")
    }

    // ================================================================================================ the cache itself

    @Test
    fun `saving the settings of a carrier in the admin service clears its live rates (test 70)`(): Unit = runBlocking {
        val shirt = shirt()
        val z = zone()
        val admin = ShippingAdminService(
            db = w.db, clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers,
            throttles = w.throttles, lookup = lookup, cipher = com.panomc.plugins.market.provider.SecretCipher(ByteArray(32) { (it + 3).toByte() }),
            contexts = ShippingContexts { provider, settings, testMode -> TestContexts.shipping(provider.id, settings, vertx, testMode) },
            site = { TestContexts.defaultSite() }, onQuoteCacheInvalidate = { shipping.invalidate(it) }
        )

        admin.saveCarrier(carrier.id, JsonObject().put("senderCountry", "TR").put("senderLine1", "Depo 1").put("senderCity", "Istanbul"), null)
        admin.toggleCarrier(carrier.id, true)
        carrier.onQuote = { QuoteResult(listOf(rate("exp", 900))) }
        method("Live", listOf(z), source = ShippingRateSource.CARRIER, providerId = carrier.id)

        quote(shirt to 1, address = de)
        quote(shirt to 1, address = de)
        assertEquals(1, carrier.quotes.size)

        admin.saveCarrier(carrier.id, JsonObject().put("senderCountry", "TR").put("senderLine1", "Depo 2").put("senderCity", "Istanbul"), null)
        quote(shirt to 1, address = de)
        assertEquals(2, carrier.quotes.size, "the saved settings cleared the cache of the provider")
        assertEquals("Depo 2", carrier.quotes.last().from.line1)
    }

    @Test
    fun `the cache is a bounded LRU and invalidate removes one provider only`() {
        val cache = ShippingQuoteCache(w.clock, capacity = 3)
        val result = QuoteResult(listOf(rate("a", 100)))

        cache.put("k1", "p1", result)
        cache.put("k2", "p2", result)
        cache.put("k3", "p1", result)
        assertNotNull(cache.fresh("k1", 60))
        cache.put("k4", "p2", result)

        assertEquals(3, cache.size())
        assertNull(cache.fresh("k2", 60), "the least recently used entry went")

        cache.invalidate("p1")
        assertNull(cache.fresh("k1", 60))
        assertNull(cache.fresh("k3", 60))
        assertNotNull(cache.fresh("k4", 60))

        assertNull(cache.fresh("k4", 0), "0 seconds = never reused for quoting")
        w.clock.advance(61_000)
        assertNull(cache.fresh("k4", 60))
        assertNotNull(cache.honoured("k4", 60), "the honour window is at least 1800 s")
        w.clock.advance(31 * 60_000)
        assertNull(cache.honoured("k4", 60))

        cache.put("k5", "p3", QuoteResult(listOf(rate("x", 100, expiresAt = w.clock.now() + 1000))))
        w.clock.advance(2_000)
        assertNull(cache.honoured("k5", 600), "never past the expiry of the last rate")
    }

    @Test
    fun `the breaker opens on three failures within 60 s and a success resets it`() {
        val breaker = QuoteBreaker(w.clock)

        breaker.failure("p")
        breaker.failure("p")
        w.clock.advance(61_000)
        breaker.failure("p")
        assertTrue(breaker.allow("p"), "the first two failures are older than 60 s")

        breaker.failure("p")
        breaker.failure("p")
        assertFalse(breaker.allow("p"))
        w.clock.advance(59_000)
        assertFalse(breaker.allow("p"))
        w.clock.advance(2_000)
        assertTrue(breaker.allow("p"))

        breaker.failure("p")
        breaker.failure("p")
        breaker.success("p")
        breaker.failure("p")
        assertTrue(breaker.allow("p"), "a success resets the count")
        assertTrue(breaker.allow("other"))
    }

    @Suppress("unused")
    private fun keepImports(a: Address, s: SeqIds) = Unit
}
