package com.panomc.plugins.market.e2e.support

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The negative cases of `E2eInstanceGuard` (17 section 15): port 8088, testMode=false, database name `pano`, plus the accepted instance. */
class E2eInstanceGuardTest {
    @Test
    fun `accepts the isolated instance`() {
        E2eInstanceGuard.checkEnvironment("http://127.0.0.1:18400", "pano_market_e2e_slota", null)
        E2eInstanceGuard.checkEnvironment("http://127.0.0.1:18500", "pano_market_e2e_slotb", null)
        E2eInstanceGuard.checkTestMode(JsonObject().put("settings", JsonObject().put("testMode", true)))
    }

    @Test
    fun `aborts on the dev port 8088`() {
        for (url in listOf("http://127.0.0.1:8088", "http://localhost:8088/", "http://127.0.0.1:8088/api")) {
            assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkUrl(url) }
        }
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkEnvironment("http://127.0.0.1:8088", "pano_market_e2e", null) }
    }

    @Test
    fun `aborts on a missing or foreign url`() {
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkUrl(null) }
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkUrl("") }
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkUrl("https://panomc.com") }
    }

    @Test
    fun `aborts when testMode is false or missing after the bootstrap`() {
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkTestMode(JsonObject().put("settings", JsonObject().put("testMode", false))) }
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkTestMode(JsonObject().put("settings", JsonObject())) }
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkTestMode(null) }
    }

    @Test
    fun `aborts on the database pano or any non e2e name`() {
        for (name in listOf("pano", "pano_market_it_1_1", "pano_market_e2e;drop", "PANO_MARKET_E2E", "", null)) {
            assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkDatabase(name) }
        }
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkEnvironment("http://127.0.0.1:18400", "pano", null) }
    }

    @Test
    fun `reads the database name of the instance config and refuses pano there too`(@TempDir dir: File) {
        File(dir, "config.conf").writeText("server {\n    name = \"x\"\n}\ndatabase {\n    type = \"mariadb\"\n    name = \"pano\"\n    username = \"root\"\n}\n")
        assertEquals("pano", E2eInstanceGuard.databaseNameOf(dir.path))
        assertThrows(E2eGuardViolation::class.java) { E2eInstanceGuard.checkEnvironment("http://127.0.0.1:18400", "pano_market_e2e_slota", dir.path) }
        File(dir, "config.conf").writeText("database {\n    name = \"pano_market_e2e_slota\"\n}\n")
        E2eInstanceGuard.checkEnvironment("http://127.0.0.1:18400", "pano_market_e2e_slota", dir.path)
        assertNull(E2eInstanceGuard.databaseNameOf(File(dir, "missing").path))
    }
}
