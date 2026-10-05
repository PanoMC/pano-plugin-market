package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eTestBase
import io.vertx.core.json.JsonArray
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The server side of the market on a real instance (MK-103, 08 section 8.5, 04 section 8): `GET /api/panel/market/servers` runs the production wiring of
 * `McSyncService` (`mcSyncService(plugin)` built from the plugin's beans, `DeliveryService`, the DAOs, `PlatformMcServerLink` over the platform's `server` table and
 * `ServerManager`) and the `MarketPanelApi` gate, and the scheduler arms the `delivery` job with its server steps (an instance whose `MarketJobs.jobs` could not
 * build the service would not boot into READY).
 *
 * The instance has no accepted Minecraft server (creating one needs the connect flow of a real component, E2E-21 / MC-09), so the list is empty here; the
 * rows, the counts and the states are proven by `McSyncServiceIT` on the real tables.
 */
class McSyncE2E : E2eTestBase() {
    override val tag = "mcs"

    @Test
    fun `an admin reads the server list and a user without a market node and a visitor do not`() {
        val body = admin.get("/api/panel/market/servers").ok().obj()

        assertTrue(body.getValue("servers") is JsonArray, body.encode())

        for (server in body.getJsonArray("servers").map { it as io.vertx.core.json.JsonObject }) {
            assertTrue(
                server.fieldNames().containsAll(
                    setOf("id", "name", "type", "connected", "proxy", "mcComponentVersion", "requiredVersion", "marketState", "waitingDeliveries", "queuedDeliveries", "downloadUrl", "platform", "integrations", "settings")
                ),
                server.encode()
            )
        }

        val ordinary = buyer(canPay = false).client.get("/api/panel/market/servers")
        val guest = visitor().get("/api/panel/market/servers")

        assertTrue(ordinary.status in setOf(401, 403), "an ordinary user got ${ordinary.status}")
        assertTrue(guest.status in setOf(401, 403), "a visitor got ${guest.status}")
    }
}
