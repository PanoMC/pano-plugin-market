package com.panomc.plugins.market.routes.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/panel/market/servers` (04 section 8, any market node): every Minecraft server the owner accepted with the market's view of its component:
 * `marketState` (`READY | OFFLINE | COMPONENT_MISSING | VERSION_MISMATCH`; `REMOVED` never appears, a removed server is not listed), the reported and the
 * required version, the deliveries waiting for it (`PENDING`, `WAITING_SERVER`, `SENT`) and those queued on the game server (`QUEUED`), the download link,
 * the platform, the integrations the component reported and the per-server settings override (or `null`). The panel warns for every server that is not
 * `READY` while `waitingDeliveries > 0` (08 section 8.5).
 */
@Endpoint
class PanelGetServersAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/servers", RouteType.GET))

    /** Empty = a holder of any market node (04 section 9). */
    override val nodes: Set<MarketNode> = emptySet()

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result = Successful(mapOf("items" to mcSyncService(plugin).servers().map { it.toJson() }))
}
