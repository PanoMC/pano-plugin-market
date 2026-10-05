package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.MarketStatus

/** `market_product_variant` (01 section 2.3): a row the buyer must choose when the product `hasVariants`. */
open class MarketProductVariant(
    val id: Long = -1,
    val productId: Long = -1,
    val name: String = "",
    val sku: String? = null,
    /** JSON `{axisKey: valueKey}`. */
    val optionValues: String? = null,
    /** JSON `{key: value}` strings, exposed as `{variant.<key>}`. */
    val attributes: String? = null,
    /** Absolute base-currency price; `null` = the product price. */
    val price: Long? = null,
    val creditPrice: Long? = null,
    val compareAtPrice: Long? = null,
    /** `null` = unlimited. Changes only through the guarded counter statements, never through `update`. */
    val stock: Int? = null,
    val weightGrams: Int? = null,
    val periodCount: Int? = null,
    val imageFileName: String? = null,
    val position: Int = 0,
    val status: MarketStatus = MarketStatus.ACTIVE,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
