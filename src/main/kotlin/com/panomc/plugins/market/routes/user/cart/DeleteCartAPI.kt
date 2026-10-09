package com.panomc.plugins.market.routes.user.cart

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.BadRequest
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

/** `DELETE /api/market/me/cart` (`USER`): empties the cart (lines, codes, recipient, gift message, shipping selection). */
@Endpoint
class DeleteCartAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/me/cart", RouteType.DELETE))

    override val doc = EndpointDoc(
        summary = "Empties the cart and answers it with its quote.",
        tag = "me",
        response = MarketSchemas.cartAnswer,
        errors = listOf(BadRequest::class, NotFound::class, InvalidCart::class, NotLoggedIn::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val cart by lazy { cartService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handleMarket(context: RoutingContext): Result {
        val userId = buyerId(plugin, context)
        val view = cart.clear(userId)

        return Successful(CartJson.answer(plugin, userId, view))
    }
}
