package com.panomc.plugins.market.util

import com.panomc.plugins.market.db.migration.MarketMigration10to11
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Thumbnails (version 2): sharp short side, JPEG for opaque and PNG for transparent images, the 10 -> 11 rewrite. */
class ImageThumbnailTest {
    @TempDir
    lateinit var dir: File

    private val uploads by lazy { File(dir, "uploads").also { it.mkdirs() } }
    private val thumbnails by lazy { File(uploads, "thumbnails") }

    private fun image(name: String, width: Int, height: Int, transparent: Boolean = false, format: String = "png"): File {
        val type = if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val img = BufferedImage(width, height, type)
        val g = img.createGraphics()
        g.color = Color(30, 120, 200)
        g.fillRect(0, 0, width, height)
        g.color = Color.WHITE
        g.drawLine(0, 0, width, height)
        if (transparent) {
            g.composite = java.awt.AlphaComposite.Clear
            g.fillRect(0, 0, width / 4, height / 4)
        }
        g.dispose()
        return File(uploads, name).also { ImageIO.write(img, format, it) }
    }

    private fun read(file: File) = ImageIO.read(file)

    @Test
    fun `the short side is sharp enough for a square card and the long side is capped`() {
        assertEquals(1280 to 640, ImageUtil.thumbnailSize(2000, 1000))
        assertEquals(640 to 1280, ImageUtil.thumbnailSize(1000, 2000))
        assertEquals(1280 to 427, ImageUtil.thumbnailSize(3000, 1000), "a very wide image is limited by its long side")
        assertEquals(640 to 640, ImageUtil.thumbnailSize(4000, 4000))
        assertEquals(500 to 300, ImageUtil.thumbnailSize(500, 300), "never scaled up")
    }

    @Test
    fun `an opaque png becomes a smaller jpeg served as image jpeg`() {
        val original = image("photo.png", 2400, 1800)

        assertTrue(ImageUtil.generateThumbnail(original, thumbnails))

        val thumb = File(thumbnails, "photo.png")
        val decoded = read(thumb)
        assertEquals(853, decoded.width)
        assertEquals(640, decoded.height)
        assertEquals("image/jpeg", ImageUtil.servedMimeType(thumb))
        assertEquals("image/png", ImageUtil.servedMimeType(original))
        assertTrue(thumbnails.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a transparent png stays a png`() {
        val original = image("logo.png", 1600, 1600, transparent = true)

        assertTrue(ImageUtil.generateThumbnail(original, thumbnails))

        val thumb = File(thumbnails, "logo.png")
        assertEquals("image/png", ImageUtil.servedMimeType(thumb))
        val decoded = read(thumb)
        assertEquals(640, decoded.width)
        assertEquals(0, decoded.getRGB(5, 5) ushr 24, "the transparent corner stays transparent")
    }

    @Test
    fun `an image that is already small is copied as it is`() {
        val original = image("small.jpg", 400, 300, format = "jpg")

        assertTrue(ImageUtil.generateThumbnail(original, thumbnails))

        assertArrayEquals(original.readBytes(), File(thumbnails, "small.jpg").readBytes())
    }

    @Test
    fun `a file that is not an image gives no thumbnail`() {
        val bogus = File(uploads, "x.png").also { it.writeText("not an image at all") }

        assertFalse(ImageUtil.generateThumbnail(bogus, thumbnails))
        assertFalse(File(thumbnails, "x.png").exists())
    }

    @Test
    fun `the thumbnail etag carries the version, the original's does not`() {
        assertEquals("\"t${ImageUtil.THUMBNAIL_VERSION}-a.png\"", ImageUtil.etag("a.png", true))
        assertEquals("\"a.png\"", ImageUtil.etag("a.png", false))
        assertNotEquals(ImageUtil.etag("a.png", true), ImageUtil.etag("a.png", false))
    }

    @Test
    fun `migration 10 to 11 writes old thumbnails again and removes ones without an original`() {
        val original = image("old.png", 2000, 2000)
        thumbnails.mkdirs()
        // a version 1 thumbnail: 128 px
        ImageIO.write(BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB), "png", File(thumbnails, "old.png"))
        File(thumbnails, "gone.png").writeText("orphan")
        File(thumbnails, ".old.png.123.tmp").writeText("leftover")

        val report = MarketMigration10to11.regenerate(uploads, thumbnails)

        assertEquals(1, report.written)
        assertEquals(2, report.removed)
        assertEquals(0, report.failed)
        assertEquals(640, read(File(thumbnails, "old.png")).width)
        assertFalse(File(thumbnails, "gone.png").exists())
        assertTrue(original.exists(), "the original is never touched")

        // run again (an interrupted start): same result
        assertEquals(1, MarketMigration10to11.regenerate(uploads, thumbnails).written)
        assertEquals(0, MarketMigration10to11.regenerate(File(dir, "missing"), File(dir, "missing-thumbs")).written, "no thumbnails folder: nothing to do")
    }
}
