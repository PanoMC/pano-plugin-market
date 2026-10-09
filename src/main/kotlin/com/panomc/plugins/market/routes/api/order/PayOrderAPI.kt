package com.panomc.plugins.market.routes.api.order

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.plugins.market.error.OrderNotPayable
import com.panomc.plugins.market.error.OrderNotCancellable
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.api.checkout.useCredits
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.routes.panel.block.blockListService
import com.panomc.plugins.market.service.PayRequest
import com.panomc.plugins.market.service.UseCredits
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

private val PAY_KEYS = setOf("paymentMethodId", "useCredits", "billingInfo")

/**
 * The body of `POST /orders/:publicId/pay` (04 section 3): `paymentMethodId` (required, at most 64 characters), `useCredits` (a number of
 * credits with at most two decimals; omitted keeps the credit part; `"MAX"` is a quote value and refused here), `billingInfo` (an object). A key
 * outside the contract is refused (PT-1).
 */
internal fun parsePayRequest(body: JsonObject): PayRequest {
    for (key in body.fieldNames()) if (key !in PAY_KEYS) throw RequestValueException(key, "UNKNOWN_FIELD")

    val method = (body.getValue("paymentMethodId") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
        ?: throw RequestValueException("paymentMethodId", "REQUIRED")
    val credits = when (val use = useCredits(body.getValue("useCredits"))) {
        null -> null
        is UseCredits.Max -> throw RequestValueException("useCredits", "MAX_IS_QUOTE_ONLY")
        is UseCredits.Amount -> use.credits
    }
    val billing = when (val raw = body.getValue("billingInfo")) {
        null -> null
        is JsonObject -> raw
        is Map<*, *> -> JsonObject(raw.entries.associate { it.key.toString() to it.value })
        else -> throw RequestValueException("billingInfo", "MUST_BE_AN_OBJECT")
    }

    return PayRequest(method, credits, billing)
}

/**
 * `POST /api/market/orders/:publicId/pay` (04 section 3, 06 section 9.3; owner only, `PUB-M`): a new attempt for a `PENDING` order, the fee and
 * the credit part re-priced for the chosen method. Answers `{payment: PaymentStart}` (`null` when another request took the attempt over). Limiter L1.
 */
@Endpoint
class PayOrderAPI(private val plugin: MarketPlugin) : MarketPublicMutationApi() {
    override val paths = listOf(Path("/orders/:publicId/pay", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Starts a payment for a pending order with the chosen method.",
        tag = "orders",
        response = MarketSchemas.paymentAnswer,
        errors = listOf(NotFound::class, BadRequest::class, OrderNotPayable::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val limiter = OrderMutationLimiter { com.panomc.plugins.market.routes.panel.settings.currentConfig(plugin).checkoutRateLimitPerMinute }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("publicId", stringSchema()))
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()
        val order = resolveOrder(plugin, context, getParameters(context).pathParameter("publicId").string, sqlClient).requireOwner()
        val caller = payCaller(plugin, context)

        limiter.check(caller.clientIp, order.buyerKey)
        blockListService(plugin).requireOrderBuyerAllowed(order, caller.clientIp, sqlClient)

        val request = parsePayRequest(getParameters(context).body().jsonObject)
        val payment = paymentService(plugin).pay(order, request, caller, sqlClient)

        noStore(context)

        return Successful(mapOf("payment" to payment))
    }
}

/** `POST /api/market/orders/:publicId/payment/continue` (04 section 3, 06 section 9.3): `values{}`, the second step of an `EMBEDDED` form. */
@Endpoint
class ContinueOrderPaymentAPI(private val plugin: MarketPlugin) : MarketPublicMutationApi() {
    override val paths = listOf(Path("/orders/:publicId/payment/continue", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Continues the open payment attempt of an order.",
        tag = "orders",
        response = MarketSchemas.paymentAnswer,
        errors = listOf(NotFound::class, OrderNotPayable::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val limiter = OrderMutationLimiter { com.panomc.plugins.market.routes.panel.settings.currentConfig(plugin).checkoutRateLimitPerMinute }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("publicId", stringSchema()))
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()
        val order = resolveOrder(plugin, context, getParameters(context).pathParameter("publicId").string, sqlClient).requireOwner()
        val caller = payCaller(plugin, context)

        limiter.check(caller.clientIp, order.buyerKey)
        blockListService(plugin).requireOrderBuyerAllowed(order, caller.clientIp, sqlClient)

        val body = getParameters(context).body().jsonObject
        val values = body.getValue("values") as? JsonObject ?: throw BadRequest()
        val payment = paymentService(plugin).continuePayment(order, values, caller, sqlClient)

        noStore(context)

        return Successful(mapOf("payment" to payment))
    }
}

/** `POST /api/market/orders/:publicId/cancel` (04 section 3, 06 section 11 O7; owner only, `PUB-M`): `{}`; 409 `ORDER_NOT_CANCELLABLE`. */
@Endpoint
class CancelOrderAPI(private val plugin: MarketPlugin) : MarketPublicMutationApi() {
    override val paths = listOf(Path("/orders/:publicId/cancel", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Cancels a pending order.",
        tag = "orders",
        response = MarketSchemas.empty(),
        errors = listOf(NotFound::class, OrderNotCancellable::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("publicId", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()
        val order = resolveOrder(plugin, context, getParameters(context).pathParameter("publicId").string, sqlClient).requireOwner()

        paymentService(plugin).cancel(order, sqlClient)
        noStore(context)

        return Successful(emptyMap<String, Any?>())
    }
}
