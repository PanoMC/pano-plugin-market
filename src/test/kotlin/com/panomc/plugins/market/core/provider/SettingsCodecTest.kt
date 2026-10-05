package com.panomc.plugins.market.core.provider

import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.settingsSchema
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 02 section 4 storage protocol and 11 section 8.1 / 8.2, run against a schema that uses every field kind. */
class SettingsCodecTest {
    private fun t(s: String) = LocalizedText.of(s)

    private val schema = settingsSchema {
        group("main", t("Main"))
        text("merchantId") { label = t("Merchant"); required = true; pattern = "^[A-Z]{2}\\d{4}$"; group = "main" }
        secret("apiKey") { label = t("API key"); required = true }
        secret("webhookSecret") { label = t("Webhook secret") }
        secretTextarea("privateKey") { label = t("Private key") }
        textarea("instructions") { label = t("Instructions") }
        number("timeout") { label = t("Timeout"); min = 1; max = 60; default = 15 }
        select("mode") { label = t("Mode"); option("LIVE", t("Live")); option("TEST", t("Test")); default = "LIVE" }
        switch("embedded") { label = t("Embedded"); default = false }
        url("baseUrl") { label = t("Base URL") }
        text("region") { label = t("Region"); required = true; visibleWhen("mode", "TEST") }
        webhookUrl("callback") { label = t("Callback") }
        notice("hint") { label = t("Hint") }
        hidden("accountCurrency") { label = t("Currency"); dependsOn("apiKey") }
        hiddenSecret("webhookId") { label = t("Webhook id"); dependsOn("baseUrl") }
    }

    private val cipher = SecretCipher(ByteArray(32) { (it + 1).toByte() })
    private val codec = SettingsCodec(schema, cipher)

    private fun form(vararg pairs: Pair<String, Any?>) = JsonObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    private val valid get() = form("merchantId" to "AB1234", "apiKey" to "sk_live_secret_value", "mode" to "LIVE")

    private fun save(stored: JsonObject?, form: JsonObject) = codec.applyForm(stored, form)

    private fun first(): JsonObject {
        val r = save(null, valid)
        assertTrue(r.ok, r.issues.toString())
        return r.settings
    }

    // ---- storage

    @Test
    fun `a first save stores valid values, applies defaults and encrypts secrets`() {
        val r = save(null, valid.put("webhookSecret", "whsec_123456"))
        assertTrue(r.ok, r.issues.toString())
        val s = r.settings
        assertEquals("AB1234", s.getString("merchantId"))
        assertEquals(15L, s.getLong("timeout"))
        assertEquals("LIVE", s.getString("mode"))
        assertEquals(false, s.getBoolean("embedded"))
        assertTrue(s.getString("apiKey").startsWith("v1:"))
        assertTrue(s.getString("webhookSecret").startsWith("v1:"))
        assertFalse(s.encode().contains("sk_live_secret_value"))
        assertEquals("sk_live_secret_value", cipher.decrypt(s.getString("apiKey")))
        assertFalse(s.containsKey("privateKey"), "an unset optional secret is not stored")
    }

    @Test
    fun `unknown keys are dropped`() {
        val r = save(null, valid.put("evil", "x").put("__proto__", "y").put("admin", true))
        assertTrue(r.ok)
        assertEquals(setOf("merchantId", "apiKey", "mode", "timeout", "embedded"), r.settings.fieldNames())
    }

    @Test
    fun `readonly and notice fields are never stored`() {
        val r = save(null, valid.put("callback", "http://evil").put("hint", "text"))
        assertTrue(r.ok)
        assertFalse(r.settings.containsKey("callback"))
        assertFalse(r.settings.containsKey("hint"))
    }

    @Test
    fun `hidden fields are never accepted from the panel form`() {
        val r = save(null, valid.put("accountCurrency", "USD").put("webhookId", "wh_1"))
        assertTrue(r.ok)
        assertFalse(r.settings.containsKey("accountCurrency"))
        assertFalse(r.settings.containsKey("webhookId"))
    }

    @Test
    fun `a stored hidden value survives a save that does not touch its dependencies`() {
        val patched = codec.applyPatch(first(), mapOf("accountCurrency" to "USD", "webhookId" to "wh_secret_1")).settings
        val r = save(patched, valid.put("apiKey", "********").put("timeout", 30))
        assertTrue(r.ok, r.issues.toString())
        assertEquals("USD", r.settings.getString("accountCurrency"))
        assertEquals("wh_secret_1", cipher.decrypt(r.settings.getString("webhookId")))
        assertTrue(r.clearedHidden.isEmpty())
    }

    @Test
    fun `a hidden value is cleared when a field it depends on changes`() {
        val patched = codec.applyPatch(first(), mapOf("accountCurrency" to "USD", "webhookId" to "wh_secret_1")).settings
        val r = save(patched, valid.put("apiKey", "sk_live_another_key").put("baseUrl", "https://gw.example.com"))
        assertTrue(r.ok, r.issues.toString())
        assertFalse(r.settings.containsKey("accountCurrency"), "depends on apiKey")
        assertFalse(r.settings.containsKey("webhookId"), "depends on baseUrl")
        assertEquals(setOf("accountCurrency", "webhookId"), r.clearedHidden)
    }

    // ---- secret write protocol

    @Test
    fun `the mask and a blank value keep the stored secret`() {
        val stored = first()
        val before = stored.getString("apiKey")
        for (keep in listOf("********", "", "   ", " ******** ")) {
            val r = save(stored, valid.put("apiKey", keep))
            assertTrue(r.ok, "'$keep' ${r.issues}")
            assertEquals("sk_live_secret_value", cipher.decrypt(r.settings.getString("apiKey")), "'$keep'")
            assertFalse("apiKey" in r.changedKeys)
        }
        assertTrue(before.startsWith("v1:"))
    }

    @Test
    fun `an absent secret key keeps the stored secret`() {
        val r = save(first(), form("merchantId" to "AB1234"))
        assertTrue(r.ok, r.issues.toString())
        assertEquals("sk_live_secret_value", cipher.decrypt(r.settings.getString("apiKey")))
    }

    @Test
    fun `a new value replaces the secret and re-encrypts with a new iv`() {
        val stored = first()
        val r = save(stored, valid.put("apiKey", "sk_live_new_value"))
        assertTrue(r.ok)
        assertEquals("sk_live_new_value", cipher.decrypt(r.settings.getString("apiKey")))
        assertTrue("apiKey" in r.changedKeys)
    }

    @Test
    fun `null clears an optional secret`() {
        val stored = save(null, valid.put("webhookSecret", "whsec_123456")).settings
        val r = save(stored, valid.put("apiKey", "********").put("webhookSecret", null as String?))
        assertTrue(r.ok, r.issues.toString())
        assertFalse(r.settings.containsKey("webhookSecret"))
        assertEquals("sk_live_secret_value", cipher.decrypt(r.settings.getString("apiKey")))
        assertTrue("webhookSecret" in r.changedKeys)
    }

    @Test
    fun `null on a required secret is refused`() {
        val r = save(first(), valid.put("apiKey", null as String?))
        assertFalse(r.ok)
        assertEquals(SettingsCodec.REQUIRED, r.issues.getValue("apiKey").code)
    }

    @Test
    fun `a secret that is not text is refused`() {
        val r = save(null, valid.put("apiKey", 12345))
        assertEquals(SettingsCodec.INVALID_TYPE, r.issues.getValue("apiKey").code)
    }

    @Test
    fun `an unreadable stored secret stays in place when it is kept, and can be replaced or cleared`() {
        val other = SecretCipher(ByteArray(32) { 99 })
        val stored = SettingsCodec(schema, other).applyForm(null, valid.put("webhookSecret", "whsec_old_value")).settings
        val keepWebhook = save(stored, valid.put("apiKey", "sk_live_re_entered").put("webhookSecret", "********"))
        assertTrue(keepWebhook.ok, keepWebhook.issues.toString())
        assertEquals(stored.getString("webhookSecret"), keepWebhook.settings.getString("webhookSecret"), "the ciphertext is kept: the key may come back from a backup")
        assertEquals("sk_live_re_entered", cipher.decrypt(keepWebhook.settings.getString("apiKey")))
        val cleared = save(stored, valid.put("apiKey", "sk_live_re_entered").put("webhookSecret", null as String?))
        assertFalse(cleared.settings.containsKey("webhookSecret"))
        val replaced = save(stored, valid.put("apiKey", "sk_live_re_entered").put("webhookSecret", "whsec_new_value"))
        assertEquals("whsec_new_value", cipher.decrypt(replaced.settings.getString("webhookSecret")))
    }

    // ---- masking and reading

    @Test
    fun `mask shows the sentinel for a set secret and an empty string for an unset one`() {
        val stored = save(null, valid.put("webhookSecret", "whsec_123456")).settings
        val masked = codec.mask(stored)
        assertEquals("********", masked.getString("apiKey"))
        assertEquals("********", masked.getString("webhookSecret"))
        assertEquals("", masked.getString("privateKey"))
        assertEquals("AB1234", masked.getString("merchantId"))
        assertFalse(masked.encode().contains("sk_live"))
        assertFalse(masked.containsKey("accountCurrency"), "hidden fields are not rendered")
        assertFalse(masked.containsKey("webhookId"))
        assertFalse(masked.containsKey("callback"))
        assertEquals(setOf("merchantId", "apiKey", "webhookSecret", "privateKey", "timeout", "mode", "embedded"), masked.fieldNames())
    }

    @Test
    fun `mask of nothing stored has an empty string for every secret`() {
        val masked = codec.mask(null)
        for (k in listOf("apiKey", "webhookSecret", "privateKey")) assertEquals("", masked.getString(k))
    }

    @Test
    fun `decrypt gives the provider plain values and the typed accessors work`() {
        val stored = save(null, valid.put("timeout", 30).put("embedded", true).put("baseUrl", "https://gw.example.com")).settings
        val s = codec.decrypt(stored)
        assertEquals("sk_live_secret_value", s.string("apiKey"))
        assertEquals("sk_live_secret_value", s.require("apiKey"))
        assertEquals(30L, s.long("timeout"))
        assertTrue(s.boolean("embedded"))
        assertEquals("https://gw.example.com", s.string("baseUrl"))
        assertNull(s.string("privateKey"))
        assertFalse(s.boolean("nothing"))
        assertEquals("sk_live_secret_value", s.asJson().getString("apiKey"))
        assertTrue(s.unreadable.isEmpty())
        val e = assertThrows(ProviderException::class.java) { s.require("privateKey") }
        assertEquals(ProviderErrorCode.CONFIGURATION, e.code)
    }

    @Test
    fun `decrypt accepts legacy plaintext secrets and drops unreadable ones into the unreadable set`() {
        val legacy = JsonObject().put("merchantId", "AB1234").put("apiKey", "legacy-plain-key")
        assertEquals("legacy-plain-key", codec.decrypt(legacy).string("apiKey"))

        val damaged = JsonObject().put("merchantId", "AB1234").put("apiKey", "v1:AAAA").put("webhookSecret", cipher.encrypt("fine_value"))
        val s = codec.decrypt(damaged)
        assertNull(s.string("apiKey"))
        assertEquals(setOf("apiKey"), s.unreadable)
        assertEquals("fine_value", s.string("webhookSecret"))
        assertEquals(setOf("apiKey"), codec.unreadableSecrets(damaged))
    }

    @Test
    fun `valuesOf returns the secret values for the redactor`() {
        val s = codec.decrypt(first())
        assertEquals(setOf("sk_live_secret_value"), s.valuesOf(schema.secretKeys))
    }

    @Test
    fun `reveal returns only the secret fields decrypted`() {
        val stored = save(null, valid.put("webhookSecret", "whsec_123456")).settings
        val r = codec.reveal(stored)
        assertEquals(setOf("apiKey", "webhookSecret", "privateKey"), r.fieldNames())
        assertEquals("sk_live_secret_value", r.getString("apiKey"))
        assertEquals("whsec_123456", r.getString("webhookSecret"))
        assertEquals("", r.getString("privateKey"))
    }

    @Test
    fun `legacy plaintext secrets are re-encrypted and nothing else changes`() {
        val legacy = JsonObject().put("merchantId", "AB1234").put("apiKey", "legacy-plain-key").put("webhookSecret", cipher.encrypt("already"))
        val fixed = codec.encryptLegacyPlaintext(legacy)
        assertNotNull(fixed)
        assertTrue(fixed!!.getString("apiKey").startsWith("v1:"))
        assertEquals("legacy-plain-key", cipher.decrypt(fixed.getString("apiKey")))
        assertEquals(legacy.getString("webhookSecret"), fixed.getString("webhookSecret"))
        assertEquals("AB1234", fixed.getString("merchantId"))
        assertEquals("legacy-plain-key", legacy.getString("apiKey"), "the input is not modified")
        assertNull(codec.encryptLegacyPlaintext(fixed))
        assertNull(codec.encryptLegacyPlaintext(null))
    }

    @Test
    fun `a save re-encrypts a legacy plaintext secret kept with the mask`() {
        val legacy = JsonObject().put("merchantId", "AB1234").put("apiKey", "legacy-plain-key")
        val r = save(legacy, form("merchantId" to "AB1234", "apiKey" to "********"))
        assertTrue(r.ok, r.issues.toString())
        assertTrue(r.settings.getString("apiKey").startsWith("v1:"))
        assertEquals("legacy-plain-key", cipher.decrypt(r.settings.getString("apiKey")))
    }

    // ---- validation

    @Test
    fun `required fields are reported, secret ones included`() {
        val r = save(null, form("mode" to "LIVE"))
        assertFalse(r.ok)
        assertEquals(setOf("merchantId", "apiKey"), r.issues.keys)
        assertEquals(SettingsCodec.REQUIRED, r.issues.getValue("merchantId").code)
        assertTrue(r.fieldErrors.getValue("merchantId").values.containsKey("tr"))
        assertTrue(r.fieldErrors.getValue("merchantId").values.containsKey("ru"))
        assertEquals(r.fieldErrors.keys, r.issues.keys)
    }

    @Test
    fun `a blank required text is refused and text is trimmed`() {
        assertEquals(SettingsCodec.REQUIRED, save(null, valid.put("merchantId", "   ")).issues.getValue("merchantId").code)
        assertEquals("AB1234", save(null, valid.put("merchantId", "  AB1234 ")).settings.getString("merchantId"))
    }

    @Test
    fun `a field that is only visible for another value is required only then`() {
        assertTrue(save(null, valid).ok, "mode LIVE: region is hidden and not required")
        val test = save(null, valid.put("mode", "TEST"))
        assertEquals(setOf("region"), test.issues.keys)
        assertTrue(save(null, valid.put("mode", "TEST").put("region", "eu")).ok)
    }

    @Test
    fun `pattern is enforced as a full match`() {
        for (bad in listOf("AB123", "ab1234", "AB12345", "xAB1234", "AB1234 5")) {
            val r = save(null, valid.put("merchantId", bad))
            assertEquals(SettingsCodec.PATTERN_MISMATCH, r.issues["merchantId"]?.code, bad)
        }
        assertTrue(save(null, valid.put("merchantId", "ZZ0001")).ok)
    }

    @Test
    fun `a value too long for the safe regex engine is a field error, not a hang`() {
        val r = save(null, valid.put("merchantId", "A".repeat(500)))
        assertEquals(SettingsCodec.FIELD_INVALID, r.issues.getValue("merchantId").code)
    }

    @Test
    fun `number bounds and types`() {
        assertTrue(save(null, valid.put("timeout", 1)).ok)
        assertTrue(save(null, valid.put("timeout", 60)).ok)
        assertTrue(save(null, valid.put("timeout", "45")).ok)
        assertEquals(45L, save(null, valid.put("timeout", "45")).settings.getLong("timeout"))
        assertEquals(SettingsCodec.OUT_OF_RANGE, save(null, valid.put("timeout", 0)).issues.getValue("timeout").code)
        assertEquals(SettingsCodec.OUT_OF_RANGE, save(null, valid.put("timeout", 61)).issues.getValue("timeout").code)
        assertEquals(SettingsCodec.OUT_OF_RANGE, save(null, valid.put("timeout", -5)).issues.getValue("timeout").code)
        assertEquals(SettingsCodec.INVALID_NUMBER, save(null, valid.put("timeout", "abc")).issues.getValue("timeout").code)
        assertEquals(SettingsCodec.INVALID_NUMBER, save(null, valid.put("timeout", 1.5)).issues.getValue("timeout").code)
        assertEquals(SettingsCodec.INVALID_NUMBER, save(null, valid.put("timeout", true)).issues.getValue("timeout").code)
        assertEquals(SettingsCodec.INVALID_NUMBER, save(null, valid.put("timeout", Double.NaN)).issues.getValue("timeout").code)
        assertEquals(30.0, save(null, valid.put("timeout", 30.0)).settings.getLong("timeout").toDouble())
    }

    @Test
    fun `select accepts only listed options`() {
        assertEquals(SettingsCodec.INVALID_OPTION, save(null, valid.put("mode", "PROD")).issues.getValue("mode").code)
        assertEquals(SettingsCodec.INVALID_TYPE, save(null, valid.put("mode", 1)).issues.getValue("mode").code)
        assertTrue(save(null, valid.put("mode", "TEST").put("region", "eu")).ok)
    }

    @Test
    fun `switch accepts booleans and the strings true and false`() {
        assertEquals(true, save(null, valid.put("embedded", true)).settings.getBoolean("embedded"))
        assertEquals(true, save(null, valid.put("embedded", "TRUE")).settings.getBoolean("embedded"))
        assertEquals(false, save(null, valid.put("embedded", "false")).settings.getBoolean("embedded"))
        assertEquals(SettingsCodec.INVALID_TYPE, save(null, valid.put("embedded", "yes")).issues.getValue("embedded").code)
        assertEquals(SettingsCodec.INVALID_TYPE, save(null, valid.put("embedded", 1)).issues.getValue("embedded").code)
    }

    @Test
    fun `url must be an absolute http or https address without credentials`() {
        for (bad in listOf("ftp://x.example.com", "javascript:alert(1)", "/relative", "https://", "not a url", "https://user:pw@gw.example.com", "data:text/html,x")) {
            assertEquals(SettingsCodec.INVALID_URL, save(null, valid.put("baseUrl", bad)).issues["baseUrl"]?.code, bad)
        }
        assertTrue(save(null, valid.put("baseUrl", "https://gw.example.com:8443/api")).ok)
        assertTrue(save(null, valid.put("baseUrl", "http://127.0.0.1:9000")).ok)
    }

    @Test
    fun `texts have a length limit and non text values are refused`() {
        assertEquals(SettingsCodec.TOO_LONG, save(null, valid.put("baseUrl", "https://a.example.com/" + "x".repeat(5000))).issues.getValue("baseUrl").code)
        assertTrue(save(null, valid.put("instructions", "x".repeat(30_000))).ok)
        assertEquals(SettingsCodec.TOO_LONG, save(null, valid.put("instructions", "x".repeat(40_000))).issues.getValue("instructions").code)
        assertEquals(SettingsCodec.INVALID_TYPE, save(null, valid.put("instructions", JsonObject())).issues.getValue("instructions").code)
        assertEquals(SettingsCodec.INVALID_TYPE, save(null, valid.put("merchantId", 5)).issues.getValue("merchantId").code)
    }

    @Test
    fun `every problem is reported at once and nothing is stored`() {
        val r = save(null, form("merchantId" to "bad", "timeout" to 99, "mode" to "X", "baseUrl" to "ftp://x"))
        assertFalse(r.ok)
        assertEquals(setOf("merchantId", "apiKey", "timeout", "mode", "baseUrl"), r.issues.keys)
        assertTrue(r.settings.isEmpty)
    }

    @Test
    fun `an optional text set to blank removes the value but an omitted default stays`() {
        val stored = save(null, valid.put("baseUrl", "https://gw.example.com").put("instructions", "hello")).settings
        val r = save(stored, valid.put("apiKey", "********").put("baseUrl", "").put("instructions", null as String?))
        assertTrue(r.ok, r.issues.toString())
        assertFalse(r.settings.containsKey("baseUrl"))
        assertFalse(r.settings.containsKey("instructions"))
        assertEquals(15L, r.settings.getLong("timeout"), "stored value kept")
    }

    @Test
    fun `changedKeys names the changed form fields`() {
        val stored = first()
        val r = save(stored, valid.put("apiKey", "********").put("timeout", 20).put("mode", "LIVE"))
        assertEquals(setOf("timeout"), r.changedKeys)
        val same = save(r.settings, valid.put("apiKey", "********").put("timeout", 20))
        assertTrue(same.changedKeys.isEmpty())
    }

    // ---- patches from the provider

    @Test
    fun `a patch writes hidden fields only, encrypts hidden secrets and null removes`() {
        val base = first()
        val r = codec.applyPatch(base, mapOf("accountCurrency" to "EUR", "webhookId" to "wh_42", "merchantId" to "ZZ9999", "nothere" to "x"))
        assertEquals(setOf("accountCurrency", "webhookId"), r.applied)
        assertEquals(setOf("merchantId", "nothere"), r.rejected)
        assertEquals("EUR", r.settings.getString("accountCurrency"))
        assertTrue(r.settings.getString("webhookId").startsWith("v1:"))
        assertEquals("wh_42", codec.decrypt(r.settings).string("webhookId"))
        assertEquals("AB1234", r.settings.getString("merchantId"))
        assertEquals("AB1234", base.getString("merchantId"))
        assertFalse(base.containsKey("accountCurrency"), "the input is not modified")
        val removed = codec.applyPatch(r.settings, mapOf("accountCurrency" to null))
        assertFalse(removed.settings.containsKey("accountCurrency"))
    }

    // ---- configured?

    @Test
    fun `missingRequired lists empty required fields, hidden-by-condition ones excluded, and unreadable secrets`() {
        assertEquals(setOf("merchantId", "apiKey"), codec.missingRequired(null))
        assertEquals(emptySet<String>(), codec.missingRequired(first()))
        val test = codec.applyPatch(first(), emptyMap()).settings.copy().put("mode", "TEST")
        assertEquals(setOf("region"), codec.missingRequired(test))
        val damaged = first().put("apiKey", "v1:AAAA")
        assertEquals(setOf("apiKey"), codec.missingRequired(damaged))
    }

    @Test
    fun `the schema of the codec is the one it was built with`() {
        assertEquals(schema, codec.schema)
    }
}
