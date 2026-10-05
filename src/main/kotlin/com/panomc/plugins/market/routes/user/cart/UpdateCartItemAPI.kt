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
import io.vertx.json.schema.SchemaRepository

/** `PUT /api/market/me/cart/items/:itemId` (`USER`): `quantity?`, `fieldValues?`, `targetServerId?`; 404 for a line of another cart. */
@Endpoint
class UpdateCartItemAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/cart/items/:itemId", RouteType.PUT))

    private val cart by lazy { cartService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        jsonBodyValidationWithItemId(schemaRepository)

    override suspend fun handleMarket(context: RoutingContext): Result {
        val userId = buyerId(plugin, context)
        val parameters = getParameters(context)
        val view = cart.updateItem(userId, parseId(parameters.pathParameter("itemId").string, "itemId"), parseItemPatch(parameters.body().jsonObject))

        return Successful(CartJson.render(view))
    }
}
