package com.panomc.plugins.market.e2e.support

import java.io.File

/**
 * What `scripts/e2e-instance.sh start` exported (`MARKET_E2E_URL`, `_DIR`, `_GATEWAY_PORT`, `_DB`) plus the admin password it wrote to
 * `instance/admin.env` (mode 600). The password is read when it is needed and never printed, logged or copied.
 */
class E2eEnv(val url: String, val dir: String?, val gatewayPort: Int, val database: String) {
    val adminUser: String get() = adminEnv()["SMOKE_ADMIN_USER"] ?: "smokeadmin"

    fun adminPassword(): String = adminEnv()["SMOKE_ADMIN_PASSWORD"] ?: throw IllegalStateException("no admin password under MARKET_E2E_DIR/admin.env")

    private fun adminEnv(): Map<String, String> {
        val file = dir?.let { File(it, "admin.env") }?.takeIf { it.isFile } ?: return emptyMap()
        return file.readLines().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()
    }

    companion object {
        /** Reads the environment and runs every guard that can be judged before the first request (17 section 15). */
        fun load(env: Map<String, String?> = System.getenv()): E2eEnv {
            val url = env["MARKET_E2E_URL"]
            val dir = env["MARKET_E2E_DIR"]
            val db = env["MARKET_E2E_DB"] ?: E2eInstanceGuard.databaseNameOf(dir)

            E2eInstanceGuard.checkEnvironment(url, db, dir)

            val port = env["MARKET_E2E_GATEWAY_PORT"]?.toIntOrNull() ?: 18189

            return E2eEnv(url!!.trimEnd('/'), dir, port, db!!)
        }
    }
}
