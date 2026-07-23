package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.log.UpdatedMarketSettingsLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.CurrencyType
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * Admin endpoint: persists the general store settings by merging the incoming body onto the
 * current config (never a raw-body overwrite), so fields the UI omits keep their stored value.
 */
@Endpoint
class PanelUpdateMarketSettingsAPI(
    private val plugin: MarketPlugin
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/settings", RouteType.POST))

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .optionalProperty("storeName", stringSchema())
                        .optionalProperty("storeDescription", stringSchema())
                        .optionalProperty("currency", enumSchema(*CurrencyType.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("vatPercent", numberSchema())
                        .optionalProperty("showVatInPrice", booleanSchema())
                        .optionalProperty("testMode", booleanSchema())
                        .optionalProperty("allowGuestCheckout", booleanSchema())
                        .optionalProperty("minimumOrderAmount", numberSchema())
                        .optionalProperty("removeCents", booleanSchema())
                        .optionalProperty("showBestsellers", booleanSchema())
                        .optionalProperty("showFeaturedProducts", booleanSchema())
                        .optionalProperty("sendEmailAfterPurchase", booleanSchema())
                        .optionalProperty("combineDiscountsAndCoupons", booleanSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val body = context.body().asJsonObject()

        val merged = JsonObject.mapFrom(configManager.config).mergeIn(body)
        configManager.saveConfig(merged)

        val sqlClient = getSqlClient()
        val adminUserId = authProvider.getUserIdFromRoutingContext(context)
        val adminUsername = databaseManager.userDao.getUsernameFromUserId(adminUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketSettingsLog(adminUserId, adminUsername, plugin.pluginId),
            sqlClient
        )

        return Successful()
    }
}
