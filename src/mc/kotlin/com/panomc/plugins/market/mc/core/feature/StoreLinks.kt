package com.panomc.plugins.market.mc.core.feature

import java.net.URI

/**
 * The store links of a `MARKET_CONFIG` answer: the same rule as `StoreLinks` in pano-mc-plugin's Core (MC-01), kept local
 * because the Core jar this module compiles against does not carry that class yet. Replace this file with a call to Core's
 * class once the umbrella Core jar has it.
 *
 * `productUrlTemplate` is an absolute http(s) URL with a `{slug}` placeholder, `registerUrl` an absolute http(s) URL. When a
 * field is absent or not usable the link is built from `storeUrl` as before (`/store/<slug>`, `/register`).
 */
class StoreLinks(storeUrl: String?, productUrlTemplate: String? = null, registerUrl: String? = null) {
    private val base: String? = storeUrl?.trim()?.takeIf { isWebUrl(it) }?.trimEnd('/')
    private val template: String? = productUrlTemplate?.trim()?.takeIf { isWebUrl(it) && it.contains(SLUG) }
    private val register: String? = registerUrl?.trim()?.takeIf { isWebUrl(it) }

    /** The product page for [slug]; the store itself when there is no usable slug; `null` when Pano sent no address. */
    fun productUrl(slug: String?): String? {
        val safe = slug?.takeIf(::isSafeSlug)
        if (safe != null) {
            template?.let { return it.replace(SLUG, safe) }
            return base?.let { "$it/store/$safe" }
        }
        return base?.let { "$it/store" }
    }

    /** Pano's register page when it sent one, `storeUrl/register` otherwise. */
    fun registerUrl(): String? = register ?: base?.let { "$it/register" }

    companion object {
        const val SLUG = "{slug}"

        private fun isSafeSlug(slug: String) = slug.isNotBlank() && slug.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

        private fun isWebUrl(value: String): Boolean =
            try {
                val uri = URI(value.replace(SLUG, "x"))
                (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
            } catch (_: Exception) {
                false
            }
    }
}
