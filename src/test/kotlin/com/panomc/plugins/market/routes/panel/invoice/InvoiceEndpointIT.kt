package com.panomc.plugins.market.routes.panel.invoice

import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.error.InvalidInvoiceSequence
import com.panomc.plugins.market.error.InvoiceNotIssuable
import com.panomc.plugins.market.error.InvoiceRenderFailed
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.pdf.InvoiceDocuments
import com.panomc.plugins.market.pdf.InvoiceMailAttachments
import com.panomc.plugins.market.pdf.InvoicePdfRenderer
import com.panomc.plugins.market.pdf.InvoiceTextsFactory
import com.panomc.plugins.market.routes.api.OrderAccess
import com.panomc.plugins.market.service.InvoiceOutcome
import com.panomc.plugins.market.service.InvoiceService
import com.panomc.plugins.market.service.InvoiceSite
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.support.ErrorBodies

/**
 * The invoice endpoints on a real MariaDB (MK-144; 12 sections 8 and 9; T-API-1 to T-API-3 and T-API-6 in their service form, T-DB-6): owner-only
 * download with lazy rendering and the stored file, regenerate that keeps the numbers (and picks up new labels), the preview, the series counter,
 * and the invoice attachment of the mail seam. The routes are thin shells over `InvoiceEndpoints`, so the rules are exercised here without the
 * host; the HTTP status of each error is the class's own and is asserted through its encoded body.
 */
class InvoiceEndpointIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var locks: Locks
    private lateinit var access: OrderAccess
    private lateinit var service: InvoiceService
    private lateinit var documents: InvoiceDocuments
    private lateinit var endpoints: InvoiceEndpoints
    private lateinit var folder: Path
    private var config = baseConfig()
    private val renders = AtomicInteger()
    private val labelOverrides = ConcurrentHashMap<String, String>()
    private var failRender = false
    private var hugePdf = false
    private var logo: ByteArray? = null

    override val poolSize: Int = 24

    private fun baseConfig(enabled: Boolean = true, locale: String = "") = MarketConfig(
        currency = "EUR", storeTimeZone = "UTC", invoiceEnabled = enabled, invoiceLocale = locale, invoiceSellerName = "Acme Ltd",
        invoiceSellerAddress = "1 Main St", invoiceSellerTaxOffice = "Kadikoy", invoiceSellerTaxNumber = "123456", invoiceFooter = "Thank you"
    )

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
        access = OrderAccess(w.orders, w.clock)
        config = baseConfig()
        renders.set(0)
        labelOverrides.clear()
        failRender = false
        hugePdf = false
        logo = null
        folder = Files.createTempDirectory("market-invoices")

        val bundles = listOf("tr", "en-US", "ru").associateWith { MarketI18n.flatten(File("src/locales/core/$it.json").readText()) }
        val i18n = MarketI18n(bundles, { locale -> if (locale == "en-US") labelOverrides.mapKeys { MarketI18n.PREFIX + it.key } else emptyMap() }, w.clock) { }
        val format = MarketFormat(i18n, { "UTC" }, { "Credits" })
        val site = InvoiceSite("Acme Craft", "https://acme.example")

        service = InvoiceService(
            config = { config }, clock = w.clock, orders = w.orders, orderEvents = w.orderEvents, refunds = w.refunds, refundItems = w.refundItems,
            invoices = w.invoices, sequences = w.sequences, site = { site }, defaultLocale = { "en-US" }
        )
        documents = InvoiceDocuments(
            base = folder.resolve("invoices"), invoices = w.invoices, clock = w.clock,
            texts = { snapshot, locale -> InvoiceTextsFactory.build(snapshot, locale, i18n, format, "Credits") },
            logo = { logo },
            onWorker = { block ->
                renders.incrementAndGet()

                if (failRender) throw IllegalStateException("renderer is down")
                if (hugePdf) ByteArray(6 * 1024 * 1024) else block()
            },
            sqlClient = { pool }
        )
        endpoints = InvoiceEndpoints(
            db = w.db, locks = locks, orders = w.orders, invoices = w.invoices, service = service, documents = documents, config = { config }, clock = w.clock,
            site = { site }, defaultLocale = { "en-US" }
        )
    }

    @AfterEach
    fun cleanFolder() {
        folder.toFile().deleteRecursively()
    }

    // ----- fixtures --------------------------------------------------------------------------------------------------------------

    private class Placed(val order: MarketOrder, val items: List<MarketOrderItem>)

    private var placedCount = 0

    private suspend fun place(
        gross: Long = 1200, vat: Long = 200, name: String = "Rank VIP", status: OrderStatus = OrderStatus.COMPLETED, pricingMode: PricingMode = PricingMode.MARKET,
        userId: Long? = null, recipientUserId: Long? = null, accessToken: String? = null, locale: String? = "tr"
    ): Placed {
        val paid = status in listOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)
        val now = w.clock.now()
        val id = w.orders.add(
            MarketOrder(
                playerUsername = "Steve", totalPrice = gross, currency = "EUR", paymentMethodId = "manual", paymentLabel = "Manual", status = status, createdAt = now, updatedAt = now,
                publicId = w.ids.publicId(), buyerKey = userId?.let { "u:$it" } ?: "g:steve${++placedCount}", email = "buyer@example.com", locale = locale, recipientUsername = "Steve",
                reservationState = if (paid) ReservationState.COMMITTED else ReservationState.NONE, pricingMode = pricingMode, subtotal = gross, vatTotal = vat, gatewayAmount = gross,
                paidAmount = if (paid) gross else 0, paidAt = if (paid) now else null, userId = userId, recipientUserId = recipientUserId, accessToken = accessToken
            ),
            pool
        )
        val itemId = w.orderItems.add(
            MarketOrderItem(
                orderId = id, productName = name, quantity = 1, unitPrice = gross, kind = OrderItemKind.PRODUCT, listUnitPrice = gross, vatPercent = 2000, vatAmount = vat, lineTotal = gross,
                createdAt = now, updatedAt = now
            ),
            pool
        )

        return Placed(w.orders.getById(id, pool)!!, listOf(w.orderItems.getById(itemId, pool)!!))
    }

    private suspend fun issue(placed: Placed): MarketInvoice =
        w.db.tx { conn -> service.issueForOrder(conn, w.orders.getById(placed.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(placed.order.id), conn), true) }
            .let { (it as InvoiceOutcome.Issued).invoice }

    private var refundCount = 0

    private suspend fun refund(placed: Placed, amount: Long, status: RefundStatus = RefundStatus.SUCCEEDED): Long {
        val now = w.clock.now()
        val id = w.refunds.add(
            MarketRefund(
                orderId = placed.order.id, status = status, idempotencyKey = "refund-${++refundCount}", amount = amount, gatewayAmount = amount, currency = "EUR", reason = "x",
                createdAt = now, updatedAt = now
            ),
            pool
        )!!
        val succeeded = sql("SELECT COALESCE(SUM(`amount`), 0) AS a, COALESCE(SUM(`gatewayAmount`), 0) AS g FROM `pano_market_refund` WHERE `orderId` = ? AND `status` = 'SUCCEEDED'", placed.order.id).single()

        Fixtures.setColumns(
            pool, "market_order", placed.order.id,
            mapOf(
                "refundedTotal" to succeeded.getLong("a"), "refundedGatewayAmount" to succeeded.getLong("g"),
                "status" to if (succeeded.getLong("a") >= placed.order.totalPrice) "REFUNDED" else "PARTIALLY_REFUNDED"
            )
        )

        return id
    }

    private suspend fun creditNote(placed: Placed, refundId: Long): MarketInvoice =
        w.db.tx { conn -> service.issueCreditNote(conn, w.orders.getById(placed.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(placed.order.id), conn), refundId) }
            .let { (it as InvoiceOutcome.Issued).invoice }

    private suspend fun resolve(placed: Placed, session: Long? = null, token: String? = null) =
        access.resolve(placed.order.publicId, session, null, token, true, "203.0.113.5", pool)

    private fun pdfText(file: File): String = Loader.loadPDF(file).use { PDFTextStripper().getText(it) }

    private fun isPdf(file: File) = file.readBytes().take(5) == "%PDF-".toByteArray().toList()

    private fun body(error: Error): JsonObject = JsonObject(error.encode(emptyMap()))

    private inline fun <reified T : Throwable> failure(block: () -> Unit): T = assertThrowsKt(block)

    private inline fun <reified T : Throwable> assertThrowsKt(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e

            throw e
        }

        throw AssertionError("expected ${T::class.simpleName}")
    }

    // ===== buyer download: owner only (T-API-1) =============================================================================================

    @Test
    fun `the owner downloads the invoice as a stored PDF named after its number`(): Unit = runBlocking {
        val placed = place(userId = 7)
        val invoice = issue(placed)
        val file = endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool)

        assertEquals(invoice.number, file.number)
        assertTrue(isPdf(file.file), "a PDF")
        assertTrue(pdfText(file.file).contains(invoice.number), "the number is in the document")
        assertTrue(pdfText(file.file).contains("Acme Ltd") && pdfText(file.file).contains("Rank VIP"))

        val row = w.invoices.getById(invoice.id, pool)!!

        assertEquals("2025/${invoice.number}.pdf", row.fileName, "the relative name is stored")
        assertEquals(folder.resolve("invoices/2025/${invoice.number}.pdf").toAbsolutePath().normalize(), file.file.toPath().toAbsolutePath().normalize())
        assertEquals(invoice.snapshot, row.snapshot, "the snapshot is untouched")
        assertEquals(invoice.number, row.number)
        assertEquals(1, renders.get())

        // rendered once: the second download serves the stored file
        endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool)

        assertEquals(1, renders.get())
    }

    @Test
    fun `anyone but the owner gets 404, a gift recipient and the limited view included, and a guest needs the token`(): Unit = runBlocking {
        val placed = place(userId = 7, recipientUserId = 8, accessToken = "tok-0123456789abcdef")

        issue(placed)

        assertEquals(1, endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool).file.length().coerceAtMost(1))
        assertTrue(runCatching { endpoints.buyerFile(resolve(placed, session = 9), InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound, "another user")
        assertTrue(runCatching { endpoints.buyerFile(resolve(placed, session = 8), InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound, "the gift recipient")
        assertTrue(runCatching { endpoints.buyerFile(resolve(placed, session = null), InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound, "anonymous")

        val guest = place(accessToken = "guest-token-0123456789")

        issue(guest)

        assertTrue(isPdf(endpoints.buyerFile(resolve(guest, token = "guest-token-0123456789"), InvoiceType.INVOICE, null, pool).file), "a guest with the token is the owner")
        assertTrue(runCatching { endpoints.buyerFile(resolve(guest), InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound, "a guest without the token")
        assertTrue(runCatching { endpoints.buyerFile(resolve(guest, token = "wrong-token-0123456789"), InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound, "a wrong token")
    }

    @Test
    fun `a missing document, an unknown refund and an ambiguous credit note are all 404`(): Unit = runBlocking {
        val noInvoice = place(userId = 7)

        assertTrue(runCatching { endpoints.buyerFile(resolve(noInvoice, session = 7), InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound, "no invoice yet")

        val placed = place(gross = 3000, vat = 500, userId = 7)

        issue(placed)

        assertTrue(runCatching { endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, null, pool) }.exceptionOrNull() is NotFound, "no credit note")

        val r1 = refund(placed, 500)
        val cn1 = creditNote(placed, r1)

        // exactly one credit note: no refundId needed
        assertEquals(cn1.number, endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, null, pool).number)
        assertEquals(cn1.number, endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, r1, pool).number)
        assertTrue(isPdf(endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, r1, pool).file))

        val r2 = refund(placed, 300)
        val cn2 = creditNote(placed, r2)

        // two credit notes: the caller must say which
        assertTrue(runCatching { endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, null, pool) }.exceptionOrNull() is NotFound, "ambiguous")
        assertEquals(cn2.number, endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, r2, pool).number)
        assertTrue(runCatching { endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, 999_999, pool) }.exceptionOrNull() is NotFound, "unknown refund")

        // a refund of another order is not this order's credit note
        val other = place(gross = 3000, vat = 500, userId = 7)

        issue(other)

        assertTrue(runCatching { endpoints.buyerFile(resolve(other, session = 7), InvoiceType.CREDIT_NOTE, r1, pool) }.exceptionOrNull() is NotFound, "a refund of another order")
        assertTrue(pdfText(endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.CREDIT_NOTE, r2, pool).file).contains(cn2.number))
    }

    @Test
    fun `a render failure is 500 INVOICE_RENDER_FAILED and leaves the row as it was`(): Unit = runBlocking {
        val placed = place(userId = 7)
        val invoice = issue(placed)

        failRender = true

        val failure = runCatching { endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool) }.exceptionOrNull()

        assertTrue(failure is InvoiceRenderFailed, "got $failure")
        assertEquals("INVOICE_RENDER_FAILED", body(failure as Error).getJsonObject("error").getString("code"))
        assertNull(w.invoices.getById(invoice.id, pool)!!.fileName)
        assertEquals(0, Files.walk(folder).filter { it.toString().endsWith(".pdf") || it.toString().endsWith(".tmp") }.count().toInt(), "no half-written file")

        failRender = false

        assertTrue(isPdf(endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool).file), "the next request works")
    }

    // ===== the file is a cache of the snapshot ===============================================================================================

    @Test
    fun `a deleted, an empty or a poisoned file name is rendered again from the snapshot`(): Unit = runBlocking {
        val placed = place(userId = 7)
        val invoice = issue(placed)
        val first = endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool).file

        first.delete()

        val second = endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool).file

        assertEquals(first.absolutePath, second.absolutePath)
        assertTrue(isPdf(second) && pdfText(second).contains(invoice.number), "recreated, same number")
        assertEquals(2, renders.get())

        second.writeBytes(ByteArray(0))

        assertTrue(isPdf(endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool).file), "an empty file is not served")
        assertEquals(3, renders.get())

        // a stored name that would leave the invoice folder is never followed
        Fixtures.setColumns(pool, "market_invoice", invoice.id, mapOf("fileName" to "../../escape.pdf"))

        val third = endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool).file

        assertTrue(third.toPath().toAbsolutePath().normalize().startsWith(folder.resolve("invoices").toAbsolutePath().normalize()))
        assertEquals("2025/${invoice.number}.pdf", w.invoices.getById(invoice.id, pool)!!.fileName, "the row names the real file again")
        assertFalse(folder.resolve("escape.pdf").toFile().exists())
        assertEquals(4, renders.get())
    }

    @Test
    fun `parallel first downloads are harmless, one file, one name, a valid PDF`(): Unit = runBlocking {
        val placed = place(userId = 7)

        issue(placed)

        val files = (1..8).map { async { endpoints.buyerFile(resolve(placed, session = 7), InvoiceType.INVOICE, null, pool).file } }.awaitAll()

        assertEquals(1, files.map { it.absolutePath }.toSet().size)
        assertTrue(isPdf(files.first()))
        assertEquals(0, Files.walk(folder).filter { it.toString().endsWith(".tmp") }.count().toInt(), "no temp file stays")
        assertEquals("2025/${w.invoices.getByOrderId(placed.order.id, pool).single().number}.pdf", w.invoices.getByOrderId(placed.order.id, pool).single().fileName)
    }

    // ===== panel download (T-API-2 in service form) =========================================================================================

    @Test
    fun `the panel downloads any order's document and an unknown order or document is 404`(): Unit = runBlocking {
        val placed = place(userId = 7)
        val invoice = issue(placed)

        assertEquals(invoice.number, endpoints.panelFile(placed.order.id, InvoiceType.INVOICE, null, pool).number)
        assertTrue(runCatching { endpoints.panelFile(999_999, InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound)
        assertTrue(runCatching { endpoints.panelFile(place().order.id, InvoiceType.INVOICE, null, pool) }.exceptionOrNull() is NotFound)
    }

    // ===== regenerate (T-API-3) ===============================================================================================================

    @Test
    fun `regenerate keeps every number and snapshot and renders the files again`(): Unit = runBlocking {
        val placed = place(gross = 3000, vat = 500, userId = 7)
        val invoice = issue(placed)
        val cn = creditNote(placed, refund(placed, 500))

        endpoints.panelFile(placed.order.id, InvoiceType.INVOICE, null, pool)
        endpoints.panelFile(placed.order.id, InvoiceType.CREDIT_NOTE, null, pool)

        val rendersBefore = renders.get()
        val counterBefore = w.sequences.getValue("invoice:INV", pool)
        val result = endpoints.regenerate(placed.order.id, pool)

        assertEquals(setOf(invoice.number, cn.number), result.map { it.number }.toSet())
        assertEquals(2, renders.get() - rendersBefore, "each document was rendered again")
        assertEquals(counterBefore, w.sequences.getValue("invoice:INV", pool), "no number was taken")
        assertEquals(2L, count("market_invoice"))

        for (before in listOf(invoice, cn)) {
            val after = w.invoices.getById(before.id, pool)!!

            assertEquals(before.number, after.number)
            assertEquals(before.snapshot, after.snapshot, "the snapshot is the source of truth and never corrected")
            assertEquals(before.issuedAt, after.issuedAt)
            assertNotNull(after.fileName)
            assertTrue(isPdf(folder.resolve("invoices/${after.fileName}").toFile()))
        }

        // and again: still the same numbers
        assertEquals(setOf(invoice.number, cn.number), endpoints.regenerate(placed.order.id, pool).map { it.number }.toSet())
        assertEquals(2L, count("market_invoice"))
    }

    @Test
    fun `regenerate renders with the current labels while the stored snapshot stays as issued`(): Unit = runBlocking {
        val placed = place(userId = 7, locale = "en-US")
        val invoice = issue(placed)

        assertTrue(pdfText(endpoints.panelFile(placed.order.id, InvoiceType.INVOICE, null, pool).file).contains("Invoice"))

        labelOverrides["invoice.title"] = "Tax Bill"
        w.clock.advance(61_000) // the override cache lives 60 s

        // the cached file is served until regenerate drops it
        assertFalse(pdfText(endpoints.panelFile(placed.order.id, InvoiceType.INVOICE, null, pool).file).contains("Tax Bill"))

        endpoints.regenerate(placed.order.id, pool)

        val text = pdfText(endpoints.panelFile(placed.order.id, InvoiceType.INVOICE, null, pool).file)

        assertTrue(text.contains("Tax Bill"), text)
        assertTrue(text.contains(invoice.number))
        assertEquals(invoice.snapshot, w.invoices.getById(invoice.id, pool)!!.snapshot)
    }

    @Test
    fun `regenerate recreates a file that was deleted from the disk`(): Unit = runBlocking {
        val placed = place(userId = 7)
        val invoice = issue(placed)
        val file = endpoints.panelFile(placed.order.id, InvoiceType.INVOICE, null, pool).file

        assertTrue(file.delete())
        assertEquals(invoice.number, endpoints.regenerate(placed.order.id, pool).single().number)
        assertTrue(file.exists() && isPdf(file))
    }

    @Test
    fun `an order that has no invoice gets one now, and the credit notes of its succeeded refunds, in refund order`(): Unit = runBlocking {
        config = baseConfig(enabled = false) // the explicit admin action ignores the switch (12 section 8.4 b)

        val placed = place(gross = 3000, vat = 500, userId = 7)
        val r1 = refund(placed, 500)
        val failed = refund(placed, 700, RefundStatus.FAILED)
        val r2 = refund(placed, 300)

        w.clock.advance(5_000)

        val result = endpoints.regenerate(placed.order.id, pool)

        assertEquals(listOf(InvoiceType.INVOICE, InvoiceType.CREDIT_NOTE, InvoiceType.CREDIT_NOTE), result.sortedBy { it.id }.map { it.type })
        assertEquals("INV-2025-000001", result.first { it.type == InvoiceType.INVOICE }.number)
        assertEquals(w.clock.now(), result.first { it.type == InvoiceType.INVOICE }.issuedAt, "issuedAt = now")
        assertEquals(listOf(r1, r2), result.filter { it.type == InvoiceType.CREDIT_NOTE }.sortedBy { it.id }.map { it.refundId }, "in refund-id order, the failed refund has none")
        assertEquals(listOf("CN-2025-000001", "CN-2025-000002"), result.filter { it.type == InvoiceType.CREDIT_NOTE }.sortedBy { it.id }.map { it.number })
        assertEquals(0L, count("market_invoice", "`refundId` = ?", failed))
        assertEquals(3L, count("market_invoice"))
        assertEquals(result.first { it.type == InvoiceType.INVOICE }.id, w.orders.getById(placed.order.id, pool)!!.invoiceId)
        assertTrue(result.all { w.invoices.getById(it.id, pool)!!.fileName != null })
    }

    @Test
    fun `nothing to do and nothing issuable is 409 INVOICE_NOT_ISSUABLE with the reason`(): Unit = runBlocking {
        fun reason(block: () -> Unit): String {
            val e = failure<InvoiceNotIssuable>(block)

            assertEquals("INVOICE_NOT_ISSUABLE", body(e).getJsonObject("error").getString("code"))

            return ErrorBodies.details(e).getString("reason")
        }

        val unpaid = place(status = OrderStatus.PENDING)

        assertEquals("NOT_PAID", reason { runBlocking { endpoints.regenerate(unpaid.order.id, pool) } })
        assertEquals("EXTERNAL_PRICING", reason { runBlocking { endpoints.regenerate(place(pricingMode = PricingMode.EXTERNAL).order.id, pool) } })
        assertEquals("ZERO_TOTAL", reason { runBlocking { endpoints.regenerate(place(gross = 0, vat = 0).order.id, pool) } })
        assertTrue(runCatching { endpoints.regenerate(999_999, pool) }.exceptionOrNull() is NotFound)

        assertEquals(0L, count("market_invoice"))
        assertNull(w.sequences.getValue("invoice:INV", pool), "a refusal takes no number")
    }

    // ===== preview (T-API-6) ================================================================================================================

    @Test
    fun `the preview is a sample PDF under the current seller settings and stores nothing`(): Unit = runBlocking {
        config = baseConfig().let { MarketConfig(currency = it.currency, storeTimeZone = "UTC", invoiceSellerName = "Preview Seller", invoiceSellerAddress = "9 Test Road", invoiceFooter = "Custom footer") }

        val bytes = endpoints.preview(null, InvoiceType.INVOICE)
        val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

        assertTrue(bytes.take(5) == "%PDF-".toByteArray().toList())
        assertTrue(text.contains("INV-0000-000000"), text)
        assertTrue(text.contains("Preview Seller") && text.contains("9 Test Road") && text.contains("Custom footer"), text)
        assertTrue(text.contains("Invoice"), "the platform default locale en-US")

        val tr = Loader.loadPDF(endpoints.preview("tr", InvoiceType.INVOICE)).use { PDFTextStripper().getText(it) }

        assertTrue(tr.contains("Fatura"), tr)

        val cn = Loader.loadPDF(endpoints.preview("ru", InvoiceType.CREDIT_NOTE)).use { PDFTextStripper().getText(it) }

        assertTrue(cn.contains("CN-0000-000000") && cn.contains("Кредит-нота"), cn)

        config = MarketConfig(currency = "EUR", storeTimeZone = "UTC", invoiceLocale = "tr")

        assertTrue(Loader.loadPDF(endpoints.preview(null, InvoiceType.INVOICE)).use { PDFTextStripper().getText(it) }.contains("Fatura"), "invoiceLocale is the default")
        val fallback = Loader.loadPDF(endpoints.preview(null, InvoiceType.INVOICE)).use { PDFTextStripper().getText(it) }

        assertTrue(fallback.split("Acme Craft").size - 1 >= 2, "an empty seller name falls back to the website name (seller block and footer line)")

        assertEquals(0L, count("market_invoice"))
        assertNull(w.sequences.getValue("invoice:INV", pool))
        assertEquals(0, Files.walk(folder).filter { it.toString().endsWith(".pdf") }.count().toInt(), "nothing is stored")
    }

    @Test
    fun `the logo is drawn when there is one and the preview works without`(): Unit = runBlocking {
        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(120, 40, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()

        logo = png

        val withLogo = Loader.loadPDF(endpoints.preview(null, InvoiceType.INVOICE)).use { doc -> doc.getPage(0).resources.xObjectNames.count() }

        logo = null

        val without = Loader.loadPDF(endpoints.preview(null, InvoiceType.INVOICE)).use { doc -> doc.getPage(0).resources.xObjectNames.count() }

        assertEquals(1, withLogo)
        assertEquals(0, without)
    }

    // ===== invoice-sequence (T-DB-6) ==========================================================================================================

    @Test
    fun `the sequence moves forward, never backward, and the next number honours it`(): Unit = runBlocking {
        endpoints.setSequence("INV", 100)

        assertEquals(99L, w.sequences.getValue("invoice:INV", pool))
        assertEquals("INV-2025-000100", issue(place()).number, "the next issued number is the one that was set")

        val low = failure<InvalidInvoiceSequence> { runBlocking { endpoints.setSequence("INV", 50) } }

        assertEquals("INVALID_INVOICE_SEQUENCE", body(low).getJsonObject("error").getString("code"))
        assertEquals(101L, ErrorBodies.details(low).getLong("minimum"), "the smallest accepted value is the counter plus one")
        assertEquals(100L, w.sequences.getValue("invoice:INV", pool), "the counter did not move")

        assertEquals("INV-2025-000101", issue(place()).number)

        endpoints.setSequence("INV", 102) // equal to the next number: accepted, a no-op
        assertEquals(101L, w.sequences.getValue("invoice:INV", pool))

        for (bad in listOf("inv", "TOOLONGSERIES", "A B", "")) {
            val e = failure<InvalidInvoiceSequence> { runBlocking { endpoints.setSequence(bad, 5) } }

            assertNull(ErrorBodies.details(e).getValue("minimum"), "an invalid series has no minimum: '$bad'")
        }

        assertTrue(runCatching { endpoints.setSequence("CN", 0) }.exceptionOrNull() is InvalidInvoiceSequence, "zero would move backwards from nothing too")
    }

    @Test
    fun `the series listing shows the counters and the configured series that have no row yet`(): Unit = runBlocking {
        issue(place())

        val list = endpoints.sequences(pool)
        val inv = list.map { it as JsonObject }.first { it.getString("series") == "INV" }
        val cn = list.map { it as JsonObject }.first { it.getString("series") == "CN" }

        assertEquals(1L, inv.getLong("lastNumber"))
        assertEquals(2L, inv.getLong("nextNumber"))
        assertEquals(0L, cn.getLong("lastNumber"))
        assertEquals(1L, cn.getLong("nextNumber"))
    }

    // ===== the invoice attachment of the mail seam (12 section 4.4) ===================================================================

    private fun mail(kind: MailKind, orderId: Long, refType: MailRefType = MailRefType.ORDER, refId: Long = orderId) =
        MarketMailOutbox(kind = kind, refType = refType, refId = refId, orderId = orderId, recipient = "buyer@example.com")

    private fun attachments(attach: Boolean = true) = InvoiceMailAttachments(w.invoices, documents) { attach }

    @Test
    fun `ORDER_CONFIRMATION carries the invoice and ORDER_REFUNDED the credit note, named after their numbers`(): Unit = runBlocking {
        val placed = place(gross = 3000, vat = 500, userId = 7)
        val invoice = issue(placed)
        val refundId = refund(placed, 500)
        val cn = creditNote(placed, refundId)

        val confirmation = attachments().attachmentsFor(mail(MailKind.ORDER_CONFIRMATION, placed.order.id), pool)

        assertFalse(confirmation.invoiceRenderFailed)
        assertEquals("${invoice.number}.pdf", confirmation.files.single().name)
        assertEquals("application/pdf", confirmation.files.single().contentType)
        assertTrue(confirmation.files.single().data.take(5) == "%PDF-".toByteArray().toList())

        val refunded = attachments().attachmentsFor(mail(MailKind.ORDER_REFUNDED, placed.order.id, MailRefType.REFUND, refundId), pool)

        assertEquals("${cn.number}.pdf", refunded.files.single().name)

        // no document for the reference, another kind, the switch off: no attachment and no failure
        assertTrue(attachments().attachmentsFor(mail(MailKind.ORDER_REFUNDED, placed.order.id, MailRefType.REFUND, 999_999), pool).files.isEmpty())
        assertTrue(attachments().attachmentsFor(mail(MailKind.ORDER_DELIVERED, placed.order.id), pool).files.isEmpty())
        assertTrue(attachments().attachmentsFor(mail(MailKind.GIFT_RECEIVED, placed.order.id), pool).files.isEmpty())
        assertTrue(attachments(attach = false).attachmentsFor(mail(MailKind.ORDER_CONFIRMATION, placed.order.id), pool).files.isEmpty())
        assertTrue(attachments().attachmentsFor(mail(MailKind.ORDER_CONFIRMATION, place().order.id), pool).let { it.files.isEmpty() && !it.invoiceRenderFailed }, "an order without an invoice")
    }

    @Test
    fun `a render failure is reported and the mail goes out without the attachment, a PDF over 5 MB is left off`(): Unit = runBlocking {
        val placed = place(userId = 7)

        issue(placed)

        failRender = true

        val failed = attachments().attachmentsFor(mail(MailKind.ORDER_CONFIRMATION, placed.order.id), pool)

        assertTrue(failed.files.isEmpty())
        assertTrue(failed.invoiceRenderFailed, "INVOICE_RENDER_FAILED is kept on the SENT row")

        failRender = false
        hugePdf = true

        val huge = attachments().attachmentsFor(mail(MailKind.ORDER_CONFIRMATION, placed.order.id), pool)

        assertTrue(huge.files.isEmpty() && !huge.invoiceRenderFailed, "over 5 MB: no attachment, no failure")
    }

    // ===== direct renderer use on a stored snapshot ============================================================================================

    @Test
    fun `the stored snapshot of an issued invoice renders without the service`(): Unit = runBlocking {
        val placed = place(userId = 7)
        val invoice = issue(placed)
        val snapshot = JsonObject(invoice.snapshot)
        val i18n = MarketI18n(listOf("tr", "en-US", "ru").associateWith { MarketI18n.flatten(File("src/locales/core/$it.json").readText()) }, { emptyMap() }, w.clock) { }
        val texts = InvoiceTextsFactory.build(snapshot, invoice.locale, i18n, MarketFormat(i18n, { "UTC" }, { "" }), "")
        val bytes = InvoicePdfRenderer.render(snapshot, texts, null)

        assertTrue(Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }.contains(invoice.number))
    }
}
