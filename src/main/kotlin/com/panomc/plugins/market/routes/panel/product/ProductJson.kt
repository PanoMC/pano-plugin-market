package com.panomc.plugins.market.routes.panel.product

import com.panomc.plugins.market.core.catalog.ProductActions
import com.panomc.plugins.market.db.model.MarketBundleItem
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductField
import com.panomc.plugins.market.db.model.MarketProductPrice
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.service.CatalogService
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/** The wire shapes of the panel catalogue (04 section 5): money as decimal numbers, percentages as plain numbers. */
object ProductJson {
    private fun money(value: Long?): Double? = value?.let { MoneyUtil.toDecimal(it) }

    private fun json(text: String?): Any? = text?.let { runCatching { JsonArray(it) }.getOrNull() }

    private fun jsonObject(text: String?): JsonObject? = text?.let { runCatching { JsonObject(it) }.getOrNull() }

    /** One row of `GET /products`. */
    fun row(product: MarketProduct): JsonObject = JsonObject()
        .put("id", product.id)
        .put("slug", product.slug)
        .put("name", product.name)
        .put("categoryId", product.categoryId)
        .put("categoryName", product.categoryName)
        .put("price", money(product.price))
        .put("creditPrice", money(product.creditPrice))
        .put("compareAtPrice", money(product.compareAtPrice))
        .put("stock", product.stock)
        .put("status", product.status.name)
        .put("featured", product.featured)
        .put("priority", product.priority)
        .put("icon", product.icon)
        .put("imageFileName", product.imageFileName)
        .put("kind", product.kind.name)
        .put("physical", product.physical)
        .put("billingMode", product.billingMode.name)
        .put("hasVariants", product.hasVariants)
        .put("soldCount", product.soldCount)

    /** One row of `GET /products/simple` (pickers). */
    fun simple(product: MarketProduct): JsonObject = JsonObject()
        .put("id", product.id)
        .put("name", product.name)
        .put("kind", product.kind.name)
        .put("billingMode", product.billingMode.name)
        .put("hasVariants", product.hasVariants)
        .put("status", product.status.name)

    fun variant(v: MarketProductVariant): JsonObject = JsonObject()
        .put("id", v.id)
        .put("name", v.name)
        .put("sku", v.sku)
        .put("optionValues", jsonObject(v.optionValues))
        .put("attributes", jsonObject(v.attributes))
        .put("price", money(v.price))
        .put("creditPrice", money(v.creditPrice))
        .put("compareAtPrice", money(v.compareAtPrice))
        .put("stock", v.stock)
        .put("weightGrams", v.weightGrams)
        .put("periodCount", v.periodCount)
        .put("imageFileName", v.imageFileName)
        .put("position", v.position)
        .put("status", v.status.name)

    fun field(f: MarketProductField): JsonObject = JsonObject()
        .put("id", f.id)
        .put("fieldKey", f.fieldKey)
        .put("label", f.label)
        .put("helpText", f.helpText)
        .put("type", f.type.name)
        .put("required", f.required)
        .put("options", json(f.options))
        .put("pattern", f.pattern)
        .put("minLength", f.minLength)
        .put("maxLength", f.maxLength)
        .put("minValue", f.minValue)
        .put("maxValue", f.maxValue)
        .put("placeholder", f.placeholder)
        .put("defaultValue", f.defaultValue)
        .put("usableInCommands", f.usableInCommands)
        .put("position", f.position)

    fun bundleItem(i: MarketBundleItem): JsonObject = JsonObject()
        .put("productId", i.productId)
        .put("variantId", i.variantId)
        .put("quantity", i.quantity)
        .put("position", i.position)

    fun price(p: MarketProductPrice): JsonObject = JsonObject()
        .put("variantId", p.variantId)
        .put("currency", p.currency)
        .put("price", money(p.price))
        .put("compareAtPrice", money(p.compareAtPrice))

    /** `GET /products/:id`: every column plus `variants[]`, `fields[]`, `bundleItems[]`, `prices[]`, `providerMeta{}`. */
    fun detail(view: CatalogService.ProductView): JsonObject {
        val p = view.product

        val meta = JsonObject()
        view.providerMeta.forEach { (providerId, text) -> meta.put(providerId, jsonObject(text) ?: JsonObject()) }

        return JsonObject()
            .put("id", p.id)
            .put("slug", p.slug)
            .put("name", p.name)
            .put("description", p.description)
            .put("shortDescription", p.shortDescription)
            .put("categoryId", p.categoryId)
            .put("price", money(p.price))
            .put("creditPrice", money(p.creditPrice))
            .put("compareAtPrice", money(p.compareAtPrice))
            .put("stock", p.stock)
            .put("requiredProducts", JsonArray(p.requiredProducts))
            .put("requireOnlyOne", p.requireOnlyOne)
            .put("requiredPermission", p.requiredPermission)
            .put("status", p.status.name)
            .put("featured", p.featured)
            .put("durationType", p.durationType.name)
            .put("durationStart", p.durationStart)
            .put("durationExpiry", p.durationExpiry)
            .put("priority", p.priority)
            .put("icon", p.icon)
            .put("imageFileName", p.imageFileName)
            .put("actions", ProductActions.view(p.actions))
            .put("kind", p.kind.name)
            .put("vatPercent", p.vatPercent?.let { it / 100.0 })
            .put("physical", p.physical)
            .put("sku", p.sku)
            .put("weightGrams", p.weightGrams)
            .put("lengthMm", p.lengthMm)
            .put("widthMm", p.widthMm)
            .put("heightMm", p.heightMm)
            .put("hsCode", p.hsCode)
            .put("originCountry", p.originCountry)
            .put("billingMode", p.billingMode.name)
            .put("periodUnit", p.periodUnit?.name)
            .put("periodCount", p.periodCount)
            .put("subscriptionMaxCycles", p.subscriptionMaxCycles)
            .put("limitPerPlayer", p.limitPerPlayer)
            .put("maxQuantityPerOrder", p.maxQuantityPerOrder)
            .put("cooldownSeconds", p.cooldownSeconds)
            .put("tierRank", p.tierRank)
            .put("creditAmount", money(p.creditAmount))
            .put("allowGift", p.allowGift)
            .put("serverChoices", json(p.serverChoices) ?: JsonArray())
            .put("hasVariants", p.hasVariants)
            .put("variantOptions", json(p.variantOptions) ?: JsonArray())
            .put("metaTitle", p.metaTitle)
            .put("metaDescription", p.metaDescription)
            .put("soldCount", p.soldCount)
            .put("createdAt", p.createdAt)
            .put("updatedAt", p.updatedAt)
            .put("variants", JsonArray(view.variants.map { variant(it) }))
            .put("fields", JsonArray(view.fields.map { field(it) }))
            .put("bundleItems", JsonArray(view.bundleItems.map { bundleItem(it) }))
            .put("prices", JsonArray(view.prices.map { price(it) }))
            .put("providerMeta", meta)
    }
}
