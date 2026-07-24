package com.panomc.plugins.market.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.ProductDurationType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Public storefront snapshot: store settings, the ACTIVE category tree, all visible products,
 * bestseller ids, and ACTIVE comparisons. No auth — the [Api] base only runs checkSetup()/demo.
 */
@Endpoint
class GetStoreAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao,
    private val marketCategoryDao: MarketCategoryDao,
    private val marketComparisonDao: MarketComparisonDao,
    private val marketOrderItemDao: MarketOrderItemDao
) : Api() {
    override val paths = listOf(Path("/api/market/store", RouteType.GET))

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    companion object {
        private const val BESTSELLER_LIMIT = 10
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        val config = configManager.config
        val sqlClient = databaseManager.getSqlClient()
        val now = System.currentTimeMillis()

        // ACTIVE categories only; the tree is built from active roots so any subtree under a
        // non-active (or missing) ancestor disappears entirely rather than getting promoted.
        val activeCategories = marketCategoryDao.getAll(null, sqlClient)
            .filter { it.status == MarketStatus.ACTIVE }
        val activeCategoryIds = activeCategories.map { it.id }.toSet()

        // Visible products: ACTIVE + within duration window; exclude those whose category is not
        // an ACTIVE category (uncategorized/null still shows).
        val visibleProducts = marketProductDao.getVisibleProducts(sqlClient)
            .filter { isWithinDuration(it, now) }
            .filter { it.categoryId == null || it.categoryId in activeCategoryIds }

        val productsCountByCategory = visibleProducts
            .mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()

        val productsJson = JsonArray()
        visibleProducts.forEach { productsJson.add(toProductListJson(it)) }

        val tree = buildTree(activeCategories, productsCountByCategory)

        // Bestsellers: all-time top sellers, filtered down to the currently-visible product ids
        // (preserving sold-quantity order). Gated behind the config toggle.
        val bestsellers = JsonArray()
        if (config.showBestsellers) {
            val visibleIds = visibleProducts.map { it.id }.toSet()
            marketOrderItemDao.topProductIds(BESTSELLER_LIMIT, sqlClient)
                .filter { it in visibleIds }
                .forEach { bestsellers.add(it) }
        }

        // ACTIVE comparisons; raw JSON blobs decoded verbatim (productIds may contain null slots).
        // The front-end resolves ids against the products list and drops any that are not visible.
        // Gated behind the config toggle, mirroring bestsellers.
        val comparisons = JsonArray()
        if (config.showComparisons) {
            marketComparisonDao.getAllByStatus(MarketStatus.ACTIVE, sqlClient).forEach { comparison ->
                comparisons.add(
                    JsonObject()
                        .put("id", comparison.id)
                        .put("name", comparison.name)
                        .put("productIds", JsonArray(comparison.productIds))
                        .put("features", JsonArray(comparison.features))
                        .put("cellValues", JsonObject(comparison.cellValues))
                )
            }
        }

        val settings = JsonObject()
            .put("storeName", config.storeName)
            .put("storeDescription", config.storeDescription)
            .put("currency", config.currency.name)
            .put("currencySymbol", config.currency.symbol)
            .put("creditsEnabled", config.creditsEnabled)
            .put("creditName", config.creditName)
            .put("removeCents", config.removeCents)
            .put("showBestsellers", config.showBestsellers)
            .put("showFeaturedProducts", config.showFeaturedProducts)
            .put("showComparisons", config.showComparisons)

        return Successful(
            mapOf(
                "settings" to settings,
                "categories" to tree,
                "products" to productsJson,
                "bestsellers" to bestsellers,
                "comparisons" to comparisons
            )
        )
    }

    private fun isWithinDuration(product: MarketProduct, now: Long): Boolean {
        if (product.durationType != ProductDurationType.TEMPORARY) return true
        if (product.durationStart != null && now < product.durationStart) return false
        if (product.durationExpiry != null && now >= product.durationExpiry) return false
        return true
    }

    private fun toProductListJson(product: MarketProduct): JsonObject =
        JsonObject()
            .put("id", product.id)
            .put("slug", product.slug)
            .put("name", product.name)
            .put("description", product.description)
            .put("categoryId", product.categoryId)
            .put("price", MoneyUtil.toDecimal(product.price))
            .put("creditPrice", MoneyUtil.toDecimal(product.creditPrice))
            .put("stock", product.stock)
            .put("featured", product.featured)
            .put("priority", product.priority)
            .put("icon", product.icon)
            .put("imageFileName", product.imageFileName)

    // Roots are ACTIVE categories with no parent; recursion descends only into ACTIVE children,
    // so subtrees hanging off a non-active/missing parent are omitted.
    private fun buildTree(categories: List<MarketCategory>, productsCountByCategory: Map<Long, Int>): List<JsonObject> {
        val childrenByParent = categories.groupBy { it.parentId }

        fun toNode(category: MarketCategory): JsonObject {
            val children = childrenByParent[category.id].orEmpty()
                .sortedBy { it.position }
                .map { toNode(it) }

            return JsonObject()
                .put("id", category.id)
                .put("name", category.name)
                .put("description", category.description)
                .put("icon", category.icon)
                .put("color", category.color)
                .put("parentId", category.parentId)
                .put("position", category.position)
                .put("imageFileName", category.imageFileName)
                .put("productsCount", (productsCountByCategory[category.id] ?: 0).toLong())
                .put("children", children)
        }

        return categories
            .filter { it.parentId == null }
            .sortedBy { it.position }
            .map { toNode(it) }
    }
}
