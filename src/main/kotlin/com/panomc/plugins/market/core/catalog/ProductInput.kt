package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.ProductDurationType

/** What a save does with an image slot: leave it, drop it, or replace it with a file the route already stored. */
sealed class ImageChange {
    object Keep : ImageChange()
    object Remove : ImageChange()
    class Set(val fileName: String) : ImageChange()
}

/** One entry of `prices[]` or `variants[i].prices[]` (04 section 5): [variantId] `null` / `0` = product level. */
class PriceDraft(
    val variantId: Long?,
    val currency: String,
    /** x100 of the row's currency. */
    val price: Long,
    val compareAtPrice: Long?
)

/** One entry of `variants[]`. A row with an [id] updates that variant, a row without one creates it. */
class VariantDraft(
    val id: Long?,
    val name: String,
    val sku: String?,
    /** `{axisKey: valueKey}`; `null` = none. */
    val optionValues: Map<String, String>?,
    /** `{key: value}`; `null` = none. */
    val attributes: Map<String, String>?,
    val price: Long?,
    val creditPrice: Long?,
    val compareAtPrice: Long?,
    /** Honoured for a new variant only. */
    val stock: Int?,
    val weightGrams: Int?,
    val periodCount: Int?,
    val position: Int?,
    val status: MarketStatus,
    val image: ImageChange = ImageChange.Keep,
    /** Prices of this variant; `null` = the key was absent (the stored rows stay unless `prices` is sent at top level). */
    val prices: List<PriceDraft>? = null
)

/** One entry of `fields[]`. */
class FieldDraft(
    val id: Long?,
    val fieldKey: String,
    val label: String,
    val helpText: String?,
    val type: ProductFieldType,
    val required: Boolean,
    /** SELECT: `[(value, label)]`. */
    val options: List<Pair<String, String>>?,
    val pattern: String?,
    val minLength: Int?,
    val maxLength: Int?,
    val minValue: Long?,
    val maxValue: Long?,
    val placeholder: String?,
    val defaultValue: String?,
    val usableInCommands: Boolean,
    val position: Int?
)

/** One entry of `bundleItems[]`. */
class BundleItemDraft(
    val productId: Long,
    /** `0` = the child's own price rules apply (no fixed variant). */
    val variantId: Long,
    val quantity: Int,
    val position: Int?
)

/**
 * A parsed product save request (04 section 5): the scalar columns that were present, the set parts that were present
 * (`null` = absent, so the stored set stays), and the problems found while reading the values. Nothing here touches the
 * database; [ProductRules] judges the merged result and `CatalogService` writes it.
 */
class ProductInput(
    /** Typed values keyed by request field name, only for the keys that were present. */
    val scalars: Map<String, Any?>,
    val variants: List<VariantDraft>? = null,
    val fields: List<FieldDraft>? = null,
    val bundleItems: List<BundleItemDraft>? = null,
    val prices: List<PriceDraft>? = null,
    /** `providerId -> meta JSON text`; `null` = absent. */
    val providerMeta: Map<String, String>? = null,
    /** Normalised `actions` JSON text; `null` = absent. */
    val actions: String? = null,
    val image: ImageChange = ImageChange.Keep,
    /** `fieldErrors` found while parsing (wrong type, malformed JSON part): the save is refused when not empty. */
    val parseErrors: Map<String, String> = emptyMap()
) {
    fun has(key: String): Boolean = key in scalars

    /** `true` when the request carries nothing to write at all. */
    fun isEmpty(): Boolean =
        scalars.isEmpty() && variants == null && fields == null && bundleItems == null && prices == null &&
            providerMeta == null && actions == null && image is ImageChange.Keep

    @Suppress("UNCHECKED_CAST")
    private fun <T> pick(key: String, current: T): T = if (key in scalars) scalars[key] as T else current

    /**
     * The product after the save: [base] (the stored row; `null` on create, the defaults of a new product apply) with
     * the present scalars laid over it. `stock` is taken from the request only when [base] is `null` (honoured on create
     * only, bug 2); the counters `soldCount` / `deletedAt` and the identity are never taken from a request. [slug] is the
     * already resolved slug.
     */
    fun applyTo(base: MarketProduct?, slug: String, now: Long, categoryTiered: Boolean = false): MarketProduct {
        val b = base ?: MarketProduct()
        val kind = pick<ProductKind>("kind", b.kind)
        val billingMode = pick<BillingMode>("billingMode", b.billingMode)
        val subscription = billingMode == BillingMode.SUBSCRIPTION
        val periodic = billingMode != BillingMode.ONE_TIME

        return MarketProduct(
            id = b.id,
            slug = slug,
            name = pick("name", b.name),
            description = pick("description", b.description),
            categoryId = pick("categoryId", b.categoryId),
            price = pick("price", b.price),
            creditPrice = pick("creditPrice", b.creditPrice),
            stock = if (base == null) pick<Int?>("stock", null) else b.stock,
            requiredProducts = pick("requiredProducts", b.requiredProducts),
            requireOnlyOne = pick("requireOnlyOne", b.requireOnlyOne),
            requiredPermission = pick("requiredPermission", b.requiredPermission),
            status = pick<MarketStatus>("status", b.status),
            featured = pick("featured", b.featured),
            durationType = pick<ProductDurationType>("durationType", b.durationType),
            durationStart = pick("durationStart", b.durationStart),
            durationExpiry = pick("durationExpiry", b.durationExpiry),
            priority = pick("priority", b.priority),
            icon = pick("icon", b.icon),
            imageFileName = b.imageFileName,
            actions = actions ?: b.actions,
            kind = kind,
            shortDescription = pick("shortDescription", b.shortDescription),
            compareAtPrice = pick("compareAtPrice", b.compareAtPrice),
            vatPercent = pick("vatPercent", b.vatPercent),
            // A BUNDLE's own flag is forced to 0 (10 section 2.1): its children carry the physical lines.
            physical = pick("physical", b.physical) && kind != ProductKind.BUNDLE,
            sku = pick("sku", b.sku),
            weightGrams = pick("weightGrams", b.weightGrams),
            lengthMm = pick("lengthMm", b.lengthMm),
            widthMm = pick("widthMm", b.widthMm),
            heightMm = pick("heightMm", b.heightMm),
            hsCode = pick("hsCode", b.hsCode),
            originCountry = pick("originCountry", b.originCountry),
            billingMode = billingMode,
            // The period belongs to TIMED (validity) and SUBSCRIPTION (interval) only; cycles to SUBSCRIPTION only.
            periodUnit = if (periodic) pick<PeriodUnit?>("periodUnit", b.periodUnit) else null,
            periodCount = if (periodic) pick("periodCount", b.periodCount) else null,
            subscriptionMaxCycles = if (subscription) pick("subscriptionMaxCycles", b.subscriptionMaxCycles) else null,
            limitPerPlayer = pick("limitPerPlayer", b.limitPerPlayer),
            // Forced to 1 for TIMED / SUBSCRIPTION / tiered products (01 section 2.2).
            maxQuantityPerOrder = if (periodic || categoryTiered) 1 else pick("maxQuantityPerOrder", b.maxQuantityPerOrder),
            cooldownSeconds = pick("cooldownSeconds", b.cooldownSeconds),
            tierRank = pick("tierRank", b.tierRank),
            creditAmount = if (kind == ProductKind.CREDIT_PACK) pick("creditAmount", b.creditAmount) else null,
            allowGift = pick("allowGift", b.allowGift),
            serverChoices = pick("serverChoices", b.serverChoices),
            hasVariants = pick("hasVariants", b.hasVariants),
            variantOptions = pick("variantOptions", b.variantOptions),
            metaTitle = pick("metaTitle", b.metaTitle),
            metaDescription = pick("metaDescription", b.metaDescription),
            soldCount = b.soldCount,
            deletedAt = b.deletedAt,
            createdAt = base?.createdAt ?: now,
            updatedAt = now
        )
    }
}
