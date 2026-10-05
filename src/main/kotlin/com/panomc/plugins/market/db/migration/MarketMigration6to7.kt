package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 6 to 7 (01 section 14.2): the credit ledger. The tables `market_credit_account`, `market_credit_tx`
 * and `market_credit_entry` are created and the five system accounts (`ISSUANCE`, `SPENT`, `HOLD`, `REVOKED`,
 * `EXTERNAL`) are seeded with one `INSERT IGNORE`, so running the step again leaves five rows. Every statement comes
 * from the declaration in [MarketSchema], one handler per statement, and a failing statement is logged without
 * stopping the step (rule 3). There is no data to convert (rule 2).
 */
@Migration
class MarketMigration6to7 : DatabaseMigration(6, 7, "Credits: credit account, credit transaction, credit entry and the five system accounts") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 6 -> 7 statement failed, continuing: {}", e.message)
            }
        }

    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration6to7::class.java)

        private val created = listOf(MarketSchema.CREDIT_ACCOUNT, MarketSchema.CREDIT_TX, MarketSchema.CREDIT_ENTRY)

        /** Every statement of the step as a function of the table prefix: the three `CREATE`s, then the seed of the system accounts. */
        internal fun statements(): List<(String) -> String> =
            created.map { table -> { prefix: String -> table.createSql(prefix) } } +
                { prefix: String -> MarketSchema.seedCreditSystemAccountsSql(prefix) }
    }
}
