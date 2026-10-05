package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.panel.discount.Promotion
import com.panomc.plugins.market.routes.panel.discount.PromotionAdminRoute
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `GET /api/panel/market/creator-codes` (`P:DISC`): the live creator codes with their counters; query `page`, `pageSize`, `search`, `status`. */
@Endpoint
class PanelGetCreatorCodesAPI(plugin: MarketPlugin) : PromotionAdminRoute(plugin, Promotion.CREATOR_CODE) {
    override val paths = listOf(Path("/api/panel/market/creator-codes", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = listValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = listAnswer(context)
}
