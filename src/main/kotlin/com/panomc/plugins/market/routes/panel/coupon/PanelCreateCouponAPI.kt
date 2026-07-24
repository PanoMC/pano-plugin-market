package com.panomc.plugins.market.routes.panel.coupon

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCouponDao
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.db.dao.MarketGiftDao
import com.panomc.plugins.market.db.model.MarketCoupon
import com.panomc.plugins.market.error.CodeAlreadyExists
import com.panomc.plugins.market.log.CreatedMarketCouponLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.CouponScope
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
class PanelCreateCouponAPI(
    private val plugin: MarketPlugin,
    private val couponDao: MarketCouponDao,
    private val giftDao: MarketGiftDao,
    private val creatorCodeDao: MarketCreatorCodeDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/coupons", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("name", stringSchema())
                        .requiredProperty("code", stringSchema())
                        .requiredProperty("discount", numberSchema())
                        .optionalProperty("scope", enumSchema(*CouponScope.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("productIds", arraySchema())
                        .optionalProperty("unit", enumSchema(*DiscountUnit.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("minPaymentAmount", numberSchema())
                        .optionalProperty("startDate", numberSchema())
                        .optionalProperty("expiryDate", numberSchema())
                        .optionalProperty("redeemLimit", numberSchema())
                        .optionalProperty("customerRedeemLimit", numberSchema())
                        .optionalProperty("status", enumSchema(MarketStatus.ACTIVE.name, MarketStatus.INACTIVE.name))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val data = getParameters(context).body().jsonObject

        val name = data.getString("name")
        val code = data.getString("code")
        if (code.isBlank()) {
            throw BadRequest()
        }

        val sqlClient = databaseManager.getSqlClient()

        if (couponDao.getByCode(code, sqlClient) != null ||
            giftDao.getByCode(code, sqlClient) != null ||
            creatorCodeDao.getByCode(code, sqlClient) != null
        ) {
            throw CodeAlreadyExists()
        }

        val coupon = MarketCoupon(
            name = name,
            code = code,
            scope = data.getString("scope")?.let { CouponScope.valueOf(it) } ?: CouponScope.ALL,
            productIds = data.getJsonArray("productIds")?.map { (it as Number).toLong() },
            discount = MoneyUtil.toMinor(data.getDouble("discount")),
            unit = data.getString("unit")?.let { DiscountUnit.valueOf(it) } ?: DiscountUnit.PERCENT,
            minPaymentAmount = data.getDouble("minPaymentAmount")?.let { MoneyUtil.toMinor(it) },
            startDate = data.getLong("startDate"),
            expiryDate = data.getLong("expiryDate"),
            redeemLimit = data.getInteger("redeemLimit"),
            customerRedeemLimit = data.getInteger("customerRedeemLimit"),
            status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE
        )

        val id = couponDao.add(coupon, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
        databaseManager.panelActivityLogDao.add(CreatedMarketCouponLog(userId, username, plugin.pluginId, name), sqlClient)

        return Successful(mapOf("id" to id))
    }
}
