package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.BillingInfoMode
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.catalog.CountryCodes
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketShippingRate
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.CurrencyType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `CheckoutConfigService` on a real MariaDB (MK-071): guest / gift / billing mode / credit and top-up limits, the legal
 * block (also the "required without a text" case), currencies, address field sets and shipping countries.
 */
class CheckoutConfigIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val fx by lazy { Fixtures(w) }

    @Volatile
    private var config = MarketConfig()
    private val legal by lazy { LegalTextService(w.db, w.clock, w.legalTexts, { "en-US" }) }
    private val service by lazy { CheckoutConfigService({ config }, legal, w.shippingZones) }

    private suspend fun get(locale: String? = null): JsonObject = service.get(locale, pool)

    private suspend fun rate(zoneId: Long, methodId: Long) {
        w.shippingRates.add(MarketShippingRate(methodId = methodId, zoneId = zoneId, createdAt = w.clock.now(), updatedAt = w.clock.now()), pool)
    }

    private fun strings(array: JsonArray) = array.map { it as String }

    @Test
    fun `defaults serve every key of the contract`(): Unit = runBlocking {
        config = MarketConfig()
        val body = get()

        assertEquals(
            setOf(
                "guestCheckout", "giftPurchase", "billingInfoMode", "creditsEnabled", "creditName", "mixedCredit", "legal", "currencies",
                "addressFields", "shippingCountries", "minimumOrderAmount", "creditTopUp"
            ),
            body.fieldNames()
        )
        assertTrue(body.getBoolean("guestCheckout"))
        assertTrue(body.getBoolean("giftPurchase"))
        assertEquals("OPTIONAL", body.getString("billingInfoMode"))
        assertNull(body.getValue("legal"))
        assertEquals(0.0, body.getDouble("minimumOrderAmount"))
        assertEquals(0, body.getJsonArray("shippingCountries").size())
    }

    @Test
    fun `guest gift billing mode and minimum order follow the settings`(): Unit = runBlocking {
        config = MarketConfig(allowGuestCheckout = false, allowGiftPurchase = false, billingInfoMode = BillingInfoMode.REQUIRED, minimumOrderAmount = 12.5)
        val body = get()

        assertFalse(body.getBoolean("guestCheckout"))
        assertFalse(body.getBoolean("giftPurchase"))
        assertEquals("REQUIRED", body.getString("billingInfoMode"))
        assertEquals(12.5, body.getDouble("minimumOrderAmount"))
    }

    @Test
    fun `credits mixed payment and top up limits`(): Unit = runBlocking {
        config = MarketConfig(
            currency = CurrencyType.EUR, creditsEnabled = true, creditName = "Gems", allowMixedCreditPayment = true,
            creditTopUpEnabled = true, creditTopUpFreeAmount = true, creditTopUpMin = 5.0, creditTopUpMax = 250.0, creditValue = 0.1
        )
        val body = get()
        val top = body.getJsonObject("creditTopUp")

        assertTrue(body.getBoolean("creditsEnabled"))
        assertEquals("Gems", body.getString("creditName"))
        assertTrue(body.getBoolean("mixedCredit"))
        assertEquals(
            mapOf("enabled" to true, "freeAmount" to true, "min" to 5.0, "max" to 250.0, "creditValue" to 0.1, "currency" to "EUR"),
            top.map
        )
    }

    @Test
    fun `top up and mixed payment are off when credits are off or the switch is off`(): Unit = runBlocking {
        config = MarketConfig(creditsEnabled = false, allowMixedCreditPayment = true, creditTopUpEnabled = true, creditTopUpFreeAmount = true)
        var body = get()
        assertFalse(body.getBoolean("mixedCredit"))
        assertFalse(body.getJsonObject("creditTopUp").getBoolean("enabled"))
        assertFalse(body.getJsonObject("creditTopUp").getBoolean("freeAmount"))

        config = MarketConfig(creditsEnabled = true, creditTopUpEnabled = true, creditTopUpFreeAmount = false)
        body = get()
        assertTrue(body.getJsonObject("creditTopUp").getBoolean("enabled"))
        assertFalse(body.getJsonObject("creditTopUp").getBoolean("freeAmount"))

        config = MarketConfig(creditsEnabled = true, creditTopUpEnabled = false)
        assertFalse(get().getJsonObject("creditTopUp").getBoolean("enabled"))
    }

    @Test
    fun `legal block carries the active text of the locale chain and the required flag`(): Unit = runBlocking {
        config = MarketConfig(legalTextRequired = true)
        legal.publish("en-US", "Terms", "<p>english</p><script>x()</script>", null)
        val tr = legal.publish("tr", "Şartlar", "<p>türkçe</p>", null)

        val forTr = get("tr").getJsonObject("legal")
        assertEquals(
            mapOf("required" to true, "id" to tr.id, "version" to tr.version, "title" to "Şartlar", "content" to "<p>türkçe</p>"),
            forTr.map
        )

        val fallback = get("de").getJsonObject("legal")
        assertEquals("Terms", fallback.getString("title"))
        assertFalse(fallback.getString("content").contains("script"))

        config = MarketConfig(legalTextRequired = false)
        assertFalse(get("tr").getJsonObject("legal").getBoolean("required"))
    }

    @Test
    fun `a newly published version replaces the legal block`(): Unit = runBlocking {
        config = MarketConfig(legalTextRequired = true)
        val one = legal.publish("en-US", "Terms", "<p>1</p>", null)
        assertEquals(one.id, get("en-US").getJsonObject("legal").getLong("id"))

        val two = legal.publish("en-US", "Terms", "<p>2</p>", null)
        val block = get("en-US").getJsonObject("legal")

        assertEquals(two.id, block.getLong("id"))
        assertEquals(two.version, block.getInteger("version"))
    }

    @Test
    fun `legal required without any active text is not served so sales never stop`(): Unit = runBlocking {
        config = MarketConfig(legalTextRequired = true)

        assertNull(get("en-US").getValue("legal"))
    }

    @Test
    fun `currencies are the store currency first then the additional ones without duplicates`(): Unit = runBlocking {
        config = MarketConfig(currency = CurrencyType.TRY, additionalCurrencies = listOf("usd", " EUR ", "TRY", "USD", ""))

        assertEquals(listOf("TRY", "USD", "EUR"), strings(get().getJsonArray("currencies")))

        config = MarketConfig(currency = CurrencyType.USD)
        assertEquals(listOf("USD"), strings(get().getJsonArray("currencies")))
    }

    @Test
    fun `address fields hold the default entry and the country sets`(): Unit = runBlocking {
        val fields = get().getJsonObject("addressFields")

        assertEquals(listOf("firstName", "lastName", "phone", "country", "city", "line1", "postalCode"), strings(fields.getJsonArray("*")))
        assertEquals(listOf("firstName", "lastName", "phone", "country", "city", "district", "line1"), strings(fields.getJsonArray("TR")))
        assertTrue(strings(fields.getJsonArray("US")).contains("state"))
        assertFalse(strings(fields.getJsonArray("AE")).contains("postalCode"))
    }

    @Test
    fun `shipping countries are the union of sellable zones sorted by code`(): Unit = runBlocking {
        val method = fx.shippingMethod()
        val tr = fx.shippingZone("Turkey", "[\"TR\"]")
        val eu = fx.shippingZone("EU", "[\"de\", \"FR\", \"TR\", \"XX\", 5]", position = 1)
        rate(tr.id, method.id)
        rate(eu.id, method.id)

        assertEquals(listOf("DE", "FR", "TR"), strings(get().getJsonArray("shippingCountries")))
    }

    @Test
    fun `zones without a sellable method or that are inactive are left out`(): Unit = runBlocking {
        val method = fx.shippingMethod()
        val deleted = fx.shippingMethod("Old")
        val inactiveMethod = fx.shippingMethod("Paused")
        val sellable = fx.shippingZone("Sellable", "[\"TR\"]")
        fx.shippingZone("NoRate", "[\"DE\"]")
        val inactiveZone = fx.shippingZone("InactiveZone", "[\"FR\"]")
        val deletedMethodZone = fx.shippingZone("DeletedMethod", "[\"IT\"]")
        val inactiveMethodZone = fx.shippingZone("InactiveMethod", "[\"ES\"]")
        val brokenJson = fx.shippingZone("Broken", "not json")

        rate(sellable.id, method.id)
        rate(inactiveZone.id, method.id)
        rate(deletedMethodZone.id, deleted.id)
        rate(inactiveMethodZone.id, inactiveMethod.id)
        rate(brokenJson.id, method.id)
        Fixtures.setColumns(pool, "market_shipping_zone", inactiveZone.id, mapOf("status" to "INACTIVE"))
        Fixtures.setColumns(pool, "market_shipping_method", deleted.id, mapOf("deletedAt" to 1L))
        Fixtures.setColumns(pool, "market_shipping_method", inactiveMethod.id, mapOf("status" to "INACTIVE"))

        assertEquals(listOf("TR"), strings(get().getJsonArray("shippingCountries")))
    }

    @Test
    fun `a star zone opens every country`(): Unit = runBlocking {
        val method = fx.shippingMethod()
        val zone = fx.shippingZone("World", "[\"*\"]")
        rate(zone.id, method.id)

        assertEquals(CountryCodes.ALL.sorted(), strings(get().getJsonArray("shippingCountries")))
    }
}
