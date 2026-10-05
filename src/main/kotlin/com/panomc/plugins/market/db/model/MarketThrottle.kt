package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_throttle.scope` values (01 section 12): the table keeps the text, new scopes need no migration. */
object ThrottleScope {
    const val COUPON = "COUPON"
    const val GIFT = "GIFT"
    const val CHECKOUT = "CHECKOUT"
    const val REVEAL = "REVEAL"
    const val DELIVERY_ALERT = "DELIVERY_ALERT"
}

/** `market_throttle` (01 section 12): a durable counter and lock per (scope, subject). */
open class MarketThrottle(
    val id: Long = -1,
    val scope: String = "",
    val subject: String = "",
    val count: Int = 0,
    val windowStart: Long = 0,
    val lockedUntil: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
