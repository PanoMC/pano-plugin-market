package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.error.InvalidCategoryMove
import com.panomc.plugins.market.log.UpdatedMarketCategoryLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.ImageUtil
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import java.io.File

@Endpoint
class PanelUpdateCategoryAPI(
    private val plugin: MarketPlugin,
    private val marketCategoryDao: MarketCategoryDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/categories/:id", RouteType.PUT))

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
            .pathParameter(param("id", numberSchema()))
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
                        .optionalProperty("removeImage", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
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

        // The color is interpolated into an inline style on the storefront — accept only a
        // strict hex color so no arbitrary CSS can be injected. Validated up-front, before
        // any destructive side effect (old image deletion) can run.
        val color = data.getString("color") ?: "#0d6efd"
        if (!color.matches(HEX_COLOR_REGEX)) {
            throw BadRequest()
        }

        validateImage(fileUpload)

        val sqlClient = databaseManager.getSqlClient()
        val existingCategory = marketCategoryDao.getById(id, sqlClient) ?: throw NotExists()

        var imageFileName: String? = existingCategory.imageFileName
        val removeImage = data.getBoolean("removeImage") ?: false

        if (removeImage || fileUpload != null) {
            existingCategory.imageFileName?.let { deleteFile(it) }
            imageFileName = null
        }

        if (fileUpload != null) {
            imageFileName = saveUploadedFile(fileUpload)
        }

        val icon = data.getString("icon") ?: "fa-folder"
        val status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE
        val parentId = data.getLong("parentId")
        val position = data.getInteger("position") ?: existingCategory.position

        // Reject reparenting onto a missing category, itself or its own subtree (would create a
        // parent-chain cycle that drops the whole cycle from the tree view) — mirrors PanelSortCategoriesAPI.
        if (parentId != null && parentId != existingCategory.parentId) {
            val categories = marketCategoryDao.getAll(null, sqlClient)

            if (categories.none { it.id == parentId } || parentId in collectSubtreeIds(id, categories)) {
                throw InvalidCategoryMove()
            }
        }

        val category = MarketCategory(
            id = id,
            name = name,
            description = data.getString("description"),
            icon = icon,
            color = color,
            status = status,
            parentId = parentId,
            position = position,
            imageFileName = imageFileName,
            createdAt = existingCategory.createdAt,
            updatedAt = System.currentTimeMillis()
        )

        marketCategoryDao.update(category, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        val changes = JsonObject()
        if (existingCategory.name != name) changes.put("name", name)
        if (existingCategory.description != data.getString("description")) changes.put("description", data.getString("description"))
        if (existingCategory.icon != icon) changes.put("icon", icon)
        if (existingCategory.color != color) changes.put("color", color)
        if (existingCategory.status != status) changes.put("status", status.name)
        if (existingCategory.parentId != parentId) changes.put("parentId", parentId)
        if (existingCategory.position != position) changes.put("position", position)
        if (existingCategory.imageFileName != imageFileName) changes.put("image", imageFileName != null)

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketCategoryLog(userId, username, plugin.pluginId, name, if (changes.isEmpty) null else changes),
            sqlClient
        )

        return Successful()
    }

    private companion object {
        private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
    }

    private fun collectSubtreeIds(rootId: Long, categories: List<MarketCategory>): Set<Long> {
        val childrenByParent = categories.groupBy { it.parentId }
        val result = mutableSetOf(rootId)
        val queue = ArrayDeque<Long>()
        queue.add(rootId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenByParent[current].orEmpty().forEach { child ->
                if (result.add(child.id)) {
                    queue.add(child.id)
                }
            }
        }

        return result
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
