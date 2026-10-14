package com.panomc.plugins.market.support

import com.panomc.platform.mail.template.MailTemplateIndex
import com.panomc.platform.mail.template.MailTemplateOwner
import com.panomc.platform.mail.template.MailTemplateRegistry
import com.panomc.platform.mail.template.MailTemplateService
import com.panomc.plugins.market.mail.MarketMailMessage
import com.panomc.plugins.market.mail.OutboundMail
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Renders a [MarketMailMessage] the way the platform does when it sends it: the real `MailTemplateService` of the host (its
 * locale fallback, Handlebars set-up, the shared `mail/layout.hbs` and the plain-text alternative) over the plugin's bundled
 * `src/main/resources/mail-templates`, with no database (no panel overrides) and no plugin manager.
 *
 * The service and the registry are built through their internal constructors (reflection: they are internal to the host's
 * module); the plugin's mails are registered as the registry's core owner, which changes nothing in resolution or rendering.
 * `year` is pinned so golden files do not change on New Year's Day.
 */
object MailRendering {
    const val YEAR = "2026"

    private val dir = File("src/main/resources/mail-templates")

    val index: MailTemplateIndex by lazy { MailTemplateIndex.parse(JsonObject(File(dir, "index.json").readText())) }

    private val owner: MailTemplateOwner by lazy {
        MailTemplateOwner(MailTemplateRegistry.CORE, null, null, index) { path -> File(dir, path).takeIf { it.isFile }?.readText() }
    }

    val service: MailTemplateService by lazy {
        val ownerProvider: () -> MailTemplateOwner? = { owner }
        val pluginName: (String) -> String? = { null }
        val registry = MailTemplateRegistry::class.java.declaredConstructors
            .single { it.parameterCount == 2 && it.parameterTypes[0] == Function0::class.java }
            .apply { isAccessible = true }
            .newInstance(ownerProvider, pluginName) as MailTemplateRegistry
        val overrides: suspend (SqlClient?, String, String, String, String) -> Any? = { _, _, _, _, _ -> null }
        val layout: () -> String = {
            MailTemplateService::class.java.classLoader.getResourceAsStream(MailTemplateService.LAYOUT_RESOURCE)!!.bufferedReader().use { it.readText() }
        }

        MailTemplateService::class.java.declaredConstructors
            .single { it.parameterCount == 3 && it.parameterTypes[0] == MailTemplateRegistry::class.java }
            .apply { isAccessible = true }
            .newInstance(registry, overrides, layout) as MailTemplateService
    }

    fun system(locale: String, websiteName: String = "Blocky Network", websiteUrl: String = "https://shop.example"): Map<String, Any?> =
        MailTemplateService.systemVariables(websiteName, websiteUrl, locale) + ("year" to YEAR)

    /** Subject, HTML and plain text of [message] in [locale] (falling back like a send: language, then en-US). */
    fun render(
        message: MarketMailMessage,
        locale: String,
        websiteName: String = "Blocky Network",
        websiteUrl: String = "https://shop.example"
    ): MailTemplateService.RenderedMail = runBlocking {
        service.renderForSend(
            MailTemplateRegistry.CORE, MarketMailMessage.KEY, message.variant, locale, message.variables(), system(locale, websiteName, websiteUrl), null
        )
    }
}

/** What the platform would send for this outbound mail (its row locale, the default test site). */
val OutboundMail.rendered: MailTemplateService.RenderedMail get() = MailRendering.render(message, locale)
