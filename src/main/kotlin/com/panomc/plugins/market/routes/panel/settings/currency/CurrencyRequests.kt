package com.panomc.plugins.market.routes.panel.settings.currency

import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.service.CurrencyRateEntry
import com.panomc.plugins.market.service.CurrencyRateService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal

/** Most entries one `PUT /settings/currencies` carries. */
const val MAX_RATE_ENTRIES = 100

/**
 * The body of `PUT /settings/currencies` (04 section 8): `{rates: [{currency, mode, rate?}]}`. Shape problems are
 * [InvalidSettings] `fieldErrors` keyed like the service's (`rates.<i>.<field>`); a value of the wrong type is judged
 * here, the business rules (known currency, not the base, range) by [CurrencyRateService.update].
 */
fun parseCurrencyRatesBody(body: JsonObject?): List<CurrencyRateEntry> {
    val errors = LinkedHashMap<String, String>()

    if (body == null) throw InvalidSettings(mapOf("rates" to "REQUIRED"))

    body.fieldNames().filter { it != "rates" }.forEach { errors[it] = "UNKNOWN_PROPERTY" }

    val raw = body.getValue("rates")

    if (raw !is JsonArray) {
        errors["rates"] = "REQUIRED"

        throw InvalidSettings(errors)
    }

    if (raw.size() > MAX_RATE_ENTRIES) errors["rates"] = "TOO_MANY"

    val entries = ArrayList<CurrencyRateEntry>()

    for (i in 0 until minOf(raw.size(), MAX_RATE_ENTRIES)) {
        val item = raw.getValue(i) as? JsonObject

        if (item == null) {
            errors["rates.$i"] = "INVALID"
            entries += CurrencyRateEntry(null, null, null)

            continue
        }

        item.fieldNames().filter { it !in setOf("currency", "mode", "rate") }.forEach { errors["rates.$i.$it"] = "UNKNOWN_PROPERTY" }

        val currency = item.getValue("currency")

        if (currency != null && currency !is String) errors["rates.$i.currency"] = "INVALID"

        val modeRaw = item.getValue("mode")
        val mode = when {
            modeRaw == null -> null
            modeRaw is String && modeRaw in CurrencyRateMode.entries.map { it.name } -> CurrencyRateMode.valueOf(modeRaw)
            else -> {
                errors["rates.$i.mode"] = "UNKNOWN_VALUE"

                null
            }
        }

        val rateRaw = item.getValue("rate")
        val rate: BigDecimal? = when (rateRaw) {
            null -> null
            is Number -> runCatching { BigDecimal(rateRaw.toString()) }.getOrNull()
            else -> null
        }

        if (rateRaw != null && rate == null) errors["rates.$i.rate"] = "INVALID"

        entries += CurrencyRateEntry(currency as? String, mode, rate)
    }

    if (errors.isNotEmpty()) throw InvalidSettings(errors)

    return entries
}

/** The response of the three currency endpoints: `{currencyMode, baseCurrency, rates[{currency, rate, mode, fetchedAt}]}`. */
fun currencyRatesJson(view: CurrencyRateService.View): Map<String, Any?> = mapOf(
    "currencyMode" to view.currencyMode,
    "baseCurrency" to view.baseCurrency,
    "rates" to view.rates.map {
        mapOf("currency" to it.currency, "rate" to it.rate?.stripTrailingZeros()?.let { r -> if (r.scale() < 0) r.setScale(0) else r }?.toDouble(), "mode" to it.mode.name, "fetchedAt" to it.fetchedAt)
    }
)
