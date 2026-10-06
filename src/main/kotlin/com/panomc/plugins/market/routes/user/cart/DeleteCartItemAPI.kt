package com.panomc.plugins.market.routes.user.cart

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.base.parseId
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** `DELETE /api/market/me/cart/items/:itemId` (`USER`): idempotent, a missing line is success. */
@Endpoint
class DeleteCartItemAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/cart/items/:itemId", RouteType.DELETE))

    private val cart by lazy { cartService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("itemId", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val userId = buyerId(plugin, context)
        val view = cart.removeItem(userId, parseId(getParameters(context).pathParameter("itemId").string, "itemId"))

        return Successful(CartJson.answer(plugin, userId, view))
    }
}
