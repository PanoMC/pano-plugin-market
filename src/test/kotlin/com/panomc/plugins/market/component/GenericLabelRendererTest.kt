package com.panomc.plugins.market.component

import com.panomc.plugins.market.pdf.GenericLabel
import com.panomc.plugins.market.pdf.GenericLabelRenderer
import com.panomc.plugins.market.pdf.LabelAddress
import com.panomc.plugins.market.pdf.LabelCaptions
import com.panomc.plugins.market.routes.panel.shipment.labelContentType
import com.panomc.plugins.market.routes.panel.shipment.labelFileName
import com.panomc.plugins.market.routes.panel.shipment.parseLabelGeneric
import com.panomc.plugins.market.routes.panel.shipment.parseLabelIndex
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.shipping.LabelFormat
import io.vertx.core.json.JsonObject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The generic shipping label (MK-145): the PDF is opened with PDFBox and its text, page size and barcode checked. */
class GenericLabelRendererTest {
    private val recipient = LabelAddress.lines(
        JsonObject().put("firstName", "Şükrü").put("lastName", "Öztürk").put("line1", "Atatürk Cad. 12/4").put("district", "Kadıköy").put("postalCode", "34710")
            .put("city", "İstanbul").put("country", "TR").put("phone", "+905551112233")
    )
    private val sender = LabelAddress.lines(Address("Acme", "Craft", "Acme Craft Ltd", null, null, "TR", null, "Ankara", null, null, "Depo 1", null, "06000", null, null, null))

    private fun label(
        barcode: String = "TRK123456", paper: String = "A6", weight: Int? = 1250, locale: String? = "en-US", to: List<String> = recipient, orderId: Long = 4711, publicId: String? = "ABCD-1234"
    ) = GenericLabel(sender, to, orderId, publicId, weight, barcode, paper, LabelCaptions.of(locale))

    private fun text(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    @Test
    fun `the label text has recipient, tracking number, order number, publicId, weight and sender`() {
        val bytes = GenericLabelRenderer.render(label())
        val t = text(bytes)

        assertTrue(String(bytes, 0, 5) == "%PDF-", "a PDF")
        assertTrue(t.contains("Şükrü Öztürk"), t)
        assertTrue(t.contains("Atatürk Cad. 12/4"), t)
        assertTrue(t.contains("34710 İstanbul"), t)
        assertTrue(t.contains("TRK123456"), "the tracking number under the barcode: $t")
        assertTrue(t.contains("Order #4711"), t)
        assertTrue(t.contains("ABCD-1234"), t)
        assertTrue(t.contains("1.25 kg"), t)
        assertTrue(t.contains("Acme Craft Ltd"), t)
        assertTrue(t.contains("Ankara"), t)
    }

    @Test
    fun `A6 is the default page, A4 and unknown values are honoured or fall back`() {
        fun size(paper: String) = Loader.loadPDF(GenericLabelRenderer.render(label(paper = paper))).use { it.getPage(0).mediaBox.let { b -> b.width to b.height } }

        val a6 = size("A6")
        val a4 = size("A4")

        assertEquals(1, Loader.loadPDF(GenericLabelRenderer.render(label())).use { it.numberOfPages })
        assertTrue(a6.first < 300f && a6.second < 430f, "A6 is 105 x 148 mm: $a6")
        assertTrue(a4.first > 590f && a4.second > 835f, "A4 is 210 x 297 mm: $a4")
        assertEquals(a6, size("Letter"))
        assertEquals(a4, size("a4"))
    }

    @Test
    fun `captions follow the locale`() {
        assertTrue(text(GenericLabelRenderer.render(label(locale = "tr"))).contains("Sipariş #4711"))
        assertTrue(text(GenericLabelRenderer.render(label(locale = "ru"))).contains("Заказ #4711"))
        assertTrue(text(GenericLabelRenderer.render(label(locale = "de"))).contains("Order #4711"))
    }

    @Test
    fun `Cyrillic and Turkish names print`() {
        val t = text(GenericLabelRenderer.render(label(to = listOf("Иван Петров", "ул. Ленина 5", "Москва"))))

        assertTrue(t.contains("Иван Петров"), t)
        assertTrue(t.contains("Москва"), t)
    }

    @Test
    fun `the barcode is drawn and differs with the text`() {
        val a = GenericLabelRenderer.render(label(barcode = "TRK123456"))
        val b = GenericLabelRenderer.render(label(barcode = "TRK123457"))
        val c = GenericLabelRenderer.render(label(barcode = "TRK123456"))

        assertTrue(!a.contentEquals(b))
        assertEquals(text(a).contains("TRK123456"), true)
        assertEquals(text(c).trim(), text(a).trim())
    }

    @Test
    fun `a merchant reference stands in for a missing tracking number`() {
        assertTrue(text(GenericLabelRenderer.render(label(barcode = "MRN-20260101-0042"))).contains("MRN-20260101-0042"))
    }

    @Test
    fun `hostile and oversized input never throws`() {
        val long = "x".repeat(400)
        val bytes = GenericLabelRenderer.render(
            label(barcode = "Çay\n" + "9".repeat(300), to = listOf(long, "\u0000‮ evil", "  ", "😀 emoji", long) + (1..40).map { "line $it" }, weight = null, publicId = null)
        )

        assertEquals(1, Loader.loadPDF(bytes).use { it.numberOfPages })
        assertTrue(text(bytes).isNotBlank())
    }

    @Test
    fun `address lines drop blank parts`() {
        assertEquals(emptyList<String>(), LabelAddress.lines(null as JsonObject?))
        assertEquals(listOf("Ada Lovelace", "Street 1", "1000 City", "GB"), LabelAddress.lines(Address("Ada", "Lovelace", " ", null, null, "GB", null, "City", null, null, "Street 1", "", "1000", null, null, null)))
    }

    @Test
    fun `weight under one kilo prints in grams`() {
        assertTrue(text(GenericLabelRenderer.render(label(weight = 480))).contains("480 g"))
        assertTrue(text(GenericLabelRenderer.render(label(weight = 12005))).contains("12.00 kg"))
    }

    @Test
    fun `the label route parses its query and names its file`() {
        assertEquals(0, parseLabelIndex(null))
        assertEquals(0, parseLabelIndex(" "))
        assertEquals(3, parseLabelIndex("3"))
        assertEquals(false, parseLabelGeneric(null))
        assertEquals(true, parseLabelGeneric("true"))
        assertEquals(true, parseLabelGeneric("1"))
        assertEquals(false, parseLabelGeneric("0"))

        for (bad in listOf("-1", "21", "x", "1.5")) assertThrows<RequestValueException> { parseLabelIndex(bad) }

        assertThrows<RequestValueException> { parseLabelGeneric("yes") }

        assertEquals("label-12-34-0.pdf", labelFileName(12, 34, 0, LabelFormat.PDF))
        assertEquals("label-12-34-2.zpl", labelFileName(12, 34, 2, LabelFormat.ZPL))
        assertEquals("application/octet-stream", labelContentType(LabelFormat.EPL))
        assertEquals("image/svg+xml", labelContentType(LabelFormat.SVG))
        assertEquals("text/html", labelContentType(LabelFormat.HTML))
        assertEquals("application/pdf", labelContentType(LabelFormat.PDF))
        assertEquals("image/png", labelContentType(LabelFormat.PNG))
        assertEquals("image/gif", labelContentType(LabelFormat.GIF))
        assertArrayEquals(LabelFormat.entries.map { labelContentType(it).isNotEmpty() }.toBooleanArray(), BooleanArray(LabelFormat.entries.size) { true })
    }
}
