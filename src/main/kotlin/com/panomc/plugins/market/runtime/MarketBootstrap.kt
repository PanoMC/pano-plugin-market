package com.panomc.plugins.market.runtime

import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.SchemaVerifier
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The start-up order of 01 section 14.4, executed once per JVM when the database is available (from `onStart` when
 * setup is done, otherwise from `SetupEventHandler.onSetupFinished`):
 *
 * 1. `MarketRuntime` = `STARTING`;
 * 2. [initDatabase] (`pluginDatabaseManager.initialize(plugin)`: fresh install = `Dao.init`, otherwise the migration
 *    chain). A failure is logged and never skips the next steps;
 * 3. `MarketSchema.ensure` (all idempotent DDL) and 4. the data fixups;
 * 5. `SchemaVerifier.verify`: a failure makes the run `DEGRADED`, steps 6 to 8 still run;
 * 6. [secrets] (`secret.key`, re-encryption of plaintext secrets), 7. [seeds] (system accounts, carrier, zone);
 * 8. [armScheduler], then `READY` unless `DEGRADED`.
 *
 * No step ever throws out of [run]: each one is wrapped, logged and, when it is unexpected, recorded for `/health`.
 * Everything that touches the host is a constructor parameter, so the class runs against a throwaway database in
 * tests exactly as it runs in the plugin.
 */
class MarketBootstrap(
    private val prefix: () -> String,
    private val pool: suspend () -> Pool,
    private val initDatabase: suspend () -> Unit,
    private val secrets: suspend () -> Unit = {},
    private val seeds: suspend () -> Unit = {},
    private val armScheduler: suspend () -> Unit = {},
    private val tables: List<MarketSchema.Table> = MarketSchema.tables,
    private val fixups: () -> List<MarketSchema.Fixup> = { MarketSchema.fixups() },
    private val markerTable: String = MarketSchema.ONE_SHOT_MARKER_TABLE
) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var finished = false

    @Volatile
    private var degraded = false

    /** `true` once [run] reached its end (READY or DEGRADED) in this instance. */
    val hasRun: Boolean get() = finished

    /** The state [run] ended in; `STOPPED` before it finished. */
    val finalState: MarketRuntime.State
        get() = if (!finished) MarketRuntime.State.STOPPED else if (degraded) MarketRuntime.State.DEGRADED else MarketRuntime.State.READY

    /**
     * Runs the order above once and returns the state it ended in. A second call (also a concurrent one) returns the
     * current state without doing anything. When the database pool cannot be obtained the run ends `DEGRADED` and a
     * later call may try again.
     */
    suspend fun run(): MarketRuntime.State {
        if (!started.compareAndSet(false, true)) return MarketRuntime.state

        MarketRuntime.starting()
        val errors = ArrayList<String>()

        val client: Pool
        val p: String
        try {
            client = pool()
            p = prefix()
        } catch (e: Exception) {
            logger.error("Market bootstrap: database is not available: {}", e.message)
            errors += "database: ${e.message}"
            MarketRuntime.finish(null, errors, degraded = true)
            started.set(false)
            return MarketRuntime.state
        }

        try {
            initDatabase()
        } catch (e: Exception) {
            logger.error("Market bootstrap: database initialisation failed, continuing: {}", e.message)
            errors += "initialize: ${e.message}"
        }

        try {
            val report = MarketSchema.ensure(client, p, tables, fixups(), markerTable)
            errors += report.ddlErrors
            errors += report.fixupErrors
        } catch (e: Exception) {
            logger.error("Market bootstrap: schema ensure failed: {}", e.message)
            errors += "ensure: ${e.message}"
        }

        var verification: SchemaVerifier.Result? = null
        try {
            verification = SchemaVerifier.verify(client, p, tables, fixups(), markerTable)
            if (!verification.ok) {
                logger.error("Market schema verification failed, running in degraded mode: {}", verification.describe())
            }
        } catch (e: Exception) {
            logger.error("Market bootstrap: schema verification could not run: {}", e.message)
            errors += "verify: ${e.message}"
        }
        degraded = verification == null || !verification.ok

        step("secrets", errors, secrets)
        step("seeds", errors, seeds)
        step("scheduler", errors, armScheduler)

        finished = true
        MarketRuntime.finish(verification, errors, degraded)
        return MarketRuntime.state
    }

    /** After a stop -> start of the same plugin instance: the state of the one bootstrap, no step runs again. */
    fun resume() {
        if (finished) MarketRuntime.resume(degraded)
    }

    private suspend fun step(name: String, errors: MutableList<String>, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.error("Market bootstrap: step {} failed, continuing: {}", name, e.message, e)
            errors += "$name: ${e.message}"
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(MarketBootstrap::class.java)
    }
}
