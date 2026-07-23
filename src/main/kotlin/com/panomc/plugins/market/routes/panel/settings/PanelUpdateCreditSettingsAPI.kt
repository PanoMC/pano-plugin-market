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
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * Admin endpoint: persists the credit-related settings. Separate path from the general settings
 * endpoint (matches the UI), but writes into the same config file via the same merge-save.
 */
@Endpoint
class PanelUpdateCreditSettingsAPI(
    private val plugin: MarketPlugin
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/settings/credits", RouteType.POST))

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
                        .optionalProperty("creditsEnabled", booleanSchema())
                        .optionalProperty("creditName", stringSchema())
                        .optionalProperty("cashbackPercent", numberSchema())
                        .optionalProperty("onlyAcceptCredits", booleanSchema())
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
