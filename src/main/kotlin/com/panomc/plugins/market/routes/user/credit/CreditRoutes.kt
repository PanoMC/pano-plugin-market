package com.panomc.plugins.market.routes.user.credit

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.error.NotLoggedIn
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.plugins.market.error.CreditsDisabled
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.api.order.noStore
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.user.cart.buyerId
import com.panomc.plugins.market.runtime.beans
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/** The buyer views on the plugin's beans (stateless: a route keeps one). */
internal fun buyerCreditViews(plugin: MarketPlugin): BuyerCreditViews =
    BuyerCreditViews({ currentConfig(plugin) }, creditService(plugin), plugin.beans.getBean(MarketCreditTxDao::class.java).prefix())

/** `GET /api/market/me/credits` (`USER`): q `page?`, `pageSize?`; the balance, the credit name and the ledger of the caller, newest first. 404 `PAGE_NOT_FOUND` beyond the last page. */
@Endpoint
class GetMyCreditsAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/me/credits", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The credit balance and the ledger of the buyer.",
        tag = "me",
        response = MarketSchemas.credits,
        errors = listOf(PageNotFound::class, CreditsDisabled::class, NotLoggedIn::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val views by lazy { buyerCreditViews(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val window = Paging.request(context)
        val body = views.credits(buyerId(plugin, context), window, databaseManager.getSqlClient())

        noStore(context)

        return Successful(body.map)
    }
}
