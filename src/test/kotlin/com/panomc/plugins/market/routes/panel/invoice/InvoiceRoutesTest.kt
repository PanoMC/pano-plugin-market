package com.panomc.plugins.market.routes.panel.invoice

import com.panomc.platform.annotation.Endpoint
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.order.GetOrderInvoiceAPI
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The route shells of MK-144 (04 sections 3, 7 and 8; 12 section 9): what the request parsers accept, the headers of a PDF that holds billing data,
 * which class sits under which base, and that the composition root actually uses the invoice service. The behaviour behind the routes is
 * `InvoiceEndpointIT`; the host (router, sessions, CSRF) is outside this tier.
 */
class InvoiceRoutesTest {
    @Test
    fun `type defaults to INVOICE and refuses anything else`() {
        assertEquals(InvoiceType.INVOICE, parseInvoiceType(null))
        assertEquals(InvoiceType.INVOICE, parseInvoiceType(""))
        assertEquals(InvoiceType.INVOICE, parseInvoiceType("  "))
        assertEquals(InvoiceType.INVOICE, parseInvoiceType("INVOICE"))
        assertEquals(InvoiceType.CREDIT_NOTE, parseInvoiceType("CREDIT_NOTE"))

        for (bad in listOf("invoice", "RECEIPT", "INVOICE;", "1")) assertThrows(RequestValueException::class.java) { parseInvoiceType(bad) }
    }

    @Test
    fun `refundId is absent or a positive integer id`() {
        assertNull(parseRefundId(null))
        assertNull(parseRefundId(""))
        assertEquals(12L, parseRefundId("12"))

        for (bad in listOf("0", "-1", "1.5", "1e3", "abc", "9999999999999999999999")) assertThrows(RequestValueException::class.java, { parseRefundId(bad) }, bad)
    }

    @Test
    fun `the preview locale is absent or a locale code`() {
        assertNull(parsePreviewLocale(null))
        assertNull(parsePreviewLocale(" "))

        for (ok in listOf("tr", "en-US", "ru", "pt_BR", "zh-Hant-TW")) assertEquals(ok, parsePreviewLocale(ok))
        for (bad in listOf("t", "../../etc", "en US", "english-united-states-x", "t\nr", "<b>")) assertThrows(RequestValueException::class.java, { parsePreviewLocale(bad) }, bad)
    }

    @Test
    fun `the sequence body needs a series and an integral number`() {
        assertEquals("INV" to 100L, parseSequenceBody(JsonObject().put("series", "INV").put("nextNumber", 100)))
        assertEquals("CN" to 5L, parseSequenceBody(JsonObject().put("series", " CN ").put("nextNumber", 5.0)))
        assertEquals("INV" to 0L, parseSequenceBody(JsonObject().put("series", "INV").put("nextNumber", 0)), "zero is the service's 400, not a parse error")
        assertEquals("INV" to -3L, parseSequenceBody(JsonObject().put("series", "INV").put("nextNumber", -3)))

        for (bad in listOf(
            JsonObject().put("nextNumber", 5), JsonObject().put("series", "").put("nextNumber", 5), JsonObject().put("series", 5).put("nextNumber", 5),
            JsonObject().put("series", "INV"), JsonObject().put("series", "INV").put("nextNumber", 1.5), JsonObject().put("series", "INV").put("nextNumber", "7"),
            JsonObject().put("series", "INV").put("nextNumber", 1.0E20)
        )) {
            assertThrows(RequestValueException::class.java, { parseSequenceBody(bad) }, bad.encode())
        }
    }

    private class Recorder {
        val headers = linkedMapOf<String, String>()

        val context: RoutingContext = run {
            val response = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServerResponse::class.java)) { proxy, method, args ->
                if (method.name == "putHeader") headers[args[0].toString()] = args[1].toString()

                if (method.returnType == HttpServerResponse::class.java) proxy else null
            } as HttpServerResponse

            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RoutingContext::class.java)) { _, method, _ ->
                if (method.name == "response") response else null
            } as RoutingContext
        }
    }

    @Test
    fun `a PDF with billing data is an attachment that is never sniffed, cached or referred`() {
        val recorder = Recorder()

        pdfHeaders(recorder.context, "INV-2025-000001.pdf")

        assertEquals("application/pdf", recorder.headers["Content-Type"])
        assertEquals("attachment; filename=\"INV-2025-000001.pdf\"", recorder.headers["Content-Disposition"])
        assertEquals("nosniff", recorder.headers["X-Content-Type-Options"])
        assertEquals("no-store", recorder.headers["Cache-Control"])
        assertEquals("no-referrer", recorder.headers["Referrer-Policy"])

        val preview = Recorder()

        pdfHeaders(preview.context, "invoice-preview.pdf", inline = true)

        assertEquals("inline; filename=\"invoice-preview.pdf\"", preview.headers["Content-Disposition"])
        assertEquals("no-store", preview.headers["Cache-Control"])
    }

    @Test
    fun `the routes sit under the right base class, are endpoints, and the panel ones carry the nodes of 04 section 7`() {
        for (type in listOf(PanelGetOrderInvoiceAPI::class.java, PanelRegenerateOrderInvoiceAPI::class.java, PanelGetInvoicePreviewAPI::class.java, PanelUpdateInvoiceSequenceAPI::class.java)) {
            assertTrue(MarketPanelApi::class.java.isAssignableFrom(type), "${type.simpleName} is a MarketPanelApi")
            assertTrue(type.isAnnotationPresent(Endpoint::class.java), "${type.simpleName} is an @Endpoint")
        }

        assertTrue(MarketApi::class.java.isAssignableFrom(GetOrderInvoiceAPI::class.java))
        assertTrue(GetOrderInvoiceAPI::class.java.isAnnotationPresent(Endpoint::class.java))

        // the node sets are constructor-bound to a plugin, so read them from the source of truth: the class declarations
        val source = File("src/main/kotlin/com/panomc/plugins/market/routes/panel/invoice/InvoiceRoutes.kt").readText()

        fun nodesOf(cls: String) = Regex("class $cls\\(plugin: MarketPlugin\\) : InvoiceRoute\\(plugin, setOf\\(([^)]*)\\)\\)").find(source)!!.groupValues[1]
            .split(",").map { MarketNode.valueOf(it.trim().removePrefix("MarketNode.")) }.toSet()

        assertEquals(setOf(MarketNode.ORDERS_MANAGE, MarketNode.PAYMENTS), nodesOf("PanelGetOrderInvoiceAPI"), "the document holds billing data: OM or PAY")
        assertEquals(setOf(MarketNode.ORDERS_MANAGE), nodesOf("PanelRegenerateOrderInvoiceAPI"))
        assertEquals(setOf(MarketNode.SETTINGS), nodesOf("PanelGetInvoicePreviewAPI"))
        assertEquals(setOf(MarketNode.SETTINGS), nodesOf("PanelUpdateInvoiceSequenceAPI"))
    }

    @Test
    fun `the composition root issues invoices inside the order transitions and the settings page lists the sequences`() {
        val order = File("src/main/kotlin/com/panomc/plugins/market/routes/api/order/OrderRouteSupport.kt").readText()
        val settings = File("src/main/kotlin/com/panomc/plugins/market/routes/panel/settings/PanelGetSettingsAPI.kt").readText()

        // evidence/MK-076.md seam 2 and evidence/MK-143.md seams 1 and 3: an unwired IssueInvoice effect is only a WARN and a paid order gets no invoice
        assertTrue(Regex("InvoiceEffects\\(\\s*invoiceService\\(plugin\\),\\s*orderDao").containsMatchIn(order), "OrderService is built with InvoiceEffects")
        assertTrue(settings.contains("\"invoiceSequences\""), "GET /settings carries invoiceSequences")
    }
}
