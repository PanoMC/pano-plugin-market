package com.panomc.plugins.market.routes.panel.gift

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketGiftDao
import com.panomc.plugins.market.db.model.MarketGift
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
class PanelGetGiftsAPI(
    private val plugin: MarketPlugin,
    private val giftDao: MarketGiftDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/gifts", RouteType.GET))

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
        val gifts = giftDao.getAll(page, search, status, sqlClient)
        val count = giftDao.count(search, status, sqlClient)

        val totalPageNum = ceil(count.toDouble() / 10).toLong()

        if (totalPageNum in 1..<page) {
            throw PageNotFound()
        }

        return Successful(
            mapOf(
                "gifts" to gifts.map { it.toMap() },
                "giftCount" to count,
                "totalPage" to totalPageNum
            )
        )
    }

    private fun MarketGift.toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "code" to code,
        "type" to type.name,
        "productId" to productId,
        "creditAmount" to creditAmount?.let { MoneyUtil.toDecimal(it) },
        "productIds" to productIds,
        "status" to status.name,
        "startDate" to startDate,
        "expiryDate" to expiryDate,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}
