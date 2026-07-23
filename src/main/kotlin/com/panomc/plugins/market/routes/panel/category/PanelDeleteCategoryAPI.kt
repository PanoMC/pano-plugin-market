package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.log.DeletedMarketCategoryLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import java.io.File

@Endpoint
class PanelDeleteCategoryAPI(
    private val plugin: MarketPlugin,
    private val marketCategoryDao: MarketCategoryDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/categories/:id", RouteType.DELETE))

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
        val category = marketCategoryDao.getById(id, sqlClient) ?: throw NotExists()

        // Reparent children up to the deleted node's parent and detach its products (no cascade).
        marketCategoryDao.reparentChildren(id, category.parentId, sqlClient)
        marketCategoryDao.clearProductsCategory(id, sqlClient)

        category.imageFileName?.let { deleteFile(it) }

        marketCategoryDao.deleteById(id, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            DeletedMarketCategoryLog(userId, username, plugin.pluginId, category.name),
            sqlClient
        )

        return Successful()
    }

    private fun deleteFile(fileName: String) {
        val file = File(plugin.uploadsDir, fileName)
        if (file.exists()) {
            file.delete()
        }

        val thumbnailFile = File(plugin.thumbnailsDir, fileName)
        if (thumbnailFile.exists()) {
            thumbnailFile.delete()
        }
    }
}
