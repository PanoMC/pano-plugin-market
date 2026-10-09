package com.panomc.plugins.market.routes.user.gift

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.error.NotFound
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.BadRequest
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.core.cart.CartLineParser
import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.routes.api.checkout.abuseWiring
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.api.checkout.InstalledLocale
import com.panomc.plugins.market.routes.api.checkout.OrderLocale
import com.panomc.plugins.market.routes.api.checkout.checkoutService
import com.panomc.plugins.market.routes.api.checkout.quoteCaller
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.RedemptionService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/** The keys of a redeem body; anything else is refused (11 PT-1 style: nothing else is read, nothing else is accepted). */
internal val REDEEM_KEYS = setOf("code", "targetServerId", "fieldValues")

/** A redeem body as the route reads it. */
internal class RedeemRequest(val code: String, val targetServerId: Long?, val fieldValues: Map<String, Any?>)

/** Pure parsing of the redeem body: `code*` (a string), `targetServerId?` (an id), `fieldValues?` (an object of strings, numbers and booleans). */
internal fun parseRedeemRequest(body: JsonObject): RedeemRequest {
    body.fieldNames().firstOrNull { it !in REDEEM_KEYS }?.let { throw RequestValueException(it, "UNKNOWN_FIELD") }

    val code = body.getValue("code") as? String ?: throw RequestValueException("code", if (body.getValue("code") == null) "REQUIRED" else "MUST_BE_TEXT")

    if (code.length > MAX_RAW_CODE) throw RequestValueException("code", "TOO_LONG")

    val server = body.getValue("targetServerId")?.let { parseBodyId(it, "targetServerId") }
    val rawValues = body.getValue("fieldValues").let { if (it is JsonObject) it.map else it }
    val values = CartLineParser.fieldValues(rawValues) ?: throw RequestValueException("fieldValues", "INVALID")

    return RedeemRequest(code, server, CartLineKey.normalize(values))
}

private const val MAX_RAW_CODE = 256

/**
 * `POST /api/market/me/gifts/redeem` (`USER`, 04 section 4, 21 section 6): `{code, targetServerId?, fieldValues?}` in, `{order}` out (the zero-total
 * `GIFT_CODE` order, completed). 400 `INVALID_GIFT_CODE {reason}`, 429 `CODE_ATTEMPTS_LOCKED`. The answer is never cached.
 */
@Endpoint
class RedeemGiftAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/me/gifts/redeem", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Redeems a gift code and answers the order it creates.",
        tag = "me",
        response = objectSchema().requiredProperty("order", objectSchema()),
        errors = listOf(BadRequest::class, NotFound::class, NotLoggedIn::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy {
        val context = plugin.beans
        val orders = context.getBean(MarketOrderDao::class.java)
        val redemptionDao = context.getBean(MarketRedemptionDao::class.java)
        val locks = Locks(orders, context.getBean(MarketOrderItemDao::class.java), redemptionDao, context.getBean(MarketCreditAccountDao::class.java))

        GiftRedeemService(
            checkout = checkoutService(plugin, withCheckout = true), redemptions = RedemptionService(SystemClock, locks, redemptionDao),
            client = { databaseManager.getSqlClient() }, clock = SystemClock,
            guard = abuseWiring(plugin).codeGuard.forScope(AbuseLimits.SCOPE_GIFT).let { guard -> { guard } },
            limit = { caller -> abuseWiring(plugin).rateLimits.checkout(caller.clientIp, "u:${caller.userId}") }
        )
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val request = parseRedeemRequest(getParameters(context).body().jsonObject)
        val caller = quoteCaller(plugin, context)

        context.response().putHeader("Cache-Control", "no-store")

        val result = service.redeem(request.code, request.targetServerId, request.fieldValues, caller, orderLocaleOf(context, caller.userId))

        return Successful(mapOf("order" to result.order))
    }

    /** 06 section 5.4 without a body locale: the user's stored locale, `Accept-Language`, the site default. */
    private suspend fun orderLocaleOf(context: RoutingContext, userId: Long?): String {
        val sqlClient = databaseManager.getSqlClient()
        val installed = databaseManager.localeDao.getAll(sqlClient).map { InstalledLocale(it.code, it.derivatives) }
        val stored = userId?.let { databaseManager.userDao.getLocaleCodeById(it, sqlClient) }
        val siteDefault = plugin.applicationContext.getBean(ConfigManager::class.java).config.locale

        return OrderLocale.resolve(null, stored, context.request().getHeader("Accept-Language"), installed, siteDefault)
    }
}
