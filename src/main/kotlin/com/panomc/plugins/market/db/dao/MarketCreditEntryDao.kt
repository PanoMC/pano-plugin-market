package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Credit ledger entries, the legs of a transaction (01 section 7.3). Rows are never updated or deleted. */
abstract class MarketCreditEntryDao : MarketDao<MarketCreditEntry>(MarketCreditEntry::class.java) {
    abstract suspend fun add(entry: MarketCreditEntry, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreditEntry?

    /** The legs of a transaction, in insertion order (`idx_tx`). */
    abstract suspend fun getByTxId(txId: Long, sqlClient: SqlClient): List<MarketCreditEntry>

    /** The newest [limit] entries of an account, newest first (`idx_account`). */
    abstract suspend fun getByAccountId(accountId: Long, limit: Int, sqlClient: SqlClient): List<MarketCreditEntry>

    /** `Σ amount` of one transaction (invariant: 0). */
    abstract suspend fun sumByTxId(txId: Long, sqlClient: SqlClient): Long

    /** `Σ amount` of one account's entries (invariant: equals the account's cached balance). */
    abstract suspend fun sumByAccountId(accountId: Long, sqlClient: SqlClient): Long
}
