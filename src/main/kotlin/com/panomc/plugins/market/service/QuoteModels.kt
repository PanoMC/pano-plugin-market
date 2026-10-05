package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal

/*
 * The request and response shapes of `POST /api/market/checkout/quote` (04 section 2: `CartInput`, `Quote`,
 * `QuoteLine`, `PaymentMethodOption`). Money is `Long` x 100 inside; the JSON writes decimals (04 section 1).
 */

/** `CartInput.useCredits`: a number of credits x 100, or `"MAX"` (the quote only). */
sealed class UseCredits {
    data object Max : UseCredits()

    class Amount(val credits: Long) : UseCredits()
}

/** `CartInput.creditTopUp` as the route read it: a number of credits x 100, or a value that is not a usable number. */
class TopUpRequest(val credits: Long?)

class GuestInput(val username: String?, val email: String?)

/**
 * `CartInput` (04 section 2). [items] is `null` when the caller sent none: a logged-in caller then means the server cart
 * (and the cart-level fields of that cart fill whatever the request left out); a guest has an empty cart.
 */
class QuoteInput(
    val items: List<CartLine>? = null,
    val currency: String? = null,
    val couponCode: String? = null,
    val creatorCode: String? = null,
    val recipientUsername: String? = null,
    val giftMessage: String? = null,
    val creditTopUp: TopUpRequest? = null,
    val guest: GuestInput? = null,
    val useCredits: UseCredits? = null,
    val payWithCredits: Boolean = false,
    val shippingAddress: JsonObject? = null,
    val shippingAddressId: Long? = null,
    val shippingMethodId: Long? = null,
    val billingInfo: JsonObject? = null,
    val paymentMethodId: String? = null,
    val locale: String? = null
)

/**
 * Who is asking. [canUseTestMode] = the session holds `SET`, `PAY` or the umbrella node (02 section 5.1 test-mode rule);
 * the route resolves it, the service never looks at the session.
 */
class QuoteCaller(val userId: Long?, val canUseTestMode: Boolean = false, val clientIp: String? = null, val userAgent: String? = null) {
    val loggedIn: Boolean get() = userId != null

    companion object {
        val GUEST = QuoteCaller(null)
    }
}

class QuoteMessage(val code: String, val level: String, val lineKey: String? = null) {
    fun toJson(): JsonObject = JsonObject().put("code", code).put("level", level).apply { lineKey?.let { put("lineKey", it) } }

    override fun equals(other: Any?): Boolean = other is QuoteMessage && other.code == code && other.level == level && other.lineKey == lineKey

    override fun hashCode(): Int = (code.hashCode() * 31 + level.hashCode()) * 31 + (lineKey?.hashCode() ?: 0)
}

/** `QuoteLine` (04 section 2). A bundle child has `kind = BUNDLE_CHILD`, its bundle's key in [parentLineKey] and every amount 0. */
class QuoteLine(
    val lineKey: String,
    val productId: Long?,
    val variantId: Long,
    val name: String,
    val variantName: String?,
    val slug: String?,
    val imageFileName: String?,
    val quantity: Int,
    val maxQuantity: Int,
    val listUnitPrice: Long,
    val unitPrice: Long,
    val discountAmount: Long,
    val upgradeAmount: Long,
    val couponAmount: Long,
    /** Basis points. */
    val vatPercent: Long,
    val vatAmount: Long,
    val lineTotal: Long,
    val creditUnitPrice: Long,
    val fieldValues: Map<String, Any?>,
    val targetServerId: Long?,
    val physical: Boolean,
    val kind: String,
    val parentLineKey: String?,
    val billingMode: String,
    val errors: List<String>
) {
    fun toJson(): JsonObject = JsonObject()
        .put("lineKey", lineKey)
        .put("productId", productId)
        .put("variantId", variantId)
        .put("name", name)
        .put("variantName", variantName)
        .put("slug", slug)
        .put("imageFileName", imageFileName)
        .put("quantity", quantity)
        .put("maxQuantity", maxQuantity)
        .put("listUnitPrice", money(listUnitPrice))
        .put("unitPrice", money(unitPrice))
        .put("discountAmount", money(discountAmount))
        .put("upgradeAmount", money(upgradeAmount))
        .put("couponAmount", money(couponAmount))
        .put("vatPercent", vatPercent / 100.0)
        .put("vatAmount", money(vatAmount))
        .put("lineTotal", money(lineTotal))
        .put("creditUnitPrice", money(creditUnitPrice))
        .put("fieldValues", JsonObject(LinkedHashMap(fieldValues)))
        .put("targetServerId", targetServerId)
        .put("physical", physical)
        .put("kind", kind)
        .put("parentLineKey", parentLineKey)
        .put("billingMode", billingMode)
        .put("errors", JsonArray(errors))
}

/** `PaymentMethodOption` (04 section 2). [providerCode] is the code a provider gave when it refused (`PROVIDER_INELIGIBLE`). */
class PaymentMethodOption(
    val id: String,
    val label: String,
    val description: String?,
    val hint: String?,
    val icon: String?,
    val logoUrl: String?,
    val color: String?,
    val feeAmount: Long,
    val available: Boolean,
    val unavailableReason: String?,
    val providerCode: String?,
    val pricing: String,
    val recurring: String?,
    val testMode: Boolean,
    val notices: List<Pair<String, String>>,
    val requiredBuyerFields: List<String>
) {
    fun toJson(): JsonObject = JsonObject()
        .put("id", id)
        .put("label", label)
        .put("description", description)
        .put("hint", hint)
        .put("icon", icon)
        .put("logoUrl", logoUrl)
        .put("color", color)
        .put("feeAmount", money(feeAmount))
        .put("available", available)
        .put("unavailableReason", unavailableReason)
        .put("pricing", pricing)
        .put("recurring", recurring)
        .put("testMode", testMode)
        .put("notices", JsonArray(notices.map { JsonObject().put("label", it.first).put("url", it.second) }))
        .put("requiredBuyerFields", JsonArray(requiredBuyerFields))
        .apply { providerCode?.let { put("providerCode", it) } }
}

class QuoteCredits(
    val enabled: Boolean,
    val name: String,
    val balance: Long,
    val payableInCredits: Boolean,
    val creditTotal: Long,
    val maxApplicable: Long,
    val applied: Long,
    val appliedValue: Long
) {
    fun toJson(): JsonObject = JsonObject()
        .put("enabled", enabled)
        .put("name", name)
        .put("balance", money(balance))
        .put("payableInCredits", payableInCredits)
        .put("creditTotal", money(creditTotal))
        .put("maxApplicable", money(maxApplicable))
        .put("applied", money(applied))
        .put("appliedValue", money(appliedValue))
}

/** `Quote.coupon` / `Quote.creatorCode`. */
class QuoteCode(val code: String, val valid: Boolean, val reason: String?) {
    fun toJson(): JsonObject = JsonObject().put("code", code).put("valid", valid).put("reason", reason)
}

class QuoteLegal(val required: Boolean, val id: Long, val version: Int, val title: String) {
    fun toJson(): JsonObject = JsonObject().put("required", required).put("id", id).put("version", version).put("title", title)
}

class QuoteDisplay(val currency: String, val rate: BigDecimal, val subtotal: Long, val total: Long, val gatewayAmount: Long) {
    fun toJson(): JsonObject = JsonObject()
        .put("currency", currency)
        .put("rate", rate.toDouble())
        .put("subtotal", money(subtotal))
        .put("total", money(total))
        .put("gatewayAmount", money(gatewayAmount))
}

/** `Quote` (04 section 2): what a checkout of this input would charge, and why it cannot be done when it cannot. */
class Quote(
    val currency: String,
    val baseCurrency: String,
    val displayCurrency: String?,
    val lines: List<QuoteLine>,
    val pricingMode: String,
    val pricesIncludeVat: Boolean,
    val fxRate: BigDecimal,
    val display: QuoteDisplay?,
    val minimumOrderAmount: Long,
    val subtotal: Long,
    val discountTotal: Long,
    val couponDiscount: Long,
    val creatorDiscount: Long,
    val upgradeDiscount: Long,
    val shippingTotal: Long,
    val paymentFee: Long,
    val vatTotal: Long,
    val total: Long,
    val credits: QuoteCredits?,
    val gatewayAmount: Long,
    val coupon: QuoteCode?,
    val creatorCode: QuoteCode?,
    val requiresShipping: Boolean,
    val shippingOptions: List<JsonObject>,
    val shippingMethodId: Long?,
    val paymentMethods: List<PaymentMethodOption>,
    val requiredBuyerFields: List<String>,
    val legal: QuoteLegal?,
    val messages: List<QuoteMessage>,
    val canCheckout: Boolean
) {
    fun toJson(): JsonObject = JsonObject()
        .put("currency", currency)
        .put("baseCurrency", baseCurrency)
        .apply { displayCurrency?.let { put("displayCurrency", it) } }
        .put("lines", JsonArray(lines.map { it.toJson() }))
        .put("pricingMode", pricingMode)
        .put("pricesIncludeVat", pricesIncludeVat)
        .put("fxRate", fxRate.toDouble())
        .put("display", display?.toJson())
        .put("minimumOrderAmount", money(minimumOrderAmount))
        .put("subtotal", money(subtotal))
        .put("discountTotal", money(discountTotal))
        .put("couponDiscount", money(couponDiscount))
        .put("creatorDiscount", money(creatorDiscount))
        .put("upgradeDiscount", money(upgradeDiscount))
        .put("shippingTotal", money(shippingTotal))
        .put("paymentFee", money(paymentFee))
        .put("vatTotal", money(vatTotal))
        .put("total", money(total))
        .put("credits", credits?.toJson())
        .put("gatewayAmount", money(gatewayAmount))
        .put("coupon", coupon?.toJson())
        .put("creatorCode", creatorCode?.toJson())
        .put("requiresShipping", requiresShipping)
        .put("shippingOptions", JsonArray(shippingOptions))
        .put("shippingMethodId", shippingMethodId)
        .put("paymentMethods", JsonArray(paymentMethods.map { it.toJson() }))
        .put("requiredBuyerFields", JsonArray(requiredBuyerFields))
        .put("legal", legal?.toJson())
        .put("messages", JsonArray(messages.map { it.toJson() }))
        .put("canCheckout", canCheckout)
}

/** Money x100 as the decimal number of the wire format. */
internal fun money(amount: Long): Double = MoneyUtil.toDecimal(amount)
