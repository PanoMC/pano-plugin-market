package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.support.MarketMigrationTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * The scheme-version-2 fixtures load into an empty throwaway database (17 section 5.2, 01 section 14.1 rule 6) and
 * [MarketMigrationTestBase] restores them before every test, without truncate and without `ensure()`.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class MarketMigrationTestBaseIT : MarketMigrationTestBase() {
    private val tables = listOf(
        "market_category", "market_comparison", "market_coupon", "market_creator_code", "market_discount",
        "market_gift", "market_order", "market_order_item", "market_payment_method", "market_product"
    )

    private suspend fun tableNames() =
        sql("SELECT TABLE_NAME AS n FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME").map { it.getString("n") }

    @Test
    fun `a1 the database holds exactly the ten version 2 tables and nothing else`() = runBlocking<Unit> {
        assertEquals("pano_", MarketTables.prefixOverride)
        assertEquals(tables.map { "pano_$it" }.sorted(), tableNames())
    }

    @Test
    fun `a2 seed row counts match the specification`() = runBlocking<Unit> {
        val expected = mapOf(
            "market_category" to 3L, "market_product" to 6L, "market_discount" to 2L, "market_coupon" to 2L,
            "market_creator_code" to 1L, "market_gift" to 2L, "market_payment_method" to 2L, "market_order" to 3L,
            "market_order_item" to 5L, "market_comparison" to 0L
        )
        expected.forEach { (table, n) -> assertEquals(n, count(table), table) }
    }

    @Test
    fun `a3 the seed covers the cases the migration tests rely on`() = runBlocking<Unit> {
        assertEquals(1L, count("market_category", "`parentId` IS NOT NULL"), "one nested category")
        assertEquals(1L, count("market_product", "`stock` = 0"), "one sold out product")
        val actionTypes = sql("SELECT `actions` FROM `pano_market_product` WHERE `actions` IS NOT NULL").joinToString { it.getString("actions") }
        for (type in listOf("CREDIT", "PERMISSION", "COMMAND")) assertTrue(actionTypes.contains("\"type\":\"$type\""), "$type action present")
        assertEquals(1L, count("market_coupon", "`usedCount` = 4"))
        assertEquals(
            listOf("COMPLETED", "PENDING", "REFUNDED"),
            sql("SELECT `status` FROM `pano_market_order` ORDER BY `status`").map { it.getString("status") }
        )
        val secrets = sql("SELECT `settings` FROM `pano_market_payment_method` WHERE `settings` LIKE '%\"secret\"%'")
        assertEquals(1, secrets.size, "one payment method with a plaintext secret")
        assertFalse(secrets.single().getString("settings").startsWith("ENC"), "legacy secrets are not encrypted")
    }

    @Test
    fun `a4 order totals equal the sum of their items`() = runBlocking<Unit> {
        val mismatches = sql(
            "SELECT o.`id` FROM `pano_market_order` o WHERE o.`totalPrice` <> " +
                "(SELECT SUM(i.`unitPrice` * i.`quantity`) FROM `pano_market_order_item` i WHERE i.`orderId` = o.`id`)"
        )
        assertEquals(0, mismatches.size)
    }

    @Test
    fun `a5 a test that wrecks the data does not leak into the next one`() = runBlocking<Unit> {
        sql("DELETE FROM `pano_market_order_item`")
        sql("DROP TABLE `pano_market_gift`")
        sql("UPDATE `pano_market_coupon` SET `usedCount` = 99")
        sql("CREATE TABLE `pano_market_extra` (`id` INT)")
    }

    @Test
    fun `a6 every test starts from the frozen install again`() = runBlocking<Unit> {
        assertEquals(tables.map { "pano_$it" }.sorted(), tableNames(), "the stray table is gone, the dropped one is back")
        assertEquals(5L, count("market_order_item"))
        assertEquals(2L, count("market_gift"))
        assertEquals(1L, count("market_coupon", "`usedCount` = 4"))
        assertEquals(0L, count("market_coupon", "`usedCount` = 99"))
    }

    @Test
    fun `a7 no ensure ran in the set-up, so nothing newer than version 2 exists`() = runBlocking<Unit> {
        // A column the later schema adds (01 section 14.2) must be absent: the migration test runs the chain itself.
        val columns = sql(
            "SELECT COLUMN_NAME AS c FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pano_market_order'"
        ).map { it.getString("c") }
        assertFalse("publicId" in columns || "buyerKey" in columns || "subtotal" in columns, "columns: $columns")
        assertTrue("exchangeRate" in columns)
        // And the fixtures survive a second load on top of a populated database (dropAllTables first).
        MarketTestDb.dropAllTables(pool)
        assertEquals(emptyList<String>(), tableNames())
        pool.query("CREATE VIEW `pano_market_leftover_view` AS SELECT 1 AS one").execute().coAwait()
        MarketTestDb.dropAllTables(pool)
        assertEquals(emptyList<String>(), tableNames(), "views are dropped too")
    }
}
