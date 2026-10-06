package com.panomc.plugins.market.routes.user.cart

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.base.MarketUserApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `POST /api/market/me/cart/items` (`USER`): a `CartLine`, merged into an identical line; 400 `INVALID_CART` for a bad line or a full cart. */
@Endpoint
class AddCartItemAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/cart/items", RouteType.POST))

    private val cart by lazy { cartService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = jsonBodyValidation(schemaRepository)

    override suspend fun handleMarket(context: RoutingContext): Result {
        val userId = buyerId(plugin, context)
        val view = cart.addItem(userId, parseStrictLine(getParameters(context).body().jsonObject))

        return Successful(CartJson.answer(plugin, userId, view))
    }
}
