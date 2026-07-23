package com.panomc.plugins.market.util

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object ImageUtil {
    private const val THUMBNAIL_SIZE = 128

    fun generateThumbnail(originalFile: File, thumbnailsDir: File): Boolean {
        if (!originalFile.exists()) return false

        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }

        val thumbnailFile = File(thumbnailsDir, originalFile.name)
        if (thumbnailFile.exists()) return true

        try {
            val originalImage = ImageIO.read(originalFile) ?: return false
            val type = if (originalImage.type == 0) BufferedImage.TYPE_INT_ARGB else originalImage.type

            var width = originalImage.width
            var height = originalImage.height

            if (width > THUMBNAIL_SIZE || height > THUMBNAIL_SIZE) {
                if (width > height) {
                    height = (height * THUMBNAIL_SIZE) / width
                    width = THUMBNAIL_SIZE
                } else {
                    width = (width * THUMBNAIL_SIZE) / height
                    height = THUMBNAIL_SIZE
                }
            }

            val resizedImage = BufferedImage(width, height, type)
            val g = resizedImage.createGraphics()
            g.drawImage(originalImage.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()

            val extension = originalFile.name.split(".").last().lowercase()
            val formatName = when (extension) {
                "png" -> "png"
                "gif" -> "gif"
                else -> "jpg"
            }

            return ImageIO.write(resizedImage, formatName, thumbnailFile)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
