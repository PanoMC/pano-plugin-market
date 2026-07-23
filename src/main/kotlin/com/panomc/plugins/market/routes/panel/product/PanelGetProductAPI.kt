package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelGetProductAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/products/:id", RouteType.GET))

    private val authProvider: AuthProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val sqlClient = databaseManager.getSqlClient()
        val product = marketProductDao.getById(id, sqlClient) ?: throw NotFound()

        val productJson = JsonObject()
            .put("id", product.id)
            .put("slug", product.slug)
            .put("name", product.name)
            .put("description", product.description)
            .put("categoryId", product.categoryId)
            .put("price", MoneyUtil.toDecimal(product.price))
            .put("creditPrice", MoneyUtil.toDecimal(product.creditPrice))
            .put("stock", product.stock)
            .put("requiredProducts", JsonArray(product.requiredProducts))
            .put("requireOnlyOne", product.requireOnlyOne)
            .put("requiredPermission", product.requiredPermission)
            .put("status", product.status.name)
            .put("featured", product.featured)
            .put("durationType", product.durationType.name)
            .put("durationStart", product.durationStart)
            .put("durationExpiry", product.durationExpiry)
            .put("priority", product.priority)
            .put("icon", product.icon)
            .put("imageFileName", product.imageFileName)
            .put("actions", JsonArray(product.actions ?: "[]"))
            .put("createdAt", product.createdAt)
            .put("updatedAt", product.updatedAt)

        return Successful(mapOf("product" to productJson))
    }
}
