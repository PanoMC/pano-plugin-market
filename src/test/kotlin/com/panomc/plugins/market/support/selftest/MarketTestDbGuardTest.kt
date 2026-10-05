package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

/**
 * Safety guard of the database test infrastructure (17 section 15 `MarketTestDbGuardTest`): nothing but a database
 * whose name starts with `pano_market_it_` may be created, dropped, truncated or cleaned up. No database needed.
 */
class MarketTestDbGuardTest {
    /** A [Pool] that fails every call: proves the guard fires before anything is sent to the server. */
    private val untouchablePool: Pool = Proxy.newProxyInstance(
        Pool::class.java.classLoader, arrayOf(Pool::class.java)
    ) { _, method, _ -> throw AssertionError("the guard let ${method.name} through to the pool") } as Pool

    @Test
    fun `accepts only names with the throwaway prefix`() {
        assertTrue(MarketTestDb.isThrowawayName("pano_market_it_12345_1"))
        assertTrue(MarketTestDb.isThrowawayName("pano_market_it_race_abc"))
        for (bad in listOf(
            "pano", "mysql", "information_schema", "pano_market", "pano_market_it", "pano_market_it_", "pano_bktest_a",
            "PANO_MARKET_IT_1", "xpano_market_it_1", "pano_market_it_1; DROP DATABASE pano", "pano_market_it_1`",
            "pano_market_it_1 ", "pano_market_it_a.b", "pano_market_it_" + "x".repeat(41), ""
        )) {
            assertFalse(MarketTestDb.isThrowawayName(bad), "'$bad' must be refused")
            val e = assertThrows<IllegalArgumentException> { MarketTestDb.requireThrowawayName(bad) }
            assertTrue(e.message!!.contains("pano_market_it_"), "message names the required prefix: ${e.message}")
        }
        assertEquals("pano_market_it_9_9", MarketTestDb.requireThrowawayName("pano_market_it_9_9"))
    }

    @Test
    fun `generated names pass the guard and never repeat`() {
        val names = List(50) { MarketTestDb.newDatabaseName() }
        assertEquals(50, names.toSet().size)
        names.forEach { assertTrue(MarketTestDb.isThrowawayName(it), it) }
        assertTrue(names.all { it.startsWith("pano_market_it_${ProcessHandle.current().pid()}_") })
    }

    @Test
    fun `create and drop refuse a foreign database before touching the pool`(): Unit = runBlocking {
        assertThrows<IllegalArgumentException> { runBlocking { MarketTestDb.createDatabase(untouchablePool, "pano") } }
        assertThrows<IllegalArgumentException> { runBlocking { MarketTestDb.dropDatabase(untouchablePool, "pano") } }
        assertThrows<IllegalArgumentException> { runBlocking { MarketTestDb.dropDatabase(untouchablePool, "mysql") } }
    }

    @Test
    fun `a pool on a foreign database is refused`() {
        val connection = MarketTestDb.Connection("127.0.0.1", 1, "root", "never-used")
        assertThrows<IllegalArgumentException> { MarketTestDb.pool("pano", connection = connection) }
    }

    @Test
    fun `cleanup candidates are limited to the prefix and to a known age over the threshold`() {
        val now = 10_000_000_000L
        val hour = MarketTestDb.STALE_AFTER_MS
        val candidates = listOf(
            MarketTestDb.Candidate("pano", now - 5 * hour),                         // the real database: never
            MarketTestDb.Candidate("mysql", now - 5 * hour),                        // other: never
            MarketTestDb.Candidate("pano_bktest_a", now - 5 * hour),                // sibling prefix: never
            MarketTestDb.Candidate("pano_market_it_old_1", now - 2 * hour),         // stale: yes
            MarketTestDb.Candidate("pano_market_it_edge_1", now - hour),            // exactly one hour: yes
            MarketTestDb.Candidate("pano_market_it_fresh_1", now - hour + 1),       // younger: no
            MarketTestDb.Candidate("pano_market_it_unknown_1", null),               // age unknown: kept
            MarketTestDb.Candidate("pano_market_it_x`; DROP DATABASE pano", now - 9 * hour) // hostile name: never
        )
        assertEquals(
            listOf("pano_market_it_old_1", "pano_market_it_edge_1"),
            MarketTestDb.staleDatabases(candidates, now)
        )
        assertEquals(emptyList<String>(), MarketTestDb.staleDatabases(emptyList(), now))
    }

    @Test
    fun `connection settings come from the environment and never leak the password`() {
        val c = MarketTestDb.connectionFromEnv(mapOf("PANO_IT_MARIADB" to "db.local:3307", "PANO_IT_MARIADB_PASSWORD" to "s3cr3t-value"))
        assertEquals("db.local", c.host)
        assertEquals(3307, c.port)
        assertEquals("root", c.user)
        assertFalse(c.toString().contains("s3cr3t-value"))
        assertEquals(3306, MarketTestDb.connectionFromEnv(mapOf("PANO_IT_MARIADB" to "db.local")).port)

        for (env in listOf(
            emptyMap<String, String?>(), mapOf("PANO_IT_MARIADB" to ""), mapOf("PANO_IT_MARIADB" to ":3306"),
            mapOf("PANO_IT_MARIADB" to "h:notaport"), mapOf("PANO_IT_MARIADB" to "h:70000"), mapOf("PANO_IT_MARIADB" to "a:1:2")
        )) {
            val e = assertThrows<IllegalArgumentException> {
                MarketTestDb.connectionFromEnv(env + ("PANO_IT_MARIADB_PASSWORD" to "s3cr3t-value"))
            }
            assertFalse(e.message!!.contains("s3cr3t-value"))
        }
        assertNotEquals("", c.user)
    }
}
