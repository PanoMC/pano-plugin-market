package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** The block list (01 section 12). */
abstract class MarketBlockDao : MarketDao<MarketBlock>(MarketBlock::class.java) {
    /** The new id, or `null` when (type, value) is blocked already (`uq_type_value`). */
    abstract suspend fun add(block: MarketBlock, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketBlock?

    abstract suspend fun getByTypeAndValue(type: BlockType, value: String, sqlClient: SqlClient): MarketBlock?

    /** Entries of one type that are not expired at [now] (an IP type is matched against CIDRs by the caller). */
    abstract suspend fun getActiveByType(type: BlockType, now: Long, sqlClient: SqlClient): List<MarketBlock>

    /** Every entry, newest first. */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketBlock>

    /** Atomic `hitCount + 1` and `lastHitAt`: one refused checkout. */
    abstract suspend fun recordHit(id: Long, now: Long, sqlClient: SqlClient): Boolean

    abstract suspend fun delete(id: Long, sqlClient: SqlClient): Boolean

    /** Deletes the entries that expired before [now]; returns how many. */
    abstract suspend fun deleteExpired(now: Long, sqlClient: SqlClient): Int
}
