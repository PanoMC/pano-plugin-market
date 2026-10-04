package com.panomc.plugins.market.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.util.HtmlSanitizer
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.ProductDurationType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Public detail of a single visible product by its slug. 404 unless the product exists, is ACTIVE,
 * and (if TEMPORARY) is currently within its duration window. Server-only fields (actions,
 * requiredPermission) are never exposed.
 */
@Endpoint
class GetStoreProductAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao,
    private val marketCategoryDao: MarketCategoryDao
) : Api() {
    override val paths = listOf(Path("/api/market/products/:slug", RouteType.GET))

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("slug", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val slug = parameters.pathParameter("slug").string

        val sqlClient = databaseManager.getSqlClient()
        val product = marketProductDao.getBySlug(slug, sqlClient) ?: throw NotFound()

        // Not-visible products are indistinguishable from missing ones (404, no info leak).
        if (product.status != MarketStatus.ACTIVE) throw NotFound()

        val now = System.currentTimeMillis()
        if (product.durationType == ProductDurationType.TEMPORARY) {
            if (product.durationStart != null && now < product.durationStart) throw NotFound()
            if (product.durationExpiry != null && now >= product.durationExpiry) throw NotFound()
        }

        val categoryName = product.categoryId?.let { marketCategoryDao.getById(it, sqlClient)?.name }

        val productJson = JsonObject()
            .put("id", product.id)
            .put("slug", product.slug)
            .put("name", product.name)
            // Sanitized on output too: rows saved before write-time sanitizing may still hold raw HTML.
            .put("description", HtmlSanitizer.sanitizeOrNull(product.description))
            .put("categoryId", product.categoryId)
            .put("categoryName", categoryName)
            .put("price", MoneyUtil.toDecimal(product.price))
            .put("creditPrice", MoneyUtil.toDecimal(product.creditPrice))
            .put("stock", product.stock)
            .put("requiredProducts", JsonArray(product.requiredProducts))
            .put("requireOnlyOne", product.requireOnlyOne)
            .put("featured", product.featured)
            .put("durationType", product.durationType.name)
            .put("durationStart", product.durationStart)
            .put("durationExpiry", product.durationExpiry)
            .put("priority", product.priority)
            .put("icon", product.icon)
            .put("imageFileName", product.imageFileName)
            .put("createdAt", product.createdAt)
            .put("updatedAt", product.updatedAt)

        return Successful(mapOf("product" to productJson))
    }
}
