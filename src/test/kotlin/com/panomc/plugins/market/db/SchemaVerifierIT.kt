package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.SchemaVerifier.Kind
import com.panomc.plugins.market.support.MarketDbTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `SchemaVerifier` against a real database (17 section 11.3 `SchemaVerifierIT`): the expectation is derived from
 * `MarketSchema`; this test compares it with `information_schema` on its own, then damages the schema one object at
 * a time and expects exactly that object in the report.
 */
class SchemaVerifierIT : MarketDbTestBase() {
    /** The unique indexes of the existing tables (00 section 8.1 and the scheme-version-2 install). */
    private val uniqueIndexes = mapOf(
        "pano_market_coupon" to ("unique_code" to listOf("code")),
        "pano_market_creator_code" to ("unique_code" to listOf("code")),
        "pano_market_gift" to ("unique_code" to listOf("code")),
        "pano_market_payment_method" to ("unique_method_id" to listOf("methodId")),
        "pano_market_product" to ("unique_slug" to listOf("slug"))
    )

    private suspend fun rebuild() {
        MarketTestDb.dropAllTables(pool)
        MarketSchema.ensure(pool, prefix)
    }

    private suspend fun verify() = SchemaVerifier.verify(pool, prefix)

    @Test
    fun `a fresh ensure verifies clean`(): Unit = runBlocking {
        val result = verify()
        assertTrue(result.ok, result.describe().toString())
        assertTrue(result.describe().isEmpty())
    }

    @Test
    fun `every declared column and index exists in information_schema with the documented type`(): Unit = runBlocking {
        for (table in MarketSchema.tables) {
            val physical = table.physicalName(prefix)
            val columns = sql(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", physical
            ).associateBy { it.getString("COLUMN_NAME") }
            for (column in table.columns) {
                val row = columns[column.name]
                assertTrue(row != null, "$physical.${column.name} is missing")
                assertEquals(column.dataType, row!!.getString("DATA_TYPE").lowercase(), "$physical.${column.name} type")
                assertEquals(if (column.nullable) "YES" else "NO", row.getString("IS_NULLABLE"), "$physical.${column.name} null")
                if (column.charLength != null) {
                    assertEquals(column.charLength, (row.getValue("CHARACTER_MAXIMUM_LENGTH") as Number).toLong())
                }
            }
            val keys = sql(
                "SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? GROUP BY INDEX_NAME, NON_UNIQUE", physical
            ).associateBy { it.getString("INDEX_NAME") }
            for (key in table.keys) {
                val row = keys[key.name]
                assertTrue(row != null, "$physical#${key.name} is missing")
                assertEquals(key.columns.joinToString(","), row!!.getString("cols"))
                assertEquals(key.unique, (row.getValue("NON_UNIQUE") as Number).toInt() == 0)
            }
        }
    }

    @Test
    fun `the unique indexes of the existing tables are declared and present`(): Unit = runBlocking {
        for ((physical, key) in uniqueIndexes) {
            val declared = MarketSchema.tables.single { it.physicalName(prefix) == physical }.keys.single { it.name == key.first }
            assertTrue(declared.unique)
            assertEquals(key.second, declared.columns)
            val rows = sql(
                "SELECT NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                physical, key.first
            )
            assertEquals(1, rows.size)
            assertEquals(0, (rows.single().getValue("NON_UNIQUE") as Number).toInt())
        }
    }

    @Test
    fun `dropping an index makes verify report exactly that index`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_coupon` DROP INDEX `unique_code`")
            val result = verify()
            val finding = result.findings.single()
            assertEquals(Kind.MISSING_INDEX, finding.kind)
            assertEquals("pano_market_coupon#unique_code", finding.target)
            assertFalse(result.ok)
        } finally {
            rebuild()
        }
        assertTrue(verify().ok)
    }

    @Test
    fun `an index that lost its uniqueness is reported as a mismatch`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_product` DROP INDEX `unique_slug`, ADD INDEX `unique_slug` (`slug`)")
            val finding = verify().findings.single()
            assertEquals(Kind.INDEX_MISMATCH, finding.kind)
            assertEquals("pano_market_product#unique_slug", finding.target)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `a missing column is reported by table and name`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_gift` DROP COLUMN `creditAmount`")
            val finding = verify().findings.single()
            assertEquals(Kind.MISSING_COLUMN, finding.kind)
            assertEquals("pano_market_gift.creditAmount", finding.target)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `a failed widening of a column length is caught`(): Unit = runBlocking {
        try {
            // the order status stays VARCHAR(16) here; the declared type is checked by length and nullability
            sql("ALTER TABLE `pano_market_order` MODIFY `status` VARCHAR(8) NOT NULL DEFAULT 'PENDING'")
            val finding = verify().findings.single()
            assertEquals(Kind.COLUMN_MISMATCH, finding.kind)
            assertEquals("pano_market_order.status", finding.target)
            assertTrue(finding.detail.contains("varchar(16)") && finding.detail.contains("varchar(8)"), finding.detail)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `a wrong type and a wrong nullability are caught`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_coupon` MODIFY `discount` VARCHAR(20) NOT NULL")
            sql("ALTER TABLE `pano_market_coupon` MODIFY `minPaymentAmount` BIGINT NOT NULL DEFAULT 0")
            val targets = verify().findings.associate { it.target to it.kind }
            assertEquals(
                mapOf(
                    "pano_market_coupon.discount" to Kind.COLUMN_MISMATCH,
                    "pano_market_coupon.minPaymentAmount" to Kind.COLUMN_MISMATCH
                ),
                targets
            )
        } finally {
            rebuild()
        }
    }

    @Test
    fun `a missing table and a view in its place are both a missing table`(): Unit = runBlocking {
        try {
            sql("DROP TABLE `pano_market_comparison`")
            sql("DROP TABLE `pano_market_category`")
            sql("CREATE VIEW `pano_market_category` AS SELECT 1 AS id")
            val result = verify()
            assertEquals(listOf("pano_market_category", "pano_market_comparison"), result.missingTables().sorted())
            assertEquals(2, result.findings.size)
        } finally {
            rebuild()
        }
    }

    @Test
    fun `extra columns indexes and tables are not reported`(): Unit = runBlocking {
        try {
            sql("ALTER TABLE `pano_market_coupon` ADD COLUMN `extra` INT, ADD INDEX `extra_idx` (`extra`)")
            sql("CREATE TABLE `pano_market_zz_extra` (`id` INT) ENGINE=InnoDB")
            assertTrue(verify().ok)
        } finally {
            runCatching { sql("DROP TABLE IF EXISTS `pano_market_zz_extra`") }
            rebuild()
        }
    }

    @Test
    fun `unfixed rows of a fixup are counted and reported`(): Unit = runBlocking {
        val probe = "pano_market_zz_verify_probe"
        try {
            sql("CREATE TABLE `$probe` (`id` INT NOT NULL AUTO_INCREMENT, `v` INT NOT NULL DEFAULT 0, PRIMARY KEY (`id`)) ENGINE=InnoDB")
            sql("INSERT INTO `$probe` (`v`) VALUES (0), (0), (0), (1)")
            val fixup = MarketSchema.Fixup(
                "probe", requires = listOf("market_zz_verify_probe"),
                pendingSql = { p -> "SELECT COUNT(*) FROM `${p}market_zz_verify_probe` WHERE `v` = 0" }
            ) { client, p -> client.query("UPDATE `${p}market_zz_verify_probe` SET `v` = 1 WHERE `v` = 0").execute().coAwait() }

            val before = SchemaVerifier.verify(pool, prefix, MarketSchema.tables, listOf(fixup))
            assertTrue(before.findings.isEmpty())
            assertEquals(mapOf("probe" to 3L), before.unfixed)
            assertEquals(listOf("UNFIXED probe: 3 row(s)"), before.describe())

            MarketSchema.ensure(pool, prefix, MarketSchema.tables, listOf(fixup))
            assertTrue(SchemaVerifier.verify(pool, prefix, MarketSchema.tables, listOf(fixup)).ok)

            // a fixup whose table is gone is skipped by the verifier (the missing table is reported elsewhere)
            sql("DROP TABLE `$probe`")
            assertTrue(SchemaVerifier.verify(pool, prefix, MarketSchema.tables, listOf(fixup)).ok)
        } finally {
            runCatching { sql("DROP TABLE IF EXISTS `$probe`") }
        }
    }

    @Test
    fun `an unreachable database is a CHECK_FAILED finding and not an exception`(): Unit = runBlocking {
        val closed = MarketTestDb.pool(databaseName, 1)
        closed.close().coAwait()
        val result = SchemaVerifier.verify(closed, prefix)
        assertFalse(result.ok)
        assertTrue(result.findings.any { it.kind == Kind.CHECK_FAILED })
    }
}
