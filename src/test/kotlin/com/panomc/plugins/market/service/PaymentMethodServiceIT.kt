package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.error.InvalidPassword
import com.panomc.plugins.market.error.InvalidProviderSettings
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.PaymentMethodNotConfigured
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.ProviderUnavailable
import com.panomc.plugins.market.error.PublicUrlRequired
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderAsset
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.SettingsValidation
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `PaymentMethodService` on a real MariaDB (MK-046): schema driven save with the mask / blank / null secret protocol,
 * re-encryption of legacy plaintext, legacy flag migration, rule validation, sort, toggle guards, throttled reveal, actions.
 */
class PaymentMethodServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val vertx: Vertx = Vertx.vertx()
    private val cipher = SecretCipher(ByteArray(32) { (it + 7).toByte() })
    private var lookup = StaticProviderLookup()
    private var site = defaultSite()

    private lateinit var service: PaymentMethodService

    private fun defaultSite() = SiteInfo("Shop", "https://shop.example", https = true, publiclyReachable = true, defaultLocale = "en-US")

    /** Every test starts with no registered provider, the default site and a fresh service (the database is reset by the base). */
    @BeforeEach
    fun freshService() {
        lookup = StaticProviderLookup()
        site = defaultSite()
        service = PaymentMethodService(
            db = w.db, clock = w.clock, methods = w.paymentMethods, throttles = w.throttles, lookup = lookup, cipher = cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode, site = site) },
            site = { site }
        )
    }

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    /** A configurable gateway: required `merchantId` and `apiKey` (secret), optional `webhookSecret` (secret) and `note`. */
    private class Gateway(override val id: String = "gate") : PaymentProvider {
        val validated = ArrayList<String>()
        val saved = ArrayList<String>()
        val order = ArrayList<String>()
        var caps = PaymentCapabilities()
        var validation: () -> SettingsValidation = { SettingsValidation.ok() }
        var onSaved: () -> ActionResult = { ActionResult.none() }
        var action: (String) -> ActionResult = { ActionResult.Message(LocalizedText.of("ok"), true) }
        var failSaved: ProviderException? = null
        var failAction: Throwable? = null
        var secretsSeenByValidate: Map<String, String?> = emptyMap()

        override val descriptor = ProviderDescriptor(LocalizedText.of("Gate"), LocalizedText.of("A gate"), "credit-card").also {
            it.logo = ProviderAsset("image/png", byteArrayOf(1, 2, 3))
            it.color = "#112233"
        }

        override fun settingsSchema(): SettingsSchema = settingsSchema {
            text("merchantId") { label = LocalizedText.of("Merchant"); required = true }
            secret("apiKey") { label = LocalizedText.of("API key"); required = true }
            secret("webhookSecret") { label = LocalizedText.of("Webhook secret") }
            text("note") { label = LocalizedText.of("Note") }
            webhookUrl("callback") { label = LocalizedText.of("Callback") }
            action("test-connection") { label = LocalizedText.of("Test"); requiresSavedSettings = true }
            action("ping") { label = LocalizedText.of("Ping"); requiresSavedSettings = false }
        }

        override fun capabilities(settings: ProviderSettings): PaymentCapabilities = caps

        override suspend fun validateSettings(ctx: PaymentContext, settings: ProviderSettings): SettingsValidation {
            order.add("validate")
            validated.add(settings.string("merchantId") ?: "")
            secretsSeenByValidate = mapOf("apiKey" to settings.string("apiKey"), "webhookSecret" to settings.string("webhookSecret"))
            return validation()
        }

        override suspend fun onSettingsSaved(ctx: PaymentContext, previous: ProviderSettings?): ActionResult {
            order.add("saved")
            saved.add(previous?.string("merchantId") ?: "<none>")
            failSaved?.let { throw it }
            return onSaved()
        }

        override suspend fun runAction(ctx: PaymentContext, actionId: String, input: JsonObject): ActionResult {
            failAction?.let { throw it }
            return action(actionId)
        }

        override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult = error("not used")

        override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult = InboundResult.ignored(HttpReply.empty())
    }

    private fun json(vararg pairs: Pair<String, Any?>) = JsonObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    private fun settings(vararg pairs: Pair<String, Any?>) = json("merchantId" to "M-1", "apiKey" to "sk_live_abcdef123456", *pairs)

    private fun gateway(id: String = "gate", block: Gateway.() -> Unit = {}): Gateway = Gateway(id).also { block(it); lookup.add(it) }

    private suspend fun row(id: String = "gate") = w.paymentMethods.getByMethodId(id, pool)

    private fun fieldErrors(e: Error): JsonObject = JsonObject(e.encode(emptyMap())).getJsonObject("fieldErrors")

    private suspend fun entry(id: String = "gate"): JsonObject = service.list().first { it.getString("id") == id }

    // ---- secrets: mask / blank keeps, null clears, encrypted at rest

    @Test
    fun `a saved secret is encrypted at rest and masked everywhere`(): Unit = runBlocking {
        gateway()
        service.save("gate", settings(), null)

        val stored = JsonObject(row()!!.settings)
        assertTrue(stored.getString("apiKey").startsWith("v1:"), "the secret is stored encrypted")
        assertFalse(row()!!.settings.contains("sk_live_abcdef123456"))
        assertEquals("M-1", stored.getString("merchantId"))

        val listed = entry().getJsonObject("settings")
        assertEquals("********", listed.getString("apiKey"))
        assertEquals("", listed.getString("webhookSecret"))
        assertEquals("M-1", listed.getString("merchantId"))
        assertFalse(entry().encode().contains("sk_live_abcdef123456"))
        assertEquals("********", service.settingsSummary().getJsonObject("gate").getJsonObject("settings").getString("apiKey"))
    }

    @Test
    fun `the mask or a blank value keeps a stored secret and null clears it`(): Unit = runBlocking {
        val g = gateway()
        service.save("gate", settings("webhookSecret" to "whsec_123456789"), null)

        service.save("gate", settings("apiKey" to "********", "webhookSecret" to ""), null)
        assertEquals("sk_live_abcdef123456", service.reveal("gate", 1, { true }, { }).getString("apiKey"), "mask keeps")
        assertEquals("whsec_123456789", service.reveal("gate", 1, { true }, { }).getString("webhookSecret"), "blank keeps")
        assertEquals("sk_live_abcdef123456", g.secretsSeenByValidate["apiKey"], "the provider reads the decrypted value")

        service.save("gate", json("merchantId" to "M-1", "apiKey" to "********"), null)
        assertEquals("whsec_123456789", service.reveal("gate", 1, { true }, { }).getString("webhookSecret"), "absent keeps")

        service.save("gate", json("merchantId" to "M-1", "apiKey" to "********", "webhookSecret" to null), null)
        assertFalse(JsonObject(row()!!.settings).containsKey("webhookSecret"), "null clears")
        assertEquals("", entry().getJsonObject("settings").getString("webhookSecret"))
        assertEquals("", service.reveal("gate", 1, { true }, { }).getString("webhookSecret"))
        assertEquals("sk_live_abcdef123456", service.reveal("gate", 1, { true }, { }).getString("apiKey"))

        val replaced = service.save("gate", settings("apiKey" to "sk_live_new_value_9"), null)
        assertNull(replaced.message)
        assertEquals("sk_live_new_value_9", g.secretsSeenByValidate["apiKey"])
    }

    @Test
    fun `a required field that is missing is refused and nothing is stored`(): Unit = runBlocking {
        val g = gateway()
        val e = assertThrows<InvalidProviderSettings> { runBlocking { service.save("gate", json("merchantId" to "M-1"), null) } }

        assertEquals(setOf("apiKey"), fieldErrors(e).fieldNames())
        assertNotNull(fieldErrors(e).getJsonObject("apiKey").getString("default"))
        assertNull(row())
        assertTrue(g.validated.isEmpty(), "schema validation runs before validateSettings")
    }

    @Test
    fun `reveal returns only the secret fields decrypted`(): Unit = runBlocking {
        gateway()
        service.save("gate", settings("webhookSecret" to "whsec_123456789"), null)

        val secrets = service.reveal("gate", 7, { true }, { })

        assertEquals(setOf("apiKey", "webhookSecret"), secrets.fieldNames())
        assertEquals("sk_live_abcdef123456", secrets.getString("apiKey"))
        assertEquals("whsec_123456789", secrets.getString("webhookSecret"))
    }

    // ---- start: plaintext re-encrypted

    @Test
    fun `legacy plaintext secrets are re-encrypted at start and keep working`(): Unit = runBlocking {
        val g = gateway()
        w.paymentMethods.upsertByMethodId("gate", true, json("merchantId" to "M-9", "apiKey" to "plain_api_key_value", "webhookSecret" to "plain_hook_value").encode(), pool)

        service.startup()

        val stored = JsonObject(row()!!.settings)
        assertTrue(stored.getString("apiKey").startsWith("v1:"))
        assertTrue(stored.getString("webhookSecret").startsWith("v1:"))
        assertEquals("M-9", stored.getString("merchantId"))
        assertTrue(row()!!.enabled, "enabled stays")
        assertEquals("plain_api_key_value", service.reveal("gate", 1, { true }, { }).getString("apiKey"))
        assertNull(row()!!.lastError)

        val before = row()!!.settings
        service.startup()
        assertEquals(before, row()!!.settings, "a second start changes nothing")
        assertTrue(g.validated.isEmpty())
    }

    @Test
    fun `a secret that cannot be decrypted is reported on the row and cleared when it can be read again`(): Unit = runBlocking {
        gateway()
        val foreign = SecretCipher(ByteArray(32) { 99 })
        w.paymentMethods.upsertByMethodId("gate", false, json("merchantId" to "M", "apiKey" to foreign.encrypt("sk_other_key_1234")).encode(), pool)

        service.startup()

        assertEquals("SECRET_UNREADABLE", row()!!.lastError)
        assertNotNull(row()!!.lastErrorAt)
        assertEquals("NOT_CONFIGURED", entry().getString("state"))
        assertEquals("SECRET_UNREADABLE", entry().getString("lastError"))

        service.save("gate", settings(), null)
        assertNull(row()!!.lastError, "a save clears the error")
        assertEquals("DISABLED", entry().getString("state"))
    }

    // ---- legacy flags

    @Test
    fun `the legacy sandbox flag becomes the test mode on the first save and is dropped`(): Unit = runBlocking {
        gateway()
        w.paymentMethods.upsertByMethodId("gate", false, json("merchantId" to "M-1", "apiKey" to "plain_api_key_value", "sandbox" to true).encode(), pool)

        service.save("gate", settings("apiKey" to "********"), null)

        val r = row()!!
        assertTrue(r.testMode)
        assertFalse(JsonObject(r.settings).containsKey("sandbox"))
        assertTrue(JsonObject(r.settings).getString("apiKey").startsWith("v1:"))
        assertEquals("plain_api_key_value", service.reveal("gate", 1, { true }, { }).getString("apiKey"))
        assertTrue(entry().getJsonObject("config").getBoolean("testMode"))
    }

    @Test
    fun `the legacy testMode string flag migrates and an explicit config wins`(): Unit = runBlocking {
        gateway()
        gateway("other")
        w.paymentMethods.upsertByMethodId("gate", false, json("merchantId" to "M-1", "apiKey" to "plain_api_key_value", "testMode" to "true").encode(), pool)
        w.paymentMethods.upsertByMethodId("other", false, json("merchantId" to "M-1", "apiKey" to "plain_api_key_value", "sandbox" to true).encode(), pool)

        service.save("gate", settings("apiKey" to "********"), null)
        service.save("other", settings("apiKey" to "********"), json("testMode" to false))

        assertTrue(row("gate")!!.testMode)
        assertFalse(row("other")!!.testMode, "config.testMode = false beats the legacy flag")
    }

    @Test
    fun `a legacy flag set to false changes nothing`(): Unit = runBlocking {
        gateway()
        w.paymentMethods.upsertByMethodId("gate", false, json("merchantId" to "M-1", "apiKey" to "plain_api_key_value", "sandbox" to false).encode(), pool)

        service.save("gate", settings("apiKey" to "********"), null)

        assertFalse(row()!!.testMode)
        assertFalse(JsonObject(row()!!.settings).containsKey("sandbox"))
    }

    @Test
    fun `the old single account keys of bank transfer fold into the account list on the first save`(): Unit = runBlocking {
        lookup.add(BankTransferProvider())
        w.paymentMethods.upsertByMethodId(
            "bank-transfer", true,
            json("bankName" to "Old Bank", "iban" to "TR330006100519786457841326", "accountHolder" to "Ahmet", "description" to "Pay in 3 days", "autoApprove" to true).encode(), pool
        )

        service.save("bank-transfer", json("instructions" to "Pay in 3 days"), null)

        val stored = JsonObject(row("bank-transfer")!!.settings)
        val accounts = JsonArray(stored.getString("accounts"))
        assertEquals(1, accounts.size())
        assertEquals("Old Bank", accounts.getJsonObject(0).getString("bank"))
        assertEquals("TR330006100519786457841326", accounts.getJsonObject(0).getString("iban"))
        assertFalse(stored.containsKey("iban"))
        assertFalse(stored.containsKey("autoApprove"))
        assertTrue(row("bank-transfer")!!.enabled)
    }

    // ---- checkout rules

    @Test
    fun `fee window position label and currencies are stored and listed as decimals`(): Unit = runBlocking {
        gateway()
        service.save(
            "gate", settings(),
            json(
                "position" to 4, "customLabel" to "Credit card", "customDescription" to "Visa", "feeMode" to "BUYER", "feePercent" to 2.9,
                "feeFixed" to 0.3, "minAmount" to 5, "maxAmount" to 500, "currencies" to JsonArray().add("EUR").add("USD"), "testMode" to true
            )
        )

        val r = row()!!
        assertEquals(4, r.position)
        assertEquals("Credit card", r.customLabel)
        assertEquals("Visa", r.customDescription)
        assertEquals(PaymentFeeMode.BUYER, r.feeMode)
        assertEquals(290L, r.feePercent)
        assertEquals(30L, r.feeFixed)
        assertEquals(500L, r.minAmount)
        assertEquals(50000L, r.maxAmount)
        assertEquals("[\"EUR\",\"USD\"]", r.currencies)
        assertTrue(r.testMode)
        assertNotNull(r.settingsUpdatedAt)

        val config = entry().getJsonObject("config")
        assertEquals(java.math.BigDecimal("2.90"), java.math.BigDecimal(config.getValue("feePercent").toString()))
        assertEquals(0.3, config.getDouble("feeFixed"), 1e-9)
        assertEquals(5.0, config.getDouble("minAmount"), 1e-9)
        assertEquals(500.0, config.getDouble("maxAmount"), 1e-9)
        assertEquals(JsonArray().add("EUR").add("USD"), config.getJsonArray("currencies"))
        assertEquals("Credit card", config.getString("customLabel"))
    }

    @Test
    fun `a rule that breaks is refused with its dotted path and nothing is stored`(): Unit = runBlocking {
        val g = gateway()
        val e = assertThrows<InvalidProviderSettings> {
            runBlocking {
                service.save("gate", settings(), json("feeMode" to "BUYER", "feePercent" to 101, "minAmount" to 10, "maxAmount" to 5, "customLabel" to "x".repeat(300), "position" to -3))
            }
        }

        val errors = fieldErrors(e)
        assertEquals("OUT_OF_RANGE", errors.getString("config.feePercent"))
        assertEquals("BELOW_MIN", errors.getString("config.maxAmount"))
        assertEquals("TOO_LONG", errors.getString("config.customLabel"))
        assertEquals("INVALID", errors.getString("config.position"))
        assertNull(row())
        assertTrue(g.validated.isEmpty())
    }

    @Test
    fun `a rule error and a settings error come back together`(): Unit = runBlocking {
        gateway()
        val e = assertThrows<InvalidProviderSettings> { runBlocking { service.save("gate", json("merchantId" to "M"), json("feeMode" to "BUYER")) } }

        assertEquals(setOf("apiKey", "config.feePercent"), fieldErrors(e).fieldNames())
    }

    @Test
    fun `a config only save keeps the settings and calls no provider hook`(): Unit = runBlocking {
        val g = gateway()
        service.save("gate", settings(), null)
        val before = row()!!.settings
        g.validated.clear()
        g.saved.clear()

        val result = service.save("gate", null, json("position" to 2, "customLabel" to "Card"))

        assertNull(result.message)
        assertEquals(before, row()!!.settings)
        assertEquals(2, row()!!.position)
        assertEquals("Card", row()!!.customLabel)
        assertTrue(g.validated.isEmpty() && g.saved.isEmpty())
    }

    @Test
    fun `rules left out of a save keep their stored value`(): Unit = runBlocking {
        gateway()
        service.save("gate", settings(), json("customLabel" to "Card", "feeMode" to "BUYER", "feePercent" to 1.5, "minAmount" to 2))
        service.save("gate", settings(), json("position" to 9))

        val r = row()!!
        assertEquals("Card", r.customLabel)
        assertEquals(150L, r.feePercent)
        assertEquals(200L, r.minAmount)
        assertEquals(9, r.position)
    }

    @Test
    fun `a gateway that sets the price takes no fee`(): Unit = runBlocking {
        gateway { caps = PaymentCapabilities().also { it.priceAuthority = com.panomc.plugins.market.spi.payment.PriceAuthority.GATEWAY_CATALOG } }
        val e = assertThrows<InvalidProviderSettings> { runBlocking { service.save("gate", settings(), json("feeMode" to "BUYER", "feeFixed" to 1)) } }

        assertEquals("EXTERNAL_PRICING", fieldErrors(e).getString("config.feeMode"))
    }

    // ---- provider hooks

    @Test
    fun `validateSettings runs before the write and onSettingsSaved after it`(): Unit = runBlocking {
        val g = gateway()
        service.save("gate", settings(), null)
        assertEquals(listOf("validate", "saved"), g.order)
        assertEquals(listOf("<none>"), g.saved)

        service.save("gate", settings("merchantId" to "M-2"), null)
        assertEquals(listOf("<none>", "M-1"), g.saved, "onSettingsSaved gets the previous settings")
    }

    @Test
    fun `a failing validateSettings stores nothing and reports its field errors`(): Unit = runBlocking {
        val g = gateway { validation = { SettingsValidation.invalid(mapOf("apiKey" to LocalizedText.of("Key rejected"))) } }
        val e = assertThrows<InvalidProviderSettings> { runBlocking { service.save("gate", settings(), null) } }

        assertEquals("Key rejected", fieldErrors(e).getJsonObject("apiKey").getString("default"))
        assertNull(row())
        assertTrue(g.saved.isEmpty(), "onSettingsSaved is not called")
    }

    @Test
    fun `a provider that throws in validateSettings is a 502`(): Unit = runBlocking {
        gateway { validation = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") } }

        assertThrows<PaymentProviderError> { runBlocking { service.save("gate", settings(), null) } }
        assertNull(row())
    }

    @Test
    fun `a settings patch of onSettingsSaved is stored and its text returned`(): Unit = runBlocking {
        val g = gateway()
        g.onSaved = { ActionResult.SettingsPatch(mapOf("note" to "registered"), LocalizedText.of("Webhook registered")) }
        // `note` is a plain field here; a patch only reaches HIDDEN fields, so nothing may change in the stored note
        val result = service.save("gate", settings(), null)

        assertEquals("Webhook registered", result.message!!.fallback)
        assertFalse(JsonObject(row()!!.settings).containsKey("note"))
    }

    @Test
    fun `a message of onSettingsSaved is returned and a gateway failure keeps the settings and marks the row`(): Unit = runBlocking {
        val g = gateway()
        g.onSaved = { ActionResult.Message(LocalizedText.of("Saved, webhook registered"), true) }
        assertEquals("Saved, webhook registered", service.save("gate", settings(), null).message!!.fallback)

        g.failSaved = ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "rejected", adminMessage = "Webhook URL refused")
        val result = service.save("gate", settings("merchantId" to "M-3"), null)

        assertEquals("Webhook URL refused", result.message!!.fallback)
        assertEquals("M-3", JsonObject(row()!!.settings).getString("merchantId"), "the settings are stored")
        assertEquals("GATEWAY_REJECTED", row()!!.lastError)
        assertNotNull(row()!!.lastErrorAt)
        assertEquals("GATEWAY_REJECTED", entry().getString("lastError"))
    }

    // ---- availability

    @Test
    fun `an unknown id is 404 and a provider that vanished is 409`(): Unit = runBlocking {
        assertThrows<NotFound> { runBlocking { service.save("nope", settings(), null) } }
        assertThrows<NotFound> { runBlocking { service.toggle("nope", true) } }
        assertThrows<NotFound> { runBlocking { service.reveal("nope", 1, { true }, { }) } }
        assertThrows<NotFound> { runBlocking { service.runAction("nope", "x", JsonObject()) } }

        gateway()
        service.save("gate", settings(), null)
        lookup.remove("gate")

        val e = assertThrows<ProviderUnavailable> { runBlocking { service.save("gate", settings(), null) } }
        assertEquals("UNAVAILABLE", JsonObject(e.encode(emptyMap())).getString("state"))
        assertThrows<ProviderUnavailable> { runBlocking { service.toggle("gate", true) } }
        assertThrows<ProviderUnavailable> { runBlocking { service.toggle("gate", false) } }
        assertThrows<ProviderUnavailable> { runBlocking { service.reveal("gate", 1, { true }, { }) } }
        assertThrows<ProviderUnavailable> { runBlocking { service.runAction("gate", "ping", JsonObject()) } }
        assertNotNull(row(), "the row and its settings are kept")
    }

    @Test
    fun `a row without a provider is listed as UNAVAILABLE and read only`(): Unit = runBlocking {
        w.paymentMethods.upsertByMethodId("tebex", true, json("webstoreId" to "1", "secret" to "plain_value_123").encode(), pool)

        val e = entry("tebex")

        assertEquals("UNAVAILABLE", e.getString("state"))
        assertTrue(e.getJsonObject("config").getBoolean("readOnly"))
        assertTrue(e.getJsonObject("config").getBoolean("enabled"))
        assertEquals(JsonObject(), e.getJsonObject("settings"))
        assertNull(e.getValue("schema"))
        assertFalse(e.encode().contains("plain_value_123"))
        assertEquals(JsonObject(), service.settingsSummary().getJsonObject("tebex").getJsonObject("settings"))
    }

    // ---- toggle

    @Test
    fun `toggle refuses a method that is not configured`(): Unit = runBlocking {
        gateway()

        assertThrows<PaymentMethodNotConfigured> { runBlocking { service.toggle("gate", true) } }
        assertNull(row())

        service.save("gate", settings(), null)
        service.toggle("gate", true)
        assertTrue(row()!!.enabled)
        assertEquals("ACTIVE", entry().getString("state"))

        service.toggle("gate", false)
        assertFalse(row()!!.enabled)
        assertEquals("DISABLED", entry().getString("state"))
        assertEquals("M-1", JsonObject(row()!!.settings).getString("merchantId"), "toggling keeps the settings")
    }

    @Test
    fun `toggle keeps the checkout rules`(): Unit = runBlocking {
        gateway()
        service.save("gate", settings(), json("customLabel" to "Card", "feeMode" to "BUYER", "feePercent" to 3, "position" to 5))

        service.toggle("gate", true)

        assertEquals("Card", row()!!.customLabel)
        assertEquals(300L, row()!!.feePercent)
        assertEquals(5, row()!!.position)
    }

    @Test
    fun `a provider without required fields can be enabled at once`(): Unit = runBlocking {
        lookup.add(com.panomc.plugins.market.provider.FreeProvider())

        service.toggle("free", true)

        assertTrue(row("free")!!.enabled)
        assertEquals("ACTIVE", entry("free").getString("state"))
    }

    @Test
    fun `toggle refuses a provider that needs a public https site`(): Unit = runBlocking {
        gateway { caps = PaymentCapabilities().also { it.needsPublicUrl = true } }
        service.save("gate", settings(), null)

        site = SiteInfo("Shop", "http://localhost:3000", https = false, publiclyReachable = false, defaultLocale = "en-US")
        assertThrows<PublicUrlRequired> { runBlocking { service.toggle("gate", true) } }
        assertFalse(row()!!.enabled)

        site = SiteInfo("Shop", "http://shop.example", https = false, publiclyReachable = true, defaultLocale = "en-US")
        assertThrows<PublicUrlRequired> { runBlocking { service.toggle("gate", true) } }

        site = SiteInfo("Shop", "https://shop.example", https = true, publiclyReachable = true, defaultLocale = "en-US")
        service.toggle("gate", true)
        assertTrue(row()!!.enabled)

        site = SiteInfo("Shop", "http://localhost:3000", https = false, publiclyReachable = false, defaultLocale = "en-US")
        service.toggle("gate", false)
        assertFalse(row()!!.enabled, "disabling never needs the public url")
    }

    @Test
    fun `toggle runs validateSettings before it enables`(): Unit = runBlocking {
        val g = gateway()
        service.save("gate", settings(), null)
        g.validation = { SettingsValidation.invalid(mapOf("apiKey" to LocalizedText.of("Key revoked"))) }

        assertThrows<InvalidProviderSettings> { runBlocking { service.toggle("gate", true) } }
        assertFalse(row()!!.enabled)

        g.validation = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        assertThrows<PaymentProviderError> { runBlocking { service.toggle("gate", true) } }
        assertFalse(row()!!.enabled)
    }

    // ---- sort

    @Test
    fun `sort persists and the list follows it`(): Unit = runBlocking {
        gateway("alpha")
        gateway("beta")
        gateway("gamma")
        service.save("alpha", settings(), null)

        service.sort(listOf("gamma", "alpha", "beta"))

        assertEquals(0, row("gamma")!!.position)
        assertEquals(1, row("alpha")!!.position)
        assertEquals(2, row("beta")!!.position)
        assertEquals(listOf("gamma", "alpha", "beta"), service.list().map { it.getString("id") })
        assertEquals("M-1", JsonObject(row("alpha")!!.settings).getString("merchantId"), "sorting keeps the settings of an existing row")
        assertFalse(row("gamma")!!.enabled)

        service.sort(listOf("beta", "gamma"))
        assertEquals(0, row("beta")!!.position)
        assertEquals(1, row("gamma")!!.position)
        assertEquals(1, row("alpha")!!.position, "ids that were not named keep their position")
    }

    @Test
    fun `sort refuses an unknown id and changes nothing`(): Unit = runBlocking {
        gateway("alpha")
        gateway("beta")

        assertThrows<NotFound> { runBlocking { service.sort(listOf("beta", "ghost")) } }

        assertNull(row("beta"), "the whole call rolled back")
        assertNull(row("alpha"))
    }

    @Test
    fun `a position set by a save is overwritten by a later sort`(): Unit = runBlocking {
        gateway("alpha")
        gateway("beta")
        service.save("alpha", settings(), json("position" to 50))

        service.sort(listOf("beta", "alpha"))

        assertEquals(1, row("alpha")!!.position)
        assertEquals(0, row("beta")!!.position)
    }

    // ---- reveal: throttle

    @Test
    fun `five wrong passwords lock the reveal for ten minutes`(): Unit = runBlocking {
        gateway()
        service.save("gate", settings(), null)
        var failures = 0

        repeat(5) {
            assertThrows<InvalidPassword> { runBlocking { service.reveal("gate", 7, { false }, { failures++ }) } }
        }
        assertEquals(5, failures)

        val locked = assertThrows<TooManyRequests> { runBlocking { service.reveal("gate", 7, { true }, { failures++ }) } }
        assertEquals(600, JsonObject(locked.encode(emptyMap())).getLong("retryAfter"))
        assertEquals(5, failures, "a locked attempt is not counted again and the password is not even asked")

        // another admin is not locked
        assertEquals("sk_live_abcdef123456", service.reveal("gate", 8, { true }, { }).getString("apiKey"))

        w.clock.advance(10 * 60_000L + 1)
        assertEquals("sk_live_abcdef123456", service.reveal("gate", 7, { true }, { }).getString("apiKey"))
    }

    @Test
    fun `the password is not asked while the admin is locked`(): Unit = runBlocking {
        gateway()
        repeat(5) { assertThrows<InvalidPassword> { runBlocking { service.reveal("gate", 7, { false }, { }) } } }
        var asked = false

        assertThrows<TooManyRequests> { runBlocking { service.reveal("gate", 7, { asked = true; true }, { }) } }

        assertFalse(asked)
    }

    @Test
    fun `a good password resets the failure count`(): Unit = runBlocking {
        gateway()
        repeat(4) { assertThrows<InvalidPassword> { runBlocking { service.reveal("gate", 7, { false }, { }) } } }

        service.reveal("gate", 7, { true }, { })

        repeat(4) { assertThrows<InvalidPassword> { runBlocking { service.reveal("gate", 7, { false }, { }) } } }
        service.reveal("gate", 7, { true }, { })
    }

    @Test
    fun `failures outside the ten minute window do not add up`(): Unit = runBlocking {
        gateway()
        repeat(4) { assertThrows<InvalidPassword> { runBlocking { service.reveal("gate", 7, { false }, { }) } } }
        w.clock.advance(10 * 60_000L + 1)

        repeat(4) { assertThrows<InvalidPassword> { runBlocking { service.reveal("gate", 7, { false }, { }) } } }

        assertNotNull(service.reveal("gate", 7, { true }, { }))
    }

    // ---- actions

    @Test
    fun `an action runs on the saved settings and returns the provider text`(): Unit = runBlocking {
        val g = gateway { action = { ActionResult.Message(LocalizedText.of("Connected"), true) } }

        assertThrows<InvalidState> { runBlocking { service.runAction("gate", "test-connection", JsonObject()) } }
        assertThrows<NotFound> { runBlocking { service.runAction("gate", "unknown-action", JsonObject()) } }

        val noSettings = service.runAction("gate", "ping", JsonObject())
        assertTrue(noSettings.success)

        service.save("gate", settings(), null)
        val ok = service.runAction("gate", "test-connection", JsonObject())
        assertTrue(ok.success)
        assertEquals("Connected", ok.message!!.fallback)

        g.action = { ActionResult.Message(LocalizedText.of("Bad key"), false) }
        val bad = service.runAction("gate", "test-connection", JsonObject())
        assertFalse(bad.success)
        assertEquals("Bad key", bad.message!!.fallback)
    }

    @Test
    fun `a provider failure in an action is a 502 and is remembered on the row`(): Unit = runBlocking {
        val g = gateway()
        service.save("gate", settings(), null)

        g.failAction = ProviderException(ProviderErrorCode.AUTHENTICATION, "401 from gateway")
        val e = assertThrows<PaymentProviderError> { runBlocking { service.runAction("gate", "test-connection", JsonObject()) } }

        assertEquals("AUTHENTICATION", JsonObject(e.encode(emptyMap())).getString("code"))
        assertEquals("AUTHENTICATION", row()!!.lastError)

        g.failAction = IllegalStateException("boom")
        val internal = assertThrows<PaymentProviderError> { runBlocking { service.runAction("gate", "test-connection", JsonObject()) } }
        assertEquals("INTERNAL", JsonObject(internal.encode(emptyMap())).getString("code"))
    }

    @Test
    fun `the catalogue import result is not stored and says so`(): Unit = runBlocking {
        gateway { action = { ActionResult.CatalogImport(emptyList(), emptyList()) } }
        service.save("gate", settings(), null)

        val outcome = service.runAction("gate", "test-connection", JsonObject())

        assertFalse(outcome.success)
        assertNotNull(outcome.message)
    }

    // ---- list shape

    @Test
    fun `the provider list carries the wire shape of 04 section 8`(): Unit = runBlocking {
        gateway { caps = PaymentCapabilities().also { it.needsPublicUrl = true } }
        service.save("gate", settings(), json("customLabel" to "Card"))

        val e = entry()

        assertEquals("DISABLED", e.getString("state"))
        assertEquals("AVAILABLE", e.getString("availability"))
        assertNotNull(e.getValue("spiVersion"))
        assertEquals("Gate", e.getJsonObject("descriptor").getJsonObject("name").getString("default"))
        assertEquals("/api/market/payment-providers/gate/logo", e.getJsonObject("descriptor").getString("logoUrl"))
        assertEquals("#112233", e.getJsonObject("descriptor").getString("color"))
        assertEquals("global", e.getJsonObject("descriptor").getString("region"))
        assertEquals("UNVERIFIED", e.getJsonObject("descriptor").getString("verification"))
        assertEquals(setOf("merchantId", "apiKey", "webhookSecret", "note", "callback"), e.getJsonObject("schema").getJsonArray("fields").map { (it as JsonObject).getString("key") }.toSet())
        assertEquals(2, e.getJsonObject("schema").getJsonArray("actions").size())
        assertTrue(e.getJsonObject("capabilities").getBoolean("needsPublicUrl"))
        assertEquals("PER_PAYMENT", e.getJsonObject("capabilities").getString("webhookSetup"))
        assertEquals("https://shop.example/api/market/payments/gate/webhook", e.getJsonObject("webhookUrls").getString("default"))
        val callback = e.getJsonObject("schema").getJsonArray("fields").map { it as JsonObject }.first { it.getString("key") == "callback" }
        assertEquals("https://shop.example/api/market/payments/gate/webhook", callback.getJsonObject("readonly").getString("value"))
        assertTrue(e.containsKey("lastInboundAt") && e.containsKey("lastError") && e.containsKey("lastErrorAt") && e.containsKey("productMetaSchema"))
        assertEquals(setOf("enabled", "position", "customLabel", "customDescription", "feeMode", "feePercent", "feeFixed", "minAmount", "maxAmount", "currencies", "testMode", "readOnly"), e.getJsonObject("config").fieldNames())
    }

    @Test
    fun `the list shows NOT_CONFIGURED for a provider with missing required settings and never leaks a secret`(): Unit = runBlocking {
        gateway()
        assertEquals("NOT_CONFIGURED", entry().getString("state"))

        service.save("gate", settings("webhookSecret" to "whsec_123456789"), null)
        val text = service.list().toString() + service.settingsSummary()

        assertFalse(text.contains("sk_live_abcdef123456"))
        assertFalse(text.contains("whsec_123456789"))
    }

    @Test
    fun `a provider that throws while it is described is listed unavailable and the others still show`(): Unit = runBlocking {
        val broken = object : PaymentProvider by Gateway("broken") {
            override fun settingsSchema(): SettingsSchema = error("schema exploded")
        }
        lookup.add(broken)
        gateway("fine")

        val all = service.list()

        assertEquals("UNAVAILABLE", all.first { it.getString("id") == "broken" }.getString("state"))
        assertEquals("NOT_CONFIGURED", all.first { it.getString("id") == "fine" }.getString("state"))
    }

    @Test
    fun `the logo is served for a provider that has one`(): Unit = runBlocking {
        gateway()
        val logo = service.logo("gate")

        assertNotNull(logo)
        assertEquals("image/png", logo!!.contentType)
        assertEquals(listOf<Byte>(1, 2, 3), logo.bytes.toList())
        assertNull(service.logo("ghost"))
    }

    @Test
    fun `a provider without a logo has no logo url and no logo`(): Unit = runBlocking {
        lookup.add(com.panomc.plugins.market.provider.FreeProvider())

        assertNull(service.logo("free"))
        assertNull(entry("free").getJsonObject("descriptor").getValue("logoUrl"))
    }
}
