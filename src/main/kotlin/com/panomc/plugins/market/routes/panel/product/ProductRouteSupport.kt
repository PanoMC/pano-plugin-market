package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.catalog.ImageChange
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductProviderMetaDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.CatalogService
import com.panomc.plugins.market.util.ImageUtil
import io.vertx.ext.web.FileUpload
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool
import java.io.File

/** The service of the product routes, built on the plugin's beans (stateless: a route keeps one). */
internal fun catalogService(plugin: MarketPlugin): CatalogService {
    val context = plugin.applicationContext
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }

    return CatalogService(
        db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock),
        config = { currentConfig(plugin) },
        clock = SystemClock,
        products = context.getBean(MarketProductDao::class.java),
        variants = context.getBean(MarketProductVariantDao::class.java),
        prices = context.getBean(MarketProductPriceDao::class.java),
        fields = context.getBean(MarketProductFieldDao::class.java),
        bundleItems = context.getBean(MarketBundleItemDao::class.java),
        providerMeta = context.getBean(MarketProductProviderMetaDao::class.java),
        categories = context.getBean(MarketCategoryDao::class.java),
        comparisons = context.getBean(MarketComparisonDao::class.java)
    )
}

/**
 * Every form field of POST / PUT `/products`. All are declared as strings: a multipart form carries only strings and
 * `ProductRequestParser` reads (and judges) the values itself, answering `INVALID_PRODUCT` with `fieldErrors` instead of
 * a generic 400 from the schema layer.
 */
internal val PRODUCT_FORM_FIELDS = listOf(
    "name", "slug", "description", "shortDescription", "categoryId", "price", "creditPrice", "compareAtPrice", "stock",
    "requiredProducts", "requireOnlyOne", "requiredPermission", "status", "featured", "durationType", "durationStart",
    "durationExpiry", "priority", "icon", "actions", "kind", "vatPercent", "physical", "sku", "weightGrams", "lengthMm",
    "widthMm", "heightMm", "hsCode", "originCountry", "billingMode", "periodUnit", "periodCount", "subscriptionMaxCycles",
    "limitPerPlayer", "maxQuantityPerOrder", "cooldownSeconds", "tierRank", "creditAmount", "allowGift", "serverChoices",
    "hasVariants", "variantOptions", "metaTitle", "metaDescription", "variants", "fields", "bundleItems", "prices",
    "providerMeta", "removeImage"
)

internal fun productFormSchema(): ObjectSchemaBuilder {
    val schema = objectSchema()

    PRODUCT_FORM_FIELDS.forEach { schema.optionalProperty(it, stringSchema()) }

    return schema
}

/** The files of one product save: the image parts are validated first, stored second, and removed again on a failed save. */
internal class ProductUploads(private val plugin: MarketPlugin, uploads: List<FileUpload>) {
    class Stored(val productImage: ImageChange, val variantImages: Map<Int, String>)

    private val product: FileUpload?
    private val variants: Map<Int, FileUpload>
    private val saved = mutableListOf<String>()

    init {
        var productPart: FileUpload? = null
        val variantParts = linkedMapOf<Int, FileUpload>()

        uploads.forEach { upload ->
            val name = upload.name()
            val index = VARIANT_PART.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

            when {
                name == "image" && productPart == null -> productPart = upload
                index != null && index < MAX_VARIANT_IMAGES && index !in variantParts -> variantParts[index] = upload
                else -> throw BadRequest()
            }
        }

        product = productPart
        variants = variantParts
        (listOfNotNull(product) + variants.values).forEach { validate(it) }
    }

    /** Stores every part; the names stay registered so [discard] can remove them. */
    fun store(): Stored {
        val productImage: ImageChange = product?.let { ImageChange.Set(save(it)) } ?: ImageChange.Keep
        val variantImages = variants.mapValues { (_, upload) -> save(upload) }

        return Stored(productImage, variantImages)
    }

    /** A failed save: the files stored for it are of no use. */
    fun discard() {
        saved.forEach { deleteFile(plugin, it) }
        saved.clear()
    }

    private fun validate(upload: FileUpload) {
        if (upload.size() > MAX_IMAGE_BYTES) throw BadRequest()

        // The multipart Content-Type header is client-controlled; the magic-byte sniff is authoritative.
        if (ImageUtil.detectImageExtension(File(upload.uploadedFileName())) == null) throw BadRequest()
    }

    private fun save(upload: FileUpload): String {
        // Extension comes from the sniffed byte signature, never from the client-supplied filename.
        val extension = ImageUtil.detectImageExtension(File(upload.uploadedFileName())) ?: throw BadRequest()
        val fileName = "product-${System.currentTimeMillis()}-${saved.size}-${upload.uploadedFileName().split(File.separator).last()}.$extension"
        val destFile = File(plugin.uploadsDir, fileName)

        File(upload.uploadedFileName()).copyTo(destFile, true)
        saved.add(fileName)

        ImageUtil.generateThumbnail(destFile, File(plugin.uploadsDir, "thumbnails"))

        return fileName
    }

    companion object {
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
        const val MAX_VARIANT_IMAGES = 200
        const val BODY_LIMIT = 40L * 1024 * 1024
        private val VARIANT_PART = Regex("^variantImage_([0-9]{1,3})$")
    }
}

internal fun deleteFile(plugin: MarketPlugin, fileName: String) {
    val file = File(plugin.uploadsDir, fileName)
    if (file.exists()) file.delete()

    val thumbnail = File(File(plugin.uploadsDir, "thumbnails"), fileName)
    if (thumbnail.exists()) thumbnail.delete()
}

/** `context.pathParam("id")` as a positive integer id (a `1.5` or `abc` is a 400, never a 500). */
internal fun RoutingContext.productId(): Long = com.panomc.plugins.market.routes.base.parseId(pathParam("id"))
