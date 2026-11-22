package com.panomc.plugins.market.db.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.db.DatabaseMigration
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.util.ImageUtil
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Scheme version 10 to 11: no table changes. The product and category thumbnails written before are replaced by
 * sharper ones (thumbnail version 2, see [ImageUtil.THUMBNAIL_VERSION]). Every thumbnail whose original is still in
 * the uploads folder is written again, and a thumbnail without an original is deleted. The step can run again after
 * an interruption, and a file that fails is logged without stopping it. A fresh install starts at 11 and has no old
 * thumbnails.
 */
@Migration
class MarketMigration10to11(private val plugin: MarketPlugin) :
    DatabaseMigration(10, 11, "Sharper product and category thumbnails: write the existing ones again") {
    override val handlers: List<suspend (SqlClient) -> Unit> = listOf(
        { _: SqlClient -> withContext(Dispatchers.IO) { regenerate(plugin.uploadsDir, plugin.thumbnailsDir) } }
    )

    companion object {
        private val logger = LoggerFactory.getLogger(MarketMigration10to11::class.java)

        class Report(val written: Int, val removed: Int, val failed: Int)

        /** Writes every thumbnail in [thumbnailsDir] again from its original in [uploadsDir]. */
        fun regenerate(uploadsDir: File, thumbnailsDir: File): Report {
            var written = 0
            var removed = 0
            var failed = 0

            for (thumbnail in thumbnailsDir.listFiles().orEmpty()) {
                if (!thumbnail.isFile) continue

                try {
                    val original = File(uploadsDir, thumbnail.name)

                    if (thumbnail.name.startsWith(".") || !original.isFile) {
                        if (thumbnail.delete()) removed++
                        continue
                    }

                    if (ImageUtil.regenerateThumbnail(original, thumbnailsDir)) written++ else failed++
                } catch (e: Exception) {
                    failed++
                    logger.warn("Market migration 10 -> 11: thumbnail {} not written again: {}", thumbnail.name, e.toString())
                }
            }

            logger.info("Market migration 10 -> 11: {} thumbnails written again, {} removed, {} failed", written, removed, failed)
            return Report(written, removed, failed)
        }
    }
}
