package com.panomc.plugins.market.routes.panel.webhook

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.hosted.HostedEnvConfig
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.core.webhook.DiscordLabelSource
import com.panomc.plugins.market.core.webhook.DiscordLabels
import com.panomc.plugins.market.core.webhook.DiscordRenderer
import com.panomc.plugins.market.core.webhook.TargetPolicy
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.db.dao.MarketWebhookDeliveryDao
import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
import com.panomc.plugins.market.db.model.MarketWebhookEndpoint
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.routes.api.checkout.abuseWiring
import com.panomc.plugins.market.routes.api.order.webhookService
import com.panomc.plugins.market.routes.panel.invoice.marketI18n
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.OutboundHttp
import com.panomc.plugins.market.service.RenderedBody
import com.panomc.plugins.market.service.WebhookBodyRenderer
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool

/**
 * The `format = DISCORD` renderer of [com.panomc.plugins.market.service.WebhookService] (08 section 16): the endpoint's custom template (or the built-in
 * one) over the variables of the event's envelope. A custom template that does not parse falls back to the built-in one and the row records
 * `lastError = TEMPLATE_ERROR` without failing.
 */
class DiscordWebhookRenderer(private val labels: DiscordLabelSource) : WebhookBodyRenderer {
    override suspend fun render(endpoint: MarketWebhookEndpoint, event: String, envelope: JsonObject): String =
        renderChecked(endpoint, event, envelope).body

    override suspend fun renderChecked(endpoint: MarketWebhookEndpoint, event: String, envelope: JsonObject): RenderedBody {
        val vars = DiscordRenderer.vars(event, envelope, labels.labels(event))
        val rendered = DiscordRenderer.renderChecked(endpoint.template, vars)

        return RenderedBody(rendered.body, if (rendered.templateError) TEMPLATE_ERROR else null)
    }

    companion object {
        const val TEMPLATE_ERROR = "TEMPLATE_ERROR"
    }
}

/** The `MarketI18n` translations `webhooks.discord.<event>.title` / `.description` and `webhooks.discord.label.*` in the locale [locale] (08 section 16.2). */
class I18nDiscordLabels(private val i18n: MarketI18n, private val locale: () -> String) : DiscordLabelSource {
    override suspend fun labels(event: String): DiscordLabels {
        val l = locale()

        suspend fun t(key: String, fallback: String): String = if (i18n.has(l, key)) i18n.t(l, key) else fallback

        val generic = "webhooks.discord.generic"
        val title = t("webhooks.discord.$event.title", t("$generic.title", DiscordLabels.DEFAULT.title))
        val description = t("webhooks.discord.$event.description", t("$generic.description", DiscordLabels.DEFAULT.description))

        return DiscordLabels(
            title, description,
            t("webhooks.discord.label.player", DiscordLabels.DEFAULT.player), t("webhooks.discord.label.total", DiscordLabels.DEFAULT.total),
            t("webhooks.discord.label.items", DiscordLabels.DEFAULT.items)
        )
    }
}

@Volatile
private var cachedLabels: Pair<MarketPlugin, DiscordLabelSource>? = null

@Volatile
private var cachedRenderer: Pair<MarketPlugin, WebhookBodyRenderer>? = null

@Volatile
private var cachedAdmin: Pair<MarketPlugin, WebhookAdminService>? = null

private object WebhookWiringHolder

/** The Discord label source on the plugin's translator and the platform's default locale. */
internal fun discordLabelSource(plugin: MarketPlugin): DiscordLabelSource {
    cachedLabels?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(WebhookWiringHolder) {
        cachedLabels?.takeIf { it.first === plugin }?.second ?: run {
            val context = plugin.beans
            val locale = { runCatching { context.getBean(ConfigManager::class.java).config.locale }.getOrNull()?.takeIf { it.isNotBlank() } ?: "en-US" }

            I18nDiscordLabels(marketI18n(plugin).first, locale).also { cachedLabels = plugin to it }
        }
    }
}

internal fun discordWebhookRenderer(plugin: MarketPlugin): WebhookBodyRenderer {
    cachedRenderer?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(WebhookWiringHolder) {
        cachedRenderer?.takeIf { it.first === plugin }?.second ?: DiscordWebhookRenderer(discordLabelSource(plugin)).also { cachedRenderer = plugin to it }
    }
}

/** The panel service of the webhook routes, built once per plugin instance. */
internal fun webhookAdminService(plugin: MarketPlugin): WebhookAdminService {
    cachedAdmin?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(WebhookWiringHolder) {
        cachedAdmin?.takeIf { it.first === plugin }?.second ?: buildAdmin(plugin).also { cachedAdmin = plugin to it }
    }
}

private fun buildAdmin(plugin: MarketPlugin): WebhookAdminService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val version = plugin.applicationContext.getBean(com.panomc.platform.PluginManager::class.java).getPlugin(plugin.pluginId).descriptor.version

    return WebhookAdminService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), clock = SystemClock,
        endpoints = context.getBean(MarketWebhookEndpointDao::class.java), deliveries = context.getBean(MarketWebhookDeliveryDao::class.java),
        webhooks = webhookService(plugin), cipher = paymentWiring(plugin).cipher, outbound = OutboundHttp.create(context.getBean(Vertx::class.java), version),
        allowPrivate = { TargetPolicy.effectiveAllowPrivate(currentConfig(plugin).allowPrivateWebhookTargets, HostedEnvConfig.current.isHosted) },
        prefix = { MarketTables.prefixOverride ?: databaseManager().getTablePrefix() },
        rateLimit = { userId -> abuseWiring(plugin).rateLimits.panelAction(userId) }
    )
}
