package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.catalog.CountryCodes
import com.panomc.plugins.market.core.order.AddressFieldSets
import com.panomc.plugins.market.db.dao.MarketShippingZoneDao
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import java.util.Locale

/**
 * `GET /api/market/checkout/config` (04 section 3): what the checkout page needs before the first quote: guest / gift
 * switches, billing mode, credit and top-up limits (the single place they are served), the legal block, the offered
 * currencies, the address field sets and the countries the store ships to.
 */
class CheckoutConfigService(
    private val config: () -> MarketConfig,
    private val legal: LegalTextService,
    private val zones: MarketShippingZoneDao
) {
    suspend fun get(locale: String?, sqlClient: SqlClient): JsonObject {
        val c = config()
        val text = legal.activeFor(locale, sqlClient)

        // `legalTextRequired` without any active text is treated as not required (06 section 8.1).
        val legalBlock = text?.let {
            JsonObject()
                .put("required", c.legalTextRequired)
                .put("id", it.id)
                .put("version", it.version)
                .put("title", it.title)
                .put("content", it.content)
        }

        val topUpEnabled = c.creditsEnabled && c.creditTopUpEnabled

        return JsonObject()
            .put("guestCheckout", c.allowGuestCheckout)
            .put("giftPurchase", c.allowGiftPurchase)
            .put("billingInfoMode", c.billingInfoMode.name)
            .put("creditsEnabled", c.creditsEnabled)
            .put("creditName", c.creditName)
            .put("mixedCredit", c.creditsEnabled && c.allowMixedCreditPayment)
            .put("legal", legalBlock)
            .put("currencies", JsonArray(currencies(c)))
            .put("addressFields", JsonObject(AddressFieldSets.asMap().mapValues { (_, v) -> JsonArray(v) }))
            .put("shippingCountries", JsonArray(shippingCountries(sqlClient)))
            .put("minimumOrderAmount", c.minimumOrderAmount)
            .put(
                "creditTopUp",
                JsonObject()
                    .put("enabled", topUpEnabled)
                    .put("freeAmount", topUpEnabled && c.creditTopUpFreeAmount)
                    .put("min", c.creditTopUpMin)
                    .put("max", c.creditTopUpMax)
                    .put("creditValue", c.creditValue)
                    .put("currency", c.currency)
            )
    }

    /** The store currency first, then the additional ones, upper-case and without duplicates. */
    private fun currencies(c: MarketConfig): List<String> =
        (listOf(c.currency) + c.additionalCurrencies.map { it.trim().uppercase(Locale.ROOT) })
            .filter { it.isNotEmpty() }
            .distinct()

    /** Union of the countries of the zones a buyer can ship to; a zone with `"*"` opens every country. Sorted by code. */
    private suspend fun shippingCountries(sqlClient: SqlClient): List<String> {
        val result = sortedSetOf<String>()

        for (zone in zones.getSellable(sqlClient)) {
            val list = runCatching { JsonArray(zone.countries) }.getOrNull() ?: continue
            val codes = list.mapNotNull { (it as? String)?.trim()?.uppercase(Locale.ROOT) }

            if ("*" in codes) return CountryCodes.ALL.sorted()

            codes.filterTo(result) { CountryCodes.isValid(it) }
        }

        return result.toList()
    }
}
