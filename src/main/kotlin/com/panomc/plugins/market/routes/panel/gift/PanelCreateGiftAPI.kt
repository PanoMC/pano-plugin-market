package com.panomc.plugins.market.routes.panel.gift

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCouponDao
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.db.dao.MarketGiftDao
import com.panomc.plugins.market.db.model.MarketGift
import com.panomc.plugins.market.error.CodeAlreadyExists
import com.panomc.plugins.market.log.CreatedMarketGiftLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.GiftType
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
class PanelCreateGiftAPI(
    private val plugin: MarketPlugin,
    private val giftDao: MarketGiftDao,
    private val couponDao: MarketCouponDao,
    private val creatorCodeDao: MarketCreatorCodeDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/gifts", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("code", stringSchema())
                        .requiredProperty("type", enumSchema(*GiftType.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("productId", numberSchema())
                        .optionalProperty("creditAmount", numberSchema())
                        .optionalProperty("productIds", arraySchema())
                        .optionalProperty("startDate", numberSchema())
                        .optionalProperty("expiryDate", numberSchema())
                        .optionalProperty("status", enumSchema(*MarketStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val data = getParameters(context).body().jsonObject

        val code = data.getString("code")
        val type = GiftType.valueOf(data.getString("type"))
        val productId = data.getLong("productId")
        val creditAmount = data.getDouble("creditAmount")
        val productIds = data.getJsonArray("productIds")?.map { (it as Number).toLong() }
        val status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE
        val startDate = data.getLong("startDate")
        val expiryDate = data.getLong("expiryDate")

        if (code.isBlank()) {
            throw BadRequest()
        }

        when (type) {
            GiftType.PRODUCT -> if (productId == null) throw BadRequest()
            GiftType.CREDIT -> if (creditAmount == null) throw BadRequest()
            GiftType.RANDOM -> if (productIds.isNullOrEmpty()) throw BadRequest()
        }

        val sqlClient = databaseManager.getSqlClient()

        if (giftDao.getByCode(code, sqlClient) != null ||
            couponDao.getByCode(code, sqlClient) != null ||
            creatorCodeDao.getByCode(code, sqlClient) != null
        ) {
            throw CodeAlreadyExists()
        }

        val gift = MarketGift(
            code = code,
            type = type,
            productId = if (type == GiftType.PRODUCT) productId else null,
            creditAmount = if (type == GiftType.CREDIT) creditAmount?.let { MoneyUtil.toMinor(it) } else null,
            productIds = if (type == GiftType.RANDOM) productIds else null,
            status = status,
            startDate = startDate,
            expiryDate = expiryDate
        )

        val id = giftDao.add(gift, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
        databaseManager.panelActivityLogDao.add(CreatedMarketGiftLog(userId, username, plugin.pluginId, code), sqlClient)

        return Successful(mapOf("id" to id))
    }
}
