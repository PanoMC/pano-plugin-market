package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketPaymentMethod
import io.vertx.sqlclient.SqlClient

abstract class MarketPaymentMethodDao : MarketDao<MarketPaymentMethod>(MarketPaymentMethod::class.java) {
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketPaymentMethod>

    abstract suspend fun getByMethodId(methodId: String, sqlClient: SqlClient): MarketPaymentMethod?

    abstract suspend fun upsertByMethodId(
        methodId: String,
        enabled: Boolean,
        settings: String,
        sqlClient: SqlClient
    )

    /**
     * Writes the provider configuration of [row] (MK-046): `enabled`, `settings` and the rule columns of 01 section 6.1,
     * inserting the row when the provider has none yet. `createdAt`, `lastInboundAt`, `lastError` and `lastErrorAt` are left
     * alone (the error columns have [setLastError]).
     */
    abstract suspend fun saveConfig(row: MarketPaymentMethod, sqlClient: SqlClient)

    /** Sets `lastError` / `lastErrorAt` of an existing row (both `null` clears them); `false` when the provider has no row. */
    abstract suspend fun setLastError(methodId: String, error: String?, at: Long?, sqlClient: SqlClient): Boolean

    /** Sets the checkout position of an existing row; `false` when the provider has no row. */
    abstract suspend fun setPosition(methodId: String, position: Int, sqlClient: SqlClient): Boolean
}
