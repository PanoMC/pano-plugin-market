package com.panomc.plugins.market.support.selftest

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.MarketDbTestBase
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
 * Self-test of [MarketDbTestBase] (17 section 5.1, 15): the throwaway database, the prefix override, the per-test
 * reset to the seeded rows, the platform stubs, the cleanup of stale databases.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class MarketDbTestBaseIT : MarketDbTestBase() {
    private val seeded = "pano_market_zz_seeded"
    private val empty = "pano_market_zz_empty"

    override suspend fun installSchema() {
        pool.query("CREATE TABLE `$seeded` (`id` INT PRIMARY KEY, `v` VARCHAR(20) NOT NULL, `j` MEDIUMTEXT) ENGINE=InnoDB").execute().coAwait()
        pool.query("CREATE TABLE `$empty` (`id` INT PRIMARY KEY AUTO_INCREMENT, `v` VARCHAR(20)) ENGINE=InnoDB").execute().coAwait()
        pool.query("CREATE TABLE `zz_not_market` (`id` INT PRIMARY KEY, `v` VARCHAR(20)) ENGINE=InnoDB").execute().coAwait()
        // What a schema installer seeds (the five system credit accounts in the real schema): must survive every reset.
        pool.query("INSERT INTO `$seeded` VALUES (1, 'system-a', '{\"k\":1}'), (2, 'system-b', NULL)").execute().coAwait()
        pool.query("INSERT INTO `zz_not_market` VALUES (1, 'platform-owned')").execute().coAwait()
    }

    private suspend fun seededRows() = sql("SELECT `id`, `v`, `j` FROM `$seeded` ORDER BY `id`").map {
        Triple(it.getInteger("id"), it.getString("v"), it.getString("j"))
    }

    @Test
    fun `a1 setup gives a throwaway database, the prefix override and a ready gson`() = runBlocking<Unit> {
        assertTrue(MarketTestDb.isThrowawayName(databaseName), databaseName)
        assertEquals("pano_", MarketTables.prefixOverride)
        assertEquals(databaseName, sql("SELECT DATABASE() AS d").first().getString("d"))
        assertEquals("utf8mb4_unicode_ci", sql("SELECT @@collation_database AS c").first().getString("c"))
        assertTrue(DBEntity.gson.toJson(mapOf("a" to 1)).contains("\"a\""))
        assertEquals(listOf(empty, seeded), MarketTestDb.marketTables(pool))
    }

    @Test
    fun `a2 mutate every kind of row`() = runBlocking<Unit> {
        sql("INSERT INTO `$seeded` VALUES (3, 'test-row', NULL)")
        sql("UPDATE `$seeded` SET `v` = 'changed' WHERE `id` = 1")
        sql("DELETE FROM `$seeded` WHERE `id` = 2")
        sql("INSERT INTO `$empty` (`v`) VALUES ('x'), ('y')")
        sql("UPDATE `zz_not_market` SET `v` = 'touched by the test'")
        assertEquals(listOf(1 to "changed", 3 to "test-row"), seededRows().map { it.first to it.second })
    }

    @Test
    fun `a3 each test starts from the seeded rows of a fresh install`() = runBlocking<Unit> {
        assertEquals(listOf(Triple(1, "system-a", "{\"k\":1}"), Triple(2, "system-b", null)), seededRows())
        assertEquals(0L, sql("SELECT COUNT(*) AS c FROM `$empty`").first().getLong("c"))
        // AUTO_INCREMENT starts again at 1 after the reset (TRUNCATE), as on a fresh install.
        sql("INSERT INTO `$empty` (`v`) VALUES ('first')")
        assertEquals(1, sql("SELECT `id` FROM `$empty`").first().getInteger("id"))
    }

    @Test
    fun `a4 tables outside the market prefix are left alone by the reset`() = runBlocking<Unit> {
        // Depends on a2 (the class runs in method-name order): a2 changed it; the reset must not have restored it (it is not a pano_market_ table).
        assertEquals("touched by the test", sql("SELECT `v` FROM `zz_not_market`").first().getString("v"))
    }

    @Test
    fun `b1 count helper prefixes the table, sql helper binds arguments`() = runBlocking<Unit> {
        assertEquals(2L, count("market_zz_seeded"))
        assertEquals(1L, count("market_zz_seeded", "`v` = ?", "system-a"))
        assertEquals(0L, count("market_zz_seeded", "`v` = ?", "nothing"))
        assertEquals("system-b", sql("SELECT `v` FROM `$seeded` WHERE `id` = ?", 2).first().getString("v"))
    }

    @Test
    fun `b2 platform stubs create the minimal user and server tables`() = runBlocking<Unit> {
        MarketTestDb.createPlatformStubs(pool)
        MarketTestDb.createPlatformStubs(pool) // idempotent
        sql("INSERT INTO `pano_user` (`username`, `email`, `emailVerified`, `registerDate`) VALUES ('Steve', 'steve@example.com', 1, 1700000000000)")
        sql("INSERT INTO `pano_server` (`name`) VALUES ('lobby')")
        assertEquals(1L, MarketTestDb.count(pool, "user"))
        assertEquals("lobby", sql("SELECT `name` FROM `pano_server`").first().getString("name"))
        // They are not market tables: not truncated, not part of the baseline.
        assertFalse(MarketTestDb.marketTables(pool).contains("pano_user"))
    }

    @Test
    fun `b3 the real MarketDb works on the class pool`() = runBlocking<Unit> {
        val v = marketDb().tx { conn ->
            conn.query("UPDATE `$seeded` SET `v` = 'tx' WHERE `id` = 1").execute().coAwait()
            conn.query("SELECT `v` FROM `$seeded` WHERE `id` = 1").execute().coAwait().first().getString("v")
        }
        assertEquals("tx", v)
    }

    @Test
    fun `c1 stale databases are dropped, fresh ones and foreign names are kept`() = runBlocking<Unit> {
        val admin = MarketTestDb.adminPool()
        val pid = ProcessHandle.current().pid()
        val stale = "pano_market_it_selftest_stale_$pid"
        val fresh = "pano_market_it_selftest_fresh_$pid"
        val foreign = "zz_pano_market_decoy_$pid" // outside the prefix: the listing must never return it
        val now = 1_760_000_000_000L
        try {
            MarketTestDb.createDatabase(admin, stale, FakeClock(now - 2 * MarketTestDb.STALE_AFTER_MS))
            MarketTestDb.createDatabase(admin, fresh, FakeClock(now - 1_000))
            admin.query("CREATE DATABASE `$foreign` COMMENT 'market-it:1'").execute().coAwait()

            val dropped = MarketTestDb.dropStaleDatabases(admin, FakeClock(now))
            assertTrue(stale in dropped, "stale one dropped: $dropped")
            assertFalse(fresh in dropped)
            assertFalse(foreign in dropped)
            assertFalse(databaseName in dropped, "the running class's own database is never stale")

            val remaining = admin.query("SHOW DATABASES").execute().coAwait().map { it.getString(0) }
            assertFalse(stale in remaining)
            assertTrue(fresh in remaining)
            assertTrue(foreign in remaining)
            assertTrue(databaseName in remaining)
        } finally {
            runCatching { MarketTestDb.dropDatabase(admin, stale) }
            runCatching { MarketTestDb.dropDatabase(admin, fresh) }
            runCatching { admin.query("DROP DATABASE IF EXISTS `$foreign`").execute().coAwait() }
            admin.close().coAwait()
        }
    }
}
