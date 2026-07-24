package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.model.MarketDiscount
import com.panomc.plugins.market.log.UpdatedMarketDiscountLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateDiscountAPI(
    private val plugin: MarketPlugin,
    private val discountDao: MarketDiscountDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/discounts/:id", RouteType.PUT))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("name", stringSchema())
                        .requiredProperty("value", numberSchema())
                        .optionalProperty("unit", enumSchema(*DiscountUnit.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("minPaymentAmount", numberSchema())
                        .optionalProperty("scope", enumSchema(*DiscountScope.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("productIds", arraySchema())
                        .optionalProperty("categoryIds", arraySchema())
                        .optionalProperty("startDate", numberSchema())
                        .optionalProperty("expiryDate", numberSchema())
                        .optionalProperty("usageLimit", numberSchema())
                        .optionalProperty("status", enumSchema(MarketStatus.ACTIVE.name, MarketStatus.INACTIVE.name))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val id = context.pathParam("id").toLong()
        val data = getParameters(context).body().jsonObject

        val name = data.getString("name")
        if (name.isBlank()) {
            throw BadRequest()
        }

        val sqlClient = databaseManager.getSqlClient()
        val existing = discountDao.getById(id, sqlClient) ?: throw BadRequest()

        val discount = MarketDiscount(
            id = id,
            name = name,
            value = MoneyUtil.toMinor(data.getDouble("value")),
            unit = data.getString("unit")?.let { DiscountUnit.valueOf(it) } ?: DiscountUnit.PERCENT,
            minPaymentAmount = data.getDouble("minPaymentAmount")?.let { MoneyUtil.toMinor(it) },
            scope = data.getString("scope")?.let { DiscountScope.valueOf(it) } ?: DiscountScope.ALL,
            productIds = data.getJsonArray("productIds")?.map { (it as Number).toLong() },
            categoryIds = data.getJsonArray("categoryIds")?.map { (it as Number).toLong() },
            startDate = data.getLong("startDate"),
            expiryDate = data.getLong("expiryDate"),
            usageLimit = data.getInteger("usageLimit"),
            usedCount = existing.usedCount,
            status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE,
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis()
        )

        discountDao.update(discount, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
        databaseManager.panelActivityLogDao.add(UpdatedMarketDiscountLog(userId, username, plugin.pluginId, name), sqlClient)

        return Successful()
    }
}
