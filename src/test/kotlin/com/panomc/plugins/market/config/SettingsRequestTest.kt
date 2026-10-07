package com.panomc.plugins.market.config

import com.google.gson.Gson
import com.panomc.plugins.market.error.InvalidSettings
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `POST /settings` (04 section 8): partial update, additionalProperties false, `INVALID_SETTINGS` with fieldErrors. */
class SettingsRequestTest {
    private fun current(): JsonObject = JsonObject(Gson().toJson(MarketConfig()))

    private fun fieldErrors(error: InvalidSettings): JsonObject =
        JsonObject(error.encode(emptyMap())).getJsonObject("fieldErrors")

    @Test
    fun `the schema lists exactly the non credit keys and refuses additional properties`() {
        val schema = SettingsRequest.schema().toJson()

        assertEquals(false, schema.getValue("additionalProperties"))

        val properties = schema.getJsonObject("properties").fieldNames()
        val general = MarketConfigKeys.writable(ConfigScope.GENERAL).map { it.name }.toSet()

        assertEquals(general, properties)
        assertFalse("version" in properties)
        assertFalse("creditValue" in properties, "credit keys go through /settings/credits")
        assertFalse("exchangeRateUpdatedAt" in properties, "system keys are not writable")
        assertTrue("storeEnabled" in properties && "mcVaultMode" in properties && "moduleSidebars" in properties)

        val credit = SettingsRequest.schema(ConfigScope.CREDIT).toJson().getJsonObject("properties").fieldNames()

        assertTrue(credit.containsAll(listOf("creditValue", "allowMixedCreditPayment", "creditTopUpEnabled", "creditTopUpFreeAmount", "creditTopUpMin", "creditTopUpMax", "creditsEnabled", "creditName", "cashbackPercent", "onlyAcceptCredits")))
        assertTrue(credit.intersect(general).isEmpty())
    }

    @Test
    fun `a valid subset is merged and every other value is kept`() {
        val before = current().put("storeName", "Old").put("vatPercent", 8.0)

        val merged = SettingsRequest.apply(
            JsonObject().put("storeEnabled", false).put("moduleSidebars", JsonArray().add("profile")).put("storeName", "New"),
            before
        )

        assertEquals(false, merged.getBoolean("storeEnabled"))
        assertEquals("New", merged.getString("storeName"))
        assertEquals(JsonArray().add("profile"), merged.getJsonArray("moduleSidebars"))
        assertEquals(8.0, merged.getDouble("vatPercent"))
        assertEquals(before.getInteger("version"), merged.getInteger("version"))
        assertEquals("Old", before.getString("storeName"), "the input is not mutated")
    }

    @Test
    fun `unknown keys and version are refused`() {
        val unknown = assertThrows(InvalidSettings::class.java) {
            SettingsRequest.apply(JsonObject().put("nope", 1).put("version", 9), current())
        }

        assertEquals(MarketConfigKeys.UNKNOWN_KEY, fieldErrors(unknown).getString("nope"))
        assertEquals(MarketConfigKeys.UNKNOWN_KEY, fieldErrors(unknown).getString("version"))
    }

    @Test
    fun `credit and system keys are unknown to the general endpoint`() {
        val error = assertThrows(InvalidSettings::class.java) {
            SettingsRequest.apply(JsonObject().put("creditValue", 2.0).put("exchangeRateUpdatedAt", 5), current())
        }

        assertEquals(setOf("creditValue", "exchangeRateUpdatedAt"), fieldErrors(error).fieldNames())
    }

    @Test
    fun `invalid settings carry a field error per bad field and nothing is applied`() {
        val error = assertThrows(InvalidSettings::class.java) {
            SettingsRequest.apply(
                JsonObject()
                    .put("vatPercent", 120)
                    .put("storeTimeZone", "Mars/Base")
                    .put("moduleSidebars", JsonArray().add("admin"))
                    .put("additionalCurrencies", JsonArray().add("XXXX"))
                    .put("invoiceSeries", "TEST")
                    .put("checkoutRateLimitPerMinute", 100001)
                    .put("storeName", "fine"),
                current()
            )
        }

        assertEquals(400, error.getStatusCode())
        assertEquals("INVALID_SETTINGS", error.getErrorCode())

        val errors = fieldErrors(error)

        assertEquals(
            setOf("vatPercent", "storeTimeZone", "moduleSidebars", "additionalCurrencies", "invoiceSeries", "checkoutRateLimitPerMinute"),
            errors.fieldNames()
        )
        assertEquals(MarketConfigKeys.OUT_OF_RANGE, errors.getString("vatPercent"))
        assertEquals(MarketConfigKeys.INVALID_VALUE, errors.getString("storeTimeZone"))
    }

    @Test
    fun `rate limits accept their documented ranges`() {
        for (v in listOf(0, 1, 100000)) {
            SettingsRequest.apply(JsonObject().put("checkoutRateLimitPerMinute", v), current())
        }

        for (v in listOf(1, 100000)) {
            SettingsRequest.apply(JsonObject().put("quoteRateLimitPerMinute", v), current())
        }

        assertThrows(InvalidSettings::class.java) { SettingsRequest.apply(JsonObject().put("quoteRateLimitPerMinute", 0), current()) }
        assertThrows(InvalidSettings::class.java) { SettingsRequest.apply(JsonObject().put("checkoutRateLimitPerMinute", -1), current()) }
    }

    @Test
    fun `the credit scope validates the credit rules`() {
        val merged = SettingsRequest.apply(
            JsonObject().put("creditValue", 0.5).put("creditTopUpEnabled", true).put("creditTopUpFreeAmount", true),
            current(), ConfigScope.CREDIT
        )

        assertEquals(0.5, merged.getDouble("creditValue"))

        val error = assertThrows(InvalidSettings::class.java) {
            SettingsRequest.apply(JsonObject().put("creditValue", 0.001), current(), ConfigScope.CREDIT)
        }

        assertEquals(MarketConfigKeys.OUT_OF_RANGE, fieldErrors(error).getString("creditValue"))
    }

    @Test
    fun `an empty body changes nothing`() {
        assertEquals(current(), SettingsRequest.apply(JsonObject(), current()))
    }

    @Test
    fun `store and stats currency accept a supported code and refuse others`() {
        val ok = SettingsRequest.apply(JsonObject().put("currency", "JPY").put("statsCurrency", "USD"), current())

        assertEquals("JPY", ok.getString("currency"))
        assertEquals("USD", ok.getString("statsCurrency"))

        for (bad in listOf("KWD", "XXX", "try", "")) {
            val error = assertThrows(InvalidSettings::class.java) {
                SettingsRequest.apply(JsonObject().put("currency", bad).put("statsCurrency", bad), current())
            }
            val errors = fieldErrors(error)

            assertEquals(MarketConfigKeys.INVALID_VALUE, errors.getString("currency"), bad)
            assertEquals(MarketConfigKeys.INVALID_VALUE, errors.getString("statsCurrency"), bad)
        }
    }
}
