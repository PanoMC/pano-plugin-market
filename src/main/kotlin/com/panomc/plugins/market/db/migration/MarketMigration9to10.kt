package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 9 to 10 (01 section 14.2): shipping, abuse and store modules. This file holds the shipping tables
 * (zone, method, rate, carrier, shipment, shipment item and event, MK-031) and the abuse and module tables
 * (`market_block`, `market_throttle`, `market_goal`, MK-030). One
 * handler per statement taken from the declaration in [MarketSchema]; a failing statement is logged without stopping
 * the step (rule 3). There is no data to convert (rule 2).
 */
@Migration
class MarketMigration9to10 : DatabaseMigration(9, 10, "Shipping, abuse and modules: shipping, block, throttle and goal") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 9 -> 10 statement failed, continuing: {}", e.message)
            }
        }

    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration9to10::class.java)

        private val created = listOf(
            MarketSchema.SHIPPING_ZONE, MarketSchema.SHIPPING_METHOD, MarketSchema.SHIPPING_RATE,
            MarketSchema.SHIPPING_CARRIER, MarketSchema.SHIPMENT, MarketSchema.SHIPMENT_ITEM,
            MarketSchema.SHIPMENT_EVENT, MarketSchema.BLOCK, MarketSchema.THROTTLE, MarketSchema.GOAL
        )

        /** Every statement of the step as a function of the table prefix: the `CREATE`s. */
        internal fun statements(): List<(String) -> String> =
            created.map { table -> { prefix: String -> table.createSql(prefix) } }
    }
}
