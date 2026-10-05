package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.money.Currencies
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketThrottleDao
import com.panomc.plugins.market.db.model.MarketPaymentMethod
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.ThrottleScope
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InvalidPassword
import com.panomc.plugins.market.error.InvalidProviderSettings
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.PaymentMethodNotConfigured
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.ProviderUnavailable
import com.panomc.plugins.market.error.PublicUrlRequired
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.provider.ProviderListing
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.ResolvedProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderAsset
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.ReadonlyValue
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PriceAuthority
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Builds the context a provider gets for the settings hooks (`validateSettings`, `onSettingsSaved`, `runAction`). */
fun interface PaymentContexts {
    fun create(provider: PaymentProvider, settings: ProviderSettings, testMode: Boolean): PaymentContext
}

/**
 * The provider configuration of the panel (04 section 8, MK-046), on the provider registry instead of the old
 * compile-time catalogue. One row of `market_payment_method` per provider id holds `enabled`, the encrypted `settings`
 * and the checkout rules (position, label, fee, window, currencies, test mode).
 *
 * Calls to a provider never run inside a database transaction (they may take seconds): read, call, write. A save is
 * schema validation ([SettingsCodec.applyForm]), the rule validation ([PaymentMethodRules]), `validateSettings`, the
 * write, then `onSettingsSaved`.
 */
class PaymentMethodService(
    private val db: MarketDb,
    private val clock: Clock,
    private val methods: MarketPaymentMethodDao,
    private val throttles: MarketThrottleDao,
    private val lookup: ProviderLookup,
    private val cipher: SecretCipher,
    private val contexts: PaymentContexts,
    private val site: () -> SiteInfo
) {
    /** Outcome of [save]: the text `onSettingsSaved` returned for the admin, if any. */
    class SaveResult(val message: LocalizedText?)

    /** Outcome of [runAction]. */
    class ActionOutcome(val success: Boolean, val message: LocalizedText?)

    // ---- list

    /** The `providers[]` of `GET /payment-providers`, ordered by position then registry order. */
    suspend fun list(): List<JsonObject> {
        val rows = db.tx { conn -> methods.getAll(conn) }.associateBy { it.methodId }
        val listings = lookup.listing(ProviderKind.PAYMENT)
        val seen = HashSet<String>()
        val entries = ArrayList<Pair<Int, JsonObject>>()

        for (listing in listings) {
            val id = listing.id ?: continue
            if (!seen.add(id)) continue
            val row = rows[id]
            entries.add((row?.position ?: 0) to describe(id, listing, row))
        }

        // A row whose provider is not registered at all is kept and reported UNAVAILABLE (01 section 6.1).
        for (row in rows.values) {
            if (seen.add(row.methodId)) entries.add(row.position to unavailableEntry(row))
        }

        return entries.sortedBy { it.first }.map { it.second }
    }

    /** `paymentMethods{<id>: {enabled, settings}}` of `GET /settings` (masked), built from the registry. */
    suspend fun settingsSummary(): JsonObject {
        val rows = db.tx { conn -> methods.getAll(conn) }.associateBy { it.methodId }
        val out = JsonObject()

        for (listing in lookup.listing(ProviderKind.PAYMENT)) {
            val id = listing.id ?: continue
            val row = rows[id]
            val provider = listing.resolved?.provider as? PaymentProvider
            val settings = if (provider != null && listing.state.usable) {
                runCatching { SettingsCodec(provider.settingsSchema(), cipher).mask(row?.let { storedOf(it) }) }.getOrDefault(JsonObject())
            } else JsonObject()
            out.put(id, JsonObject().put("enabled", row?.enabled ?: false).put("settings", settings))
        }

        for (row in rows.values) {
            if (!out.containsKey(row.methodId)) out.put(row.methodId, JsonObject().put("enabled", row.enabled).put("settings", JsonObject()))
        }

        return out
    }

    /** The logo bytes of a usable provider, `null` when it has none or is unknown (`GET /payment-providers/:id/logo`). */
    fun logo(id: String): ProviderAsset? {
        val provider = lookup.payment(id)?.provider ?: return null

        return try {
            provider.descriptor.logo
        } catch (e: Exception) {
            null
        }
    }

    // ---- save

    /**
     * `POST /payment-methods/:id`. [settingsForm] (the schema fields) and [configForm] (the rule keys) are both optional;
     * what is absent stays as stored. Throws [NotFound], [ProviderUnavailable], [InvalidProviderSettings] and
     * [PaymentProviderError].
     */
    suspend fun save(id: String, settingsForm: JsonObject?, configForm: JsonObject?): SaveResult {
        val resolved = requireUsable(id)
        val provider = resolved.provider
        val codec = SettingsCodec(provider.settingsSchema(), cipher)
        val row = db.tx { conn -> methods.getByMethodId(id, conn) }

        var stored: JsonObject? = row?.let { storedOf(it) }
        val legacyTestFlag = legacyTestFlag(stored, codec)
        if (stored != null && provider is BankTransferProvider) stored = BankTransferProvider.migrateLegacy(stored)

        val errors = LinkedHashMap<String, Any?>()
        var newSettings: JsonObject = stored ?: JsonObject()

        if (settingsForm != null) {
            val applied = codec.applyForm(stored, settingsForm)
            applied.issues.forEach { (key, issue) -> errors[key] = issue.text.toJson().map }
            newSettings = applied.settings
        } else {
            codec.encryptLegacyPlaintext(newSettings)?.let { newSettings = it }
        }

        val capabilities = capabilitiesOf(provider, codec.decrypt(newSettings))
        val base = PaymentMethodRules.State.of(row)
        val parsed = PaymentMethodRules.parse(configForm ?: JsonObject(), base, capabilities, legacyTestFlag.takeIf { configForm?.containsKey("testMode") != true })
        parsed.errors.forEach { (key, code) -> errors["config.$key"] = code }

        if (errors.isNotEmpty()) throw InvalidProviderSettings(errors)

        val config = parsed.state
        val decrypted = codec.decrypt(newSettings)
        val effectiveTest = config.testMode

        if (settingsForm != null) {
            val validation = guarded { provider.validateSettings(contexts.create(provider, decrypted, effectiveTest), decrypted) }

            if (!validation.ok) {
                val fieldErrors = LinkedHashMap<String, Any?>()
                validation.fieldErrors.forEach { (key, text) -> fieldErrors[key] = text.toJson().map }
                validation.message?.let { fieldErrors["settings"] = it.toJson().map }
                throw InvalidProviderSettings(fieldErrors)
            }
        }

        val now = clock.now()
        val written = rowOf(id, row, newSettings, config, now, touchSettings = settingsForm != null)

        db.tx { conn ->
            methods.saveConfig(written, conn)
            methods.setLastError(id, null, null, conn)
        }

        if (settingsForm == null) return SaveResult(null)

        val previous = row?.let { codec.decrypt(storedOf(it)) }
        var message: LocalizedText? = null

        try {
            val result = provider.onSettingsSaved(contexts.create(provider, decrypted, effectiveTest), previous)
            message = applyResult(id, codec, result)
        } catch (e: ProviderException) {
            // The settings are stored; the gateway side failed (webhook registration, ...): shown to the admin, kept on the row.
            recordError(id, e.code.name)
            message = LocalizedText.of(e.adminMessage ?: e.message ?: e.code.name)
        }

        return SaveResult(message)
    }

    // ---- toggle

    /** `POST /payment-methods/:id/toggle`. Enabling needs a usable provider, all required settings, a public URL where needed, and a passing `validateSettings`. */
    suspend fun toggle(id: String, enabled: Boolean) {
        val resolved = requireUsable(id)
        val provider = resolved.provider
        val codec = SettingsCodec(provider.settingsSchema(), cipher)
        val row = db.tx { conn -> methods.getByMethodId(id, conn) }
        val stored = row?.let { storedOf(it) }

        if (enabled) {
            if (codec.missingRequired(stored).isNotEmpty()) throw PaymentMethodNotConfigured()

            val decrypted = codec.decrypt(stored)
            val capabilities = capabilitiesOf(provider, decrypted)
            val info = site()

            if (capabilities.needsPublicUrl && !(info.https && info.publiclyReachable)) throw PublicUrlRequired()

            val validation = guarded { provider.validateSettings(contexts.create(provider, decrypted, row?.testMode ?: false), decrypted) }

            if (!validation.ok) {
                val fieldErrors = LinkedHashMap<String, Any?>()
                validation.fieldErrors.forEach { (key, text) -> fieldErrors[key] = text.toJson().map }
                validation.message?.let { fieldErrors["settings"] = it.toJson().map }
                throw InvalidProviderSettings(fieldErrors)
            }
        }

        db.tx { conn -> methods.upsertByMethodId(id, enabled, row?.settings ?: "{}", conn) }
    }

    // ---- sort

    /** `POST /payment-methods/sort`: [ids] get the positions 0..n-1 in that order (a provider without a row gets one). */
    suspend fun sort(ids: List<String>) {
        db.tx { conn ->
            val rows = methods.getAll(conn).associateBy { it.methodId }
            val known = lookup.listing(ProviderKind.PAYMENT).mapNotNull { it.id }.toSet()

            ids.forEach { id -> if (id !in rows && id !in known) throw NotFound() }

            ids.forEachIndexed { index, id ->
                if (id !in rows) methods.upsertByMethodId(id, false, "{}", conn)
                methods.setPosition(id, index, conn)
            }
        }
    }

    // ---- reveal

    /**
     * `POST /payment-methods/:id/reveal` (11 section 8.3): lock check, password, throttle; returns only the secret fields of
     * the schema, decrypted. [onFailed] runs after a wrong password was counted (the activity log).
     */
    suspend fun reveal(id: String, userId: Long, passwordCorrect: suspend () -> Boolean, onFailed: suspend () -> Unit): JsonObject {
        val resolved = requireUsable(id)
        val subject = "u:$userId"
        val now = clock.now()
        val lockedUntil = db.tx { conn -> throttles.lockedUntil(ThrottleScope.REVEAL, subject, now, conn) }

        if (lockedUntil != null) throw TooManyRequests(maxOf(1L, (lockedUntil - now + 999) / 1000))

        if (!passwordCorrect()) {
            db.tx { conn -> throttles.fail(ThrottleScope.REVEAL, subject, REVEAL_THRESHOLD, REVEAL_WINDOW_MS, REVEAL_LOCK_MS, now, conn) }
            onFailed()
            throw InvalidPassword()
        }

        db.tx { conn -> throttles.reset(ThrottleScope.REVEAL, subject, conn) }

        val row = db.tx { conn -> methods.getByMethodId(id, conn) }

        return SettingsCodec(resolved.provider.settingsSchema(), cipher).reveal(row?.let { storedOf(it) })
    }

    // ---- actions

    /** `POST /payment-methods/:id/actions/:actionId`. A provider failure is a 502 `PAYMENT_PROVIDER_ERROR`. */
    suspend fun runAction(id: String, actionId: String, input: JsonObject): ActionOutcome {
        val resolved = requireUsable(id)
        val provider = resolved.provider
        val schema = provider.settingsSchema()
        val action = schema.actions.firstOrNull { it.id == actionId } ?: throw NotFound()
        val row = db.tx { conn -> methods.getByMethodId(id, conn) }

        if (action.requiresSavedSettings && row == null) throw InvalidState("SETTINGS_NOT_SAVED")

        val codec = SettingsCodec(schema, cipher)
        val decrypted = codec.decrypt(row?.let { storedOf(it) })

        val result = try {
            provider.runAction(contexts.create(provider, decrypted, row?.testMode ?: false), actionId, input)
        } catch (e: ProviderException) {
            recordError(id, e.code.name)
            throw PaymentProviderError(e.code.name)
        } catch (e: RuntimeException) {
            recordError(id, ProviderErrorCodes.INTERNAL)
            throw PaymentProviderError(ProviderErrorCodes.INTERNAL)
        }

        return when (result) {
            is ActionResult.None -> ActionOutcome(true, null)
            is ActionResult.Message -> ActionOutcome(result.success, result.text)
            is ActionResult.SettingsPatch -> ActionOutcome(true, applyResult(id, codec, result))
            // The catalogue import is written by the catalogue slice (it needs CatalogService); nothing is stored here.
            is ActionResult.CatalogImport -> ActionOutcome(false, LocalizedText.of("The catalogue import is not available yet.", "tr" to "Katalog içe aktarma henüz kullanılamıyor.", "ru" to "Импорт каталога пока недоступен."))
        }
    }

    // ---- start

    /**
     * At start (01 section 14.4): secrets that are still legacy plaintext are encrypted, and a row with a secret that
     * cannot be decrypted gets `lastError = SECRET_UNREADABLE` (a later good read clears it). Never throws for one provider.
     */
    suspend fun startup() {
        val rows = db.tx { conn -> methods.getAll(conn) }

        for (row in rows) {
            try {
                val provider = lookup.payment(row.methodId)?.provider ?: continue
                val codec = SettingsCodec(provider.settingsSchema(), cipher)
                val stored = storedOf(row)
                val encrypted = codec.encryptLegacyPlaintext(stored)
                val unreadable = codec.unreadableSecrets(encrypted ?: stored)

                db.tx { conn ->
                    if (encrypted != null) methods.upsertByMethodId(row.methodId, row.enabled, encrypted.encode(), conn)

                    if (unreadable.isNotEmpty()) methods.setLastError(row.methodId, SECRET_UNREADABLE, clock.now(), conn)
                    else if (row.lastError == SECRET_UNREADABLE) methods.setLastError(row.methodId, null, null, conn)
                }
            } catch (e: Exception) {
                // one broken provider must not stop the others
            }
        }
    }

    // ---- internals

    private object ProviderErrorCodes {
        const val INTERNAL = "INTERNAL"
    }

    private fun storedOf(row: MarketPaymentMethod): JsonObject = try {
        JsonObject(row.settings)
    } catch (e: Exception) {
        JsonObject()
    }

    /** `404` for an id nothing knows, `409 PROVIDER_UNAVAILABLE` for a known id whose provider cannot be used right now. */
    private suspend fun requireUsable(id: String): ResolvedProvider<PaymentProvider> {
        lookup.payment(id)?.let { return it }

        val state = lookup.state(ProviderKind.PAYMENT, id)
        val hasRow = db.tx { conn -> methods.getByMethodId(id, conn) } != null

        if (state.availability == ProviderAvailability.MISSING && !hasRow) throw NotFound()

        throw ProviderUnavailable(PaymentMethodStates.of(state.availability))
    }

    private fun legacyTestFlag(stored: JsonObject?, codec: SettingsCodec): Boolean? {
        if (stored == null) return null

        for (key in LEGACY_TEST_KEYS) {
            if (codec.schema.field(key) != null || !stored.containsKey(key)) continue

            val value = stored.getValue(key)
            val truthy = value == true || (value is String && value.trim().lowercase() in setOf("true", "1", "yes", "on", "sandbox", "test"))

            if (truthy) return true
        }

        return null
    }

    private fun capabilitiesOf(provider: PaymentProvider, settings: ProviderSettings): PaymentCapabilities = try {
        provider.capabilities(settings)
    } catch (e: Exception) {
        PaymentCapabilities()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: ProviderException) {
        throw PaymentProviderError(e.code.name)
    } catch (e: RuntimeException) {
        throw PaymentProviderError(ProviderErrorCodes.INTERNAL)
    }

    private suspend fun recordError(id: String, code: String) {
        db.tx { conn -> methods.setLastError(id, code, clock.now(), conn) }
    }

    /** Stores what an [ActionResult] asks for and returns the text for the admin. */
    private suspend fun applyResult(id: String, codec: SettingsCodec, result: ActionResult): LocalizedText? = when (result) {
        is ActionResult.Message -> result.text
        is ActionResult.SettingsPatch -> {
            val row = db.tx { conn -> methods.getByMethodId(id, conn) }
            val patched = codec.applyPatch(row?.let { storedOf(it) }, result.values)

            db.tx { conn -> methods.upsertByMethodId(id, row?.enabled ?: false, patched.settings.encode(), conn) }
            result.text
        }

        else -> null
    }

    private fun rowOf(id: String, row: MarketPaymentMethod?, settings: JsonObject, config: PaymentMethodRules.State, now: Long, touchSettings: Boolean) =
        MarketPaymentMethod(
            methodId = id,
            enabled = row?.enabled ?: false,
            settings = settings.encode(),
            createdAt = row?.createdAt ?: now,
            updatedAt = now,
            position = config.position,
            customLabel = config.customLabel,
            customDescription = config.customDescription,
            feeMode = config.feeMode,
            feePercent = config.feePercent,
            feeFixed = config.feeFixed,
            minAmount = config.minAmount,
            maxAmount = config.maxAmount,
            currencies = config.currencies?.let { JsonArray(it).encode() },
            testMode = config.testMode,
            settingsUpdatedAt = if (touchSettings) now else row?.settingsUpdatedAt
        )

    private fun unavailableEntry(row: MarketPaymentMethod): JsonObject = JsonObject()
        .put("id", row.methodId)
        .put("state", PaymentMethodStates.UNAVAILABLE)
        .put("availability", ProviderAvailability.MISSING.name)
        .put("pluginId", null as String?)
        .put("spiVersion", null as Int?)
        .put("descriptor", null as JsonObject?)
        .put("schema", null as JsonObject?)
        .put("capabilities", null as JsonObject?)
        .put("productMetaSchema", null as JsonObject?)
        .put("config", configJson(row, true))
        .put("settings", JsonObject())
        .put("webhookUrls", JsonObject())
        .put("lastInboundAt", row.lastInboundAt)
        .put("lastError", row.lastError)
        .put("lastErrorAt", row.lastErrorAt)

    private fun describe(id: String, listing: ProviderListing, row: MarketPaymentMethod?): JsonObject {
        val provider = listing.resolved?.provider as? PaymentProvider
        val state = listing.state
        val out = JsonObject().put("id", id).put("availability", state.availability.name).put("pluginId", state.pluginId ?: listing.resolved?.pluginId)

        if (provider == null || !state.usable) {
            // INCOMPATIBLE is read-only with "update the plugin"; every other unusable case is UNAVAILABLE.
            return out
                .put("state", PaymentMethodStates.of(state.availability))
                .put("spiVersion", state.spiVersion)
                .put("detail", state.detail)
                .put("descriptor", null as JsonObject?)
                .put("schema", null as JsonObject?)
                .put("capabilities", null as JsonObject?)
                .put("productMetaSchema", null as JsonObject?)
                .put("config", configJson(row, false))
                .put("settings", JsonObject())
                .put("webhookUrls", JsonObject())
                .put("lastInboundAt", row?.lastInboundAt)
                .put("lastError", row?.lastError)
                .put("lastErrorAt", row?.lastErrorAt)
        }

        return try {
            val schema = provider.settingsSchema()
            val codec = SettingsCodec(schema, cipher)
            val stored = row?.let { storedOf(it) }
            val decrypted = codec.decrypt(stored)
            val ctx = contexts.create(provider, decrypted, row?.testMode ?: false)
            val capabilities = capabilitiesOf(provider, decrypted)
            val missing = codec.missingRequired(stored)
            val webhookUrls = JsonObject()

            schema.fields.forEach { field ->
                (field.readonly as? ReadonlyValue.WebhookUrl)?.let { webhookUrls.put(it.channel, ctx.urls.webhook(it.channel)) }
            }

            if (webhookUrls.isEmpty && capabilities.webhookSetup != com.panomc.plugins.market.spi.common.WebhookSetup.NONE) {
                webhookUrls.put(MarketSpi.DEFAULT_CHANNEL, ctx.urls.webhook(MarketSpi.DEFAULT_CHANNEL))
            }

            out
                .put("state", PaymentMethodStates.stateOf(state.availability, row?.enabled ?: false, missing.isNotEmpty()))
                .put("spiVersion", state.spiVersion ?: listing.resolved.spiVersion)
                .put("descriptor", descriptorJson(id, provider))
                .put("schema", schema.toJson { value -> resolveReadonly(ctx, value) })
                .put("capabilities", capabilitiesJson(capabilities))
                .put("productMetaSchema", runCatching { provider.productMetaSchema(decrypted)?.toJson() }.getOrNull())
                .put("config", configJson(row, false))
                .put("settings", codec.mask(stored))
                .put("webhookUrls", webhookUrls)
                .put("lastInboundAt", row?.lastInboundAt)
                .put("lastError", row?.lastError)
                .put("lastErrorAt", row?.lastErrorAt)
        } catch (e: Exception) {
            // A plugin that throws while describing itself must not break the list: it is reported like an invalid provider.
            out
                .put("state", PaymentMethodStates.UNAVAILABLE)
                .put("availability", ProviderAvailability.INVALID.name)
                .put("spiVersion", state.spiVersion)
                .put("detail", "the provider threw while it was described")
                .put("descriptor", null as JsonObject?)
                .put("schema", null as JsonObject?)
                .put("capabilities", null as JsonObject?)
                .put("productMetaSchema", null as JsonObject?)
                .put("config", configJson(row, false))
                .put("settings", JsonObject())
                .put("webhookUrls", JsonObject())
                .put("lastInboundAt", row?.lastInboundAt)
                .put("lastError", row?.lastError)
                .put("lastErrorAt", row?.lastErrorAt)
        }
    }

    private fun resolveReadonly(ctx: PaymentContext, value: ReadonlyValue): String? = when (value) {
        is ReadonlyValue.WebhookUrl -> ctx.urls.webhook(value.channel)
        is ReadonlyValue.ReturnUrlPrefix -> ctx.site.baseUrl
        is ReadonlyValue.SiteBaseUrl -> ctx.site.baseUrl
        is ReadonlyValue.Static -> null
    }

    private fun descriptorJson(id: String, provider: PaymentProvider): JsonObject {
        val d = provider.descriptor

        return JsonObject()
            .put("name", d.displayName.toJson())
            .put("description", d.description.toJson())
            .put("icon", d.icon)
            .put("logoUrl", if (d.logo != null) "/api/market/payment-providers/$id/logo" else null)
            .put("color", d.color)
            .put("region", d.region)
            .put("docsUrl", d.docsUrl)
            .put("verification", d.verification.name)
            .put("checkoutHint", d.checkoutHint?.toJson())
            .put("storefrontNotices", JsonArray(d.storefrontNotices.map { JsonObject().put("label", it.label.toJson()).put("url", it.url) }))
    }

    companion object {
        const val SECRET_UNREADABLE = "SECRET_UNREADABLE"
        private const val REVEAL_THRESHOLD = 5
        private const val REVEAL_WINDOW_MS = 10L * 60_000
        private const val REVEAL_LOCK_MS = 10L * 60_000
        private val LEGACY_TEST_KEYS = listOf("sandbox", "testMode")

        /** The `config` object of a provider row; money and the fee percent as decimals (04 section 1). */
        fun configJson(row: MarketPaymentMethod?, unavailable: Boolean): JsonObject {
            val r = row ?: MarketPaymentMethod()

            return JsonObject()
                .put("enabled", row?.enabled ?: false)
                .put("position", r.position)
                .put("customLabel", r.customLabel)
                .put("customDescription", r.customDescription)
                .put("feeMode", r.feeMode.name)
                .put("feePercent", PaymentMethodRules.toDecimal(r.feePercent))
                .put("feeFixed", PaymentMethodRules.toDecimal(r.feeFixed))
                .put("minAmount", r.minAmount?.let { PaymentMethodRules.toDecimal(it) })
                .put("maxAmount", r.maxAmount?.let { PaymentMethodRules.toDecimal(it) })
                .put("currencies", r.currencies?.let { runCatching { JsonArray(it) }.getOrNull() })
                .put("testMode", r.testMode)
                .put("readOnly", unavailable)
        }

        /** The `capabilities{}` object of the wire format (02 section 5.1). */
        fun capabilitiesJson(c: PaymentCapabilities): JsonObject = JsonObject()
            .put("currencies", c.currencies?.let { JsonArray(it.sorted()) })
            .put("refund", c.refund.name)
            .put("recurring", c.recurring.name)
            .put("recurringCurrencies", c.recurringCurrencies?.let { JsonArray(it.sorted()) })
            .put("recurringIntervals", c.recurringIntervals?.let { set -> JsonArray(set.map { it.name }.sorted()) })
            .put("recurringMaxCycles", c.recurringMaxCycles)
            .put("recurringMinIntervalDays", c.recurringMinIntervalDays)
            .put("recurringMaxIntervalDays", c.recurringMaxIntervalDays)
            .put("recurringResume", c.recurringResume)
            .put("recurringRetry", c.recurringRetry)
            .put("recurringPortal", c.recurringPortal)
            .put("statusQuery", c.statusQuery)
            .put("cancelPending", c.cancelPending)
            .put("disputeEvents", c.disputeEvents)
            .put("buyerMayPayMore", c.buyerMayPayMore)
            .put("priceAuthority", c.priceAuthority.name)
            .put("fulfillment", c.fulfillment.name)
            .put("testMode", c.testMode.name)
            .put("derivedTestMode", c.derivedTestMode)
            .put("requiredBuyerFields", JsonArray(c.requiredBuyerFields.map { it.name }.sorted()))
            .put("minAmount", c.minAmount?.let { JsonObject().put("amount", it.toDecimalString()).put("currency", it.currency) })
            .put("maxAmount", c.maxAmount?.let { JsonObject().put("amount", it.toDecimalString()).put("currency", it.currency) })
            .put("physicalGoods", c.physicalGoods)
            .put("guests", c.guests)
            .put("mixedCredit", c.mixedCredit)
            .put("paymentWindowMinutes", c.paymentWindowMinutes)
            .put("longPending", c.longPending)
            .put("fulfillmentUpdates", c.fulfillmentUpdates)
            .put("webhookSetup", c.webhookSetup.name)
            .put("needsPublicUrl", c.needsPublicUrl)
    }
}

/** The method states of 02 section 11. */
object PaymentMethodStates {
    const val ACTIVE = "ACTIVE"
    const val DISABLED = "DISABLED"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val INCOMPATIBLE = "INCOMPATIBLE"
    const val UNAVAILABLE = "UNAVAILABLE"

    /** The state of a method whose provider is [availability]. Only `AVAILABLE` looks at the row. */
    fun stateOf(availability: ProviderAvailability, enabled: Boolean, missingRequired: Boolean): String = when (availability) {
        ProviderAvailability.AVAILABLE -> when {
            missingRequired -> NOT_CONFIGURED
            enabled -> ACTIVE
            else -> DISABLED
        }

        else -> of(availability)
    }

    /** The state name of a provider that cannot be used. `SHADOWED`, `INVALID` and `MISSING` read as `UNAVAILABLE`. */
    fun of(availability: ProviderAvailability): String = when (availability) {
        ProviderAvailability.INCOMPATIBLE -> INCOMPATIBLE
        else -> UNAVAILABLE
    }
}

/**
 * The checkout rules of a provider row (01 section 6.1, 13 section on checkout rules) and their validation, pure so it is
 * tested without a database. Wire format: money and the fee percent are decimal numbers (`2.9`, `0.30`), stored as x100
 * (the percent as basis points). A bad key is reported under its own name; nothing is stored then.
 */
object PaymentMethodRules {
    /** The rule values of one row. */
    class State(
        val position: Int,
        val customLabel: String?,
        val customDescription: String?,
        val feeMode: PaymentFeeMode,
        val feePercent: Long,
        val feeFixed: Long,
        val minAmount: Long?,
        val maxAmount: Long?,
        val currencies: List<String>?,
        val testMode: Boolean
    ) {
        companion object {
            fun of(row: MarketPaymentMethod?): State = State(
                row?.position ?: 0, row?.customLabel, row?.customDescription, row?.feeMode ?: PaymentFeeMode.NONE, row?.feePercent ?: 0,
                row?.feeFixed ?: 0, row?.minAmount, row?.maxAmount,
                row?.currencies?.let { runCatching { JsonArray(it).map { c -> c.toString() } }.getOrNull() }, row?.testMode ?: false
            )
        }
    }

    class Parsed(val state: State, val errors: Map<String, String>)

    val KEYS = setOf("position", "customLabel", "customDescription", "feeMode", "feePercent", "feeFixed", "minAmount", "maxAmount", "currencies", "testMode")

    const val MAX_LABEL = 255
    const val MAX_DESCRIPTION = 512
    const val MAX_POSITION = 1_000_000L

    /** Largest amount (in whole units) a rule may hold. */
    private val MAX_UNITS = BigDecimal("1000000000")

    fun toDecimal(x100: Long): BigDecimal = BigDecimal.valueOf(x100, 2)

    /**
     * Lays [form] over [base]. [legacyTestMode] is the old catalogue flag (`sandbox` / `testMode`) found in the stored
     * settings; when [form] does not set `testMode` itself, a legacy `true` turns the method's test mode on (the first
     * save migrates the flag, 16 section 16).
     */
    fun parse(form: JsonObject, base: State, capabilities: PaymentCapabilities?, legacyTestMode: Boolean? = null): Parsed {
        val errors = LinkedHashMap<String, String>()

        form.fieldNames().filter { it !in KEYS }.forEach { errors[it] = "UNKNOWN_PROPERTY" }

        fun has(key: String) = form.containsKey(key)

        var position = base.position
        if (has("position")) {
            val v = asLong(form.getValue("position"))
            if (v != null && v in 0..MAX_POSITION) position = v.toInt() else errors["position"] = "INVALID"
        }

        var label = base.customLabel
        if (has("customLabel")) {
            when (val v = form.getValue("customLabel")) {
                null -> label = null
                is String -> if (v.trim().length <= MAX_LABEL) label = v.trim().ifEmpty { null } else errors["customLabel"] = "TOO_LONG"
                else -> errors["customLabel"] = "INVALID"
            }
        }

        var description = base.customDescription
        if (has("customDescription")) {
            when (val v = form.getValue("customDescription")) {
                null -> description = null
                is String -> if (v.trim().length <= MAX_DESCRIPTION) description = v.trim().ifEmpty { null } else errors["customDescription"] = "TOO_LONG"
                else -> errors["customDescription"] = "INVALID"
            }
        }

        var feeMode = base.feeMode
        if (has("feeMode")) {
            val v = (form.getValue("feeMode") as? String)?.let { s -> PaymentFeeMode.entries.firstOrNull { it.name == s } }
            if (v != null) feeMode = v else errors["feeMode"] = "INVALID"
        }

        var feePercent = base.feePercent
        if (has("feePercent")) {
            val v = decimal(form.getValue("feePercent"))
            when {
                v == null -> errors["feePercent"] = "INVALID"
                v.signum() < 0 || v > BigDecimal(100) -> errors["feePercent"] = "OUT_OF_RANGE"
                v.stripTrailingZeros().scale() > 2 -> errors["feePercent"] = "TOO_MANY_DECIMALS"
                else -> feePercent = v.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
            }
        }

        var feeFixed = base.feeFixed
        if (has("feeFixed")) {
            val v = money(form.getValue("feeFixed"))
            if (v.error != null) errors["feeFixed"] = v.error else feeFixed = v.value ?: 0L
        }

        var min = base.minAmount
        if (has("minAmount")) {
            if (form.getValue("minAmount") == null) min = null
            else money(form.getValue("minAmount")).let { if (it.error != null) errors["minAmount"] = it.error else min = it.value }
        }

        var max = base.maxAmount
        if (has("maxAmount")) {
            if (form.getValue("maxAmount") == null) max = null
            else money(form.getValue("maxAmount")).let { if (it.error != null) errors["maxAmount"] = it.error else max = it.value }
        }

        if (!errors.containsKey("minAmount") && !errors.containsKey("maxAmount") && min != null && max != null && max < min) {
            errors["maxAmount"] = "BELOW_MIN"
        }

        var currencies = base.currencies
        if (has("currencies")) {
            when (val v = form.getValue("currencies")) {
                null -> currencies = null
                is JsonArray -> {
                    val parsed = parseCurrencies(v, capabilities)
                    if (parsed.second != null) errors["currencies"] = parsed.second!! else currencies = parsed.first
                }

                else -> errors["currencies"] = "INVALID"
            }
        }

        var testMode = base.testMode
        if (has("testMode")) {
            val v = form.getValue("testMode")
            if (v is Boolean) testMode = v else errors["testMode"] = "INVALID"
        } else if (legacyTestMode == true && !base.testMode) {
            testMode = true
        }

        if (!errors.containsKey("testMode") && testMode && capabilities?.testMode == TestModeSupport.NONE) errors["testMode"] = "UNSUPPORTED"

        if (!errors.containsKey("feeMode") && !errors.containsKey("feePercent") && !errors.containsKey("feeFixed") &&
            feeMode == PaymentFeeMode.BUYER && feePercent == 0L && feeFixed == 0L
        ) errors["feePercent"] = "FEE_REQUIRED"

        if (capabilities != null && capabilities.priceAuthority != PriceAuthority.MARKET) {
            // The gateway sets the price (Tebex): market cannot add a fee or hold the cart to a window of its own.
            if (feeMode == PaymentFeeMode.BUYER && !errors.containsKey("feeMode")) errors["feeMode"] = "EXTERNAL_PRICING"
            if (min != null && !errors.containsKey("minAmount")) errors["minAmount"] = "EXTERNAL_PRICING"
            if (max != null && !errors.containsKey("maxAmount")) errors["maxAmount"] = "EXTERNAL_PRICING"
        }

        return Parsed(State(position, label, description, feeMode, feePercent, feeFixed, min, max, currencies, testMode), errors)
    }

    /** `ids*[]` of the sort request: provider ids (`[a-z0-9-]{2,32}`), 1..[max], no duplicates. Throws [IllegalArgumentException] with the reason code. */
    fun parseProviderIds(array: JsonArray?, max: Int = 200): List<String> {
        require(array != null && !array.isEmpty) { "REQUIRED" }
        require(array.size() <= max) { "TOO_MANY" }

        val ids = array.list.map { v ->
            require(v is String && PROVIDER_ID.matches(v)) { "INVALID" }
            v
        }

        require(ids.toSet().size == ids.size) { "DUPLICATE" }

        return ids
    }

    private val PROVIDER_ID = Regex("^[a-z0-9-]{2,32}$")

    private class MoneyResult(val value: Long?, val error: String?)

    private fun money(raw: Any?): MoneyResult {
        val v = decimal(raw) ?: return MoneyResult(null, "INVALID")

        if (v.signum() < 0) return MoneyResult(null, "OUT_OF_RANGE")
        if (v > MAX_UNITS) return MoneyResult(null, "OUT_OF_RANGE")
        if (v.stripTrailingZeros().scale() > 2) return MoneyResult(null, "TOO_MANY_DECIMALS")

        return MoneyResult(v.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact(), null)
    }

    private fun decimal(raw: Any?): BigDecimal? = when (raw) {
        is Int -> BigDecimal(raw)
        is Long -> BigDecimal(raw)
        is Short -> BigDecimal(raw.toInt())
        is Double -> if (raw.isFinite()) BigDecimal.valueOf(raw) else null
        is Float -> if (raw.isFinite()) BigDecimal(raw.toString()) else null
        is String -> raw.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        else -> null
    }

    private fun asLong(raw: Any?): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Short -> raw.toLong()
        is Double -> if (raw == Math.floor(raw) && raw.isFinite() && Math.abs(raw) < 9.0E15) raw.toLong() else null
        is String -> raw.trim().toLongOrNull()
        else -> null
    }

    private fun parseCurrencies(array: JsonArray, capabilities: PaymentCapabilities?): Pair<List<String>?, String?> {
        if (array.isEmpty) return null to "EMPTY"

        val codes = ArrayList<String>()

        for (item in array) {
            val code = (item as? String)?.trim()?.uppercase() ?: return null to "INVALID"

            if (!Currencies.isSupported(code)) return null to "UNKNOWN_CURRENCY"
            if (code in codes) return null to "DUPLICATE"

            codes.add(code)
        }

        val allowed = capabilities?.currencies?.map { it.uppercase() }?.toSet()

        if (allowed != null && codes.any { it !in allowed }) return null to "UNSUPPORTED_CURRENCY"

        return codes to null
    }
}
