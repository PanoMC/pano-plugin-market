package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.log.UpdatedMarketSettingsLog
import com.panomc.plugins.market.config.SettingsRequest
import com.panomc.plugins.market.core.webhook.TargetPolicy
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.routes.panel.dispute.ChargebackActionRules
import com.panomc.plugins.market.routes.panel.product.RoutingCaller
import com.panomc.plugins.market.service.platform.PlatformServerRoster
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Admin endpoint (`SET`): persists the non-credit store settings by merging the incoming body onto the
 * current config (never a raw-body overwrite), so fields the UI omits keep their stored value.
 */
@Endpoint
class PanelUpdateSettingsAPI(
    private val plugin: MarketPlugin
) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

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
            .body(Bodies.json(SettingsRequest.schema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {

        val body = context.body().asJsonObject()
        // Defence-in-depth: `version` drives config migrations and must never be settable through the
        // API. The schema already rejects unknown keys, but strip it explicitly in case it is ever
        // added as a declared property.
        body.remove("version")

        checkChargebackActions(context, body)

        val merged = SettingsRequest.apply(body, JsonObject.mapFrom(configManager.config))
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

    /**
     * A body that carries `chargebackActions` goes through [ChargebackActionRules] (11 section 10, MK-112): the actions are parsed like product actions (ids `c<n>`,
     * `COMMAND` and `WEBHOOK` only, no `BUYER_CHOICE`, no `perUnit`, at most 10) and a changed one passes `ActionGuard` for the caller. 400 `INVALID_SETTINGS
     * {fieldErrors: {chargebackActions}}`, 403 `NO_PERMISSION`. A webhook action must be unsigned: this route has no cipher, so `HMAC_SHA256` (and any secret) is refused by [ChargebackActionRules] (fail closed).
     */
    private suspend fun checkChargebackActions(context: RoutingContext, body: JsonObject) {
        val submitted = body.getValue("chargebackActions") ?: return
        val text = submitted as? String ?: throw InvalidSettings(mapOf("chargebackActions" to "INVALID"))
        val roster = PlatformServerRoster({ databaseManager }) { plugin.applicationContext.getBean(com.panomc.platform.server.ServerManager::class.java) }.snapshot(getSqlClient())
        val allowPrivate = configManager.config.allowPrivateWebhookTargets
        val verdict = ChargebackActionRules.check(
            text, configManager.config.chargebackActions, RoutingCaller(plugin, context), roster.granted.toSet(), roster.granted.isNotEmpty(),
            webhookUrlOk = { url -> TargetPolicy.syntaxOk(url, TargetPolicy.effectiveAllowPrivate(allowPrivate, com.panomc.platform.hosted.HostedEnvConfig.current.isHosted)) }
        )

        when (verdict) {
            ChargebackActionRules.Verdict.Ok -> Unit
            is ChargebackActionRules.Verdict.Invalid -> throw InvalidSettings(mapOf("chargebackActions" to verdict.reason))
            ChargebackActionRules.Verdict.Forbidden -> throw NoPermission()
        }
    }
}
