package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.catalog.ImageChange
import com.panomc.plugins.market.routes.panel.product.catalogService
import com.panomc.plugins.market.routes.panel.product.deleteFile
import com.panomc.plugins.market.service.CatalogService
import com.panomc.plugins.market.util.ImageUtil
import io.vertx.ext.web.FileUpload
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

internal fun categoryCatalog(plugin: MarketPlugin): CatalogService = catalogService(plugin)

/**
 * Every form field of POST / PUT `/categories`, all declared as strings: the values are judged by `CategoryRules`, which
 * answers `400 BAD_REQUEST {fieldErrors}` with a path instead of a generic schema error, and a key that was not sent stays
 * out of the body so a PUT is a partial update.
 */
internal fun categoryFormSchema(): ObjectSchemaBuilder {
    val schema = objectSchema()

    listOf("name", "description", "icon", "color", "status", "position", "parentId", "tiered", "upgradeMode", "removeImage")
        .forEach { schema.optionalProperty(it, stringSchema()) }

    return schema
}

/** The one optional `image` part of a category save: validated first, stored second, removed again on a failed save. */
internal class CategoryUpload(private val plugin: MarketPlugin, uploads: List<FileUpload>) {
    private val upload: FileUpload?
    private var saved: String? = null

    init {
        if (uploads.size > 1) throw BadRequest()

        upload = uploads.firstOrNull()

        if (upload != null) {
            if (upload.name() != "image") throw BadRequest()
            if (upload.size() > MAX_IMAGE_BYTES) throw BadRequest()

            // The multipart Content-Type header is client-controlled; the magic-byte sniff is authoritative.
            if (ImageUtil.detectImageExtension(File(upload.uploadedFileName())) == null) throw BadRequest()
        }
    }

    /** The change a save applies: the new file, else `removeImage`, else nothing. */
    fun store(removeImage: Boolean): ImageChange {
        val part = upload ?: return if (removeImage) ImageChange.Remove else ImageChange.Keep

        val extension = ImageUtil.detectImageExtension(File(part.uploadedFileName())) ?: throw BadRequest()
        val fileName = "category-${System.currentTimeMillis()}-${part.uploadedFileName().split(File.separator).last()}.$extension"
        val destFile = File(plugin.uploadsDir, fileName)

        File(part.uploadedFileName()).copyTo(destFile, true)
        saved = fileName

        ImageUtil.generateThumbnail(destFile, plugin.thumbnailsDir)

        return ImageChange.Set(fileName)
    }

    fun discard() {
        saved?.let { deleteFile(plugin, it) }
        saved = null
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
    }
}
