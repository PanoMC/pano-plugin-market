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
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.shipping.NextPoll
import com.panomc.plugins.market.core.shipping.ShipmentFacts
import com.panomc.plugins.market.core.shipping.ShipmentStateMachine
import com.panomc.plugins.market.core.shipping.ShippingItemFacts
import com.panomc.plugins.market.core.shipping.ShippingOrderFacts
import com.panomc.plugins.market.core.shipping.ShippingStatusDeriver
import com.panomc.plugins.market.core.shipping.TrackedEvent
import com.panomc.plugins.market.core.shipping.TrackingEventRules
import com.panomc.plugins.market.core.shipping.TrackingSchedule
import com.panomc.plugins.market.core.shipping.TrackingSource
import com.panomc.plugins.market.core.shipping.TrackingUrl
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.dao.MarketShipmentEventDao
import com.panomc.plugins.market.db.dao.MarketShipmentItemDao
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShipmentEvent
import com.panomc.plugins.market.db.model.MarketShipmentItem
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.ShipmentEntryMode
import com.panomc.plugins.market.db.model.ShipmentEventSource
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidShipment
import com.panomc.plugins.market.error.InvalidShipmentTransition
import com.panomc.plugins.market.error.OrderNotShippable
import com.panomc.plugins.market.error.ProviderUnavailable
import com.panomc.plugins.market.error.ShipmentNotCancellable
import com.panomc.plugins.market.error.ShippingAddressRequired
import com.panomc.plugins.market.error.ShippingProviderError
import com.panomc.plugins.market.error.StatusQueryNotSupported
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.shipping.CancelShipmentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.LabelDocument
import com.panomc.plugins.market.spi.shipping.LabelFormat
import com.panomc.plugins.market.spi.shipping.ShipmentErrorCode
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.shipping.TrackRequest
import com.panomc.plugins.market.spi.shipping.TrackingEvent
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import com.panomc.plugins.market.spi.shipping.ShipmentStatus as SpiShipmentStatus
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
    private val quoteTimeoutMs: Long = QUOTE_TIMEOUT_MS,
    /** The fulfilment half (10 section 9: shipments, retry, cancel, manual update, tracking updates, address edit); `null` = quoting only. */
    private val fulfilment: FulfilmentDeps? = null
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


    // ================================================================================================ fulfilment (MK-133; 10 section 9)

    private fun fx(): FulfilmentDeps = fulfilment ?: error("the fulfilment half of ShippingService is not wired (FulfilmentDeps)")

    private fun table(name: String) = "`${fx().orders.prefix()}$name`"

    /** The order lock of every fulfilment use case: unlocked read, order row, then the shipment rows of the order (00 section 8.3 levels 6 and 7). */
    private suspend fun <T> inOrder(orderId: Long, block: suspend (SqlConnection, LockedOrder) -> T): T {
        val f = fx()

        try {
            return f.db.txRestartingOnOrderChange { conn ->
                f.locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) { locked ->
                    f.locks.children(conn, orderId, OrderChild.SHIPMENT)

                    block(conn, locked)
                }
            }
        } catch (e: NoSuchElementException) {
            throw NotFound()
        }
    }

    private suspend fun orderOrNotFound(orderId: Long, sqlClient: SqlClient): MarketOrder = fx().orders.getById(orderId, sqlClient) ?: throw NotFound()

    private suspend fun shipmentOrNotFound(shipmentId: Long, sqlClient: SqlClient): MarketShipment = fx().shipments.getById(shipmentId, sqlClient) ?: throw NotFound()

    /** 10 section 9.1: the order can take a shipment now. */
    private fun shippableOrThrow(order: MarketOrder) {
        if (order.status != OrderStatus.COMPLETED && order.status != OrderStatus.PARTIALLY_REFUNDED || !order.requiresShipping) throw OrderNotShippable("STATUS")
        if (order.shippingAddress == null) throw OrderNotShippable("NO_ADDRESS")
        if (order.disputeStatus == DisputeStatus.OPEN) throw OrderNotShippable("DISPUTE")
    }

    private fun isShippableItem(item: MarketOrderItem) = item.physical && item.kind != OrderItemKind.BUNDLE

    private fun shippableUnits(item: MarketOrderItem): Int = if (isShippableItem(item)) maxOf(0, item.quantity - item.refundedQuantity - item.shippedQuantity) else 0

    private fun parse(text: String?): JsonObject? = text?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() }

    private fun errorCodeOf(e: Throwable): String = when (e) {
        is TimeoutCancellationException -> TIMEOUT
        is ProviderException -> e.code.name
        else -> INTERNAL
    }

    private fun safeMessage(e: Throwable): String? = when (e) {
        is TimeoutCancellationException -> "the carrier did not answer in time"
        is ProviderException -> e.message
        else -> "internal error"
    }

    /** A usable provider for a fulfilment call: registered, enabled and configured, else 409 `PROVIDER_UNAVAILABLE {state}`. */
    private suspend fun usable(providerId: String, sqlClient: SqlClient): Carrier {
        if (lookup.shipping(providerId) == null) throw ProviderUnavailable(lookup.state(ProviderKind.SHIPPING, providerId).availability.name)

        val built = try {
            buildCarrier(providerId, sqlClient)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("shipping provider $providerId could not be read", e)

            null
        }

        if (built != null) return built

        val row = carriers.getByProviderId(providerId, sqlClient)

        throw ProviderUnavailable(if (row != null && !row.enabled) "DISABLED" else "NOT_CONFIGURED")
    }

    private suspend fun usableOrNull(providerId: String, sqlClient: SqlClient): Carrier? = try {
        usable(providerId, sqlClient)
    } catch (e: ProviderUnavailable) {
        null
    }

    /** The sender of a shipment: the provider's own, else the `manual` carrier's (10 section 8.1). */
    private suspend fun senderOf(carrier: Carrier, sqlClient: SqlClient): Address? = carrier.sender ?: manualSender(sqlClient, HashMap())

    // ------------------------------------------------------------------------------------------------ small SQL helpers

    private suspend fun setShipment(conn: SqlClient, id: Long, sets: Map<String, Any?>) {
        if (sets.isEmpty()) return

        val columns = sets.keys.joinToString(", ") { "`$it` = ?" }
        val values = ArrayList<Any?>(sets.values)

        values += clock.now()
        values += id

        conn.preparedQuery("UPDATE ${table("market_shipment")} SET $columns, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.from(values)).coAwait()
    }

    /** Every write to the order grows its version (06 section 13.2). */
    private suspend fun setOrder(conn: SqlClient, orderId: Long, sets: Map<String, Any?>) {
        val columns = sets.keys.joinToString(", ") { "`$it` = ?" }
        val values = ArrayList<Any?>(sets.values)

        values += clock.now()
        values += orderId

        conn.preparedQuery("UPDATE ${table("market_order")} SET $columns, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ?").execute(Tuple.from(values)).coAwait()
    }

    private suspend fun orderEvent(conn: SqlClient, orderId: Long, type: OrderEventType, actorType: OrderActorType, actorUserId: Long?, data: JsonObject?, message: String? = null) {
        val now = clock.now()

        fx().orderEvents.add(
            MarketOrderEvent(orderId = orderId, type = type, actorType = actorType, actorUserId = actorUserId, message = message, data = data?.encode(), createdAt = now, updatedAt = now),
            conn
        )
    }

    /** 10 section 7.3 after every shipment insert, status change and release: writes `shippingStatus` when it changed; the new value or `null`. */
    private suspend fun rederive(conn: SqlConnection, orderId: Long): ShippingStatus? {
        val f = fx()
        val order = f.orders.getById(orderId, conn) ?: return null
        val items = f.orderItems.getByOrderIds(listOf(orderId), conn)
        val facts = f.shipments.getByOrderId(orderId, conn).map { s ->
            ShipmentFacts(s.id, s.status, s.itemsReleased || s.status == ShipmentStatus.CANCELLED, f.shipmentItems.getByShipmentId(s.id, conn).associate { it.orderItemId to it.quantity })
        }
        val next = ShippingStatusDeriver.derive(
            ShippingOrderFacts(order.requiresShipping, order.paidAt != null),
            items.map { ShippingItemFacts(it.id, it.quantity, it.refundedQuantity, isShippableItem(it)) },
            facts
        )

        if (next == order.shippingStatus) return null

        setOrder(conn, orderId, mapOf("shippingStatus" to next.name))

        return next
    }

    /** `itemsReleased = 1` and the units of the shipment are shippable again (10 section 9.6). A released shipment is left alone. */
    private suspend fun release(conn: SqlConnection, shipment: MarketShipment) {
        if (shipment.itemsReleased) return

        val f = fx()
        val now = clock.now()

        for (line in f.shipmentItems.getByShipmentId(shipment.id, conn)) {
            conn.preparedQuery("UPDATE ${table("market_order_item")} SET `shippedQuantity` = GREATEST(`shippedQuantity` - ?, 0), `updatedAt` = ? WHERE `id` = ?")
                .execute(Tuple.of(line.quantity, now, line.orderItemId)).coAwait()
        }

        setShipment(conn, shipment.id, mapOf("itemsReleased" to 1))
    }

    // ------------------------------------------------------------------------------------------------ GET /orders/:id/shipping

    private val servicesCache = ConcurrentHashMap<String, Pair<Long, List<com.panomc.plugins.market.spi.shipping.ShippingService>>>()

    /**
     * `GET /orders/:id/shipping` (10 section 9.2): the still-shippable lines, suggested parcels, usable providers and the frozen quote.
     * [withAddress] = the caller holds `OM` or `PAY`; with `OV` alone the address is `null`.
     */
    suspend fun orderShipping(orderId: Long, withAddress: Boolean, sqlClient: SqlClient): JsonObject {
        val f = fx()
        val order = orderOrNotFound(orderId, sqlClient)
        val items = f.orderItems.getByOrderIds(listOf(orderId), sqlClient).filter { isShippableItem(it) }
        val now = clock.now()
        val quote = parse(order.shippingQuote)

        val lines = JsonArray()
        val parcelLines = ArrayList<ShippableLine>()

        for (item in items) {
            val snapshot = parse(item.snapshot)
            val weight = snapshot?.getInteger("weightGrams")
            val left = shippableUnits(item)

            lines.add(
                JsonObject().put("orderItemId", item.id).put("name", item.productName).put("variantName", item.variantName).put("sku", item.sku).put("quantity", item.quantity)
                    .put("refundedQuantity", item.refundedQuantity).put("shippedQuantity", item.shippedQuantity).put("shippable", left).put("weightGrams", weight)
            )

            if (left > 0) {
                parcelLines += ShippableLine(
                    item.id, item.productId ?: 0, item.variantId ?: 0, item.productName, item.sku, left, maxOf(1, weight ?: 0),
                    snapshot?.getInteger("lengthMm"), snapshot?.getInteger("widthMm"), snapshot?.getInteger("heightMm"), item.lineTotal,
                    snapshot?.getString("hsCode"), snapshot?.getString("originCountry")
                )
            }
        }

        val suggested = JsonArray()
        val manual = usableOrNull(ManualShippingProvider.ID, sqlClient)

        if (parcelLines.isNotEmpty() && !ShippableLines.isTooHeavy(parcelLines)) {
            val parcel = ParcelBuilder.forCheckout(parcelLines, null, manual?.let { parcelSize(it.settings) })

            suggested.add(parcelJson(parcel))
        }

        val providers = JsonArray()

        for (resolved in lookup.allShipping()) {
            val carrier = usableOrNull(resolved.id, sqlClient) ?: continue
            val provider = resolved.provider

            providers.add(
                JsonObject().put("id", resolved.id).put("name", provider.descriptor.displayName.resolve("en-US")).put("state", "AVAILABLE").put("services", servicesOf(carrier))
                    .put("capabilities", capabilitiesJson(carrier.capabilities))
                    .put("balance", balanceOf(carrier))
                    .put("suggested", resolved.id == quote?.getString("providerId"))
            )
        }

        val sender = (usableOrNull(quote?.getString("providerId") ?: ManualShippingProvider.ID, sqlClient)?.let { senderOf(it, sqlClient) }) ?: manual?.sender

        return JsonObject()
            .put("address", if (withAddress) parse(order.shippingAddress) else null)
            .put("quote", quote?.copy()?.put("methodId", order.shippingMethodId)?.put("methodName", order.shippingMethodName))
            .put("lines", lines)
            .put("suggestedParcels", suggested)
            .put("providers", providers)
            .put("senderAddress", sender?.let { addressJson(it) })
            .put("quoteExpired", quote?.getLong("expiresAt")?.let { it < now } ?: false)
    }

    private fun parcelJson(p: Parcel): JsonObject =
        JsonObject().put("weightGrams", p.weightGrams).put("lengthMm", p.lengthMm).put("widthMm", p.widthMm).put("heightMm", p.heightMm)

    private fun capabilitiesJson(c: ShippingCapabilities): JsonObject = JsonObject()
        .put("rateQuote", c.rateQuote).put("createShipment", c.createShipment).put("cancel", c.cancel).put("trackingPull", c.trackingPull)
        .put("trackingPush", c.trackingPush).put("externalTracking", c.externalTracking).put("requiresDimensions", c.requiresDimensions)
        .put("requiresCustomsData", c.requiresCustomsData).put("maxParcels", c.maxParcels).put("labelFormats", JsonArray(c.labelFormats.map { it.name }))

    /** `listServices`, cached 10 minutes per provider, empty on any error (10 section 9.2). */
    private suspend fun servicesOf(carrier: Carrier): JsonArray {
        val id = carrier.provider.id
        val now = clock.now()
        val hit = servicesCache[id]?.takeIf { now - it.first < SERVICES_TTL_MS }?.second

        val services = hit ?: try {
            withTimeout(fx().servicesTimeoutMs) { carrier.provider.listServices(contexts.create(carrier.provider, carrier.settings, carrier.testMode)) }
                .also { servicesCache[id] = now to it }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) emptyList() else throw e
        } catch (e: Exception) {
            emptyList()
        }

        return JsonArray(services.map { JsonObject().put("code", it.code).put("name", it.name).put("carrierName", it.carrierName).put("international", it.international) })
    }

    private suspend fun balanceOf(carrier: Carrier): JsonObject? {
        if (!carrier.capabilities.prepaidBalance) return null

        return try {
            withTimeout(fx().servicesTimeoutMs) { carrier.provider.balance(contexts.create(carrier.provider, carrier.settings, carrier.testMode)) }
                ?.let { JsonObject().put("amount", money(it.amount)).put("currency", it.currency) }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) null else throw e
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------------------------------------ POST /orders/:id/shipping/rates

    /** `POST /orders/:id/shipping/rates`: live `provider.quote`, 15 s, no cache, over every still-shippable unit (10 section 9.2). */
    suspend fun orderRates(orderId: Long, body: JsonObject, sqlClient: SqlClient): JsonObject {
        val f = fx()
        val order = orderOrNotFound(orderId, sqlClient)
        val providerId = (body.getValue("providerId") as? String)?.takeIf { it.isNotBlank() } ?: throw InvalidShipment(mapOf("providerId" to "REQUIRED"))
        val serviceCode = (body.getValue("serviceCode") as? String)?.takeIf { it.isNotBlank() }?.also { if (it.length > MAX_SERVICE_CODE) throw InvalidShipment(mapOf("serviceCode" to "TOO_LONG")) }
        val carrier = usable(providerId, sqlClient)
        val maxParcels = maxParcelsOf(providerId, carrier.capabilities)
        val parcels = try {
            parseParcels(body.getValue("parcels"), carrier.capabilities, maxParcels)
        } catch (e: ParcelProblem) {
            throw InvalidShipment(mapOf("parcels" to e.code))
        }
        val to = parse(order.shippingAddress)?.let { addressOf(it) } ?: throw OrderNotShippable("NO_ADDRESS")

        if (!carrier.capabilities.rateQuote) throw ShippingProviderError(ProviderErrorCode.UNSUPPORTED.name)

        val from = senderOf(carrier, sqlClient) ?: throw InvalidShipment(mapOf("from" to "SENDER_REQUIRED"))
        val items = f.orderItems.getByOrderIds(listOf(orderId), sqlClient).filter { shippableUnits(it) > 0 }.map { shipItem(it, shippableUnits(it), order.currency) }
        val value = Money(items.sumOf { it.unitValue.amount * it.quantity }, order.currency)

        val result = try {
            withTimeout(f.ratesTimeoutMs) {
                carrier.provider.quote(
                    contexts.create(carrier.provider, carrier.settings, carrier.testMode),
                    QuoteRequest(from, to, parcels, items, value, order.currency, serviceCode)
                )
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) throw ShippingProviderError(TIMEOUT) else throw e
        } catch (e: ProviderException) {
            throw ShippingProviderError(e.code.name)
        } catch (e: Exception) {
            throw ShippingProviderError(INTERNAL)
        }

        if (!result.supported) throw ShippingProviderError(ProviderErrorCode.UNSUPPORTED.name)

        return JsonObject().put(
            "rates",
            JsonArray(
                result.rates.map { r ->
                    JsonObject().put("serviceCode", r.serviceCode).put("serviceName", r.serviceName).put("carrierName", r.carrierName).put("price", money(r.price.amount))
                        .put("currency", r.price.currency).put("rateRef", r.rateRef).put("minDays", r.minDays).put("maxDays", r.maxDays).put("expiresAt", r.expiresAt)
                        .put("priceIncludesTax", r.priceIncludesTax)
                }
            )
        )
    }

    private fun maxParcelsOf(providerId: String, caps: ShippingCapabilities): Int = if (providerId == ManualShippingProvider.ID) maxOf(caps.maxParcels, MANUAL_MAX_PARCELS) else caps.maxParcels

    private class ParcelProblem(val code: String) : Exception(code)

    /** The parcels of a request (10 section 9.3); a problem is a [ParcelProblem] the caller turns into `INVALID_SHIPMENT {parcels: code}`. */
    private fun parseParcels(raw: Any?, caps: ShippingCapabilities, maxParcels: Int): List<Parcel> {
        val array = raw as? JsonArray

        if (array == null || array.isEmpty) throw ParcelProblem("REQUIRED")
        if (array.size() > maxParcels) throw ParcelProblem("TOO_MANY")

        return array.map { entry ->
            val o = entry as? JsonObject ?: throw ParcelProblem("OUT_OF_RANGE")

            fun number(key: String): Int? = when (val v = o.getValue(key)) {
                null -> null
                is Number -> v.takeIf { it.toDouble() == Math.floor(it.toDouble()) && Math.abs(it.toDouble()) < 2.0E9 }?.toInt() ?: throw ParcelProblem("OUT_OF_RANGE")
                else -> throw ParcelProblem("OUT_OF_RANGE")
            }

            val weight = number("weightGrams")
            val dims = listOf(number("lengthMm"), number("widthMm"), number("heightMm"))

            if (weight == null || weight !in 1..MAX_PARCEL_GRAMS) throw ParcelProblem("OUT_OF_RANGE")
            if (dims.any { it != null } && dims.any { it == null } || dims.any { it != null && it !in 1..MAX_PARCEL_MM }) throw ParcelProblem("OUT_OF_RANGE")
            if (caps.requiresDimensions && dims.any { it == null }) throw ParcelProblem("DIMENSIONS_REQUIRED")

            Parcel(weight, dims[0], dims[1], dims[2])
        }
    }

    private fun shipItem(item: MarketOrderItem, quantity: Int, currency: String): ShipItem {
        val snapshot = parse(item.snapshot)

        return ShipItem(
            item.id, item.productName, item.sku, quantity, maxOf(0, snapshot?.getInteger("weightGrams") ?: 0), unitMoney(item.lineTotal, item.quantity, currency),
            snapshot?.getString("hsCode"), snapshot?.getString("originCountry")
        )
    }

    /** `lineTotal / quantity` half up; a zero-decimal currency rounds to whole units (a `Money` of such a currency must). */
    private fun unitMoney(lineTotal: Long, quantity: Int, currency: String): Money {
        val unit = BigDecimal.valueOf(lineTotal).divide(BigDecimal.valueOf(maxOf(1, quantity).toLong()), 0, java.math.RoundingMode.HALF_UP).toLong()

        return Money(if (Currencies.exponent(currency) == 0) Math.round(unit / 100.0) * 100L else unit, currency)
    }

    // ------------------------------------------------------------------------------------------------ POST /orders/:id/shipments

    private class ManualEntry(val carrierName: String?, val trackingNumber: String?, val trackingUrl: String?)

    private class Draft(
        val items: List<Pair<Long, Int>>,
        val providerId: String,
        val serviceCode: String?,
        val rateRef: String?,
        val manual: ManualEntry?,
        val note: String?,
        val parcelsRaw: Any?
    )

    private fun parseDraft(body: JsonObject): Draft {
        val errors = LinkedHashMap<String, String>()
        val items = ArrayList<Pair<Long, Int>>()
        val raw = body.getValue("items") as? JsonArray

        if (raw == null || raw.isEmpty) {
            errors["items"] = "REQUIRED"
        } else {
            val seen = HashSet<Long>()

            for (entry in raw) {
                val o = entry as? JsonObject
                val id = (o?.getValue("orderItemId") as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) && it.toLong() >= 1 }?.toLong()
                val quantity = (o?.getValue("quantity") as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) && it.toDouble() in 1.0..2.0E9 }?.toInt()

                when {
                    id == null || o == null -> errors.putIfAbsent("items", "INVALID_QUANTITY")
                    quantity == null -> errors.putIfAbsent("items", "INVALID_QUANTITY")
                    !seen.add(id) -> errors.putIfAbsent("items", "DUPLICATE")
                    else -> items += id to quantity
                }
            }
        }

        val providerId = (body.getValue("providerId") as? String)?.takeIf { it.isNotBlank() }

        if (providerId == null) errors["providerId"] = "REQUIRED"

        fun optionalText(key: String, max: Int): String? {
            val value = body.getValue(key) ?: return null

            if (value !is String || value.length > max) {
                errors[key] = "TOO_LONG"

                return null
            }

            return value.takeIf { it.isNotBlank() }
        }

        val serviceCode = optionalText("serviceCode", MAX_SERVICE_CODE)
        val rateRef = optionalText("rateRef", MAX_RATE_REF)
        val note = optionalText("note", MAX_NOTE)
        var manual: ManualEntry? = null

        when (val m = body.getValue("manual")) {
            null -> Unit

            is JsonObject -> {
                val carrierName = (m.getValue("carrierName") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val trackingNumber = (m.getValue("trackingNumber") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val trackingUrl = (m.getValue("trackingUrl") as? String)?.trim()?.takeIf { it.isNotEmpty() }

                if (m.getValue("carrierName") != null && (m.getValue("carrierName") !is String || (carrierName?.length ?: 0) > MAX_CARRIER_NAME)) errors["manual.carrierName"] = "INVALID"
                if (m.getValue("trackingNumber") != null && (m.getValue("trackingNumber") !is String || trackingNumber != null && !isTrackingNumber(trackingNumber))) errors["manual.trackingNumber"] = "INVALID"
                if (trackingUrl != null && TrackingUrl.accept(trackingUrl) == null) errors["manual.trackingUrl"] = "INVALID"
                if (m.getValue("trackingUrl") != null && m.getValue("trackingUrl") !is String) errors["manual.trackingUrl"] = "INVALID"

                manual = ManualEntry(carrierName, trackingNumber, trackingUrl)
            }

            else -> errors["manual"] = "INVALID"
        }

        if (errors.isNotEmpty()) throw InvalidShipment(errors)

        return Draft(items, providerId!!, serviceCode, rateRef, manual, note, body.getValue("parcels"))
    }

    private fun isTrackingNumber(value: String): Boolean = value.length <= MAX_TRACKING_NUMBER && TRACKING_NUMBER.matches(value)

    /** What the transaction of a create leaves for the carrier call. */
    private class CreatedShipment(val shipmentId: Long, val carrierMode: Boolean)

    /**
     * `POST /orders/:id/shipments` (10 section 9.3): validates, allocates the units under the order lock, inserts the shipment and, for a
     * manual entry, marks it `IN_TRANSIT` in the same transaction. A carrier shipment is created after the commit, outside any
     * transaction: a carrier failure answers 502 `SHIPPING_PROVIDER_ERROR` and leaves the row `CREATED` with its units allocated.
     * Returns the panel `shipment` JSON.
     */
    suspend fun createShipment(orderId: Long, body: JsonObject, actorUserId: Long?, sqlClient: SqlClient): JsonObject {
        val f = fx()
        val draft = parseDraft(body)

        orderOrNotFound(orderId, sqlClient)

        val carrier = usable(draft.providerId, sqlClient)
        val caps = carrier.capabilities
        val isManual = draft.providerId == ManualShippingProvider.ID
        val manualMode = isManual || draft.manual != null
        val errors = LinkedHashMap<String, String>()

        if (!isManual && draft.manual != null) {
            if (!caps.externalTracking) errors["manual"] = "NOT_SUPPORTED" else if (draft.manual.trackingNumber == null) errors["manual"] = "TRACKING_REQUIRED"
        }

        if (!manualMode && !caps.createShipment) errors["providerId"] = "CREATE_NOT_SUPPORTED"

        val parcels = try {
            parseParcels(draft.parcelsRaw, caps, maxParcelsOf(draft.providerId, caps))
        } catch (e: ParcelProblem) {
            errors["parcels"] = e.code

            emptyList()
        }

        if (errors.isNotEmpty()) throw InvalidShipment(errors)

        val sender = senderOf(carrier, sqlClient)

        if (!manualMode && sender == null) throw InvalidShipment(mapOf("from" to "SENDER_REQUIRED"))

        val now = clock.now()

        val created = inOrder(orderId) { conn, locked ->
            val order = locked.order

            shippableOrThrow(order)

            val byId = locked.items.associateBy { it.id }
            val itemErrors = LinkedHashMap<String, String>()

            for ((id, _) in draft.items) {
                val item = byId[id]

                if (item == null || !isShippableItem(item)) itemErrors["items"] = "UNKNOWN_ITEM"
            }

            if (itemErrors.isNotEmpty()) throw InvalidShipment(itemErrors)

            val to = parse(order.shippingAddress) ?: throw OrderNotShippable("NO_ADDRESS")

            if (!manualMode && caps.requiresCustomsData && !sender!!.country.equals(to.getString("country"), ignoreCase = true)) {
                for ((id, _) in draft.items) {
                    val snapshot = parse(byId.getValue(id).snapshot)

                    if (snapshot?.getString("hsCode").isNullOrBlank() || snapshot?.getString("originCountry").isNullOrBlank()) throw InvalidShipment(mapOf("items" to "CUSTOMS_DATA_MISSING"))
                }
            }

            for ((id, quantity) in draft.items) {
                val rows = conn.preparedQuery(
                    "UPDATE ${table("market_order_item")} SET `shippedQuantity` = `shippedQuantity` + ?, `updatedAt` = ? " +
                        "WHERE `id` = ? AND `orderId` = ? AND `shippedQuantity` + ? <= `quantity` - `refundedQuantity`"
                ).execute(Tuple.of(quantity, now, id, orderId, quantity)).coAwait().rowCount()

                if (rows == 0) throw InvalidShipment(mapOf("items" to "EXCEEDS_SHIPPABLE"))
            }

            val method = order.shippingMethodId?.let { methods.getById(it, conn) }
            val packages = JsonArray(parcels.map { parcelJson(it) })
            val trackingNumber = draft.manual?.trackingNumber
            val carrierName = if (manualMode) draft.manual?.carrierName ?: method?.carrierName else null
            val trackingUrl = if (manualMode) {
                TrackingUrl.accept(draft.manual?.trackingUrl) ?: if (trackingNumber != null && method?.trackingUrlTemplate != null) TrackingUrl.render(method.trackingUrlTemplate, trackingNumber) else null
            } else {
                null
            }
            val totalWeight = parcels.sumOf { it.weightGrams.toLong() }
            val testMode = carrier.testMode || f.config().testMode || order.testMode
            val pollsExternal = manualMode && !isManual && caps.trackingPull

            var shipmentId: Long? = null
            var merchantReference = ""

            for (attempt in 1..MERCHANT_REFERENCE_TRIES) {
                merchantReference = f.ids.publicId().takeLast(MERCHANT_REFERENCE_LENGTH)

                shipmentId = f.shipments.add(
                    MarketShipment(
                        orderId = orderId, methodId = order.shippingMethodId, providerId = draft.providerId, serviceCode = draft.serviceCode, status = ShipmentStatus.CREATED,
                        entryMode = if (manualMode) ShipmentEntryMode.MANUAL else ShipmentEntryMode.CARRIER, merchantReference = merchantReference,
                        carrierReference = if (manualMode) merchantReference else null, trackingNumber = trackingNumber, trackingUrl = trackingUrl, carrierName = carrierName,
                        rateRef = draft.rateRef, weightGrams = totalWeight.takeIf { it <= Int.MAX_VALUE }?.toInt(),
                        packages = packages.encode(), note = draft.note, claimedUntil = if (manualMode) null else now + CLAIM_MS,
                        fromAddress = (sender?.let { addressJson(it) } ?: JsonObject()).encode(), toAddress = to.encode(), testMode = testMode,
                        nextPollAt = if (pollsExternal) TrackingSchedule.first(now) else null, createdBy = actorUserId, createdAt = now, updatedAt = now
                    ),
                    conn
                )

                if (shipmentId != null) break
            }

            checkNotNull(shipmentId) { "no free merchant reference after $MERCHANT_REFERENCE_TRIES tries" }

            for ((id, quantity) in draft.items) f.shipmentItems.add(MarketShipmentItem(shipmentId = shipmentId, orderItemId = id, quantity = quantity, createdAt = now, updatedAt = now), conn)

            orderEvent(
                conn, orderId, OrderEventType.SHIPMENT_CREATED, OrderActorType.ADMIN, actorUserId,
                JsonObject().put("shipmentId", shipmentId).put("providerId", draft.providerId).put("entryMode", if (manualMode) "MANUAL" else "CARRIER")
                    .put("items", JsonArray(draft.items.map { (id, q) -> JsonObject().put("orderItemId", id).put("quantity", q) }))
            )

            if (manualMode) {
                applyCore(conn, locked, shipmentId, TrackingUpdate(ShipmentTarget.Id(shipmentId), listOf(TrackingEvent(SpiShipmentStatus.IN_TRANSIT, now))), TrackingSource.MANUAL, OrderActorType.ADMIN, actorUserId)
            } else {
                rederive(conn, orderId)
            }

            CreatedShipment(shipmentId, !manualMode)
        }

        if (created.carrierMode) runCarrierCreate(created.shipmentId, carrier, actorUserId)

        return shipmentJson(created.shipmentId, true, sqlClient)
    }

    /** Steps 5 to 7 of 10 section 9.3 for [shipmentId] (also the retry): the carrier call outside a transaction, then tx2. */
    private suspend fun runCarrierCreate(shipmentId: Long, carrier: Carrier, actorUserId: Long?) {
        val f = fx()
        val row = f.db.tx { conn -> f.shipments.getById(shipmentId, conn) } ?: throw NotFound()
        val order = f.db.tx { conn -> f.orders.getById(row.orderId, conn) } ?: throw NotFound()
        val lines = f.db.tx { conn -> f.shipmentItems.getByShipmentId(shipmentId, conn).mapNotNull { l -> f.orderItems.getById(l.orderItemId, conn)?.let { it to l.quantity } } }
        val items = lines.map { (item, quantity) -> shipItem(item, quantity, order.currency) }
        val declared = Money(items.sumOf { it.unitValue.amount * it.quantity }, order.currency)
        val parcels = (parse("{\"p\":${row.packages ?: "[]"}}")?.getJsonArray("p") ?: JsonArray()).map { p ->
            val o = p as JsonObject

            Parcel(o.getInteger("weightGrams"), o.getInteger("lengthMm"), o.getInteger("widthMm"), o.getInteger("heightMm"))
        }
        val previousData = row.providerData?.let { stored -> cipher.decrypt(stored) ?: stored }?.let { parse(it) }

        val request = CreateShipmentRequest(
            shipmentId, row.merchantReference, order.publicId ?: order.id.toString(), addressOf(JsonObject(row.fromAddress)), addressOf(JsonObject(row.toAddress)), parcels, items,
            row.serviceCode, row.rateRef, null, declared, row.note, row.carrierReference, previousData
        )

        val outcome: Any = try {
            withTimeout(f.createTimeoutMs) { carrier.provider.createShipment(contexts.create(carrier.provider, carrier.settings, row.testMode), request) }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) e else throw e
        } catch (e: Exception) {
            if (e !is ProviderException) log.warn("shipment ${row.id}: ${carrier.provider.id} createShipment failed", e)

            e
        }

        when (outcome) {
            is CreateShipmentResult.Created -> {
                val stored = storeDocuments(shipmentId, outcome)

                finishCreate(row, outcome, stored, carrier.capabilities, actorUserId)
            }

            is CreateShipmentResult.Failed -> {
                recordCreateFailure(row, outcome.code.name, outcome.message, outcome.carrierReference, outcome.providerData)

                throw ShippingProviderError(outcome.code.name, shipmentId)
            }

            else -> {
                val e = outcome as Throwable

                recordCreateFailure(row, errorCodeOf(e), safeMessage(e), null, null)

                throw ShippingProviderError(errorCodeOf(e), shipmentId)
            }
        }
    }

    private suspend fun recordCreateFailure(row: MarketShipment, code: String, message: String?, carrierReference: String?, providerData: JsonObject?) {
        inOrder(row.orderId) { conn, _ ->
            val sets = LinkedHashMap<String, Any?>()

            sets["lastErrorCode"] = code.take(MAX_ERROR_CODE)
            sets["lastError"] = message?.take(MAX_LAST_ERROR)
            sets["claimedUntil"] = null

            if (carrierReference != null) sets["carrierReference"] = carrierReference.take(MAX_CARRIER_REFERENCE)
            if (providerData != null) sets["providerData"] = cipher.encrypt(providerData.encode())

            setShipment(conn, row.id, sets)
        }
    }

    private class StoredDocuments(val labelFile: String?, val labelFormat: String?, val documents: JsonArray, val pieces: Map<Int, String>)

    /** Label and document bytes to `<labelsDir>/<shipmentId>-<n>.<ext>` on a worker thread (at most 20, at most 5 MB each; the rest dropped with a warning). */
    private suspend fun storeDocuments(shipmentId: Long, created: CreateShipmentResult.Created): StoredDocuments {
        val f = fx()
        val all = ArrayList<LabelDocument>()

        all += created.labels
        all += created.documents

        val pieceDocs = created.pieces.withIndex().mapNotNull { (i, p) -> p.label?.let { i to it } }
        var count = 0
        var labelFile: String? = null
        var labelFormat: String? = null
        val documents = JsonArray()
        val pieces = HashMap<Int, String>()

        suspend fun write(name: String, doc: LabelDocument): Boolean {
            if (count >= MAX_DOCUMENTS) {
                log.warn("shipment $shipmentId: more than $MAX_DOCUMENTS documents, the rest is dropped")

                return false
            }

            if (doc.bytes.size > MAX_DOCUMENT_BYTES) {
                log.warn("shipment $shipmentId: a document of ${doc.bytes.size} bytes is dropped")

                return false
            }

            withContext(Dispatchers.IO) {
                Files.createDirectories(f.labelsDir)
                Files.write(f.labelsDir.resolve(name), doc.bytes)
            }

            count++

            return true
        }

        all.forEachIndexed { n, doc ->
            val name = "$shipmentId-$n.${extensionOf(doc.format)}"

            if (!write(name, doc)) return@forEachIndexed

            if (labelFile == null && doc === created.labels.firstOrNull()) {
                labelFile = name
                labelFormat = doc.format.name
            } else {
                documents.add(JsonObject().put("index", documents.size() + 1).put("type", doc.kind).put("format", doc.format.name).put("file", name))
            }
        }

        for ((i, doc) in pieceDocs) {
            val name = "$shipmentId-p$i.${extensionOf(doc.format)}"

            if (write(name, doc)) pieces[i] = name
        }

        return StoredDocuments(labelFile, labelFormat, documents, pieces)
    }

    private fun extensionOf(format: LabelFormat): String = when (format) {
        LabelFormat.PDF -> "pdf"
        LabelFormat.PNG -> "png"
        LabelFormat.GIF -> "gif"
        LabelFormat.SVG -> "svg"
        LabelFormat.HTML -> "html"
        LabelFormat.ZPL -> "zpl"
        LabelFormat.EPL -> "epl"
    }

    /** tx2 of a carrier create (10 section 9.3 step 6). */
    private suspend fun finishCreate(row: MarketShipment, created: CreateShipmentResult.Created, stored: StoredDocuments, caps: ShippingCapabilities, actorUserId: Long?) {
        val f = fx()

        inOrder(row.orderId) { conn, locked ->
            val current = f.shipments.getById(row.id, conn) ?: return@inOrder

            if (current.status != ShipmentStatus.CREATED) {
                log.warn("shipment ${row.id} left CREATED while the carrier call was in flight; the carrier result is not applied")

                return@inOrder
            }

            val now = clock.now()
            val sets = LinkedHashMap<String, Any?>()
            val method = current.methodId?.let { methods.getById(it, conn) }

            sets["carrierReference"] = created.carrierReference.take(MAX_CARRIER_REFERENCE)

            created.trackingNumber?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TRACKING_NUMBER }?.let { sets["trackingNumber"] = it }

            val number = (sets["trackingNumber"] as? String) ?: current.trackingNumber
            val url = TrackingUrl.accept(created.trackingUrl) ?: if (number != null && method?.trackingUrlTemplate != null) TrackingUrl.render(method.trackingUrlTemplate, number) else null

            if (url != null) sets["trackingUrl"] = url

            (created.carrierName ?: method?.carrierName)?.take(MAX_CARRIER_NAME)?.let { sets["carrierName"] = it }

            sets["labelFile"] = stored.labelFile
            sets["labelFormat"] = stored.labelFormat
            sets["documents"] = if (stored.documents.isEmpty) null else stored.documents.encode()
            created.cost?.let {
                sets["cost"] = it.amount
                sets["costCurrency"] = it.currency
            }
            created.providerData?.let { sets["providerData"] = cipher.encrypt(it.encode()) }

            if (created.pieces.isNotEmpty()) {
                val packages = JsonArray(current.packages ?: "[]")

                created.pieces.forEachIndexed { i, piece ->
                    val p = packages.getJsonObject(i) ?: return@forEachIndexed

                    p.put("trackingNumber", piece.trackingNumber)
                    stored.pieces[i]?.let { p.put("labelFile", it) }
                }

                sets["packages"] = packages.encode()
            }

            val reported = ShipmentStatus.valueOf(created.status.name)
            val base = when {
                stored.labelFile != null -> ShipmentStatus.LABEL_READY
                reported == ShipmentStatus.CREATED || reported == ShipmentStatus.LABEL_READY -> reported
                else -> ShipmentStatus.CREATED
            }

            sets["status"] = base.name
            sets["claimedUntil"] = null
            sets["lastErrorCode"] = null
            sets["lastError"] = null

            if (caps.trackingPull) sets["nextPollAt"] = TrackingSchedule.first(now)

            setShipment(conn, row.id, sets)

            if (reported in ShipmentStateMachine.HANDED) {
                // the carrier already has the parcel: the same path as a tracking event
                applyCore(conn, locked, row.id, TrackingUpdate(ShipmentTarget.Id(row.id), listOf(TrackingEvent(created.status, now))), TrackingSource.POLL, OrderActorType.SYSTEM, actorUserId)
            } else {
                maybeQueueShippedMail(conn, locked.order, f.shipments.getById(row.id, conn)!!)
                rederive(conn, row.orderId)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ retry

    /** `POST /shipments/:id/retry` (10 section 9.4): the same `merchantReference` goes to the carrier again; two-step carriers get their previous reference back. */
    suspend fun retryShipment(shipmentId: Long, actorUserId: Long?, sqlClient: SqlClient): JsonObject {
        val row = shipmentOrNotFound(shipmentId, sqlClient)
        val retriable = row.entryMode == ShipmentEntryMode.CARRIER && row.status == ShipmentStatus.CREATED && (row.carrierReference == null || row.lastErrorCode != null)

        if (!retriable || row.lastErrorCode == ShipmentErrorCode.RATE_EXPIRED.name) throw InvalidShipmentTransition(row.status.name, "RETRY")

        val carrier = usable(row.providerId, sqlClient)
        val now = clock.now()

        val claimed = inOrder(row.orderId) { conn, _ ->
            conn.preparedQuery(
                "UPDATE ${table("market_shipment")} SET `claimedUntil` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'CREATED' AND `entryMode` = 'CARRIER' " +
                    "AND (`carrierReference` IS NULL OR `lastErrorCode` IS NOT NULL) AND (`claimedUntil` IS NULL OR `claimedUntil` < ?)"
            ).execute(Tuple.of(now + CLAIM_MS, now, shipmentId, now)).coAwait().rowCount() > 0
        }

        if (!claimed) throw InvalidShipmentTransition(row.status.name, "RETRY")

        runCarrierCreate(shipmentId, carrier, actorUserId)

        return shipmentJson(shipmentId, true, sqlClient)
    }

    // ------------------------------------------------------------------------------------------------ cancel

    /**
     * `POST /shipments/:id/cancel` (10 section 9.5). Returns the shipment JSON; `force` cancels locally when the carrier refuses, is unavailable or cannot cancel.
     * The caller writes the activity log with the `forced` flag.
     */
    suspend fun cancelShipment(shipmentId: Long, force: Boolean, actorUserId: Long?, sqlClient: SqlClient): JsonObject {
        val row = shipmentOrNotFound(shipmentId, sqlClient)
        val now = clock.now()

        if (row.status in ShipmentStateMachine.TERMINAL) throw ShipmentNotCancellable("TERMINAL")

        var callCarrier = false

        if (row.entryMode == ShipmentEntryMode.CARRIER) {
            if (row.carrierReference == null) {
                if ((row.claimedUntil ?: 0) > now) throw ShipmentNotCancellable("IN_PROGRESS")
            } else if (row.status in ShipmentStateMachine.HANDED) {
                throw ShipmentNotCancellable("HANDED_OVER")
            } else {
                callCarrier = true
            }
        }

        if (callCarrier) {
            val carrier = try {
                usable(row.providerId, sqlClient)
            } catch (e: ProviderUnavailable) {
                if (!force) throw e

                null
            }

            if (carrier != null && !carrier.capabilities.cancel) {
                if (!force) throw ShipmentNotCancellable("UNSUPPORTED")
            } else if (carrier != null) {
                val outcome: Any = try {
                    withTimeout(fx().cancelTimeoutMs) { carrier.provider.cancelShipment(contexts.create(carrier.provider, carrier.settings, row.testMode), viewOf(row)) }
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) e else throw e
                } catch (e: Exception) {
                    if (e !is ProviderException) log.warn("shipment ${row.id}: ${carrier.provider.id} cancelShipment failed", e)

                    e
                }

                val cancelled = outcome is CancelShipmentResult && outcome.cancelled

                if (!cancelled && !force) {
                    val unsupported = outcome is CancelShipmentResult && !outcome.supported

                    if (unsupported) throw ShipmentNotCancellable("UNSUPPORTED")

                    val message = (outcome as? CancelShipmentResult)?.message ?: safeMessage(outcome as Throwable)

                    inOrder(row.orderId) { conn, _ -> setShipment(conn, row.id, mapOf("lastError" to message?.take(MAX_LAST_ERROR))) }

                    throw ShippingProviderError(ProviderErrorCode.GATEWAY_REJECTED.name, shipmentId)
                }
            }
        }

        inOrder(row.orderId) { conn, locked ->
            val current = fx().shipments.getById(shipmentId, conn) ?: throw NotFound()

            if (current.status in ShipmentStateMachine.TERMINAL) throw ShipmentNotCancellable("TERMINAL")

            val t = clock.now()

            if (!fx().shipments.transition(shipmentId, current.status, ShipmentStatus.CANCELLED, t, conn)) throw ShipmentNotCancellable("TERMINAL")

            setShipment(conn, shipmentId, mapOf("cancelledAt" to t, "nextPollAt" to null, "claimedUntil" to null))
            release(conn, current)
            rederive(conn, locked.order.id)
            orderEvent(conn, locked.order.id, OrderEventType.SHIPMENT_CANCELLED, OrderActorType.ADMIN, actorUserId, JsonObject().put("shipmentId", shipmentId).put("forced", force))
        }

        return shipmentJson(shipmentId, true, sqlClient)
    }

    private fun viewOf(row: MarketShipment): ShipmentView = ShipmentView(
        row.id, row.merchantReference, row.carrierReference, row.trackingNumber, SpiShipmentStatus.valueOf(row.status.name), row.serviceCode,
        addressOf(JsonObject(row.toAddress)), row.providerData?.let { stored -> cipher.decrypt(stored) ?: stored }?.let { parse(it) }, row.testMode, row.createdAt
    )

    // ------------------------------------------------------------------------------------------------ PUT /shipments/:id

    /**
     * `PUT /shipments/:id` (10 section 9.6): tracking fields, a manual status, note, `releaseItems`. Returns the shipment JSON and the keys that changed
     * (for the activity log, never their values).
     */
    suspend fun editShipment(shipmentId: Long, body: JsonObject, actorUserId: Long?, sqlClient: SqlClient): Pair<JsonObject, List<String>> {
        val row = shipmentOrNotFound(shipmentId, sqlClient)
        val errors = LinkedHashMap<String, String>()

        fun text(key: String, max: Int): String? {
            val v = body.getValue(key) ?: return null

            if (v !is String || v.length > max) {
                errors[key] = "INVALID"

                return null
            }

            return v.trim().takeIf { it.isNotEmpty() }
        }

        val hasNumber = body.containsKey("trackingNumber") && body.getValue("trackingNumber") != null
        val number = text("trackingNumber", MAX_TRACKING_NUMBER)?.also { if (!isTrackingNumber(it)) errors["trackingNumber"] = "INVALID" }
        val url = text("trackingUrl", MAX_URL)?.also { if (TrackingUrl.accept(it) == null) errors["trackingUrl"] = "INVALID" }
        val carrierName = text("carrierName", MAX_CARRIER_NAME)
        val note = text("note", MAX_NOTE)
        val rawStatus = body.getValue("status")
        val status = (rawStatus as? String)?.let { name -> MANUAL_STATUSES.firstOrNull { it.name == name } }
        val releaseRaw = body.getValue("releaseItems")
        val releaseItems = releaseRaw as? Boolean ?: false

        if (rawStatus != null && status == null) errors["status"] = "INVALID"
        if (releaseRaw != null && releaseRaw !is Boolean) errors["releaseItems"] = "INVALID"

        val touchesTracking = hasNumber || url != null

        if (touchesTracking && row.status == ShipmentStatus.CANCELLED) throw InvalidShipmentTransition(row.status.name, row.status.name)
        if (hasNumber && row.entryMode == ShipmentEntryMode.CARRIER && row.carrierReference != null) errors["trackingNumber"] = "READ_ONLY"
        if (errors.isNotEmpty()) throw InvalidShipment(errors)

        val changed = ArrayList<String>()

        inOrder(row.orderId) { conn, locked ->
            val f = fx()
            var current = f.shipments.getById(shipmentId, conn) ?: throw NotFound()

            if (touchesTracking && current.status == ShipmentStatus.CANCELLED) throw InvalidShipmentTransition(current.status.name, current.status.name)

            changed.clear()

            val direct = LinkedHashMap<String, Any?>()

            if (carrierName != null && carrierName != current.carrierName) {
                direct["carrierName"] = carrierName
                changed += "carrierName"
            }

            if (body.containsKey("note") && note != current.note) {
                direct["note"] = note
                changed += "note"
            }

            setShipment(conn, shipmentId, direct)

            val moves = status != null && status != current.status
            val event = if (moves) listOf(TrackingEvent(SpiShipmentStatus.valueOf(status!!.name), clock.now()).also { it.description = note }) else emptyList()

            if (touchesTracking || moves) {
                // tracking fields and the manual status both go through applyUpdate (10 section 9.6)
                val outcome = applyCore(
                    conn, locked, shipmentId,
                    TrackingUpdate(ShipmentTarget.Id(shipmentId), event).also {
                        it.trackingNumber = number
                        it.trackingUrl = url
                    },
                    TrackingSource.MANUAL, OrderActorType.ADMIN, actorUserId
                )
                val after = f.shipments.getById(shipmentId, conn)!!

                if (moves && outcome.to == outcome.from) throw InvalidShipmentTransition(current.status.name, status!!.name)

                if (after.trackingNumber != current.trackingNumber) changed += "trackingNumber"
                if (after.trackingUrl != current.trackingUrl) changed += "trackingUrl"
                if (moves) changed += "status"
            }

            current = f.shipments.getById(shipmentId, conn)!!

            if (releaseItems) {
                if (current.itemsReleased || current.status != ShipmentStatus.RETURNED && current.status != ShipmentStatus.LOST) throw InvalidShipmentTransition(current.status.name, "RELEASED")

                release(conn, current)
                rederive(conn, locked.order.id)
                changed += "releaseItems"
            }
        }

        return shipmentJson(shipmentId, true, sqlClient) to changed.toList()
    }

    // ------------------------------------------------------------------------------------------------ track now

    /** `POST /shipments/:id/track` (10 section 9.7): one `provider.track`, then the updates that resolve to this shipment. */
    suspend fun trackShipment(shipmentId: Long, sqlClient: SqlClient): JsonObject {
        val row = shipmentOrNotFound(shipmentId, sqlClient)
        val carrier = usableOrNull(row.providerId, sqlClient)

        if (carrier == null || !carrier.capabilities.trackingPull) throw StatusQueryNotSupported()
        if (row.carrierReference == null && !(row.trackingNumber != null && carrier.capabilities.externalTracking)) throw StatusQueryNotSupported()

        val updates = try {
            withTimeout(fx().trackTimeoutMs) { carrier.provider.track(contexts.create(carrier.provider, carrier.settings, row.testMode), TrackRequest(listOf(viewOf(row)))) }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) throw ShippingProviderError(TIMEOUT, shipmentId) else throw e
        } catch (e: ProviderException) {
            throw ShippingProviderError(e.code.name, shipmentId)
        } catch (e: Exception) {
            throw ShippingProviderError(INTERNAL, shipmentId)
        }

        for (update in updates) if (resolves(update.target, row)) applyUpdate(shipmentId, update, TrackingSource.POLL)

        inOrder(row.orderId) { conn, _ ->
            val current = fx().shipments.getById(shipmentId, conn) ?: throw NotFound()
            val now = clock.now()
            val next = if (current.status in ShipmentStateMachine.TERMINAL) NextPoll(null, false) else TrackingSchedule.next(current.createdAt, now)

            fx().shipments.recordPoll(shipmentId, now, next.nextPollAt, conn)
            setShipment(conn, shipmentId, mapOf("stale" to if (next.stale) 1 else 0))
        }

        return shipmentJson(shipmentId, true, sqlClient)
    }

    /** Does [target] name [row]? A tracking number also matches a per-piece number (`packages`). */
    private fun resolves(target: ShipmentTarget, row: MarketShipment): Boolean = when (target) {
        is ShipmentTarget.Id -> target.shipmentId == row.id
        is ShipmentTarget.MerchantReference -> target.reference == row.merchantReference
        is ShipmentTarget.CarrierReference -> target.reference == row.carrierReference
        is ShipmentTarget.TrackingNumber -> target.trackingNumber == row.trackingNumber ||
            (parse("{\"p\":${row.packages ?: "[]"}}")?.getJsonArray("p")?.any { (it as? JsonObject)?.getString("trackingNumber") == target.trackingNumber } == true)
    }

    // ------------------------------------------------------------------------------------------------ applyUpdate

    /** What [applyUpdate] did: the status before and after (equal when nothing moved). */
    class Applied(val from: ShipmentStatus, val to: ShipmentStatus, val shippingStatus: ShippingStatus?)

    /**
     * `ShippingService.applyUpdate(shipmentId, update, source)` (10 section 7.2), used by the webhook route, the tracking job, "track now"
     * and manual edits: one transaction under the order lock; the whole function is idempotent (replaying an update changes nothing).
     */
    suspend fun applyUpdate(shipmentId: Long, update: TrackingUpdate, source: TrackingSource): Applied {
        val row = fx().db.tx { conn -> fx().shipments.getById(shipmentId, conn) } ?: throw NotFound()

        val actor = when (source) {
            TrackingSource.WEBHOOK -> OrderActorType.GATEWAY
            TrackingSource.POLL -> OrderActorType.SYSTEM
            TrackingSource.MANUAL -> OrderActorType.ADMIN
        }

        return inOrder(row.orderId) { conn, locked -> applyCore(conn, locked, shipmentId, update, source, actor, null) }
    }

    private suspend fun applyCore(
        conn: SqlConnection, locked: LockedOrder, shipmentId: Long, update: TrackingUpdate, source: TrackingSource, actorType: OrderActorType, actorUserId: Long?
    ): Applied {
        val f = fx()
        val now = clock.now()
        val before = f.shipments.getById(shipmentId, conn) ?: throw NotFound()
        val method = before.methodId?.let { methods.getById(it, conn) }
        val sets = LinkedHashMap<String, Any?>()

        // 2. tracking number and URL
        val number = update.trackingNumber?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TRACKING_NUMBER && it != before.trackingNumber }

        if (number != null) sets["trackingNumber"] = number

        val finalNumber = number ?: before.trackingNumber
        val acceptedUrl = TrackingUrl.accept(update.trackingUrl)
        val url = acceptedUrl ?: if (finalNumber != null && method?.trackingUrlTemplate != null && (number != null || before.trackingUrl == null)) TrackingUrl.render(method.trackingUrlTemplate, finalNumber) else null

        if (url != null && url != before.trackingUrl) sets["trackingUrl"] = url

        // 3. estimate and provider data
        update.estimatedDelivery?.let { if (it != before.estimatedDeliveryAt) sets["estimatedDeliveryAt"] = it }
        update.providerData?.let { sets["providerData"] = cipher.encrypt(it.encode()) }

        // 4. events
        val stored = f.shipmentEvents.countByShipmentId(shipmentId, conn).toInt()
        val room = TrackingEventRules.room(update.events.size, stored)

        if (room < update.events.size) log.warn("shipment $shipmentId: ${update.events.size - room} tracking events dropped (limit)")

        val eventSource = ShipmentEventSource.valueOf(source.name)

        for (event in update.events.take(room)) {
            val occurredAt = TrackingEventRules.clampOccurredAt(event.occurredAt, now)
            val rawStatus = TrackingEventRules.truncate(event.rawStatus, TrackingEventRules.MAX_RAW_STATUS)
            val location = TrackingEventRules.truncate(event.location, TrackingEventRules.MAX_LOCATION)

            f.shipmentEvents.add(
                MarketShipmentEvent(
                    shipmentId = shipmentId, status = ShipmentStatus.valueOf(event.status.name), rawStatus = rawStatus,
                    description = TrackingEventRules.truncate(event.description, TrackingEventRules.MAX_DESCRIPTION), location = location, occurredAt = occurredAt, source = eventSource,
                    dedupeKey = TrackingEventRules.dedupeKey(event.eventId, event.status.name, occurredAt, location, rawStatus), createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        // 5 and 6. the stored event that counts decides
        val events = f.shipmentEvents.getByShipmentId(shipmentId, conn).map { TrackedEvent(it.id, it.status, it.occurredAt, TrackingSource.valueOf(it.source.name)) }
        val resolution = ShipmentStateMachine.resolve(before.status, events)
        val next = resolution.next
        var to = before.status

        if (next != null && f.shipments.transition(shipmentId, before.status, next, now, conn)) {
            to = next

            if (next in ShipmentStateMachine.HANDED && before.shippedAt == null) resolution.shippedAt?.let { sets["shippedAt"] = it }
            if (next == ShipmentStatus.DELIVERED) resolution.deliveredAt?.let { sets["deliveredAt"] = it }
            if (resolution.clearNextPoll) sets["nextPollAt"] = null
        }

        setShipment(conn, shipmentId, sets)

        val after = f.shipments.getById(shipmentId, conn)!!

        // 7. side effects, all in this transaction
        maybeQueueShippedMail(conn, locked.order, after)

        var shippingStatus: ShippingStatus? = null

        if (to != before.status) {
            if (to == ShipmentStatus.DELIVERED) {
                enqueueShipmentMail(conn, locked.order, after, MailKind.SHIPMENT_DELIVERED)
                f.webhooks.emit(conn, WebhookEvents.SHIPMENT_DELIVERED, shipmentId.toString(), locked.order.id, webhookData(conn, locked.order, after), locked.order.testMode)
            }

            shippingStatus = rederive(conn, locked.order.id)
            orderEvent(
                conn, locked.order.id, OrderEventType.SHIPMENT_UPDATED, actorType, actorUserId,
                JsonObject().put("shipmentId", shipmentId).put("from", before.status.name).put("to", to.name).also { if (shippingStatus != null) it.put("shippingStatus", shippingStatus!!.name) }
            )
        }

        return Applied(before.status, to, shippingStatus)
    }

    // ------------------------------------------------------------------------------------------------ mail and webhook

    /** 10 section 11.1: `SHIPMENT_SHIPPED` once per shipment, the store webhook `shipment.shipped` with it. */
    private suspend fun maybeQueueShippedMail(conn: SqlConnection, order: MarketOrder, shipment: MarketShipment) {
        if (shipment.trackingMailSentAt != null || shipment.status == ShipmentStatus.CANCELLED) return

        val hasNumber = !shipment.trackingNumber.isNullOrBlank() && shipment.status != ShipmentStatus.CREATED

        if (!hasNumber && shipment.status !in ShipmentStateMachine.HANDED) return

        val f = fx()

        // the claim of the "once" first: a concurrent caller is excluded by the order lock, a replay by this column
        setShipment(conn, shipment.id, mapOf("trackingMailSentAt" to clock.now()))
        enqueueShipmentMail(conn, order, shipment, MailKind.SHIPMENT_SHIPPED)
        f.webhooks.emit(conn, WebhookEvents.SHIPMENT_SHIPPED, shipment.id.toString(), order.id, webhookData(conn, order, shipment), order.testMode)
    }

    private suspend fun enqueueShipmentMail(conn: SqlConnection, order: MarketOrder, shipment: MarketShipment, kind: MailKind) {
        val email = order.email?.takeIf { it.isNotBlank() } ?: return
        val f = fx()
        val items = shipmentLines(conn, shipment)
        val unshipped = f.orderItems.getByOrderIds(listOf(order.id), conn).sumOf { shippableUnits(it) } > 0
        val to = parse(shipment.toAddress)
        val params = JsonObject()
            .put("orderNumber", order.id.toString())
            .put("orderUrl", orderUrl(order))
            .put("carrierName", shipment.carrierName)

        if (kind == MailKind.SHIPMENT_SHIPPED) {
            params.put("hasTracking", !shipment.trackingNumber.isNullOrBlank()).put("trackingNumber", shipment.trackingNumber)
                .put("hasTrackingUrl", !shipment.trackingUrl.isNullOrBlank()).put("trackingUrl", shipment.trackingUrl)
                .put("hasEstimate", shipment.estimatedDeliveryAt != null).put("estimatedDelivery", shipment.estimatedDeliveryAt?.let { dateOf(it, order.locale) })
                .put("isPartial", unshipped)
                .put("recipientName", listOfNotNull(to?.getString("firstName"), to?.getString("lastName")).joinToString(" "))
                .put("addressLines", addressLines(to))
        } else {
            params.put("deliveredAt", shipment.deliveredAt?.let { dateOf(it, order.locale) })
        }

        params.put("items", JsonArray(items.map { JsonObject().put("name", it.getString("name")).put("variantName", it.getString("variantName")).put("quantity", it.getInteger("quantity")) }))

        f.mail.enqueue(conn, kind, MailRefType.SHIPMENT, shipment.id, "", order.id, order.userId, email, order.locale ?: "", params)
    }

    private fun orderUrl(order: MarketOrder): String {
        val base = fx().siteUrl().trimEnd('/')
        val publicId = order.publicId ?: return ""

        return "$base/store/order/$publicId" + if (order.userId == null && order.accessToken != null) "?token=${order.accessToken}" else ""
    }

    private fun dateOf(millis: Long, locale: String?): String {
        val zone = runCatching { java.time.ZoneId.of(fx().config().storeTimeZone.ifBlank { "UTC" }) }.getOrDefault(java.time.ZoneOffset.UTC)
        val tag = (locale ?: "en-US").replace('_', '-')

        return java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(tag))
            .format(java.time.Instant.ofEpochMilli(millis).atZone(zone))
    }

    private fun addressLines(a: JsonObject?): JsonArray {
        val lines = JsonArray()

        if (a == null) return lines

        a.getString("line1")?.let { lines.add(it) }
        a.getString("line2")?.let { lines.add(it) }

        listOfNotNull(a.getString("district"), a.getString("city"), a.getString("postalCode")).takeIf { it.isNotEmpty() }?.let { lines.add(it.joinToString(" ")) }

        a.getString("state")?.let { lines.add(it) }
        a.getString("country")?.let { code -> lines.add(Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }) }

        return lines
    }

    private suspend fun shipmentLines(conn: SqlClient, shipment: MarketShipment): List<JsonObject> {
        val f = fx()

        return f.shipmentItems.getByShipmentId(shipment.id, conn).mapNotNull { line ->
            f.orderItems.getById(line.orderItemId, conn)?.let { item ->
                JsonObject().put("orderItemId", item.id).put("productId", item.productId).put("name", item.productName).put("variantName", item.variantName).put("sku", item.sku).put("quantity", line.quantity)
            }
        }
    }

    /** 10 section 11.2: no address, no e-mail. */
    private suspend fun webhookData(conn: SqlClient, order: MarketOrder, shipment: MarketShipment): JsonObject = JsonObject()
        .put(
            "order",
            JsonObject().put("id", order.id).put("publicId", order.publicId).put("playerUsername", order.playerUsername)
                .put("recipientUsername", order.recipientUsername.ifEmpty { order.playerUsername })
        )
        .put(
            "shipment",
            JsonObject().put("id", shipment.id).put("status", shipment.status.name).put("carrierName", shipment.carrierName).put("trackingNumber", shipment.trackingNumber)
                .put("trackingUrl", shipment.trackingUrl).put("shippedAt", shipment.shippedAt).put("deliveredAt", shipment.deliveredAt)
                .put("items", JsonArray(shipmentLines(conn, shipment).map { it.copy().also { o -> o.remove("variantName") } }))
        )

    // ------------------------------------------------------------------------------------------------ PUT /orders/:id/shipping-address

    /**
     * `PUT /orders/:id/shipping-address` (10 section 3.5): the admin corrects the frozen address while no live shipment (`itemsReleased = 0`) exists.
     * Same normalisation and base validation as checkout; the shipping price is not recalculated; writes the timeline note, never the address.
     */
    suspend fun editShippingAddress(orderId: Long, body: JsonObject, actorUserId: Long?, sqlClient: SqlClient) {
        orderOrNotFound(orderId, sqlClient)

        inOrder(orderId) { conn, locked ->
            val order = locked.order

            if (!order.requiresShipping) throw OrderNotShippable("STATUS")
            if (fx().shipments.getByOrderId(orderId, conn).any { !it.itemsReleased }) throw OrderNotShippable("HAS_SHIPMENTS")

            val previous = parse(order.shippingAddress)
            val keepIdentity = previous?.containsKey("identityNumber") == true || body.containsKey("identityNumber")
            val normalized = AddressValidator.normalize(addressOf(body), order.email, keepIdentityNumber = keepIdentity)

            if (!Countries.isValid(normalized.country)) throw ShippingAddressRequired(listOf("country"))

            val check = AddressValidator.check(normalized)

            if (!check.valid) throw ShippingAddressRequired(check.fields)

            setOrder(conn, orderId, mapOf("shippingAddress" to addressJson(normalized).encode()))
            orderEvent(conn, orderId, OrderEventType.NOTE, OrderActorType.ADMIN, actorUserId, null, "shipping address updated")
        }
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** One shipment as the panel sees it (10 section 9.9). [detail] adds `items`, `events` and the addresses of `GET /shipments/:id`. */
    suspend fun shipmentJson(shipmentId: Long, withAddress: Boolean, sqlClient: SqlClient, detail: Boolean = false): JsonObject {
        val row = shipmentOrNotFound(shipmentId, sqlClient)

        return shipmentJson(row, withAddress, sqlClient, detail)
    }

    private suspend fun shipmentJson(row: MarketShipment, withAddress: Boolean, sqlClient: SqlClient, detail: Boolean, order: MarketOrder? = null): JsonObject {
        val f = fx()
        val owner = order ?: f.orders.getById(row.orderId, sqlClient)
        val now = clock.now()
        val resolved = lookup.shipping(row.providerId)
        val carrier = usableOrNull(row.providerId, sqlClient)
        val caps = carrier?.capabilities
        val documents = JsonArray((parse("{\"d\":${row.documents ?: "[]"}}")?.getJsonArray("d") ?: JsonArray()).map { d ->
            val o = d as JsonObject

            JsonObject().put("index", o.getInteger("index")).put("type", o.getString("type")).put("format", o.getString("format"))
        })
        val packages = JsonArray((parse("{\"p\":${row.packages ?: "[]"}}")?.getJsonArray("p") ?: JsonArray()).map { p ->
            val o = (p as JsonObject).copy()
            val hasLabel = o.getString("labelFile") != null

            o.remove("labelFile")
            o.put("hasLabel", hasLabel)
        })
        val live = row.status !in ShipmentStateMachine.TERMINAL
        val retry = row.entryMode == ShipmentEntryMode.CARRIER && row.status == ShipmentStatus.CREATED && (row.carrierReference == null || row.lastErrorCode != null) &&
            row.lastErrorCode != ShipmentErrorCode.RATE_EXPIRED.name && (row.claimedUntil ?: 0) < now
        val cancel = live && !(row.entryMode == ShipmentEntryMode.CARRIER && row.status in ShipmentStateMachine.HANDED) &&
            !(row.entryMode == ShipmentEntryMode.CARRIER && row.carrierReference == null && (row.claimedUntil ?: 0) > now)
        val track = caps?.trackingPull == true && (row.carrierReference != null || row.trackingNumber != null && caps.externalTracking) && row.status != ShipmentStatus.CANCELLED
        val editStatus = MANUAL_STATUSES.any { ShipmentStateMachine.decide(row.status, it, TrackingSource.MANUAL) != null }

        val json = JsonObject()
            .put("id", row.id).put("orderId", row.orderId).put("orderPublicId", owner?.publicId).put("playerUsername", owner?.playerUsername)
            .put("methodId", row.methodId).put("providerId", row.providerId).put("providerName", resolved?.provider?.descriptor?.displayName?.resolve("en-US") ?: row.providerId)
            .put("entryMode", row.entryMode.name).put("serviceCode", row.serviceCode).put("status", row.status.name).put("merchantReference", row.merchantReference)
            .put("carrierReference", row.carrierReference).put("trackingNumber", row.trackingNumber).put("trackingUrl", row.trackingUrl).put("carrierName", row.carrierName)
            .put("hasLabel", row.labelFile != null).put("labelFormat", row.labelFormat).put("documents", documents)
            .put("cost", row.cost?.let { money(it) }).put("costCurrency", row.costCurrency).put("weightGrams", row.weightGrams).put("packages", packages)
            .put("testMode", row.testMode).put("stale", row.stale).put("lastErrorCode", row.lastErrorCode).put("lastError", row.lastError)
            .put("estimatedDeliveryAt", row.estimatedDeliveryAt).put("shippedAt", row.shippedAt).put("deliveredAt", row.deliveredAt).put("cancelledAt", row.cancelledAt)
            .put("lastPolledAt", row.lastPolledAt).put("nextPollAt", row.nextPollAt).put("itemsReleased", row.itemsReleased).put("note", row.note)
            .put("createdAt", row.createdAt).put("updatedAt", row.updatedAt)
            .put(
                "allowed",
                JsonObject().put("retry", retry).put("cancel", cancel).put("track", track).put("editStatus", editStatus)
                    .put("releaseItems", (row.status == ShipmentStatus.RETURNED || row.status == ShipmentStatus.LOST) && !row.itemsReleased).put("label", true)
            )

        if (detail) {
            json.put("items", JsonArray(shipmentLines(sqlClient, row).map { it.copy().also { o -> o.remove("productId") } }))
                .put(
                    "events",
                    JsonArray(
                        f.shipmentEvents.getByShipmentId(row.id, sqlClient).sortedWith(compareByDescending<MarketShipmentEvent> { it.occurredAt }.thenByDescending { it.id }).map {
                            JsonObject().put("id", it.id).put("status", it.status.name).put("rawStatus", it.rawStatus).put("description", it.description).put("location", it.location)
                                .put("occurredAt", it.occurredAt).put("source", it.source.name)
                        }
                    )
                )

            if (withAddress) json.put("toAddress", parse(row.toAddress)).put("fromAddress", parse(row.fromAddress))
        }

        return json
    }

    /** The filters of `GET /shipments`. */
    class ShipmentFilter(val statuses: List<ShipmentStatus> = emptyList(), val providerId: String? = null, val stale: Boolean? = null, val search: String? = null)

    class ShipmentPage(val shipments: List<JsonObject>, val count: Long)

    /** `GET /shipments` (10 section 9.9): newest first. `search` matches tracking number, merchant / carrier reference, order id, `publicId` and player name. */
    suspend fun listShipments(filter: ShipmentFilter, window: Paging.Window, sqlClient: SqlClient): ShipmentPage {
        val where = ArrayList<String>()
        val values = ArrayList<Any?>()

        if (filter.statuses.isNotEmpty()) {
            where += "s.`status` IN (${filter.statuses.joinToString(",") { "?" }})"
            values.addAll(filter.statuses.map { it.name })
        }

        filter.providerId?.let {
            where += "s.`providerId` = ?"
            values += it
        }

        filter.stale?.let {
            where += "s.`stale` = ?"
            values += if (it) 1 else 0
        }

        filter.search?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
            val like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

            where += "(s.`trackingNumber` LIKE ? OR s.`merchantReference` LIKE ? OR s.`carrierReference` LIKE ? OR o.`publicId` = ? OR o.`playerUsername` LIKE ?" +
                (if (term.all { it.isDigit() } && term.length <= 18) " OR o.`id` = ?)" else ")")
            values.addAll(listOf(like, like, like, term, like))

            if (term.all { it.isDigit() } && term.length <= 18) values += term.toLong()
        }

        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val from = "FROM ${table("market_shipment")} s JOIN ${table("market_order")} o ON o.`id` = s.`orderId` $clause"
        val count = sqlClient.preparedQuery("SELECT COUNT(*) AS c $from").execute(Tuple.from(values)).coAwait().first().getLong("c")
        val ids = sqlClient.preparedQuery("SELECT s.`id` AS id $from ORDER BY s.`createdAt` DESC, s.`id` DESC LIMIT ? OFFSET ?")
            .execute(Tuple.from(values + window.pageSize.toLong() + window.offset)).coAwait().map { it.getLong("id") }

        return ShipmentPage(ids.mapNotNull { id -> fx().shipments.getById(id, sqlClient)?.let { shipmentJson(it, false, sqlClient, false) } }, count)
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

        private const val TIMEOUT = "TIMEOUT"
        private const val INTERNAL = "INTERNAL"
        private const val CLAIM_MS = 60_000L
        private const val SERVICES_TTL_MS = 600_000L
        private const val MANUAL_MAX_PARCELS = 20
        private const val MAX_PARCEL_GRAMS = 1_000_000_000
        private const val MAX_PARCEL_MM = 5_000
        private const val MERCHANT_REFERENCE_LENGTH = 16
        private const val MERCHANT_REFERENCE_TRIES = 8
        private const val MAX_DOCUMENTS = 20
        private const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
        private const val MAX_SERVICE_CODE = 128
        private const val MAX_RATE_REF = 255
        private const val MAX_NOTE = 512
        private const val MAX_CARRIER_NAME = 128
        private const val MAX_TRACKING_NUMBER = 128
        private const val MAX_URL = 1024
        private const val MAX_ERROR_CODE = 32
        private const val MAX_LAST_ERROR = 512
        private const val MAX_CARRIER_REFERENCE = 191
        private val TRACKING_NUMBER = Regex("^[A-Za-z0-9 ._/-]+$")

        /** The statuses an admin may set by hand (10 section 9.6). */
        val MANUAL_STATUSES: List<ShipmentStatus> = listOf(
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY, ShipmentStatus.EXCEPTION, ShipmentStatus.RETURNING,
            ShipmentStatus.RETURNED, ShipmentStatus.DELIVERED, ShipmentStatus.LOST
        )

        const val NO_ZONE = "NO_ZONE"
        const val NO_METHOD = "NO_METHOD"
        const val METHOD_NOT_OFFERED = "METHOD_NOT_OFFERED"
        private const val INFO = "info"
    }
}

/**
 * What the fulfilment half of [ShippingService] needs on top of the quoter (10 section 9): the transaction helper and lock set, the order,
 * shipment and event DAOs, the mail outbox and the store webhook writer, the directory for label files, and the call limits of 10 section 9.
 */
class FulfilmentDeps(
    val db: MarketDb,
    val locks: Locks,
    val orders: MarketOrderDao,
    val orderItems: MarketOrderItemDao,
    val orderEvents: MarketOrderEventDao,
    val shipments: MarketShipmentDao,
    val shipmentItems: MarketShipmentItemDao,
    val shipmentEvents: MarketShipmentEventDao,
    val mail: MailOutboxService,
    val webhooks: WebhookService,
    val ids: Ids,
    val config: () -> MarketConfig,
    /** `<pluginData>/labels`. */
    val labelsDir: Path,
    /** The public URL of the site, for the order link of the shipment mails. */
    val siteUrl: () -> String = { "" },
    val createTimeoutMs: Long = 30_000,
    val cancelTimeoutMs: Long = 30_000,
    val trackTimeoutMs: Long = 15_000,
    val ratesTimeoutMs: Long = 15_000,
    val servicesTimeoutMs: Long = 10_000
)
