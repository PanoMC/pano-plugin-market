package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.log.CreatedMarketProductLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.ImageUtil
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.SlugUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import java.io.File

@Endpoint
class PanelCloneProductAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/products/:id/clone", RouteType.POST))

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
        val original = marketProductDao.getById(id, sqlClient) ?: throw NotFound()

        var copyNumber = 1
        var slug = SlugUtil.copySlug(original.slug, copyNumber)
        while (marketProductDao.getBySlug(slug, sqlClient) != null) {
            copyNumber++
            slug = SlugUtil.copySlug(original.slug, copyNumber)
        }

        val name = original.name + " (Kopya)"
        val imageFileName = original.imageFileName?.let { copyImageFile(it) }

        val product = MarketProduct(
            slug = slug,
            name = name,
            description = original.description,
            categoryId = original.categoryId,
            price = original.price,
            creditPrice = original.creditPrice,
            stock = original.stock,
            requiredProducts = original.requiredProducts,
            requireOnlyOne = original.requireOnlyOne,
            requiredPermission = original.requiredPermission,
            status = MarketStatus.INACTIVE,
            featured = original.featured,
            durationType = original.durationType,
            durationStart = original.durationStart,
            durationExpiry = original.durationExpiry,
            priority = original.priority,
            icon = original.icon,
            imageFileName = imageFileName,
            actions = original.actions
        )

        val newId = marketProductDao.add(product, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            CreatedMarketProductLog(userId, username, plugin.pluginId, name),
            sqlClient
        )

        return Successful(mapOf("id" to newId))
    }

    private fun copyImageFile(fileName: String): String? {
        val originalFile = File(plugin.uploadsDir, fileName)
        if (!originalFile.exists()) return null

        val extension = fileName.substringAfterLast('.', "")
        val newFileName = "product-${System.currentTimeMillis()}-clone" + if (extension.isNotBlank()) ".$extension" else ""
        val destFile = File(plugin.uploadsDir, newFileName)

        originalFile.copyTo(destFile, true)

        ImageUtil.generateThumbnail(destFile, File(plugin.uploadsDir, "thumbnails"))

        return newFileName
    }
}
