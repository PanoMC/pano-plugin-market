package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_credit_account.type` (01 section 7.1). */
enum class CreditAccountType { USER, SYSTEM }

/**
 * `market_credit_account.systemKey` (01 section 7.1): the five system accounts, seeded by `Dao.init`, the 6 to 7
 * migration and `MarketSchema.ensure`.
 */
enum class CreditSystemKey {
    /** Source: top-up, grant, cashback, gift, refund. Excluded from the non-negative guard. */
    ISSUANCE,

    /** Sink: purchases. */
    SPENT,

    /** Credits reserved by pending orders; never negative. */
    HOLD,

    /** Sink: admin revoke, clawback. */
    REVOKED,

    /** Counter-account of the Minecraft server economy; either sign, excluded from the guard. */
    EXTERNAL
}

/**
 * `market_credit_account` (01 section 7.1). [balance] is the cached sum of the account's entries, credits x100. A
 * `USER` row has [userId] and no [systemKey]; a `SYSTEM` row the other way round.
 */
open class MarketCreditAccount(
    val id: Long = -1,
    val type: CreditAccountType = CreditAccountType.USER,
    val userId: Long? = null,
    val systemKey: CreditSystemKey? = null,
    val balance: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
