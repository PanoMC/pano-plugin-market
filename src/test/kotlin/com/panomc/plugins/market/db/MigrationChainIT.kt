package com.panomc.plugins.market.db

import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.db.migration.MarketMigration2to3
import com.panomc.plugins.market.db.migration.MarketMigration3to4
import com.panomc.plugins.market.support.MarketMigrationTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The migration chain from the frozen scheme-version-2 install (17 section 11.3 `MigrationChainIT`, 01 section 14.1
 * rule 6): the resulting schema equals the schema of a fresh `ensure()` (table, column, type, default and index
 * sets), the seed rows are unchanged in their existing columns, and a step is idempotent and survives being
 * interrupted half-way. Each later migration slice appends its step to [chain]; `2 -> 3` and `3 -> 4` are in.
 */
class MigrationChainIT : MarketMigrationTestBase() {
    private val chain: List<() -> DatabaseMigration> = listOf({ MarketMigration2to3() }, { MarketMigration3to4() })

    private suspend fun runChain(client: SqlClient = pool) {
        for (step in chain) step().migrate(client)
    }

    /** The schema of a fresh install, read from a second throwaway database. */
    private suspend fun freshSchema(): SchemaSnapshot {
        val admin = MarketTestDb.adminPool()
        val reference = MarketTestDb.newDatabaseName()
        var referencePool: Pool? = null
        try {
            MarketTestDb.createDatabase(admin, reference)
            referencePool = MarketTestDb.pool(reference, 2)
            val report = MarketSchema.ensure(referencePool, prefix)
            assertTrue(report.clean, report.ddlErrors.toString())
            return SchemaSnapshot.take(referencePool)
        } finally {
            runCatching { referencePool?.close()?.coAwait() }
            runCatching { MarketTestDb.dropDatabase(admin, reference) }
            runCatching { admin.close().coAwait() }
        }
    }

    /** Every row of every table in the columns the table has right now, as comparable strings. */
    private suspend fun dump(columnsOf: Map<String, List<String>>): Map<String, List<List<String>>> =
        columnsOf.mapValues { (table, columns) ->
            val select = columns.joinToString(", ") { "`$it`" }
            sql("SELECT $select FROM `$table` ORDER BY `id`").map { row -> columns.map { c -> "${row.getValue(c)}" } }
        }

    private suspend fun columnsOfCurrentTables(): Map<String, List<String>> =
        sql(
            "SELECT TABLE_NAME AS t, COLUMN_NAME AS c FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                "ORDER BY TABLE_NAME, ORDINAL_POSITION"
        ).groupBy({ it.getString("t") }, { it.getString("c") })

    @Test
    fun `the step declares 2 to 3 with one handler per statement`() {
        val migration = MarketMigration2to3()
        assertEquals(2, migration.from)
        assertEquals(3, migration.to)
        assertTrue(migration.isMigratable(2) && !migration.isMigratable(1))
        // 2 category columns + 29 product columns + 3 product indexes + 6 CREATE TABLE
        assertEquals(2 + 29 + 3 + 6, migration.handlers.size)
        assertEquals(2, MarketSchema.CATEGORY.alters.size)
        assertEquals(32, MarketSchema.PRODUCT.alters.size)
        assertTrue(MarketSchema.PRODUCT.alters.all { it.contains("IF NOT EXISTS") })
    }

    @Test
    fun `the chain from schema v2 and seed v2 equals a fresh ensure schema`(): Unit = runBlocking {
        assertEquals(10, SchemaSnapshot.take(pool).tables.size)
        runChain()
        val migrated = SchemaSnapshot.take(pool)
        val fresh = freshSchema()
        assertEquals(19, migrated.tables.size)
        assertEquals(fresh.tables, migrated.tables)
        assertEquals(fresh.columns, migrated.columns)
        assertEquals(fresh.keys, migrated.keys)
        val verdict = SchemaVerifier.verify(pool, prefix)
        assertTrue(verdict.ok, verdict.describe().toString())
    }

    @Test
    fun `seed rows are unchanged in their existing columns and new columns carry their defaults`(): Unit = runBlocking {
        val columns = columnsOfCurrentTables()
        assertEquals(10, columns.size)
        val before = dump(columns)
        assertEquals(6, before.getValue("pano_market_product").size)

        runChain()

        assertEquals(before, dump(columns))

        assertEquals(6L, count("market_product", "`kind` = 'STANDARD' AND `billingMode` = 'ONE_TIME' AND `allowGift` = 1 AND `physical` = 0 AND `hasVariants` = 0 AND `soldCount` = 0"))
        assertEquals(6L, count("market_product", "`periodUnit` IS NULL AND `deletedAt` IS NULL AND `compareAtPrice` IS NULL AND `vatPercent` IS NULL AND `creditAmount` IS NULL"))
        assertEquals(3L, count("market_category", "`tiered` = 0 AND `upgradeMode` = 'DIFFERENCE'"))
        // actions[].id is not backfilled in SQL: the JSON text of every product is the seeded one
        assertEquals(0L, count("market_product", "`actions` LIKE '%\"id\"%'"))
        // the new tables start empty
        for (t in listOf("product_variant", "product_price", "product_field", "bundle_item", "product_provider_meta", "currency_rate")) {
            assertEquals(0L, count("market_$t"), t)
        }
    }

    @Test
    fun `the new indexes of market_product exist after the step`(): Unit = runBlocking {
        runChain()
        val names = sql(
            "SELECT DISTINCT INDEX_NAME AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_product'"
        ).map { it.getString("n") }.toSet()
        assertTrue(names.containsAll(listOf("idx_imageFileName", "idx_kind", "idx_category_tier", "unique_slug", "PRIMARY")), names.toString())
    }

    @Test
    fun `running the step twice changes neither schema nor data`(): Unit = runBlocking {
        runChain()
        val columns = columnsOfCurrentTables()
        val schema = SchemaSnapshot.take(pool)
        val data = dump(columns)
        runChain()
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(data, dump(columns))
        // ensure on top of the migrated schema is a no-op as well
        assertTrue(MarketSchema.ensure(pool, prefix).clean)
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(data, dump(columns))
    }

    @Test
    fun `a step interrupted after any number of handlers is completed by ensure and the full step`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        val handlerCount = MarketMigration2to3().handlers.size

        // first half, a single handler, all but one handler
        for (stopAfter in listOf(handlerCount / 2, 1, handlerCount - 1)) {
            resetState()
            val handlers = MarketMigration2to3().handlers
            for (handler in handlers.take(stopAfter)) handler(pool)
            assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is a partial schema")

            val report = MarketSchema.ensure(pool, prefix)
            assertTrue(report.clean, report.ddlErrors.toString())
            runChain()
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        }
    }

    @Test
    fun `ensure alone brings the version 2 install to the same schema`(): Unit = runBlocking {
        val report = MarketSchema.ensure(pool, prefix)
        assertTrue(report.clean, report.ddlErrors.toString())
        assertEquals(freshSchema(), SchemaSnapshot.take(pool))
    }

    @Test
    fun `a failing statement is logged, the step goes on and the verifier names what is missing`(): Unit = runBlocking {
        sql("CREATE VIEW `pano_market_product_variant` AS SELECT 1 AS id") // CREATE TABLE IF NOT EXISTS is a no-op on a name in use
        try {
            MarketMigration2to3().migrate(pool) // does not throw
            MarketMigration3to4().migrate(pool)

            val findings = SchemaVerifier.verify(pool, prefix).findings
            assertEquals(listOf("pano_market_product_variant"), findings.map { it.target })
            // every other statement still ran
            assertEquals(22L + 29L, sql(
                "SELECT COUNT(*) AS c FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_product'"
            ).single().getLong("c"))

            val closed = MarketTestDb.pool(databaseName, 1)
            closed.close().coAwait()
            MarketMigration2to3().migrate(closed) // a dead connection does not throw either
            MarketMigration3to4().migrate(closed)
        } finally {
            sql("DROP VIEW IF EXISTS `pano_market_product_variant`")
        }
    }

    @Test
    fun `the step declares 3 to 4 with one handler per statement`() {
        val migration = MarketMigration3to4()
        assertEquals(3, migration.from)
        assertEquals(4, migration.to)
        assertTrue(migration.isMigratable(3) && !migration.isMigratable(2))
        // discount +3, coupon +3, creator code +4 columns and 1 index, gift +5; 3 CREATE TABLE
        assertEquals(3 + 3 + (4 + 1) + 5 + 3, migration.handlers.size)
        assertEquals(5, MarketSchema.CREATOR_CODE.alters.size)
        assertTrue((MarketSchema.DISCOUNT.alters + MarketSchema.COUPON.alters + MarketSchema.CREATOR_CODE.alters + MarketSchema.GIFT.alters).all { it.contains("IF NOT EXISTS") })
        assertTrue(MarketSchema.GIFT.alters.none { it.trim().startsWith("UPDATE", ignoreCase = true) }, "no backfill statement")
    }

    @Test
    fun `step 3 to 4 gives seeded gifts redeemLimit 1 from the column default and keeps the other columns`(): Unit = runBlocking {
        MarketMigration2to3().migrate(pool)
        val columns = columnsOfCurrentTables()
        val before = dump(columns)
        val gifts = before.getValue("pano_market_gift").size
        assertTrue(gifts > 0)

        MarketMigration3to4().migrate(pool)

        assertEquals(before, dump(columns))
        assertEquals(gifts.toLong(), count("market_gift", "`redeemLimit` = 1 AND `customerRedeemLimit` = 1 AND `usedCount` = 0 AND `name` = '' AND `deletedAt` IS NULL"))
        assertEquals(sql("SELECT COUNT(*) AS c FROM `pano_market_discount`").single().getLong("c"), count("market_discount", "`showBadge` = 1 AND `legacyUsedCount` = 0 AND `deletedAt` IS NULL"))
        assertEquals(sql("SELECT COUNT(*) AS c FROM `pano_market_coupon`").single().getLong("c"), count("market_coupon", "`categoryIds` IS NULL AND `legacyUsedCount` = 0 AND `deletedAt` IS NULL"))
        assertEquals(sql("SELECT COUNT(*) AS c FROM `pano_market_creator_code`").single().getLong("c"), count("market_creator_code", "`creatorUserId` IS NULL AND `paidOut` = 0 AND `legacyUsedCount` = 0 AND `deletedAt` IS NULL"))
        // the column default itself is 1, and an explicit NULL stays NULL (unlimited gift)
        assertEquals("1", sql("SELECT COLUMN_DEFAULT AS d FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_gift' AND COLUMN_NAME = 'redeemLimit'").single().getString("d"))
        for (t in listOf("redemption", "creator_earning", "creator_payout")) assertEquals(0L, count("market_$t"), t)
        val names = sql("SELECT DISTINCT INDEX_NAME AS n FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_creator_code'").map { it.getString("n") }.toSet()
        assertTrue("idx_creatorUser" in names, names.toString())
    }

    @Test
    fun `step 3 to 4 survives an interruption and runs twice`(): Unit = runBlocking {
        runChain()
        val expected = SchemaSnapshot.take(pool)
        val total = MarketMigration3to4().handlers.size
        for (stopAfter in listOf(total / 2, 1, total - 1)) {
            resetState()
            MarketMigration2to3().migrate(pool)
            for (handler in MarketMigration3to4().handlers.take(stopAfter)) handler(pool)
            assertTrue(SchemaSnapshot.take(pool) != expected, "interrupted after $stopAfter handlers is partial")
            assertTrue(MarketSchema.ensure(pool, prefix).clean)
            MarketMigration3to4().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool), "interrupted after $stopAfter handlers")
            MarketMigration3to4().migrate(pool)
            assertEquals(expected, SchemaSnapshot.take(pool))
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        }
    }
}
