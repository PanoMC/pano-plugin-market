package com.panomc.plugins.market.routes.user

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.api.order.noStore
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.user.cart.buyerId
import com.panomc.plugins.market.routes.user.credit.buyerCreditViews
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/market/me/summary` (`USER`, 04 section 4): `creditsEnabled, creditBalance, creditName, cartItemCount, activeSubscriptionCount, subscriptionCount,
 * isCreator`: what the navbar badge and the profile navigation of the theme need in one call.
 */
@Endpoint
class GetMySummaryAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/summary", RouteType.GET))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val views by lazy { buyerCreditViews(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handleMarket(context: RoutingContext): Result {
        val body = views.summary(buyerId(plugin, context), databaseManager.getSqlClient())

        noStore(context)

        return Successful(body.map)
    }
}
