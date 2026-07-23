package com.panomc.plugins.market.service

import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.util.CurrencyType
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.LoggerFactory
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
            logger.warn("Exchange rate fetch for ${from.name} -> ${to.name} failed", e)
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
            logger.warn("Historical rate fetch for ${from.name} -> ${to.name} failed", e)
            null
        }
    }
}
