package com.panomc.plugins.market.routes.panel.coupon

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

/** `DELETE /api/panel/market/coupons/:id` (`P:DISC`): soft delete when redemptions exist, else removed; a missing row is 404 `NOT_FOUND`. */
@Endpoint
class PanelDeleteCouponAPI(plugin: MarketPlugin) : PromotionAdminRoute(plugin, Promotion.COUPON) {
    override val paths = listOf(Path("/api/panel/market/coupons/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = deleteAnswer(context)
}
