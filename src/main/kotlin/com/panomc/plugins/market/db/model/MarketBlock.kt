package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_block.type` (01 section 12). */
enum class BlockType { PLAYER, EMAIL, IP, USER }

/** `market_block.source`. */
enum class BlockSource { MANUAL, CHARGEBACK }

/**
 * `market_block` (01 section 12): a refused buyer. [value] is stored lower-cased by the caller (an IP may be a CIDR).
 * (type, value) is unique. [hitCount] counts refused checkouts and is only changed by an atomic `+1`.
 */
open class MarketBlock(
    val id: Long = -1,
    val type: BlockType = BlockType.PLAYER,
    val value: String = "",
    val reason: String? = null,
    val source: BlockSource = BlockSource.MANUAL,
    val orderId: Long? = null,
    val createdBy: Long? = null,
    val expiresAt: Long? = null,
    val hitCount: Int = 0,
    val lastHitAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
