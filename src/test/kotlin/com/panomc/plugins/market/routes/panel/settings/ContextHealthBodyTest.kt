package com.panomc.plugins.market.routes.panel.settings

import com.panomc.plugins.market.config.BillingInfoMode
import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.runtime.MarketRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure bodies of `GET /context` and `GET /health` (04 section 8). */
class ContextHealthBodyTest {
    private val input = MarketContextInput(
        currency = "TRY", currencySymbol = "₺", statsCurrency = "USD", statsCurrencySymbol = "$",
        creditsEnabled = true, creditName = "Gold", vatPercent = 20.0, showVatInPrice = true, testMode = false,
        mailEnabled = true, shippingEnabled = false, storeUrl = "https://x.test/store", runtimeState = "READY"
    )

    @Test
    fun `the context carries every documented key`() {
        val body = marketContextBody(input, includeProductMeta = true)

        val documented = listOf(
            "currency", "currencySymbol", "statsCurrency", "statsCurrencySymbol", "currencyMode", "additionalCurrencies",
            "currencies", "creditsEnabled", "creditName", "creditValue", "allowMixedCreditPayment", "vatPercent",
            "showVatInPrice", "testMode", "storeTimeZone", "revokeOnRefund", "revokeOnChargeback", "billingInfoMode",
            "invoiceEnabled", "mailEnabled", "shippingEnabled", "storeUrl", "runtimeState", "productMetaSchemas"
        )

        assertEquals(documented.toSet(), body.keys)
        assertEquals("₺", body["currencySymbol"])
        assertEquals("READY", body["runtimeState"])
        assertEquals("SINGLE", body["currencyMode"], "the documented default")
        assertEquals(false, body["shippingEnabled"])
    }

    @Test
    fun `the context reports the configured currency mode, credit rules and billing settings, not the defaults`() {
        val config = MarketConfig(
            currencyMode = CurrencyMode.MULTI, additionalCurrencies = listOf("USD", "GBP"), creditValue = 2.5, allowMixedCreditPayment = true,
            storeTimeZone = "Europe/Istanbul", revokeOnRefund = false, revokeOnChargeback = false, billingInfoMode = BillingInfoMode.REQUIRED,
            invoiceEnabled = false
        )

        val body = marketContextBody(marketContextInput(config, storeUrl = "https://x.test/store", mailEnabled = true, shippingEnabled = false, runtimeState = "READY"), includeProductMeta = false)

        assertEquals("MULTI", body["currencyMode"])
        assertEquals(listOf("USD", "GBP"), body["additionalCurrencies"])
        assertEquals(2.5, body["creditValue"])
        assertEquals(true, body["allowMixedCreditPayment"])
        assertEquals("Europe/Istanbul", body["storeTimeZone"])
        assertEquals(false, body["revokeOnRefund"])
        assertEquals(false, body["revokeOnChargeback"])
        assertEquals("REQUIRED", body["billingInfoMode"])
        assertEquals(false, body["invoiceEnabled"])
        assertEquals("TRY", body["currency"], "the base currency of the default configuration")
    }

    @Test
    fun `the context reports shippingEnabled from the input, true when a shipping method is active`() {
        val on = marketContextBody(marketContextInput(MarketConfig(), storeUrl = "", mailEnabled = false, shippingEnabled = true, runtimeState = "READY"), includeProductMeta = false)
        val off = marketContextBody(marketContextInput(MarketConfig(), storeUrl = "", mailEnabled = false, shippingEnabled = false, runtimeState = "READY"), includeProductMeta = false)

        assertEquals(true, on["shippingEnabled"])
        assertEquals(false, off["shippingEnabled"])
    }

    @Test
    fun `a default configuration gives the documented defaults`() {
        val body = marketContextBody(marketContextInput(MarketConfig(), storeUrl = "", mailEnabled = false, shippingEnabled = false, runtimeState = "READY"), includeProductMeta = false)

        assertEquals("SINGLE", body["currencyMode"])
        assertEquals(emptyList<String>(), body["additionalCurrencies"])
        assertEquals("OPTIONAL", body["billingInfoMode"])
    }

    @Test
    fun `product meta schemas are for catalogue holders only`() {
        assertFalse(marketContextBody(input, includeProductMeta = false).containsKey("productMetaSchemas"))
        assertTrue(marketContextBody(input, includeProductMeta = true).containsKey("productMetaSchemas"))
    }

    @Test
    fun `currencies list code, symbol and exponent`() {
        val currencies = marketCurrencies()

        val codes = currencies.map { it["code"] as String }

        assertEquals(com.panomc.plugins.market.core.money.Currencies.all().size, currencies.size)
        assertEquals(listOf("TRY", "USD", "EUR", "GBP"), codes.take(4))
        assertEquals(codes.drop(4).sorted(), codes.drop(4))
        assertEquals(0, currencies.first { it["code"] == "JPY" }["exponent"])
        assertEquals(2, currencies.first { it["code"] == "USD" }["exponent"])
        assertEquals("₺", currencies.first { it["code"] == "TRY" }["symbol"])
        assertTrue(currencies.all { !(it["symbol"] as String).isBlank() })
    }

    @Test
    fun `store url is the website url plus store, empty without a website url`() {
        assertEquals("https://x.test/store", marketStoreUrl("https://x.test"))
        assertEquals("https://x.test/store", marketStoreUrl(" https://x.test/// "))
        assertEquals("", marketStoreUrl(""))
        assertEquals("", marketStoreUrl("   "))
    }

    private fun health(state: MarketRuntime.State, problems: List<String> = emptyList(), unfixed: Map<String, Long> = emptyMap()) =
        MarketRuntime.Health(state, problems, unfixed, listOf("boom"), MarketRuntime.HostCapabilities(mail = false, notifications = true))

    @Test
    fun `a healthy market reports ok and the documented keys`() {
        val body = marketHealthBody(health(MarketRuntime.State.READY), "OK", emptyList())

        assertEquals(
            setOf(
                "runtimeState", "schema", "bootstrapErrors", "jobs", "queues", "providers", "servers", "credits", "mail",
                "mailEnabled", "ipTrust", "lockedSubjects", "rejectedEventsLastHour", "routes"
            ),
            body.keys
        )
        assertEquals("READY", body["runtimeState"])
        assertEquals(mapOf("ok" to true, "missing" to emptyList<String>(), "unfixed" to emptyList<String>()), body["schema"])
        assertEquals("HOST_TOO_OLD", body["mail"])
        assertEquals(false, body["mailEnabled"])
        assertEquals("OK", body["ipTrust"])
    }

    @Test
    fun `a degraded market lists what is missing and what is unfixed, zero counts are not unfixed`() {
        val body = marketHealthBody(
            health(MarketRuntime.State.DEGRADED, listOf("MISSING_TABLE pano_market_coupon"), mapOf("b" to 2L, "a" to 1L, "z" to 0L)),
            "UNCONFIGURED_PROXY",
            listOf(mapOf("method" to "GET", "path" to "/x", "auth" to "PUB"))
        )

        assertEquals("DEGRADED", body["runtimeState"])
        assertEquals(
            mapOf("ok" to false, "missing" to listOf("MISSING_TABLE pano_market_coupon"), "unfixed" to listOf("a", "b")),
            body["schema"]
        )
        assertEquals("UNCONFIGURED_PROXY", body["ipTrust"])
        assertEquals(1, (body["routes"] as List<*>).size)
        assertEquals(listOf("boom"), body["bootstrapErrors"])
    }
}
