package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `GET /api/panel/market/discounts` (`P:DISC`): the core page shape (`items`, `page`); query `page`, `pageSize`, `search`, `status`. */
@Endpoint
class PanelGetDiscountsAPI(plugin: MarketPlugin) : PromotionAdminRoute(plugin, Promotion.DISCOUNT) {
    override val paths = listOf(Path("/discounts", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = listValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = listAnswer(context)
}
