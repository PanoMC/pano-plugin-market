package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.CreatedMarketProductLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.CloneSuffix
import com.panomc.plugins.market.util.ImageUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import java.io.File

/**
 * `POST /api/panel/market/products/:id/clone` (04 section 5, `P:CAT`): an `INACTIVE` copy with variants, fields, prices,
 * bundle rows and provider meta, a localised name suffix and fresh action ids. Copied image files that the save did not
 * end up using are removed again.
 */
@Endpoint
class PanelCloneProductAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/products/:id/clone", RouteType.POST))

    override val nodes = setOf(MarketNode.CATALOG)

    private val catalog by lazy { catalogService(plugin) }

    private val authProvider: AuthProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager: DatabaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = context.productId()
        val copies = mutableListOf<String>()

        try {
            val result = catalog.clone(id, CloneSuffix.of(context.request().getHeader("Accept-Language"))) { fileName ->
                copyImageFile(fileName)?.also { copies.add(it) }
            }

            val sqlClient = databaseManager.getSqlClient()
            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

            databaseManager.panelActivityLogDao.add(CreatedMarketProductLog(userId, username, plugin.pluginId, result.name), sqlClient)

            return Successful(mapOf("id" to result.id))
        } catch (e: Throwable) {
            copies.forEach { deleteFile(plugin, it) }

            throw e
        }
    }

    private var counter = 0

    private fun copyImageFile(fileName: String): String? {
        val originalFile = File(plugin.uploadsDir, fileName)
        if (!originalFile.exists()) return null

        val extension = fileName.substringAfterLast('.', "")
        val newFileName = "product-${System.currentTimeMillis()}-clone-${synchronized(this) { ++counter }}" + if (extension.isNotBlank()) ".$extension" else ""
        val destFile = File(plugin.uploadsDir, newFileName)

        originalFile.copyTo(destFile, true)

        ImageUtil.generateThumbnail(destFile, File(plugin.uploadsDir, "thumbnails"))

        return newFileName
    }
}
