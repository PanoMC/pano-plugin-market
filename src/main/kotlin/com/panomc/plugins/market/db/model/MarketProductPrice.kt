package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_product_price` (01 section 2.4): the price in one additional currency (`currencyMode = MULTI`). */
open class MarketProductPrice(
    val id: Long = -1,
    val productId: Long = -1,
    /** `0` = product level. */
    val variantId: Long = 0,
    /** ISO code, never the base currency. */
    val currency: String = "",
    val price: Long = 0,
    val compareAtPrice: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
