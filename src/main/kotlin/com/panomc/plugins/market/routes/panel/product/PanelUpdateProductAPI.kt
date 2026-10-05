package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.error.SlugAlreadyExists
import com.panomc.plugins.market.log.UpdatedMarketProductLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.*
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import java.io.File

@Endpoint
class PanelUpdateProductAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/products/:id", RouteType.PUT))

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
                io.vertx.ext.web.validation.builder.Bodies.multipartFormData(
                    objectSchema()
                        .requiredProperty("name", stringSchema())
                        .optionalProperty("slug", stringSchema())
                        .optionalProperty("description", stringSchema())
                        .optionalProperty("categoryId", numberSchema())
                        .optionalProperty("price", numberSchema())
                        .optionalProperty("creditPrice", numberSchema())
                        .optionalProperty("stock", numberSchema())
                        .optionalProperty("requiredProducts", stringSchema())
                        .optionalProperty("requireOnlyOne", booleanSchema())
                        .optionalProperty("requiredPermission", stringSchema())
                        .optionalProperty("status", enumSchema(MarketStatus.ACTIVE.name, MarketStatus.INACTIVE.name))
                        .optionalProperty("featured", booleanSchema())
                        .optionalProperty("durationType", enumSchema(*ProductDurationType.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("durationStart", numberSchema())
                        .optionalProperty("durationExpiry", numberSchema())
                        .optionalProperty("priority", numberSchema())
                        .optionalProperty("icon", stringSchema())
                        .optionalProperty("actions", stringSchema())
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
        validateImage(fileUpload)

        val name = data.getString("name")
        if (name.isBlank()) {
            throw BadRequest()
        }

        val slug = data.getString("slug")?.takeIf { it.isNotBlank() }?.let { SlugUtil.slugify(it) }
            ?.takeIf { it.isNotBlank() } ?: SlugUtil.slugify(name)

        val sqlClient = databaseManager.getSqlClient()
        val existingProduct = marketProductDao.getById(id, sqlClient) ?: throw NotFound()

        val slugOwner = marketProductDao.getBySlug(slug, sqlClient)
        if (slugOwner != null && slugOwner.id != id) {
            throw SlugAlreadyExists()
        }

        var imageFileName: String? = existingProduct.imageFileName
        val removeImage = data.getBoolean("removeImage") ?: false

        if (removeImage || fileUpload != null) {
            existingProduct.imageFileName?.let { deleteFile(it) }
            imageFileName = null
        }

        if (fileUpload != null) {
            imageFileName = saveUploadedFile(fileUpload)
        }

        val price = MoneyUtil.toMinor(data.getDouble("price") ?: 0.0)
        val creditPrice = MoneyUtil.toMinor(data.getDouble("creditPrice") ?: 0.0)
        val categoryId = data.getLong("categoryId")?.takeIf { it != -1L }
        val stock = data.getInteger("stock")
        val status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE
        val featured = data.getBoolean("featured") ?: false
        val priority = data.getInteger("priority") ?: 0

        val product = MarketProduct(
            id = id,
            slug = slug,
            name = name,
            description = HtmlSanitizer.sanitizeOrNull(data.getString("description")),
            categoryId = categoryId,
            price = price,
            creditPrice = creditPrice,
            stock = stock,
            requiredProducts = parseRequiredProducts(data.getString("requiredProducts")),
            requireOnlyOne = data.getBoolean("requireOnlyOne") ?: false,
            requiredPermission = data.getString("requiredPermission")?.takeIf { it.isNotBlank() },
            status = status,
            featured = featured,
            durationType = data.getString("durationType")?.let { ProductDurationType.valueOf(it) } ?: ProductDurationType.LIFETIME,
            durationStart = data.getLong("durationStart"),
            durationExpiry = data.getLong("durationExpiry"),
            priority = priority,
            icon = data.getString("icon")?.takeIf { it.isNotBlank() } ?: "fa-box",
            imageFileName = imageFileName,
            actions = sanitizeActions(data.getString("actions")),
            createdAt = existingProduct.createdAt,
            updatedAt = System.currentTimeMillis()
        )

        marketProductDao.update(product, sqlClient)

        // update() no longer writes the stock counter (00 section 8.3); until MK-050's guarded SET / ADJUST replaces this
        // route's stock field, an edited stock is written explicitly on the same client.
        if (existingProduct.stock != stock) marketProductDao.setStock(id, stock, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        val changes = JsonObject()
        if (existingProduct.name != name) changes.put("name", name)
        if (existingProduct.slug != slug) changes.put("slug", slug)
        if (existingProduct.price != price) changes.put("price", MoneyUtil.toDecimal(price))
        if (existingProduct.creditPrice != creditPrice) changes.put("creditPrice", MoneyUtil.toDecimal(creditPrice))
        if (existingProduct.categoryId != categoryId) changes.put("categoryId", categoryId)
        if (existingProduct.stock != stock) changes.put("stock", stock)
        if (existingProduct.status != status) changes.put("status", status.name)
        if (existingProduct.featured != featured) changes.put("featured", featured)
        if (existingProduct.priority != priority) changes.put("priority", priority)
        if (imageFileName != existingProduct.imageFileName) changes.put("image", imageFileName != null)

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketProductLog(userId, username, plugin.pluginId, name, if (changes.isEmpty) null else changes),
            sqlClient
        )

        return Successful()
    }

    private fun validateImage(fileUpload: FileUpload?) {
        if (fileUpload == null) return

        if (fileUpload.size() > 5 * 1024 * 1024) {
            throw BadRequest()
        }

        // The multipart Content-Type header is client-controlled; the magic-byte sniff is authoritative.
        if (ImageUtil.detectImageExtension(File(fileUpload.uploadedFileName())) == null) {
            throw BadRequest()
        }
    }

    private fun parseRequiredProducts(json: String?): List<Long> {
        if (json.isNullOrBlank()) return emptyList()

        return try {
            JsonArray(json).mapNotNull { element ->
                when (element) {
                    is Number -> element.toLong()
                    is String -> element.toLongOrNull()
                    else -> null
                }
            }
        } catch (e: Exception) {
            throw BadRequest()
        }
    }

    // Whitelists action keys per type; drops client-only junk (id, currentInput) and coerces values.
    private fun sanitizeActions(json: String?): String {
        if (json.isNullOrBlank()) return "[]"

        val input = try {
            JsonArray(json)
        } catch (e: Exception) {
            throw BadRequest()
        }

        val output = JsonArray()
        input.forEach { raw ->
            if (raw !is JsonObject) throw BadRequest()

            val type = raw.getString("type") ?: throw BadRequest()
            val actionType = try {
                ProductActionType.valueOf(type)
            } catch (e: Exception) {
                throw BadRequest()
            }

            val clean = JsonObject().put("type", actionType.name)

            when (actionType) {
                ProductActionType.CREDIT -> {
                    val value = when (val rawValue = raw.getValue("value")) {
                        is Number -> rawValue.toDouble()
                        is String -> rawValue.toDoubleOrNull() ?: throw BadRequest()
                        else -> throw BadRequest()
                    }
                    clean.put("value", value)
                }

                ProductActionType.PERMISSION -> {
                    val value = raw.getJsonArray("value") ?: throw BadRequest()
                    if (value.any { it !is String }) throw BadRequest()
                    clean.put("value", value)
                }

                ProductActionType.COMMAND -> {
                    val value = raw.getJsonArray("value") ?: throw BadRequest()
                    if (value.any { it !is String }) throw BadRequest()
                    clean.put("value", value)

                    raw.getInteger("delay")?.let { clean.put("delay", it) }

                    raw.getJsonArray("targetServers")?.let { servers ->
                        val targetServers = JsonArray()
                        servers.forEach { server ->
                            when (server) {
                                is Number -> targetServers.add(server.toLong())
                                is String -> targetServers.add(server.toLongOrNull() ?: throw BadRequest())
                                else -> throw BadRequest()
                            }
                        }
                        clean.put("targetServers", targetServers)
                    }
                }
            }

            output.add(clean)
        }

        return output.encode()
    }

    private fun saveUploadedFile(fileUpload: FileUpload): String {
        // Extension comes from the sniffed byte signature, never from the client-supplied filename.
        val extension = ImageUtil.detectImageExtension(File(fileUpload.uploadedFileName())) ?: throw BadRequest()
        val fileName =
            "product-${System.currentTimeMillis()}-${fileUpload.uploadedFileName().split(File.separator).last()}.$extension"
        val destFile = File(plugin.uploadsDir, fileName)

        File(fileUpload.uploadedFileName()).copyTo(destFile, true)

        ImageUtil.generateThumbnail(destFile, File(plugin.uploadsDir, "thumbnails"))

        return fileName
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
