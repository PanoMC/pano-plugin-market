package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.SettingsField
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.net.URI

/** Market constructs it, the provider only reads it (02 section 6). */
class StartPaymentRequest(
    val attempt: AttemptRef,
    val amount: Money,
    val order: OrderSnapshot,
    val buyer: BuyerInfo,
    val billing: Address?,
    val shipping: Address?,
    /** Non-null: create the gateway subscription / save the payment method. */
    val subscription: SubscriptionPlan?,
    val urls: AttemptUrls,
    val idempotencyKey: String,
    val locale: String,
    val expiresAt: Long,
    /** Previous attempt of the same order, already cancelled by market. */
    val replaces: PaymentAttemptView?
)

/** Second step of an [StartPaymentResult.Embedded] form. Non-card input only (PCI scope). */
class ContinuePaymentRequest(val attempt: PaymentAttemptView, val values: JsonObject, val buyer: BuyerInfo, val urls: AttemptUrls)

class InstructionField(val label: LocalizedText, val value: String) {
    var copyable: Boolean = true
}

enum class IframeResizer { NONE, IFRAME_RESIZER }

/**
 * What a provider answers to `startPayment` / `continuePayment`. A plugin constructs it. The browser-facing wire
 * format is [toPaymentStartJson] (04 section 2 `PaymentStart`).
 */
sealed class StartPaymentResult {
    var gatewayTransactionId: String? = null
    var gatewayRefs: Map<String, String> = emptyMap()

    /** Stored encrypted on the attempt, returned in every later request. */
    var providerData: JsonObject? = null

    /** Gateway-side expiry, if shorter or longer than requested. */
    var expiresAt: Long? = null

    /** Top-level GET navigation to the gateway. */
    class Redirect(val url: String) : StartPaymentResult() {
        init {
            requireWebUrl(url, "Redirect url")
        }
    }

    /** Auto-submitted hidden POST form, top-level. */
    class FormPost(val actionUrl: String, val fields: Map<String, String>) : StartPaymentResult() {
        init {
            requireWebUrl(actionUrl, "FormPost actionUrl")
        }

        var acceptCharset: String? = null
    }

    /** Gateway page inside an iframe on the order page. */
    class Iframe(val url: String) : StartPaymentResult() {
        init {
            requireWebUrl(url, "Iframe url")
        }

        /** External scripts to load (iframe resizer). */
        var scripts: List<String> = emptyList()
            set(value) {
                value.forEach { requireWebUrl(it, "Iframe script") }
                field = value
            }
        var heightPx: Int? = null

        /** Iframe `allow` attribute, for example `payment`. */
        var allow: String? = null

        /** IFRAME_RESIZER: after `scripts` loaded the theme calls `iFrameResize` for the origin of `url` (PayTR). */
        var resizer: IframeResizer = IframeResizer.NONE
    }

    /**
     * A complete HTML document the gateway returned (3-D Secure form, embed script). Market serves it top-level,
     * sandboxed in an opaque origin. Every origin must be an https origin.
     */
    class Html(val document: String) : StartPaymentResult() {
        var scriptOrigins: List<String> = emptyList()
            set(value) {
                requireHttpsOrigins(value, "scriptOrigins")
                field = value
            }
        var frameOrigins: List<String> = emptyList()
            set(value) {
                requireHttpsOrigins(value, "frameOrigins")
                field = value
            }
        var connectOrigins: List<String> = emptyList()
            set(value) {
                requireHttpsOrigins(value, "connectOrigins")
                field = value
            }

        /** Empty = any https target (3-D Secure ACS hosts are not known in advance). */
        var formActionOrigins: List<String> = emptyList()
            set(value) {
                requireHttpsOrigins(value, "formActionOrigins")
                field = value
            }

        /** Market adds a nonce to every inline script of the document. */
        var inlineScript: Boolean = false
    }

    /** In-page step: generic fields rendered by market, or a component the plugin's UI registered. */
    class Embedded(val props: JsonObject) : StartPaymentResult() {
        /** Generic form; the values come back through `continuePayment`. */
        var fields: List<SettingsField> = emptyList()

        /** View id `market:checkout:payment:<providerId>` implemented by the plugin UI. */
        var component: String? = null
        var scripts: List<String> = emptyList()
            set(value) {
                value.forEach { requireWebUrl(it, "Embedded script") }
                field = value
            }
    }

    /** Offline payment: text plus labelled values (IBAN, reference). */
    class Instructions(val body: LocalizedText, val fields: List<InstructionField>) : StartPaymentResult() {
        /** Show "I have paid" (honoured only for the built-in bank-transfer in v1). */
        var buyerConfirms: Boolean = true
    }

    /** Paid synchronously (credits, free, stored-method charge). */
    class Completed(val event: PaymentEvent.Succeeded) : StartPaymentResult()

    /** The wire name of this result, the `kind` of `PaymentStart`. */
    val kind: String
        get() = when (this) {
            is Redirect -> "REDIRECT"
            is FormPost -> "FORM_POST"
            is Iframe -> "IFRAME"
            is Html -> "HTML"
            is Embedded -> "EMBEDDED"
            is Instructions -> "INSTRUCTIONS"
            is Completed -> "COMPLETED"
        }

    /**
     * The `PaymentStart` JSON of 04 section 2 for the theme. Buyer-facing texts are resolved to plain strings in
     * [locale]. [attemptPageUrl] is market's `/api/market/payments/attempts/:token/page` URL, the `url` of FORM_POST
     * and HTML (their documents are served from there with a strict CSP, never inlined here). The instructions
     * body is escaped plain text with newlines turned into line breaks and then passed through [sanitizeHtml]
     * (market's HtmlSanitizer). [defaultExpiresAt] is used when the provider reported no expiry of its own.
     */
    fun toPaymentStartJson(
        locale: String,
        attemptPageUrl: String? = null,
        defaultExpiresAt: Long? = null,
        sanitizeHtml: (String) -> String = { it }
    ): JsonObject {
        val json = JsonObject().put("kind", kind)
        when (this) {
            is Redirect -> json.put("url", url)
            is FormPost -> json.put("url", requireNotNull(attemptPageUrl) { "attemptPageUrl is required for FORM_POST" })
            is Html -> json.put("url", requireNotNull(attemptPageUrl) { "attemptPageUrl is required for HTML" })
            is Iframe -> json.put(
                "iframe",
                JsonObject().put("url", url).put("scripts", JsonArray(scripts)).put("heightPx", heightPx)
                    .put("allow", allow).put("resizer", resizer.name)
            )
            is Embedded -> json.put(
                "embedded",
                JsonObject().put("component", component).put("props", props).put("scripts", JsonArray(scripts))
                    .put("fields", JsonArray(fields.map { embeddedFieldJson(it, locale) }))
            )
            is Instructions -> json.put(
                "instructions",
                JsonObject()
                    .put("body", sanitizeHtml(plainTextToHtml(body.resolve(locale))))
                    .put(
                        "fields",
                        JsonArray(fields.map {
                            JsonObject().put("label", it.label.resolve(locale)).put("value", it.value).put("copyable", it.copyable)
                        })
                    )
                    .put("buyerConfirms", buyerConfirms)
            )
            is Completed -> Unit
        }
        return json.put("expiresAt", expiresAt ?: defaultExpiresAt)
    }

    private fun embeddedFieldJson(field: SettingsField, locale: String): JsonObject {
        val json = JsonObject().put("key", field.key).put("type", field.type.name).put("label", field.label.resolve(locale))
        field.help?.let { json.put("help", it.resolve(locale)) }
        field.placeholder?.let { json.put("placeholder", it) }
        json.put("required", field.required)
        field.default?.let { json.put("default", it) }
        if (field.options.isNotEmpty()) {
            json.put("options", JsonArray(field.options.map { JsonObject().put("value", it.value).put("label", it.label.resolve(locale)) }))
        }
        field.pattern?.let { json.put("pattern", it) }
        field.min?.let { json.put("min", it) }
        field.max?.let { json.put("max", it) }
        return json
    }

    internal companion object {
        /** Escapes the text and turns every newline form into a line break. */
        fun plainTextToHtml(text: String): String {
            val escaped = StringBuilder(text.length + 16)
            for (c in text) {
                when (c) {
                    '&' -> escaped.append("&amp;")
                    '<' -> escaped.append("&lt;")
                    '>' -> escaped.append("&gt;")
                    '"' -> escaped.append("&quot;")
                    '\'' -> escaped.append("&#39;")
                    else -> escaped.append(c)
                }
            }
            return escaped.toString().replace("\r\n", "<br>").replace('\r', '\n').replace("\n", "<br>")
        }

        /** Only http and https URLs reach a browser: no `javascript:`, `data:` or relative targets. */
        fun requireWebUrl(url: String, what: String) {
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                throw IllegalArgumentException("$what is not a valid URL")
            }
            val scheme = uri.scheme?.lowercase()
            require((scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()) { "$what must be an absolute http or https URL" }
        }

        /** An origin is `https://host[:port]` with no path, query or fragment. */
        fun requireHttpsOrigins(origins: List<String>, what: String) {
            for (origin in origins) {
                val uri = try {
                    URI(origin)
                } catch (e: Exception) {
                    throw IllegalArgumentException("$what holds an invalid origin")
                }
                require(
                    uri.scheme?.lowercase() == "https" && !uri.host.isNullOrEmpty() && uri.rawPath.isNullOrEmpty() &&
                        uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null
                ) { "$what must hold https origins (https://host[:port]), got '$origin'" }
            }
        }
    }
}
