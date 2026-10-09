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

/** `POST /api/panel/market/creator-codes` (`P:DISC`): `{id}`; 400 with `fieldErrors`; a code that exists in any of the three code tables is 409 `CODE_ALREADY_EXISTS`. */
@Endpoint
class PanelCreateCreatorCodeAPI(plugin: MarketPlugin) : PromotionAdminRoute(plugin, Promotion.CREATOR_CODE) {
    override val paths = listOf(Path("/creator-codes", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = createAnswer(context)
}
