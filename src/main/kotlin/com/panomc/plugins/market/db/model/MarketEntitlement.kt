package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_entitlement.status` (01 section 5.5). */
enum class EntitlementStatus { ACTIVE, EXPIRED, REVOKED, UPGRADED }

/** `market_entitlement` (01 section 5.5): what a player owns. Money is x100 in the base currency. */
open class MarketEntitlement(
    val id: Long = -1,
    val userId: Long? = null,
    val playerUsername: String = "",
    val ownerKey: String = "",
    val productId: Long = -1,
    val variantId: Long = 0,
    val orderId: Long = -1,
    val orderItemId: Long = -1,
    val subscriptionId: Long? = null,
    val quantity: Int = 1,
    val status: EntitlementStatus = EntitlementStatus.ACTIVE,
    val startsAt: Long = 0,
    /** `null` = permanent. */
    val expiresAt: Long? = null,
    val tierCategoryId: Long? = null,
    val tierRank: Int? = null,
    val pricePaid: Long = 0,
    val replacedById: Long? = null,
    /** `EXPIRED, REFUND, CHARGEBACK, UPGRADE, ADMIN, SUBSCRIPTION_ENDED`. */
    val endReason: String? = null,
    val reminderSentAt: Long? = null,
    val endedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
