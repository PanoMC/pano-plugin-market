package com.panomc.plugins.market.routes.user.creator

import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.error.NotFound
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.api.order.noStore
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.panel.creatorcode.creatorService
import com.panomc.plugins.market.routes.user.cart.buyerId
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/market/me/creator` (`USER`): q `page?`, `pageSize?`; the codes whose `creatorUserId` is the caller, `totals{earned, reversed, pending, paidOut, available, currency}`,
 * the earnings (`orderNumber`, `amount`, `state`, `availableAt`, `createdAt`; no buyer data) as `items` of the core page shape, and the payouts. 404 when the user owns no creator
 * code, 404 `PAGE_NOT_FOUND` beyond the last page (21 section 7.5, 04 section 4).
 */
@Endpoint
class GetMyCreatorAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/me/creator", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The creator codes of the buyer with their earnings; 404 when the buyer has none.",
        tag = "me",
        response = objectSchema(),
        errors = listOf(NotFound::class, PageNotFound::class, NotLoggedIn::class, StoreUnavailable::class, StoreDisabled::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val window = Paging.request(context)
        val body = creatorService(plugin).mine(buyerId(plugin, context), window)

        noStore(context)

        return Successful(body.map)
    }
}
