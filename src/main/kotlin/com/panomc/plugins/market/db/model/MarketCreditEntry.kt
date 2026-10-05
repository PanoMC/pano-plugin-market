package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/**
 * `market_credit_entry` (01 section 7.3): one leg of a [MarketCreditTx]. [amount] is signed (+ into the account),
 * [balanceAfter] the account balance right after the leg. The entries of one transaction sum to 0.
 */
open class MarketCreditEntry(
    val id: Long = -1,
    val txId: Long = -1,
    val accountId: Long = -1,
    val amount: Long = 0,
    val balanceAfter: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
