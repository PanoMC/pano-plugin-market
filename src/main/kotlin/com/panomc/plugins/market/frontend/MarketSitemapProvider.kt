package com.panomc.plugins.market.frontend

import com.panomc.platform.api.SitemapEntry
import com.panomc.platform.api.SitemapProvider
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.api.store.storeQueryService
import com.panomc.plugins.market.routes.base.MarketGate
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.SitemapPages
import io.vertx.sqlclient.SqlClient
import org.springframework.stereotype.Component

/**
 * The store's public pages for `GET /api/v1/sitemap` (04 section 8): one entry per product a guest can open and one per
 * category. An entry says what the page is, not where it lives; the front-end URL map builds the address from `params`
 * (`market.product` is `/store/{slug}`).
 *
 * While the store cannot answer (not READY, or switched off) it announces nothing, as a visitor would find nothing.
 */
@Component
class MarketSitemapProvider(private val plugin: MarketPlugin) : SitemapProvider {
    override suspend fun entries(sqlClient: SqlClient): List<SitemapEntry> {
        if (MarketRuntime.state != MarketRuntime.State.READY || !MarketGate.storeEnabled()) return emptyList()

        return entriesOf(storeQueryService(plugin).sitemapPages(sqlClient))
    }

    companion object {
        /** The type of a product entry. */
        const val PRODUCT = "pano-plugin-market:product"

        /** The type of a category entry. */
        const val CATEGORY = "pano-plugin-market:category"

        /** The entries of [pages]: products first (`{ slug }`), then categories (`{ id }`). */
        fun entriesOf(pages: SitemapPages): List<SitemapEntry> =
            pages.products.map { SitemapEntry(PRODUCT, mapOf("slug" to it.slug), it.updatedAt) } +
                pages.categories.map { SitemapEntry(CATEGORY, mapOf("id" to it.id), it.updatedAt) }
    }
}
