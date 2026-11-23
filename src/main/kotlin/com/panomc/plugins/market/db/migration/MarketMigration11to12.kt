package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 11 to 12: `market_payment_method` gets `customHint` and `hideHint`, so the admin can replace or hide the
 * line a provider shows under its name at checkout. The statements are the last [ALTERS] idempotent alters of
 * [MarketSchema.PAYMENT_METHOD] (its scheme-version-12 block); a failing statement is logged without stopping the step
 * (rule 3), and `MarketSchema.ensure` repeats them at every start.
 */
@Migration
class MarketMigration11to12 : DatabaseMigration(11, 12, "Payment methods: the checkout hint can be replaced or hidden") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { sqlClient: SqlClient ->
            for (query in MarketSchema.PAYMENT_METHOD.alterSql(prefix()).takeLast(ALTERS)) {
                try {
                    sqlClient.query(query).execute().coAwait()
                } catch (e: Exception) {
                    logger.error("Market migration 11 -> 12 statement failed, continuing: {}", e.message)
                }
            }
        }
    )

    // The seam of 17 section 4 S5: tests set the override, production falls through to the platform prefix.
    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        /** The alters of the scheme-version-12 block of [MarketSchema.PAYMENT_METHOD]: `customHint`, `hideHint`. */
        const val ALTERS = 2

        private val logger = LoggerFactory.getLogger(MarketMigration11to12::class.java)
    }
}
