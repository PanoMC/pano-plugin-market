package com.panomc.plugins.market.routes.user.credit

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.api.order.noStore
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.user.cart.buyerId
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.util.Paging
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** The buyer views on the plugin's beans (stateless: a route keeps one). */
internal fun buyerCreditViews(plugin: MarketPlugin): BuyerCreditViews =
    BuyerCreditViews({ currentConfig(plugin) }, creditService(plugin), plugin.beans.getBean(MarketCreditTxDao::class.java).prefix())

/** `page` / `pageSize` of a buyer list as text: an unreadable number is 400, never a schema 500. */
internal fun parseBuyerPaging(page: String?, pageSize: String?): Paging.Window {
    fun number(raw: String?, name: String): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return value.toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_AN_INTEGER")
    }

    return parsePagingRequest(number(page, "page"), number(pageSize, "pageSize"))
}

/** `GET /api/market/me/credits` (`USER`): q `page?`, `pageSize?`; the balance, the credit name and the ledger of the caller, newest first. 404 `PAGE_NOT_FOUND` beyond the last page. */
@Endpoint
class GetMyCreditsAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/credits", RouteType.GET))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val views by lazy { buyerCreditViews(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", stringSchema()))
            .queryParameter(optionalParam("pageSize", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val request = context.request()
        val window = parseBuyerPaging(request.getParam("page"), request.getParam("pageSize"))
        val body = views.credits(buyerId(plugin, context), window, databaseManager.getSqlClient())

        noStore(context)

        return Successful(body.map)
    }
}
