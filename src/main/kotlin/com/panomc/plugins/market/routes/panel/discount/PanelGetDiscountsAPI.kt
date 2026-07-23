package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.model.MarketDiscount
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
class PanelGetDiscountsAPI(
    private val plugin: MarketPlugin,
    private val discountDao: MarketDiscountDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/discounts", RouteType.GET))

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
        val discounts = discountDao.getAll(page, search, status, sqlClient)
        val count = discountDao.count(search, status, sqlClient)

        val totalPageNum = ceil(count.toDouble() / 10).toLong()

        if (totalPageNum in 1..<page) {
            throw PageNotFound()
        }

        return Successful(
            mapOf(
                "discounts" to discounts.map { it.toMap() },
                "discountCount" to count,
                "totalPage" to totalPageNum
            )
        )
    }

    private fun MarketDiscount.toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "value" to MoneyUtil.toDecimal(value),
        "unit" to unit.name,
        "minPaymentAmount" to minPaymentAmount?.let { MoneyUtil.toDecimal(it) },
        "scope" to scope.name,
        "productIds" to productIds,
        "categoryIds" to categoryIds,
        "startDate" to startDate,
        "expiryDate" to expiryDate,
        "usageLimit" to usageLimit,
        "usedCount" to usedCount,
        "status" to status.name,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}
