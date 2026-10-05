package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

@Migration
class MarketMigration1to2 : DatabaseMigration(1, 2, "Add exchangeRate to market_order") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        addExchangeRateColumn()
    )

    // A failing plugin migration shuts the whole platform down (01 section 14.1 rule 3): the handler logs and goes
    // on; MarketSchema.ensure repeats the same idempotent statement at every start and SchemaVerifier reports it.
    private fun addExchangeRateColumn(): suspend (sqlClient: SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            for (query in MarketSchema.ORDER.alterSql(prefix())) {
                try {
                    sqlClient.query(query).execute().coAwait()
                } catch (e: Exception) {
                    logger.error("Market migration 1 -> 2 statement failed, continuing: {}", e.message)
                }
            }
        }

    // The seam of 17 section 4 S5: tests set the override, production falls through to the platform prefix.
    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    private companion object {
        val logger = LoggerFactory.getLogger(MarketMigration1to2::class.java)
    }
}
