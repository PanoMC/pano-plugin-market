package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 4 to 5 (01 section 14.2): orders. `market_order` has its `status` widened to VARCHAR(24) and gains 70
 * columns and 10 indexes, `market_order_item` gains 25 columns, and the tables `market_order_event`,
 * `market_legal_text`, `market_sequence`, `market_entitlement`, `market_address`, `market_cart`, `market_cart_item` and
 * `market_invoice` are created. DDL only (rule 7): legacy orders are converted by the fixups
 * of [MarketSchema.fixups], not here. Every statement comes from the declaration in [MarketSchema], one handler per
 * statement, and a failing statement is logged without stopping the step (rule 3).
 */
@Migration
class MarketMigration4to5 : DatabaseMigration(4, 5, "Orders: widened status, order columns, timeline, legal text, entitlement, address, cart, invoice") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 4 -> 5 statement failed, continuing: {}", e.message)
            }
        }

    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration4to5::class.java)

        /** `market_order` carried one `ALTER` before this step (the version 1 to 2 `exchangeRate`): not part of it. */
        private const val ORDER_ALTERS_BEFORE_THIS_STEP = 1

        private val created = listOf(MarketSchema.ORDER_EVENT, MarketSchema.LEGAL_TEXT, MarketSchema.SEQUENCE,
            MarketSchema.ENTITLEMENT, MarketSchema.ADDRESS, MarketSchema.CART, MarketSchema.CART_ITEM, MarketSchema.INVOICE
        )

        /** Every statement of the step as a function of the table prefix: the `ALTER`s and index creations, then the `CREATE`s. */
        internal fun statements(): List<(String) -> String> {
            val orderAlters = MarketSchema.ORDER.alters.indices.drop(ORDER_ALTERS_BEFORE_THIS_STEP)
                .map { index -> { prefix: String -> MarketSchema.ORDER.alterSql(prefix)[index] } }
            val itemAlters = MarketSchema.ORDER_ITEM.alters.indices
                .map { index -> { prefix: String -> MarketSchema.ORDER_ITEM.alterSql(prefix)[index] } }
            val creates = created.map { table -> { prefix: String -> table.createSql(prefix) } }
            return orderAlters + itemAlters + creates
        }
    }
}
