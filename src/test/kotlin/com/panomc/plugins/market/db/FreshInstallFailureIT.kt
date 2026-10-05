package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketBundleItemDaoImpl
import com.panomc.plugins.market.db.impl.MarketCategoryDaoImpl
import com.panomc.plugins.market.db.impl.MarketComparisonDaoImpl
import com.panomc.plugins.market.db.impl.MarketCouponDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreatorEarningDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreatorPayoutDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreatorCodeDaoImpl
import com.panomc.plugins.market.db.impl.MarketCurrencyRateDaoImpl
import com.panomc.plugins.market.db.impl.MarketDiscountDaoImpl
import com.panomc.plugins.market.db.impl.MarketGiftDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderItemDaoImpl
import com.panomc.plugins.market.db.impl.MarketPaymentMethodDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductFieldDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductPriceDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductProviderMetaDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductVariantDaoImpl
import com.panomc.plugins.market.db.impl.MarketRedemptionDaoImpl
import com.panomc.plugins.market.runtime.MarketBootstrap
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.support.MarketDbTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fresh install where one `CREATE` fails (17 section 11.3 `FreshInstallFailureIT`, 01 section 14.1 rule 6): no
 * exception leaves `Dao.init`, `MarketBootstrap` ends `DEGRADED` while everything else still runs, and the next
 * start with the obstacle removed reaches `READY`. The obstacle is a VIEW named like the table: the table is then
 * never created and `SchemaVerifier` names it.
 */
class FreshInstallFailureIT : MarketDbTestBase() {
    private val view = "pano_market_coupon"

    @BeforeEach
    fun freshRuntime() {
        MarketRuntime.reset()
    }

    @AfterEach
    fun resetRuntime(): Unit = runBlocking {
        MarketRuntime.reset()
        MarketTestDb.dropAllTables(pool)
        MarketSchema.ensure(pool, prefix)
    }

    private suspend fun daoInits(client: SqlClient) {
        MarketCategoryDaoImpl().init(client)
        MarketComparisonDaoImpl().init(client)
        MarketCouponDaoImpl().init(client)
        MarketCreatorCodeDaoImpl().init(client)
        MarketDiscountDaoImpl().init(client)
        MarketGiftDaoImpl().init(client)
        MarketOrderDaoImpl().init(client)
        MarketOrderItemDaoImpl().init(client)
        MarketPaymentMethodDaoImpl().init(client)
        MarketProductDaoImpl().init(client)
        MarketProductVariantDaoImpl().init(client)
        MarketProductPriceDaoImpl().init(client)
        MarketProductFieldDaoImpl().init(client)
        MarketBundleItemDaoImpl().init(client)
        MarketProductProviderMetaDaoImpl().init(client)
        MarketCurrencyRateDaoImpl().init(client)
        MarketRedemptionDaoImpl().init(client)
        MarketCreatorEarningDaoImpl().init(client)
        MarketCreatorPayoutDaoImpl().init(client)
    }

    private suspend fun obstacle() {
        MarketTestDb.dropAllTables(pool)
        sql("CREATE VIEW `$view` AS SELECT 1 AS id")
    }

    private class Calls {
        val init = AtomicInteger()
        val secrets = AtomicInteger()
        val seeds = AtomicInteger()
        val scheduler = AtomicInteger()
    }

    private fun bootstrap(calls: Calls, initDatabase: suspend () -> Unit = { daoInits(pool) }) = MarketBootstrap(
        prefix = { prefix },
        pool = { pool },
        initDatabase = { calls.init.incrementAndGet(); initDatabase() },
        secrets = { calls.secrets.incrementAndGet() },
        seeds = { calls.seeds.incrementAndGet() },
        armScheduler = { calls.scheduler.incrementAndGet() }
    )

    @Test
    fun `a failing CREATE degrades the plugin, the platform part keeps running, and the next start is READY`(): Unit = runBlocking {
        obstacle()
        val calls = Calls()

        // no exception leaves Dao.init
        daoInits(pool)

        val first = bootstrap(calls)
        assertEquals(MarketRuntime.State.STOPPED, MarketRuntime.state)
        assertEquals(MarketRuntime.State.DEGRADED, first.run())
        assertEquals(MarketRuntime.State.DEGRADED, MarketRuntime.state)
        assertEquals(MarketRuntime.State.DEGRADED, first.finalState)
        assertFalse(MarketRuntime.isReady)

        // steps 6 to 8 still ran and the other twenty-one tables exist
        assertEquals(1, calls.secrets.get())
        assertEquals(1, calls.seeds.get())
        assertEquals(1, calls.scheduler.get())
        assertEquals(21, MarketTestDb.marketTables(pool).size)

        val health = MarketRuntime.health()
        assertEquals(MarketRuntime.State.DEGRADED, health.state)
        assertTrue(health.problems.any { it.contains("MISSING_TABLE") && it.contains(view) }, health.problems.toString())

        // the obstacle is removed and the plugin starts again (a new JVM: a new bootstrap)
        MarketRuntime.stopped()
        sql("DROP VIEW `$view`")
        val second = bootstrap(Calls())
        assertEquals(MarketRuntime.State.READY, second.run())
        assertEquals(MarketRuntime.State.READY, MarketRuntime.state)
        assertTrue(MarketRuntime.isReady)
        assertTrue(MarketRuntime.health().problems.isEmpty())
        assertEquals(22, MarketTestDb.marketTables(pool).size)
        assertTrue(SchemaVerifier.verify(pool, prefix).ok)
    }

    @Test
    fun `a healthy fresh install ends READY and runs every step once`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        val calls = Calls()
        val runner = bootstrap(calls)
        assertEquals(MarketRuntime.State.READY, runner.run())
        assertEquals(listOf(1, 1, 1, 1), listOf(calls.init, calls.secrets, calls.seeds, calls.scheduler).map { it.get() })
        assertTrue(runner.hasRun)

        // exactly once: a second run does nothing
        assertEquals(MarketRuntime.State.READY, runner.run())
        assertEquals(1, calls.init.get())
        assertEquals(1, calls.scheduler.get())
    }

    @Test
    fun `stop and resume of one instance keep the verdict and run no step again`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        val calls = Calls()
        val runner = bootstrap(calls)
        runner.run()
        MarketRuntime.stopped()
        assertEquals(MarketRuntime.State.STOPPED, MarketRuntime.state)
        runner.resume()
        assertEquals(MarketRuntime.State.READY, MarketRuntime.state)
        assertEquals(1, calls.init.get())

        obstacle()
        val degraded = bootstrap(Calls())
        degraded.run()
        MarketRuntime.stopped()
        degraded.resume()
        assertEquals(MarketRuntime.State.DEGRADED, MarketRuntime.state)
    }

    @Test
    fun `a failing initialize or step is recorded and the order continues`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        val calls = Calls()
        val runner = MarketBootstrap(
            prefix = { prefix },
            pool = { pool },
            initDatabase = { calls.init.incrementAndGet(); error("initialize dies") },
            secrets = { error("secrets die") },
            seeds = { calls.seeds.incrementAndGet() },
            armScheduler = { calls.scheduler.incrementAndGet() }
        )
        // ensure() creates the schema although initialize() failed: the plugin is usable
        assertEquals(MarketRuntime.State.READY, runner.run())
        assertEquals(22, MarketTestDb.marketTables(pool).size)
        assertEquals(1, calls.seeds.get())
        assertEquals(1, calls.scheduler.get())
        val errors = MarketRuntime.health().bootstrapErrors
        assertTrue(errors.any { it.startsWith("initialize") } && errors.any { it.startsWith("secrets") }, errors.toString())
    }

    @Test
    fun `no database ends DEGRADED and a later call may try again`(): Unit = runBlocking {
        MarketTestDb.dropAllTables(pool)
        var available = false
        val calls = Calls()
        val runner = MarketBootstrap(
            prefix = { prefix },
            pool = { if (available) pool else error("connection refused") },
            initDatabase = { calls.init.incrementAndGet() }
        )
        assertEquals(MarketRuntime.State.DEGRADED, runner.run())
        assertFalse(runner.hasRun)
        assertEquals(0, calls.init.get())
        assertTrue(MarketRuntime.health().bootstrapErrors.single().startsWith("database"))

        available = true
        assertEquals(MarketRuntime.State.READY, runner.run())
        assertEquals(1, calls.init.get())
    }

    @Test
    fun `a failing one-shot fixup ends DEGRADED and the next start with it succeeding is READY`(): Unit = runBlocking {
        val markerName = "market_zz_marker"
        val physicalMarker = "${prefix}$markerName"
        try {
            MarketTestDb.dropAllTables(pool)
            sql("CREATE TABLE `$physicalMarker` (`name` VARCHAR(64) NOT NULL, `value` BIGINT NOT NULL, PRIMARY KEY (`name`)) ENGINE=InnoDB")
            var fail = true
            val backfill = MarketSchema.Fixup("backfill", oneShot = true) { client, _ ->
                if (fail) error("counter backfill dies")
            }
            fun runner() = MarketBootstrap(
                prefix = { prefix },
                pool = { pool },
                initDatabase = { daoInits(pool) },
                fixups = { listOf(backfill) },
                markerTable = markerName
            )

            val first = runner()
            assertEquals(MarketRuntime.State.DEGRADED, first.run())
            assertFalse(MarketRuntime.isReady)
            val problems = MarketRuntime.health().problems
            assertTrue(problems.any { it.contains("backfill") }, problems.toString())

            MarketRuntime.stopped()
            fail = false
            assertEquals(MarketRuntime.State.READY, runner().run())
            assertTrue(MarketRuntime.isReady)
            assertTrue(MarketRuntime.health().problems.isEmpty())
        } finally {
            runCatching { sql("DROP TABLE IF EXISTS `$physicalMarker`") }
        }
    }

    @Test
    fun `a closed pool still ends DEGRADED and never throws`(): Unit = runBlocking {
        val closed = MarketTestDb.pool(databaseName, 1)
        closed.close().coAwait()
        val runner = MarketBootstrap(prefix = { prefix }, pool = { closed }, initDatabase = { daoInits(closed) })
        assertEquals(MarketRuntime.State.DEGRADED, runner.run())
        assertTrue(runner.hasRun)
    }
}
