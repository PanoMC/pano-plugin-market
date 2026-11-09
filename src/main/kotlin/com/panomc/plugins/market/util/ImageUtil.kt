package com.panomc.plugins.market.util

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object ImageUtil {
    private const val THUMBNAIL_SIZE = 128

    /** Largest image (width x height) that is ever decoded: a small file can declare a huge canvas and exhaust the heap. */
    const val MAX_PIXELS = 40_000_000L

    /**
     * Width and height from the image header, without decoding the pixels; null when no installed reader knows the format
     * (such a file is never decoded either) or the header cannot be read.
     */
    fun dimensions(file: File): Pair<Int, Int>? = try {
        ImageIO.createImageInputStream(file)?.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: Exception) {
        null
    }

    /** False when the header declares more than [MAX_PIXELS] (or a non-positive size). */
    fun withinPixelLimit(file: File): Boolean {
        val (w, h) = dimensions(file) ?: return true
        return w > 0 && h > 0 && w.toLong() * h.toLong() <= MAX_PIXELS
    }

    /**
     * Sniffs the real image format from the file's magic bytes and returns the canonical extension
     * (webp/jpg/png/gif) or null. Never trust the client's Content-Type header or filename — a spoofed
     * SVG/HTML body stored with an attacker-picked extension would later be served as active content.
     * An image whose header declares more than [MAX_PIXELS] is refused too (null).
     */
    fun detectImageExtension(file: File): String? {
        if (!file.exists()) return null
        if (!withinPixelLimit(file)) return null

        val header = ByteArray(12)
        val read = file.inputStream().use { it.read(header) }
        if (read < 12) return null

        return when {
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> "png"

            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "jpg"

            header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte() -> "gif"

            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                    header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() -> "webp"

            else -> null
        }
    }

    /**
     * Maps a stored (allowlisted) file extension to a safe image mime type for serving.
     * Anything unexpected falls back to application/octet-stream — never svg/html.
     */
    fun getSafeMimeType(fileName: String): String = when (fileName.split(".").last().lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    fun generateThumbnail(originalFile: File, thumbnailsDir: File): Boolean {
        if (!originalFile.exists()) return false

        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }

        val thumbnailFile = File(thumbnailsDir, originalFile.name)
        if (thumbnailFile.exists()) return true

        try {
            if (!withinPixelLimit(originalFile)) return false
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
