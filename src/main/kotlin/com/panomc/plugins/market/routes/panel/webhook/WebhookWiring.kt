package com.panomc.plugins.market.routes.panel.webhook

import com.panomc.platform.api.webhook.RenderedBody
import com.panomc.platform.api.webhook.WebhookDiscordRenderer
import com.panomc.platform.api.webhook.WebhookEventType
import com.panomc.platform.api.webhook.webhookPublisher
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.webhook.WebhookEndpointService
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.webhook.DiscordLabelSource
import com.panomc.plugins.market.core.webhook.DiscordLabels
import com.panomc.plugins.market.core.webhook.DiscordRenderer
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.core.webhook.WebhookSamples
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.routes.api.order.deliveryService
import com.panomc.plugins.market.routes.panel.invoice.marketI18n
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.SqlLegacyWebhookTables
import com.panomc.plugins.market.service.WebhookEndpointImporter
import com.panomc.plugins.market.service.WebhookImport
import io.vertx.core.json.JsonObject

/**
 * The `format = DISCORD` renderer of core's webhook sender (08 section 16): the endpoint's custom template (or the built-in
 * one) over the variables of the event's envelope. A custom template that does not parse falls back to the built-in one and the row records
 * `lastError = TEMPLATE_ERROR` without failing.
 */
class DiscordWebhookRenderer(private val labels: DiscordLabelSource) : WebhookDiscordRenderer {
    /** [event] is the name without the source, as market published it (`order.paid`). */
    override suspend fun render(event: String, envelope: JsonObject, template: String?): RenderedBody {
        val vars = DiscordRenderer.vars(event, envelope, labels.labels(event))
        val rendered = DiscordRenderer.renderChecked(template, vars)

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
private var cachedRenderer: Pair<MarketPlugin, WebhookDiscordRenderer>? = null

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

internal fun discordWebhookRenderer(plugin: MarketPlugin): WebhookDiscordRenderer {
    cachedRenderer?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(WebhookWiringHolder) {
        cachedRenderer?.takeIf { it.first === plugin }?.second ?: DiscordWebhookRenderer(discordLabelSource(plugin)).also { cachedRenderer = plugin to it }
    }
}

/**
 * The store's events as core knows them (doc 06 section 4.4, `WebhookPublisher.register`): the ten subscribable names with their samples, the four `action.*`
 * events of the product `WEBHOOK` action as `subscribable = false`, the Discord renderer for the endpoints with `format = DISCORD`, and the listener for the
 * end of an action's direct delivery. Core prefixes `market.` and drops all of it when the plugin stops, so this runs at every start; calling it again replaces
 * the earlier call.
 */
internal fun registerStoreWebhooks(plugin: MarketPlugin) {
    plugin.webhookPublisher().register(
        plugin, storeWebhookEvents(), discordWebhookRenderer(plugin), deliveryService(plugin).outcomeListener
    )
}

/** The events the market declares, in the order the panel lists them. */
internal fun storeWebhookEvents(): List<WebhookEventType> =
    WebhookEvents.SUBSCRIBABLE.map { WebhookEventType(it, WebhookSamples.of(it), subscribable = true) } +
        WebhookEvents.ACTION_EVENTS.map { WebhookEventType(it, null, subscribable = false) }

/** The import of the old market webhook endpoints into core's (doc 06 section 4.4 step 1): the market's tables, the market's cipher, core's `importEndpoint`. */
internal fun webhookImport(plugin: MarketPlugin): WebhookImport {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }

    return WebhookImport(
        SqlLegacyWebhookTables({ databaseManager().getSqlClient() }, { MarketTables.prefixOverride ?: databaseManager().getTablePrefix() }),
        paymentWiring(plugin).cipher,
        WebhookEndpointImporter { name, url, events, format, signing, secret, headers, template, enabled, maxAttempts ->
            plugin.applicationContext.getBean(WebhookEndpointService::class.java)
                .importEndpoint(name, url, events, format, signing, secret, headers, template, enabled, maxAttempts)
        }
    )
}
