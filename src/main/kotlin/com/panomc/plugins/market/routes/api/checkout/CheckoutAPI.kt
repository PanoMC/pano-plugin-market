package com.panomc.plugins.market.routes.api.checkout

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.order.RequestFingerprint
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.service.CheckoutRequest
import com.panomc.plugins.market.service.UseCredits
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import java.math.BigDecimal

/**
 * `POST /api/market/checkout` (04 section 3, auth class `PUB-M`, 06 section 4): a `CartInput` plus `acceptLegal`,
 * `legalTextId`, `expectedTotal` and the header `Idempotency-Key` in, `{order, orderToken, payment}` out. The CSRF proof of a
 * session cookie is the base class's; the rate limit, the replay, the block list and everything after are
 * [com.panomc.plugins.market.service.CheckoutService.checkout]. The answer is never cached (it carries the order token).
 */
@Endpoint
class CheckoutAPI(private val plugin: MarketPlugin) : MarketPublicMutationApi() {
    override val paths = listOf(Path("/api/market/checkout", RouteType.POST))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { checkoutService(plugin, withCheckout = true) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val key = idempotencyKeyOf(context.request().getHeader(IDEMPOTENCY_HEADER))
        val request = parseCheckoutRequest(getParameters(context).body().jsonObject, key)
        val caller = quoteCaller(plugin, context)

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(service.checkout(request, caller, databaseManager.getSqlClient()).toMap())
    }

    companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
    }
}

private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{16,64}$")

/** 06 section 4 step 1: the header is required and matches `^[A-Za-z0-9_-]{16,64}$`; anything else is 400 `BAD_REQUEST`. */
internal fun idempotencyKeyOf(header: String?): String {
    val value = header?.trim()

    if (value == null || !IDEMPOTENCY_KEY.matches(value)) throw BadRequest()

    return value
}

/** The keys a checkout body may carry (11 section 4.1 PT-1: nothing else is read, and nothing else is accepted). */
internal val CHECKOUT_KEYS = setOf(
    "items", "currency", "couponCode", "creatorCode", "recipientUsername", "giftMessage", "creditTopUp", "guest", "useCredits", "payWithCredits",
    "expectedTotal", "hideFromBroadcast", "shippingAddress", "shippingAddressId", "shippingMethodId", "billingInfo", "paymentMethodId", "locale",
    "acceptLegal", "legalTextId"
)

/**
 * The `CartInput` of the quote plus the checkout fields. Pure. A key outside [CHECKOUT_KEYS] and `useCredits: "MAX"` (a quote-only
 * value, the buyer confirms an exact amount) are [RequestValueException] (400 `BAD_REQUEST`); `expectedTotal` is a money amount
 * with at most two decimals.
 */
internal fun parseCheckoutRequest(body: JsonObject, idempotencyKey: String): CheckoutRequest {
    body.fieldNames().firstOrNull { it !in CHECKOUT_KEYS }?.let { throw RequestValueException(it, "UNKNOWN_FIELD") }

    val input = parseQuoteInput(body)

    if (input.useCredits is UseCredits.Max) throw RequestValueException("useCredits", "MAX_NOT_ALLOWED")

    return CheckoutRequest(
        input = input,
        expectedTotal = body.getValue("expectedTotal")?.let { moneyOf(it, "expectedTotal") },
        acceptLegal = flag(body, "acceptLegal"),
        legalTextId = body.getValue("legalTextId")?.let { parseBodyId(it, "legalTextId") },
        hideFromBroadcast = flag(body, "hideFromBroadcast"),
        idempotencyKey = idempotencyKey,
        bodyHash = RequestFingerprint.hash(body)
    )
}

private fun flag(body: JsonObject, key: String): Boolean {
    val value = body.getValue(key) ?: return false

    return value as? Boolean ?: throw RequestValueException(key, "MUST_BE_A_BOOLEAN")
}

/** A non-negative amount with at most two decimals as money x 100 (PT-4: parsed from the number's text with `BigDecimal`). */
private fun moneyOf(raw: Any?, name: String): Long {
    if (raw !is Number) throw RequestValueException(name, "MUST_BE_A_NUMBER")

    val value = runCatching { BigDecimal(raw.toString()) }.getOrNull() ?: throw RequestValueException(name, "MUST_BE_A_NUMBER")
    val scaled = value.movePointRight(2)

    if (scaled.signum() < 0 || scaled.stripTrailingZeros().scale() > 0) throw RequestValueException(name, "INVALID_AMOUNT")

    return runCatching { scaled.longValueExact() }.getOrNull() ?: throw RequestValueException(name, "INVALID_AMOUNT")
}
