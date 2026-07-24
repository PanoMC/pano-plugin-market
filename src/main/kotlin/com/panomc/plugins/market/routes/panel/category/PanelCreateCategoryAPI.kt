package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.log.CreatedMarketCategoryLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.ImageUtil
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.Handler
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import java.io.File

@Endpoint
class PanelCreateCategoryAPI(
    private val plugin: MarketPlugin,
    private val marketCategoryDao: MarketCategoryDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/categories", RouteType.POST))

    private val authProvider: AuthProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(5 * 1024 * 1024) // 5MB

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) {
            throw BadRequest()
        }
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.multipartFormData(
                    objectSchema()
                        .requiredProperty("name", stringSchema())
                        .optionalProperty("description", stringSchema())
                        .optionalProperty("icon", stringSchema())
                        .optionalProperty("color", stringSchema())
                        .optionalProperty("status", enumSchema(*MarketStatus.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("position", numberSchema())
                        .optionalProperty("parentId", numberSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val fileUploads = context.fileUploads()

        if (fileUploads.size > 1) {
            throw BadRequest()
        }

        val fileUpload = fileUploads.firstOrNull { it.name() == "image" }

        val name = data.getString("name")
        if (name.isNullOrBlank()) {
            throw BadRequest()
        }

        // The color is interpolated into an inline style on the storefront — accept
        // only a strict hex color so no arbitrary CSS can be injected.
        val color = data.getString("color")
        if (color != null && !color.matches(HEX_COLOR_REGEX)) {
            throw BadRequest()
        }

        validateImage(fileUpload)

        val sqlClient = databaseManager.getSqlClient()

        val parentId = data.getLong("parentId")
        val position = data.getInteger("position")
            ?: (marketCategoryDao.getMaxPosition(parentId, sqlClient) + 1)

        var imageFileName: String? = null
        if (fileUpload != null) {
            imageFileName = saveUploadedFile(fileUpload)
        }

        val category = MarketCategory(
            name = name,
            description = data.getString("description"),
            icon = data.getString("icon") ?: "fa-folder",
            color = color ?: "#0d6efd",
            status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE,
            parentId = parentId,
            position = position,
            imageFileName = imageFileName
        )

        val id = marketCategoryDao.add(category, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            CreatedMarketCategoryLog(userId, username, plugin.pluginId, name),
            sqlClient
        )

        return Successful(mapOf("id" to id))
    }

    private companion object {
        private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
    }

    private fun validateImage(fileUpload: FileUpload?) {
        if (fileUpload != null) {
            if (fileUpload.size() > 5 * 1024 * 1024) {
                throw BadRequest()
            }

            // The multipart Content-Type header is client-controlled; the magic-byte sniff is authoritative.
            if (ImageUtil.detectImageExtension(File(fileUpload.uploadedFileName())) == null) {
                throw BadRequest()
            }
        }
    }

    private fun saveUploadedFile(fileUpload: FileUpload): String {
        // Extension comes from the sniffed byte signature, never from the client-supplied filename.
        val extension = ImageUtil.detectImageExtension(File(fileUpload.uploadedFileName())) ?: throw BadRequest()
        val fileName =
            "category-${System.currentTimeMillis()}-${fileUpload.uploadedFileName().split(File.separator).last()}.$extension"
        val destFile = File(plugin.uploadsDir, fileName)

        File(fileUpload.uploadedFileName()).copyTo(destFile, true)

        ImageUtil.generateThumbnail(destFile, plugin.thumbnailsDir)

        return fileName
    }
}
