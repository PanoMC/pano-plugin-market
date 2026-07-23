package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCouponDao
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.db.dao.MarketGiftDao
import com.panomc.plugins.market.db.model.MarketCreatorCode
import com.panomc.plugins.market.error.CodeAlreadyExists
import com.panomc.plugins.market.log.CreatedMarketCreatorCodeLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelCreateCreatorCodeAPI(
    private val plugin: MarketPlugin,
    private val creatorCodeDao: MarketCreatorCodeDao,
    private val giftDao: MarketGiftDao,
    private val couponDao: MarketCouponDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/creator-codes", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("creator", stringSchema())
                        .requiredProperty("code", stringSchema())
                        .requiredProperty("discount", numberSchema())
                        .optionalProperty("unit", enumSchema(*DiscountUnit.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("commissionPercent", numberSchema())
                        .optionalProperty("startDate", numberSchema())
                        .optionalProperty("expiryDate", numberSchema())
                        .optionalProperty("redeemLimit", numberSchema())
                        .optionalProperty("status", enumSchema(*MarketStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val data = getParameters(context).body().jsonObject

        val creator = data.getString("creator")
        val code = data.getString("code")
        val commissionPercent = data.getDouble("commissionPercent") ?: 0.0

        if (creator.isBlank() || code.isBlank()) {
            throw BadRequest()
        }

        if (commissionPercent < 0 || commissionPercent > 100) {
            throw BadRequest()
        }

        val sqlClient = databaseManager.getSqlClient()

        if (creatorCodeDao.getByCode(code, sqlClient) != null ||
            giftDao.getByCode(code, sqlClient) != null ||
            couponDao.getByCode(code, sqlClient) != null
        ) {
            throw CodeAlreadyExists()
        }

        val creatorCode = MarketCreatorCode(
            creator = creator,
            code = code,
            discount = MoneyUtil.toMinor(data.getDouble("discount")),
            unit = data.getString("unit")?.let { DiscountUnit.valueOf(it) } ?: DiscountUnit.PERCENT,
            commissionPercent = MoneyUtil.toMinor(commissionPercent),
            startDate = data.getLong("startDate"),
            expiryDate = data.getLong("expiryDate"),
            redeemLimit = data.getInteger("redeemLimit"),
            status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE
        )

        val id = creatorCodeDao.add(creatorCode, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
        databaseManager.panelActivityLogDao.add(CreatedMarketCreatorCodeLog(userId, username, plugin.pluginId, code), sqlClient)

        return Successful(mapOf("id" to id))
    }
}
