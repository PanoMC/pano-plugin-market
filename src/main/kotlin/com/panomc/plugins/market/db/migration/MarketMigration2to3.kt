package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.MarketTables
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

/**
 * Scheme version 2 to 3 (01 section 14.2): the catalogue. `market_category` gains 2 columns, `market_product` 29
 * columns and 3 indexes, and the six tables `market_product_variant`, `market_product_price`,
 * `market_product_field`, `market_bundle_item`, `market_product_provider_meta` and `market_currency_rate` are
 * created. DDL only: no data backfill (rule 7); `actions[].id` is assigned lazily on the next read or save.
 *
 * Every statement comes from the declaration in [MarketSchema] (the same one `Dao.init` and `MarketSchema.ensure`
 * use), one handler per statement. A failing statement is logged and the migration goes on, because a throwing
 * plugin migration shuts the whole platform down (rule 3); `MarketSchema.ensure` repeats the same idempotent
 * statements at every start and `SchemaVerifier` reports what is still missing.
 */
@Migration
class MarketMigration2to3 : DatabaseMigration(2, 3, "Catalogue: product variants, prices, fields, bundles, provider meta, currency rates") {
    override val handlers: List<suspend (SqlClient) -> Unit> =
        statements().map { statement -> handler(statement) }

    private fun handler(statement: (prefix: String) -> String): suspend (SqlClient) -> Unit =
        { sqlClient: SqlClient ->
            try {
                sqlClient.query(statement(prefix())).execute().coAwait()
            } catch (e: Exception) {
                logger.error("Market migration 2 -> 3 statement failed, continuing: {}", e.message)
            }
        }

    // The seam of 17 section 4 S5: tests set the override, production falls through to the platform prefix.
    private fun prefix(): String = MarketTables.prefixOverride ?: getTablePrefix()

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration2to3::class.java)

        /** The ALTERed existing tables, then the created ones, in dependency-free order. */
        private val altered = listOf(MarketSchema.CATEGORY, MarketSchema.PRODUCT)
        private val created = listOf(
            MarketSchema.PRODUCT_VARIANT, MarketSchema.PRODUCT_PRICE, MarketSchema.PRODUCT_FIELD,
            MarketSchema.BUNDLE_ITEM, MarketSchema.PRODUCT_PROVIDER_META, MarketSchema.CURRENCY_RATE
        )

        /** Every statement of the step as a function of the table prefix: `ALTER`s and index creations, then `CREATE`s. */
        internal fun statements(): List<(String) -> String> {
            val alters = altered.flatMap { table ->
                table.alters.indices.map { index -> { prefix: String -> table.alterSql(prefix)[index] } }
            }
            val creates = created.map { table -> { prefix: String -> table.createSql(prefix) } }
            return alters + creates
        }
    }
}
