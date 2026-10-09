package com.panomc.plugins.market.routes.api

import com.panomc.platform.error.NotFound
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.util.ImageUtil
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

/**
 * Public category image (04 section 3, `PUB`). The URL fileName is only ever a DB lookup key ([getByImageFileName]) — the
 * file actually served is the stored path from the row, so no arbitrary client path can be reached.
 * INACTIVE categories' images 404 (indistinguishable from missing).
 */
@Endpoint
class GetCategoryImageAPI(
    private val plugin: MarketPlugin,
    private val marketCategoryDao: MarketCategoryDao
) : MarketApi() {
    override val paths = listOf(Path("/categories/image/:fileName", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "A category image file.",
        tag = "categories",
        binary = true,
        errors = listOf(NotFound::class)
    )

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

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val parameters = getParameters(context)
        val fileName = parameters.pathParameter("fileName").string

        val sqlClient = databaseManager.getSqlClient()
        val category = marketCategoryDao.getByImageFileName(fileName, sqlClient)

        if (category?.imageFileName == null || category.status == MarketStatus.INACTIVE) {
            context.response().setStatusCode(404).end()
            return null
        }

        val isThumbnail = parameters.queryParameter("thumbnail")?.boolean ?: false
        val file = if (isThumbnail) {
            getThumbnailFile(category.imageFileName)
        } else {
            File(plugin.uploadsDir, category.imageFileName)
        }

        if (!file.exists()) {
            context.response().setStatusCode(404).end()
            return null
        }

        val etag = "\"${category.imageFileName}\""
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
