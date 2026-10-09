package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.CreatedMarketCouponLog
import com.panomc.plugins.market.log.CreatedMarketCreatorCodeLog
import com.panomc.plugins.market.log.CreatedMarketDiscountLog
import com.panomc.plugins.market.log.CreatedMarketGiftLog
import com.panomc.plugins.market.log.DeletedMarketCouponLog
import com.panomc.plugins.market.log.DeletedMarketCreatorCodeLog
import com.panomc.plugins.market.log.DeletedMarketDiscountLog
import com.panomc.plugins.market.log.DeletedMarketGiftLog
import com.panomc.plugins.market.log.UpdatedMarketCouponLog
import com.panomc.plugins.market.log.UpdatedMarketCreatorCodeLog
import com.panomc.plugins.market.log.UpdatedMarketDiscountLog
import com.panomc.plugins.market.log.UpdatedMarketGiftLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.RedemptionService
import com.panomc.plugins.market.util.MoneyUtil
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

private object PromotionAdminWiringHolder

@Volatile
private var cachedAdmin: Pair<MarketPlugin, PromotionAdminService>? = null

/** The discounts panel logic on the plugin's beans; one per plugin instance. */
internal fun promotionAdminService(plugin: MarketPlugin): PromotionAdminService {
    cachedAdmin?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(PromotionAdminWiringHolder) {
        cachedAdmin?.takeIf { it.first === plugin }?.second ?: buildPromotionAdminService(plugin).also { cachedAdmin = plugin to it }
    }
}

private fun buildPromotionAdminService(plugin: MarketPlugin): PromotionAdminService {
    val databaseManager = { plugin.applicationContext.getBean(DatabaseManager::class.java) }
    val context = plugin.beans
    val redemptionDao = context.getBean(MarketRedemptionDao::class.java)
    val locks = Locks(
        context.getBean(MarketOrderDao::class.java), context.getBean(MarketOrderItemDao::class.java), redemptionDao, context.getBean(MarketCreditAccountDao::class.java)
    )

    return PromotionAdminService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock),
        pool = { databaseManager().getSqlClient() as Pool },
        client = { databaseManager().getSqlClient() },
        prefix = { MarketTables.prefixOverride ?: databaseManager().getTablePrefix() },
        clock = SystemClock,
        redemptions = RedemptionService(SystemClock, locks, redemptionDao),
        users = PlatformUserDirectory(databaseManager),
        products = context.getBean(MarketProductDao::class.java),
        bundleItems = context.getBean(MarketBundleItemDao::class.java),
        categories = context.getBean(MarketCategoryDao::class.java)
    )
}

/** The activity log row of an action on a promotion; the details carry the label (the code, or the name of a discount). */
internal fun promotionLog(promotion: Promotion, action: PromotionAction, userId: Long, username: String, pluginId: String, label: String): PluginActivityLog =
    when (promotion) {
        Promotion.DISCOUNT -> when (action) {
            PromotionAction.CREATE -> CreatedMarketDiscountLog(userId, username, pluginId, label)
            PromotionAction.UPDATE -> UpdatedMarketDiscountLog(userId, username, pluginId, label)
            PromotionAction.DELETE -> DeletedMarketDiscountLog(userId, username, pluginId, label)
        }

        Promotion.COUPON -> when (action) {
            PromotionAction.CREATE -> CreatedMarketCouponLog(userId, username, pluginId, label)
            PromotionAction.UPDATE -> UpdatedMarketCouponLog(userId, username, pluginId, label)
            PromotionAction.DELETE -> DeletedMarketCouponLog(userId, username, pluginId, label)
        }

        Promotion.CREATOR_CODE -> when (action) {
            PromotionAction.CREATE -> CreatedMarketCreatorCodeLog(userId, username, pluginId, label)
            PromotionAction.UPDATE -> UpdatedMarketCreatorCodeLog(userId, username, pluginId, label)
            PromotionAction.DELETE -> DeletedMarketCreatorCodeLog(userId, username, pluginId, label)
        }

        Promotion.GIFT -> when (action) {
            PromotionAction.CREATE -> CreatedMarketGiftLog(userId, username, pluginId, label)
            PromotionAction.UPDATE -> UpdatedMarketGiftLog(userId, username, pluginId, label)
            PromotionAction.DELETE -> DeletedMarketGiftLog(userId, username, pluginId, label)
        }
    }

enum class PromotionAction { CREATE, UPDATE, DELETE }

/** The items of a `RedemptionRow` list as 04 section 6 pins it. */
internal fun redemptionItems(page: com.panomc.plugins.market.service.RedemptionPage): List<Map<String, Any?>> = page.rows.map {
    linkedMapOf(
        "orderId" to it.orderId, "playerUsername" to it.playerUsername, "amount" to MoneyUtil.toDecimal(it.amount), "currency" to it.currency,
        "state" to it.state.name, "createdAt" to it.createdAt
    )
}

/**
 * Shared parts of the promotion routes: all `P:DISC` (04 section 6; the umbrella permission is accepted by the base class), the acting user and the
 * activity log, the paging and the body schema.
 */
abstract class PromotionAdminRoute(protected val plugin: MarketPlugin, protected val promotion: Promotion) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.DISCOUNTS)

    protected val admin: PromotionAdminService get() = promotionAdminService(plugin)

    protected fun idOf(context: RoutingContext): Long = parseId(context.pathParam("id"), "id")

    protected fun window(context: RoutingContext): PageRequest = Paging.request(context)

    protected fun listValidation(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = Paging.params(ValidationHandlerBuilder.create(schemaRepository))

        for (name in listOf("search", "status")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    protected fun pagingValidation(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

    protected fun bodyValidation(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    protected fun body(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: throw RequestValueException("body", "REQUIRED")

    protected suspend fun log(context: RoutingContext, action: PromotionAction, label: String) {
        val databaseManager = plugin.applicationContext.getBean(DatabaseManager::class.java)
        val client = databaseManager.getSqlClient()
        val userId = plugin.applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, client)!!

        databaseManager.panelActivityLogDao.add(promotionLog(promotion, action, userId, username, plugin.pluginId, label), client)
    }

    /** The list of this promotion: the core page shape; 404 `PAGE_NOT_FOUND` beyond the last page. */
    protected suspend fun listAnswer(context: RoutingContext): Result {
        val window = window(context)
        val page = admin.list(promotion, window, context.request().getParam("search"), context.request().getParam("status"))

        return Successful(Paging.response(page.rows, page.total, window))
    }

    protected suspend fun createAnswer(context: RoutingContext): Result {
        val created = admin.create(promotion, body(context))

        log(context, PromotionAction.CREATE, created.label)

        return Successful(mapOf("id" to created.id))
    }

    protected suspend fun updateAnswer(context: RoutingContext): Result {
        val updated = admin.update(promotion, idOf(context), body(context))

        log(context, PromotionAction.UPDATE, updated.label)

        return Successful()
    }

    protected suspend fun deleteAnswer(context: RoutingContext): Result {
        val label = admin.delete(promotion, idOf(context))

        log(context, PromotionAction.DELETE, label)

        return Successful()
    }

    protected suspend fun redemptionsAnswer(context: RoutingContext): Result {
        val window = window(context)
        val page = admin.redemptionList(promotion, idOf(context), window)

        return Successful(Paging.response(redemptionItems(page), page.total, window))
    }
}
