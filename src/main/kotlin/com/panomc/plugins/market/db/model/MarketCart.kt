package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_cart` (01 section 4.1): one per logged-in user. */
open class MarketCart(
    val id: Long = -1,
    val userId: Long = -1,
    val currency: String? = null,
    val couponCode: String? = null,
    val creatorCode: String? = null,
    val recipientUsername: String? = null,
    val giftMessage: String? = null,
    val shippingAddressId: Long? = null,
    val shippingMethodId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
