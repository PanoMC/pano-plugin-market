package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.db.model.MarketCreatorCode
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
class PanelGetCreatorCodesAPI(
    private val plugin: MarketPlugin,
    private val creatorCodeDao: MarketCreatorCodeDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/creator-codes", RouteType.GET))

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
        val creatorCodes = creatorCodeDao.getAll(page, search, status, sqlClient)
        val count = creatorCodeDao.count(search, status, sqlClient)

        val totalPageNum = ceil(count.toDouble() / 10).toLong()

        if (totalPageNum in 1..<page) {
            throw PageNotFound()
        }

        return Successful(
            mapOf(
                "creatorCodes" to creatorCodes.map { it.toMap() },
                "creatorCodeCount" to count,
                "totalPage" to totalPageNum
            )
        )
    }

    private fun MarketCreatorCode.toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "creator" to creator,
        "code" to code,
        "discount" to MoneyUtil.toDecimal(discount),
        "unit" to unit.name,
        "commissionPercent" to MoneyUtil.toDecimal(commissionPercent),
        "startDate" to startDate,
        "expiryDate" to expiryDate,
        "redeemLimit" to redeemLimit,
        "usedCount" to usedCount,
        "earnings" to MoneyUtil.toDecimal(earnings),
        "status" to status.name,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}
