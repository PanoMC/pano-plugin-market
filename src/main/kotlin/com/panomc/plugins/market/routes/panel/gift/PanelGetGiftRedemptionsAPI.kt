package com.panomc.plugins.market.routes.panel.gift

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

/** `GET /api/panel/market/gifts/:id/redemptions` (`P:DISC`): `redemptions[{orderId, playerUsername, amount, currency, state, createdAt}]`, `redemptionCount`, `totalPage`. */
@Endpoint
class PanelGetGiftRedemptionsAPI(plugin: MarketPlugin) : PromotionAdminRoute(plugin, Promotion.GIFT) {
    override val paths = listOf(Path("/api/panel/market/gifts/:id/redemptions", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = redemptionsAnswer(context)
}
