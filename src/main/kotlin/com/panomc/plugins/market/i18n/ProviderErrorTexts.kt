package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderException

/**
 * Panel text of a failed provider call: the gateway's own [ProviderException.adminMessage] when it gave one, else the
 * panel locale key `provider-errors.<CODE>`. The developer message of the exception is for the log only and is never shown.
 */
object ProviderErrorTexts {
    private const val PREFIX = "plugins.pano-plugin-market.provider-errors."

    private val FALLBACKS = mapOf(
        "CONFIGURATION" to "The provider is not configured correctly.",
        "AUTHENTICATION" to "The provider rejected the credentials.",
        "IP_NOT_ALLOWED" to "The provider does not allow this server's IP address.",
        "GATEWAY_UNREACHABLE" to "The provider could not be reached. Try again in a moment.",
        "GATEWAY_REJECTED" to "The provider refused the request.",
        "RATE_LIMITED" to "The provider is limiting requests. Try again in a moment.",
        "INVALID_REQUEST" to "The provider found the request invalid.",
        "UNSUPPORTED" to "The provider does not support this.",
        "NOT_FOUND" to "The provider could not find the record.",
        "INTERNAL" to "The provider had an internal error.",
        "INVALID_BUYER" to "The provider refused the buyer's details.",
    )

    fun of(e: ProviderException): LocalizedText {
        e.adminMessage?.takeIf { it.isNotBlank() }?.let { return LocalizedText.of(it) }

        val code = e.code.name

        return LocalizedText.key(PREFIX + code, FALLBACKS[code] ?: "The provider reported an error ($code).")
    }
}
