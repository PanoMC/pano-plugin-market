package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `DELETE /api/panel/market/discounts/:id` (`P:DISC`): soft delete when redemptions exist, else removed; a missing row is 404 `NOT_FOUND`. */
@Endpoint
class PanelDeleteDiscountAPI(plugin: MarketPlugin) : PromotionAdminRoute(plugin, Promotion.DISCOUNT) {
    override val paths = listOf(Path("/discounts/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = deleteAnswer(context)
}
