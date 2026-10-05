package com.panomc.plugins.market.support

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.db.tx.MarketDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Base of every tier-T2 class (17 section 5.1). One throwaway database `pano_market_it_<pid>_<counter>` per test
 * class: created in `@BeforeAll`, dropped in `@AfterAll`; leftovers of crashed runs older than one hour are dropped
 * first. `MarketTables.prefixOverride` is `"pano_"` while the class runs. The schema is installed once per class
 * ([installSchema]); every test then starts from the rows the installer seeded ([resetState]), and the global
 * invariants are checked after every test ([assertInvariants]).
 *
 * The `db` tag is inherited, so a subclass can never be picked up by the plain `test` task. The class is
 * `PER_CLASS`: test methods may share state held in properties, but never data in the database.
 *
 * `MarketSchema.ensure` (MK-020) and `InvariantChecker.assertAll` (MK-012 / MK-032) do not exist yet when this base
 * is written; they are called through [LateBound] as soon as their classes appear, with no further edit here.
 */
@Tag("db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MarketDbTestBase {
    protected val prefix: String = MarketTestDb.TABLE_PREFIX

    /** Pool of the class's database (`PoolOptions.maxSize = poolSize`). */
    protected lateinit var pool: Pool
        private set

    protected lateinit var databaseName: String
        private set

    private var admin: Pool? = null
    private var baseline: Map<String, MarketTestDb.TableSnapshot> = emptyMap()

    protected open val poolSize: Int = 16

    @BeforeAll
    fun marketDbBeforeAll(): Unit = runBlocking {
        MarketTestDb.ensureGson()
        val adminPool = MarketTestDb.adminPool().also { admin = it }
        MarketTestDb.dropStaleDatabases(adminPool)
        databaseName = MarketTestDb.newDatabaseName()
        MarketTestDb.createDatabase(adminPool, databaseName)
        pool = MarketTestDb.pool(databaseName, poolSize)
        MarketTables.prefixOverride = prefix
        installSchema()
        baseline = MarketTestDb.snapshotMarketTables(pool)
    }

    @AfterAll
    fun marketDbAfterAll(): Unit = runBlocking {
        MarketTables.prefixOverride = null
        runCatching { if (::pool.isInitialized) pool.close().coAwait() }
        val adminPool = admin
        if (adminPool != null) {
            if (::databaseName.isInitialized) runCatching { MarketTestDb.dropDatabase(adminPool, databaseName) }
            runCatching { adminPool.close().coAwait() }
        }
    }

    @BeforeEach
    fun marketDbBeforeEach(): Unit = runBlocking { resetState() }

    @AfterEach
    fun marketDbAfterEach(): Unit = runBlocking { assertInvariants() }

    /** Creates the market schema in the empty database: `MarketSchema.ensure(pool, "pano_")`, the code the plugin runs. */
    protected open suspend fun installSchema() {
        LateBound.call("com.panomc.plugins.market.db.MarketSchema", "ensure", pool, prefix)
    }

    /** Back to a fresh install: every `pano_market_*` table truncated and the seeded rows restored. */
    protected open suspend fun resetState() {
        MarketTestDb.resetMarketTables(pool, baseline)
    }

    /** `InvariantChecker.assertAll(pool)` once that class exists (17 section 7). */
    protected open suspend fun assertInvariants() {
        LateBound.call("com.panomc.plugins.market.support.InvariantChecker", "assertAll", pool)
    }

    /** A real [MarketDb] on this class's pool. */
    protected fun marketDb(lockWaitSeconds: Int = MarketDb.LOCK_WAIT_SECONDS, clock: Clock = SystemClock): MarketDb =
        MarketDb({ pool }, clock, lockWaitSeconds)

    protected suspend fun sql(statement: String, vararg args: Any?): List<Row> =
        MarketTestDb.sql(pool, statement, *args)

    /** Rows of `pano_<table>` (table without the prefix) matching [where] (without the `WHERE` keyword). */
    protected suspend fun count(table: String, where: String? = null, vararg args: Any?): Long =
        MarketTestDb.count(pool, table, where, *args)
}
