package com.panomc.plugins.market.pdf

import com.panomc.plugins.market.spi.common.Address
import io.vertx.core.json.JsonObject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The label could not be produced: the panel answers 500 `INTERNAL`. */
class LabelRenderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The words on the label, in the language of the store (`tr`, `en-US`, `ru`; anything else is English). */
class LabelCaptions(val to: String, val from: String, val order: String, val weight: String, val reference: String) {
    companion object {
        private val EN = LabelCaptions("Deliver to", "From", "Order", "Weight", "Ref")
        private val TR = LabelCaptions("Alıcı", "Gönderici", "Sipariş", "Ağırlık", "Ref")
        private val RU = LabelCaptions("Получатель", "Отправитель", "Заказ", "Вес", "Реф.")

        fun of(locale: String?): LabelCaptions = when (locale?.substringBefore('-')?.lowercase()) {
            "tr" -> TR
            "ru" -> RU
            else -> EN
        }
    }
}

/** What one generic label shows. The address blocks are already formatted lines (see [LabelAddress]). */
class GenericLabel(
    val sender: List<String>,
    val recipient: List<String>,
    /** `market_order.id`. */
    val orderId: Long,
    val publicId: String?,
    val weightGrams: Int?,
    /** Tracking number, else the merchant reference: the text of the Code 128 barcode. */
    val barcode: String,
    /** `A6` (default) or `A4`. */
    val paper: String = "A6",
    val captions: LabelCaptions = LabelCaptions.of(null)
)

/** Address lines of a label: name, company, street, area, postal code and city, country, phone. Blank parts are left out. */
object LabelAddress {
    fun lines(a: Address): List<String> = lines(
        listOfNotNull(a.firstName, a.lastName).joinToString(" "), a.company, a.line1, a.line2, a.neighborhood, a.district,
        listOfNotNull(a.postalCode, a.city, a.state).joinToString(" "), a.country, a.phone
    )

    fun lines(json: JsonObject?): List<String> = if (json == null) emptyList() else {
        fun text(key: String) = json.getValue(key)?.toString()

        lines(
            listOfNotNull(text("firstName"), text("lastName")).joinToString(" "), text("company"), text("line1"), text("line2"), text("neighborhood"), text("district"),
            listOfNotNull(text("postalCode"), text("city"), text("state")).joinToString(" "), text("country"), text("phone")
        )
    }

    private fun lines(vararg parts: String?): List<String> = parts.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
}

/**
 * Market's own shipping label (10 section 9.8): sender, recipient, order number and `publicId`, parcel weight and the Code 128 barcode of the
 * tracking number (or the merchant reference). One page, A6 or A4 portrait, Noto Sans embedded as a subset (Turkish and Cyrillic names print).
 * Synchronous and CPU bound: call it on a worker thread. Nothing is stored.
 */
object GenericLabelRenderer {
    private const val QUIET_MODULES = 10

    private val regularBytes: ByteArray by lazy { resource("fonts/NotoSans-Regular.ttf") }
    private val boldBytes: ByteArray by lazy { resource("fonts/NotoSans-Bold.ttf") }

    private fun resource(path: String): ByteArray =
        (GenericLabelRenderer::class.java.classLoader.getResourceAsStream(path) ?: throw LabelRenderException("font resource $path is missing")).use { it.readBytes() }

    fun pageOf(paper: String?): PDRectangle = if (paper.equals("A4", ignoreCase = true)) PDRectangle.A4 else PDRectangle.A6

    fun render(label: GenericLabel): ByteArray = try {
        draw(label)
    } catch (e: LabelRenderException) {
        throw e
    } catch (e: Exception) {
        throw LabelRenderException("label rendering failed: ${e.message}", e)
    }

    private fun draw(label: GenericLabel): ByteArray {
        PDDocument().use { document ->
            val regular = PDType0Font.load(document, ByteArrayInputStream(regularBytes), true)
            val bold = PDType0Font.load(document, ByteArrayInputStream(boldBytes), true)
            val size = pageOf(label.paper)
            val a4 = size.width > 400f
            val margin = if (a4) 36f else 16f
            val scale = if (a4) 1.35f else 1f
            val width = size.width - 2 * margin
            val page = PDPage(size)
            val c = label.captions

            document.addPage(page)

            val barcodeText = Code128.sanitize(label.barcode.ifBlank { "-" })
            val barcodeHeight = 60f * scale
            // the barcode block sits at the bottom: bars, the human readable text under them
            val barcodeBottom = margin + 4f
            val barcodeTop = barcodeBottom + 12f * scale + 4f + barcodeHeight
            var y = size.height - margin

            PDPageContentStream(document, page).use { cs ->
                fun line(f: PDFont, fontSize: Float, text: String, x: Float = margin, color: Color = Color.BLACK) {
                    cs.beginText()
                    cs.setFont(f, fontSize)
                    cs.setNonStrokingColor(color)
                    cs.newLineAtOffset(x, y - fontSize)
                    cs.showText(PdfText.sanitize(f, text))
                    cs.endText()
                    y -= fontSize * 1.25f
                }

                fun block(f: PDFont, fontSize: Float, lines: List<String>, maxLines: Int, color: Color = Color.BLACK) {
                    var drawn = 0

                    for (raw in lines) {
                        for (part in wrap(f, fontSize, PdfText.sanitize(f, raw), width)) {
                            if (drawn >= maxLines || y - fontSize < barcodeTop + 70f * scale) return

                            line(f, fontSize, part, color = color)
                            drawn++
                        }
                    }
                }

                fun rule() {
                    y -= 4f
                    cs.setStrokingColor(Color(0xC8, 0xC8, 0xC8))
                    cs.setLineWidth(0.6f)
                    cs.moveTo(margin, y)
                    cs.lineTo(margin + width, y)
                    cs.stroke()
                    y -= 6f
                }

                line(bold, 7.5f * scale, c.to.uppercase(), color = Color(0x55, 0x55, 0x55))
                y -= 2f
                block(bold, 13f * scale, label.recipient.take(1), 2)
                block(regular, 11f * scale, label.recipient.drop(1), 8)
                rule()
                line(bold, 7.5f * scale, c.from.uppercase(), color = Color(0x55, 0x55, 0x55))
                y -= 2f
                block(regular, 9f * scale, label.sender, 6)
                rule()

                val order = buildString {
                    append(c.order).append(" #").append(label.orderId)

                    if (!label.publicId.isNullOrBlank()) append("  ").append(label.publicId)
                }

                line(bold, 12f * scale, order)

                if (label.weightGrams != null) line(regular, 10f * scale, "${c.weight}: ${weightText(label.weightGrams)}")

                drawBarcode(cs, barcodeText, margin, width, barcodeBottom + 12f * scale + 4f, barcodeHeight, scale)

                val textWidth = regular.getStringWidth(PdfText.sanitize(regular, barcodeText)) / 1000f * 10f * scale
                val tx = margin + (width - textWidth) / 2f

                cs.beginText()
                cs.setFont(regular, 10f * scale)
                cs.setNonStrokingColor(Color.BLACK)
                cs.newLineAtOffset(maxOf(margin, tx), barcodeBottom)
                cs.showText(PdfText.sanitize(regular, barcodeText))
                cs.endText()
            }

            val out = ByteArrayOutputStream()

            document.save(out)

            return out.toByteArray()
        }
    }

    private fun weightText(grams: Int): String =
        if (grams >= 1000) "${grams / 1000}.${((grams % 1000) / 10).toString().padStart(2, '0')} kg" else "$grams g"

    private fun drawBarcode(cs: PDPageContentStream, text: String, margin: Float, width: Float, bottom: Float, height: Float, scale: Float) {
        val modules = Code128.modules(text)
        val moduleWidth = minOf(1.8f * scale, width / (modules.length + 2 * QUIET_MODULES))
        val drawn = modules.length * moduleWidth
        var x = margin + (width - drawn) / 2f

        cs.setNonStrokingColor(Color.BLACK)

        var i = 0

        while (i < modules.length) {
            if (modules[i] == '1') {
                var j = i

                while (j < modules.length && modules[j] == '1') j++

                cs.addRect(x + i * moduleWidth, bottom, (j - i) * moduleWidth, height)
                i = j
            } else {
                i++
            }
        }

        cs.fill()
    }

    /** Greedy word wrap; a word wider than the column is cut by characters. */
    private fun wrap(font: PDFont, size: Float, text: String, maxWidth: Float): List<String> {
        fun w(s: String) = font.getStringWidth(s) / 1000f * size

        val lines = ArrayList<String>()
        var current = ""

        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            var rest = word

            while (w(rest) > maxWidth && rest.length > 1) {
                var cut = rest.length - 1

                while (cut > 1 && w(rest.substring(0, cut)) > maxWidth) cut--

                if (current.isNotEmpty()) {
                    lines += current
                    current = ""
                }

                lines += rest.substring(0, cut)
                rest = rest.substring(cut)
            }

            val candidate = if (current.isEmpty()) rest else "$current $rest"

            if (w(candidate) <= maxWidth) {
                current = candidate
            } else {
                lines += current
                current = rest
            }
        }

        if (current.isNotEmpty()) lines += current

        return lines
    }
}
