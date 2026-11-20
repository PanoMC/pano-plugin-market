package com.panomc.plugins.market.routes.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.McComponentDownload
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/panel/market/mc-component/download` (04 section 8, any market node; 19 section 2.3): streams the Minecraft plugin jar of `?platform=` (`spigot`,
 * `bungee`, `velocity`, `fabric`, or a server type such as `PAPER`; none = `spigot`) that this market build bundles, as
 * `pano-plugin-market-<platform>-<version>.jar`: built with this market, so it is by construction the version `MARKET_SYNC` requires. 400 for a platform no
 * Market jar runs on, 404 `NOT_FOUND` when this build did not bundle it (the Fabric jar of a build without `-Pfabric`). No path ever comes from the request.
 */
@Endpoint
class PanelGetMcComponentDownloadAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/mc-component/download", RouteType.GET))

    /** Empty = a holder of any market node (04 section 9). */
    override val nodes: Set<MarketNode> = emptySet()

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).queryParameter(optionalParam("platform", stringSchema())).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result? {
        val platform = context.request().getParam("platform")?.trim()?.takeIf { it.isNotEmpty() }

        if (platform != null && McComponentDownload.jarOf(platform) == null) throw BadRequest()

        val downloads = mcComponentDownload(plugin)
        val download = downloads.resolve(platform) ?: throw NotFound()

        downloads.send(context.response(), download)

        return null
    }
}
