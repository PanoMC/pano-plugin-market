package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.DeletedMarketCategoryLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.panel.product.deleteFile
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/** `DELETE /api/panel/market/categories/:id` (`P:CAT`): children move up, products are detached; `409 CATEGORY_IN_USE` for a tiered category with ACTIVE entitlements. */
@Endpoint
class PanelDeleteCategoryAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/categories/:id", RouteType.DELETE))

    override val nodes = setOf(MarketNode.CATALOG)

    private val catalog by lazy { categoryCatalog(plugin) }

    private val authProvider: AuthProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager: DatabaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val result = catalog.deleteCategory(parseId(context.pathParam("id")))

        result.orphanedFiles.forEach { deleteFile(plugin, it) }

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(DeletedMarketCategoryLog(userId, username, plugin.pluginId, result.name), sqlClient)

        return Successful()
    }
}
