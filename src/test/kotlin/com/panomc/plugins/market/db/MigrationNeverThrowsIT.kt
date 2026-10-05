package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.SchemaVerifier.Kind
import com.panomc.plugins.market.db.migration.MarketMigration1to2
import com.panomc.plugins.market.support.MarketDbTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A migration handler whose statement fails logs and continues (17 section 11.3 `MigrationNeverThrowsIT`, 01
 * section 14.1 rule 3): a plugin migration that throws shuts the whole platform down. `SchemaVerifier` then reports
 * what the handler could not do.
 */
class MigrationNeverThrowsIT : MarketDbTestBase() {
    private suspend fun rebuild() {
        MarketTestDb.dropAllTables(pool)
        MarketSchema.ensure(pool, prefix)
    }

    @Test
    fun `the migration declares the one step 1 to 2`() {
        val migration = MarketMigration1to2()
        assertEquals(1, migration.from)
        assertEquals(2, migration.to)
        assertEquals(1, migration.handlers.size)
        assertTrue(migration.isMigratable(1))
    }

    @Test
    fun `the handler adds the exchange rate column to a version 1 order table`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_order` DROP COLUMN `exchangeRate`")
            assertEquals(Kind.MISSING_COLUMN, SchemaVerifier.verify(pool, prefix).findings.single().kind)

            MarketMigration1to2().migrate(pool)
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)

            MarketMigration1to2().migrate(pool) // idempotent
            assertTrue(SchemaVerifier.verify(pool, prefix).ok)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `a failing statement is swallowed and the verifier reports the missing table`(): Unit = runBlocking {
        try {
            sql("DROP TABLE `pano_market_order`")
            sql("CREATE VIEW `pano_market_order` AS SELECT 1 AS id") // ALTER TABLE on a view fails

            MarketMigration1to2().migrate(pool) // does not throw

            val finding = SchemaVerifier.verify(pool, prefix).findings.single()
            assertEquals(Kind.MISSING_TABLE, finding.kind)
            assertEquals("pano_market_order", finding.target)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `a table with a conflicting column is left alone and the verifier reports the column`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_order` MODIFY `exchangeRate` VARCHAR(10)")

            MarketMigration1to2().migrate(pool) // IF NOT EXISTS: nothing to add, nothing thrown

            val finding = SchemaVerifier.verify(pool, prefix).findings.single()
            assertEquals(Kind.COLUMN_MISMATCH, finding.kind)
            assertEquals("pano_market_order.exchangeRate", finding.target)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `no table at all and a closed pool do not throw either`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        try {
            MarketMigration1to2().migrate(pool)
            assertEquals(MarketSchema.tables.size, SchemaVerifier.verify(pool, prefix).missingTables().size)

            val closed = MarketTestDb.pool(databaseName, 1)
            closed.close().coAwait()
            MarketMigration1to2().migrate(closed)
        } finally {
            rebuild()
        }
    }
}
