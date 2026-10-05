package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Currencies
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.core.order.LineRules
import com.panomc.plugins.market.core.pricing.ShippingCharge
import com.panomc.plugins.market.core.pricing.Tender
import com.panomc.plugins.market.core.shipping.AddressValidator
import com.panomc.plugins.market.core.shipping.Measure
import com.panomc.plugins.market.core.shipping.ParcelBuilder
import com.panomc.plugins.market.core.shipping.ParcelSize
import com.panomc.plugins.market.core.shipping.QuoteCacheKey
import com.panomc.plugins.market.core.shipping.RateEngine
import com.panomc.plugins.market.core.shipping.RateRow
import com.panomc.plugins.market.core.shipping.RateSource
import com.panomc.plugins.market.core.shipping.RateTable
import com.panomc.plugins.market.core.shipping.RawRate
import com.panomc.plugins.market.core.shipping.ShippableLine
import com.panomc.plugins.market.core.shipping.ShippableLines
import com.panomc.plugins.market.core.shipping.ShippingPriceCalculator
import com.panomc.plugins.market.core.shipping.ShippingTerms
import com.panomc.plugins.market.core.shipping.Zone
import com.panomc.plugins.market.core.shipping.ZoneMatcher
import com.panomc.plugins.market.core.shipping.ZoneRegion
import com.panomc.plugins.market.core.shipping.Countries
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketAddressDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.dao.MarketShippingCarrierDao
import com.panomc.plugins.market.db.dao.MarketShippingMethodDao
import com.panomc.plugins.market.db.dao.MarketShippingRateDao
import com.panomc.plugins.market.db.dao.MarketShippingZoneDao
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketShippingMethod
import com.panomc.plugins.market.error.InvalidGiftCode
import com.panomc.plugins.market.db.model.MarketShippingZone
import com.panomc.plugins.market.db.model.ShippingRateSource
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.shipping.AddressField
import com.panomc.plugins.market.spi.shipping.AddressResolution
import com.panomc.plugins.market.spi.shipping.Parcel
import com.panomc.plugins.market.spi.shipping.QuoteRequest
import com.panomc.plugins.market.spi.shipping.QuoteResult
import com.panomc.plugins.market.spi.shipping.RateOption
import com.panomc.plugins.market.spi.shipping.SenderAddress
import com.panomc.plugins.market.spi.shipping.ShipItem
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shipping at checkout (10 sections 3 to 6; MK-132): the address, the zone, the offered methods with their prices (rule
 * rates, live carrier quotes with a timeout, a cache, a circuit breaker and the fallback to the rules) and what checkout
 * freezes on the order. It is the production [ShippingQuoter] of [CheckoutService].
 *
 * Fail closed: whatever goes wrong inside (a data defect, an exception, a provider that is gone) the answer is "no charge
 * could be priced" with `SHIPPING_UNAVAILABLE`; a physical cart is never sold with a shipping price of 0 by accident.
 * Everything here reads; the one write is the throttled `lastError` stamp of a carrier row.
 */
class ShippingService(
    private val clock: Clock,
    private val zones: MarketShippingZoneDao,
    private val methods: MarketShippingMethodDao,
    private val rates: MarketShippingRateDao,
    private val carriers: MarketShippingCarrierDao,
    private val currencyRates: MarketCurrencyRateDao,
    private val addresses: MarketAddressDao,
    private val lookup: ProviderLookup,
    private val cipher: SecretCipher,
    private val contexts: ShippingContexts,
    val cache: ShippingQuoteCache = ShippingQuoteCache(clock),
    val breaker: QuoteBreaker = QuoteBreaker(clock),
    /** The live carrier call limit of 10 section 5.4 (5 s). */
    private val quoteTimeoutMs: Long = QUOTE_TIMEOUT_MS
) : ShippingQuoter {
    private val log = LoggerFactory.getLogger(ShippingService::class.java)
    private val lastErrorStamp = ConcurrentHashMap<String, AtomicLong>()

    /** Hung from [ShippingAdminService.onQuoteCacheInvalidate]: a carrier's settings changed. */
    fun invalidate(providerId: String) {
        cache.invalidate(providerId)
    }

    // ------------------------------------------------------------------------------------------------ the quoter

    override suspend fun quote(request: ShippingRequest, sqlClient: SqlClient): ShippingQuote = try {
        compute(request, sqlClient)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("shipping quote failed; answering NO_METHOD", e)

        unavailable(NO_METHOD)
    }

    /** What one candidate method resolved to. */
    private class Computed(val charge: com.panomc.plugins.market.core.shipping.ShippingCharge, val figures: Tender.ShippingFigures)

    private class Priced(val method: MarketShippingMethod, val raw: RawRate, val rate: RateOption?, val charge: Computed)

    private class Carrier(
        val provider: ShippingProvider,
        val settings: ProviderSettings,
        val testMode: Boolean,
        val capabilities: ShippingCapabilities,
        val version: Long
    ) {
        val sender: Address? get() = SenderAddress.from(settings)
    }

    private suspend fun compute(request: ShippingRequest, sqlClient: SqlClient): ShippingQuote {
        val conv = request.conversions ?: return unavailable(NO_METHOD)
        val lines = request.shippable?.takeIf { it.isNotEmpty() } ?: return unavailable(NO_METHOD)
        val checkout = request.checkout

        // 1. the address (10 section 3.1): inline wins, else the buyer's own saved one; a foreign or missing id is "no address"
        val raw = request.shippingAddress?.let { addressOf(it) } ?: savedAddress(request, sqlClient)
        val requiredDefault = AddressValidator.requiredFields(null)
        val country = raw?.let { AddressValidator.clean(it.country) }

        if (raw == null || country == null) {
            return ShippingQuote(null, messages = listOf(QuoteMessage(CheckoutService.SHIPPING_ADDRESS_REQUIRED, INFO, fields = requiredDefault)), fields = requiredDefault)
        }

        val normalized = AddressValidator.normalize(raw, request.orderEmail)
        val base = AddressValidator.check(normalized)

        if (!Countries.isValid(normalized.country)) return invalidAddress(listOf("country"))

        if (checkout && !base.valid) return invalidAddress(base.fields)

        // 3. a method id is required at checkout (06 section 6.2 step 3), before anything is priced
        if (checkout && request.shippingMethodId == null) return methodRequired()

        val weight = ShippableLines.weightGrams(lines)

        // 4.1 zone
        val allZones = zones.getActive(sqlClient).map { zoneOf(it) }
        val zone = ZoneMatcher.match(allZones, normalized)

        if (weight > ShippableLines.MAX_WEIGHT_GRAMS) return if (base.valid) unavailable(NO_METHOD) else invalidAddress(base.fields)

        if (zone == null) return if (base.valid) unavailable(NO_ZONE) else invalidAddress(base.fields)

        // 5.3 candidates
        val carrierCache = HashMap<String, Carrier?>()
        val table = rateTable(conv, sqlClient)
        val candidates = ArrayList<Pair<MarketShippingMethod, List<RateRow>>>()

        for (method in methods.getActive(sqlClient)) {
            val rows = rates.getByMethodAndZone(method.id, zone.id, sqlClient)

            if (rows.isEmpty()) continue

            val carrier = carrierOf(method.providerId, sqlClient, carrierCache) ?: continue

            if (method.maxWeightGrams != null && weight > method.maxWeightGrams) continue

            val caps = carrier.capabilities

            if (caps.destinationCountries != null && normalized.country !in caps.destinationCountries!!) continue

            val senderCountry = carrier.sender?.country ?: manualSender(sqlClient, carrierCache)?.country

            if (caps.originCountries != null && senderCountry != null && senderCountry !in caps.originCountries!!) continue

            candidates += method to rows.map { RateRow(it.basis, it.rangeFrom, it.rangeTo, it.price, it.perUnitPrice, it.position, it.id) }
        }

        if (candidates.isEmpty()) return if (base.valid) unavailable(NO_METHOD) else invalidAddress(base.fields)

        // 5.4 the live rates, one call per provider, in parallel
        val units = lines.fold(0L) { acc, l -> Math.addExact(acc, l.quantity.toLong()) }
        val measure = Measure(weight, request.physicalBasisBase, units)
        val wanted = candidates.filter { (m, _) -> m.rateSource != ShippingRateSource.RULES }.map { it.first }.groupBy { it.providerId }
        val live: Map<String, QuoteResult?> = if (base.valid && wanted.isNotEmpty()) {
            liveRates(wanted, normalized, lines, request, conv, checkout, sqlClient, carrierCache)
        } else {
            emptyMap()
        }

        // 5.3 step 4 and 5: price every survivor
        val priced = ArrayList<Priced>()

        for ((method, rows) in candidates) {
            val ruleRaw = RateEngine.price(rows, measure)?.let { RawRate(it, conv.baseCurrency, request.pricesIncludeVat, RateSource.RULES) }
            val result = live[method.providerId]
            val picked = if (method.rateSource == ShippingRateSource.RULES) null else pickRate(method, result, conv, table)

            fun price(raw: RawRate, rate: RateOption?): Priced? {
                val charge = ShippingPriceCalculator.compute(
                    ShippingTerms(method.freeShippingThreshold, method.handlingFee, method.vatPercent), raw, request.physicalBasis,
                    request.pricesIncludeVat, request.configVatBp, conv, table
                ) ?: return null

                return Priced(method, raw, rate, Computed(charge, figures(charge, request, conv)))
            }

            val carrierRaw = picked?.let { RawRate(it.price.amount, it.price.currency, it.priceIncludesTax, RateSource.CARRIER) }
            val entry = when (method.rateSource) {
                ShippingRateSource.RULES -> ruleRaw?.let { price(it, null) }
                ShippingRateSource.CARRIER -> if (base.valid && carrierRaw != null) price(carrierRaw, picked) else null
                ShippingRateSource.CARRIER_WITH_FALLBACK ->
                    (if (base.valid && carrierRaw != null) price(carrierRaw, picked) else null)
                        ?: ruleRaw?.let { r -> price(RawRate(r.amount, r.currency, r.includesTax, RateSource.FALLBACK), null) }
            }

            if (entry != null) priced += entry
        }

        if (priced.isEmpty()) return if (base.valid) unavailable(NO_METHOD) else invalidAddress(base.fields)

        // 5.3 step 6 and 7: the options, the selection
        val options = priced.map { optionOf(it, conv) }
        val asked = request.shippingMethodId
        val chosen: Priced? = if (checkout) {
            priced.firstOrNull { it.method.id == asked }
        } else {
            priced.firstOrNull { it.method.id == asked } ?: priced.minByOrNull { it.charge.figures.total }
        }

        if (checkout && chosen == null) return unavailable(METHOD_NOT_OFFERED, options)

        chosen!!

        val messages = ArrayList<QuoteMessage>()
        val carrier = carrierOf(chosen.method.providerId, sqlClient, carrierCache)
        val providerFields = carrier?.capabilities?.requiredAddressFields.orEmpty()
        val final = AddressValidator.normalize(raw, request.orderEmail, keepIdentityNumber = AddressField.IDENTITY_NUMBER in providerFields)
        val finalCheck = AddressValidator.check(final, providerFields)

        // 6.2 step 5 / 6.1: the address must also satisfy the chosen method's provider
        if (!finalCheck.valid) {
            messages += QuoteMessage(CheckoutService.SHIPPING_ADDRESS_INVALID, LineRules.ERROR, fields = finalCheck.fields)
        } else if (!base.valid) {
            messages += QuoteMessage(CheckoutService.SHIPPING_ADDRESS_INVALID, LineRules.ERROR, fields = base.fields)
        } else if (carrier != null && carrier.capabilities.addressResolve && !addressAccepted(carrier, final, checkout)) {
            // 3.4: the carrier called the address invalid (its text is logged, never shown)
            messages += QuoteMessage(CheckoutService.SHIPPING_ADDRESS_INVALID, LineRules.ERROR, fields = emptyList())
        }

        if (!checkout) {
            if (asked == null || priced.none { it.method.id == asked }) {
                messages += QuoteMessage(CheckoutService.SHIPPING_METHOD_REQUIRED, if (asked == null) INFO else LineRules.WARNING)
            }
        }

        if (messages.any { it.code == CheckoutService.SHIPPING_ADDRESS_INVALID }) {
            // checkout refuses with these fields; the quote shows the estimate and cannot be paid
            val fields = messages.first { it.code == CheckoutService.SHIPPING_ADDRESS_INVALID }.fields.orEmpty()

            return ShippingQuote(chargeOf(chosen, request), options, chosen.method.id, messages, fields = fields)
        }

        val now = clock.now()

        return ShippingQuote(
            charge = chargeOf(chosen, request),
            options = options,
            methodId = chosen.method.id,
            messages = messages,
            address = addressJson(final),
            methodName = chosen.method.name,
            snapshot = snapshotOf(chosen, zone.id, conv, now, carrier, weight, lines, request),
            weightGrams = weight.toInt()
        )
    }

    // ------------------------------------------------------------------------------------------------ messages

    private fun unavailable(reason: String, options: List<JsonObject> = emptyList()) =
        ShippingQuote(
            null, options, messages = listOf(QuoteMessage(CheckoutService.SHIPPING_UNAVAILABLE, LineRules.ERROR, reason = reason)), reason = reason
        )

    private fun methodRequired() =
        ShippingQuote(null, messages = listOf(QuoteMessage(CheckoutService.SHIPPING_METHOD_REQUIRED, LineRules.ERROR)), reason = "METHOD_REQUIRED")

    private fun invalidAddress(fields: List<String>) =
        ShippingQuote(null, messages = listOf(QuoteMessage(CheckoutService.SHIPPING_ADDRESS_INVALID, LineRules.ERROR, fields = fields)), fields = fields)

    // ------------------------------------------------------------------------------------------------ address

    private suspend fun savedAddress(request: ShippingRequest, sqlClient: SqlClient): Address? {
        val id = request.shippingAddressId ?: return null
        val userId = request.userId ?: return null
        val row = addresses.getById(id, sqlClient) ?: return null

        if (row.userId != userId) return null

        return Address(
            row.firstName, row.lastName, row.company, row.phone, row.email, row.country, row.state, row.city, row.district, row.neighborhood,
            row.line1, row.line2, row.postalCode, null, null, row.identityNumber
        )
    }

    private fun addressOf(o: JsonObject): Address {
        fun text(key: String): String? = when (val v = o.getValue(key)) {
            is String -> v
            is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) v.toLong().toString() else v.toString()
            else -> null
        }

        return Address(
            text("firstName"), text("lastName"), text("company"), text("phone"), text("email"), text("country"), text("state"), text("city"),
            text("district"), text("neighborhood"), text("line1"), text("line2"), text("postalCode"), null, null, text("identityNumber")
        )
    }

    // ------------------------------------------------------------------------------------------------ providers

    /** The provider of a method when it can be used right now: registered, enabled, configured (10 section 5.3 step 2). */
    private suspend fun carrierOf(providerId: String, sqlClient: SqlClient, memo: MutableMap<String, Carrier?>): Carrier? {
        if (memo.containsKey(providerId)) return memo[providerId]

        val built = try {
            buildCarrier(providerId, sqlClient)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("shipping provider $providerId could not be read", e)

            null
        }

        memo[providerId] = built

        return built
    }

    private suspend fun buildCarrier(providerId: String, sqlClient: SqlClient): Carrier? {
        val resolved = lookup.shipping(providerId) ?: return null
        val row = carriers.getByProviderId(providerId, sqlClient)
        val builtIn = providerId == ManualShippingProvider.ID

        if (row == null && !builtIn) return null

        if (row != null && !row.enabled && !builtIn) return null

        val provider = resolved.provider
        val codec = SettingsCodec(provider.settingsSchema(), cipher)
        val stored = row?.settings?.let { runCatching { JsonObject(it) }.getOrNull() }

        if (!builtIn && codec.missingRequired(stored).isNotEmpty()) return null

        val settings = codec.decrypt(stored)

        return Carrier(provider, settings, row?.testMode ?: false, provider.capabilities(settings), settingsVersion(row?.settings))
    }

    /**
     * The cache version of a carrier: a digest of its stored settings, never `updatedAt`. `lastError` / inbound stamps bump `updatedAt`
     * on every carrier failure, which would silently end the 30-minute honour window of every cached quote of the provider (10 section 5.4);
     * a settings save changes the stored text (and clears the cache through `ShippingAdminService`).
     */
    private fun settingsVersion(settings: String?): Long {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest((settings ?: "").toByteArray(Charsets.UTF_8))

        return java.nio.ByteBuffer.wrap(digest, 0, 8).long
    }

    private suspend fun manualSender(sqlClient: SqlClient, memo: MutableMap<String, Carrier?>): Address? =
        carrierOf(ManualShippingProvider.ID, sqlClient, memo)?.sender

    // ------------------------------------------------------------------------------------------------ live rates

    private suspend fun liveRates(
        wanted: Map<String, List<MarketShippingMethod>>,
        to: Address,
        lines: List<ShippableLine>,
        request: ShippingRequest,
        conv: Conversions,
        checkout: Boolean,
        sqlClient: SqlClient,
        memo: MutableMap<String, Carrier?>
    ): Map<String, QuoteResult?> {
        // the provider data is read before the parallel part: the DAO calls stay on the caller's connection
        val jobs = ArrayList<Triple<String, Carrier, List<MarketShippingMethod>>>()

        for ((providerId, ms) in wanted) {
            val carrier = carrierOf(providerId, sqlClient, memo) ?: continue

            if (!carrier.capabilities.rateQuote) continue

            jobs += Triple(providerId, carrier, ms)
        }

        val manual = manualSender(sqlClient, memo)
        val manualParcel = carrierOf(ManualShippingProvider.ID, sqlClient, memo)?.let { parcelSize(it.settings) }
        val out = HashMap<String, QuoteResult?>()

        coroutineScope {
            jobs.map { (providerId, carrier, ms) ->
                async { providerId to liveRate(providerId, carrier, ms, to, lines, request, conv, checkout, manual, manualParcel, sqlClient) }
            }.awaitAll()
        }.forEach { (providerId, result) -> out[providerId] = result }

        return out
    }

    private fun parcelSize(settings: ProviderSettings): ParcelSize? {
        val (l, w, h) = SenderAddress.defaultParcelMm(settings)

        return if (l != null && w != null && h != null) ParcelSize(l, w, h) else null
    }

    /** One quote call per provider per request (10 section 5.4); `null` = no live rate for every method of the provider. */
    private suspend fun liveRate(
        providerId: String,
        carrier: Carrier,
        ms: List<MarketShippingMethod>,
        to: Address,
        lines: List<ShippableLine>,
        request: ShippingRequest,
        conv: Conversions,
        checkout: Boolean,
        manualSender: Address?,
        manualParcel: ParcelSize?,
        sqlClient: SqlClient
    ): QuoteResult? {
        val ctx = contexts.create(carrier.provider, carrier.settings, carrier.testMode)

        try {
            val from = carrier.sender ?: manualSender ?: return null.also { ctx.log.warn("no sender address: live rate skipped") }
            val parcel = ParcelBuilder.forCheckout(lines, parcelSize(carrier.settings), manualParcel)

            if (carrier.capabilities.requiresDimensions && !ParcelBuilder.hasDimensions(parcel)) return null

            // the service filter: only when exactly one carrier-sourced candidate of the provider exists
            val serviceCode = ms.singleOrNull()?.serviceCode
            val value = Money(lines.fold(0L) { acc, l -> Math.addExact(acc, l.lineValue) }, request.currency)
            val key = QuoteCacheKey.of(providerId, carrier.testMode, carrier.version, serviceCode, from, to, listOf(parcel), value.amount, request.currency)
            val seconds = carrier.capabilities.quoteCacheSeconds
            val cached = if (checkout) cache.honoured(key, seconds) else cache.fresh(key, seconds)

            if (cached != null) return cached

            if (!breaker.allow(providerId)) return null

            val items = lines.map { l ->
                val unit = Rounding.ratioQ(BigDecimal.valueOf(l.lineValue), BigDecimal.valueOf(l.quantity.toLong()), conv.oq)

                ShipItem(l.orderItemId, l.name, l.sku, l.quantity, l.unitWeightGrams, Money(unit, request.currency), l.hsCode, l.originCountry)
            }

            val result = try {
                withTimeout(quoteTimeoutMs) {
                    carrier.provider.quote(ctx, QuoteRequest(from, to, listOf(parcel), items, value, request.currency, serviceCode))
                }
            } catch (e: TimeoutCancellationException) {
                failed(providerId, ctx.log, "timeout", sqlClient)

                return null
            }

            breaker.success(providerId)

            if (!result.supported || result.rates.isEmpty()) {
                ctx.log.warn(if (result.supported) "the carrier returned no rate" else "the carrier has no rate API")

                return null
            }

            cache.put(key, providerId, result)

            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(providerId, ctx.log, e.javaClass.simpleName, sqlClient, e)

            return null
        }
    }

    private suspend fun failed(providerId: String, log: com.panomc.plugins.market.spi.common.ProviderLog, code: String, sqlClient: SqlClient, error: Throwable? = null) {
        breaker.failure(providerId)
        log.warn("live shipping rate failed: $code", error)

        // `lastError` of the carrier row, at most once a minute (10 section 5.4)
        val stamp = lastErrorStamp.getOrPut(providerId) { AtomicLong(0) }
        val now = clock.now()
        val last = stamp.get()

        if (now - last < 60_000 && last != 0L) return
        if (!stamp.compareAndSet(last, now)) return

        try {
            carriers.getByProviderId(providerId, sqlClient)?.let { carriers.recordError(it.id, code.take(64), now, sqlClient) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            this.log.warn("could not record the shipping provider error", e)
        }
    }

    /** The rate of [method] out of a provider's result (10 section 5.4): its service, else the cheapest; stale or unusable rates are dropped. */
    private fun pickRate(method: MarketShippingMethod, result: QuoteResult?, conv: Conversions, table: RateTable): RateOption? {
        if (result == null) return null

        val now = clock.now()
        val usable = result.rates.mapNotNull { r ->
            val amount = r.price.amount

            if (amount < 0 || !Currencies.isSupported(r.price.currency)) return@mapNotNull null
            if (r.expiresAt != null && r.expiresAt!! <= now) return@mapNotNull null

            val inBase = table.convert(amount, r.price.currency, conv.baseCurrency) ?: return@mapNotNull null

            r to inBase
        }
        val matching = if (method.serviceCode != null) usable.filter { it.first.serviceCode == method.serviceCode } else usable

        return matching.minByOrNull { it.second }?.first
    }

    private suspend fun rateTable(conv: Conversions, sqlClient: SqlClient): RateTable {
        val others = currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate }

        return RateTable(conv.baseCurrency, conv.orderCurrency, conv.fx, others)
    }

    // ------------------------------------------------------------------------------------------------ 3.4 address check

    /** `false` only when the carrier answered "invalid" (live inside the quote, from the cache at checkout); anything else accepts the address as typed. */
    private suspend fun addressAccepted(carrier: Carrier, address: Address, checkout: Boolean): Boolean {
        val providerId = carrier.provider.id
        val key = resolutionKey(providerId, carrier, address)
        val seconds = carrier.capabilities.quoteCacheSeconds

        (if (checkout) cache.honouredResolution(key, seconds) else cache.freshResolution(key, seconds))?.let { return it }

        if (checkout || !breaker.allow(providerId)) return true

        val ctx = contexts.create(carrier.provider, carrier.settings, carrier.testMode)

        return try {
            val resolution: AddressResolution = withTimeout(quoteTimeoutMs) { carrier.provider.resolveAddress(ctx, address) }

            breaker.success(providerId)
            cache.putResolution(key, providerId, resolution)

            if (resolution.supported && !resolution.valid) {
                ctx.log.warn("the carrier called the address invalid: ${resolution.message}")

                false
            } else {
                true
            }
        } catch (e: TimeoutCancellationException) {
            breaker.failure(providerId)
            ctx.log.warn("address check timed out")

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            breaker.failure(providerId)
            ctx.log.warn("address check failed: ${e.javaClass.simpleName}")

            true
        }
    }

    private fun resolutionKey(providerId: String, carrier: Carrier, a: Address): String {
        val parts = listOf(
            "resolve", providerId, carrier.testMode.toString(), carrier.version.toString(), a.country, a.state, a.city, a.district, a.neighborhood, a.line1,
            a.line2, a.postalCode
        ).joinToString("|") { "${(it ?: "").length}:${it ?: ""}" }

        return java.security.MessageDigest.getInstance("SHA-256").digest(parts.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------------------------------------ results

    private fun zoneOf(z: MarketShippingZone): Zone = Zone(
        id = z.id,
        countries = jsonList(z.countries).map { it.toString() },
        regions = z.regions?.let { json ->
            jsonList(json).mapNotNull { e ->
                val o = e as? JsonObject ?: (e as? Map<*, *>)?.let { JsonObject(it as Map<String, Any?>) }

                o?.let { ZoneRegion(it.getString("country"), (it.getJsonArray("states") ?: JsonArray()).map { s -> s.toString() }) }
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

    /** What the pricing engine will compute for this price (05 section 9.1): the figure the buyer pays and its VAT. */
    private fun figures(charge: com.panomc.plugins.market.core.shipping.ShippingCharge, request: ShippingRequest, conv: Conversions) =
        Tender.shipping(priceOf(charge, request), charge.vatBp, request.configVatBp, request.pricesIncludeVat, conv.oq)

    /** The `ShippingCharge.price` the engine takes: the price basis of the store (VAT included, or net when prices exclude VAT). */
    private fun priceOf(charge: com.panomc.plugins.market.core.shipping.ShippingCharge, request: ShippingRequest): Long =
        if (request.pricesIncludeVat) charge.gross else charge.gross - charge.vat

    private fun chargeOf(p: Priced, request: ShippingRequest): ShippingCharge =
        ShippingCharge(priceOf(p.charge.charge, request), p.charge.charge.vatBp)

    private fun optionOf(p: Priced, conv: Conversions): JsonObject {
        val carrierSourced = p.charge.charge.source == RateSource.CARRIER
        val minDays = if (carrierSourced) p.rate?.minDays ?: p.method.minDeliveryDays else p.method.minDeliveryDays
        val maxDays = if (carrierSourced) p.rate?.maxDays ?: p.method.maxDeliveryDays else p.method.maxDeliveryDays

        return JsonObject()
            .put("methodId", p.method.id)
            .put("name", p.method.name)
            .put("description", p.method.description)
            .put("price", money(p.charge.figures.total))
            .put("currency", conv.orderCurrency)
            .put("minDays", minDays)
            .put("maxDays", maxDays)
            .put("source", p.charge.charge.source.name)
            .put("free", p.charge.charge.free)
    }

    /** What checkout freezes as `market_order.shippingQuote` (10 section 6.3). */
    private fun snapshotOf(
        p: Priced, zoneId: Long, conv: Conversions, now: Long, carrier: Carrier?, weight: Long, lines: List<ShippableLine>, request: ShippingRequest
    ): JsonObject {
        val c = p.charge.charge
        val carrierSourced = c.source == RateSource.CARRIER
        val rate = p.rate?.takeIf { carrierSourced }
        val parcel = lines.let { ParcelBuilder.forCheckout(it, carrier?.let { k -> parcelSize(k.settings) }) }
        val bpPercent = BigDecimal.valueOf(p.charge.figures.vatPercent, 2)

        return JsonObject()
            .put("zoneId", zoneId)
            .put("methodId", p.method.id)
            .put("providerId", p.method.providerId)
            .put("serviceCode", if (carrierSourced) rate?.serviceCode ?: p.method.serviceCode else p.method.serviceCode)
            .put("serviceName", rate?.serviceName)
            .put("carrierName", rate?.carrierName ?: p.method.carrierName)
            .put("rateRef", rate?.rateRef)
            .put("price", money(p.charge.figures.total))
            .put("currency", conv.orderCurrency)
            .put("source", c.source.name)
            .put("free", c.free)
            .put("vatPercent", bpPercent.toDouble())
            .put("vatAmount", money(p.charge.figures.vat))
            .put("handlingFee", money(c.handlingPart))
            .put("carrierPrice", if (carrierSourced) rate?.let { money(p.raw.amount) } else null)
            .put("carrierCurrency", if (carrierSourced) rate?.price?.currency else null)
            .put("minDays", if (carrierSourced) rate?.minDays ?: p.method.minDeliveryDays else p.method.minDeliveryDays)
            .put("maxDays", if (carrierSourced) rate?.maxDays ?: p.method.maxDeliveryDays else p.method.maxDeliveryDays)
            .put("expiresAt", if (carrierSourced) rate?.expiresAt else null)
            .put("quotedAt", now)
            .put("weightGrams", weight)
            .put(
                "parcels",
                JsonArray().add(
                    JsonObject().put("weightGrams", parcel.weightGrams).put("lengthMm", parcel.lengthMm).put("widthMm", parcel.widthMm).put("heightMm", parcel.heightMm)
                )
            )
    }

    private fun addressJson(a: Address): JsonObject {
        val o = JsonObject()

        fun put(key: String, value: String?) {
            if (value != null) o.put(key, value)
        }

        put("firstName", a.firstName)
        put("lastName", a.lastName)
        put("company", a.company)
        put("phone", a.phone)
        put("email", a.email)
        put("country", a.country)
        put("state", a.state)
        put("city", a.city)
        put("district", a.district)
        put("neighborhood", a.neighborhood)
        put("line1", a.line1)
        put("line2", a.line2)
        put("postalCode", a.postalCode)
        put("identityNumber", a.identityNumber)

        return o
    }

    companion object {
        const val QUOTE_TIMEOUT_MS = 5_000L

        /**
         * Gift-code redemption of a physical product is not supported in v1 (10 section 6.3): the endpoint takes no address and shipping is
         * never priced for `GIFT_CODE` orders. [products] are the products the code would hand out (a bundle's children included); the
         * redemption and the panel gift form answer with the returned `INVALID_GIFT_CODE {reason: PHYSICAL_NOT_SUPPORTED}`, or `null` when none is physical.
         */
        fun giftCodeRefusal(products: Collection<MarketProduct>): InvalidGiftCode? =
            if (products.any { it.physical }) InvalidGiftCode("PHYSICAL_NOT_SUPPORTED") else null

        const val NO_ZONE = "NO_ZONE"
        const val NO_METHOD = "NO_METHOD"
        const val METHOD_NOT_OFFERED = "METHOD_NOT_OFFERED"
        private const val INFO = "info"
    }
}
