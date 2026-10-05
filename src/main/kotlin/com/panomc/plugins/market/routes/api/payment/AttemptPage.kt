package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.provider.SecretCipher
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.net.URI

/** What `GET /api/market/payments/attempts/:attemptToken/page` answers. */
sealed interface AttemptPageResult {
    /** Unknown token, or an attempt without a page: 404. */
    data object NotFound : AttemptPageResult

    /** The attempt is not open any more: 303 to the order page (or the store when the order cannot be named). */
    class ToOrderPage(val publicId: String?) : AttemptPageResult

    /** [headers] are complete (CSP, `no-store`, `nosniff`, `no-referrer`, content type). */
    class Page(val body: String, val headers: Map<String, String>) : AttemptPageResult
}

/**
 * The attempt page (02 section 6, 11 section 5.5, 17 P-21 / V-14): `Html` and `FormPost` start results are served to the browser by market, so the
 * theme only navigates. Served only while the attempt is `CREATED` or `PENDING` (else 303 to the order page).
 *
 * - `FORM_POST`: market's own minimal document, every field name and value HTML-escaped, auto-submitted by a nonce'd script, with
 *   `Content-Security-Policy: default-src 'none'; script-src 'nonce-<n>'; form-action <origin of actionUrl>; base-uri 'none'; frame-ancestors 'none'`.
 *   The action URL must be `https:` (`http:` only for a test-mode attempt).
 * - `HTML`: the gateway's document is foreign code and never runs with the site's origin: it is always sent with
 *   `sandbox allow-scripts allow-forms allow-top-navigation allow-popups` and without `allow-same-origin`, which gives it an opaque origin (no
 *   cookies, no credentialed call to `/api`, nothing of the buyer's session), plus the origins the provider declared.
 */
class AttemptPageService(
    private val attempts: InboundAttempts,
    private val cipher: SecretCipher,
    private val ids: Ids
) {
    suspend fun page(token: String): AttemptPageResult {
        val attempt = attempts.byToken(token) ?: return AttemptPageResult.NotFound

        if (attempt.status != PaymentStatus.CREATED && attempt.status != PaymentStatus.PENDING) {
            return AttemptPageResult.ToOrderPage(attempts.publicIdOf(attempt.orderId))
        }

        val stored = attempt.startPayload?.let { cipher.decrypt(it) }?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return AttemptPageResult.NotFound
        val nonce = ids.hexToken(16)

        stored.getJsonObject("formPost")?.let { return formPost(attempt, it, nonce) }
        stored.getJsonObject("html")?.let { return html(it, nonce) }

        return AttemptPageResult.NotFound
    }

    private fun formPost(attempt: MarketPayment, form: JsonObject, nonce: String): AttemptPageResult {
        val action = form.getString("actionUrl").orEmpty()
        val origin = formOrigin(action, attempt.testMode)

        if (origin == null) {
            logger.warn("attempt {}: the stored form action is not an acceptable address, nothing is served", attempt.id)

            return AttemptPageResult.NotFound
        }

        return AttemptPageResult.Page(AttemptPages.formPostDocument(action, form.getJsonObject("fields") ?: JsonObject(), form.getString("acceptCharset"), nonce), AttemptPages.formPostHeaders(origin, nonce))
    }

    private fun html(html: JsonObject, nonce: String): AttemptPageResult {
        val inline = html.getBoolean("inlineScript") ?: false
        val document = html.getString("document").orEmpty()

        return AttemptPageResult.Page(
            if (inline) AttemptPages.withNonce(document, nonce) else document,
            AttemptPages.htmlHeaders(
                origins(html.getJsonArray("scriptOrigins")), origins(html.getJsonArray("frameOrigins")), origins(html.getJsonArray("connectOrigins")),
                origins(html.getJsonArray("formActionOrigins")), inline, nonce
            )
        )
    }

    private fun origins(array: JsonArray?): List<String> = array?.mapNotNull { it?.toString() } ?: emptyList()

    /** `scheme://host[:port]` of an `https:` address (`http:` for a test attempt) without credentials; `null` otherwise. */
    private fun formOrigin(action: String, testMode: Boolean): String? {
        val uri = try {
            URI(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()

        if (scheme != "https" && !(scheme == "http" && testMode)) return null
        if (uri.host.isNullOrEmpty() || uri.userInfo != null) return null

        return "$scheme://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AttemptPageService::class.java)
    }
}

/** The documents and headers of [AttemptPageService], pure so that a test can read exactly what a browser would get. */
object AttemptPages {
    /** What a CSP source list may contain: a scheme (`https:`), or a host with an optional `*.` and port. Anything else (`;`, spaces, quotes) is dropped. */
    private val SOURCE = Regex("^(https?://(\\*\\.)?[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:\\d{1,5})?|https?:)$")

    private val SCRIPT_TAG = Regex("<script\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val NONCE_ATTRIBUTE = Regex("\\s+nonce\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)

    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** Market's own auto-submitting form. */
    fun formPostDocument(actionUrl: String, fields: JsonObject, acceptCharset: String?, nonce: String): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\"><title>&#8230;</title></head><body>")
        append("<form id=\"f\" method=\"post\" action=\"").append(escape(actionUrl)).append('"')
        if (!acceptCharset.isNullOrBlank()) append(" accept-charset=\"").append(escape(acceptCharset)).append('"')
        append('>')

        for (name in fields.fieldNames()) {
            append("<input type=\"hidden\" name=\"").append(escape(name)).append("\" value=\"").append(escape(fields.getValue(name)?.toString().orEmpty())).append("\">")
        }

        append("<noscript><button type=\"submit\">&#8594;</button></noscript></form>")
        append("<script nonce=\"").append(nonce).append("\">document.getElementById('f').submit();</script></body></html>")
    }

    fun formPostHeaders(origin: String, nonce: String): Map<String, String> = base() + mapOf(
        "Content-Security-Policy" to "default-src 'none'; script-src 'nonce-$nonce'; form-action $origin; base-uri 'none'; frame-ancestors 'none'"
    )

    /**
     * The CSP of a gateway document: `sandbox` without `allow-same-origin`, nothing allowed beyond the declared origins. Empty lists mean `'none'`
     * (a form may go to any `https:` target when no origin is declared: 3-D Secure ACS hosts are not known in advance).
     */
    fun htmlHeaders(
        scriptOrigins: List<String>, frameOrigins: List<String>, connectOrigins: List<String>, formActionOrigins: List<String>, inlineScript: Boolean, nonce: String
    ): Map<String, String> {
        fun sources(list: List<String>, extra: String? = null): String =
            (list.filter { SOURCE.matches(it) } + listOfNotNull(extra)).distinct().ifEmpty { listOf("'none'") }.joinToString(" ")

        val formAction = formActionOrigins.filter { SOURCE.matches(it) }.distinct().ifEmpty { listOf("https:") }.joinToString(" ")
        val csp = "sandbox allow-scripts allow-forms allow-top-navigation allow-popups; default-src 'none'; " +
            "script-src ${sources(scriptOrigins, if (inlineScript) "'nonce-$nonce'" else null)}; frame-src ${sources(frameOrigins)}; connect-src ${sources(connectOrigins)}; " +
            "form-action $formAction; img-src https: data:; style-src 'unsafe-inline' https:; base-uri 'none'; frame-ancestors 'none'"

        return base() + mapOf("Content-Security-Policy" to csp)
    }

    /** Adds the nonce to every `<script>` of the document (an attribute the gateway put there is replaced). */
    fun withNonce(document: String, nonce: String): String =
        SCRIPT_TAG.replace(document) { m ->
            val attributes = m.groupValues[1].replace(NONCE_ATTRIBUTE, "")
            val selfClosing = attributes.trimEnd().endsWith("/")

            "<script" + (if (selfClosing) attributes.trimEnd().removeSuffix("/") else attributes) + " nonce=\"$nonce\"" + if (selfClosing) "/>" else ">"
        }

    private fun base() = mapOf(
        "Content-Type" to "text/html; charset=utf-8",
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer"
    )
}
