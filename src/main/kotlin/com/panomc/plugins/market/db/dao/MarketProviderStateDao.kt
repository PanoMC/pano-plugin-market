package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** The key/value store handed to providers (01 section 6.6). `value` is an ENC column stored verbatim. */
abstract class MarketProviderStateDao : MarketDao<MarketProviderState>(MarketProviderState::class.java) {
    /** The new id, or `null` on a unique-key duplicate. */
    abstract suspend fun add(providerState: MarketProviderState, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketProviderState?

    /** The entry of (kind, provider, key) (`uq_kind_provider_key`). */
    abstract suspend fun get(kind: ProviderStateKind, providerId: String, stateKey: String, sqlClient: SqlClient): MarketProviderState?
}
