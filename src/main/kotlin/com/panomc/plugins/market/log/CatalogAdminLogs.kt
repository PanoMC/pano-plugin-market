package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Catalogue activity logs of 04 section 10 (MK-160). Details: names, modes and counts only.

/** `POST /products/:id/stock`: [name] of the product, [mode] (`SET` or `ADJUST`), [value] (`null` = unlimited), [variantId] when a variant was changed. */
class UpdatedMarketProductStockLog(userId: Long, username: String, pluginId: String, name: String, mode: String, value: Int?, variantId: Long? = null) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("name", name).put("mode", mode).put("value", value).apply { if (variantId != null) put("variantId", variantId) }
    )

/** A provider action that imported a catalogue (`POST /payment-methods/:id/actions/:actionId` with `imported{}`): the provider id and how many rows came in. */
class ImportedMarketCatalogLog(userId: Long, username: String, pluginId: String, providerId: String, products: Int, categories: Int) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("providerId", providerId).put("products", products).put("categories", categories)
    )
