package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.routes.panel.settings.payment.siteInfoOf
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PriceAuthority
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The checkout rules of a provider row (01 section 6.1): validation without a database. */
class PaymentMethodRulesTest {
    private val base = PaymentMethodRules.State.of(null)

    private fun parse(vararg pairs: Pair<String, Any?>, caps: PaymentCapabilities? = null, legacy: Boolean? = null, from: PaymentMethodRules.State = base) =
        PaymentMethodRules.parse(JsonObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }, from, caps, legacy)

    @Test
    fun `an empty form keeps every stored rule`() {
        val stored = PaymentMethodRules.State(3, "Card", "Visa", PaymentFeeMode.BUYER, 290, 30, 100, 90000, listOf("EUR"), true)
        val r = parse(from = stored)

        assertTrue(r.errors.isEmpty())
        assertEquals(3, r.state.position)
        assertEquals("Card", r.state.customLabel)
        assertEquals(290L, r.state.feePercent)
        assertEquals(30L, r.state.feeFixed)
        assertEquals(100L, r.state.minAmount)
        assertEquals(90000L, r.state.maxAmount)
        assertEquals(listOf("EUR"), r.state.currencies)
        assertTrue(r.state.testMode)
    }

    @Test
    fun `a full valid form is stored as x100 and basis points`() {
        val r = parse(
            "position" to 2, "customLabel" to "  Pay by card ", "customDescription" to "Visa, Mastercard", "feeMode" to "BUYER",
            "feePercent" to 2.9, "feeFixed" to "0.30", "minAmount" to 5, "maxAmount" to "1000.5", "currencies" to JsonArray().add("eur").add("USD"), "testMode" to true
        )

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(2, r.state.position)
        assertEquals("Pay by card", r.state.customLabel)
        assertEquals("Visa, Mastercard", r.state.customDescription)
        assertEquals(PaymentFeeMode.BUYER, r.state.feeMode)
        assertEquals(290L, r.state.feePercent)
        assertEquals(30L, r.state.feeFixed)
        assertEquals(500L, r.state.minAmount)
        assertEquals(100050L, r.state.maxAmount)
        assertEquals(listOf("EUR", "USD"), r.state.currencies)
        assertTrue(r.state.testMode)
    }

    @Test
    fun `null clears label description window and currencies`() {
        val stored = PaymentMethodRules.State(0, "x", "y", PaymentFeeMode.NONE, 0, 0, 100, 200, listOf("EUR"), false)
        val r = parse("customLabel" to null, "customDescription" to null, "minAmount" to null, "maxAmount" to null, "currencies" to null, from = stored)

        assertTrue(r.errors.isEmpty())
        assertNull(r.state.customLabel)
        assertNull(r.state.customDescription)
        assertNull(r.state.minAmount)
        assertNull(r.state.maxAmount)
        assertNull(r.state.currencies)
    }

    @Test
    fun `blank text is no label`() {
        assertNull(parse("customLabel" to "   ").state.customLabel)
    }

    @Test
    fun `label and description length limits`() {
        assertTrue(parse("customLabel" to "a".repeat(255), "customDescription" to "b".repeat(512)).errors.isEmpty())
        assertEquals("TOO_LONG", parse("customLabel" to "a".repeat(256)).errors["customLabel"])
        assertEquals("TOO_LONG", parse("customDescription" to "b".repeat(513)).errors["customDescription"])
        assertEquals("INVALID", parse("customLabel" to 5).errors["customLabel"])
    }

    @Test
    fun `fee percent is 0 to 100 with at most two decimals`() {
        assertTrue(parse("feeMode" to "BUYER", "feePercent" to 100).errors.isEmpty())
        assertTrue(parse("feeMode" to "BUYER", "feePercent" to "0.01").errors.isEmpty())
        assertEquals("OUT_OF_RANGE", parse("feePercent" to 100.01).errors["feePercent"])
        assertEquals("OUT_OF_RANGE", parse("feePercent" to -1).errors["feePercent"])
        assertEquals("TOO_MANY_DECIMALS", parse("feePercent" to 2.999).errors["feePercent"])
        assertEquals("INVALID", parse("feePercent" to "abc").errors["feePercent"])
        assertEquals("INVALID", parse("feePercent" to true).errors["feePercent"])
        assertEquals(1L, parse("feePercent" to "0.01").state.feePercent)
    }

    @Test
    fun `fixed fee and window amounts are not negative and have at most two decimals`() {
        assertEquals("OUT_OF_RANGE", parse("feeFixed" to -0.01).errors["feeFixed"])
        assertEquals("TOO_MANY_DECIMALS", parse("feeFixed" to "0.001").errors["feeFixed"])
        assertEquals("OUT_OF_RANGE", parse("minAmount" to -1).errors["minAmount"])
        assertEquals("OUT_OF_RANGE", parse("maxAmount" to 1_000_000_001L).errors["maxAmount"])
        assertEquals("INVALID", parse("maxAmount" to "x").errors["maxAmount"])
        assertEquals(0L, parse("minAmount" to 0).state.minAmount)
    }

    @Test
    fun `no float rounding drift in a decimal amount`() {
        assertEquals(1015L, parse("feeFixed" to 10.15).state.feeFixed)
        assertEquals(29L, parse("feeFixed" to 0.29).state.feeFixed)
        assertEquals(1999L, parse("feeFixed" to "19.99").state.feeFixed)
    }

    @Test
    fun `the maximum must not be below the minimum`() {
        assertEquals("BELOW_MIN", parse("minAmount" to 10, "maxAmount" to 9.99).errors["maxAmount"])
        assertTrue(parse("minAmount" to 10, "maxAmount" to 10).errors.isEmpty())
        // the stored minimum counts when only the maximum arrives
        assertEquals("BELOW_MIN", parse("maxAmount" to 1, from = PaymentMethodRules.State(0, null, null, PaymentFeeMode.NONE, 0, 0, 500, null, null, false)).errors["maxAmount"])
    }

    @Test
    fun `a buyer fee needs a percent or a fixed part`() {
        assertEquals("FEE_REQUIRED", parse("feeMode" to "BUYER").errors["feePercent"])
        assertTrue(parse("feeMode" to "BUYER", "feeFixed" to 0.1).errors.isEmpty())
        assertTrue(parse("feeMode" to "NONE", "feePercent" to 0).errors.isEmpty())
    }

    @Test
    fun `fee mode and position must be valid`() {
        assertEquals("INVALID", parse("feeMode" to "SELLER").errors["feeMode"])
        assertEquals("INVALID", parse("position" to -1).errors["position"])
        assertEquals("INVALID", parse("position" to 1_000_001).errors["position"])
        assertEquals("INVALID", parse("position" to 1.5).errors["position"])
        assertEquals(1_000_000, parse("position" to 1_000_000).state.position)
    }

    @Test
    fun `currencies are known unique and not empty`() {
        assertEquals("EMPTY", parse("currencies" to JsonArray()).errors["currencies"])
        assertEquals("UNKNOWN_CURRENCY", parse("currencies" to JsonArray().add("XXX1")).errors["currencies"])
        assertEquals("DUPLICATE", parse("currencies" to JsonArray().add("EUR").add("eur")).errors["currencies"])
        assertEquals("INVALID", parse("currencies" to JsonArray().add(1)).errors["currencies"])
        assertEquals("INVALID", parse("currencies" to "EUR").errors["currencies"])
    }

    @Test
    fun `currencies must stay inside what the provider supports`() {
        val caps = PaymentCapabilities().also { it.currencies = setOf("EUR", "USD") }

        assertTrue(parse("currencies" to JsonArray().add("EUR"), caps = caps).errors.isEmpty())
        assertEquals("UNSUPPORTED_CURRENCY", parse("currencies" to JsonArray().add("EUR").add("TRY"), caps = caps).errors["currencies"])
    }

    @Test
    fun `an unknown key is refused`() {
        assertEquals("UNKNOWN_PROPERTY", parse("enabled" to true).errors["enabled"])
        assertEquals("UNKNOWN_PROPERTY", parse("feePercentage" to 1).errors["feePercentage"])
    }

    @Test
    fun `a legacy sandbox flag turns the test mode on unless the form says otherwise`() {
        assertTrue(parse(legacy = true).state.testMode)
        assertFalse(parse(legacy = null).state.testMode)
        assertFalse(parse(legacy = false).state.testMode)
        assertFalse(parse("testMode" to false, legacy = true).state.testMode)
    }

    @Test
    fun `test mode is refused for a provider without one`() {
        val none = PaymentCapabilities().also { it.testMode = TestModeSupport.NONE }

        assertEquals("UNSUPPORTED", parse("testMode" to true, caps = none).errors["testMode"])
        assertTrue(parse("testMode" to false, caps = none).errors.isEmpty())
        assertEquals("INVALID", parse("testMode" to "yes").errors["testMode"])
    }

    @Test
    fun `a gateway that sets the price takes no market fee or window`() {
        val tebex = PaymentCapabilities().also { it.priceAuthority = PriceAuthority.GATEWAY_CATALOG }
        val r = parse("feeMode" to "BUYER", "feePercent" to 1, "minAmount" to 1, "maxAmount" to 5, caps = tebex)

        assertEquals("EXTERNAL_PRICING", r.errors["feeMode"])
        assertEquals("EXTERNAL_PRICING", r.errors["minAmount"])
        assertEquals("EXTERNAL_PRICING", r.errors["maxAmount"])
        assertTrue(parse("customLabel" to "Tebex", caps = tebex).errors.isEmpty())
    }

    @Test
    fun `every error is reported at once`() {
        val r = parse("position" to -1, "feePercent" to 500, "minAmount" to "x", "currencies" to JsonArray())

        assertEquals(setOf("position", "feePercent", "minAmount", "currencies"), r.errors.keys)
    }

    @Test
    fun `sort ids are provider ids without duplicates`() {
        assertEquals(listOf("stripe", "bank-transfer"), PaymentMethodRules.parseProviderIds(JsonArray().add("stripe").add("bank-transfer")))
        assertEquals("REQUIRED", assertThrows(IllegalArgumentException::class.java) { PaymentMethodRules.parseProviderIds(null) }.message)
        assertEquals("REQUIRED", assertThrows(IllegalArgumentException::class.java) { PaymentMethodRules.parseProviderIds(JsonArray()) }.message)
        assertEquals("INVALID", assertThrows(IllegalArgumentException::class.java) { PaymentMethodRules.parseProviderIds(JsonArray().add(1)) }.message)
        assertEquals("INVALID", assertThrows(IllegalArgumentException::class.java) { PaymentMethodRules.parseProviderIds(JsonArray().add("A")) }.message)
        assertEquals("DUPLICATE", assertThrows(IllegalArgumentException::class.java) { PaymentMethodRules.parseProviderIds(JsonArray().add("ab").add("ab")) }.message)
        assertEquals("TOO_MANY", assertThrows(IllegalArgumentException::class.java) { PaymentMethodRules.parseProviderIds(JsonArray().add("ab").add("cd"), max = 1) }.message)
    }

    @Test
    fun `method states of 02 section 11`() {
        assertEquals("ACTIVE", PaymentMethodStates.stateOf(ProviderAvailability.AVAILABLE, enabled = true, missingRequired = false))
        assertEquals("DISABLED", PaymentMethodStates.stateOf(ProviderAvailability.AVAILABLE, enabled = false, missingRequired = false))
        assertEquals("NOT_CONFIGURED", PaymentMethodStates.stateOf(ProviderAvailability.AVAILABLE, enabled = true, missingRequired = true))
        assertEquals("INCOMPATIBLE", PaymentMethodStates.stateOf(ProviderAvailability.INCOMPATIBLE, enabled = true, missingRequired = false))
        assertEquals("UNAVAILABLE", PaymentMethodStates.stateOf(ProviderAvailability.MISSING, enabled = true, missingRequired = false))
        assertEquals("UNAVAILABLE", PaymentMethodStates.stateOf(ProviderAvailability.SHADOWED, enabled = false, missingRequired = false))
        assertEquals("UNAVAILABLE", PaymentMethodStates.stateOf(ProviderAvailability.INVALID, enabled = false, missingRequired = false))
    }

    @Test
    fun `site info marks local hosts as not reachable`() {
        assertTrue(siteInfoOf("https://shop.example.com/", "Shop").publiclyReachable)
        assertTrue(siteInfoOf("https://shop.example.com/", "Shop").https)
        assertEquals("https://shop.example.com", siteInfoOf("https://shop.example.com/", "Shop").baseUrl)
        assertFalse(siteInfoOf("http://localhost:3000", "x").publiclyReachable)
        assertFalse(siteInfoOf("https://192.168.1.4", "x").publiclyReachable)
        assertFalse(siteInfoOf("http://10.0.0.2", "x").publiclyReachable)
        assertFalse(siteInfoOf("http://172.20.1.1", "x").publiclyReachable)
        assertTrue(siteInfoOf("http://172.32.1.1", "x").publiclyReachable)
        assertFalse(siteInfoOf("", "x").publiclyReachable)
        assertFalse(siteInfoOf("http://shop.example.com", "x").https)
    }
}
