package com.panomc.plugins.market.service

import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.money.Currencies
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.ExchangeRateFetchFailed
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.util.CurrencyType
import com.panomc.plugins.market.util.NetworkFailureUtil
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Fetches foreign-exchange rates for converting a sale amount into the stats currency.
 * A "rate" here is always stats-per-sales, i.e. how many `to`-currency units equal 1 `from`-currency
 * unit. Every call is best-effort: it returns null on any failure (non-200, missing rate, network
 * error, bad payload) and never throws, so callers can fall back to a stored/manual rate.
 * Registered as a singleton bean by [MarketPlugin].
 */
class ExchangeRateService(private val plugin: MarketPlugin) {

    private val logger = LoggerFactory.getLogger("Market:ExchangeRate")

    private val webClient by lazy {
        plugin.applicationContext.getBean(WebClient::class.java)
    }

    companion object {
        private const val CURRENT_URL = "https://open.er-api.com/v6/latest/"
        private const val HISTORICAL_URL = "https://api.exchangerate.host/"
    }

    /**
     * Current rate: how many [to] units equal 1 [from] unit. Same currency yields 1.0.
     * Uses the free keyless open.er-api.com endpoint. Returns null on any failure.
     */
    suspend fun fetchRate(from: CurrencyType, to: CurrencyType): Double? {
        if (from == to) return 1.0

        return try {
            val response = webClient.getAbs(CURRENT_URL + from.name)
                .putHeader("Accept", "application/json")
                .send()
                .coAwait()

            if (response.statusCode() != 200) {
                logger.warn("Exchange rate fetch for {} -> {} failed: HTTP {}", from.name, to.name, response.statusCode())
                return null
            }

            val rates = response.bodyAsJsonObject()?.getJsonObject("rates")
            rates?.getDouble(to.name)
        } catch (e: Exception) {
            logFailure("Exchange rate fetch for ${from.name} -> ${to.name} failed", e)
            null
        }
    }

    /**
     * Every current rate for one [base] unit in one request (`GET latest/<base>`): `currency -> units per 1 base unit`,
     * only positive finite values of at most 10 fraction digits. `null` on any failure (never throws), so the caller
     * keeps the stored rows.
     */
    suspend fun fetchAll(base: String): Map<String, BigDecimal>? {
        return try {
            val response = webClient.getAbs(CURRENT_URL + base)
                .putHeader("Accept", "application/json")
                .send()
                .coAwait()

            if (response.statusCode() != 200) {
                logger.warn("Exchange rate table fetch for {} failed: HTTP {}", base, response.statusCode())
                return null
            }

            val rates = response.bodyAsJsonObject()?.getJsonObject("rates") ?: return null
            val result = LinkedHashMap<String, BigDecimal>()

            for (code in rates.fieldNames()) {
                val rate = CurrencyRates.parse((rates.getValue(code) as? Number)?.toString()) ?: continue

                result[code.uppercase()] = rate
            }

            result
        } catch (e: Exception) {
            logFailure("Exchange rate table fetch for $base failed", e)
            null
        }
    }

    /**
     * Best-effort historical rate for [epochMillis]: how many [to] units equalled 1 [from] unit on
     * that date. Same currency yields 1.0. Returns null when unavailable so the caller can fall back
     * to the current rate.
     */
    suspend fun fetchRateForDate(from: CurrencyType, to: CurrencyType, epochMillis: Long): Double? {
        if (from == to) return 1.0

        return try {
            val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)

            val response = webClient.getAbs(HISTORICAL_URL + date)
                .addQueryParam("base", from.name)
                .addQueryParam("symbols", to.name)
                .putHeader("Accept", "application/json")
                .send()
                .coAwait()

            if (response.statusCode() != 200) {
                logger.warn("Historical rate fetch for {} -> {} ({}) failed: HTTP {}", from.name, to.name, date, response.statusCode())
                return null
            }

            val rates = response.bodyAsJsonObject()?.getJsonObject("rates")
            rates?.getDouble(to.name)
        } catch (e: Exception) {
            logFailure("Historical rate fetch for ${from.name} -> ${to.name} failed", e)
            null
        }
    }

    /**
     * Reports a failed fetch as an operator-facing line rather than a stack dump.
     *
     * Not reaching the rate provider is an environment condition and, for a service documented to
     * be best-effort, an entirely survivable one - the caller just falls back to a stored or manual
     * rate. The ~25 Vert.x/Netty frames behind it are identical every time and add nothing to "what
     * failed and why", so they are reduced to one line, with the trace still available at DEBUG.
     * Every other exception keeps its stack trace, because there the frames are the only thing that
     * points at the bug.
     */
    private fun logFailure(message: String, error: Throwable) {
        if (!NetworkFailureUtil.isConnectivityFailure(error)) {
            logger.warn(message, error)

            return
        }

        logger.warn("{}: {}", message, NetworkFailureUtil.describe(error))
        logger.debug(message, error)
    }
}

/** Rate arithmetic of `market_currency_rate` (`DECIMAL(20,10)`, 01 section 2.9). */
object CurrencyRates {
    /** Largest accepted rate: 10 integer digits fit the column. */
    val MAX: BigDecimal = BigDecimal("9999999999")

    /** [raw] as a rate at scale 10 (half up); `null` unless positive, at most [MAX] and not zero after rounding. */
    fun parse(raw: String?): BigDecimal? {
        val value = try {
            BigDecimal(raw?.trim() ?: return null)
        } catch (e: NumberFormatException) {
            return null
        }

        return normalise(value)
    }

    fun normalise(value: BigDecimal): BigDecimal? {
        if (value.signum() <= 0 || value > MAX) return null

        val scaled = value.setScale(10, RoundingMode.HALF_UP)

        return scaled.takeIf { it.signum() > 0 }
    }
}

/** One entry of `PUT /settings/currencies`: [rate] is read for `MANUAL` only. */
class CurrencyRateEntry(val currency: String?, val mode: CurrencyRateMode?, val rate: BigDecimal?)

/**
 * The additional-currency rates (`market_currency_rate`, 04 section 8 `GET / PUT / POST /settings/currencies[/refresh]`, 00
 * section 6.7). A currency is offered only with a positive rate; `AUTO` rows are refreshed from the provider
 * (`source`, one request for the whole table), `MANUAL` rows only change by an admin edit.
 */
class CurrencyRateService(
    private val db: MarketDb,
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val rates: MarketCurrencyRateDao,
    /** `base -> currency -> units per 1 base unit`; `null` when the provider is unreachable. */
    private val source: suspend (String) -> Map<String, BigDecimal>?
) {
    class RateView(val currency: String, val rate: BigDecimal?, val mode: CurrencyRateMode, val fetchedAt: Long?)

    class View(val currencyMode: String, val baseCurrency: String, val rates: List<RateView>)

    private fun base(): String = config().currency.name

    /** The offered currencies of the settings, normalised: supported, not the base, no duplicates. */
    private fun configured(): List<String> =
        config().additionalCurrencies.map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && Currencies.isSupported(it) && it != base() }
            .distinct()

    /** The currencies of the settings first (their order), then the other stored rows by code; one entry each. */
    suspend fun view(sqlClient: SqlClient): View {
        val stored = rates.getAll(sqlClient).associateBy { it.currency }
        val codes = configured() + stored.keys.filter { it !in configured() }.sorted()

        return View(
            config().currencyMode.name,
            base(),
            codes.map { code ->
                val row = stored[code]

                RateView(code, row?.rate, row?.mode ?: CurrencyRateMode.AUTO, row?.fetchedAt)
            }
        )
    }

    /** The positive rates by currency, the table the pricing code gets (`PricingConfig.rates`). */
    suspend fun positiveRates(sqlClient: SqlClient): Map<String, BigDecimal> =
        rates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate }

    /**
     * `PUT`: validates every entry first ([InvalidSettings] `fieldErrors` keyed `rates.<i>.<field>`, nothing is written),
     * then upserts them in one transaction. `MANUAL` needs a `rate`; `AUTO` takes the provider's current rate, keeps the
     * stored one when the provider is down, and is refused (`RATE_UNAVAILABLE`) when there is neither. Rows of currencies
     * not in the list stay as they are.
     */
    suspend fun update(entries: List<CurrencyRateEntry>, sqlClient: SqlClient): View {
        val errors = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        val parsed = ArrayList<Triple<String, CurrencyRateMode, BigDecimal?>>()
        val base = base()

        entries.forEachIndexed { i, entry ->
            val code = entry.currency?.trim()?.uppercase().orEmpty()

            when {
                code.isEmpty() || !Currencies.isSupported(code) -> errors["rates.$i.currency"] = "UNKNOWN_CURRENCY"
                code == base -> errors["rates.$i.currency"] = "IS_BASE_CURRENCY"
                !seen.add(code) -> errors["rates.$i.currency"] = "DUPLICATE"
            }

            val mode = entry.mode

            if (mode == null) {
                errors["rates.$i.mode"] = "REQUIRED"
            } else if (mode == CurrencyRateMode.MANUAL) {
                val rate = entry.rate?.let { CurrencyRates.normalise(it) }

                if (rate == null) errors["rates.$i.rate"] = if (entry.rate == null) "REQUIRED" else "OUT_OF_RANGE"
                else parsed += Triple(code, mode, rate)
            } else {
                parsed += Triple(code, mode, null)
            }
        }

        if (errors.isNotEmpty()) throw InvalidSettings(errors)

        val needsFetch = parsed.any { it.second == CurrencyRateMode.AUTO }
        val fetched = if (needsFetch) source(base) else null
        val stored = rates.getAll(sqlClient).associateBy { it.currency }
        val now = clock.now()
        val writes = ArrayList<MarketCurrencyRate>()

        parsed.forEach { (code, mode, manual) ->
            if (mode == CurrencyRateMode.MANUAL) {
                writes += MarketCurrencyRate(
                    currency = code, rate = manual!!, mode = mode, fetchedAt = now, createdAt = now, updatedAt = now
                )

                return@forEach
            }

            val live = fetched?.get(code)?.let { CurrencyRates.normalise(it) }
            val row = stored[code]

            when {
                live != null -> writes += MarketCurrencyRate(
                    currency = code, rate = live, mode = mode, fetchedAt = now, createdAt = now, updatedAt = now
                )

                row != null && row.rate.signum() > 0 -> writes += MarketCurrencyRate(
                    currency = code, rate = row.rate, mode = mode, fetchedAt = row.fetchedAt, createdAt = now, updatedAt = now
                )

                else -> errors["rates.${entries.indexOfFirst { it.currency?.trim()?.uppercase() == code }}.rate"] = "RATE_UNAVAILABLE"
            }
        }

        if (errors.isNotEmpty()) throw InvalidSettings(errors)

        db.tx { conn -> for (row in writes) rates.upsert(row, conn) }

        return view(sqlClient)
    }

    /**
     * `POST /refresh`: one provider request, then every `AUTO` currency (the settings' list plus stored `AUTO` rows) that
     * the provider knows is upserted. `MANUAL` rows are never touched. [ExchangeRateFetchFailed] (502) when the provider
     * is unreachable; the stored rows stay.
     */
    suspend fun refresh(sqlClient: SqlClient): View {
        val fetched = source(base()) ?: throw ExchangeRateFetchFailed()
        val stored = rates.getAll(sqlClient).associateBy { it.currency }
        val targets = (configured() + stored.values.filter { it.mode == CurrencyRateMode.AUTO }.map { it.currency }).distinct()
            .filter { stored[it]?.mode != CurrencyRateMode.MANUAL }
        val now = clock.now()
        val writes = targets.mapNotNull { code ->
            val rate = fetched[code]?.let { CurrencyRates.normalise(it) } ?: return@mapNotNull null

            MarketCurrencyRate(currency = code, rate = rate, mode = CurrencyRateMode.AUTO, fetchedAt = now, createdAt = now, updatedAt = now)
        }

        db.tx { conn -> for (row in writes) rates.upsert(row, conn) }

        return view(sqlClient)
    }
}
