package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Credit accounts: one per user and the five system accounts (01 section 7.1). */
abstract class MarketCreditAccountDao : MarketDao<MarketCreditAccount>(MarketCreditAccount::class.java) {
    /** The new id, or `null` on a unique-key duplicate (`uq_user`, `uq_system`). */
    abstract suspend fun add(account: MarketCreditAccount, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreditAccount?

    /** The account of a user (`uq_user`). */
    abstract suspend fun getByUserId(userId: Long, sqlClient: SqlClient): MarketCreditAccount?

    /** The system account of [key] (`uq_system`). */
    abstract suspend fun getBySystemKey(key: CreditSystemKey, sqlClient: SqlClient): MarketCreditAccount?

    /** The system accounts, ordered by id. */
    abstract suspend fun getSystemAccounts(sqlClient: SqlClient): List<MarketCreditAccount>

    /**
     * `INSERT IGNORE` of the five system accounts (01 section 7.1); returns how many rows were inserted (5 on a fresh
     * table, 0 when they all exist). Running it twice leaves five rows.
     */
    abstract suspend fun seedSystemAccounts(sqlClient: SqlClient): Int

    /**
     * `INSERT IGNORE` of the account of [userId] with balance 0 (01 section 7 / 07 section 3.2 step 1); `true` when the
     * row was created, `false` when the user already had one. Read the row afterwards with [getByUserId].
     */
    abstract suspend fun insertUserAccountIgnore(userId: Long, sqlClient: SqlClient): Boolean

    /**
     * `SELECT ... WHERE id IN (...) ORDER BY id ASC FOR UPDATE` (07 section 3.2 step 2): locks the accounts in ascending
     * id order and answers their current rows. Call it on the connection of the posting's `MarketDb.tx`.
     */
    abstract suspend fun lockByIds(ids: Collection<Long>, sqlClient: SqlClient): List<MarketCreditAccount>

    /**
     * `UPDATE ... SET balance = balance + delta WHERE id = ? AND (guarded = 0 OR balance + delta >= 0)` (07 section
     * 3.2 step 6). Returns the affected rows: 1, or 0 when the account does not exist or the guard refuses a balance
     * below zero.
     */
    abstract suspend fun addToBalance(accountId: Long, delta: Long, guarded: Boolean, sqlClient: SqlClient): Int
}
