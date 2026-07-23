package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.log.DeletedMarketProductLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import java.io.File

@Endpoint
class PanelDeleteProductAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/products/:id", RouteType.DELETE))

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

        product.imageFileName?.let { deleteFile(it) }

        marketProductDao.deleteById(id, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            DeletedMarketProductLog(userId, username, plugin.pluginId, product.name),
            sqlClient
        )

        return Successful()
    }

    private fun deleteFile(fileName: String) {
        val file = File(plugin.uploadsDir, fileName)
        if (file.exists()) {
            file.delete()
        }

        val thumbnailFile = File(File(plugin.uploadsDir, "thumbnails"), fileName)
        if (thumbnailFile.exists()) {
            thumbnailFile.delete()
        }
    }
}
