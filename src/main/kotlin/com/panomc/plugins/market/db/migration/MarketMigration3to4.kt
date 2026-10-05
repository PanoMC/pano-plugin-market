package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 3 to 4 (01 section 14.2): promotions. `market_discount` gains 3 columns, `market_coupon` 3,
 * `market_creator_code` 4 columns and the index `idx_creatorUser`, `market_gift` 5 columns (`redeemLimit` and
 * `customerRedeemLimit` default to 1 in the column, no backfill statement), and the tables `market_redemption`,
 * `market_creator_earning` and `market_creator_payout` are created. DDL only (rule 7): `legacyUsedCount` is written by
 * a fixup of the order slice. Every statement comes from the declaration in [MarketSchema], one handler per
 * statement, and a failing statement is logged without stopping the step (rule 3).
 */
@Migration
class MarketMigration3to4 : DatabaseMigration(3, 4, "Promotions: redemptions, creator earnings and payouts") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 3 -> 4 statement failed, continuing: {}", e.message)
            }
        }

    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration3to4::class.java)

        private val altered = listOf(MarketSchema.DISCOUNT, MarketSchema.COUPON, MarketSchema.CREATOR_CODE, MarketSchema.GIFT)
        private val created = listOf(MarketSchema.REDEMPTION, MarketSchema.CREATOR_EARNING, MarketSchema.CREATOR_PAYOUT)

        /** Every statement of the step as a function of the table prefix: the `ALTER`s and index creations, then the `CREATE`s. */
        internal fun statements(): List<(String) -> String> {
            val alters = altered.flatMap { table ->
                // The old version 2 / 3 alters of a table (none for these four) are not part of this step: only the
                // statements of the `added` block of scheme version 4 are.
                table.alters.indices.map { index -> { prefix: String -> table.alterSql(prefix)[index] } }
            }
            val creates = created.map { table -> { prefix: String -> table.createSql(prefix) } }
            return alters + creates
        }
    }
}
