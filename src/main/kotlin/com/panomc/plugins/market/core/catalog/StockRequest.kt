package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.routes.base.parseEnum
import io.vertx.core.json.JsonObject

enum class StockMode { SET, ADJUST }

/** Body of `POST /products/:id/stock`: `{variantId?, mode, value}`. [value] `null` = unlimited (SET only). */
class StockRequest(val variantId: Long?, val mode: StockMode, val value: Int?) {
    companion object {
        /** Throws [RequestValueException] (400 `BAD_REQUEST`) for a shape outside the contract; ranges are the service's `INVALID_PRODUCT`. */
        fun parse(body: JsonObject): StockRequest {
            val mode = parseEnum(StockMode.entries.toTypedArray(), body.getValue("mode") as? String, "mode")
            val variantId = body.getValue("variantId")?.let { parseBodyId(it, "variantId") }

            if (!body.containsKey("value")) throw RequestValueException("value", "REQUIRED")

            val value = when (val raw = body.getValue("value")) {
                null -> null
                is Int -> raw
                is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else throw RequestValueException("value", "OUT_OF_RANGE")
                is Double -> if (raw == Math.floor(raw) && Math.abs(raw) <= Int.MAX_VALUE) raw.toInt() else throw RequestValueException("value", "MUST_BE_AN_INTEGER")
                else -> throw RequestValueException("value", "MUST_BE_AN_INTEGER")
            }

            return StockRequest(variantId, mode, value)
        }
    }
}
