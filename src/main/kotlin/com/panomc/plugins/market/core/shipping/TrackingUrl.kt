package com.panomc.plugins.market.core.shipping

import java.net.URI
import java.net.URLEncoder

/** Tracking URLs (10 section 7.2 step 2 and section 5.1). Pure. */
object TrackingUrl {
    const val PLACEHOLDER = "{tracking}"
    const val MAX_URL_LENGTH = 1024
    const val MAX_TEMPLATE_LENGTH = 512

    /** A template is `http://` or `https://`, at most 512 characters and contains `{tracking}`. */
    fun isValidTemplate(template: String): Boolean =
        template.length <= MAX_TEMPLATE_LENGTH && PLACEHOLDER in template && isHttp(template.replace(PLACEHOLDER, "x"))

    /** `{tracking}` replaced by the number, URL-encoded as UTF-8 (a space becomes `%20`, not `+`). */
    fun render(template: String, trackingNumber: String): String =
        template.replace(PLACEHOLDER, URLEncoder.encode(trackingNumber, Charsets.UTF_8).replace("+", "%20"))

    /** A provider URL is accepted only when it parses as `http` / `https` and has at most 1024 characters. */
    fun accept(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_LENGTH && isHttp(it) }

    private fun isHttp(url: String): Boolean = try {
        val uri = URI(url)

        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }
}
