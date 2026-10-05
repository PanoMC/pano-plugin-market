package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 8 to 9 (01 section 14.2): subscriptions. The tables `market_subscription` and
 * `market_subscription_renewal` are created, one handler per statement taken from the declaration in [MarketSchema];
 * a failing statement is logged without stopping the step (rule 3). There is no data to convert (rule 2).
 */
@Migration
class MarketMigration8to9 : DatabaseMigration(8, 9, "Subscriptions: subscription and subscription renewal") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 8 -> 9 statement failed, continuing: {}", e.message)
            }
        }

    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration8to9::class.java)

        private val created = listOf(
            MarketSchema.SUBSCRIPTION, MarketSchema.SUBSCRIPTION_RENEWAL
        )

        /** Every statement of the step as a function of the table prefix: the two `CREATE`s. */
        internal fun statements(): List<(String) -> String> =
            created.map { table -> { prefix: String -> table.createSql(prefix) } }
    }
}
