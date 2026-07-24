package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.MarketDiscount
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.DiscountScope
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
    private val discountDao: MarketDiscountDao,
    private val marketProductDao: MarketProductDao,
    private val marketCategoryDao: MarketCategoryDao
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

        // Resolve every scoped id (PRODUCTS' productIds / CATEGORIES' categoryIds) to a name in one query each.
        val allProductIds = discounts
            .filter { it.scope == DiscountScope.PRODUCTS }
            .flatMap { it.productIds ?: emptyList() }
            .distinct()
        val allCategoryIds = discounts
            .filter { it.scope == DiscountScope.CATEGORIES }
            .flatMap { it.categoryIds ?: emptyList() }
            .distinct()
        val productNames = marketProductDao.getByIds(allProductIds, sqlClient).associate { it.id to it.name }
        val categoryNames = marketCategoryDao.getNamesByIds(allCategoryIds, sqlClient)

        return Successful(
            mapOf(
                "discounts" to discounts.map { it.toMap(productNames, categoryNames) },
                "discountCount" to count,
                "totalPage" to totalPageNum
            )
        )
    }

    private fun MarketDiscount.toMap(
        productNames: Map<Long, String>,
        categoryNames: Map<Long, String>
    ): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "value" to MoneyUtil.toDecimal(value),
        "unit" to unit.name,
        "minPaymentAmount" to minPaymentAmount?.let { MoneyUtil.toDecimal(it) },
        "scope" to scope.name,
        "productIds" to productIds,
        "categoryIds" to categoryIds,
        // Resolved display names for the list table; raw id arrays above stay for modal prefill.
        "products" to when (scope) {
            DiscountScope.ALL -> listOf("all")
            DiscountScope.PRODUCTS -> productIds.orEmpty().mapNotNull { productNames[it] }
            DiscountScope.CATEGORIES -> categoryIds.orEmpty().mapNotNull { categoryNames[it] }
        },
        "startDate" to startDate,
        "expiryDate" to expiryDate,
        "usageLimit" to usageLimit,
        "usedCount" to usedCount,
        "status" to status.name,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}
