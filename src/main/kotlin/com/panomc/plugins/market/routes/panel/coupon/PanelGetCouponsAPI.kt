package com.panomc.plugins.market.routes.panel.coupon

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCouponDao
import com.panomc.plugins.market.db.model.MarketCoupon
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlin.math.ceil

@Endpoint
class PanelGetCouponsAPI(
    private val plugin: MarketPlugin,
    private val couponDao: MarketCouponDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/coupons", RouteType.GET))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("status", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val page = parameters.queryParameter("page")?.long ?: 1L
        val search = parameters.queryParameter("search")?.string
        val status = parameters.queryParameter("status")?.string

        val sqlClient = databaseManager.getSqlClient()
        val coupons = couponDao.getAll(page, search, status, sqlClient)
        val count = couponDao.count(search, status, sqlClient)

        val totalPageNum = ceil(count.toDouble() / 10).toLong()

        if (totalPageNum in 1..<page) {
            throw PageNotFound()
        }

        return Successful(
            mapOf(
                "coupons" to coupons.map { it.toMap() },
                "couponCount" to count,
                "totalPage" to totalPageNum
            )
        )
    }

    private fun MarketCoupon.toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "code" to code,
        "scope" to scope.name,
        "productIds" to productIds,
        "discount" to MoneyUtil.toDecimal(discount),
        "unit" to unit.name,
        "minPaymentAmount" to minPaymentAmount?.let { MoneyUtil.toDecimal(it) },
        "startDate" to startDate,
        "expiryDate" to expiryDate,
        "redeemLimit" to redeemLimit,
        "customerRedeemLimit" to customerRedeemLimit,
        "usedCount" to usedCount,
        "status" to status.name,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}
