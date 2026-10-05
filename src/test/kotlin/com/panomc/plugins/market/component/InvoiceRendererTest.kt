package com.panomc.plugins.market.component

import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.pdf.InvoiceLabelKeys
import com.panomc.plugins.market.pdf.InvoicePdfRenderer
import com.panomc.plugins.market.pdf.InvoiceRenderException
import com.panomc.plugins.market.pdf.InvoiceSample
import com.panomc.plugins.market.pdf.InvoiceTexts
import com.panomc.plugins.market.pdf.InvoiceTextsFactory
import com.panomc.plugins.market.pdf.PdfText
import com.panomc.plugins.market.support.FakeClock
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The invoice PDF (MK-144; T-PDF-1 to T-PDF-4, T-PDF-6 and T-PDF-7 of 12 section 12): the renderer's output is opened with PDFBox and its
 * text checked, in the three languages, with Turkish and Cyrillic glyphs and the currency signs, over several pages, with hostile text and
 * logos, and twice for the same bytes. T-PDF-5 (the same from the shaded jar) is `ShadedInvoiceRendererTest`.
 */
class InvoiceRendererTest {
    private val bundles = listOf("tr", "en-US", "ru").associateWith { MarketI18n.flatten(File("src/locales/core/$it.json").readText()) }
    private val i18n = MarketI18n(bundles, { emptyMap() }, FakeClock()) { }
    private val format = MarketFormat(i18n, { "Europe/Istanbul" }, { "Credits" })

    private fun texts(snapshot: JsonObject, locale: String = snapshot.getString("locale")): InvoiceTexts =
        runBlocking { InvoiceTextsFactory.build(snapshot, locale, i18n, format, "Credits") }

    private fun render(snapshot: JsonObject, logo: ByteArray? = null, locale: String = snapshot.getString("locale")): ByteArray =
        InvoicePdfRenderer.render(snapshot, texts(snapshot, locale), logo)

    private fun open(bytes: ByteArray): PDDocument = Loader.loadPDF(bytes)

    private fun pageTexts(bytes: ByteArray): List<String> = open(bytes).use { doc ->
        (1..doc.numberOfPages).map { p -> PDFTextStripper().also { it.startPage = p; it.endPage = p }.getText(doc) }
    }

    private fun text(bytes: ByteArray): String = pageTexts(bytes).joinToString("\n")

    private val seller = JsonObject().put("name", "Acme Craft Ltd").put("address", "1 Main Street\n34000 Kadıköy").put("taxOffice", "Kadıköy")
        .put("taxNumber", "1234567890").put("websiteName", "Acme Craft").put("websiteUrl", "https://acme.example")

    private fun sample(creditNote: Boolean = false, locale: String = "en-US", currency: String = "EUR", footer: String = "Thank you for your order") =
        InvoiceSample.snapshot(creditNote, locale, currency, "Europe/Istanbul", seller.copy(), footer, 1_760_000_000_000L)

    private fun line(name: String, gross: Long = 1200, vat: Long = 200, percent: Long = 2000, qty: Int = 1, kind: String = "PRODUCT", variant: String? = null, sku: String? = null) =
        JsonObject().put("kind", kind).put("name", name).put("variantName", variant).put("sku", sku).put("quantity", qty).put("unitPrice", gross / qty)
            .put("discount", 0).put("vatPercent", percent).put("net", gross - vat).put("vat", vat).put("gross", gross)

    // ----- T-PDF-1 -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the sample renders in each language with number, seller, buyer, lines, VAT rows and totals`() {
        for (locale in listOf("tr", "en-US", "ru")) sampleRenders(locale)
    }

    private fun sampleRenders(locale: String) {
        val snap = sample(locale = locale)
        val body = text(render(snap))

        assertTrue(body.contains("INV-0000-000000"), body)
        assertTrue(body.contains("Acme Craft Ltd"), body)
        assertTrue(body.contains("1 Main Street"), body)
        assertTrue(body.contains("Alex Example"), body)
        assertTrue(body.contains("Steve"), body)
        assertTrue(body.contains("VIP Rank") && body.contains("Crate keys"), body)
        assertTrue(body.contains("VIP-30") && body.contains("30 days"), "variant and SKU, second line")
        assertTrue(body.contains("Acme Craft") && body.contains("https://acme.example"), "footer site line")

        val t = texts(snap)

        for (key in listOf("invoice.title", "invoice.seller", "invoice.bill-to", "invoice.description", "invoice.vat-total", "invoice.total")) {
            assertTrue(body.contains(t.label(key)), "$locale: label '${t.label(key)}' ($key) in the document")
        }

        // the VAT summary: one row for the only rate (20%), net / VAT / gross
        val totals = snap.getJsonObject("totals")

        for (amount in listOf(totals.getLong("total"), totals.getLong("vat"), totals.getLong("net"))) assertTrue(body.contains(t.money(amount)), "$locale: ${t.money(amount)}")

        assertTrue(body.contains(t.percent(2000)), "the VAT rate")
        assertTrue(body.contains(t.label("invoice.vat-rate")), "VAT summary header")
        assertTrue(body.contains(t.label("invoice.paid-with", mapOf("method" to "Card"))), "payment line")
    }

    @Test
    fun `Turkish and Cyrillic glyphs and the currency signs survive in the text`() {
        val turkish = sample(locale = "tr", currency = "TRY")

        turkish.getJsonObject("seller").put("name", "Çağrı Şirketi İstanbul ığş")
        turkish.getJsonObject("buyer").put("name", "Şükrü Öğüt İnci")
        turkish.getJsonArray("lines").add(line("Ğ İ ı ş Ç ç Ö ö Ü ü"))

        val trBody = text(render(turkish))

        assertTrue(trBody.contains("Çağrı Şirketi İstanbul ığş"), trBody)
        assertTrue(trBody.contains("Şükrü Öğüt İnci"), trBody)
        assertTrue(trBody.contains("Ğ İ ı ş Ç ç Ö ö Ü ü"), trBody)
        assertTrue(trBody.contains("₺"), "lira sign")

        val russian = sample(locale = "ru", currency = "RUB")

        russian.getJsonObject("seller").put("name", "ООО Пример Ёлка")
        russian.getJsonObject("buyer").put("name", "Иван Петров")
        russian.getJsonArray("lines").add(line("Ранг «Привилегия» ЖжЩщЪъЫы"))

        val ruBody = text(render(russian))

        assertTrue(ruBody.contains("ООО Пример Ёлка") && ruBody.contains("Иван Петров"), ruBody)
        assertTrue(ruBody.contains("Ранг «Привилегия» ЖжЩщЪъЫы"), ruBody)
        assertTrue(ruBody.contains("₽"), "ruble sign")
        assertTrue(ruBody.contains(texts(russian).label("invoice.title")), "translated title")

        assertTrue(text(render(sample(currency = "EUR"))).contains("€"), "euro sign")
        assertTrue(text(render(sample(currency = "GBP"))).contains("£"), "pound sign")
    }

    @Test
    fun `the PDF library never scans the system fonts and variant and SKU sit on a second line`() {
        val body = text(render(sample()))

        // easytable's static default font would make PDFBox's own mapper read every installed font and write ~/.pdfbox.cache
        assertTrue(org.apache.pdfbox.pdmodel.font.FontMappers.instance().javaClass.name.contains("EmbeddedFontsOnly"), "the embedded-fonts-only mapper is installed, not PDFBox's own")
        assertTrue(body.lines().any { it.contains("30 days") && it.contains("VIP-30") && !it.contains("VIP Rank") }, "variant and SKU on their own line: $body")
    }

    @Test
    fun `discount column only when a line has a discount, VAT summary omitted when the only rate is zero`() {
        val withDiscount = sample()
        val without = sample()

        without.getJsonArray("lines").forEach { (it as JsonObject).put("discount", 0) }

        val d = texts(withDiscount).label("invoice.discount")

        assertTrue(text(render(withDiscount)).contains(d))

        // the totals box still has a discount row when the totals say so, so remove it there too
        without.getJsonObject("totals").put("discount", 0)
        assertFalse(text(render(without)).contains(d), "no discount column and no discount row")

        val zero = sample()

        zero.put("lines", JsonArray().add(line("Free of VAT", gross = 1000, vat = 0, percent = 0)))
        zero.put("vatRows", JsonArray().add(JsonObject().put("vatPercent", 0).put("net", 1000).put("vat", 0).put("gross", 1000)))
        zero.put("totals", JsonObject().put("subtotal", 1000).put("discount", 0).put("shipping", 0).put("paymentFee", 0).put("net", 1000).put("vat", 0).put("total", 1000).put("creditValue", 0).put("creditAmount", 0).put("gatewayAmount", 1000))

        val t = texts(zero)

        assertFalse(text(render(zero)).contains(t.label("invoice.vat-rate")), "no VAT summary for a single zero rate")
        assertTrue(text(render(sample())).contains(t.label("invoice.vat-rate")))
    }

    @Test
    fun `labels fall back by kind, bundle children are indented, shipping and fee lines show, test mode and gift are marked`() {
        val snap = sample()
        val lines = snap.getJsonArray("lines")

        lines.add(line("", kind = "PAYMENT_FEE", gross = 100, vat = 17))
        lines.add(line("Bundle part", kind = "BUNDLE_CHILD", gross = 0, vat = 0, qty = 1))
        snap.put("testMode", true)
        snap.getJsonObject("order").put("isGift", true).put("recipientUsername", "Alex")

        val body = text(render(snap))
        val t = texts(snap)

        assertTrue(body.contains(t.label("invoice.line-shipping")), "the empty SHIPPING name is labelled by kind")
        assertTrue(body.contains(t.label("invoice.line-payment-fee")), "the empty PAYMENT_FEE name is labelled by kind")
        assertTrue(body.contains("Bundle part"))
        assertTrue(body.contains(t.label("invoice.test-notice")), "test notice")
        assertTrue(body.contains(t.label("invoice.recipient") + ": Alex"), "gift recipient")
    }

    @Test
    fun `the renderer asks only for listed keys and every key exists in all three languages`() {
        val asked = linkedSetOf<String>()
        val snap = sample().also { it.put("testMode", true); it.getJsonObject("order").put("isGift", true) }
        snap.getJsonArray("lines").add(line("", kind = "PAYMENT_FEE")).add(line("", kind = "CREDIT_TOPUP")).add(line("", kind = "REFUND"))
        snap.getJsonObject("totals").put("creditValue", 500).put("paymentFee", 100)
        snap.getJsonObject("buyer").put("company", "Acme").put("taxOffice", "T").put("taxNumber", "9").put("phone", "1")

        val real = texts(snap)
        val recording = object : InvoiceTexts by real {
            override fun label(key: String, vars: Map<String, Any?>): String {
                asked.add(key)

                return real.label(key, vars)
            }
        }

        InvoicePdfRenderer.render(snap, recording, null)

        val creditNote = sample(creditNote = true)
        val realCn = texts(creditNote)

        InvoicePdfRenderer.render(creditNote, object : InvoiceTexts by realCn {
            override fun label(key: String, vars: Map<String, Any?>): String {
                asked.add(key)

                return realCn.label(key, vars)
            }
        }, null)

        assertTrue(InvoiceLabelKeys.ALL.containsAll(asked), "asked but not listed: ${asked - InvoiceLabelKeys.ALL.toSet()}")

        for ((locale, map) in bundles) {
            for (key in InvoiceLabelKeys.ALL) {
                val value = map[key]

                assertTrue(!value.isNullOrBlank(), "$locale is missing $key")
                assertFalse(value!!.contains("{{") || value.contains(", plural,") || value.contains(", select,"), "$locale $key has template syntax")
            }
        }

        // the three languages use the same placeholders
        for (key in InvoiceLabelKeys.ALL) {
            val sets = bundles.values.map { Regex("\\{([a-z]+)}").findAll(it.getValue(key)).map { m -> m.groupValues[1] }.toSet() }

            assertEquals(1, sets.toSet().size, "placeholders of $key differ between languages")
        }
    }

    // ----- T-PDF-2 -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `120 lines run over several pages with the header row and page x of y on each`() {
        val snap = sample()
        val lines = JsonArray()

        for (i in 1..120) lines.add(line("Line number $i", variant = "Variant $i", sku = "SKU-$i"))

        snap.put("lines", lines)

        val pages = pageTexts(render(snap))
        val t = texts(snap)

        assertTrue(pages.size >= 3, "pages: ${pages.size}")

        pages.forEachIndexed { index, page ->
            assertTrue(page.contains(t.label("invoice.description")), "the header row on page ${index + 1}")
            assertTrue(page.contains(t.label("invoice.page", mapOf("page" to index + 1, "pages" to pages.size))), "page ${index + 1} of ${pages.size}: $page")
        }

        val all = pages.joinToString("\n")

        for (i in listOf(1, 40, 41, 80, 120)) assertTrue(all.contains("Line number $i"), "line $i present")

        assertTrue(pages.last().contains(t.label("invoice.total")), "the totals follow the last line")
    }

    @Test
    fun `the totals box is not split across pages`() {
        // enough lines that the totals box only just does not fit under the table: it moves whole to the next page
        for (n in 20..40) {
            val snap = sample()
            val lines = JsonArray()

            for (i in 1..n) lines.add(line("Row $i"))

            snap.put("lines", lines)

            val pages = pageTexts(render(snap))
            val t = texts(snap)
            val holders = pages.withIndex().filter { it.value.contains(t.label("invoice.net-total")) }.map { it.index }

            assertEquals(1, holders.size, "n=$n: the totals box on one page only")

            val box = pages[holders.single()]

            assertTrue(box.contains(t.label("invoice.vat-total")) && box.contains(t.label("invoice.total")), "n=$n: the box is whole")
        }
    }

    // ----- T-PDF-3 -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `unsupported glyphs and control characters never throw and become question marks`() {
        val snap = sample()

        snap.getJsonObject("buyer").put("name", "日本語 العربية 😀 Zed")
        snap.getJsonArray("lines").add(line("Tab\there\u0000nul\u0007bell 🎮 end"))
        snap.getJsonObject("seller").put("address", "Line one\r\nLine\ttwo​")

        val body = text(render(snap))

        assertTrue(body.contains("Zed"), body)
        assertTrue(body.contains("?"), "unsupported code points are replaced")
        assertFalse(body.contains("日") || body.contains("😀"), body)
        assertTrue(body.contains("Tab here") && body.contains("nulbell") && body.contains("end"), body)
        assertTrue(body.contains("Line one") && body.contains("Line two"), body)
    }

    @Test
    fun `sanitize replaces per code point, keeps what the font has and drops control characters`() {
        PDDocument().use { doc ->
            val font = PDType0Font.load(doc, ByteArrayInputStream(File("src/main/resources/fonts/NotoSans-Regular.ttf").readBytes()), true)

            assertEquals("abc ğşİı ЖЯ ₺₽€£", PdfText.sanitize(font, "abc ğşİı ЖЯ ₺₽€£"))
            assertEquals("a b", PdfText.sanitize(font, "a\tb"))
            assertEquals("ab", PdfText.sanitize(font, "a\u0000\u0001\u007Fb"))
            assertEquals("a??b", PdfText.sanitize(font, "a日語b"))
            assertEquals("a?b", PdfText.sanitize(font, "a😀b"), "a surrogate pair is one code point, one question mark")
            assertEquals("", PdfText.sanitize(font, ""))
        }
    }

    @Test
    fun `an overlong word and a long footer wrap or truncate instead of failing`() {
        val snap = sample(footer = (1..40).joinToString("\n") { "Footer line $it with some more words to fill the width of the page and wrap around" })

        snap.getJsonArray("lines").add(line("W".repeat(300)))

        val pages = pageTexts(render(snap))
        val all = pages.joinToString("\n")

        assertTrue(all.contains("Footer line 1 "), "the footer starts")
        assertFalse(all.contains("Footer line 5 "), "the footer is cut to four lines")
        assertTrue(all.contains("…"), "and says so")
        assertEquals(1, pages.size, "a footer of any length does not add pages")
    }

    // ----- T-PDF-4 -----------------------------------------------------------------------------------------------------------------

    private fun image(format: String): ByteArray {
        val img = BufferedImage(200, 80, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()

        g.fillRect(0, 0, 200, 80)
        g.dispose()

        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    private fun imageCount(bytes: ByteArray): Int = open(bytes).use { doc ->
        doc.getPage(0).resources.xObjectNames.count { doc.getPage(0).resources.isImageXObject(it) }
    }

    @Test
    fun `PNG and JPEG logos are drawn, other bytes and corrupt images are no logo and no failure`() {
        val snap = sample()

        assertEquals(1, imageCount(render(snap, image("png"))), "PNG")
        assertEquals(1, imageCount(render(snap, image("jpg"))), "JPEG")
        assertEquals(0, imageCount(render(snap, null)), "no logo")
        assertEquals(0, imageCount(render(snap, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())), "SVG")
        assertEquals(0, imageCount(render(snap, byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3))), "corrupt PNG")
        assertEquals(0, imageCount(render(snap, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 1))), "corrupt JPEG")
        assertEquals(0, imageCount(render(snap, ByteArray(0))), "empty")
        assertEquals(0, imageCount(render(snap, ByteArray(2))), "two bytes")
    }

    @Test
    fun `an invalid snapshot is an InvoiceRenderException, not a raw exception`() {
        val broken = sample()

        broken.put("lines", "not an array")

        val failure = runCatching { InvoicePdfRenderer.render(broken, texts(sample()), null) }.exceptionOrNull()

        assertTrue(failure is InvoiceRenderException, "got $failure")
    }

    // ----- T-PDF-6 -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the same snapshot gives the same bytes, another snapshot another file`() {
        val snap = sample(locale = "tr", currency = "TRY")
        val first = render(snap, image("png"))
        val second = render(snap.copy(), image("png"))

        assertArrayEquals(first, second, "byte-identical")
        assertEquals(text(first), text(second))
        assertEquals(open(first).use { it.numberOfPages }, open(second).use { it.numberOfPages })

        val other = sample(locale = "tr", currency = "TRY").also { it.put("number", "INV-0000-000001") }

        assertNotEquals(first.toList(), render(other, image("png")).toList())
    }

    @Test
    fun `the document info carries the number, the producer and the issue time, not the render time`() {
        val snap = sample()

        open(render(snap)).use { doc ->
            assertEquals("INV-0000-000000", doc.documentInformation.title)
            assertEquals("Pano Market", doc.documentInformation.producer)
            assertEquals(1_760_000_000_000L, doc.documentInformation.creationDate.timeInMillis)
            assertEquals(595.28f, doc.getPage(0).mediaBox.width, 0.01f, "A4 portrait")
        }
    }

    // ----- T-PDF-7 -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a credit note has its own title, the invoice reference, the refund lines and the refund split`() {
        val snap = sample(creditNote = true)

        snap.put(
            "lines",
            JsonArray().add(line("Rank VIP", gross = 1200, vat = 200, qty = 1)).add(line("", kind = "REFUND", gross = 600, vat = 55, percent = 1000))
        )
        snap.put("vatRows", JsonArray().add(JsonObject().put("vatPercent", 1000).put("net", 545).put("vat", 55).put("gross", 600)).add(JsonObject().put("vatPercent", 2000).put("net", 1000).put("vat", 200).put("gross", 1200)))
        snap.getJsonObject("totals").put("total", 1800).put("net", 1545).put("vat", 255).put("gatewayAmount", 1000).put("creditValue", 800)

        val body = text(render(snap))
        val t = texts(snap)

        assertTrue(body.contains(t.label("invoice.credit-note-title")), body)
        assertFalse(body.contains(t.label("invoice.title") + "\n"), "not titled as an invoice")
        assertTrue(body.contains(t.label("invoice.for-invoice", mapOf("number" to "INV-0000-000000"))), "reference to the invoice")
        assertTrue(body.contains("CN-0000-000000"))
        assertTrue(body.contains("Rank VIP") && body.contains(t.label("invoice.line-refund")), "itemised row and the REFUND line")
        assertTrue(body.contains(t.percent(1000)) && body.contains(t.percent(2000)), "one row per rate")
        assertTrue(body.contains(t.label("invoice.refund-total")))
        assertTrue(body.contains(t.label("invoice.refunded-to-method")) && body.contains(t.money(1000)), "refunded to the method")
        assertTrue(body.contains(t.label("invoice.refunded-as-credits", mapOf("name" to "Credits"))) && body.contains(t.money(800)), "refunded as credits")
        assertFalse(body.contains(t.label("invoice.paid-with", mapOf("method" to "Card"))), "no 'paid with' on a credit note")
    }

    @Test
    fun `a mixed payment shows both payment lines`() {
        val snap = sample()

        snap.getJsonObject("totals").put("creditValue", 400).put("gatewayAmount", 600)

        val body = text(render(snap))
        val t = texts(snap)

        assertTrue(body.contains(t.label("invoice.paid-with", mapOf("method" to "Card"))))
        assertTrue(body.contains(t.label("invoice.paid-with-credits", mapOf("name" to "Credits"))))
    }

    @Test
    fun `dates are formatted in the store time zone`() {
        // 2025-12-31T22:30:00Z is already 2026-01-01 in Istanbul (UTC+3)
        val snap = sample(locale = "tr").also { it.put("issuedAt", 1_767_220_200_000L) }
        val t = texts(snap)

        assertEquals("01.01.2026", t.date(1_767_220_200_000L))
        assertTrue(text(render(snap)).contains("01.01.2026"))
    }
}
