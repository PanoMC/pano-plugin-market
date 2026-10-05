package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_bundle_item` (01 section 2.6): one child of a `BUNDLE` product. */
open class MarketBundleItem(
    val id: Long = -1,
    val bundleProductId: Long = -1,
    val productId: Long = -1,
    /** `0` = no fixed variant of the child. */
    val variantId: Long = 0,
    val quantity: Int = 1,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
