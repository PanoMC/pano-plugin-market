package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketPaymentMethod
import io.vertx.sqlclient.SqlClient

abstract class MarketPaymentMethodDao : Dao<MarketPaymentMethod>(MarketPaymentMethod::class.java) {
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketPaymentMethod>

    abstract suspend fun getByMethodId(methodId: String, sqlClient: SqlClient): MarketPaymentMethod?

    abstract suspend fun upsertByMethodId(
        methodId: String,
        enabled: Boolean,
        settings: String,
        sqlClient: SqlClient
    )
}
