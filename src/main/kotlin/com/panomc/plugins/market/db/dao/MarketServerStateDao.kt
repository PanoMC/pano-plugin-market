package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** What market knows about each Minecraft server (01 section 9.2), one row per server (`uq_server`). */
abstract class MarketServerStateDao : MarketDao<MarketServerState>(MarketServerState::class.java) {
    /**
     * Records the last `MARKET_SYNC` of a server: inserts the row or updates the sync columns of the existing one
     * (`mcComponentVersion`, `capabilities`, `platform`, `protocol`, `queuedCount`, `lastSeenAt`). `settings` is never
     * touched by this call.
     */
    abstract suspend fun upsertSync(state: MarketServerState, sqlClient: SqlClient)

    abstract suspend fun getByServerId(serverId: Long, sqlClient: SqlClient): MarketServerState?

    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketServerState>

    /** Sets the per-server settings JSON (`null` = panel defaults). Returns `false` when the server has no row. */
    abstract suspend fun updateSettings(serverId: Long, settings: String?, now: Long, sqlClient: SqlClient): Boolean
}
