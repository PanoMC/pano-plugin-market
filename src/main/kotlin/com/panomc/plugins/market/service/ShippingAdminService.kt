package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.shipping.Countries
import com.panomc.plugins.market.core.shipping.TrackingUrl
import com.panomc.plugins.market.core.shipping.Zone
import com.panomc.plugins.market.core.shipping.ZoneMatcher
import com.panomc.plugins.market.core.shipping.ZoneRegion
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketShippingCarrierDao
import com.panomc.plugins.market.db.dao.MarketShippingMethodDao
import com.panomc.plugins.market.db.dao.MarketShippingRateDao
import com.panomc.plugins.market.db.dao.MarketShippingZoneDao
import com.panomc.plugins.market.db.dao.MarketThrottleDao
import com.panomc.plugins.market.db.model.MarketShippingCarrier
import com.panomc.plugins.market.db.model.MarketShippingMethod
import com.panomc.plugins.market.db.model.MarketShippingRate
import com.panomc.plugins.market.db.model.MarketShippingZone
import com.panomc.plugins.market.db.model.ShippingRateBasis
import com.panomc.plugins.market.db.model.ShippingRateSource
import com.panomc.plugins.market.db.model.ThrottleScope
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InvalidPassword
import com.panomc.plugins.market.error.InvalidProviderSettings
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.PaymentMethodNotConfigured
import com.panomc.plugins.market.error.ProviderUnavailable
import com.panomc.plugins.market.error.ShippingProviderError
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.provider.ProviderListing
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.ResolvedProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.ReadonlyValue
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.security.SecureRandom

/** Builds the context a shipping provider gets for the settings hooks, `listServices`, `balance` and actions. */
fun interface ShippingContexts {
    fun create(provider: ShippingProvider, settings: ProviderSettings, testMode: Boolean): ShippingContext
}

/**
 * The shipping configuration of the panel (10 sections 4.2, 5.1, 8.2; 04 section 8, MK-131): zones, methods with their
 * rate rows, and the carrier (provider) configuration as the twin of [PaymentMethodService]. All of it is `P:SET`.
 *
 * Calls to a provider never run inside a database transaction. [onQuoteCacheInvalidate] is called with the provider id
 * whenever a carrier's settings changed (the live-rate cache of MK-132 listens to it).
 */
class ShippingAdminService(
    private val db: MarketDb,
    private val clock: Clock,
    private val zones: MarketShippingZoneDao,
    private val methods: MarketShippingMethodDao,
    private val rates: MarketShippingRateDao,
    private val carriers: MarketShippingCarrierDao,
    private val throttles: MarketThrottleDao,
    private val lookup: ProviderLookup,
    private val cipher: SecretCipher,
    private val contexts: ShippingContexts,
    private val site: () -> SiteInfo,
    private val onQuoteCacheInvalidate: (String) -> Unit = {},
    private val token: () -> String = ShippingAdminRules::newInstallToken
) {
    class SaveResult(val message: LocalizedText?)

    class ActionOutcome(val success: Boolean, val message: LocalizedText?)

    // ---- seed

    /**
     * First start (10 section 4.2): when the `manual` carrier row does not exist it is created (enabled, random install
     * token) and, if there is no zone either, the zone "Everywhere" (`["*"]`, position 0). Idempotent and race safe: a
     * second call (or a second node) finds the carrier row (`uq_provider`) and does nothing, so a zone the admin deleted
     * never comes back.
     */
    suspend fun seed() {
        val now = clock.now()

        db.tx { conn ->
            if (carriers.getByProviderId(ManualShippingProvider.ID, conn) != null) return@tx

            val added = carriers.add(
                MarketShippingCarrier(providerId = ManualShippingProvider.ID, enabled = true, webhookToken = token(), createdAt = now, updatedAt = now),
                conn
            )

            if (added != null && zones.getAll(conn).isEmpty()) {
                zones.add(MarketShippingZone(name = SEED_ZONE_NAME, countries = JsonArray().add(ZoneMatcher.WILDCARD).encode(), position = 0, createdAt = now, updatedAt = now), conn)
            }
        }
    }

    // ---- zones

    /** `GET /shipping/zones`: every zone with `rateCount` and `shadowedBy`. */
    suspend fun listZones(): List<JsonObject> {
        seed()

        val (all, counts) = db.tx { conn ->
            val z = zones.getAll(conn)
            val rateCounts = HashMap<Long, Int>()

            methods.getAll(conn).filter { it.deletedAt == null }.forEach { m ->
                rates.getByMethodId(m.id, conn).forEach { r -> rateCounts.merge(r.zoneId, 1, Int::plus) }
            }

            z to rateCounts
        }
        val matcher = all.map { zoneOf(it) }

        return all.map { z ->
            zoneJson(z)
                .put("rateCount", counts[z.id] ?: 0)
                .put("shadowedBy", ZoneMatcher.shadowedBy(matcher, zoneOf(z)))
        }
    }

    /** `POST /shipping/zones`; returns the new id. */
    suspend fun createZone(form: JsonObject): Long {
        val parsed = ShippingAdminRules.parseZone(form, null)
        if (parsed.errors.isNotEmpty()) throw InvalidSettings(parsed.errors)

        val draft = parsed.draft
        val now = clock.now()

        return db.tx { conn ->
            val all = zones.getAll(conn)

            if (all.size >= MAX_ZONES) throw InvalidSettings(mapOf("zones" to "LIMIT_REACHED"))

            zones.add(
                MarketShippingZone(
                    name = draft.name, countries = draft.countries.encode(), regions = draft.regions?.encode(),
                    postalPatterns = draft.postalPatterns?.encode(), position = (all.maxOfOrNull { it.position } ?: -1) + 1,
                    status = draft.status, createdAt = now, updatedAt = now
                ),
                conn
            )
        }
    }

    /** `PUT /shipping/zones/:id`: fields that are absent stay as stored. */
    suspend fun updateZone(id: Long, form: JsonObject) {
        val row = db.tx { conn -> zones.getById(id, conn) } ?: throw NotFound()
        val parsed = ShippingAdminRules.parseZone(form, row)
        if (parsed.errors.isNotEmpty()) throw InvalidSettings(parsed.errors)

        val draft = parsed.draft

        db.tx { conn ->
            zones.update(
                MarketShippingZone(
                    id = row.id, name = draft.name, countries = draft.countries.encode(), regions = draft.regions?.encode(),
                    postalPatterns = draft.postalPatterns?.encode(), position = row.position, status = draft.status,
                    createdAt = row.createdAt, updatedAt = clock.now()
                ),
                conn
            ) || throw NotFound()
        }
    }

    /** `DELETE /shipping/zones/:id`: the zone and its rate rows (orders keep `shippingQuote.zoneId` as history). */
    suspend fun deleteZone(id: Long) {
        db.tx { conn ->
            zones.getById(id, conn) ?: throw NotFound()
            rates.deleteByZoneId(id, conn)
            zones.delete(id, conn)
        }
    }

    /** `POST /shipping/zones/sort`: [ids] must be exactly the set of existing zone ids, else 400 `BAD_REQUEST`. */
    suspend fun sortZones(ids: List<Long>) {
        db.tx { conn ->
            val all = zones.getAll(conn)

            if (ids.toSet().size != ids.size || ids.toSet() != all.map { it.id }.toSet()) throw BadRequest()

            val byId = all.associateBy { it.id }
            val now = clock.now()

            ids.forEachIndexed { index, id ->
                val z = byId.getValue(id)

                if (z.position != index) zones.update(copyOf(z, index, now), conn)
            }
        }
    }

    // ---- methods

    /** `GET /shipping/methods`: the live methods with their `rates[]` (decimal money) and the provider state. */
    suspend fun listMethods(): List<JsonObject> {
        seed()

        val (all, ratesByMethod) = db.tx { conn ->
            val live = methods.getAll(conn).filter { it.deletedAt == null }

            live to live.associate { it.id to rates.getByMethodId(it.id, conn) }
        }

        return all.map { m ->
            methodJson(m)
                .put("providerState", providerStateName(m.providerId))
                .put("rates", JsonArray(ratesByMethod[m.id].orEmpty().map { rateJson(it) }))
        }
    }

    /** `POST /shipping/methods`; returns the new id. */
    suspend fun createMethod(form: JsonObject): Long {
        val context = methodContext(form)
        val parsed = ShippingAdminRules.parseMethod(form, null, context)
        if (parsed.errors.isNotEmpty()) throw InvalidSettings(parsed.errors)

        val draft = parsed.draft
        val now = clock.now()

        return db.tx { conn ->
            val live = methods.getAll(conn).filter { it.deletedAt == null }

            if (live.size >= MAX_METHODS) throw InvalidSettings(mapOf("methods" to "LIMIT_REACHED"))

            val carrierMissing = carriers.getByProviderId(draft.providerId, conn) == null && !builtInId(draft.providerId)
            if (carrierMissing) throw InvalidSettings(mapOf("providerId" to "UNKNOWN_PROVIDER"))

            val existingZones = zones.getAll(conn).map { it.id }.toSet()
            parsed.rateZoneErrors(existingZones).takeIf { it.isNotEmpty() }?.let { throw InvalidSettings(it) }

            val id = methods.add(rowOf(0, draft, (live.maxOfOrNull { it.position } ?: -1) + 1, now, now, null), conn)

            writeRates(id, parsed.rates!!, now, conn)

            id
        }
    }

    /** `PUT /shipping/methods/:id`: absent fields stay; a present `rates` replaces the whole rate set in one transaction. */
    suspend fun updateMethod(id: Long, form: JsonObject) {
        val row = db.tx { conn -> methods.getById(id, conn) }?.takeIf { it.deletedAt == null } ?: throw NotFound()
        val context = methodContext(form, row.providerId)
        val parsed = ShippingAdminRules.parseMethod(form, row, context)
        if (parsed.errors.isNotEmpty()) throw InvalidSettings(parsed.errors)

        val draft = parsed.draft
        val now = clock.now()

        db.tx { conn ->
            val carrierMissing = carriers.getByProviderId(draft.providerId, conn) == null && !builtInId(draft.providerId)
            if (carrierMissing) throw InvalidSettings(mapOf("providerId" to "UNKNOWN_PROVIDER"))

            if (parsed.rates != null) {
                val existingZones = zones.getAll(conn).map { it.id }.toSet()
                parsed.rateZoneErrors(existingZones).takeIf { it.isNotEmpty() }?.let { throw InvalidSettings(it) }
            }

            methods.update(rowOf(row.id, draft, row.position, row.createdAt, now, row.settings), conn) || throw NotFound()

            if (parsed.rates != null) {
                rates.deleteByMethodId(row.id, conn)
                writeRates(row.id, parsed.rates, now, conn)
            }
        }
    }

    /** `DELETE /shipping/methods/:id`: soft delete (`deletedAt`, `status = INACTIVE`), orders keep their method name. */
    suspend fun deleteMethod(id: Long) {
        val now = clock.now()

        db.tx { conn ->
            val row = methods.getById(id, conn)?.takeIf { it.deletedAt == null } ?: throw NotFound()

            methods.update(rowOf(row.id, draftOf(row).copy(status = "INACTIVE"), row.position, row.createdAt, now, row.settings), conn)
            methods.softDelete(id, now, conn)
        }
    }

    /** `POST /shipping/methods/sort`: [ids] must be exactly the set of live method ids, else 400 `BAD_REQUEST`. */
    suspend fun sortMethods(ids: List<Long>) {
        db.tx { conn ->
            val live = methods.getAll(conn).filter { it.deletedAt == null }

            if (ids.toSet().size != ids.size || ids.toSet() != live.map { it.id }.toSet()) throw BadRequest()

            val byId = live.associateBy { it.id }
            val now = clock.now()

            ids.forEachIndexed { index, id ->
                val m = byId.getValue(id)

                if (m.position != index) methods.update(rowOf(m.id, draftOf(m), index, m.createdAt, now, m.settings), conn)
            }
        }
    }

    // ---- carriers: list

    /** `providers[]` of `GET /shipping/carriers`: every registered provider plus every row without one (`UNAVAILABLE`). */
    suspend fun listCarriers(): List<JsonObject> {
        seed()

        val (rows, counts) = db.tx { conn ->
            val r = carriers.getAll(conn).associateBy { it.providerId }
            val count = HashMap<String, Int>()

            methods.getAll(conn).filter { it.deletedAt == null }.forEach { count.merge(it.providerId, 1, Int::plus) }

            r to count
        }
        val seen = HashSet<String>()
        val out = ArrayList<JsonObject>()

        for (listing in lookup.listing(ProviderKind.SHIPPING)) {
            val id = listing.id ?: continue
            if (!seen.add(id)) continue

            out.add(describe(id, listing, rows[id]).put("methodCount", counts[id] ?: 0))
        }

        for (row in rows.values) {
            if (seen.add(row.providerId)) out.add(unavailableEntry(row).put("methodCount", counts[row.providerId] ?: 0))
        }

        return out
    }

    // ---- carriers: save

    /**
     * `POST /shipping/carriers/:id` (`settings{}`, `config{testMode}`): schema validation, `validateSettings`, the write
     * (the row is created on the first save with a 40 hex character install token), `onSettingsSaved`; clears the quote
     * cache of the provider. Throws [NotFound], [ProviderUnavailable], [InvalidProviderSettings], [ShippingProviderError].
     */
    suspend fun saveCarrier(id: String, settingsForm: JsonObject?, configForm: JsonObject?): SaveResult {
        val provider = requireUsable(id).provider
        val codec = SettingsCodec(provider.settingsSchema(), cipher)
        val row = db.tx { conn -> carriers.getByProviderId(id, conn) }
        val stored: JsonObject? = row?.let { storedOf(it) }
        val errors = LinkedHashMap<String, Any?>()
        var newSettings: JsonObject = stored ?: JsonObject()

        if (settingsForm != null) {
            val applied = codec.applyForm(stored, settingsForm)
            applied.issues.forEach { (key, issue) -> errors[key] = issue.text.toJson().map }
            newSettings = applied.settings
        } else {
            codec.encryptLegacyPlaintext(newSettings)?.let { newSettings = it }
        }

        val decrypted = codec.decrypt(newSettings)
        val capabilities = capabilitiesOf(provider, decrypted)
        var testMode = row?.testMode ?: false

        if (configForm != null) {
            configForm.fieldNames().filter { it != "testMode" }.forEach { errors["config.$it"] = "UNKNOWN_PROPERTY" }

            if (configForm.containsKey("testMode")) {
                val v = configForm.getValue("testMode")

                if (v !is Boolean) errors["config.testMode"] = "INVALID"
                else if (v && capabilities.testMode == TestModeSupport.NONE) errors["config.testMode"] = "NOT_SUPPORTED"
                else testMode = v
            }
        }

        if (errors.isNotEmpty()) throw InvalidProviderSettings(errors)

        if (settingsForm != null) {
            val validation = guarded(id) { provider.validateSettings(contexts.create(provider, decrypted, testMode), decrypted) }

            if (!validation.ok) {
                val fieldErrors = LinkedHashMap<String, Any?>()
                validation.fieldErrors.forEach { (key, text) -> fieldErrors[key] = text.toJson().map }
                validation.message?.let { fieldErrors["settings"] = it.toJson().map }
                throw InvalidProviderSettings(fieldErrors)
            }
        }

        write(id, row, newSettings.encode(), row?.enabled ?: false, testMode)
        db.tx { conn -> carriers.getByProviderId(id, conn)?.let { carriers.recordError(it.id, null, clock.now(), conn) } }
        onQuoteCacheInvalidate(id)

        if (settingsForm == null) return SaveResult(null)

        val previous = row?.let { codec.decrypt(storedOf(it)) }
        var message: LocalizedText? = null

        try {
            val result = provider.onSettingsSaved(contexts.create(provider, decrypted, testMode), previous)
            message = applyResult(id, codec, result)
        } catch (e: ProviderException) {
            // The settings are stored; the carrier side failed: shown to the admin and kept on the row.
            recordError(id, e.code.name)
            message = LocalizedText.of(e.adminMessage ?: e.message ?: e.code.name)
        }

        return SaveResult(message)
    }

    // ---- carriers: toggle

    /**
     * `POST /shipping/carriers/:id/toggle`. `manual` cannot be disabled (400 `BAD_REQUEST`). Enabling needs a usable provider
     * (409 `PROVIDER_UNAVAILABLE`), all required settings and a passing `validateSettings`.
     */
    suspend fun toggleCarrier(id: String, enabled: Boolean) {
        if (id == ManualShippingProvider.ID && !enabled) throw BadRequest()

        val provider = requireUsable(id).provider
        val codec = SettingsCodec(provider.settingsSchema(), cipher)
        val row = db.tx { conn -> carriers.getByProviderId(id, conn) }
        val stored = row?.let { storedOf(it) }

        if (enabled) {
            if (codec.missingRequired(stored).isNotEmpty()) throw PaymentMethodNotConfigured()

            val decrypted = codec.decrypt(stored)
            val validation = guarded(id) { provider.validateSettings(contexts.create(provider, decrypted, row?.testMode ?: false), decrypted) }

            if (!validation.ok) {
                val fieldErrors = LinkedHashMap<String, Any?>()
                validation.fieldErrors.forEach { (key, text) -> fieldErrors[key] = text.toJson().map }
                validation.message?.let { fieldErrors["settings"] = it.toJson().map }
                throw InvalidProviderSettings(fieldErrors)
            }
        }

        write(id, row, row?.settings ?: "{}", enabled, row?.testMode ?: false)
    }

    // ---- carriers: reveal

    /** `POST /shipping/carriers/:id/reveal` (11 section 8.3): lock check, password, throttle; only the secret fields, decrypted. */
    suspend fun revealCarrier(id: String, userId: Long, passwordCorrect: suspend () -> Boolean, onFailed: suspend () -> Unit): JsonObject {
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

        val row = db.tx { conn -> carriers.getByProviderId(id, conn) }

        return SettingsCodec(resolved.provider.settingsSchema(), cipher).reveal(row?.let { storedOf(it) })
    }

    // ---- carriers: actions and services

    /** `POST /shipping/carriers/:id/actions/:actionId`. A carrier failure is a 502 `SHIPPING_PROVIDER_ERROR`. */
    suspend fun runCarrierAction(id: String, actionId: String, input: JsonObject): ActionOutcome {
        val provider = requireUsable(id).provider
        val schema = provider.settingsSchema()
        val action = schema.actions.firstOrNull { it.id == actionId } ?: throw NotFound()
        val row = db.tx { conn -> carriers.getByProviderId(id, conn) }

        if (action.requiresSavedSettings && row == null) throw InvalidState("SETTINGS_NOT_SAVED")

        val codec = SettingsCodec(schema, cipher)
        val decrypted = codec.decrypt(row?.let { storedOf(it) })

        val result = try {
            provider.runAction(contexts.create(provider, decrypted, row?.testMode ?: false), actionId, input)
        } catch (e: ProviderException) {
            recordError(id, e.code.name)
            throw ShippingProviderError(e.code.name)
        } catch (e: RuntimeException) {
            recordError(id, INTERNAL)
            throw ShippingProviderError(INTERNAL)
        }

        return when (result) {
            is ActionResult.None -> ActionOutcome(true, null)
            is ActionResult.Message -> ActionOutcome(result.success, result.text)
            is ActionResult.SettingsPatch -> ActionOutcome(true, applyResult(id, codec, result))
            is ActionResult.CatalogImport -> ActionOutcome(false, null)
        }
    }

    /** `GET /shipping/carriers/:id/services`: `listServices` with a 10 s timeout; any failure is 502 `SHIPPING_PROVIDER_ERROR`. */
    suspend fun carrierServices(id: String): List<JsonObject> {
        val provider = requireUsable(id).provider
        val row = db.tx { conn -> carriers.getByProviderId(id, conn) }
        val decrypted = SettingsCodec(provider.settingsSchema(), cipher).decrypt(row?.let { storedOf(it) })
        val ctx = contexts.create(provider, decrypted, row?.testMode ?: false)

        val services = try {
            withTimeoutOrNull(SERVICES_TIMEOUT_MS) { provider.listServices(ctx) }
        } catch (e: ProviderException) {
            recordError(id, e.code.name)
            throw ShippingProviderError(e.code.name)
        } catch (e: RuntimeException) {
            recordError(id, INTERNAL)
            throw ShippingProviderError(INTERNAL)
        }

        if (services == null) {
            recordError(id, TIMEOUT)
            throw ShippingProviderError(TIMEOUT)
        }

        return services.map { s ->
            JsonObject().put("code", s.code).put("name", s.name).put("carrierName", s.carrierName).put("international", s.international)
        }
    }

    // ---- internals

    private fun builtInId(id: String): Boolean = id == ManualShippingProvider.ID

    private fun storedOf(row: MarketShippingCarrier): JsonObject = try {
        JsonObject(row.settings ?: "{}")
    } catch (e: Exception) {
        JsonObject()
    }

    private suspend fun requireUsable(id: String): ResolvedProvider<ShippingProvider> {
        lookup.shipping(id)?.let { return it }

        val state = lookup.state(ProviderKind.SHIPPING, id)
        val hasRow = db.tx { conn -> carriers.getByProviderId(id, conn) } != null

        if (state.availability == ProviderAvailability.MISSING && !hasRow) throw NotFound()

        throw ProviderUnavailable(PaymentMethodStates.of(state.availability))
    }

    private fun providerStateName(id: String): String {
        val state = lookup.state(ProviderKind.SHIPPING, id)

        return if (state.usable) PaymentMethodStates.ACTIVE else PaymentMethodStates.of(state.availability)
    }

    /** What the method rules need to know about the provider: whether it is usable and may quote. */
    private fun methodContext(form: JsonObject, currentProvider: String? = null): ShippingAdminRules.MethodContext {
        val providerId = (form.getValue("providerId") as? String) ?: currentProvider ?: ManualShippingProvider.ID
        val resolved = lookup.shipping(providerId)
        val caps = resolved?.let { r ->
            try {
                r.provider.capabilities(SettingsCodec(r.provider.settingsSchema(), cipher).decrypt(null))
            } catch (e: Exception) {
                null
            }
        }

        return ShippingAdminRules.MethodContext(providerUsable = resolved != null, canQuote = caps?.rateQuote ?: false)
    }

    private fun capabilitiesOf(provider: ShippingProvider, settings: ProviderSettings): ShippingCapabilities = try {
        provider.capabilities(settings)
    } catch (e: Exception) {
        ShippingCapabilities()
    }

    private suspend fun <T> guarded(id: String, block: suspend () -> T): T = try {
        block()
    } catch (e: ProviderException) {
        recordError(id, e.code.name)
        throw ShippingProviderError(e.code.name)
    } catch (e: RuntimeException) {
        recordError(id, INTERNAL)
        throw ShippingProviderError(INTERNAL)
    }

    private suspend fun recordError(id: String, code: String) {
        db.tx { conn -> carriers.getByProviderId(id, conn)?.let { carriers.recordError(it.id, code, clock.now(), conn) } }
    }

    /** Creates the row on the first write (a lost race falls back to an update) or updates it. */
    private suspend fun write(id: String, row: MarketShippingCarrier?, settings: String, enabled: Boolean, testMode: Boolean) {
        val now = clock.now()

        db.tx { conn ->
            var current = row ?: carriers.getByProviderId(id, conn)

            if (current == null) {
                val added = carriers.add(MarketShippingCarrier(providerId = id, enabled = enabled, settings = settings, testMode = testMode, webhookToken = token(), createdAt = now, updatedAt = now), conn)

                if (added != null) return@tx

                current = carriers.getByProviderId(id, conn) ?: throw NotFound()
            }

            carriers.update(
                MarketShippingCarrier(
                    id = current.id, providerId = id, enabled = enabled, settings = settings, testMode = testMode, webhookToken = current.webhookToken,
                    lastInboundAt = current.lastInboundAt, lastError = current.lastError, lastErrorAt = current.lastErrorAt,
                    createdAt = current.createdAt, updatedAt = now
                ),
                conn
            )
        }
    }

    private suspend fun applyResult(id: String, codec: SettingsCodec, result: ActionResult): LocalizedText? = when (result) {
        is ActionResult.Message -> result.text
        is ActionResult.SettingsPatch -> {
            val row = db.tx { conn -> carriers.getByProviderId(id, conn) }
            val patched = codec.applyPatch(row?.let { storedOf(it) }, result.values)

            write(id, row, patched.settings.encode(), row?.enabled ?: false, row?.testMode ?: false)
            result.text
        }

        else -> null
    }

    private suspend fun writeRates(methodId: Long, parsed: List<ShippingAdminRules.RateDraft>, now: Long, conn: io.vertx.sqlclient.SqlConnection) {
        val positions = HashMap<Long, Int>()

        for (r in parsed) {
            val position = positions.merge(r.zoneId, 1, Int::plus)!! - 1

            rates.add(
                MarketShippingRate(
                    methodId = methodId, zoneId = r.zoneId, basis = r.basis, rangeFrom = r.rangeFrom, rangeTo = r.rangeTo,
                    price = r.price, perUnitPrice = r.perUnitPrice, position = position, createdAt = now, updatedAt = now
                ),
                conn
            )
        }
    }

    private fun copyOf(z: MarketShippingZone, position: Int, now: Long) = MarketShippingZone(
        id = z.id, name = z.name, countries = z.countries, regions = z.regions, postalPatterns = z.postalPatterns,
        position = position, status = z.status, createdAt = z.createdAt, updatedAt = now
    )

    private fun draftOf(m: MarketShippingMethod) = ShippingAdminRules.MethodDraft(
        m.name, m.description, m.providerId, m.serviceCode, m.rateSource, m.freeShippingThreshold, m.handlingFee, m.vatPercent,
        m.minDeliveryDays, m.maxDeliveryDays, m.maxWeightGrams, m.carrierName, m.trackingUrlTemplate, m.status
    )

    private fun rowOf(id: Long, d: ShippingAdminRules.MethodDraft, position: Int, createdAt: Long, now: Long, settings: String?) = MarketShippingMethod(
        id = id, name = d.name, description = d.description, providerId = d.providerId, serviceCode = d.serviceCode, rateSource = d.rateSource,
        freeShippingThreshold = d.freeShippingThreshold, handlingFee = d.handlingFee, vatPercent = d.vatPercent,
        minDeliveryDays = d.minDeliveryDays, maxDeliveryDays = d.maxDeliveryDays, maxWeightGrams = d.maxWeightGrams,
        carrierName = d.carrierName, trackingUrlTemplate = d.trackingUrlTemplate, settings = settings, position = position,
        status = d.status, createdAt = createdAt, updatedAt = now
    )

    // ---- JSON

    private fun zoneOf(z: MarketShippingZone): Zone = Zone(
        id = z.id,
        countries = jsonList(z.countries).map { it.toString() },
        regions = z.regions?.let { json ->
            jsonList(json).mapNotNull { e ->
                (e as? JsonObject ?: (e as? Map<*, *>)?.let { JsonObject(it as Map<String, Any?>) })?.let { o -> ZoneRegion(o.getString("country"), (o.getJsonArray("states") ?: JsonArray()).map { it.toString() }) }
            }
        },
        postalPatterns = z.postalPatterns?.let { json -> jsonList(json).map { it.toString() } },
        position = z.position,
        active = z.status == "ACTIVE"
    )

    private fun jsonList(text: String): List<Any?> = try {
        JsonArray(text).list
    } catch (e: Exception) {
        emptyList()
    }

    private fun zoneJson(z: MarketShippingZone): JsonObject = JsonObject()
        .put("id", z.id)
        .put("name", z.name)
        .put("countries", runCatching { JsonArray(z.countries) }.getOrDefault(JsonArray()))
        .put("regions", z.regions?.let { runCatching { JsonArray(it) }.getOrNull() })
        .put("postalPatterns", z.postalPatterns?.let { runCatching { JsonArray(it) }.getOrNull() })
        .put("position", z.position)
        .put("status", z.status)
        .put("createdAt", z.createdAt)
        .put("updatedAt", z.updatedAt)

    private fun methodJson(m: MarketShippingMethod): JsonObject = JsonObject()
        .put("id", m.id)
        .put("name", m.name)
        .put("description", m.description)
        .put("providerId", m.providerId)
        .put("serviceCode", m.serviceCode)
        .put("rateSource", m.rateSource.name)
        .put("freeShippingThreshold", m.freeShippingThreshold?.let { ShippingAdminRules.decimal(it) })
        .put("handlingFee", ShippingAdminRules.decimal(m.handlingFee))
        .put("vatPercent", m.vatPercent?.let { ShippingAdminRules.decimal(it) })
        .put("minDeliveryDays", m.minDeliveryDays)
        .put("maxDeliveryDays", m.maxDeliveryDays)
        .put("maxWeightGrams", m.maxWeightGrams)
        .put("carrierName", m.carrierName)
        .put("trackingUrlTemplate", m.trackingUrlTemplate)
        .put("position", m.position)
        .put("status", m.status)
        .put("createdAt", m.createdAt)
        .put("updatedAt", m.updatedAt)

    private fun rateJson(r: MarketShippingRate): JsonObject {
        val money = r.basis == ShippingRateBasis.AMOUNT

        return JsonObject()
            .put("id", r.id)
            .put("zoneId", r.zoneId)
            .put("basis", r.basis.name)
            .put("rangeFrom", if (money) ShippingAdminRules.decimal(r.rangeFrom) else r.rangeFrom)
            .put("rangeTo", r.rangeTo?.let { if (money) ShippingAdminRules.decimal(it) else it })
            .put("price", ShippingAdminRules.decimal(r.price))
            .put("perUnitPrice", ShippingAdminRules.decimal(r.perUnitPrice))
    }

    private fun unavailableEntry(row: MarketShippingCarrier): JsonObject = JsonObject()
        .put("id", row.providerId)
        .put("state", PaymentMethodStates.UNAVAILABLE)
        .put("availability", ProviderAvailability.MISSING.name)
        .put("pluginId", null as String?)
        .put("spiVersion", null as Int?)
        .put("descriptor", null as JsonObject?)
        .put("schema", null as JsonObject?)
        .put("capabilities", null as JsonObject?)
        .put("config", JsonObject().put("enabled", row.enabled).put("testMode", row.testMode).put("readOnly", true))
        .put("settings", JsonObject())
        .put("webhookUrl", null as String?)
        .put("lastInboundAt", row.lastInboundAt)
        .put("lastError", row.lastError)
        .put("lastErrorAt", row.lastErrorAt)
        .put("balance", null as JsonObject?)

    private suspend fun describe(id: String, listing: ProviderListing, row: MarketShippingCarrier?): JsonObject {
        val provider = listing.resolved?.provider as? ShippingProvider
        val state = listing.state
        val config = JsonObject().put("enabled", row?.enabled ?: false).put("testMode", row?.testMode ?: false)
        val out = JsonObject().put("id", id).put("availability", state.availability.name).put("pluginId", state.pluginId ?: listing.resolved?.pluginId)

        if (provider == null || !state.usable) {
            return out
                .put("state", PaymentMethodStates.of(state.availability))
                .put("spiVersion", state.spiVersion)
                .put("detail", state.detail)
                .put("descriptor", null as JsonObject?)
                .put("schema", null as JsonObject?)
                .put("capabilities", null as JsonObject?)
                .put("config", config.put("readOnly", true))
                .put("settings", JsonObject())
                .put("webhookUrl", null as String?)
                .put("lastInboundAt", row?.lastInboundAt)
                .put("lastError", row?.lastError)
                .put("lastErrorAt", row?.lastErrorAt)
                .put("balance", null as JsonObject?)
        }

        return try {
            val schema = provider.settingsSchema()
            val codec = SettingsCodec(schema, cipher)
            val stored = row?.let { storedOf(it) }
            val decrypted = codec.decrypt(stored)
            val ctx = contexts.create(provider, decrypted, row?.testMode ?: false)
            val capabilities = capabilitiesOf(provider, decrypted)
            val missing = codec.missingRequired(stored)
            val info = site()
            val webhookUrl = if (row != null && (capabilities.trackingPush || capabilities.webhookSetup != WebhookSetup.NONE)) {
                "${info.baseUrl}/api/market/shipping/$id/webhook/${row.webhookToken}"
            } else null

            out
                .put("state", PaymentMethodStates.stateOf(state.availability, row?.enabled ?: false, missing.isNotEmpty()))
                .put("spiVersion", state.spiVersion ?: listing.resolved.spiVersion)
                .put("descriptor", descriptorJson(provider))
                .put("schema", schema.toJson { value -> resolveReadonly(ctx, info, value, webhookUrl) })
                .put("capabilities", capabilitiesJson(capabilities))
                .put("config", config.put("readOnly", false))
                .put("settings", codec.mask(stored))
                .put("webhookUrl", webhookUrl)
                .put("lastInboundAt", row?.lastInboundAt)
                .put("lastError", row?.lastError)
                .put("lastErrorAt", row?.lastErrorAt)
                .put("balance", if (capabilities.prepaidBalance) balanceJson(provider, ctx) else null)
        } catch (e: Exception) {
            out
                .put("state", PaymentMethodStates.UNAVAILABLE)
                .put("availability", ProviderAvailability.INVALID.name)
                .put("spiVersion", state.spiVersion)
                .put("detail", "the provider threw while it was described")
                .put("descriptor", null as JsonObject?)
                .put("schema", null as JsonObject?)
                .put("capabilities", null as JsonObject?)
                .put("config", config.put("readOnly", true))
                .put("settings", JsonObject())
                .put("webhookUrl", null as String?)
                .put("lastInboundAt", row?.lastInboundAt)
                .put("lastError", row?.lastError)
                .put("lastErrorAt", row?.lastErrorAt)
                .put("balance", null as JsonObject?)
        }
    }

    /** `provider.balance`, 3 s timeout, `null` on any error. */
    private suspend fun balanceJson(provider: ShippingProvider, ctx: ShippingContext): JsonObject? = try {
        withTimeoutOrNull(BALANCE_TIMEOUT_MS) { provider.balance(ctx) }
            ?.let { JsonObject().put("amount", it.toDecimalString()).put("currency", it.currency) }
    } catch (e: Exception) {
        null
    }

    private fun resolveReadonly(ctx: ShippingContext, info: SiteInfo, value: ReadonlyValue, webhookUrl: String?): String? = when (value) {
        is ReadonlyValue.WebhookUrl -> webhookUrl ?: ctx.urls.webhook(value.channel)
        is ReadonlyValue.ReturnUrlPrefix -> info.baseUrl
        is ReadonlyValue.SiteBaseUrl -> info.baseUrl
        is ReadonlyValue.Static -> null
    }

    private fun descriptorJson(provider: ShippingProvider): JsonObject {
        val d = provider.descriptor

        return JsonObject()
            .put("name", d.displayName.toJson())
            .put("description", d.description.toJson())
            .put("icon", d.icon)
            .put("color", d.color)
            .put("region", d.region)
            .put("docsUrl", d.docsUrl)
            .put("verification", d.verification.name)
    }

    companion object {
        const val MAX_ZONES = 100
        const val MAX_METHODS = 100
        const val SEED_ZONE_NAME = "Everywhere"
        private const val INTERNAL = "INTERNAL"
        private const val TIMEOUT = "TIMEOUT"
        private const val REVEAL_THRESHOLD = 5
        private const val REVEAL_WINDOW_MS = 10L * 60_000
        private const val REVEAL_LOCK_MS = 10L * 60_000
        private const val SERVICES_TIMEOUT_MS = 10_000L
        private const val BALANCE_TIMEOUT_MS = 3_000L

        /** The `capabilities{}` object of the wire format (03 section 5). */
        fun capabilitiesJson(c: ShippingCapabilities): JsonObject = JsonObject()
            .put("rateQuote", c.rateQuote)
            .put("createShipment", c.createShipment)
            .put("labelFormats", JsonArray(c.labelFormats.map { it.name }.sorted()))
            .put("cancel", c.cancel)
            .put("trackingPull", c.trackingPull)
            .put("trackBatchSize", c.trackBatchSize)
            .put("trackingPush", c.trackingPush)
            .put("webhookSigned", c.webhookSigned)
            .put("webhookSetup", c.webhookSetup.name)
            .put("externalTracking", c.externalTracking)
            .put("addressResolve", c.addressResolve)
            .put("prepaidBalance", c.prepaidBalance)
            .put("returns", c.returns)
            .put("originCountries", c.originCountries?.let { JsonArray(it.sorted()) })
            .put("destinationCountries", c.destinationCountries?.let { JsonArray(it.sorted()) })
            .put("requiresDimensions", c.requiresDimensions)
            .put("requiresCustomsData", c.requiresCustomsData)
            .put("requiredAddressFields", JsonArray(c.requiredAddressFields.map { it.name }.sorted()))
            .put("maxParcels", c.maxParcels)
            .put("quoteCacheSeconds", c.quoteCacheSeconds)
            .put("testMode", c.testMode.name)
    }
}

/**
 * Validation of zones, methods and rate sets (10 sections 4.2 and 5.1), pure so it is tested without a database. Wire
 * format: money and the VAT percent are decimal numbers, stored x100 (the percent as basis points). A bad key is reported
 * under its own name (`rates[3].price` for a rate row); nothing is stored when any error exists.
 */
object ShippingAdminRules {
    // ---- limits (10 sections 4.2 and 5.1)
    const val MAX_NAME = 128
    const val MAX_DESCRIPTION = 512
    const val MAX_SERVICE_CODE = 128
    const val MAX_CARRIER_NAME = 128
    const val MAX_STATES = 200
    const val MAX_STATE_LENGTH = 100
    const val MAX_PATTERNS = 200
    const val MAX_RATES = 200
    const val MAX_WEIGHT_GRAMS = 2_000_000_000L
    private const val MAX_QUANTITY = 1_000_000L
    private val MAX_UNITS = BigDecimal("1000000000")

    private val PREFIX = Regex("^[A-Za-z0-9]{1,10}\\*$")
    private val RANGE = Regex("^\\d{1,10}-\\d{1,10}$")
    private val EXACT = Regex("^[A-Za-z0-9]{1,16}$")

    fun decimal(x100: Long): BigDecimal = BigDecimal.valueOf(x100, 2)

    /** 40 hex characters from [SecureRandom]. */
    fun newInstallToken(): String {
        val bytes = ByteArray(20).also { SecureRandom().nextBytes(it) }

        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---- zones

    class ZoneDraft(val name: String, val countries: JsonArray, val regions: JsonArray?, val postalPatterns: JsonArray?, val status: String)

    class ParsedZone(val draft: ZoneDraft, val errors: Map<String, String>)

    /** Lays [form] over [base] (absent keys keep the stored value). */
    fun parseZone(form: JsonObject, base: MarketShippingZone?): ParsedZone {
        val errors = LinkedHashMap<String, String>()

        var name = base?.name ?: ""
        if (form.containsKey("name") || base == null) {
            val v = form.getValue("name")

            if (v is String) {
                val t = v.trim()

                if (t.isEmpty()) errors["name"] = "REQUIRED" else if (t.length > MAX_NAME) errors["name"] = "TOO_LONG" else name = t
            } else errors["name"] = if (v == null) "REQUIRED" else "INVALID"
        }

        var countries: List<String> = base?.let { stringList(it.countries) } ?: emptyList()
        if (form.containsKey("countries") || base == null) {
            val raw = form.getValue("countries")

            if (raw is JsonArray) {
                val list = raw.list.map { (it as? String)?.trim()?.uppercase() }
                val code = when {
                    list.isEmpty() -> "REQUIRED"
                    list.any { it == null || (it != ZoneMatcher.WILDCARD && !Countries.isValid(it)) } -> "INVALID_COUNTRY"
                    ZoneMatcher.WILDCARD in list && list.toSet().size > 1 -> "WILDCARD_EXCLUSIVE"
                    else -> null
                }

                if (code != null) errors["countries"] = code else countries = list.filterNotNull().distinct()
            } else errors["countries"] = if (raw == null) "REQUIRED" else "INVALID"
        }

        var regions: JsonArray? = base?.regions?.let { runCatching { JsonArray(it) }.getOrNull() }
        if (form.containsKey("regions")) {
            val raw = form.getValue("regions")

            if (raw == null) regions = null
            else if (raw is JsonArray) {
                val parsed = parseRegions(raw, countries, errors)

                if (parsed != null) regions = parsed.takeIf { !it.isEmpty }
            } else errors["regions"] = "INVALID"
        } else if (regions != null && "countries" !in errors) {
            // a changed country list must not leave a region of a country that is gone
            val ok = regions.list.all { e -> asObject(e)?.getString("country") in countries } && ZoneMatcher.WILDCARD !in countries
            if (!ok && form.containsKey("countries")) errors["regions"] = "INVALID_REGION_COUNTRY"
        }

        var patterns: JsonArray? = base?.postalPatterns?.let { runCatching { JsonArray(it) }.getOrNull() }
        if (form.containsKey("postalPatterns")) {
            val raw = form.getValue("postalPatterns")

            if (raw == null) patterns = null
            else if (raw is JsonArray) {
                if (raw.size() > MAX_PATTERNS) errors["postalPatterns"] = "TOO_MANY"
                else {
                    var ok = true

                    raw.list.forEachIndexed { i, p ->
                        val code = patternError(p)

                        if (code != null) {
                            errors["postalPatterns[$i]"] = code
                            ok = false
                        }
                    }

                    if (ok) patterns = JsonArray(raw.list.map { (it as String).trim() }).takeIf { !it.isEmpty }
                }
            } else errors["postalPatterns"] = "INVALID"
        }

        var status = base?.status ?: "ACTIVE"
        if (form.containsKey("status")) {
            val v = form.getValue("status")

            if (v == "ACTIVE" || v == "INACTIVE") status = v as String else errors["status"] = "INVALID"
        }

        return ParsedZone(ZoneDraft(name, JsonArray(countries), regions, patterns, status), errors)
    }

    private fun parseRegions(raw: JsonArray, countries: List<String>, errors: MutableMap<String, String>): JsonArray? {
        val out = JsonArray()
        var ok = true

        raw.list.forEachIndexed { i, e ->
            val o = asObject(e)
            val country = (o?.getValue("country") as? String)?.trim()?.uppercase()
            val states = o?.getValue("states") as? JsonArray

            when {
                o == null || country == null || states == null -> {
                    errors["regions[$i]"] = "INVALID"
                    ok = false
                }

                ZoneMatcher.WILDCARD in countries || country !in countries -> {
                    errors["regions[$i].country"] = "INVALID_REGION_COUNTRY"
                    ok = false
                }

                states.isEmpty || states.size() > MAX_STATES -> {
                    errors["regions[$i].states"] = if (states.isEmpty) "REQUIRED" else "TOO_MANY"
                    ok = false
                }

                states.list.any { it !is String || it.trim().isEmpty() || it.trim().length > MAX_STATE_LENGTH } -> {
                    errors["regions[$i].states"] = "INVALID"
                    ok = false
                }

                else -> out.add(JsonObject().put("country", country).put("states", JsonArray(states.list.map { (it as String).trim() })))
            }
        }

        return if (ok) out else null
    }

    private fun patternError(p: Any?): String? {
        val s = (p as? String)?.trim() ?: return "INVALID"

        return when {
            PREFIX.matches(s) || EXACT.matches(s) -> null
            RANGE.matches(s) -> {
                val (from, to) = s.split('-')

                if (from.length != to.length) "RANGE_LENGTH" else if (from.toLong() > to.toLong()) "RANGE_ORDER" else null
            }

            else -> "INVALID_PATTERN"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asObject(e: Any?): JsonObject? = when (e) {
        is JsonObject -> e
        is Map<*, *> -> JsonObject(e as Map<String, Any?>)
        else -> null
    }

    private fun stringList(json: String): List<String> = runCatching { JsonArray(json).list.map { it.toString() } }.getOrDefault(emptyList())

    // ---- methods

    /** What the rules need about the method's provider. */
    class MethodContext(val providerUsable: Boolean, val canQuote: Boolean)

    data class MethodDraft(
        val name: String,
        val description: String?,
        val providerId: String,
        val serviceCode: String?,
        val rateSource: ShippingRateSource,
        val freeShippingThreshold: Long?,
        val handlingFee: Long,
        val vatPercent: Long?,
        val minDeliveryDays: Int?,
        val maxDeliveryDays: Int?,
        val maxWeightGrams: Int?,
        val carrierName: String?,
        val trackingUrlTemplate: String?,
        val status: String
    )

    class RateDraft(val zoneId: Long, val basis: ShippingRateBasis, val rangeFrom: Long, val rangeTo: Long?, val price: Long, val perUnitPrice: Long)

    /** [rates] is `null` when the form carried no `rates` key (a PUT that keeps the stored set). */
    class ParsedMethod(val draft: MethodDraft, val rates: List<RateDraft>?, val errors: Map<String, String>) {
        /** `rates[i].zoneId` codes for rows that name a zone which does not exist. */
        fun rateZoneErrors(existing: Set<Long>): Map<String, String> {
            val out = LinkedHashMap<String, String>()

            rates?.forEachIndexed { i, r -> if (r.zoneId !in existing) out["rates[$i].zoneId"] = "NOT_FOUND" }

            return out
        }
    }

    fun parseMethod(form: JsonObject, base: MarketShippingMethod?, context: MethodContext): ParsedMethod {
        val errors = LinkedHashMap<String, String>()
        fun has(key: String) = form.containsKey(key)

        var name = base?.name ?: ""
        if (has("name") || base == null) {
            val v = form.getValue("name")

            if (v is String) {
                val t = v.trim()

                if (t.isEmpty()) errors["name"] = "REQUIRED" else if (t.length > MAX_NAME) errors["name"] = "TOO_LONG" else name = t
            } else errors["name"] = if (v == null) "REQUIRED" else "INVALID"
        }

        val description = optionalText(form, "description", base?.description, MAX_DESCRIPTION, errors)

        var providerId = base?.providerId ?: "manual"
        if (has("providerId")) {
            val v = form.getValue("providerId")

            if (v is String && v.trim().isNotEmpty() && v.trim().length <= 64) providerId = v.trim() else errors["providerId"] = "INVALID"
        }

        var serviceCode = optionalText(form, "serviceCode", base?.serviceCode, MAX_SERVICE_CODE, errors)
        if (providerId == "manual") serviceCode = null

        var rateSource = base?.rateSource ?: ShippingRateSource.RULES
        if (has("rateSource")) {
            val v = (form.getValue("rateSource") as? String)?.let { s -> ShippingRateSource.entries.firstOrNull { it.name == s } }

            if (v != null) rateSource = v else errors["rateSource"] = "INVALID"
        }
        // The carrier values need a provider that can quote; an unavailable provider is accepted as stored (not offered).
        if (rateSource != ShippingRateSource.RULES && context.providerUsable && !context.canQuote && "rateSource" !in errors) {
            errors["rateSource"] = "RATE_QUOTE_NOT_SUPPORTED"
        }

        var free = base?.freeShippingThreshold
        if (has("freeShippingThreshold")) {
            val v = form.getValue("freeShippingThreshold")

            if (v == null) free = null
            else {
                val minor = money(v)

                if (minor != null && minor > 0) free = minor else errors["freeShippingThreshold"] = if (minor == null) "INVALID" else "MUST_BE_POSITIVE"
            }
        }

        var handling = base?.handlingFee ?: 0L
        if (has("handlingFee")) {
            val minor = form.getValue("handlingFee")?.let { money(it) }

            if (minor != null && minor >= 0) handling = minor else errors["handlingFee"] = if (minor == null) "INVALID" else "NEGATIVE"
        }

        var vat = base?.vatPercent
        if (has("vatPercent")) {
            val v = form.getValue("vatPercent")

            if (v == null) vat = null
            else {
                val bp = money(v)

                if (bp != null && bp in 0..10_000) vat = bp else errors["vatPercent"] = "OUT_OF_RANGE"
            }
        }

        var minDays = base?.minDeliveryDays
        if (has("minDeliveryDays")) minDays = optionalInt(form, "minDeliveryDays", 0, 365, minDays, errors)

        var maxDays = base?.maxDeliveryDays
        if (has("maxDeliveryDays")) maxDays = optionalInt(form, "maxDeliveryDays", 0, 365, maxDays, errors)

        if (minDays != null && maxDays != null && minDays > maxDays && "minDeliveryDays" !in errors && "maxDeliveryDays" !in errors) {
            errors["minDeliveryDays"] = "MIN_GREATER_THAN_MAX"
        }

        var maxWeight = base?.maxWeightGrams
        if (has("maxWeightGrams")) maxWeight = optionalInt(form, "maxWeightGrams", 1, MAX_WEIGHT_GRAMS.toInt(), maxWeight, errors)

        val carrierName = optionalText(form, "carrierName", base?.carrierName, MAX_CARRIER_NAME, errors)

        var template = base?.trackingUrlTemplate
        if (has("trackingUrlTemplate")) {
            val v = form.getValue("trackingUrlTemplate")

            when {
                v == null -> template = null
                v !is String -> errors["trackingUrlTemplate"] = "INVALID"
                v.trim().isEmpty() -> template = null
                v.trim().length > TrackingUrl.MAX_TEMPLATE_LENGTH -> errors["trackingUrlTemplate"] = "TOO_LONG"
                !TrackingUrl.isValidTemplate(v.trim()) -> errors["trackingUrlTemplate"] = "INVALID_TEMPLATE"
                else -> template = v.trim()
            }
        }

        var status = base?.status ?: "ACTIVE"
        if (has("status")) {
            val v = form.getValue("status")

            if (v == "ACTIVE" || v == "INACTIVE") status = v as String else errors["status"] = "INVALID"
        }

        var rates: List<RateDraft>? = null
        if (has("rates")) {
            val raw = form.getValue("rates")

            if (raw == null) rates = emptyList()
            else if (raw is JsonArray) rates = parseRates(raw, errors)
            else errors["rates"] = "INVALID"
        }

        val draft = MethodDraft(name, description, providerId, serviceCode, rateSource, free, handling, vat, minDays, maxDays, maxWeight, carrierName, template, status)

        return ParsedMethod(draft, rates, errors)
    }

    /** Row validation, then the rate set rules per zone: `RATE_UNREACHABLE` after a `FLAT` row, `RATE_OVERLAP` for two rows of one basis. */
    fun parseRates(raw: JsonArray, errors: MutableMap<String, String>): List<RateDraft>? {
        if (raw.size() > MAX_RATES) {
            errors["rates"] = "TOO_MANY"

            return null
        }

        val out = ArrayList<RateDraft>()
        var rowsOk = true

        raw.list.forEachIndexed { i, e ->
            val o = asObject(e)

            if (o == null) {
                errors["rates[$i]"] = "INVALID"
                rowsOk = false

                return@forEachIndexed
            }

            val row = parseRate(o, i, errors)

            if (row != null) out.add(row) else rowsOk = false
        }

        if (!rowsOk) return null

        val byZone = LinkedHashMap<Long, MutableList<Pair<Int, RateDraft>>>()
        out.forEachIndexed { i, r -> byZone.getOrPut(r.zoneId) { ArrayList() }.add(i to r) }

        for ((_, rows) in byZone) {
            rows.forEachIndexed { j, (index, row) ->
                val earlier = rows.subList(0, j).map { it.second }

                if (earlier.any { it.basis == ShippingRateBasis.FLAT }) errors["rates[$index]"] = "RATE_UNREACHABLE"
                else if (earlier.any { it.basis == row.basis && overlaps(it, row) }) errors["rates[$index]"] = "RATE_OVERLAP"
            }
        }

        return if (errors.keys.any { it.startsWith("rates[") }) null else out
    }

    private fun overlaps(a: RateDraft, b: RateDraft): Boolean {
        val aTo = a.rangeTo ?: Long.MAX_VALUE
        val bTo = b.rangeTo ?: Long.MAX_VALUE

        return a.rangeFrom <= bTo && b.rangeFrom <= aTo
    }

    private fun parseRate(o: JsonObject, i: Int, errors: MutableMap<String, String>): RateDraft? {
        val before = errors.size
        fun err(key: String, code: String) {
            errors["rates[$i].$key"] = code
        }

        val zoneId = (o.getValue("zoneId") as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toLong()
        if (zoneId == null || zoneId <= 0) err("zoneId", "INVALID")

        val basis = (o.getValue("basis") as? String)?.let { s -> ShippingRateBasis.entries.firstOrNull { it.name == s } }
        if (basis == null) err("basis", "INVALID")

        val money = basis == ShippingRateBasis.AMOUNT
        val flat = basis == ShippingRateBasis.FLAT

        var from = 0L
        var to: Long? = null

        if (!flat && basis != null) {
            val rawFrom = o.getValue("rangeFrom")
            val f = if (rawFrom == null) 0L else rangeValue(rawFrom, basis)

            if (f == null || f < 0) err("rangeFrom", if (f == null) "INVALID" else "NEGATIVE") else from = f

            val rawTo = o.getValue("rangeTo")

            if (rawTo != null) {
                val t = rangeValue(rawTo, basis)

                if (t == null) err("rangeTo", "INVALID") else if (t < from) err("rangeTo", "BEFORE_FROM") else to = t
            }
        }

        val price = o.getValue("price")?.let { money(it) }
        if (price == null || price < 0 || price > MAX_UNITS.movePointRight(2).toLong()) err("price", if (price == null) "INVALID" else "OUT_OF_RANGE")

        val rawPer = o.getValue("perUnitPrice")
        val per = if (rawPer == null) 0L else money(rawPer)

        if (per == null || per < 0 || per > MAX_UNITS.movePointRight(2).toLong()) err("perUnitPrice", if (per == null) "INVALID" else "OUT_OF_RANGE")
        else if (per != 0L && (flat || money)) err("perUnitPrice", "MUST_BE_ZERO")

        if (errors.size != before) return null

        return RateDraft(zoneId!!, basis!!, from, to, price!!, per!!)
    }

    /** Grams (`WEIGHT`), x100 money (`AMOUNT`) or units (`QUANTITY`); `null` when malformed. */
    private fun rangeValue(v: Any, basis: ShippingRateBasis): Long? = when (basis) {
        ShippingRateBasis.AMOUNT -> money(v)
        ShippingRateBasis.WEIGHT -> wholeNumber(v)?.takeIf { it <= MAX_WEIGHT_GRAMS }
        ShippingRateBasis.QUANTITY -> wholeNumber(v)?.takeIf { it <= MAX_QUANTITY }
        ShippingRateBasis.FLAT -> 0L
    }

    private fun bigDecimalOf(v: Any?): BigDecimal? = when (v) {
        is Int, is Long, is Short, is Byte -> BigDecimal.valueOf((v as Number).toLong())
        is BigDecimal -> v
        is Double -> if (v.isNaN() || v.isInfinite()) null else BigDecimal(v.toString())
        is Float -> if (v.isNaN() || v.isInfinite()) null else BigDecimal(v.toString())
        else -> null
    }

    /** A decimal with at most two fraction digits as x100, or `null` when malformed or above the largest allowed value. */
    fun money(v: Any?): Long? {
        val d = bigDecimalOf(v) ?: return null

        if (d.stripTrailingZeros().scale() > 2 || d.abs() > MAX_UNITS) return null

        return d.movePointRight(2).setScale(0).toLong()
    }

    private fun wholeNumber(v: Any?): Long? {
        val d = bigDecimalOf(v) ?: return null

        if (d.stripTrailingZeros().scale() > 0) return null

        return runCatching { d.toBigIntegerExact().longValueExact() }.getOrNull()
    }

    private fun optionalText(form: JsonObject, key: String, current: String?, max: Int, errors: MutableMap<String, String>): String? {
        if (!form.containsKey(key)) return current

        return when (val v = form.getValue(key)) {
            null -> null
            is String -> if (v.trim().length > max) {
                errors[key] = "TOO_LONG"
                current
            } else v.trim().ifEmpty { null }

            else -> {
                errors[key] = "INVALID"
                current
            }
        }
    }

    private fun optionalInt(form: JsonObject, key: String, min: Int, max: Int, current: Int?, errors: MutableMap<String, String>): Int? {
        val v = form.getValue(key) ?: return null
        val n = wholeNumber(v)

        return if (n != null && n in min..max) n.toInt() else {
            errors[key] = "OUT_OF_RANGE"
            current
        }
    }
}
