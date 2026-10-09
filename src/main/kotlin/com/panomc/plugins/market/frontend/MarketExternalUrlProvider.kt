package com.panomc.plugins.market.frontend

import com.panomc.platform.api.ExternalUrl
import com.panomc.platform.api.ExternalUrlProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.provider.ProviderOrigin
import com.panomc.plugins.market.routes.panel.settings.payment.providerLookup
import com.panomc.plugins.market.routes.panel.settings.payment.siteInfoOf
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.util.MarketPaths
import org.springframework.stereotype.Component

/**
 * The addresses an admin has registered at a gateway or a carrier (04 section 9, decisions 17 and 46). The API cutover moved
 * them and the old ones are not kept as aliases, so the panel's compatibility page lists the new ones for the admin to enter again.
 *
 * Only providers that come from a plugin are listed; the built-in methods (free, credits, bank transfer) talk to no outside service.
 */
@Component
class MarketExternalUrlProvider(private val plugin: MarketPlugin) : ExternalUrlProvider {
    override fun urls(): List<ExternalUrl> {
        val config = runCatching { plugin.beans.getBean(ConfigManager::class.java).config }.getOrNull() ?: return emptyList()
        val base = siteInfoOf(config.websiteUrl, config.websiteName).baseUrl

        if (base.isBlank()) return emptyList()

        val lookup = providerLookup(plugin)

        return urlsOf(
            base,
            paymentIds = lookup.allPayment().filter { it.origin == ProviderOrigin.PLUGIN }.map { it.id },
            shippingIds = lookup.allShipping().filter { it.origin == ProviderOrigin.PLUGIN }.map { it.id }
        )
    }

    companion object {
        /** Stands where the per-attempt token of a return address goes. */
        const val ATTEMPT_TOKEN = "{attemptToken}"

        /** Stands where the install token of a carrier's webhook goes (the panel shows the real one on the carrier's page). */
        const val INSTALL_TOKEN = "{installToken}"

        /**
         * The addresses for [paymentIds] and [shippingIds] under the site [base] (no trailing slash): a webhook and a return
         * address per payment provider, a webhook per carrier. Pure.
         */
        fun urlsOf(base: String, paymentIds: List<String>, shippingIds: List<String>): List<ExternalUrl> {
            val payments = paymentIds.flatMap { id ->
                listOf(
                    ExternalUrl("$id payment webhook", base + MarketPaths.site("/payments/$id/webhook")),
                    ExternalUrl("$id payment return", base + MarketPaths.site("/payments/$id/return/$ATTEMPT_TOKEN/result"))
                )
            }
            val carriers = shippingIds.map { id ->
                ExternalUrl("$id shipping webhook", base + MarketPaths.site("/shipping/$id/webhook/$INSTALL_TOKEN"))
            }

            return payments + carriers
        }
    }
}
