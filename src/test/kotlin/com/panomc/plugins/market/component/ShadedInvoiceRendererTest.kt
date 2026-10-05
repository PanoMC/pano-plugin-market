package com.panomc.plugins.market.component

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.URL
import java.net.URLClassLoader
import java.util.zip.ZipFile

/**
 * T-PDF-5 (12 section 12, recon constraint 15): the invoice renderer run from the BUILT market jar in a fresh class loader. The loader takes every
 * market class, the relocated PDFBox / FontBox / commons-logging / easytable classes and their resources from the jar only, and refuses the original
 * `org.apache.pdfbox...` names outright, so a class or a resource path that relocation missed fails here (`NoClassDefFoundError`, a missing
 * resource) instead of in production. Runs in `shadedTest` (part of `check`), which builds the jar first and passes `market.jar`.
 */
@Tag("shaded")
class ShadedInvoiceRendererTest {
    private val jarPath = System.getProperty("market.jar")

    private val unshadedPrefixes = listOf("org.apache.pdfbox.", "org.apache.fontbox.", "org.apache.commons.logging.", "org.vandeseer.")

    /** Market and shaded classes and resources from the jar first; the original PDF library names are invisible; everything else is the host's. */
    private class JarFirstLoader(jar: File, parent: ClassLoader, private val hidden: List<String>) : URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }

                if (hidden.any { name.startsWith(it) }) throw ClassNotFoundException("$name is hidden: it must come from the relocated copy")

                if (name.startsWith("com.panomc.plugins.market.")) {
                    val own = try {
                        findClass(name)
                    } catch (e: ClassNotFoundException) {
                        null
                    }

                    if (own != null) {
                        if (resolve) resolveClass(own)

                        return own
                    }
                }

                return super.loadClass(name, resolve)
            }
        }

        override fun getResource(name: String): URL? = findResource(name) ?: super.getResource(name)
    }

    private fun texts(loader: ClassLoader, labels: Map<String, String>): Any {
        val iface = loader.loadClass("com.panomc.plugins.market.pdf.InvoiceTexts")

        return Proxy.newProxyInstance(
            loader, arrayOf(iface),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "label" -> (labels[args[0] as String] ?: (args[0] as String)).let { t -> (args[1] as Map<*, *>).entries.fold(t) { acc, e -> acc.replace("{${e.key}}", e.value.toString()) } }
                    "money" -> "%d,%02d ₺".format((args[0] as Long) / 100, (args[0] as Long) % 100)
                    "date" -> "01.01.2026"
                    "percent" -> "%d%%".format((args[0] as Long) / 100)
                    "getCreditName" -> "Credits"
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        )
    }

    private fun snapshot(): JsonObject = JsonObject()
        .put("v", 1).put("type", "INVOICE").put("number", "INV-2026-000007").put("issuedAt", 1_767_225_600_000L).put("locale", "tr").put("currency", "TRY")
        .put("timeZone", "UTC").put("testMode", false)
        .put("seller", JsonObject().put("name", "Çağrı Ltd Şti").put("address", "Kadıköy İstanbul").put("taxOffice", "Kadıköy").put("taxNumber", "123").put("websiteName", "Acme").put("websiteUrl", "https://acme.example"))
        .put("buyer", JsonObject().put("username", "Steve").put("name", "Öğüt Işık").put("company", "").put("addressLines", JsonArray()).put("country", "TR").put("email", "b@example.com"))
        .put("order", JsonObject().put("id", 5).put("createdAt", 1_767_225_600_000L).put("paidAt", 1_767_225_600_000L).put("paymentLabel", "Kart").put("isGift", false))
        .put("pricesIncludeVat", true)
        .put(
            "lines",
            JsonArray().add(
                JsonObject().put("kind", "PRODUCT").put("name", "Ğ İ ı ş VIP Ранг").put("variantName", "30").put("sku", "V30").put("quantity", 1).put("unitPrice", 12000)
                    .put("discount", 0).put("vatPercent", 2000).put("net", 10000).put("vat", 2000).put("gross", 12000)
            )
        )
        .put("vatRows", JsonArray().add(JsonObject().put("vatPercent", 2000).put("net", 10000).put("vat", 2000).put("gross", 12000)))
        .put("totals", JsonObject().put("subtotal", 12000).put("discount", 0).put("shipping", 0).put("paymentFee", 0).put("net", 10000).put("vat", 2000).put("total", 12000).put("creditValue", 0).put("creditAmount", 0).put("gatewayAmount", 12000))
        .put("footer", "Teşekkürler")

    @Test
    fun `the built jar renders an invoice with its relocated PDF stack and resources`() {
        assumeTrue(jarPath != null, "market.jar is set by the shadedTest task")

        val jar = File(jarPath)

        assertTrue(jar.isFile, "the shadow jar exists: $jar")

        // what relocation did to the jar
        ZipFile(jar).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()

            assertTrue(names.none { it.startsWith("org/apache/pdfbox/") || it.startsWith("org/apache/fontbox/") || it.startsWith("org/vandeseer/") || it.startsWith("org/apache/commons/logging/") }, "nothing at the original paths")
            assertTrue(names.any { it.startsWith("com/panomc/plugins/market/shaded/org/apache/pdfbox/resources/") }, "the PDFBox resources moved with the classes")
            assertTrue(names.any { it.startsWith("com/panomc/plugins/market/shaded/org/apache/fontbox/") && it.endsWith(".class") })
            assertTrue(names.any { it.startsWith("com/panomc/plugins/market/shaded/org/vandeseer/easytable/") && it.endsWith(".class") })
            assertTrue(names.any { it.startsWith("META-INF/LICENSE") || it.startsWith("META-INF/NOTICE") }, "the Apache LICENSE / NOTICE are kept")
            assertTrue("fonts/OFL.txt" in names)
        }

        val loader = JarFirstLoader(jar, javaClass.classLoader, unshadedPrefixes)

        loader.use {
            assertTrue(runCatching { Class.forName("org.apache.pdfbox.pdmodel.PDDocument", false, loader) }.isFailure, "the original names are not visible to the jar's classes")

            val renderer = loader.loadClass("com.panomc.plugins.market.pdf.InvoicePdfRenderer")

            assertEquals(loader, renderer.classLoader, "the renderer comes from the jar")

            val instance = renderer.getField("INSTANCE").get(null)
            val textsType = loader.loadClass("com.panomc.plugins.market.pdf.InvoiceTexts")
            val render = renderer.getMethod("render", JsonObject::class.java, textsType, ByteArray::class.java)
            val labels = mapOf("invoice.title" to "Fatura", "invoice.number" to "No", "invoice.date" to "Tarih", "invoice.order" to "Sipariş", "invoice.seller" to "Satıcı", "invoice.bill-to" to "Alıcı", "invoice.description" to "Açıklama", "invoice.page" to "Sayfa {page} / {pages}")
            val bytes = render.invoke(instance, snapshot(), texts(loader, labels), null) as ByteArray

            assertTrue(bytes.size > 1000, "a document came out")

            val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

            assertTrue(text.contains("INV-2026-000007"), text)
            assertTrue(text.contains("Çağrı Ltd Şti") && text.contains("Öğüt Işık") && text.contains("Ğ İ ı ş VIP Ранг"), text)
            assertTrue(text.contains("120,00 ₺"), text)
            assertTrue(text.contains("Sayfa 1 / 1"), text)

            // the same twice, byte for byte, also from the jar
            val again = render.invoke(instance, snapshot(), texts(loader, labels), null) as ByteArray

            assertTrue(bytes.contentEquals(again), "deterministic output")

            // and a hostile string does not throw from the jar either
            val hostile = snapshot().also { it.getJsonObject("buyer").put("name", "日本語 😀 \u0007x") }
            val ok = render.invoke(instance, hostile, texts(loader, labels), null) as ByteArray

            assertFalse(Loader.loadPDF(ok).use { PDFTextStripper().getText(it) }.contains("日"))
        }
    }
}
