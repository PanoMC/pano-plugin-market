package com.panomc.plugins.market.db.model

import com.panomc.platform.annotation.Ignore
import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.ProductDurationType

/** `market_product.kind` (01 section 2.2). */
enum class ProductKind { STANDARD, BUNDLE, CREDIT_PACK }

/** `market_product.billingMode` (01 section 2.2). */
enum class BillingMode { ONE_TIME, TIMED, SUBSCRIPTION }

/** `market_product.periodUnit`: validity of a TIMED product or billing interval of a SUBSCRIPTION. */
enum class PeriodUnit { MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

/**
 * `market_product` with the 29 columns of scheme version 3 (01 section 2.2). `stock` and `soldCount` are counters that
 * change only through the guarded statements of the DAO (never through `update`); `deletedAt` is set only by the soft
 * delete statement.
 */
open class MarketProduct(
    val id: Long = -1,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val categoryId: Long? = null,
    val price: Long = 0,
    val creditPrice: Long = 0,
    val stock: Int? = null,
    val requiredProducts: List<Long> = emptyList(),
    val requireOnlyOne: Boolean = false,
    val requiredPermission: String? = null,
    val status: MarketStatus = MarketStatus.ACTIVE,
    val featured: Boolean = false,
    val durationType: ProductDurationType = ProductDurationType.LIFETIME,
    val durationStart: Long? = null,
    val durationExpiry: Long? = null,
    val priority: Int = 0,
    val icon: String = "fa-box",
    val imageFileName: String? = null,
    val actions: String? = null,
    val kind: ProductKind = ProductKind.STANDARD,
    val shortDescription: String? = null,
    val compareAtPrice: Long? = null,
    /** VAT override in basis points (percent x 100); `null` = the store's `vatPercent`. */
    val vatPercent: Long? = null,
    val physical: Boolean = false,
    val sku: String? = null,
    val weightGrams: Int? = null,
    val lengthMm: Int? = null,
    val widthMm: Int? = null,
    val heightMm: Int? = null,
    val hsCode: String? = null,
    val originCountry: String? = null,
    val billingMode: BillingMode = BillingMode.ONE_TIME,
    val periodUnit: PeriodUnit? = null,
    val periodCount: Int? = null,
    val subscriptionMaxCycles: Int? = null,
    val limitPerPlayer: Int? = null,
    val maxQuantityPerOrder: Int? = null,
    val cooldownSeconds: Long? = null,
    val tierRank: Int? = null,
    val creditAmount: Long? = null,
    val allowGift: Boolean = true,
    /** JSON `long[]`. */
    val serverChoices: String? = null,
    val hasVariants: Boolean = false,
    /** JSON `[{key,label,values:[{key,label}]}]`. */
    val variantOptions: String? = null,
    val metaTitle: String? = null,
    val metaDescription: String? = null,
    val soldCount: Int = 0,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Resolved via a LEFT JOIN in getAllPaged only; @field:Ignore forces the annotation onto the
    // backing field so the host Dao.fields projection (which reads field.declaredAnnotations) skips it.
    @field:Ignore val categoryName: String? = null,
) : DBEntity()
