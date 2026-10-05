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
import com.panomc.platform.db.DatabaseMigration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `MigrationInterruptedIT` (17 section 11.3): for each of the eight migrations, only part of its handlers run (the first
 * handler, the first half, all but the last), the process "dies", and the next start repairs the install with
 * `ensure()` (the platform records the new scheme version even when a handler never ran, so the missing handlers are
 * never run by the platform again). The result must equal the uninterrupted chain followed by `ensure()`: same schema,
 * same data in every stable column, a clean `SchemaVerifier`. With the full chain run on top, as `MigrationChainIT`
 * does for `2 -> 3`, the result is the same again.
 *
 * Columns that legitimately differ between two runs are left out of the data comparison: `createdAt` / `updatedAt`
 * (the system credit accounts are stamped with the wall clock) and the random `publicId` / `accessToken` of the
 * converted orders, which are asserted separately to be present, unique and well formed.
 */
class MigrationInterruptedIT : MarketMigrationTestBase() {
    private fun chain(): List<DatabaseMigration> = listOf(
        MarketMigration2to3(), MarketMigration3to4(), MarketMigration4to5(), MarketMigration5to6(), MarketMigration6to7(),
        MarketMigration7to8(), MarketMigration8to9(), MarketMigration9to10()
    )

    private suspend fun ensure(ids: Ids = SeqIds(100)) {
        val report = MarketSchema.ensure(pool, prefix, MarketSchema.tables, MarketSchema.fixups(ids))
        assertTrue(report.clean, "ddl=${report.ddlErrors} fixups=${report.fixupErrors}")
    }

    private val volatileColumns = setOf("createdAt", "updatedAt", "publicId", "accessToken")

    private suspend fun data(): Map<String, List<List<String>>> {
        val tables = sql(
            "SELECT TABLE_NAME AS t FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME"
        ).map { it.getString("t") }
        val columns = sql(
            "SELECT TABLE_NAME AS t, COLUMN_NAME AS c FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION"
        ).groupBy({ it.getString("t") }, { it.getString("c") }).mapValues { (_, cols) -> cols.filter { it !in volatileColumns } }
        return tables.associateWith { table ->
            val cols = columns.getValue(table)
            if (cols.isEmpty()) emptyList()
            else sql("SELECT ${cols.joinToString(", ") { "`$it`" }} FROM `$table` ORDER BY `id`").map { row -> cols.map { c -> "${row.getValue(c)}" } }
        }
    }

    private suspend fun assertIdsWellFormed() {
        val rows = sql("SELECT `publicId` AS p, `accessToken` AS a FROM `pano_market_order`")
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.getString("p")?.length == 20 && it.getString("a")?.length == 40 })
        assertEquals(3, rows.map { it.getString("p") }.toSet().size)
    }

    private class Reference(val schema: SchemaSnapshot, val data: Map<String, List<List<String>>>)

    private suspend fun reference(): Reference {
        resetState()
        for (step in chain()) step.migrate(pool)
        ensure()
        assertEquals(53, SchemaSnapshot.take(pool).tables.size)
        return Reference(SchemaSnapshot.take(pool), data())
    }

    private fun cuts(count: Int): List<Int> = listOf(1, count / 2, count - 1).filter { it in 1 until count }.distinct()

    /** Migrations `0 until index` ran completely, `index` only up to [stopAfter] handlers, later ones never ran. */
    private suspend fun interrupt(index: Int, stopAfter: Int) {
        resetState()
        val steps = chain()
        for (step in steps.take(index)) step.migrate(pool)
        for (handler in steps[index].handlers.take(stopAfter)) handler(pool)
    }

    @Test
    fun `every migration interrupted after the first handler, half way and before the last is repaired by ensure alone`(): Unit = runBlocking {
        val expected = reference()
        for ((index, step) in chain().withIndex()) {
            for (stopAfter in cuts(step.handlers.size)) {
                val label = "${step.from} -> ${step.to} interrupted after $stopAfter of ${step.handlers.size} handlers"
                interrupt(index, stopAfter)
                ensure()
                assertEquals(expected.schema, SchemaSnapshot.take(pool), label)
                assertEquals(expected.data, data(), label)
                assertIdsWellFormed()
                assertTrue(SchemaVerifier.verify(pool, prefix).ok, label)
            }
        }
    }

    @Test
    fun `every migration interrupted at the same points is also completed by ensure and the full chain`(): Unit = runBlocking {
        val expected = reference()
        for ((index, step) in chain().withIndex()) {
            for (stopAfter in cuts(step.handlers.size)) {
                val label = "${step.from} -> ${step.to} interrupted after $stopAfter of ${step.handlers.size} handlers"
                interrupt(index, stopAfter)
                ensure()
                for (s in chain()) s.migrate(pool)
                ensure(SeqIds(9000))
                assertEquals(expected.schema, SchemaSnapshot.take(pool), label)
                assertEquals(expected.data, data(), label)
                assertTrue(SchemaVerifier.verify(pool, prefix).ok, label)
            }
        }
    }

    @Test
    fun `an interruption is a partial schema until ensure runs`(): Unit = runBlocking {
        val expected = reference()
        interrupt(chain().size - 1, 1)
        val partial = SchemaSnapshot.take(pool)
        assertTrue(partial != expected.schema, "the interrupted install is not complete yet")
        assertTrue(SchemaVerifier.verify(pool, prefix).findings.isNotEmpty(), "and the verifier says so")
    }
}
