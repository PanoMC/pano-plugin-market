package com.panomc.plugins.market.routes.api

import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.sqlclient.SqlClient

/**
 * The decision of the public product image route (04 section 3, `images`), apart from the HTTP and the file system:
 * which stored file name a URL `fileName` stands for, or `null` for 404.
 *
 * The URL name is only ever a lookup key. It is looked up on the products first and then on the variants (a variant
 * image); the result is the stored name of the row that matched. An image whose product is not visible (INACTIVE,
 * ARCHIVED, soft deleted) or whose variant is soft deleted is `null`, the same as an unknown name, so a hidden product
 * cannot be told from a missing one.
 */
class ProductImageResolver(
    private val productDao: MarketProductDao,
    private val variantDao: MarketProductVariantDao
) {
    suspend fun resolve(fileName: String, sqlClient: SqlClient): String? {
        val ownProduct = productDao.getByImageFileName(fileName, sqlClient)

        if (ownProduct != null) {
            return ownProduct.imageFileName?.takeIf { visible(ownProduct.status, ownProduct.deletedAt) }
        }

        val variant = variantDao.getByImageFileName(fileName, sqlClient) ?: return null

        if (variant.deletedAt != null) return null

        val product = productDao.getById(variant.productId, sqlClient) ?: return null

        return variant.imageFileName?.takeIf { visible(product.status, product.deletedAt) }
    }

    companion object {
        fun visible(status: MarketStatus, deletedAt: Long?): Boolean =
            deletedAt == null && status != MarketStatus.INACTIVE && status != MarketStatus.ARCHIVED

        /** `If-None-Match` as a list of (optionally weak) validators, or `*`; blank or absent never matches. */
        fun etagMatches(header: String?, etag: String): Boolean {
            if (header.isNullOrBlank()) return false

            return header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
        }
    }
}
