package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

@Migration
class MarketMigration1to2 : DatabaseMigration(1, 2, "Add exchangeRate to market_order") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addExchangeRateColumn()
    )

    private fun addExchangeRateColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            val query = "ALTER TABLE `${getTablePrefix()}market_order` ADD COLUMN IF NOT EXISTS `exchangeRate` DOUBLE"
            sqlClient.query(query).execute().coAwait()
        }
}
