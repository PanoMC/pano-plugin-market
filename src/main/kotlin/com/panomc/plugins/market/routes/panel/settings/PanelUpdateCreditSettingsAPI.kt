package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.log.UpdatedMarketSettingsLog
import com.panomc.plugins.market.config.ConfigScope
import com.panomc.plugins.market.config.SettingsRequest
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.panel.credit.applyCreditSettings
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Admin endpoint (`SET`, 07 section 14.2): persists the credit settings through the key table of `MarketConfigKeys` and the credit bounds of
 * `applyCreditSettings` (400 `INVALID_SETTINGS {fieldErrors}`, nothing applied on any error). Same merge-save as the general settings endpoint.
 */
@Endpoint
class PanelUpdateCreditSettingsAPI(
    private val plugin: MarketPlugin
) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

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
            .body(Bodies.json(SettingsRequest.schema(ConfigScope.CREDIT)))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val body = context.body().asJsonObject()
        // Defence-in-depth: `version` drives config migrations and must never be settable through the
        // API. The schema already rejects unknown keys, but strip it explicitly in case it is ever
        // added as a declared property.
        body.remove("version")

        val merged = applyCreditSettings(body, JsonObject.mapFrom(configManager.config))
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
