package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.core.webhook.TargetPolicy
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.db.dao.MarketCreatorEarningDao
import com.panomc.plugins.market.db.dao.MarketCreatorPayoutDao
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.CreatorPayoutMethod
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.api.order.deliveryService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseIdempotencyKey
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.routes.panel.order.actingUserId
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.routes.panel.product.RoutingCaller
import com.panomc.plugins.market.routes.panel.refund.parseMoney
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.CreatorPageOutOfRange
import com.panomc.plugins.market.service.CreatorPayoutInput
import com.panomc.plugins.market.service.CreatorService
import com.panomc.plugins.market.service.platform.PlatformServerRoster
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

private object CreatorWiringHolder

@Volatile
private var cachedCreators: Pair<MarketPlugin, CreatorService>? = null

/** The creator earnings and payouts on the plugin's beans (MK-114); one per plugin instance. */
internal fun creatorService(plugin: MarketPlugin): CreatorService {
    cachedCreators?.takeIf { it.first === plugin }?.let { return it.second }

    // built outside the lock: it reaches into the credit and delivery wiring
    val built = buildCreatorService(plugin)

    return synchronized(CreatorWiringHolder) { cachedCreators?.takeIf { it.first === plugin }?.second ?: built.also { cachedCreators = plugin to it } }
}

private fun buildCreatorService(plugin: MarketPlugin): CreatorService {
    val context = plugin.beans
    val databaseManager = { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    return CreatorService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), clock = SystemClock, config = { currentConfig(plugin) },
        earnings = context.getBean(MarketCreatorEarningDao::class.java), payouts = context.getBean(MarketCreatorPayoutDao::class.java), credits = creditService(plugin),
        deliveries = deliveryService(plugin), users = PlatformUserDirectory(databaseManager),
        prefix = { MarketTables.prefixOverride ?: databaseManager().getTablePrefix() }, client = { databaseManager().getSqlClient() }
    )
}

/** The routes of the creator views and payouts: `P:DISC` or `P:PAY` read, `P:PAY` moves money (04 section 6, 11 section 14.3). */
abstract class CreatorRoute(protected val plugin: MarketPlugin, readOnly: Boolean) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = if (readOnly) setOf(MarketNode.DISCOUNTS, MarketNode.PAYMENTS) else setOf(MarketNode.PAYMENTS)

    internal val service: CreatorService get() = creatorService(plugin)

    protected suspend fun <T> paged(block: suspend () -> T): T = try {
        block()
    } catch (e: CreatorPageOutOfRange) {
        throw PageNotFound()
    }

    protected fun longParam(context: RoutingContext, name: String): Long? =
        context.request().getParam(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_AN_INTEGER") }

    protected fun pagingValidation(schemaRepository: SchemaRepository, vararg extra: String): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("page", "pageSize") + extra) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }
}

/** `GET /api/panel/market/creator-codes/report` (`P:DISC` or `P:PAY`): q `from?`, `to?` (epoch ms); `creators[{id, creator, code, uses, revenue, earned, pending, reversed, paidOut, available}]`, `currency`. */
@Endpoint
class PanelGetCreatorReportAPI(plugin: MarketPlugin) : CreatorRoute(plugin, readOnly = true) {
    override val paths = listOf(Path("/api/panel/market/creator-codes/report", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("from", stringSchema()))
            .queryParameter(optionalParam("to", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result = Successful(service.report(longParam(context, "from"), longParam(context, "to")).map)
}

/** `GET /api/panel/market/creator-codes/:id/earnings` (`P:DISC` or `P:PAY`): q `page?`, `pageSize?`, `state?`; `earnings[]`, `earningCount`, `totalPage`; 404 for an unknown code. */
@Endpoint
class PanelGetCreatorEarningsAPI(plugin: MarketPlugin) : CreatorRoute(plugin, readOnly = true) {
    override val paths = listOf(Path("/api/panel/market/creator-codes/:id/earnings", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository, "state")

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val state = context.request().getParam("state")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            CreatorEarningState.values().firstOrNull { it.name == raw } ?: throw RequestValueException("state", "INVALID")
        }
        val window = parsePagingRequest(longParam(context, "page"), longParam(context, "pageSize"))

        return Successful(paged { service.earningsOf(id, state, window) }.map)
    }
}

/** `GET /api/panel/market/creator-codes/:id/payouts` (`P:DISC` or `P:PAY`): `payouts[{id, amount, currency, method, state, note, paidBy, paidAt}]`; 404 for an unknown code. */
@Endpoint
class PanelGetCreatorPayoutsAPI(plugin: MarketPlugin) : CreatorRoute(plugin, readOnly = true) {
    override val paths = listOf(Path("/api/panel/market/creator-codes/:id/payouts", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result = Successful(service.payoutsOf(parseId(context.pathParam("id"))).map)
}

/**
 * `POST /api/panel/market/creator-codes/:id/payouts` (`P:PAY`, `Idempotency-Key`, 21 section 7.4, 07 section 12): `amount*` (> 0, at most the available balance),
 * `method*` (`CREDIT` | `ACTION` | `MANUAL`), `actions[]` (`ACTION`: [PayoutActionRules], `ActionGuard` applies), `note` (`MANUAL`: required); `{id}`. 400 `INVALID_PAYOUT_AMOUNT
 * {available}`, `CREATOR_HAS_NO_ACCOUNT`; 403 `NO_PERMISSION`; 404; 409 `IDEMPOTENCY_CONFLICT`, `CREDITS_DISABLED`.
 */
@Endpoint
class PanelCreateCreatorPayoutAPI(plugin: MarketPlugin) : CreatorRoute(plugin, readOnly = false) {
    override val paths = listOf(Path("/api/panel/market/creator-codes/:id/payouts", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val key = parseIdempotencyKey(context.request().getHeader("Idempotency-Key"), required = true)!!
        val codeId = parseId(context.pathParam("id"))
        val body = context.body().asJsonObject() ?: JsonObject()
        val amount = body.getValue("amount")?.let { parseMoney(it, "amount") } ?: throw RequestValueException("amount", "REQUIRED")
        val methodName = body.getValue("method") as? String ?: throw RequestValueException("method", "REQUIRED")
        val method = CreatorPayoutMethod.values().firstOrNull { it.name == methodName } ?: throw RequestValueException("method", "INVALID")
        val note = when (val raw = body.getValue("note")) {
            null -> null
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.also { if (it.length > MAX_NOTE) throw RequestValueException("note", "TOO_LONG") }
            else -> throw RequestValueException("note", "INVALID")
        }

        if (method == CreatorPayoutMethod.MANUAL && note == null) throw RequestValueException("note", "REQUIRED")

        if (method != CreatorPayoutMethod.ACTION && body.getValue("actions") != null) throw RequestValueException("actions", "NOT_ALLOWED")

        val actions = if (method == CreatorPayoutMethod.ACTION) checkActions(context, body.getValue("actions")) else null
        val actor = actingUserId(plugin, context)
        val outcome = service.requestPayout(codeId, CreatorPayoutInput(amount, method, actions, note), key, actor)

        if (!outcome.replay) {
            logOrderDecision(plugin, context) { userId, username ->
                CreatedMarketCreatorPayoutLog(userId, username, plugin.pluginId, outcome.creatorCode, outcome.payout.amount, method.name)
            }
        }

        return Successful(mapOf("id" to outcome.payout.id))
    }

    /** The canonical text of the validated actions; 400 `BAD_REQUEST {actions}` for a rule that failed, 403 for an action the caller may not run. */
    private suspend fun checkActions(context: RoutingContext, submitted: Any?): String {
        val databaseManager = plugin.applicationContext.getBean(DatabaseManager::class.java)
        val roster = PlatformServerRoster({ databaseManager }) { plugin.applicationContext.getBean(com.panomc.platform.server.ServerManager::class.java) }.snapshot(databaseManager.getSqlClient())
        val allowPrivate = currentConfig(plugin).allowPrivateWebhookTargets
        val verdict = PayoutActionRules.check(
            submitted, RoutingCaller(plugin, context), roster.granted.toSet(), roster.granted.isNotEmpty(),
            webhookUrlOk = { url -> TargetPolicy.syntaxOk(url, TargetPolicy.effectiveAllowPrivate(allowPrivate, com.panomc.platform.hosted.HostedEnvConfig.current.isHosted)) }
        )

        return when (verdict) {
            is PayoutActionRules.Verdict.Ok -> verdict.canonical
            is PayoutActionRules.Verdict.Invalid -> throw RequestValueException("actions", verdict.reason)
            PayoutActionRules.Verdict.Forbidden -> throw NoPermission()
        }
    }

    private companion object {
        const val MAX_NOTE = 255
    }
}

/** `POST /api/panel/market/creator-payouts/:id/cancel` (`P:PAY`, 21 section 7.4): a `PENDING` (rows not settled) or `FAILED` payout; `{}`; 404; 409 `INVALID_STATE`. */
@Endpoint
class PanelCancelCreatorPayoutAPI(plugin: MarketPlugin) : CreatorRoute(plugin, readOnly = false) {
    override val paths = listOf(Path("/api/panel/market/creator-payouts/:id/cancel", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val outcome = service.cancelPayout(parseId(context.pathParam("id")))

        logOrderDecision(plugin, context) { userId, username ->
            CancelledMarketCreatorPayoutLog(userId, username, plugin.pluginId, outcome.creatorCode, outcome.payout.amount)
        }

        return Successful()
    }
}
