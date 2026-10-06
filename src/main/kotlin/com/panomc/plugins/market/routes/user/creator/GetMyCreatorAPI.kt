package com.panomc.plugins.market.routes.user.creator

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.api.order.noStore
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.panel.creatorcode.creatorService
import com.panomc.plugins.market.routes.user.cart.buyerId
import com.panomc.plugins.market.routes.user.credit.parseBuyerPaging
import com.panomc.plugins.market.service.CreatorPageOutOfRange
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/me/creator` (`USER`): q `page?`, `pageSize?`; the codes whose `creatorUserId` is the caller, `totals{earned, reversed, pending, paidOut, available, currency}`,
 * the earnings (`orderNumber`, `amount`, `state`, `availableAt`, `createdAt`; no buyer data) with `earningCount` / `totalPage`, and the payouts. 404 when the user owns no creator
 * code, 404 `PAGE_NOT_FOUND` beyond the last page (21 section 7.5, 04 section 4).
 */
@Endpoint
class GetMyCreatorAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/creator", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", stringSchema()))
            .queryParameter(optionalParam("pageSize", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val request = context.request()
        val window = parseBuyerPaging(request.getParam("page"), request.getParam("pageSize"))
        val body = try {
            creatorService(plugin).mine(buyerId(plugin, context), window)
        } catch (e: CreatorPageOutOfRange) {
            throw PageNotFound()
        }

        noStore(context)

        return Successful(body.map)
    }
}
