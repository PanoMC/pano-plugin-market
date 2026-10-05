package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_product_provider_meta` (01 section 2.7): per-product data owned by one payment provider plugin. */
open class MarketProductProviderMeta(
    val id: Long = -1,
    val productId: Long = -1,
    /** `0` = product level. */
    val variantId: Long = 0,
    val providerId: String = "",
    /** JSON, defined by the provider (`{"packageId":"123"}`). */
    val meta: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
