package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.support.Race
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * SQL half of the Race self-test (17 section 15): 20 actors running `UPDATE ... SET n = n + 1` through
 * [Race.run] never lose an update, because the row lock serialises them.
 *
 * Self-contained until `MarketDbTestBase` (MK-011) exists: it opens its own pool on a throwaway database whose name
 * starts with `pano_market_it_` and drops it afterwards. When MK-011 lands this class should extend that base and
 * drop the connection code below.
 */
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RaceSelfIT {
    private val actors = 20
    private val database = "pano_market_it_race_" + UUID.randomUUID().toString().replace("-", "").take(12)
    private lateinit var admin: Pool
    private lateinit var pool: Pool

    private fun options(db: String?): MySQLConnectOptions {
        val address = System.getenv("PANO_IT_MARIADB")
        require(!address.isNullOrBlank()) { "RaceSelfIT needs PANO_IT_MARIADB=host:port" }
        val (host, port) = address.split(":").let { it[0] to (it.getOrNull(1)?.toInt() ?: 3306) }
        return MySQLConnectOptions()
            .setHost(host)
            .setPort(port)
            .setUser("root")
            .setPassword(System.getenv("PANO_IT_MARIADB_PASSWORD") ?: "")
            .also { if (db != null) it.setDatabase(db) }
    }

    @BeforeAll
    fun createDatabase() = runBlocking {
        require(database.startsWith("pano_market_it_")) { "refusing to touch database $database" }
        admin = MySQLBuilder.pool().with(PoolOptions().setMaxSize(1)).connectingTo(options(null)).build()
        admin.query("CREATE DATABASE `$database` CHARACTER SET utf8mb4").execute().coAwait()
        // One connection per actor so the statements really overlap on the server.
        pool = MySQLBuilder.pool().with(PoolOptions().setMaxSize(actors)).connectingTo(options(database)).build()
        Unit
    }

    @AfterAll
    fun dropDatabase() = runBlocking {
        runCatching { pool.close().coAwait() }
        runCatching { admin.query("DROP DATABASE IF EXISTS `$database`").execute().coAwait() }
        runCatching { admin.close().coAwait() }
        Unit
    }

    @Test
    fun `atomic SQL increments through the gate never lose an update`() = runBlocking {
        repeat(Race.rounds) { round ->
            val table = "race_counter_$round"
            pool.query("CREATE TABLE `$table` (id INT PRIMARY KEY, n INT NOT NULL) ENGINE=InnoDB").execute().coAwait()
            pool.query("INSERT INTO `$table` (id, n) VALUES (1, 0)").execute().coAwait()

            val results = Race.run(actors) {
                pool.query("UPDATE `$table` SET n = n + 1 WHERE id = 1").execute().coAwait().rowCount()
            }

            assertEquals(actors, results.size)
            val failures = results.filter { it.isFailure }.map { it.exceptionOrNull() }
            assertTrue(failures.isEmpty(), "round $round: failed actors: $failures")
            assertTrue(results.all { it.getOrThrow() == 1 }, "round $round: every UPDATE must touch the one row")
            val n = pool.query("SELECT n FROM `$table` WHERE id = 1").execute().coAwait().first().getInteger("n")
            assertEquals(actors, n, "round $round lost updates")
        }
    }
}
