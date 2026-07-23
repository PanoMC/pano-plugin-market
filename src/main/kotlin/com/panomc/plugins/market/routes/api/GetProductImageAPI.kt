package com.panomc.plugins.market.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.util.ImageUtil
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

/**
 * Public product image. The URL fileName is only ever a DB lookup key ([getByImageFileName]) — the
 * file actually served is the stored path from the row, so no arbitrary client path can be reached.
 * INACTIVE products' images 404 (indistinguishable from missing).
 */
@Endpoint
class GetProductImageAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao
) : Api() {
    override val paths = listOf(Path("/api/market/products/image/:fileName", RouteType.GET))

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    companion object {
        private const val CACHE_TTL_SECONDS = 7 * 24 * 60 * 60 // 1 week
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("fileName", stringSchema()))
            .queryParameter(optionalParam("thumbnail", booleanSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)
        val fileName = parameters.pathParameter("fileName").string

        val sqlClient = databaseManager.getSqlClient()
        val product = marketProductDao.getByImageFileName(fileName, sqlClient)

        if (product?.imageFileName == null || product.status == MarketStatus.INACTIVE) {
            context.response().setStatusCode(404).end()
            return null
        }

        val isThumbnail = parameters.queryParameter("thumbnail")?.boolean ?: false
        val file = if (isThumbnail) {
            getThumbnailFile(product.imageFileName)
        } else {
            File(plugin.uploadsDir, product.imageFileName)
        }

        if (!file.exists()) {
            context.response().setStatusCode(404).end()
            return null
        }

        val etag = "\"${product.imageFileName}\""
        // Force a safe image Content-Type from the allowlisted stored extension — never svg/html.
        val mimeType = ImageUtil.getSafeMimeType(file.name)

        val response = context.response()
        response.putHeader("Content-Type", mimeType)
        response.putHeader("Content-Disposition", "inline")
        response.putHeader("X-Content-Type-Options", "nosniff")
        response.putHeader("ETag", etag)
        response.putHeader("Cache-Control", "public, max-age=$CACHE_TTL_SECONDS, immutable")

        try {
            response.sendFile(file.absolutePath)
        } catch (_: Exception) {
        }

        return null
    }

    private fun getThumbnailFile(fileName: String): File {
        val originalFile = File(plugin.uploadsDir, fileName)

        if (!originalFile.exists()) return originalFile

        ImageUtil.generateThumbnail(originalFile, plugin.thumbnailsDir)

        val thumbnailFile = File(plugin.thumbnailsDir, fileName)
        return if (thumbnailFile.exists()) thumbnailFile else originalFile
    }
}
