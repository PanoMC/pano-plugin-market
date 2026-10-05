package com.panomc.plugins.market.pdf

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.apache.fontbox.FontBoxFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.font.CIDFontMapping
import org.apache.pdfbox.pdmodel.font.FontMapper
import org.apache.pdfbox.pdmodel.font.FontMapping
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.vandeseer.easytable.RepeatedHeaderTableDrawer
import org.vandeseer.easytable.TableDrawer
import org.vandeseer.easytable.settings.HorizontalAlignment
import org.vandeseer.easytable.settings.VerticalAlignment
import org.vandeseer.easytable.structure.Row
import org.vandeseer.easytable.structure.Table
import org.vandeseer.easytable.structure.cell.TextCell
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** The PDF could not be produced (12 section 8.3): the download answers 500 `INVOICE_RENDER_FAILED`, the mail goes out without the attachment. */
class InvoiceRenderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Everything the renderer needs to turn numbers and keys into words, resolved by the caller beforehand (12 section 8.2: the renderer has no
 * suspend calls and no database access). All amounts are in the snapshot's currency.
 */
interface InvoiceTexts {
    /** `invoice.*` label of the document's locale with its `{var}` placeholders filled. Never null. */
    fun label(key: String, vars: Map<String, Any?> = emptyMap()): String

    /** [amount] is x100 in the snapshot currency, e.g. `1.234,56 TL`. */
    fun money(amount: Long): String

    /** A date in the store time zone and the document's pattern. */
    fun date(ms: Long): String

    /** [bp] basis points. */
    fun percent(bp: Long): String

    /** The name of the credits (`MarketConfig.creditName`), used by "Paid with <creditName>". */
    val creditName: String
}

/** `PdfText.sanitize` (12 section 8.2): rendering never throws on Unicode input. */
object PdfText {
    /**
     * [s] without control characters (`\t` becomes a space), each code point [font] cannot encode replaced by `?`. Line breaks are not
     * text: the caller splits on them before it asks.
     */
    fun sanitize(font: PDFont, s: String): String {
        val out = StringBuilder(s.length)
        var i = 0

        while (i < s.length) {
            val cp = s.codePointAt(i)

            i += Character.charCount(cp)

            when {
                cp == '\t'.code -> out.append(' ')
                Character.getType(cp) == Character.CONTROL.toInt() -> {}
                Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029 -> {}
                canEncode(font, cp) -> out.appendCodePoint(cp)
                else -> out.append('?')
            }
        }

        return out.toString()
    }

    private fun canEncode(font: PDFont, cp: Int): Boolean = try {
        font.encode(String(Character.toChars(cp)))

        true
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: Exception) {
        false
    }
}

/**
 * The invoice and credit-note PDF (12 section 8.2). `render` is synchronous and CPU bound: call it inside `vertx.executeBlocking`. The same
 * snapshot gives the same bytes: the creation date is the issue time, the trailer id is a hash of the snapshot, nothing random is written.
 *
 * A4 portrait, margins 40 pt, Noto Sans 9 pt body / 8 pt table / 16 pt bold title, both weights embedded as subsets; the standard 14 fonts and
 * the system fonts are never looked at.
 */
object InvoicePdfRenderer {
    private const val MARGIN = 40f
    private const val BODY = 9
    private const val TABLE_FONT = 8
    private const val TITLE = 16
    private const val FOOTER_FONT = 7
    private const val FOOTER_LINES = 4
    private const val LOGO_MAX_W = 140f
    private const val LOGO_MAX_H = 50f
    private const val LEADING = 1.25f

    private val GRAY = Color(0xF0, 0xF0, 0xF0)
    private val RULE = Color(0xC8, 0xC8, 0xC8)
    private val MUTED = Color(0x55, 0x55, 0x55)

    init {
        // Before anything of PDFBox or easytable runs: easytable's `Table` creates a standard-14 Helvetica as a static default, which makes
        // PDFBox's default mapper scan the system fonts and write `~/.pdfbox.cache` into the home of whoever runs Pano. Market embeds its own
        // fonts and never asks the mapper for anything real, so the mapper it gets knows one font and never looks at the disk.
        FontMappers.set(EmbeddedFontsOnly)
    }

    private val regularBytes: ByteArray by lazy { resource("fonts/NotoSans-Regular.ttf") }
    private val boldBytes: ByteArray by lazy { resource("fonts/NotoSans-Bold.ttf") }

    private fun resource(path: String): ByteArray =
        (InvoicePdfRenderer::class.java.classLoader.getResourceAsStream(path) ?: throw InvoiceRenderException("font resource $path is missing"))
            .use { it.readBytes() }

    /** The one font the mapper hands out for every request (only the generic metrics of the unused default Helvetica ever read it). */
    private object EmbeddedFontsOnly : FontMapper {
        private val fallback: TrueTypeFont by lazy { TTFParser().parse(RandomAccessReadBuffer(regularBytes)) }

        override fun getTrueTypeFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<TrueTypeFont> = FontMapping(fallback, true)

        override fun getFontBoxFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<FontBoxFont> = FontMapping(fallback, true)

        override fun getCIDFont(baseFont: String?, fontDescriptor: PDFontDescriptor?, cidSystemInfo: PDCIDSystemInfo?): CIDFontMapping =
            CIDFontMapping(null, fallback, true)
    }

    /** The fonts of one document. */
    private class Fonts(val regular: PDFont, val bold: PDFont) {
        fun of(bold: Boolean) = if (bold) this.bold else regular
    }

    fun render(snapshot: JsonObject, texts: InvoiceTexts, logo: ByteArray?): ByteArray = try {
        draw(snapshot, texts, logo)
    } catch (e: InvoiceRenderException) {
        throw e
    } catch (e: Exception) {
        throw InvoiceRenderException("invoice PDF rendering failed: ${e.message}", e)
    }

    private fun draw(snap: JsonObject, texts: InvoiceTexts, logoBytes: ByteArray?): ByteArray {
        val creditNote = snap.getString("type") == "CREDIT_NOTE"

        PDDocument().use { document ->
            val fonts = Fonts(
                PDType0Font.load(document, ByteArrayInputStream(regularBytes), true),
                PDType0Font.load(document, ByteArrayInputStream(boldBytes), true)
            )
            val page = PDRectangle.A4
            val contentWidth = page.width - 2 * MARGIN

            fun t(s: String?, bold: Boolean = false) = PdfText.sanitize(fonts.of(bold), s.orEmpty())

            val footer = footerLines(snap, fonts, contentWidth)
            val footerHeight = (footer.size + 1) * FOOTER_FONT * LEADING + 8f
            val bottom = MARGIN + footerHeight + 6f

            val first = PDPage(page)

            document.addPage(first)

            var y = page.height - MARGIN

            PDPageContentStream(document, first).use { cs ->
                y = header(cs, document, snap, texts, fonts, logoBytes, creditNote, y, page.width)
                y = parties(cs, snap, texts, fonts, y, contentWidth)
            }

            y -= 14f
            y = lineTable(document, snap, texts, fonts, contentWidth, y, bottom, page)
            y = vatSummary(document, snap, texts, fonts, y - 14f, bottom, page)
            totals(document, snap, texts, fonts, creditNote, y - 12f, bottom, page, contentWidth)

            drawFooters(document, footer, texts, fonts, snap, contentWidth)
            describe(document, snap)

            val out = ByteArrayOutputStream()

            document.save(out)

            return out.toByteArray()
        }
    }

    // ----- header, seller and buyer ------------------------------------------------------------------------------------------

    private fun header(
        cs: PDPageContentStream, document: PDDocument, snap: JsonObject, texts: InvoiceTexts, fonts: Fonts, logoBytes: ByteArray?,
        creditNote: Boolean, top: Float, pageWidth: Float
    ): Float {
        var logoHeight = 0f
        val image = decodeLogo(document, logoBytes)

        if (image != null) {
            val scale = minOf(LOGO_MAX_W / image.width, LOGO_MAX_H / image.height, 1f).let { if (it <= 0f) 1f else it }
            val w = image.width * scale
            val h = image.height * scale

            cs.drawImage(image, MARGIN, top - h, w, h)

            logoHeight = h
        }

        val order = snap.getJsonObject("order") ?: JsonObject()
        val right = pageWidth - MARGIN
        var y = top

        fun line(text: String, bold: Boolean, size: Int, gap: Float = 0f) {
            val font = fonts.of(bold)
            val s = PdfText.sanitize(font, text)
            val w = font.getStringWidth(s) / 1000f * size

            y -= size * LEADING + gap

            cs.beginText()
            cs.setFont(font, size.toFloat())
            cs.newLineAtOffset(right - w, y)
            cs.showText(s)
            cs.endText()
        }

        line(texts.label(if (creditNote) "invoice.credit-note-title" else "invoice.title"), true, TITLE)
        line(texts.label("invoice.number") + " " + snap.getString("number").orEmpty(), true, BODY, 4f)
        line(texts.label("invoice.date") + " " + texts.date(snap.getLong("issuedAt", 0L)), false, BODY)
        line(texts.label("invoice.order") + " #" + order.getLong("id", 0L), false, BODY)

        snap.getJsonObject("ref")?.getString("number")?.takeIf { it.isNotEmpty() }?.let {
            line(texts.label("invoice.for-invoice", mapOf("number" to it)), false, BODY)
        }

        if (snap.getBoolean("testMode", false)) line(texts.label("invoice.test-notice"), true, BODY, 4f)

        return minOf(top - logoHeight, y)
    }

    /** PNG and JPEG only (12 section 8.2); anything else, a decode error included, is no logo and never a failure. */
    private fun decodeLogo(document: PDDocument, bytes: ByteArray?): PDImageXObject? {
        if (bytes == null || bytes.size < 4) return null

        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

        if (!png && !jpeg) return null

        return try {
            PDImageXObject.createFromByteArray(document, bytes, "logo").takeIf { it.width > 0 && it.height > 0 }
        } catch (e: Exception) {
            null
        }
    }

    private fun parties(cs: PDPageContentStream, snap: JsonObject, texts: InvoiceTexts, fonts: Fonts, top: Float, contentWidth: Float): Float {
        val gap = 20f
        val colWidth = (contentWidth - gap) / 2
        val y0 = top - 22f
        val seller = snap.getJsonObject("seller") ?: JsonObject()
        val buyer = snap.getJsonObject("buyer") ?: JsonObject()
        val order = snap.getJsonObject("order") ?: JsonObject()

        class L(val text: String, val bold: Boolean = false, val muted: Boolean = false)

        fun labelled(key: String, value: String?) = value?.takeIf { it.isNotBlank() }?.let { L(texts.label(key) + ": " + it) }

        val sellerLines = buildList {
            add(L(seller.getString("name").orEmpty(), bold = true))
            seller.getString("address").orEmpty().split('\n').filter { it.isNotBlank() }.forEach { add(L(it.trim())) }
            labelled("invoice.tax-office", seller.getString("taxOffice"))?.let { add(it) }
            labelled("invoice.tax-number", seller.getString("taxNumber"))?.let { add(it) }
        }

        val company = buyer.getString("company").orEmpty()
        val name = buyer.getString("name").orEmpty()
        val buyerLines = buildList {
            if (company.isNotBlank()) {
                add(L(company, bold = true))
                if (name.isNotBlank()) add(L(name))
            } else if (name.isNotBlank()) {
                add(L(name, bold = true))
            }

            (buyer.getJsonArray("addressLines") ?: JsonArray()).forEach { if (it.toString().isNotBlank()) add(L(it.toString())) }
            buyer.getString("country")?.takeIf { it.isNotBlank() }?.let { add(L(it)) }
            labelled("invoice.tax-office", buyer.getString("taxOffice"))?.let { add(it) }
            labelled("invoice.tax-number", buyer.getString("taxNumber"))?.let { add(it) }
            labelled("invoice.identity-number", buyer.getString("identityNumber"))?.let { add(it) }
            labelled("invoice.email", buyer.getString("email"))?.let { add(it) }
            labelled("invoice.phone", buyer.getString("phone"))?.let { add(it) }
            add(L(texts.label("invoice.username") + ": " + buyer.getString("username").orEmpty()))

            if (order.getBoolean("isGift", false)) labelled("invoice.recipient", order.getString("recipientUsername"))?.let { add(it) }
        }

        fun column(x: Float, heading: String, lines: List<L>): Float {
            var y = y0

            textAt(cs, fonts.bold, BODY, x, top - 10f, PdfText.sanitize(fonts.bold, heading), MUTED)

            for (l in lines) {
                val font = fonts.of(l.bold)

                for (part in wrap(font, BODY, PdfText.sanitize(font, l.text), colWidth, Int.MAX_VALUE)) {
                    textAt(cs, font, BODY, x, y - BODY, part, Color.BLACK)

                    y -= BODY * LEADING
                }
            }

            return y
        }

        val sellerEnd = column(MARGIN, texts.label("invoice.seller"), sellerLines)
        val buyerEnd = column(MARGIN + colWidth + gap, texts.label("invoice.bill-to"), buyerLines)

        return minOf(sellerEnd, buyerEnd)
    }

    // ----- tables --------------------------------------------------------------------------------------------------------------

    private fun cell(fonts: Fonts, text: String, bold: Boolean = false, right: Boolean = false, background: Color? = null, size: Int = TABLE_FONT): TextCell {
        val font = fonts.of(bold)
        val builder = TextCell.builder()
            // line breaks are the cell's own (`\n` splits a cell into lines); everything else goes through the sanitizer
            .text(text.split('\n').joinToString("\n") { PdfText.sanitize(font, it) })
            .font(font)
            .fontSize(size)
            .horizontalAlignment(if (right) HorizontalAlignment.RIGHT else HorizontalAlignment.LEFT)
            .verticalAlignment(VerticalAlignment.TOP)
            .borderWidthBottom(0.5f)
            .borderWidthTop(0f)
            .borderWidthLeft(0f)
            .borderWidthRight(0f)
            .borderColor(RULE)

        if (background != null) builder.backgroundColor(background)

        return builder.build()
    }

    private fun lineTable(document: PDDocument, snap: JsonObject, texts: InvoiceTexts, fonts: Fonts, contentWidth: Float, startY: Float, endY: Float, page: PDRectangle): Float {
        val lines = (snap.getJsonArray("lines") ?: JsonArray()).map { it as JsonObject }
        val withDiscount = lines.any { it.getLong("discount", 0L) > 0 }
        val incl = snap.getBoolean("pricesIncludeVat", true)
        val qty = 34f
        val unit = 64f
        val disc = if (withDiscount) 56f else 0f
        val pct = 40f
        val vat = 56f
        val total = 68f
        val description = contentWidth - qty - unit - disc - pct - vat - total
        val widths = if (withDiscount) floatArrayOf(description, qty, unit, disc, pct, vat, total) else floatArrayOf(description, qty, unit, pct, vat, total)

        val table = Table.builder().addColumnsOfWidth(*widths).font(fonts.regular).fontSize(TABLE_FONT).padding(3f).wordBreak(true).borderColor(RULE)

        val header = Row.builder()
            .add(cell(fonts, texts.label("invoice.description"), bold = true, background = GRAY))
            .add(cell(fonts, texts.label("invoice.quantity"), bold = true, right = true, background = GRAY))
            .add(
                cell(
                    fonts, texts.label("invoice.unit-price") + "\n(" + texts.label(if (incl) "invoice.incl-vat" else "invoice.excl-vat") + ")", bold = true,
                    right = true, background = GRAY
                )
            )

        if (withDiscount) header.add(cell(fonts, texts.label("invoice.discount"), bold = true, right = true, background = GRAY))

        header
            .add(cell(fonts, texts.label("invoice.vat-percent"), bold = true, right = true, background = GRAY))
            .add(cell(fonts, texts.label("invoice.vat"), bold = true, right = true, background = GRAY))
            .add(cell(fonts, texts.label("invoice.line-total"), bold = true, right = true, background = GRAY))

        table.addRow(header.build())

        for (line in lines) {
            val kind = line.getString("kind").orEmpty()
            val child = kind == "BUNDLE_CHILD"
            val name = line.getString("name").orEmpty().ifBlank { kindLabel(kind, texts) }
            val second = listOfNotNull(
                line.getString("variantName")?.takeIf { it.isNotBlank() },
                line.getString("sku")?.takeIf { it.isNotBlank() }?.let { texts.label("invoice.sku") + " " + it }
            ).joinToString(" · ")
            val text = (if (child) "    " else "") + name + if (second.isNotEmpty()) "\n" + (if (child) "    " else "") + second else ""
            val row = Row.builder().add(cell(fonts, text))

            row.add(cell(fonts, line.getLong("quantity", 0L).toString(), right = true))

            if (child) {
                repeat(if (withDiscount) 5 else 4) { row.add(cell(fonts, "", right = true)) }
            } else {
                row.add(cell(fonts, texts.money(line.getLong("unitPrice", 0L)), right = true))

                if (withDiscount) row.add(cell(fonts, line.getLong("discount", 0L).let { if (it > 0) texts.money(it) else "" }, right = true))

                row.add(cell(fonts, texts.percent(line.getLong("vatPercent", 0L)), right = true))
                row.add(cell(fonts, texts.money(line.getLong("vat", 0L)), right = true))
                row.add(cell(fonts, texts.money(line.getLong("gross", 0L)), right = true))
            }

            table.addRow(row.build())
        }

        val built = table.build()
        val drawer = RepeatedHeaderTableDrawer.builder()
            .table(built).startX(MARGIN).startY(startY).endY(endY).build()

        drawer.draw({ document }, { PDPage(page) }, MARGIN)

        return drawer.finalY
    }

    private fun kindLabel(kind: String, texts: InvoiceTexts): String = when (kind) {
        "SHIPPING" -> texts.label("invoice.line-shipping")
        "PAYMENT_FEE" -> texts.label("invoice.line-payment-fee")
        "REFUND" -> texts.label("invoice.line-refund")
        "CREDIT_TOPUP" -> texts.label("invoice.line-credit-topup")
        else -> ""
    }

    /** One row per VAT rate; omitted when the only rate is 0 (12 section 8.2 item 4). */
    private fun vatSummary(document: PDDocument, snap: JsonObject, texts: InvoiceTexts, fonts: Fonts, startY: Float, endY: Float, page: PDRectangle): Float {
        val rows = (snap.getJsonArray("vatRows") ?: JsonArray()).map { it as JsonObject }

        if (rows.isEmpty() || (rows.size == 1 && rows[0].getLong("vatPercent", 0L) == 0L)) return startY + 14f

        val table = Table.builder().addColumnsOfWidth(70f, 80f, 80f, 80f).font(fonts.regular).fontSize(TABLE_FONT).padding(3f).borderColor(RULE)

        table.addRow(
            Row.builder()
                .add(cell(fonts, texts.label("invoice.vat-rate"), bold = true, background = GRAY))
                .add(cell(fonts, texts.label("invoice.net"), bold = true, right = true, background = GRAY))
                .add(cell(fonts, texts.label("invoice.vat"), bold = true, right = true, background = GRAY))
                .add(cell(fonts, texts.label("invoice.gross"), bold = true, right = true, background = GRAY))
                .build()
        )

        for (r in rows) {
            table.addRow(
                Row.builder()
                    .add(cell(fonts, texts.percent(r.getLong("vatPercent", 0L))))
                    .add(cell(fonts, texts.money(r.getLong("net", 0L)), right = true))
                    .add(cell(fonts, texts.money(r.getLong("vat", 0L)), right = true))
                    .add(cell(fonts, texts.money(r.getLong("gross", 0L)), right = true))
                    .build()
            )
        }

        return drawWhole(document, table.build(), MARGIN, startY, endY, page)
    }

    /** The totals box, right-aligned (12 section 8.2 item 5), then the payment lines. */
    private fun totals(document: PDDocument, snap: JsonObject, texts: InvoiceTexts, fonts: Fonts, creditNote: Boolean, startY: Float, endY: Float, page: PDRectangle, contentWidth: Float) {
        val totals = snap.getJsonObject("totals") ?: JsonObject()
        val order = snap.getJsonObject("order") ?: JsonObject()
        val boxWidth = 250f
        val table = Table.builder().addColumnsOfWidth(boxWidth - 96f, 96f).font(fonts.regular).fontSize(TABLE_FONT + 1).padding(3f).borderColor(RULE)

        fun row(label: String, value: String, bold: Boolean = false, shaded: Boolean = false) {
            table.addRow(
                Row.builder()
                    .add(cell(fonts, label, bold = bold, background = if (shaded) GRAY else null, size = TABLE_FONT + 1))
                    .add(cell(fonts, value, bold = bold, right = true, background = if (shaded) GRAY else null, size = TABLE_FONT + 1))
                    .build()
            )
        }

        val discount = totals.getLong("discount", 0L)
        val shipping = totals.getLong("shipping", 0L)
        val fee = totals.getLong("paymentFee", 0L)

        if (!creditNote && (discount > 0 || shipping > 0 || fee > 0)) {
            row(texts.label("invoice.subtotal"), texts.money(totals.getLong("subtotal", 0L)))

            if (discount > 0) row(texts.label("invoice.discount"), texts.money(-discount))
            if (shipping > 0) row(texts.label("invoice.shipping"), texts.money(shipping))
            if (fee > 0) row(texts.label("invoice.payment-fee"), texts.money(fee))
        }

        row(texts.label("invoice.net-total"), texts.money(totals.getLong("net", 0L)))
        row(texts.label("invoice.vat-total"), texts.money(totals.getLong("vat", 0L)))
        row(texts.label(if (creditNote) "invoice.refund-total" else "invoice.total"), texts.money(totals.getLong("total", 0L)), bold = true, shaded = true)

        val gateway = totals.getLong("gatewayAmount", 0L)
        val creditValue = totals.getLong("creditValue", 0L)
        val credits = texts.creditName.ifBlank { "Credits" }

        if (creditNote) {
            if (gateway > 0) row(texts.label("invoice.refunded-to-method"), texts.money(gateway))
            if (creditValue > 0) row(texts.label("invoice.refunded-as-credits", mapOf("name" to credits)), texts.money(creditValue))
        } else {
            if (gateway > 0) row(texts.label("invoice.paid-with", mapOf("method" to order.getString("paymentLabel").orEmpty())), texts.money(gateway))
            if (creditValue > 0) row(texts.label("invoice.paid-with-credits", mapOf("name" to credits)), texts.money(creditValue))
        }

        drawWhole(document, table.build(), MARGIN + contentWidth - boxWidth, startY, endY, page)
    }

    /**
     * A table that is kept together: when it does not fit under [startY] it starts on a new page (easytable only tests the first row).
     * Returns the y under the table.
     */
    private fun drawWhole(document: PDDocument, table: Table, x: Float, startY: Float, endY: Float, page: PDRectangle): Float {
        val fits = startY - table.height >= endY
        val drawer = TableDrawer.builder().table(table).startX(x).startY(if (fits) startY else endY).endY(endY).build()

        drawer.draw({ document }, { PDPage(page) }, MARGIN)

        return drawer.finalY
    }

    // ----- footer, document info ---------------------------------------------------------------------------------------------

    private fun footerLines(snap: JsonObject, fonts: Fonts, contentWidth: Float): List<String> {
        val text = snap.getString("footer").orEmpty()
            .split('\n').map { PdfText.sanitize(fonts.regular, it.trim()) }.filter { it.isNotEmpty() }.joinToString("\n")

        if (text.isEmpty()) return emptyList()

        val wrapped = text.split('\n').flatMap { wrap(fonts.regular, FOOTER_FONT, it, contentWidth, Int.MAX_VALUE) }

        if (wrapped.size <= FOOTER_LINES) return wrapped

        val kept = wrapped.take(FOOTER_LINES).toMutableList()
        var last = kept.last()

        while (last.isNotEmpty() && fonts.regular.getStringWidth("$last…") / 1000f * FOOTER_FONT > contentWidth) last = last.dropLast(1)

        kept[kept.lastIndex] = "$last…"

        return kept
    }

    private fun drawFooters(document: PDDocument, footer: List<String>, texts: InvoiceTexts, fonts: Fonts, snap: JsonObject, contentWidth: Float) {
        val seller = snap.getJsonObject("seller") ?: JsonObject()
        val site = listOf(seller.getString("websiteName").orEmpty(), seller.getString("websiteUrl").orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        val pages = document.numberOfPages

        for (index in 0 until pages) {
            PDPageContentStream(document, document.getPage(index), PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                val lineHeight = FOOTER_FONT * LEADING
                var y = MARGIN + (footer.size + 1) * lineHeight

                cs.setStrokingColor(RULE)
                cs.setLineWidth(0.5f)
                cs.moveTo(MARGIN, y + 4f)
                cs.lineTo(MARGIN + contentWidth, y + 4f)
                cs.stroke()

                for (line in footer) {
                    textAt(cs, fonts.regular, FOOTER_FONT, MARGIN, y - FOOTER_FONT, line, MUTED)

                    y -= lineHeight
                }

                textAt(cs, fonts.regular, FOOTER_FONT, MARGIN, y - FOOTER_FONT, PdfText.sanitize(fonts.regular, site), MUTED)

                val label = PdfText.sanitize(fonts.regular, texts.label("invoice.page", mapOf("page" to index + 1, "pages" to pages)))
                val w = fonts.regular.getStringWidth(label) / 1000f * FOOTER_FONT

                textAt(cs, fonts.regular, FOOTER_FONT, MARGIN + contentWidth - w, y - FOOTER_FONT, label, MUTED)
            }
        }
    }

    /** Title = number, producer `Pano Market`, creation date = `issuedAt`, and a trailer id derived from the snapshot: no render-time data. */
    private fun describe(document: PDDocument, snap: JsonObject) {
        val info = document.documentInformation
        val issued = GregorianCalendar(TimeZone.getTimeZone("UTC")).also { it.timeInMillis = snap.getLong("issuedAt", 0L) } as Calendar

        info.title = snap.getString("number").orEmpty()
        info.producer = "Pano Market"
        info.creationDate = issued

        val digest = MessageDigest.getInstance("SHA-256").digest(snap.encode().toByteArray(Charsets.UTF_8)).copyOf(16)
        val id = COSString(digest)
        val ids = COSArray()

        ids.add(id)
        ids.add(COSString(digest))
        document.document.trailer.setItem(COSName.ID, ids)
    }

    // ----- text helpers ------------------------------------------------------------------------------------------------------

    private fun textAt(cs: PDPageContentStream, font: PDFont, size: Int, x: Float, y: Float, text: String, color: Color) {
        if (text.isEmpty()) return

        cs.beginText()
        cs.setNonStrokingColor(color)
        cs.setFont(font, size.toFloat())
        cs.newLineAtOffset(x, y)
        cs.showText(text)
        cs.endText()
    }

    /** Greedy word wrap at [maxWidth] points; a word wider than the line is cut. At most [maxLines] lines. */
    private fun wrap(font: PDFont, size: Int, text: String, maxWidth: Float, maxLines: Int): List<String> {
        fun width(s: String) = font.getStringWidth(s) / 1000f * size

        val lines = ArrayList<String>()
        var current = StringBuilder()

        fun flush() {
            lines.add(current.toString())
            current = StringBuilder()
        }

        for (word in text.split(' ')) {
            var w = word

            while (width(w) > maxWidth && w.length > 1) {
                var cut = w.length - 1

                while (cut > 1 && width(w.substring(0, cut)) > maxWidth) cut--

                if (current.isNotEmpty()) flush()

                lines.add(w.substring(0, cut))
                w = w.substring(cut)
            }

            val candidate = if (current.isEmpty()) w else "$current $w"

            if (current.isNotEmpty() && width(candidate) > maxWidth) {
                flush()
                current.append(w)
            } else {
                current = StringBuilder(candidate)
            }
        }

        if (current.isNotEmpty() || lines.isEmpty()) flush()

        return lines.take(maxLines)
    }
}
