package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.spi.common.LocalizedText

/**
 * Buyer-facing texts the market hands to a payment provider (hosted page, statement description, line names), in the
 * order's locale. They come from the market's bundled locale files (`gateway.*`, `server-format.credit-name-default`);
 * a missing file or key gives the English text.
 */
object GatewayTexts {
    /** `Order #<id>`: the payment description of an order. */
    fun order(id: Long, locale: String?): String = text("gateway.order", "Order #{id}", locale, mapOf("id" to id))

    /** `Order`: the description of an order that has no number yet (a quote). */
    fun orderWithoutNumber(locale: String?): String = text("gateway.order-without-number", "Order", locale)

    /** The default credit name: the line name of a credit top-up without a product. */
    fun credits(locale: String?): String = text("server-format.credit-name-default", "Credits", locale)

    private fun text(path: String, fallback: String, locale: String?, vars: Map<String, Any?> = emptyMap()): String {
        val raw = ProviderTexts.resolve(LocalizedText.key("plugins.pano-plugin-market.$path", fallback), GatewayTexts, locale?.takeIf { it.isNotBlank() } ?: "en-US")

        return MarketI18n.interpolate(raw, vars)
    }
}
