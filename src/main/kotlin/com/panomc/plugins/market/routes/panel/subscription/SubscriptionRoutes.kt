package com.panomc.plugins.market.routes.panel.subscription

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.subscription.CancelActor
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.CancelledMarketSubscriptionLog
import com.panomc.plugins.market.log.RetriedMarketSubscriptionChargeLog
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.routes.base.parseText
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.routes.user.subscription.SubscriptionFilter
import com.panomc.plugins.market.service.CancelOutcome
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

private fun number(raw: String?, name: String): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return value.toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_AN_INTEGER")
}

/** `status` (csv) of the list: an unknown name is a 400, never an empty list. */
internal fun parseStatuses(raw: String?): Set<SubscriptionStatus> =
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { name ->
        SubscriptionStatus.entries.firstOrNull { it.name == name } ?: throw RequestValueException("status", "UNKNOWN_STATUS")
    }?.toSet().orEmpty()

/** `GET /api/panel/market/subscriptions` (`P:OV`, 09 section 13): q `status` (csv), `mode`, `providerId`, `search`, `page`, `pageSize`; `subscriptions`, `subscriptionCount`, `totalPage`. */
@Endpoint
class PanelGetSubscriptionsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/subscriptions", RouteType.GET))

    override val nodes: Set<MarketNode> = setOf(MarketNode.ORDERS_VIEW)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("status", "mode", "providerId", "search", "page", "pageSize")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = context.request()
        val window: Paging.Window = parsePagingRequest(number(request.getParam("page"), "page"), number(request.getParam("pageSize"), "pageSize"))
        val mode = request.getParam("mode")?.trim()?.takeIf { it.isNotEmpty() }?.let { name -> SubscriptionMode.entries.firstOrNull { it.name == name } ?: throw RequestValueException("mode", "UNKNOWN_MODE") }
        val filter = SubscriptionFilter(parseStatuses(request.getParam("status")), mode, parseText(request.getParam("providerId"), "providerId", maxLength = 64), parseText(request.getParam("search"), "search"))
        val client = plugin.applicationContext.getBean(DatabaseManager::class.java).getSqlClient()
        val page = subscriptionViews(plugin).panelList(filter, window, client, searchEmail = FieldGating.piiTier(context))

        if (Paging.isBeyondLast(window.page, page.totalPage)) throw PageNotFound()

        return Successful(mapOf("subscriptions" to page.rows, "subscriptionCount" to page.count, "totalPage" to page.totalPage))
    }
}

/** `GET /api/panel/market/subscriptions/:id` (`P:OV`, 09 section 13): `subscription`, `renewals[]`, `orders[]`, `allowed{cancel, retry}`; no `storedMethod`, no `providerData`. */
@Endpoint
class PanelGetSubscriptionAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/subscriptions/:id", RouteType.GET))

    override val nodes: Set<MarketNode> = setOf(MarketNode.ORDERS_VIEW)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val client = plugin.applicationContext.getBean(DatabaseManager::class.java).getSqlClient()
        val id = parseId(context.pathParam("id"))
        val detail = subscriptionViews(plugin).panelDetail(id, client, pii = FieldGating.piiTier(context)) ?: throw NotFound()

        return Successful(detail.map)
    }
}

/**
 * `POST /api/panel/market/subscriptions/:id/cancel` (`P:PAY`, 09 section 10.1): `atPeriodEnd?` (default `true`), `reason?` (at most 255, kept in the order event and the
 * activity log); `{}`. A terminal row whose remote cancel gave up (`FAILED`) is put back in the queue. 409 `SUBSCRIPTION_NOT_CANCELLABLE`; 502 `PAYMENT_PROVIDER_ERROR`.
 */
@Endpoint
class PanelCancelSubscriptionAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/subscriptions/:id/cancel", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(false).optionalProperty("atPeriodEnd", booleanSchema()).optionalProperty("reason", stringSchema())))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val body = context.body().asJsonObject() ?: JsonObject()
        val atPeriodEnd = body.getBoolean("atPeriodEnd") ?: true
        val reason = parseText(body.getString("reason"), "reason")

        // the admin's own gateway-side decision: a buyer-only gateway (`BuyerActionRequired`) cannot be cancelled by the store
        when (val outcome = subscriptionActions(plugin).cancel(id, CancelActor.ADMIN, null, atPeriodEnd, reason)) {
            is CancelOutcome.Done -> Unit

            is CancelOutcome.Redirect -> throw com.panomc.plugins.market.error.PaymentProviderError("UNSUPPORTED")
        }

        logOrderDecision(plugin, context) { userId, username -> CancelledMarketSubscriptionLog(userId, username, plugin.pluginId, id, atPeriodEnd) }

        return Successful(emptyMap<String, Any?>())
    }
}

/**
 * `POST /api/panel/market/subscriptions/:id/retry` (`P:PAY`, 09 section 9.3): retry a failed renewal now. `MERCHANT` always (the charge of 09 section 8.3 runs at once),
 * `GATEWAY` with `capabilities.recurringRetry`; `{}`. A declined charge is not an error (it is in the subscription). 409 `SUBSCRIPTION_NOT_RETRYABLE`; 502 `PAYMENT_PROVIDER_ERROR`.
 */
@Endpoint
class PanelRetrySubscriptionAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/subscriptions/:id/retry", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))

        subscriptionActions(plugin).retry(id) { subscriptionJob(plugin).chargeOne(it, admin = true) }

        logOrderDecision(plugin, context) { userId, username -> RetriedMarketSubscriptionChargeLog(userId, username, plugin.pluginId, id) }

        return Successful(emptyMap<String, Any?>())
    }
}
