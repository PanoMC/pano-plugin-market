package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.error.InvalidPassword
import com.panomc.plugins.market.error.InvalidProviderSettings
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.PaymentMethodNotConfigured
import com.panomc.plugins.market.error.ProviderUnavailable
import com.panomc.plugins.market.error.ShippingProviderError
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.SettingsValidation
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.ShippingService
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
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
import org.junit.jupiter.api.assertThrows
import com.panomc.plugins.market.support.ErrorBodies
import com.panomc.plugins.market.util.MarketPaths

/**
 * `ShippingAdminService` on a real MariaDB (MK-131): the seed, zone and method CRUD with the rate-set rules, sort
 * endpoints, and the carrier configuration as the twin of the payment methods (mask / reveal / toggle / actions / services).
 */
class ShippingAdminIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val vertx: Vertx = Vertx.vertx()
    private val cipher = SecretCipher(ByteArray(32) { (it + 3).toByte() })
    private var lookup = StaticProviderLookup()
    private val invalidated = ArrayList<String>()
    private var tokenSeq = 0

    private lateinit var service: ShippingAdminService

    @BeforeEach
    fun freshService() {
        lookup = StaticProviderLookup(shipping = listOf(ManualShippingProvider()))
        invalidated.clear()
        tokenSeq = 0
        service = ShippingAdminService(
            db = w.db, clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates,
            carriers = w.shippingCarriers, throttles = w.throttles, lookup = lookup, cipher = cipher,
            contexts = ShippingContexts { provider, settings, testMode -> TestContexts.shipping(provider.id, settings, vertx, testMode) },
            site = { SiteInfo("Shop", "https://shop.example", https = true, publiclyReachable = true, defaultLocale = "en-US") },
            onQuoteCacheInvalidate = { invalidated.add(it) },
            token = { ShippingAdminRules.newInstallToken() }
        )
    }

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    /** A configurable carrier: required `accountId` and `apiKey` (secret), quotes, services, balance and an action. */
    private class Carrier(override val id: String = "ups") : ShippingProvider {
        val order = ArrayList<String>()
        var caps = ShippingCapabilities().also { it.rateQuote = true; it.createShipment = true; it.prepaidBalance = true }
        var validation: () -> SettingsValidation = { SettingsValidation.ok() }
        var onSaved: () -> ActionResult = { ActionResult.none() }
        var failSaved: ProviderException? = null
        var services: suspend () -> List<ShippingService> = { listOf(ShippingService("EXPRESS", "Express")) }
        var balance: suspend () -> Money? = { Money(12_345, "EUR") }
        var action: (String) -> ActionResult = { ActionResult.Message(LocalizedText.of("ok"), true) }
        var failAction: Throwable? = null
        var previousSeen: String? = null

        override val descriptor = ProviderDescriptor(LocalizedText.of("UPS"), LocalizedText.of("A carrier"), "truck")

        override fun settingsSchema(): SettingsSchema = settingsSchema {
            text("accountId") { label = LocalizedText.of("Account"); required = true }
            secret("apiKey") { label = LocalizedText.of("API key"); required = true }
            text("senderCountry") { label = LocalizedText.of("Country") }
            action("test-connection") { label = LocalizedText.of("Test"); requiresSavedSettings = true }
            action("ping") { label = LocalizedText.of("Ping"); requiresSavedSettings = false }
        }

        override fun capabilities(settings: ProviderSettings): ShippingCapabilities = caps

        override suspend fun validateSettings(ctx: ShippingContext, settings: ProviderSettings): SettingsValidation {
            order.add("validate")
            return validation()
        }

        override suspend fun onSettingsSaved(ctx: ShippingContext, previous: ProviderSettings?): ActionResult {
            order.add("saved")
            previousSeen = previous?.string("accountId")
            failSaved?.let { throw it }
            return onSaved()
        }

        override suspend fun runAction(ctx: ShippingContext, actionId: String, input: JsonObject): ActionResult {
            failAction?.let { throw it }
            return action(actionId)
        }

        override suspend fun listServices(ctx: ShippingContext): List<ShippingService> = services()

        override suspend fun balance(ctx: ShippingContext): Money? = balance.invoke()
    }

    private fun carrier(id: String = "ups", block: Carrier.() -> Unit = {}): Carrier = Carrier(id).also { block(it); lookup.addShipping(it) }

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    private fun fieldErrors(e: Error): JsonObject = ErrorBodies.details(e).getJsonObject("fieldErrors")

    private fun countries(vararg c: String) = JsonArray(c.toList())

    private suspend fun zone(name: String = "TR", vararg c: String = arrayOf("TR")): Long = service.createZone(obj("name" to name, "countries" to countries(*c)))

    private fun rate(zoneId: Long, basis: String, from: Any? = null, to: Any? = null, price: Any? = 1, per: Any? = null) =
        obj("zoneId" to zoneId, "basis" to basis, "rangeFrom" to from, "rangeTo" to to, "price" to price, "perUnitPrice" to per)

    private suspend fun method(name: String = "Std", vararg rates: JsonObject, extra: JsonObject = JsonObject()): Long =
        service.createMethod(obj("name" to name, "rates" to JsonArray(rates.toList())).mergeIn(extra))

    private suspend fun method(id: Long): JsonObject = service.listMethods().first { it.getLong("id") == id }

    // ---- seed

    @Test
    fun `the first start seeds the manual carrier and the Everywhere zone`(): Unit = runBlocking {
        service.seed()

        val carrier = w.shippingCarriers.getByProviderId("manual", pool)!!
        assertTrue(carrier.enabled)
        assertTrue(Regex("^[0-9a-f]{40}$").matches(carrier.webhookToken), carrier.webhookToken)

        val zones = service.listZones()
        assertEquals(1, zones.size)
        assertEquals("Everywhere", zones[0].getString("name"))
        assertEquals(listOf("*"), zones[0].getJsonArray("countries").list)
        assertEquals(0, zones[0].getInteger("position"))
        assertEquals(0, zones[0].getInteger("rateCount"))
        assertEquals("ACTIVE", zones[0].getString("status"))
        assertTrue(service.listMethods().isEmpty(), "no methods are seeded")
    }

    @Test
    fun `the seed runs once even when called again or when the zone was deleted`(): Unit = runBlocking {
        service.seed()
        val token = w.shippingCarriers.getByProviderId("manual", pool)!!.webhookToken
        val zoneId = service.listZones()[0].getLong("id")

        service.seed()
        assertEquals(1, service.listZones().size)
        assertEquals(token, w.shippingCarriers.getByProviderId("manual", pool)!!.webhookToken)

        service.deleteZone(zoneId)
        service.seed()
        service.listZones()
        assertTrue(service.listZones().isEmpty(), "a deleted zone does not come back")
        assertEquals(1, w.shippingCarriers.getAll(pool).size)
    }

    @Test
    fun `a store that already has a zone keeps it and still gets the manual carrier`(): Unit = runBlocking {
        val id = zone("Mine", "DE")

        service.seed()

        assertEquals(listOf(id), service.listZones().map { it.getLong("id") })
        assertNotNull(w.shippingCarriers.getByProviderId("manual", pool))
    }

    // ---- zones

    @Test
    fun `zones are created with the next position and listed with their shadow`(): Unit = runBlocking {
        service.seed()
        val tr = zone("TR", "TR")
        val de = zone("DE", "DE")

        val zones = service.listZones()

        assertEquals(listOf("Everywhere", "TR", "DE"), zones.map { it.getString("name") })
        assertEquals(listOf(0, 1, 2), zones.map { it.getInteger("position") })
        assertNull(zones[0].getValue("shadowedBy"))
        assertEquals(zones[0].getLong("id"), zones.first { it.getLong("id") == tr }.getLong("shadowedBy"), "TR below the catch-all can never match")
        assertEquals(zones[0].getLong("id"), zones.first { it.getLong("id") == de }.getLong("shadowedBy"))
    }

    @Test
    fun `a zone above the catch-all is not shadowed and a zone with patterns does not shadow`(): Unit = runBlocking {
        service.seed()
        val patterned = service.createZone(obj("name" to "Istanbul", "countries" to countries("TR"), "postalPatterns" to JsonArray().add("34*")))
        val all = service.listZones().map { it.getLong("id") }

        service.sortZones(listOf(patterned) + all.filter { it != patterned })
        val zones = service.listZones()

        assertEquals("Istanbul", zones[0].getString("name"))
        assertNull(zones[0].getValue("shadowedBy"))
        assertNull(zones[1].getValue("shadowedBy"), "the catch-all is the last, nothing before it covers everything")
    }

    @Test
    fun `an update changes only what the form carries`(): Unit = runBlocking {
        val id = service.createZone(obj("name" to "TR", "countries" to countries("TR"), "regions" to JsonArray().add(obj("country" to "TR", "states" to JsonArray().add("Izmir")))))

        service.updateZone(id, obj("name" to "Turkey", "status" to "INACTIVE"))

        val z = service.listZones().first { it.getLong("id") == id }
        assertEquals("Turkey", z.getString("name"))
        assertEquals("INACTIVE", z.getString("status"))
        assertEquals(listOf("TR"), z.getJsonArray("countries").list)
        assertEquals("Izmir", z.getJsonArray("regions").getJsonObject(0).getJsonArray("states").getString(0))

        service.updateZone(id, obj("regions" to null, "postalPatterns" to JsonArray().add("35*")))
        val after = service.listZones().first { it.getLong("id") == id }
        assertNull(after.getValue("regions"))
        assertEquals(listOf("35*"), after.getJsonArray("postalPatterns").list)
    }

    @Test
    fun `zone validation errors are all reported and nothing is stored`(): Unit = runBlocking {
        val e = assertThrows<InvalidSettings> { runBlocking { service.createZone(obj("name" to "", "countries" to countries("*", "TR"), "postalPatterns" to JsonArray().add("x y"))) } }

        assertEquals(setOf("name", "countries", "postalPatterns[0]"), fieldErrors(e).fieldNames())
        assertTrue(service.listZones().none { it.getString("name") == "" })

        val mixed = assertThrows<InvalidSettings> { runBlocking { service.createZone(obj("name" to "X", "countries" to countries("TR"), "regions" to JsonArray().add(obj("country" to "DE", "states" to JsonArray().add("Bayern"))))) } }
        assertEquals("INVALID_REGION_COUNTRY", fieldErrors(mixed).getString("regions[0].country"))
    }

    @Test
    fun `at most 100 zones`(): Unit = runBlocking {
        repeat(100) { zone("Z$it", "TR") }

        val e = assertThrows<InvalidSettings> { runBlocking { zone("one too many") } }

        assertEquals("LIMIT_REACHED", fieldErrors(e).getString("zones"))
    }

    @Test
    fun `deleting a zone removes its rate rows and nothing else`(): Unit = runBlocking {
        service.seed()
        val a = zone("A", "TR")
        val b = zone("B", "DE")
        val m = method("Std", rate(a, "FLAT", price = 5), rate(b, "FLAT", price = 7))

        service.deleteZone(a)

        assertEquals(listOf(b), method(m).getJsonArray("rates").map { (it as JsonObject).getLong("zoneId") })
        assertThrows<NotFound> { runBlocking { service.deleteZone(a) } }
        assertEquals(1, w.shippingRates.getByMethodId(m, pool).size)
    }

    @Test
    fun `zone sort needs exactly the set of existing ids`(): Unit = runBlocking {
        service.seed()
        val a = zone("A")
        val b = zone("B")
        val first = service.listZones()[0].getLong("id")

        service.sortZones(listOf(b, first, a))
        assertEquals(listOf(b, first, a), service.listZones().map { it.getLong("id") })
        assertEquals(listOf(0, 1, 2), service.listZones().map { it.getInteger("position") })

        assertThrows<BadRequest> { runBlocking { service.sortZones(listOf(b, a)) } }
        assertThrows<BadRequest> { runBlocking { service.sortZones(listOf(b, first, a, 9999)) } }
        assertThrows<BadRequest> { runBlocking { service.sortZones(listOf(b, b, a)) } }
        assertEquals(listOf(b, first, a), service.listZones().map { it.getLong("id") }, "a refused sort changes nothing")
    }

    @Test
    fun `an unknown zone is 404`(): Unit = runBlocking {
        assertThrows<NotFound> { runBlocking { service.updateZone(404, obj("name" to "x")) } }
        assertThrows<NotFound> { runBlocking { service.deleteZone(404) } }
    }

    // ---- methods

    @Test
    fun `a method is stored with its rates and read back in decimal money`(): Unit = runBlocking {
        service.seed()
        val z = zone("TR")
        val id = service.createMethod(
            obj(
                "name" to "Standard", "description" to "3-5 days", "freeShippingThreshold" to 250.5, "handlingFee" to 1.25, "vatPercent" to 8,
                "minDeliveryDays" to 3, "maxDeliveryDays" to 5, "maxWeightGrams" to 20_000, "carrierName" to "PTT",
                "trackingUrlTemplate" to "https://t.example/{tracking}",
                "rates" to JsonArray()
                    .add(rate(z, "WEIGHT", 0, 999, 5.5))
                    .add(rate(z, "WEIGHT", 1000, null, 8, per = 1.5))
                    .add(rate(z, "AMOUNT", 0, 49.99, 4))
            )
        )

        val m = method(id)

        assertEquals("Standard", m.getString("name"))
        assertEquals("manual", m.getString("providerId"))
        assertEquals("RULES", m.getString("rateSource"))
        assertEquals(250.5, m.getDouble("freeShippingThreshold"))
        assertEquals(1.25, m.getDouble("handlingFee"))
        assertEquals(8.0, m.getDouble("vatPercent"))
        assertEquals("ACTIVE", m.getString("status"))
        assertEquals("ACTIVE", m.getString("providerState"))
        assertEquals(0, m.getInteger("position"))

        val rates = m.getJsonArray("rates").map { it as JsonObject }
        assertEquals(3, rates.size)
        assertEquals(5.5, rates[0].getDouble("price"))
        assertEquals(1000, rates[1].getInteger("rangeFrom"))
        assertNull(rates[1].getValue("rangeTo"))
        assertEquals(1.5, rates[1].getDouble("perUnitPrice"))
        assertEquals(49.99, rates[2].getDouble("rangeTo"))

        // stored x100, positions per zone in array order
        val rows = w.shippingRates.getByMethodId(id, pool)
        assertEquals(listOf(550L, 800L, 400L), rows.map { it.price })
        assertEquals(listOf(0, 1, 2), rows.map { it.position })
        assertEquals(4_999L, rows[2].rangeTo)
        assertEquals(25_050L, w.shippingMethods.getById(id, pool)!!.freeShippingThreshold)
        assertEquals(800L, w.shippingMethods.getById(id, pool)!!.vatPercent)
        assertEquals(3, service.listZones().first { it.getLong("id") == z }.getInteger("rateCount"))
    }

    @Test
    fun `overlapping ranges are refused with RATE_OVERLAP and nothing is stored`(): Unit = runBlocking {
        val z = zone()

        val e = assertThrows<InvalidSettings> { runBlocking { method("Std", rate(z, "WEIGHT", 0, 1000), rate(z, "WEIGHT", 1000, 2000)) } }

        assertEquals("RATE_OVERLAP", fieldErrors(e).getString("rates[1]"))
        assertTrue(service.listMethods().isEmpty())
        assertTrue(w.shippingRates.getByMethodId(1, pool).isEmpty())
    }

    @Test
    fun `an open ended row that is not last is refused`(): Unit = runBlocking {
        val z = zone()

        val e = assertThrows<InvalidSettings> { runBlocking { method("Std", rate(z, "WEIGHT", 0, null), rate(z, "WEIGHT", 2000, 3000)) } }

        assertEquals("RATE_OVERLAP", fieldErrors(e).getString("rates[1]"))
        method("Ok", rate(z, "WEIGHT", 0, 1999), rate(z, "WEIGHT", 2000, null))
    }

    @Test
    fun `a FLAT row with a second row behind it is RATE_UNREACHABLE`(): Unit = runBlocking {
        val z = zone()

        val two = assertThrows<InvalidSettings> { runBlocking { method("Std", rate(z, "FLAT", price = 5), rate(z, "FLAT", price = 6)) } }
        val after = assertThrows<InvalidSettings> { runBlocking { method("Std", rate(z, "FLAT", price = 5), rate(z, "WEIGHT", 0, 100)) } }

        assertEquals("RATE_UNREACHABLE", fieldErrors(two).getString("rates[1]"))
        assertEquals("RATE_UNREACHABLE", fieldErrors(after).getString("rates[1]"))
        method("Ok", rate(z, "WEIGHT", 0, 100), rate(z, "FLAT", price = 9))
    }

    @Test
    fun `a rate row of an unknown zone is refused`(): Unit = runBlocking {
        val e = assertThrows<InvalidSettings> { runBlocking { method("Std", rate(777, "FLAT")) } }

        assertEquals("NOT_FOUND", fieldErrors(e).getString("rates[0].zoneId"))
    }

    @Test
    fun `row and field errors of a method come back together`(): Unit = runBlocking {
        val z = zone()
        val e = assertThrows<InvalidSettings> {
            runBlocking {
                service.createMethod(obj("name" to "", "trackingUrlTemplate" to "https://x.example/", "rates" to JsonArray().add(rate(z, "AMOUNT", 0, 10, 1, per = 1))))
            }
        }

        assertEquals(setOf("name", "trackingUrlTemplate", "rates[0].perUnitPrice"), fieldErrors(e).fieldNames())
    }

    @Test
    fun `an unknown provider is refused and the manual one always exists`(): Unit = runBlocking {
        val e = assertThrows<InvalidSettings> { runBlocking { service.createMethod(obj("name" to "X", "providerId" to "ghost")) } }

        assertEquals("UNKNOWN_PROVIDER", fieldErrors(e).getString("providerId"))
        method("Ok")
    }

    @Test
    fun `a carrier rate source needs a carrier that quotes`(): Unit = runBlocking {
        val z = zone()
        val refused = assertThrows<InvalidSettings> { runBlocking { service.createMethod(obj("name" to "X", "rateSource" to "CARRIER", "rates" to JsonArray().add(rate(z, "FLAT")))) } }
        assertEquals("RATE_QUOTE_NOT_SUPPORTED", fieldErrors(refused).getString("rateSource"))

        carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        val id = service.createMethod(obj("name" to "UPS", "providerId" to "ups", "serviceCode" to "EXPRESS", "rateSource" to "CARRIER_WITH_FALLBACK", "rates" to JsonArray().add(rate(z, "FLAT", price = 0))))

        assertEquals("EXPRESS", method(id).getString("serviceCode"))

        // the plugin goes away: the method stays as stored and reports the provider state
        lookup.remove("ups")
        assertEquals("UNAVAILABLE", method(id).getString("providerState"))
        service.updateMethod(id, obj("name" to "UPS renamed"))
        assertEquals("CARRIER_WITH_FALLBACK", method(id).getString("rateSource"))
    }

    @Test
    fun `PUT replaces the whole rate set in one transaction`(): Unit = runBlocking {
        val a = zone("A", "TR")
        val b = zone("B", "DE")
        val id = method("Std", rate(a, "FLAT", price = 5), rate(b, "FLAT", price = 7))

        service.updateMethod(id, obj("rates" to JsonArray().add(rate(a, "WEIGHT", 0, 100, 3)).add(rate(a, "FLAT", price = 4))))

        val rates = method(id).getJsonArray("rates").map { it as JsonObject }
        assertEquals(listOf("WEIGHT", "FLAT"), rates.map { it.getString("basis") })
        assertEquals(setOf(a), rates.map { it.getLong("zoneId") }.toSet())

        // an invalid set leaves the stored set untouched (atomic)
        assertThrows<InvalidSettings> { runBlocking { service.updateMethod(id, obj("name" to "Changed", "rates" to JsonArray().add(rate(a, "FLAT")).add(rate(a, "FLAT")))) } }
        assertEquals("Std", method(id).getString("name"))
        assertEquals(2, method(id).getJsonArray("rates").size())

        // no rates key keeps the set, an empty list clears it
        service.updateMethod(id, obj("name" to "Renamed"))
        assertEquals(2, method(id).getJsonArray("rates").size())
        service.updateMethod(id, obj("rates" to JsonArray()))
        assertEquals(0, method(id).getJsonArray("rates").size())
        assertTrue(w.shippingRates.getByMethodId(id, pool).isEmpty())
    }

    @Test
    fun `delete is a soft delete that hides the method`(): Unit = runBlocking {
        val z = zone()
        val id = method("Std", rate(z, "FLAT"))

        service.deleteMethod(id)

        assertTrue(service.listMethods().isEmpty())
        val row = w.shippingMethods.getById(id, pool)!!
        assertNotNull(row.deletedAt)
        assertEquals("INACTIVE", row.status)
        assertThrows<NotFound> { runBlocking { service.deleteMethod(id) } }
        assertThrows<NotFound> { runBlocking { service.updateMethod(id, obj("name" to "x")) } }
        assertEquals(0, service.listZones().first { it.getLong("id") == z }.getInteger("rateCount"), "a deleted method no longer counts")
    }

    @Test
    fun `at most 100 live methods and a deleted one frees a slot`(): Unit = runBlocking {
        val ids = (0 until 100).map { method("M$it") }

        val e = assertThrows<InvalidSettings> { runBlocking { method("over") } }
        assertEquals("LIMIT_REACHED", fieldErrors(e).getString("methods"))

        service.deleteMethod(ids[0])
        method("again")
    }

    @Test
    fun `method sort needs exactly the live ids`(): Unit = runBlocking {
        val a = method("A")
        val b = method("B")
        val c = method("C")
        service.deleteMethod(b)

        service.sortMethods(listOf(c, a))
        assertEquals(listOf(c, a), service.listMethods().map { it.getLong("id") })
        assertEquals(listOf(0, 1), service.listMethods().map { it.getInteger("position") })

        assertThrows<BadRequest> { runBlocking { service.sortMethods(listOf(c, a, b)) } }
        assertThrows<BadRequest> { runBlocking { service.sortMethods(listOf(a)) } }
        assertEquals(listOf(c, a), service.listMethods().map { it.getLong("id") })
    }

    @Test
    fun `new methods go to the end`(): Unit = runBlocking {
        val a = method("A")
        val b = method("B")

        assertEquals(listOf(a, b), service.listMethods().map { it.getLong("id") })
        assertEquals(listOf(0, 1), service.listMethods().map { it.getInteger("position") })
    }

    // ---- carriers: list, save, mask

    @Test
    fun `the list holds the manual carrier, registered carriers and rows without a provider`(): Unit = runBlocking {
        carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        w.shippingCarriers.add(com.panomc.plugins.market.db.model.MarketShippingCarrier(providerId = "gone", enabled = true, webhookToken = "t".repeat(40)), pool)
        val z = zone()
        method("U", rate(z, "FLAT"), extra = obj("providerId" to "ups"))

        val list = service.listCarriers()
        val byId = list.associateBy { it.getString("id") }

        assertEquals(setOf("manual", "ups", "gone"), byId.keys)
        assertEquals("ACTIVE", byId.getValue("manual").getString("state"))
        assertTrue(byId.getValue("manual").getJsonObject("config").getBoolean("enabled"))
        assertEquals("DISABLED", byId.getValue("ups").getString("state"))
        assertEquals("UNAVAILABLE", byId.getValue("gone").getString("state"))
        assertTrue(byId.getValue("gone").getJsonObject("config").getBoolean("readOnly"))
        assertEquals(1, byId.getValue("ups").getInteger("methodCount"))
        assertEquals(0, byId.getValue("manual").getInteger("methodCount"))
        assertNotNull(byId.getValue("ups").getJsonObject("schema"))
        assertNotNull(byId.getValue("ups").getJsonObject("capabilities"))
        assertEquals(true, byId.getValue("ups").getJsonObject("capabilities").getBoolean("rateQuote"))
        assertEquals(false, byId.getValue("manual").getJsonObject("capabilities").getBoolean("rateQuote"))
    }

    @Test
    fun `settings are encrypted at rest and masked in the list`(): Unit = runBlocking {
        carrier("ups")

        service.saveCarrier("ups", obj("accountId" to "ACC-1", "apiKey" to "sk_live_abcdef123456"), null)

        val stored = w.shippingCarriers.getByProviderId("ups", pool)!!
        assertTrue(JsonObject(stored.settings).getString("apiKey").startsWith("v1:"), "the secret is encrypted")
        assertFalse(stored.settings!!.contains("sk_live_abcdef123456"))
        val listed = service.listCarriers().first { it.getString("id") == "ups" }
        assertEquals("********", listed.getJsonObject("settings").getString("apiKey"))
        assertEquals("ACC-1", listed.getJsonObject("settings").getString("accountId"))
        assertFalse(listed.encode().contains("sk_live_abcdef123456"))
    }

    @Test
    fun `the first save creates the row with a 40 hex install token and the webhook url carries it`(): Unit = runBlocking {
        carrier("ups") { caps = ShippingCapabilities().also { it.trackingPush = true } }
        assertNull(w.shippingCarriers.getByProviderId("ups", pool))
        assertNull(service.listCarriers().first { it.getString("id") == "ups" }.getString("webhookUrl"), "no row, no url")

        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)

        val row = w.shippingCarriers.getByProviderId("ups", pool)!!
        assertTrue(Regex("^[0-9a-f]{40}$").matches(row.webhookToken))
        assertFalse(row.enabled)
        assertEquals("https://shop.example${MarketPaths.SITE_ROOT}/shipping/ups/webhook/${row.webhookToken}", service.listCarriers().first { it.getString("id") == "ups" }.getString("webhookUrl"))

        val token = row.webhookToken
        service.saveCarrier("ups", obj("accountId" to "B", "apiKey" to "********"), null)
        assertEquals(token, w.shippingCarriers.getByProviderId("ups", pool)!!.webhookToken, "the token never changes")
        assertEquals(1, w.shippingCarriers.getAll(pool).count { it.providerId == "ups" })
    }

    @Test
    fun `the mask or a blank value keeps the stored secret and null clears it`(): Unit = runBlocking {
        carrier("ups") { }
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "sk_live_abcdef123456"), null)

        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "********"), null)
        assertEquals("sk_live_abcdef123456", service.revealCarrier("ups", 1, { true }, { }).getString("apiKey"))
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to ""), null)
        assertEquals("sk_live_abcdef123456", service.revealCarrier("ups", 1, { true }, { }).getString("apiKey"))
        service.saveCarrier("ups", obj("accountId" to "A"), null)
        assertEquals("sk_live_abcdef123456", service.revealCarrier("ups", 1, { true }, { }).getString("apiKey"))
    }

    @Test
    fun `a missing required field is refused with the localized text and nothing is stored`(): Unit = runBlocking {
        val c = carrier("ups")

        val e = assertThrows<InvalidProviderSettings> { runBlocking { service.saveCarrier("ups", obj("accountId" to "A"), null) } }

        assertEquals(setOf("apiKey"), fieldErrors(e).fieldNames())
        assertNull(w.shippingCarriers.getByProviderId("ups", pool))
        assertTrue(c.order.isEmpty(), "schema validation runs before validateSettings")
    }

    @Test
    fun `validateSettings runs before the write and onSettingsSaved after it`(): Unit = runBlocking {
        val c = carrier("ups") { onSaved = { ActionResult.Message(LocalizedText.of("Saved at the carrier"), true) } }

        val first = service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        assertEquals(listOf("validate", "saved"), c.order)
        assertEquals("Saved at the carrier", first.message!!.resolve("en-US"))
        assertNull(c.previousSeen)

        service.saveCarrier("ups", obj("accountId" to "B", "apiKey" to "********"), null)
        assertEquals("A", c.previousSeen, "onSettingsSaved gets the previous settings")
    }

    @Test
    fun `a failing validateSettings stores nothing`(): Unit = runBlocking {
        val c = carrier("ups") { validation = { SettingsValidation.invalid(mapOf("accountId" to LocalizedText.of("Unknown account"))) } }

        val e = assertThrows<InvalidProviderSettings> { runBlocking { service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null) } }

        assertEquals(setOf("accountId"), fieldErrors(e).fieldNames())
        assertNull(w.shippingCarriers.getByProviderId("ups", pool))
        assertEquals(listOf("validate"), c.order)
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun `a failing onSettingsSaved keeps the settings and records the error`(): Unit = runBlocking {
        carrier("ups") { failSaved = ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down", "The carrier is down") }

        val saved = service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)

        assertEquals("The carrier is down", saved.message!!.resolve("en-US"))
        val row = w.shippingCarriers.getByProviderId("ups", pool)!!
        assertEquals("GATEWAY_UNREACHABLE", row.lastError)
        assertNotNull(row.lastErrorAt)
        assertEquals("A", JsonObject(row.settings).getString("accountId"))
    }

    @Test
    fun `saving settings clears the quote cache of that provider only`(): Unit = runBlocking {
        carrier("ups")
        carrier("dhl")

        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        assertEquals(listOf("ups"), invalidated)

        service.saveCarrier("dhl", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        assertEquals(listOf("ups", "dhl"), invalidated)
    }

    @Test
    fun `a good save clears the last error`(): Unit = runBlocking {
        carrier("ups") { failSaved = ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        assertNotNull(w.shippingCarriers.getByProviderId("ups", pool)!!.lastError)

        carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "********"), null)

        assertNull(w.shippingCarriers.getByProviderId("ups", pool)!!.lastError)
    }

    @Test
    fun `test mode follows the capability`(): Unit = runBlocking {
        carrier("ups") { caps = ShippingCapabilities().also { it.testMode = TestModeSupport.FLAG } }
        carrier("plain") { caps = ShippingCapabilities().also { it.testMode = TestModeSupport.NONE } }

        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), obj("testMode" to true))
        assertTrue(w.shippingCarriers.getByProviderId("ups", pool)!!.testMode)
        service.saveCarrier("ups", null, obj("testMode" to false))
        assertFalse(w.shippingCarriers.getByProviderId("ups", pool)!!.testMode)

        val e = assertThrows<InvalidProviderSettings> { runBlocking { service.saveCarrier("plain", obj("accountId" to "A", "apiKey" to "key-0123456789"), obj("testMode" to true)) } }
        assertEquals("NOT_SUPPORTED", fieldErrors(e).getString("config.testMode"))
        val unknown = assertThrows<InvalidProviderSettings> { runBlocking { service.saveCarrier("ups", null, obj("enabled" to true)) } }
        assertEquals("UNKNOWN_PROPERTY", fieldErrors(unknown).getString("config.enabled"))
    }

    @Test
    fun `an unknown carrier is 404 and an unavailable one is 409 PROVIDER_UNAVAILABLE`(): Unit = runBlocking {
        assertThrows<NotFound> { runBlocking { service.saveCarrier("nope", obj(), null) } }
        assertThrows<NotFound> { runBlocking { service.toggleCarrier("nope", true) } }
        assertThrows<NotFound> { runBlocking { service.carrierServices("nope") } }

        carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        lookup.remove("ups")

        assertThrows<ProviderUnavailable> { runBlocking { service.saveCarrier("ups", obj("accountId" to "B"), null) } }
        assertThrows<ProviderUnavailable> { runBlocking { service.toggleCarrier("ups", true) } }
        assertThrows<ProviderUnavailable> { runBlocking { service.revealCarrier("ups", 1, { true }, { }) } }
        assertThrows<ProviderUnavailable> { runBlocking { service.carrierServices("ups") } }
    }

    // ---- toggle

    @Test
    fun `manual cannot be disabled`(): Unit = runBlocking {
        service.seed()

        assertThrows<BadRequest> { runBlocking { service.toggleCarrier("manual", false) } }
        service.toggleCarrier("manual", true)

        assertTrue(w.shippingCarriers.getByProviderId("manual", pool)!!.enabled)
    }

    @Test
    fun `enabling needs the required settings and a passing validation`(): Unit = runBlocking {
        val c = carrier("ups")

        assertThrows<PaymentMethodNotConfigured> { runBlocking { service.toggleCarrier("ups", true) } }

        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        c.validation = { SettingsValidation.invalid(mapOf("accountId" to LocalizedText.of("Expired"))) }
        assertThrows<InvalidProviderSettings> { runBlocking { service.toggleCarrier("ups", true) } }
        assertFalse(w.shippingCarriers.getByProviderId("ups", pool)!!.enabled)

        c.validation = { SettingsValidation.ok() }
        service.toggleCarrier("ups", true)
        assertTrue(w.shippingCarriers.getByProviderId("ups", pool)!!.enabled)
        assertEquals("ACTIVE", service.listCarriers().first { it.getString("id") == "ups" }.getString("state"))

        service.toggleCarrier("ups", false)
        assertFalse(w.shippingCarriers.getByProviderId("ups", pool)!!.enabled)
        assertEquals("DISABLED", service.listCarriers().first { it.getString("id") == "ups" }.getString("state"))
    }

    @Test
    fun `toggle keeps the settings and the token`(): Unit = runBlocking {
        carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        val before = w.shippingCarriers.getByProviderId("ups", pool)!!

        service.toggleCarrier("ups", true)

        val after = w.shippingCarriers.getByProviderId("ups", pool)!!
        assertEquals(before.settings, after.settings)
        assertEquals(before.webhookToken, after.webhookToken)
    }

    // ---- reveal

    @Test
    fun `reveal returns only the secret fields decrypted`(): Unit = runBlocking {
        carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "sk_live_abcdef123456"), null)

        val secrets = service.revealCarrier("ups", 7, { true }, { })

        assertEquals(setOf("apiKey"), secrets.fieldNames())
        assertEquals("sk_live_abcdef123456", secrets.getString("apiKey"))
    }

    @Test
    fun `five wrong passwords lock the reveal for ten minutes`(): Unit = runBlocking {
        carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "sk_live_abcdef123456"), null)
        var failures = 0

        repeat(5) { assertThrows<InvalidPassword> { runBlocking { service.revealCarrier("ups", 7, { false }, { failures++ }) } } }
        assertEquals(5, failures)

        var asked = false
        val locked = assertThrows<TooManyRequests> { runBlocking { service.revealCarrier("ups", 7, { asked = true; true }, { failures++ }) } }
        assertEquals(600, ErrorBodies.details(locked).getLong("retryAfter"))
        assertFalse(asked, "the password is not asked while locked")
        assertEquals(5, failures)

        assertEquals("sk_live_abcdef123456", service.revealCarrier("ups", 8, { true }, { }).getString("apiKey"))
        w.clock.advance(10 * 60_000L + 1)
        assertEquals("sk_live_abcdef123456", service.revealCarrier("ups", 7, { true }, { }).getString("apiKey"))
    }

    // ---- actions and services

    @Test
    fun `an action runs on the saved settings and returns the provider text`(): Unit = runBlocking {
        carrier("ups") { action = { ActionResult.Message(LocalizedText.of("Connected"), true) } }
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)

        val outcome = service.runCarrierAction("ups", "test-connection", JsonObject())

        assertTrue(outcome.success)
        assertEquals("Connected", outcome.message!!.resolve("en-US"))
    }

    @Test
    fun `an action that needs saved settings is refused before the first save and an unknown one is 404`(): Unit = runBlocking {
        carrier("ups")

        assertThrows<InvalidState> { runBlocking { service.runCarrierAction("ups", "test-connection", JsonObject()) } }
        assertTrue(service.runCarrierAction("ups", "ping", JsonObject()).success)
        assertThrows<NotFound> { runBlocking { service.runCarrierAction("ups", "nope", JsonObject()) } }
    }

    @Test
    fun `an action failure is 502 SHIPPING_PROVIDER_ERROR and recorded`(): Unit = runBlocking {
        carrier("ups") { failAction = ProviderException(ProviderErrorCode.AUTHENTICATION, "bad key") }
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)

        val e = assertThrows<ShippingProviderError> { runBlocking { service.runCarrierAction("ups", "ping", JsonObject()) } }

        assertEquals("AUTHENTICATION", ErrorBodies.details(e).getString("code"))
        assertEquals("AUTHENTICATION", w.shippingCarriers.getByProviderId("ups", pool)!!.lastError)
    }

    @Test
    fun `services list the carrier services`(): Unit = runBlocking {
        carrier("ups") { services = { listOf(ShippingService("EXPRESS", "Express").also { it.carrierName = "UPS"; it.international = true }, ShippingService("STD", "Standard")) } }

        val services = service.carrierServices("ups")

        assertEquals(listOf("EXPRESS", "STD"), services.map { it.getString("code") })
        assertEquals("UPS", services[0].getString("carrierName"))
        assertTrue(services[0].getBoolean("international"))
    }

    @Test
    fun `a failing or hanging listServices is 502 SHIPPING_PROVIDER_ERROR`(): Unit = runBlocking {
        val c = carrier("ups") { services = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") } }
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)

        val e = assertThrows<ShippingProviderError> { runBlocking { service.carrierServices("ups") } }
        assertEquals("GATEWAY_UNREACHABLE", ErrorBodies.details(e).getString("code"))

        c.services = { throw IllegalStateException("boom") }
        val internal = assertThrows<ShippingProviderError> { runBlocking { service.carrierServices("ups") } }
        assertEquals("INTERNAL", ErrorBodies.details(internal).getString("code"))
    }

    @Test
    fun `balance is listed only for prepaid carriers and null when the call fails`(): Unit = runBlocking {
        val c = carrier("ups")
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)

        val listed = service.listCarriers().first { it.getString("id") == "ups" }.getJsonObject("balance")
        assertEquals("123.45", listed.getString("amount"))
        assertEquals("EUR", listed.getString("currency"))

        c.balance = { throw IllegalStateException("down") }
        assertNull(service.listCarriers().first { it.getString("id") == "ups" }.getValue("balance"))

        c.caps = ShippingCapabilities().also { it.prepaidBalance = false }
        c.balance = { Money(1, "EUR") }
        assertNull(service.listCarriers().first { it.getString("id") == "ups" }.getValue("balance"))
        assertNull(service.listCarriers().first { it.getString("id") == "manual" }.getValue("balance"))
    }

    @Test
    fun `a slow balance does not hold the list for more than three seconds`(): Unit = runBlocking {
        carrier("ups") { balance = { delay(60_000); Money(1, "EUR") } }
        service.saveCarrier("ups", obj("accountId" to "A", "apiKey" to "key-0123456789"), null)
        val started = System.currentTimeMillis()

        val listed = service.listCarriers().first { it.getString("id") == "ups" }

        assertNull(listed.getValue("balance"))
        assertTrue(System.currentTimeMillis() - started < 8_000, "the balance call is cut at 3 s")
    }

    @Test
    fun `the manual carrier is registered, configurable and costs nothing`(): Unit = runBlocking {
        service.seed()

        service.saveCarrier("manual", obj("senderCountry" to "TR", "senderLine1" to "Main St 1", "labelPaper" to "A4"), null)

        val manual = service.listCarriers().first { it.getString("id") == "manual" }
        assertEquals("ACTIVE", manual.getString("state"))
        assertEquals("A4", manual.getJsonObject("settings").getString("labelPaper"))
        assertEquals("TR", manual.getJsonObject("settings").getString("senderCountry"))
        assertTrue(w.shippingCarriers.getByProviderId("manual", pool)!!.enabled, "saving does not disable it")
        assertNull(manual.getString("webhookUrl"), "manual takes no webhooks")
    }
}
