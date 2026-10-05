package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 7 to 8 (01 section 14.2): delivery and outbound messages. The tables `market_delivery`,
 * `market_server_state`, `market_webhook_endpoint`, `market_webhook_delivery` and `market_mail_outbox` are created,
 * one handler per statement taken from the declaration in [MarketSchema]; a failing statement is logged without
 * stopping the step (rule 3). There is no data to convert (rule 2).
 */
@Migration
class MarketMigration7to8 : DatabaseMigration(7, 8, "Delivery: delivery, server state, webhook endpoint, webhook delivery and mail outbox") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 7 -> 8 statement failed, continuing: {}", e.message)
            }
        }

    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration7to8::class.java)

        private val created = listOf(
            MarketSchema.DELIVERY, MarketSchema.SERVER_STATE, MarketSchema.WEBHOOK_ENDPOINT,
            MarketSchema.WEBHOOK_DELIVERY, MarketSchema.MAIL_OUTBOX
        )

        /** Every statement of the step as a function of the table prefix: the five `CREATE`s. */
        internal fun statements(): List<(String) -> String> =
            created.map { table -> { prefix: String -> table.createSql(prefix) } }
    }
}
