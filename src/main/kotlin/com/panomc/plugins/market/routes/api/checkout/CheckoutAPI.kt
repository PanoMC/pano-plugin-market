package com.panomc.plugins.market.routes.api.checkout

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLineParser
import com.panomc.plugins.market.core.order.RequestFingerprint
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.service.CheckoutRequest
import com.panomc.plugins.market.service.UseCredits
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import java.math.BigDecimal
import java.util.Locale

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
        val body = getParameters(context).body().jsonObject
        val parsed = parseCheckoutRequest(body, key)
        val caller = quoteCaller(plugin, context)
        val request = parsed.copy(orderLocale = orderLocaleOf(context, body, caller.userId))

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(service.checkout(request, caller, databaseManager.getSqlClient()).toMap())
    }

    /** 06 section 5.4 `locale`: [OrderLocale.resolve] over the installed platform locales, the user's stored locale, `Accept-Language` and the site default. */
    private suspend fun orderLocaleOf(context: RoutingContext, body: JsonObject, userId: Long?): String {
        val sqlClient = databaseManager.getSqlClient()
        val installed = databaseManager.localeDao.getAll(sqlClient).map { InstalledLocale(it.code, it.derivatives) }
        val stored = userId?.let { databaseManager.userDao.getLocaleCodeById(it, sqlClient) }
        val siteDefault = plugin.applicationContext.getBean(ConfigManager::class.java).config.locale

        return OrderLocale.resolve(body.getValue("locale") as? String, stored, context.request().getHeader("Accept-Language"), installed, siteDefault)
    }

    companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
    }
}

/** An installed platform locale: its code and the alternative codes (`tr-tr` for `tr`) the platform maps onto it. */
internal class InstalledLocale(val code: String, val derivatives: List<String> = emptyList())

/**
 * The locale an order is stored with (06 section 5.4): the body `locale` when it is an installed platform locale; else the user's stored
 * locale; else the first installed match of `Accept-Language`; else the site default. Pure. The answer is always the code of an installed
 * locale (or the site default), never a client string, because order mails and invoices look their templates up by it.
 */
internal object OrderLocale {
    fun resolve(bodyLocale: String?, userLocale: String?, acceptLanguage: String?, installed: List<InstalledLocale>, siteDefault: String): String {
        exact(bodyLocale, installed)?.let { return it }
        exact(userLocale, installed)?.let { return it }

        for (tag in acceptedTags(acceptLanguage)) {
            exact(tag, installed)?.let { return it }
            sameLanguage(tag, installed)?.let { return it }
        }

        return siteDefault
    }

    /** The installed code that is [tag] (case-insensitively) or lists it as a derivative. */
    private fun exact(tag: String?, installed: List<InstalledLocale>): String? {
        val wanted = tag?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.length <= MAX_TAG } ?: return null

        installed.firstOrNull { it.code.lowercase(Locale.ROOT) == wanted }?.let { return it.code }

        return installed.firstOrNull { l -> l.derivatives.any { it.trim().lowercase(Locale.ROOT) == wanted } }?.code
    }

    /** The first installed locale of the same primary language (`en-GB` finds `en-US`). */
    private fun sameLanguage(tag: String, installed: List<InstalledLocale>): String? {
        val language = tag.substringBefore('-')

        return installed.firstOrNull { it.code.lowercase(Locale.ROOT).substringBefore('-') == language }?.code
    }

    /** The language tags of an `Accept-Language` value, highest weight first (equal weights keep their order); `*` and `q=0` are dropped. */
    internal fun acceptedTags(header: String?): List<String> {
        if (header.isNullOrBlank() || header.length > MAX_HEADER) return emptyList()

        return header.split(',').mapIndexedNotNull { index, part ->
            val pieces = part.split(';')
            val tag = pieces[0].trim().lowercase(Locale.ROOT)
            val weight = pieces.drop(1).map { it.trim() }.firstOrNull { it.startsWith("q=", ignoreCase = true) }
                ?.substring(2)?.trim()?.toDoubleOrNull() ?: 1.0

            if (tag.isEmpty() || tag == "*" || weight <= 0.0 || weight.isNaN()) null else Triple(index, weight, tag)
        }.sortedWith(compareByDescending<Triple<Int, Double, String>> { it.second }.thenBy { it.first }).map { it.third }
    }

    private const val MAX_TAG = 35
    private const val MAX_HEADER = 1024
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

    requireQuantities(body)

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

/**
 * 06 section 2.1 / 11 section 4.1 PT-2: a line of an order carries an integral `quantity` in `1..999` (the quote clamps, an order is never
 * created for a number the buyer did not send), and equal lines (same `lineKey`) add up to at most 999. A violation is 400 `INVALID_CART`
 * with `lineErrors {"items[<index>]": ["QUANTITY_OUT_OF_RANGE"]}` (11 PT-2 names `INVALID_CART`; the lines of a parsed request have no
 * stable key yet, so the position in `items` names the line). Only the explicit `items` are looked at; a line that is not readable at
 * all is left to the quote parser (`INVALID_LINE`, 400 `BAD_REQUEST`).
 */
private fun requireQuantities(body: JsonObject) {
    val array = body.getValue("items") as? JsonArray ?: return

    if (array.size() > MAX_QUOTE_LINES) return

    val sums = HashMap<String, Long>()

    array.list.forEachIndexed { index, element ->
        val map = when (element) {
            is JsonObject -> element.map
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (element as Map<String, Any?>)
            else -> return@forEachIndexed
        }
        val quantity = integralQuantity(map["quantity"]) ?: throw quantityOutOfRange(index)

        if (quantity < CartLimits.MIN_QUANTITY || quantity > CartLimits.MAX_QUANTITY) throw quantityOutOfRange(index)

        val line = CartLineParser.parse(map) ?: return@forEachIndexed
        val sum = (sums[line.lineKey] ?: 0L) + quantity

        if (sum > CartLimits.MAX_QUANTITY) throw quantityOutOfRange(index)

        sums[line.lineKey] = sum
    }
}

internal const val QUANTITY_OUT_OF_RANGE = "QUANTITY_OUT_OF_RANGE"

private fun quantityOutOfRange(index: Int) = InvalidCart(mapOf("items[$index]" to listOf(QUANTITY_OUT_OF_RANGE)))

/** A JSON number with no fraction (`2` and `2.0`), or `null` (absent, text, `1.5`, infinite). */
private fun integralQuantity(raw: Any?): Long? {
    if (raw !is Number) return null

    val value = runCatching { BigDecimal(raw.toString()) }.getOrNull() ?: return null

    return if (value.signum() == 0 || value.stripTrailingZeros().scale() <= 0) runCatching { value.longValueExact() }.getOrNull() else null
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
