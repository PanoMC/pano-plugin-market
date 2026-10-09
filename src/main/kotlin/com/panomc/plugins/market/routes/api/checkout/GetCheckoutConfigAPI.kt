package com.panomc.plugins.market.routes.api.checkout

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketLegalTextDao
import com.panomc.plugins.market.db.dao.MarketShippingZoneDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.CheckoutConfigService
import com.panomc.plugins.market.service.LegalTextService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool

/** `GET /api/market/checkout/config` (04 section 3, auth class `PUB`): see [CheckoutConfigService]. Never cached. */
@Endpoint
class GetCheckoutConfigAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/checkout/config", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "What the checkout page needs before it prices a cart: guest and gift switches, billing mode, address fields, legal text, currencies and credit top-up limits.",
        tag = "checkout",
        response = MarketSchemas.checkoutConfig,
        errors = listOf(StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy {
        val context = plugin.beans

        CheckoutConfigService(
            config = { currentConfig(plugin) },
            legal = legalTextService(plugin),
            zones = context.getBean(MarketShippingZoneDao::class.java)
        )
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("locale", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val locale = context.request().getParam("locale")?.take(16)

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(service.get(locale, databaseManager.getSqlClient()).map)
    }
}

/** The legal text service on the plugin's beans (stateless: a route keeps one). */
internal fun legalTextService(plugin: MarketPlugin): LegalTextService {
    val context = plugin.beans
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }

    return LegalTextService(
        db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock),
        clock = SystemClock,
        legalTexts = context.getBean(MarketLegalTextDao::class.java),
        siteLocale = { context.getBean(ConfigManager::class.java).config.locale }
    )
}
