package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `POST /api/panel/market/discounts` (`P:DISC`): `{id}`; 400 `BAD_REQUEST` with `fieldErrors` for a value outside its range (percent 0-100, limits >= 0, window order). */
@Endpoint
class PanelCreateDiscountAPI(plugin: MarketPlugin) : PromotionAdminRoute(plugin, Promotion.DISCOUNT) {
    override val paths = listOf(Path("/api/panel/market/discounts", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = createAnswer(context)
}
