package com.panomc.plugins.market.routes.api.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.routes.panel.block.blockListService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.BankTransferService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool

private object BankTransferWiringHolder

@Volatile
private var cachedBankTransfer: Pair<MarketPlugin, BankTransferService>? = null

/** The bank transfer flow (the buyer's notice, the admin's decision) on the plugin's beans (MK-094); one per plugin instance. */
internal fun bankTransferService(plugin: MarketPlugin): BankTransferService {
    cachedBankTransfer?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(BankTransferWiringHolder) {
        cachedBankTransfer?.takeIf { it.first === plugin }?.second ?: buildBankTransferService(plugin).also { cachedBankTransfer = plugin to it }
    }
}

private fun buildBankTransferService(plugin: MarketPlugin): BankTransferService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    val methods = context.getBean(MarketPaymentMethodDao::class.java)

    return BankTransferService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, clock = SystemClock, orders = orderDao,
        payments = context.getBean(MarketPaymentDao::class.java), events = context.getBean(MarketOrderEventDao::class.java), paymentService = paymentService(plugin),
        cashback = { currentConfig(plugin).cashbackPercent > 0 },
        requireNotice = { requireBuyerNotice(methods.getByMethodId(BankTransferProvider.ID, databaseManager().getSqlClient())?.settings) },
        readClient = { databaseManager().getSqlClient() },
        // MK-172 (the open seam of MK-094): the PAY holders get a panel notification when the buyer says the transfer was made
        buyerNoticeAlert = { orderId -> com.panomc.plugins.market.notification.marketAlerts(plugin).alert(orderId, com.panomc.plugins.market.notification.AlertCodes.BANK_TRANSFER_NOTIFIED, JsonObject()) }
    )
}

/** `requireBuyerNotice` of the stored bank transfer settings (a plain switch, never encrypted); unreadable or absent = off. */
internal fun requireBuyerNotice(settingsJson: String?): Boolean {
    val settings = settingsJson?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return false

    return settings.getValue(BankTransferProvider.KEY_REQUIRE_NOTICE) == true
}

/**
 * The body of the buyer's notice (04 section 3): `senderName?` and `note?`, text of at most 255 characters (the service removes control characters and
 * cuts at 255). A key outside the contract or a value that is not text is refused (PT-1).
 */
internal fun parseBankTransferNotice(body: JsonObject?): Pair<String?, String?> {
    if (body == null) return null to null

    for (key in body.fieldNames()) if (key != "senderName" && key != "note") throw RequestValueException(key, "UNKNOWN_FIELD")

    fun text(key: String): String? = when (val v = body.getValue(key)) {
        null -> null
        is String -> v
        else -> throw RequestValueException(key, "MUST_BE_A_STRING")
    }

    return text("senderName") to text("note")
}

/**
 * `POST /api/market/orders/:publicId/bank-transfer/notify` (04 section 3, 06 section 14.1 step 2; owner only, `PUB-M`): the buyer says the transfer was made.
 * `{senderName?, note?}` in, `{}` out; the attempt becomes `PROCESSING` and the expiry does not move. 409 `ORDER_NOT_PAYABLE` when the order or its newest
 * attempt cannot take a notice, a repeated notice is 200. Limiter L1.
 */
@Endpoint
class BankTransferNotifyAPI(private val plugin: MarketPlugin) : MarketPublicMutationApi() {
    override val paths = listOf(Path("/api/market/orders/:publicId/bank-transfer/notify", RouteType.POST))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val limiter = OrderMutationLimiter { currentConfig(plugin).checkoutRateLimitPerMinute }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("publicId", stringSchema()))
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()
        val order = resolveOrder(plugin, context, getParameters(context).pathParameter("publicId").string, sqlClient).requireOwner()
        val caller = payCaller(plugin, context)

        limiter.check(caller.clientIp, order.buyerKey)
        blockListService(plugin).requireOrderBuyerAllowed(order, caller.clientIp, sqlClient)

        val (senderName, note) = parseBankTransferNotice(context.body().asJsonObject())

        bankTransferService(plugin).notify(order.id, senderName, note, order.userId)
        noStore(context)

        return Successful(emptyMap<String, Any?>())
    }
}
