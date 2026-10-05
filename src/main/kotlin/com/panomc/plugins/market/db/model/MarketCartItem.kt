package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_cart_item` (01 section 4.2): identical lines (same [lineKey]) merge. */
open class MarketCartItem(
    val id: Long = -1,
    val cartId: Long = -1,
    val productId: Long = -1,
    val variantId: Long = 0,
    val quantity: Int = 1,
    /** JSON text `{fieldKey: value}`. */
    val fieldValues: String? = null,
    val targetServerId: Long? = null,
    /** SHA-1 hex of `productId|variantId|canonical(fieldValues)|targetServerId`. */
    val lineKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
