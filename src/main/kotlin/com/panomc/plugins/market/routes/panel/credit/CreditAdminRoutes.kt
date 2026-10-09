package com.panomc.plugins.market.routes.panel.credit

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.runtime.beans
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool

private object CreditAdminWiringHolder

@Volatile
private var cachedAdmin: Pair<MarketPlugin, CreditAdminService>? = null

/** The panel credit logic on the plugin's beans; one per plugin instance. */
internal fun creditAdminService(plugin: MarketPlugin): CreditAdminService {
    cachedAdmin?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(CreditAdminWiringHolder) {
        cachedAdmin?.takeIf { it.first === plugin }?.second ?: buildCreditAdminService(plugin).also { cachedAdmin = plugin to it }
    }
}

private fun buildCreditAdminService(plugin: MarketPlugin): CreditAdminService {
    val databaseManager = { plugin.applicationContext.getBean(DatabaseManager::class.java) }
    val context = plugin.beans

    return CreditAdminService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), credits = creditService(plugin),
        accounts = context.getBean(MarketCreditAccountDao::class.java), txs = context.getBean(MarketCreditTxDao::class.java),
        prefix = { MarketTables.prefixOverride ?: databaseManager().getTablePrefix() }, client = { databaseManager().getSqlClient() }
    )
}

/** Shared parts of the credit routes: all `P:PAY` (04 section 7), the acting user and the activity log. */
abstract class CreditAdminRoute(protected val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    internal val service: CreditAdminService get() = creditAdminService(plugin)

    protected fun userIdOf(context: RoutingContext): Long = parseId(context.pathParam("userId"), "userId")

    protected fun window(context: RoutingContext): PageRequest = Paging.request(context)

    protected suspend fun log(context: RoutingContext, build: (userId: Long, username: String) -> com.panomc.platform.db.model.PluginActivityLog) {
        val databaseManager = plugin.applicationContext.getBean(DatabaseManager::class.java)
        val client = databaseManager.getSqlClient()
        val userId = plugin.applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, client)!!

        databaseManager.panelActivityLogDao.add(build(userId, username), client)
    }

    protected suspend fun actor(context: RoutingContext): Long =
        plugin.applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)

    protected fun pagingValidation(schemaRepository: SchemaRepository, vararg extra: String): ValidationHandler {
        var builder = Paging.params(ValidationHandlerBuilder.create(schemaRepository))

        for (name in extra) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }
}

/** `GET /api/panel/market/credits/accounts` (`P:PAY`): `items[{userId, username, balance}]`, `page`, `totals`. */
@Endpoint
class PanelGetCreditAccountsAPI(plugin: MarketPlugin) : CreditAdminRoute(plugin) {
    override val paths = listOf(Path("/credits/accounts", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository, "search")

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        Successful(service.accountList(context.request().getParam("search"), window(context)).map)
}

/** `GET /api/panel/market/credits/accounts/:userId` (`P:PAY`): `balance`, `items[]` (the entries), `page`; 404 for an unknown user. */
@Endpoint
class PanelGetCreditAccountAPI(plugin: MarketPlugin) : CreditAdminRoute(plugin) {
    override val paths = listOf(Path("/credits/accounts/:userId", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        Successful(service.accountDetail(userIdOf(context), window(context)).map)
}

/** `GET /api/panel/market/credits/transactions` (`P:PAY`): the global ledger with `type?` (csv), `userId?`, `orderId?`, `from?`, `to?`. */
@Endpoint
class PanelGetCreditTransactionsAPI(plugin: MarketPlugin) : CreditAdminRoute(plugin) {
    override val paths = listOf(Path("/credits/transactions", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        pagingValidation(schemaRepository, "type", "userId", "orderId", "from", "to")

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = context.request()
        val filter = parseCreditTxFilter(request.getParam("type"), request.getParam("userId"), request.getParam("orderId"), request.getParam("from"), request.getParam("to"))

        return Successful(service.transactions(filter, window(context)).map)
    }
}

private fun body(schemaRepository: SchemaRepository): ValidationHandler =
    ValidationHandlerBuilder.create(schemaRepository)
        .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
        .predicate(RequestPredicate.BODY_REQUIRED)
        .build()

/** `POST /api/panel/market/credits/accounts/:userId/grant` (`P:PAY`, `Idempotency-Key`): `{balance, shortfall}`; 400 `INVALID_CREDIT_AMOUNT`; 404; 409 `IDEMPOTENCY_CONFLICT`. */
@Endpoint
class PanelGrantCreditsAPI(plugin: MarketPlugin) : CreditAdminRoute(plugin) {
    override val paths = listOf(Path("/credits/accounts/:userId/grant", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = body(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = parseCreditMove(CreditTxType.GRANT, context.request().getHeader("Idempotency-Key"), context.body().asJsonObject() ?: JsonObject())
        val movement = service.move(request, userIdOf(context), actor(context))

        if (!movement.replayed) log(context) { u, n -> GrantedMarketCreditsLog(u, n, plugin.pluginId, movement.username, movement.moved) }

        return Successful(movement.toJson().map)
    }
}

/** `POST /api/panel/market/credits/accounts/:userId/revoke` (`P:PAY`, `Idempotency-Key`): takes what is spendable, the rest is the `shortfall`. */
@Endpoint
class PanelRevokeCreditsAPI(plugin: MarketPlugin) : CreditAdminRoute(plugin) {
    override val paths = listOf(Path("/credits/accounts/:userId/revoke", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = body(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = parseCreditMove(CreditTxType.REVOKE, context.request().getHeader("Idempotency-Key"), context.body().asJsonObject() ?: JsonObject())
        val movement = service.move(request, userIdOf(context), actor(context))

        if (!movement.replayed) log(context) { u, n -> RevokedMarketCreditsLog(u, n, plugin.pluginId, movement.username, movement.moved, movement.shortfall) }

        return Successful(movement.toJson().map)
    }
}
