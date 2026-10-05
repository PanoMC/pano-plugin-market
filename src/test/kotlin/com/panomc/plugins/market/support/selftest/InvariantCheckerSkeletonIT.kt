package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.support.InvariantChecker
import com.panomc.plugins.market.support.InvariantViolation
import com.panomc.plugins.market.support.MarketDbTestBase
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The skeleton of `InvariantChecker` (MK-012): the one implemented invariant (I5, stock never negative) catches a
 * violating row and passes a consistent database, a check whose tables are missing is reported as skipped, and both
 * `assertAll` shapes that the database test bases call exist. The class installs its own two probe tables (only the
 * columns the checks read) instead of the market schema, and checks the invariants itself.
 */
class InvariantCheckerSkeletonIT : MarketDbTestBase() {
    private val product = "pano_market_product"
    private val variant = "pano_market_product_variant"

    override suspend fun installSchema() {
        pool.query("CREATE TABLE `$product` (`id` BIGINT PRIMARY KEY, `stock` INT NOT NULL) ENGINE=InnoDB").execute().coAwait()
        pool.query("CREATE TABLE `$variant` (`id` BIGINT PRIMARY KEY, `stock` INT NOT NULL) ENGINE=InnoDB").execute().coAwait()
    }

    /** The test calls the checker itself; the automatic check after each test would trip over the violations on purpose. */
    override suspend fun assertInvariants() {}

    @Test
    fun `a consistent database passes both shapes and I5 ran for product and variant`(): Unit = runBlocking {
        sql("INSERT INTO `$product` VALUES (1, 0), (2, 5)")
        sql("INSERT INTO `$variant` VALUES (1, 0)")
        InvariantChecker.assertAll(pool)
        assertEquals(listOf("I5", "I5"), InvariantChecker.lastRun.ran)
        // the other invariants (I1 to I22) are complete now and need tables the two probe tables do not have: skipped, never failed
        assertTrue("I5" !in InvariantChecker.lastRun.skipped, "I5 was not skipped")
        InvariantChecker.assertAll(pool, true)
    }

    @Test
    fun `a negative product stock is reported as I5 with the row`(): Unit = runBlocking {
        sql("INSERT INTO `$product` VALUES (1, 3), (7, -1)")
        val e = org.junit.jupiter.api.Assertions.assertThrows(InvariantViolation::class.java) {
            runBlocking { InvariantChecker.assertAll(pool) }
        }
        assertEquals("I5", e.id)
        assertEquals(listOf("7"), e.rows)
    }

    @Test
    fun `a negative variant stock is reported as I5 in the legacy-aware shape too`(): Unit = runBlocking {
        sql("INSERT INTO `$variant` VALUES (4, -3)")
        val e = org.junit.jupiter.api.Assertions.assertThrows(InvariantViolation::class.java) {
            runBlocking { InvariantChecker.assertAll(pool, true) }
        }
        assertEquals("I5", e.id)
        assertEquals(listOf("4"), e.rows)
    }

    @Test
    fun `a check whose table does not exist yet is skipped, not failed`(): Unit = runBlocking {
        sql("DROP TABLE `$variant`")
        try {
            sql("INSERT INTO `$product` VALUES (1, 1)")
            InvariantChecker.assertAll(pool)
            assertEquals(listOf("I5"), InvariantChecker.lastRun.ran)
            assertTrue("I5" in InvariantChecker.lastRun.skipped, "the I5 shape that needs the dropped variant table is skipped, skipped=${InvariantChecker.lastRun.skipped}")
        } finally {
            pool.query("CREATE TABLE `$variant` (`id` BIGINT PRIMARY KEY, `stock` INT NOT NULL) ENGINE=InnoDB").execute().coAwait()
        }
    }
}
