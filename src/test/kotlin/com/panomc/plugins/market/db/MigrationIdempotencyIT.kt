package com.panomc.plugins.market.db

import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.migration.MarketMigration2to3
import com.panomc.plugins.market.db.migration.MarketMigration3to4
import com.panomc.plugins.market.db.migration.MarketMigration4to5
import com.panomc.plugins.market.db.migration.MarketMigration5to6
import com.panomc.plugins.market.db.migration.MarketMigration6to7
import com.panomc.plugins.market.db.migration.MarketMigration7to8
import com.panomc.plugins.market.db.migration.MarketMigration8to9
import com.panomc.plugins.market.db.migration.MarketMigration9to10
import com.panomc.plugins.market.support.MarketMigrationTestBase
import com.panomc.plugins.market.support.SeqIds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `MigrationIdempotencyIT` (17 section 11.3, 01 section 14.1 rule 6): the whole chain `2 -> 3 ... 9 -> 10`, run twice in
 * a row on the frozen scheme-version-2 install, leaves an identical schema and identical data; the same holds when
 * `ensure()` (DDL and fixups) runs between the two runs and after them, and for `ensure()` alone run twice. The data
 * comparison covers every column of every table, in id order.
 */
class MigrationIdempotencyIT : MarketMigrationTestBase() {
    private fun chain() = listOf(
        MarketMigration2to3(), MarketMigration3to4(), MarketMigration4to5(), MarketMigration5to6(), MarketMigration6to7(),
        MarketMigration7to8(), MarketMigration8to9(), MarketMigration9to10()
    )

    private suspend fun runChain() {
        for (step in chain()) step.migrate(pool)
    }

    /** `ensure()` with deterministic ids, so two runs that convert the legacy rows write the same `publicId` values. */
    private suspend fun ensure(ids: Ids = SeqIds(100)) {
        val report = MarketSchema.ensure(pool, prefix, MarketSchema.tables, MarketSchema.fixups(ids))
        assertTrue(report.clean, "ddl=${report.ddlErrors} fixups=${report.fixupErrors}")
    }

    /** [stable] leaves out the wall-clock stamps (the system credit accounts are seeded with `now`) for comparisons across two runs. */
    private suspend fun data(stable: Boolean = false): Map<String, List<List<String>>> {
        val tables = sql(
            "SELECT TABLE_NAME AS t FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME"
        ).map { it.getString("t") }
        val columns = sql(
            "SELECT TABLE_NAME AS t, COLUMN_NAME AS c FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION"
        ).groupBy({ it.getString("t") }, { it.getString("c") })
            .mapValues { (_, cols) -> if (stable) cols.filter { it != "createdAt" && it != "updatedAt" } else cols }
        return tables.associateWith { table ->
            val cols = columns.getValue(table)
            sql("SELECT ${cols.joinToString(", ") { "`$it`" }} FROM `$table` ORDER BY `id`").map { row -> cols.map { c -> "${row.getValue(c)}" } }
        }
    }

    @Test
    fun `the chain run twice in a row changes neither schema nor data`(): Unit = runBlocking {
        runChain()
        val schema = SchemaSnapshot.take(pool)
        val dump = data()
        assertEquals(53, schema.tables.size)
        runChain()
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(dump, data())
    }

    @Test
    fun `chain, ensure, chain, ensure leaves the state the first ensure produced`(): Unit = runBlocking {
        runChain()
        ensure()
        val schema = SchemaSnapshot.take(pool)
        val dump = data()
        assertEquals(3, dump.getValue("pano_market_order").size)
        assertTrue(dump.getValue("pano_market_order").all { row -> row.isNotEmpty() })

        runChain()
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(dump, data())
        ensure(SeqIds(5000)) // other ids: nothing is unfixed, so none may be drawn
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(dump, data())
        assertTrue(SchemaVerifier.verify(pool, prefix).ok)
    }

    @Test
    fun `ensure alone run twice changes nothing and equals the chain followed by ensure`(): Unit = runBlocking {
        ensure()
        val schema = SchemaSnapshot.take(pool)
        val dump = data()
        ensure(SeqIds(5000))
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(dump, data())

        val stable = data(stable = true)
        resetState()
        runChain()
        ensure()
        assertEquals(schema, SchemaSnapshot.take(pool))
        assertEquals(stable, data(stable = true))
    }

    @Test
    fun `every single step run twice in a row changes nothing`(): Unit = runBlocking {
        for (step in chain()) {
            step.migrate(pool)
            val schema = SchemaSnapshot.take(pool)
            val dump = data()
            step.migrate(pool)
            assertEquals(schema, SchemaSnapshot.take(pool), "step ${step.from} -> ${step.to}")
            assertEquals(dump, data(), "step ${step.from} -> ${step.to}")
        }
    }
}
