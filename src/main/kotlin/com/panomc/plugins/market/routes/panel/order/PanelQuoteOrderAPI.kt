package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLineParser
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.InstalledLocale
import com.panomc.plugins.market.routes.api.checkout.OrderLocale
import com.panomc.plugins.market.routes.api.checkout.checkoutService
import com.panomc.plugins.market.routes.api.checkout.parseQuoteInput
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.service.ManualOrderRequest
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

/** The keys a manual order body may carry (04 section 7 `POST /orders`; 11 section 4.1 PT-1: nothing else is read). */
internal val MANUAL_ORDER_KEYS = setOf(
    "playerUsername", "recipientUsername", "email", "items", "priceOverride", "markPaid", "paymentLabel", "runDeliveries", "sendMail", "note", "force",
    "shippingAddress", "shippingMethodId", "shippingPrice"
)

private const val MAX_NAME = 64
private const val MAX_EMAIL = 255
private const val MAX_LABEL = 255
private const val MAX_NOTE = 2000

/**
 * The body of `POST /api/panel/market/orders` and `.../quote` after parsing. Pure. A key outside [MANUAL_ORDER_KEYS], a value of the wrong type, an amount with
 * more than two decimals or a negative one is [RequestValueException] (400 `BAD_REQUEST`); a line with a quantity outside `1..999` is 400 `INVALID_CART`. The
 * business rules (`EMPTY_CART`, limits, stock) are the service's. [idempotencyKey] is `""` for a quote.
 */
internal fun parseManualOrderRequest(body: JsonObject, idempotencyKey: String = "", bodyHash: String = "", orderLocale: String? = null): ManualOrderRequest {
    body.fieldNames().firstOrNull { it !in MANUAL_ORDER_KEYS }?.let { throw RequestValueException(it, "UNKNOWN_FIELD") }

    val player = text(body, "playerUsername", MAX_NAME) ?: throw RequestValueException("playerUsername", "REQUIRED")
    val rawItems = body.getValue("items") ?: throw RequestValueException("items", "REQUIRED")

    requireQuantities(rawItems)

    val items = parseQuoteInput(JsonObject().put("items", rawItems)).items.orEmpty()

    return ManualOrderRequest(
        playerUsername = player,
        recipientUsername = text(body, "recipientUsername", MAX_NAME),
        email = text(body, "email", MAX_EMAIL),
        items = items,
        priceOverride = body.getValue("priceOverride")?.let { money(it, "priceOverride") },
        markPaid = flag(body, "markPaid", false),
        paymentLabel = text(body, "paymentLabel", MAX_LABEL),
        runDeliveries = flag(body, "runDeliveries", true),
        sendMail = flag(body, "sendMail", true),
        note = text(body, "note", MAX_NOTE),
        force = flag(body, "force", false),
        shippingAddress = when (val raw = body.getValue("shippingAddress")) {
            null -> null
            is JsonObject -> raw
            is Map<*, *> -> JsonObject(@Suppress("UNCHECKED_CAST") (raw as Map<String, Any?>))
            else -> throw RequestValueException("shippingAddress", "MUST_BE_AN_OBJECT")
        },
        shippingMethodId = body.getValue("shippingMethodId")?.let { parseBodyId(it, "shippingMethodId") },
        shippingPrice = body.getValue("shippingPrice")?.let { money(it, "shippingPrice") },
        idempotencyKey = idempotencyKey,
        bodyHash = bodyHash,
        orderLocale = orderLocale
    )
}

/** A line of a manual order carries an integral `quantity` in `1..999` (06 section 2.1); the position in `items` names the line. */
private fun requireQuantities(raw: Any?) {
    val array = raw as? JsonArray ?: throw RequestValueException("items", "MUST_BE_AN_ARRAY")

    array.list.forEachIndexed { index, element ->
        val map = when (element) {
            is JsonObject -> element.map
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (element as Map<String, Any?>)
            else -> return@forEachIndexed
        }
        val quantity = (map["quantity"] as? Number)?.let { runCatching { BigDecimal(it.toString()) }.getOrNull() }
            ?.takeIf { it.signum() != 0 && it.stripTrailingZeros().scale() <= 0 }?.let { runCatching { it.longValueExact() }.getOrNull() }

        if (quantity == null || quantity < CartLimits.MIN_QUANTITY || quantity > CartLimits.MAX_QUANTITY) {
            throw InvalidCart(mapOf("items[$index]" to listOf("QUANTITY_OUT_OF_RANGE")))
        }

        CartLineParser.parse(map) ?: return@forEachIndexed
    }
}

private fun text(body: JsonObject, key: String, max: Int): String? {
    val value = body.getValue(key) ?: return null

    if (value !is String) throw RequestValueException(key, "MUST_BE_A_STRING")
    if (value.length > max) throw RequestValueException(key, "TOO_LONG")

    return value.takeIf { it.isNotBlank() }
}

private fun flag(body: JsonObject, key: String, default: Boolean): Boolean {
    val value = body.getValue(key) ?: return default

    return value as? Boolean ?: throw RequestValueException(key, "MUST_BE_A_BOOLEAN")
}

/** A non-negative amount with at most two decimals as money x 100 (PT-4: parsed from the number's text with `BigDecimal`). */
private fun money(raw: Any?, name: String): Long {
    if (raw !is Number) throw RequestValueException(name, "MUST_BE_A_NUMBER")

    val value = runCatching { BigDecimal(raw.toString()) }.getOrNull() ?: throw RequestValueException(name, "MUST_BE_A_NUMBER")
    val scaled = value.movePointRight(2)

    if (scaled.signum() < 0 || scaled.stripTrailingZeros().scale() > 0) throw RequestValueException(name, "INVALID_AMOUNT")

    return runCatching { scaled.longValueExact() }.getOrNull() ?: throw RequestValueException(name, "INVALID_AMOUNT")
}

/**
 * The locale of a manual order (06 section 14.3): the payer's stored locale when the payer is a Pano user, else the site default. Resolved like checkout
 * does ([OrderLocale]), so the answer is the code of an installed locale.
 */
internal suspend fun manualOrderLocale(plugin: MarketPlugin, playerUsername: String): String {
    val databaseManager = plugin.applicationContext.getBean(DatabaseManager::class.java)
    val sqlClient = databaseManager.getSqlClient()
    val installed = databaseManager.localeDao.getAll(sqlClient).map { InstalledLocale(it.code, it.derivatives) }
    val userId = databaseManager.userDao.getUserIdFromUsername(playerUsername.trim(), sqlClient)
    val stored = userId?.let { databaseManager.userDao.getLocaleCodeById(it, sqlClient) }
    val siteDefault = plugin.applicationContext.getBean(ConfigManager::class.java).config.locale

    return OrderLocale.resolve(null, stored, null, installed, siteDefault)
}

/**
 * `POST /api/panel/market/orders/quote` (`P:PAY`, 04 section 7): the same body as `POST /orders` without the `Idempotency-Key`; answers `{quote}` for the
 * **named player**. Nothing is created.
 */
@Endpoint
class PanelQuoteOrderAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/quote", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    private val service by lazy { checkoutService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val body = context.body().asJsonObject() ?: JsonObject()
        val request = parseManualOrderRequest(body, orderLocale = body.getString("playerUsername")?.let { manualOrderLocale(plugin, it) })
        val quote = service.quoteManual(request, plugin.applicationContext.getBean(DatabaseManager::class.java).getSqlClient())

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("quote" to quote.toJson()))
    }
}
