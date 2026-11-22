package com.panomc.plugins.market.util

import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.plugins.jpeg.JPEGImageWriteParam

object ImageUtil {
    /**
     * Thumbnail settings. Version 1 fitted the long side into 128 px and looked blurry in the store's square cards.
     * Raise [THUMBNAIL_VERSION] when the settings change: it is part of the thumbnail ETag and of the store's thumbnail
     * URLs (`v=`), and a migration writes the existing thumbnails again.
     */
    const val THUMBNAIL_VERSION = 2
    private const val THUMBNAIL_SHORT_SIDE = 640.0
    private const val THUMBNAIL_LONG_SIDE = 1280.0
    private const val JPEG_QUALITY = 0.85f

    private val logger = LoggerFactory.getLogger(ImageUtil::class.java)

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

        return formatOf(header)
    }

    /** The canonical extension (webp/jpg/png/gif) the first 12 bytes of a file name, or null. */
    private fun formatOf(header: ByteArray): String? {
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

    /**
     * Writes the thumbnail of [originalFile] to [thumbnailsDir] (same file name) unless it is there already. The store
     * shows thumbnails in square cards with `object-fit: cover`, so the short side has to be sharp: it is scaled to at
     * most [THUMBNAIL_SHORT_SIDE] px and the long side to at most [THUMBNAIL_LONG_SIDE] px, never up. An opaque image
     * becomes a progressive JPEG of quality [JPEG_QUALITY], one with transparent pixels a PNG, and an image that is already
     * that small is copied as it is. The thumbnail's format can differ from its name: serve it with [servedMimeType].
     */
    fun generateThumbnail(originalFile: File, thumbnailsDir: File): Boolean {
        if (!originalFile.exists()) return false

        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }

        val thumbnailFile = File(thumbnailsDir, originalFile.name)
        if (thumbnailFile.exists()) return true

        // Written next to it and moved in place: a request at the same time never reads half a file.
        val temp = File(thumbnailsDir, ".${originalFile.name}.${System.nanoTime()}.tmp")

        try {
            val (width, height) = dimensions(originalFile) ?: return false
            if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > MAX_PIXELS) return false

            val (targetWidth, targetHeight) = thumbnailSize(width, height)

            if (targetWidth == width && targetHeight == height) {
                Files.copy(originalFile.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                val originalImage = ImageIO.read(originalFile) ?: return false
                val resized = downscale(originalImage, targetWidth, targetHeight)
                val written = if (hasTransparentPixel(resized)) ImageIO.write(resized, "png", temp) else writeJpeg(resized, temp)

                if (!written) return false
            }

            Files.move(temp.toPath(), thumbnailFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } catch (e: Exception) {
            logger.warn("Thumbnail of {} not written: {}", originalFile.name, e.toString())
            return false
        } finally {
            temp.delete()
        }
    }

    /** Deletes the thumbnail of [originalFile] and writes it again with the current settings. */
    fun regenerateThumbnail(originalFile: File, thumbnailsDir: File): Boolean {
        File(thumbnailsDir, originalFile.name).delete()
        return generateThumbnail(originalFile, thumbnailsDir)
    }

    /** Width and height of the thumbnail of a [width] x [height] image (never larger than the image). */
    fun thumbnailSize(width: Int, height: Int): Pair<Int, Int> {
        val short = minOf(width, height).toDouble()
        val long = maxOf(width, height).toDouble()
        val scale = minOf(1.0, THUMBNAIL_SHORT_SIDE / short, THUMBNAIL_LONG_SIDE / long)

        if (scale >= 1.0) return width to height

        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** The ETag of an image response. A thumbnail's changes with [THUMBNAIL_VERSION], so a browser drops an older one. */
    fun etag(storedName: String, thumbnail: Boolean): String =
        if (thumbnail) "\"t$THUMBNAIL_VERSION-$storedName\"" else "\"$storedName\""

    /**
     * The Content-Type to serve [file] with: the format its first bytes name (the thumbnail of a PNG can be a JPEG),
     * from the same allowlist as [getSafeMimeType]. The file name decides when the bytes name none of them.
     */
    fun servedMimeType(file: File): String {
        val header = ByteArray(12)
        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (e: Exception) {
            -1
        }

        return when (if (read >= 12) formatOf(header) else null) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> getSafeMimeType(file.name)
        }
    }

    /** Halves the image while it is at least twice the target (keeps quality at any ratio), then scales it to the target. */
    private fun downscale(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        var current = toArgb(source)

        while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
            current = scaled(current, current.width / 2, current.height / 2)
        }

        return if (current.width == targetWidth && current.height == targetHeight) current else scaled(current, targetWidth, targetHeight)
    }

    private fun toArgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_ARGB) return source

        val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return copy
    }

    private fun scaled(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return target
    }

    private fun hasTransparentPixel(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) return false

        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getRGB(0, y, image.width, 1, row, 0, image.width)
            if (row.any { (it ushr 24) != 0xFF }) return true
        }
        return false
    }

    private fun writeJpeg(image: BufferedImage, target: File): Boolean {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()

        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return false
        val writer = writers.next()

        try {
            val output = ImageIO.createImageOutputStream(target) ?: return false
            output.use {
                writer.output = it
                val param = writer.defaultWriteParam
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = JPEG_QUALITY
                if (param.canWriteProgressive()) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
                if (param is JPEGImageWriteParam) param.optimizeHuffmanTables = true
                writer.write(null, IIOImage(rgb, null, null), param)
            }
        } finally {
            writer.dispose()
        }

        return true
    }
}
