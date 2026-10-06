package com.panomc.plugins.market.routes.panel.refund

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.refund.RefundMath
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.dao.MarketCreatorEarningDao
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketRefundItemDao
import com.panomc.plugins.market.db.dao.MarketServerStateDao
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.job.RefundReconcileJob
import com.panomc.plugins.market.log.RefundedMarketOrderLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.api.order.deliveryService
import com.panomc.plugins.market.routes.api.order.entitlementService
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.webhookService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseIdempotencyKey
import com.panomc.plugins.market.routes.panel.invoice.invoiceService
import com.panomc.plugins.market.routes.panel.order.actingUserId
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.routes.panel.order.panelOrder
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.service.PaymentServiceRefundGateway
import com.panomc.plugins.market.service.RefundInput
import com.panomc.plugins.market.service.RefundOutcome
import com.panomc.plugins.market.service.RefundService
import com.panomc.plugins.market.service.StandardRefundEffects
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool
import java.math.BigDecimal

private object RefundWiringHolder

@Volatile
private var cachedService: Pair<MarketPlugin, RefundService>? = null

@Volatile
private var cachedJob: Pair<MarketPlugin, RefundReconcileJob>? = null

/** The refund flow on the plugin's beans (MK-111); one per plugin instance. */
internal fun refundService(plugin: MarketPlugin): RefundService {
    cachedService?.takeIf { it.first === plugin }?.let { return it.second }

    // built outside the lock: it reaches into the payment, delivery and invoice wiring
    val built = buildRefundService(plugin)

    return synchronized(RefundWiringHolder) { cachedService?.takeIf { it.first === plugin }?.second ?: built.also { cachedService = plugin to it } }
}

/** `RefundReconcileJob` for `MarketScheduler`: the `revokeFirst` release and timeout, `queryRefund`, the `SYSTEM` rows nobody sent yet. */
internal fun refundReconcileJob(plugin: MarketPlugin): RefundReconcileJob {
    cachedJob?.takeIf { it.first === plugin }?.let { return it.second }

    val built = RefundReconcileJob(refundService(plugin))

    return synchronized(RefundWiringHolder) { cachedJob?.takeIf { it.first === plugin }?.second ?: built.also { cachedJob = plugin to it } }
}

private fun buildRefundService(plugin: MarketPlugin): RefundService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val config = { currentConfig(plugin) }
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    val effects = StandardRefundEffects(
        clock = SystemClock, earnings = context.getBean(MarketCreatorEarningDao::class.java), tablePrefix = { orderDao.prefix() }, invoices = invoiceService(plugin),
        mailOutbox = MailOutboxService(config, SystemClock, context.getBean(MarketMailOutboxDao::class.java), context.getBean(MarketOrderEventDao::class.java)),
        users = PlatformUserDirectory(databaseManager), webhooks = webhookService(plugin)
    )

    return RefundService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, clock = SystemClock, config = config, orders = orderDao,
        orderItems = context.getBean(MarketOrderItemDao::class.java), orderEvents = context.getBean(MarketOrderEventDao::class.java),
        payments = context.getBean(MarketPaymentDao::class.java), refunds = context.getBean(MarketRefundDao::class.java),
        refundItems = context.getBean(MarketRefundItemDao::class.java), deliveries = context.getBean(MarketDeliveryDao::class.java),
        entitlements = context.getBean(MarketEntitlementDao::class.java), creditTxs = context.getBean(MarketCreditTxDao::class.java), credits = creditService(plugin),
        deliveryService = deliveryService(plugin), entitlementService = entitlementService(plugin),
        gateway = PaymentServiceRefundGateway(paymentService(plugin)) { databaseManager().getSqlClient() },
        servers = context.getBean(MarketServerStateDao::class.java), effects = effects
    )
}

private fun jsonBody(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()

/** [raw] (a number or a numeric text) as money x 100: at most two decimals; a value that is not a number is a 400. */
internal fun parseMoney(raw: Any?, name: String): Long {
    val number = when (raw) {
        is Int, is Long, is Short, is Byte -> BigDecimal(raw.toString())
        is Double -> if (raw.isFinite()) BigDecimal(raw.toString()) else null
        is Float -> if (raw.isFinite()) BigDecimal(raw.toString()) else null
        is BigDecimal -> raw
        is String -> raw.trim().toBigDecimalOrNull()
        else -> null
    } ?: throw RequestValueException(name, "MUST_BE_A_NUMBER")

    if (number.stripTrailingZeros().scale() > 2) throw RequestValueException(name, "PRECISION")

    return try {
        number.movePointRight(2).longValueExact()
    } catch (e: ArithmeticException) {
        throw RequestValueException(name, "OUT_OF_RANGE")
    }
}

private fun parseItems(raw: Any?): List<RefundMath.ItemRequest>? {
    val array = when (raw) {
        null -> return null
        is JsonArray -> raw
        is String -> runCatching { JsonArray(raw) }.getOrNull() ?: throw RequestValueException("items", "MUST_BE_A_JSON_ARRAY")
        else -> throw RequestValueException("items", "MUST_BE_A_JSON_ARRAY")
    }

    return array.map { entry ->
        val o = entry as? JsonObject ?: throw RequestValueException("items", "MUST_BE_AN_OBJECT")
        val quantity = (o.getValue("quantity") as? Number)?.toInt() ?: throw RequestValueException("items", "QUANTITY_REQUIRED")

        RefundMath.ItemRequest(parseBodyId(o.getValue("orderItemId")), quantity)
    }
}

private fun parseBodyId(raw: Any?): Long = com.panomc.plugins.market.routes.base.parseBodyId(raw, "orderItemId")

private fun flag(raw: Any?, name: String): Boolean? = when (raw) {
    null -> null
    is Boolean -> raw
    is String -> raw.toBooleanStrictOrNull() ?: throw RequestValueException(name, "MUST_BE_A_BOOLEAN")
    else -> throw RequestValueException(name, "MUST_BE_A_BOOLEAN")
}

/** The body of the refund request and the query of the preview share one shape (04 section 7). */
internal fun parseRefundInput(source: (String) -> Any?): RefundInput = RefundInput(
    amount = source("amount")?.let { parseMoney(it, "amount") },
    items = parseItems(source("items")),
    gatewayAmount = source("gatewayAmount")?.let { parseMoney(it, "gatewayAmount") },
    creditAmount = source("creditAmount")?.let { parseMoney(it, "creditAmount") },
    reason = (source("reason") as? String)?.trim()?.takeIf { it.isNotEmpty() },
    revoke = flag(source("revoke"), "revoke"),
    revokeFirst = flag(source("revokeFirst"), "revokeFirst") ?: false,
    cascadeUpgrade = flag(source("cascadeUpgrade"), "cascadeUpgrade"),
    restock = flag(source("restock"), "restock") ?: false,
    manual = flag(source("manual"), "manual") ?: false
)

private fun refundView(refund: MarketRefund): JsonObject = JsonObject()
    .put("id", refund.id).put("status", refund.status.name).put("gatewayAmount", refund.gatewayAmount / 100.0).put("creditAmount", refund.creditAmount / 100.0)
    .put("buyerActionUrl", refund.buyerActionUrl)

/** A gateway that refused this very call answers 502 `PAYMENT_PROVIDER_ERROR`; the row stays `FAILED` (it was committed before the answer). */
private fun answer(outcome: RefundOutcome): Result {
    outcome.failure?.let { throw PaymentProviderError(it.code) }

    return Successful(mapOf("refund" to refundView(outcome.refund)))
}

/** `GET /api/panel/market/orders/:id/refund-preview` (`P:PAY`, 04 section 7, 07 section 7.3): what a refund would write, without writing. */
@Endpoint
class PanelRefundPreviewAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/refund-preview", RouteType.GET))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("amount", "items", "gatewayAmount", "creditAmount", "revoke", "manual", "cascadeUpgrade")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val request = context.request()
        val input = parseRefundInput { name -> request.getParam(name) }

        return Successful(refundService(plugin).preview(order.id, input).toJson().map)
    }
}

/**
 * `POST /api/panel/market/orders/:id/refunds` (`P:PAY`, `Idempotency-Key`, 04 section 7, 21 section 3): `refund{id, status, gatewayAmount, creditAmount, buyerActionUrl}`;
 * 400 `INVALID_REFUND_AMOUNT`, `REFUND_NOT_SUPPORTED`, `CASCADE_DECISION_REQUIRED`, `INVALID_ORDER_TRANSITION`; 409 `IDEMPOTENCY_CONFLICT`; 502 `PAYMENT_PROVIDER_ERROR`.
 */
@Endpoint
class PanelCreateRefundAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/refunds", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val key = parseIdempotencyKey(context.request().getHeader("Idempotency-Key"), required = true)!!
        val order = panelOrder(plugin, context)
        val body = jsonBody(context)
        val input = parseRefundInput { name -> body.getValue(name) }
        val actor = actingUserId(plugin, context)
        val outcome = refundService(plugin).request(order.id, input, key, actor)

        if (!outcome.replay) {
            logOrderDecision(plugin, context) { userId, username ->
                RefundedMarketOrderLog(userId, username, plugin.pluginId, order.id, outcome.refund.id, outcome.refund.amount / 100.0, input.manual)
            }
        }

        return answer(outcome)
    }
}

private suspend fun <T> found(block: suspend () -> T): T = try {
    block()
} catch (e: NoSuchElementException) {
    throw NotFound()
}

/** `POST /api/panel/market/refunds/:refundId/retry` (`P:PAY`, 21 section 3.3): the same key and amounts again; `refund`; 409 `INVALID_STATE`. */
@Endpoint
class PanelRetryRefundAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/refunds/:refundId/retry", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        answer(found { refundService(plugin).retry(parseId(context.pathParam("refundId"), "refundId")) })
}

/** `POST /api/panel/market/refunds/:refundId/cancel` (`P:PAY`, 21 section 3.3): a refund nothing is running for; `refund`; 409 `INVALID_STATE`. */
@Endpoint
class PanelCancelRefundAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/refunds/:refundId/cancel", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        answer(found { refundService(plugin).cancel(parseId(context.pathParam("refundId"), "refundId")) })
}
