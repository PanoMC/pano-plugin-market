package com.panomc.plugins.market.e2e.support

import io.vertx.core.json.JsonObject
import java.io.File
import java.net.URI

/** Raised by [E2eInstanceGuard]: the run is aborted before anything is sent to a real installation (17 section 15). */
class E2eGuardViolation(message: String) : IllegalStateException(message)

/**
 * `E2eInstanceGuard` (17 section 15): the end-to-end tests create stores, users and orders, so they refuse to run against anything that
 * could be a real installation. Three refusals, all pure so the negative cases are unit tested (`E2eInstanceGuardTest`) and also visible
 * in a real run (a wrong environment aborts the first test class):
 * - the instance URL is the dev backend (port 8088) or not a loopback address,
 * - the database the instance reads from (`MARKET_E2E_DB`, cross-checked with the `database.name` of `instance/config.conf`) is not a
 *   `pano_market_e2e*` database (in particular `pano`),
 * - after the bootstrap `GET /api/market/store` still reports `testMode = false`.
 */
object E2eInstanceGuard {
    const val DEV_PORT = 8088
    private val DB_NAME = Regex("^pano_market_e2e(_[a-z][a-z0-9]{0,19})?$")
    private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    fun checkUrl(url: String?) {
        if (url.isNullOrBlank()) throw E2eGuardViolation("MARKET_E2E_URL is not set (start the instance with scripts/e2e-instance.sh)")
        val uri = try {
            URI.create(url)
        } catch (e: IllegalArgumentException) {
            throw E2eGuardViolation("MARKET_E2E_URL is not a URL")
        }
        val port = if (uri.port == -1) (if (uri.scheme == "https") 443 else 80) else uri.port
        if (port == DEV_PORT) throw E2eGuardViolation("refusing to run: MARKET_E2E_URL points at port $DEV_PORT (the dev backend)")
        if (uri.host == null || uri.host !in LOOPBACK) throw E2eGuardViolation("refusing to run: MARKET_E2E_URL is not a loopback address")
    }

    fun checkDatabase(name: String?) {
        if (name.isNullOrBlank()) throw E2eGuardViolation("MARKET_E2E_DB is not set")
        if (!DB_NAME.matches(name)) throw E2eGuardViolation("refusing to run: database '$name' is not a pano_market_e2e database")
    }

    /** The `name` under `database {` of the instance's `config.conf`, `null` when the file or the key is not there. */
    fun databaseNameOf(instanceDir: String?): String? {
        val file = instanceDir?.let { File(it, "config.conf") }?.takeIf { it.isFile } ?: return null
        var inDatabase = false
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.startsWith("#")) continue
            if (line.startsWith("database") && line.endsWith("{")) inDatabase = true
            else if (inDatabase && line == "}") inDatabase = false
            else if (inDatabase && line.startsWith("name")) return Regex("^name\\s*=\\s*\"(.*)\"").find(line)?.groupValues?.get(1)
        }
        return null
    }

    /** [store] is the body of `GET /api/market/store`; its `settings.testMode` must be `true`. */
    fun checkTestMode(store: JsonObject?) {
        val testMode = store?.getJsonObject("settings")?.getBoolean("testMode")
        if (testMode != true) throw E2eGuardViolation("refusing to run: the instance reports testMode=$testMode after the bootstrap")
    }

    /** Everything that can be judged before the first request. */
    fun checkEnvironment(url: String?, db: String?, instanceDir: String?) {
        checkUrl(url)
        checkDatabase(db)
        databaseNameOf(instanceDir)?.let { checkDatabase(it) }
    }
}
