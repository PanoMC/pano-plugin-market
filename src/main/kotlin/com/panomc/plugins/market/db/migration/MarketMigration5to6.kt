package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 5 to 6 (01 section 14.2): payments. `market_payment_method` gains 14 columns and the tables
 * `market_payment`, `market_payment_event`, `market_refund`, `market_refund_item`, `market_dispute` and
 * `market_provider_state` are created. DDL only (rule 2): there is no data to convert. Every statement comes from the
 * declaration in [MarketSchema], one handler per statement, and a failing statement is logged without stopping the
 * step (rule 3).
 */
@Migration
class MarketMigration5to6 : DatabaseMigration(5, 6, "Payments: payment method columns, payment, payment event, refund, refund item, dispute, provider state") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 5 -> 6 statement failed, continuing: {}", e.message)
            }
        }

    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration5to6::class.java)

        private val created = listOf(
            MarketSchema.PAYMENT, MarketSchema.PAYMENT_EVENT, MarketSchema.REFUND, MarketSchema.REFUND_ITEM,
            MarketSchema.DISPUTE, MarketSchema.PROVIDER_STATE
        )

        /** Every statement of the step as a function of the table prefix: the `ALTER`s of `market_payment_method`, then the `CREATE`s. */
        internal fun statements(): List<(String) -> String> {
            val methodAlters = MarketSchema.PAYMENT_METHOD.alters.indices
                .map { index -> { prefix: String -> MarketSchema.PAYMENT_METHOD.alterSql(prefix)[index] } }
            val creates = created.map { table -> { prefix: String -> table.createSql(prefix) } }
            return methodAlters + creates
        }
    }
}
