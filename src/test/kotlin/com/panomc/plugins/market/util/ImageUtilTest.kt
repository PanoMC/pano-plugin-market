package com.panomc.plugins.market.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

/** An image that declares a huge canvas is refused before it is decoded (decompression bomb); a normal one still works. */
class ImageUtilTest {
    /** A valid PNG of [w] x [h] grey pixels whose rows are all zero: a few KB however large the canvas. */
    private fun bombPng(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray(Charsets.US_ASCII) + body
            data.write(typed)
            data.writeInt(CRC32().also { it.update(typed) }.value.toInt())
        }
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(0); writeByte(0); writeByte(0); writeByte(0) }
        }.toByteArray()
        chunk("IHDR", ihdr)
        val deflater = Deflater(9).apply { setInput(ByteArray(64 * 1024)); finish() }
        val buf = ByteArray(64 * 1024)
        val n = deflater.deflate(buf)
        chunk("IDAT", buf.copyOf(n))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `a huge declared canvas is refused on upload and never decoded for a thumbnail`(@TempDir dir: Path) {
        val bomb = File(dir.toFile(), "bomb.png").also { it.writeBytes(bombPng(60_000, 60_000)) }
        assertTrue(bomb.length() < 10_000)
        assertEquals(60_000 to 60_000, ImageUtil.dimensions(bomb))
        assertFalse(ImageUtil.withinPixelLimit(bomb))
        assertNull(ImageUtil.detectImageExtension(bomb))
        assertFalse(ImageUtil.generateThumbnail(bomb, File(dir.toFile(), "thumbs")))
    }

    @Test
    fun `a normal image is accepted and gets a thumbnail`(@TempDir dir: Path) {
        val img = File(dir.toFile(), "ok.png")
        ImageIO.write(BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), "png", img)
        assertEquals("png", ImageUtil.detectImageExtension(img))
        val thumbs = File(dir.toFile(), "thumbs")
        assertTrue(ImageUtil.generateThumbnail(img, thumbs))
        assertEquals(128, ImageIO.read(File(thumbs, "ok.png")).width)
    }
}
