package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.invoice.InvoiceSkip
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `InvoiceService` on a real MariaDB (MK-143; 12 sections 6 and 7; tests T-DB-1 to T-DB-3 and T-DB-6 of 12 section 12, R-19 of 17):
 * one invoice per order, one credit note per refund with its line allocation, numbering by series, the savepoint that gives a
 * number back, the counter that only moves up, and 20 orders paid at the same moment. I15 (and every other invariant) is checked
 * after each test by the base class.
 *
 * Orders are written straight through the DAOs, consistent enough for I8, I9 and I11 (a paid order carries `COMMITTED`, `paidAt`
 * and the `manual` method, so no payment attempt rows are needed). The credit part of a mixed order is `creditValue` only:
 * `creditAmount` stays 0 because I3b would ask for ledger rows that this class has no use for.
 */
class InvoiceServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var locks: Locks
    private var config = baseConfig()
    private var site = InvoiceSite("Acme Craft", "https://acme.example")
    private var platformLocale = "en-US"
    private lateinit var invoices: FlakyInvoices
    private lateinit var service: InvoiceService

    override val poolSize: Int = 32

    private fun baseConfig(
        enabled: Boolean = true,
        creditOrders: Boolean = false,
        locale: String = "",
        series: String = "INV",
        creditNoteSeries: String = "CN",
        zone: String = "UTC"
    ) = MarketConfig(
        currency = "EUR", storeTimeZone = zone, invoiceEnabled = enabled, invoiceCreditOrders = creditOrders, invoiceLocale = locale,
        invoiceSeries = series, invoiceCreditNoteSeries = creditNoteSeries, invoiceSellerName = "Acme Ltd", invoiceSellerAddress = "1 Main St",
        invoiceSellerTaxOffice = "Kadikoy", invoiceSellerTaxNumber = "123456", invoiceFooter = "Thank you"
    )

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
        config = baseConfig()
        site = InvoiceSite("Acme Craft", "https://acme.example")
        platformLocale = "en-US"
        invoices = FlakyInvoices(w.invoices)
        service = newService()
    }

    private fun newService() = InvoiceService(
        config = { config }, clock = w.clock, orders = w.orders, orderEvents = w.orderEvents, refunds = w.refunds, refundItems = w.refundItems,
        invoices = invoices, sequences = w.sequences, site = { site }, defaultLocale = { platformLocale }
    )

    /** The real invoice DAO that can be told to fail the next insert: the failure after a number was taken. */
    class FlakyInvoices(private val real: MarketInvoiceDao) : MarketInvoiceDao() {
        @Volatile
        var failNextAdd: Throwable? = null

        override suspend fun init(sqlClient: SqlClient) = real.init(sqlClient)

        override suspend fun add(invoice: MarketInvoice, sqlClient: SqlClient): Long? {
            failNextAdd?.let {
                failNextAdd = null

                throw it
            }

            return real.add(invoice, sqlClient)
        }

        override suspend fun getById(id: Long, sqlClient: SqlClient) = real.getById(id, sqlClient)

        override suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient) = real.getByOrderId(orderId, sqlClient)

        override suspend fun getByOrderTypeRefund(orderId: Long, type: InvoiceType, refundId: Long, sqlClient: SqlClient) =
            real.getByOrderTypeRefund(orderId, type, refundId, sqlClient)
    }

    // ----- fixtures --------------------------------------------------------------------------------------------------

    private class Placed(val order: MarketOrder, val items: List<MarketOrderItem>)

    /** A line of an order: gross amount, the VAT inside it, the VAT rate in basis points. */
    private class L(val gross: Long, val vat: Long, val percent: Long, val name: String = "Rank VIP", val quantity: Int = 1)

    private val ten = L(1100, 100, 1000, "Ten")
    private val twenty = L(2400, 400, 2000, "Twenty", quantity = 2)
    private val placedCount = AtomicInteger()

    private suspend fun place(
        vararg lines: L,
        currency: String = "EUR",
        testMode: Boolean = false,
        creditValue: Long = 0,
        locale: String? = "tr",
        status: OrderStatus = OrderStatus.COMPLETED,
        pricingMode: PricingMode = PricingMode.MARKET,
        billing: String? = null
    ): Placed {
        val total = lines.sumOf { it.gross }
        val paid = status in listOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)
        val now = w.clock.now()
        val id = w.orders.add(
            MarketOrder(
                playerUsername = "Steve", totalPrice = total, currency = currency, paymentMethodId = "manual", paymentLabel = "Manual", status = status,
                createdAt = now, updatedAt = now, publicId = w.ids.publicId(), buyerKey = "g:steve${placedCount.incrementAndGet()}", email = "buyer@example.com", locale = locale,
                recipientUsername = "Steve", reservationState = if (paid) ReservationState.COMMITTED else ReservationState.NONE, pricingMode = pricingMode,
                subtotal = total, vatTotal = lines.sumOf { it.vat }, creditValue = creditValue, gatewayAmount = total - creditValue,
                paidAmount = if (paid) total - creditValue else 0, paidAt = if (paid) now else null, testMode = testMode, billingInfo = billing
            ),
            pool
        )

        val items = lines.map { line ->
            val itemId = w.orderItems.add(
                MarketOrderItem(
                    orderId = id, productName = line.name, quantity = line.quantity, unitPrice = line.gross / line.quantity, kind = OrderItemKind.PRODUCT,
                    listUnitPrice = line.gross / line.quantity, vatPercent = line.percent, vatAmount = line.vat, lineTotal = line.gross, createdAt = now, updatedAt = now
                ),
                pool
            )

            w.orderItems.getById(itemId, pool)!!
        }

        return Placed(w.orders.getById(id, pool)!!, items)
    }

    /** The order and its items as the database holds them now, the way [Locks.forOrder] hands them to the transition. */
    private suspend fun freshOrder(conn: SqlConnection, id: Long) = w.orders.getById(id, conn)!!

    private suspend fun freshItems(conn: SqlConnection, id: Long) = w.orderItems.getByOrderIds(listOf(id), conn)

    private suspend fun issue(placed: Placed, admin: Boolean = false): InvoiceOutcome =
        w.db.tx { conn -> service.issueForOrder(conn, freshOrder(conn, placed.order.id), freshItems(conn, placed.order.id), admin) }

    private suspend fun creditNote(placed: Placed, refundId: Long): InvoiceOutcome =
        w.db.tx { conn -> service.issueCreditNote(conn, freshOrder(conn, placed.order.id), freshItems(conn, placed.order.id), refundId) }

    private val refundCount = AtomicInteger()

    private suspend fun refund(
        placed: Placed,
        amount: Long,
        status: RefundStatus = RefundStatus.SUCCEEDED,
        gateway: Long = amount,
        creditValue: Long = 0,
        rows: List<Pair<MarketOrderItem, Pair<Int, Long>>> = emptyList(),
        reason: String? = "defective",
        orderId: Long = placed.order.id
    ): Long {
        val now = w.clock.now()
        val id = w.refunds.add(
            MarketRefund(
                orderId = orderId, status = status, idempotencyKey = "refund-${refundCount.incrementAndGet()}", amount = amount, gatewayAmount = gateway, creditValue = creditValue,
                currency = placed.order.currency, reason = reason, createdAt = now, updatedAt = now
            ),
            pool
        )!!

        for ((item, quantityAndAmount) in rows) {
            w.refundItems.add(MarketRefundItem(refundId = id, orderItemId = item.id, quantity = quantityAndAmount.first, amount = quantityAndAmount.second, createdAt = now, updatedAt = now), pool)
        }

        // the books of the order (I9): what came back is the sum of the SUCCEEDED refunds
        val succeeded = sql("SELECT COALESCE(SUM(`amount`), 0) AS a, COALESCE(SUM(`gatewayAmount`), 0) AS g FROM `pano_market_refund` WHERE `orderId` = ? AND `status` = 'SUCCEEDED'", orderId).single()

        Fixtures.setColumns(
            pool, "market_order", orderId,
            mapOf("refundedTotal" to succeeded.getLong("a"), "refundedGatewayAmount" to succeeded.getLong("g"), "status" to if (succeeded.getLong("a") >= placed.order.totalPrice) "REFUNDED" else "PARTIALLY_REFUNDED")
        )

        return id
    }

    private suspend fun invoiceRows(): List<MarketInvoice> = sql("SELECT `id` FROM `pano_market_invoice` ORDER BY `id`").map { w.invoices.getById(it.getLong("id"), pool)!! }

    private suspend fun counter(series: String): Long? = w.sequences.getValue("invoice:$series", pool)

    private suspend fun notes(orderId: Long): List<String> =
        sql("SELECT `message` FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'NOTE' ORDER BY `id`", orderId).map { it.getString("message") }

    private fun snapshotOf(invoice: MarketInvoice) = JsonObject(invoice.snapshot)

    private fun issued(outcome: InvoiceOutcome): MarketInvoice = (outcome as? InvoiceOutcome.Issued)?.invoice ?: error("expected Issued, got ${describe(outcome)}")

    private fun describe(outcome: InvoiceOutcome): String = when (outcome) {
        is InvoiceOutcome.Issued -> "Issued ${outcome.invoice.number}"
        is InvoiceOutcome.Existing -> "Existing ${outcome.invoice.number}"
        is InvoiceOutcome.Skipped -> "Skipped ${outcome.reason}"
        is InvoiceOutcome.NotIssued -> "NotIssued ${outcome.reason}"
    }

    // ===== one invoice per order ==========================================================================================

    @Test
    fun `a paid order gets an invoice with a number, a frozen snapshot and a link on the order`(): Unit = runBlocking {
        val placed = place(ten, twenty)
        val invoice = issued(issue(placed))

        assertEquals(InvoiceType.INVOICE, invoice.type)
        assertEquals(placed.order.id, invoice.orderId)
        assertEquals(0L, invoice.refundId)
        assertEquals("INV", invoice.series)
        assertEquals(1L, invoice.sequence)
        assertEquals("INV-2025-000001", invoice.number)
        assertEquals("tr", invoice.locale)
        assertEquals("EUR", invoice.currency)
        assertEquals(3500L, invoice.total)
        assertEquals(500L, invoice.vatTotal)
        assertNull(invoice.fileName, "the PDF is rendered lazily")
        assertEquals(w.clock.now(), invoice.issuedAt)

        val snapshot = snapshotOf(invoice)

        assertEquals(1, snapshot.getInteger("v"))
        assertEquals(invoice.number, snapshot.getString("number"))
        assertEquals("INVOICE", snapshot.getString("type"))
        assertEquals("Acme Ltd", snapshot.getJsonObject("seller").getString("name"))
        assertEquals("Acme Craft", snapshot.getJsonObject("seller").getString("websiteName"))
        assertEquals("Steve", snapshot.getJsonObject("buyer").getString("username"))
        assertEquals(2, snapshot.getJsonArray("lines").size())
        assertEquals(3500L, snapshot.getJsonObject("totals").getLong("total"))
        assertEquals(placed.order.paidAt, snapshot.getJsonObject("order").getLong("paidAt"))

        assertEquals(invoice.id, w.orders.getById(placed.order.id, pool)!!.invoiceId)
        assertEquals(1L, counter("INV"))
        assertEquals(1L, count("market_invoice"))
        assertEquals(emptyList<String>(), notes(placed.order.id))
    }

    @Test
    fun `issuing twice for the same order is one invoice and the counter moves once`(): Unit = runBlocking {
        val placed = place(ten)
        val first = issued(issue(placed))
        val second = issue(placed)

        assertTrue(second is InvoiceOutcome.Existing, describe(second))
        assertEquals(first.id, (second as InvoiceOutcome.Existing).invoice.id)
        assertEquals(1L, count("market_invoice"))
        assertEquals(1L, counter("INV"))
    }

    @Test
    fun `numbers are consecutive in the order the invoices were issued and the year follows the clock`(): Unit = runBlocking {
        val numbers = mutableListOf<String>()

        repeat(3) { numbers += issued(issue(place(ten))).number }

        w.clock.advance(120L * 24 * 3600 * 1000) // 2026-02-06
        numbers += issued(issue(place(ten))).number

        assertEquals(listOf("INV-2025-000001", "INV-2025-000002", "INV-2025-000003", "INV-2026-000004"), numbers, "the counter never resets with the year")
        assertEquals(4L, counter("INV"))
    }

    @Test
    fun `the number year is the year in the store time zone`(): Unit = runBlocking {
        // 2025-12-31 23:30 UTC is already 2026 in Istanbul
        w.clock.set(1_767_223_800_000L)
        config = baseConfig(zone = "Europe/Istanbul")

        assertEquals("INV-2026-000001", issued(issue(place(ten))).number)

        config = baseConfig(zone = "UTC")

        assertEquals("INV-2025-000002", issued(issue(place(ten))).number)
    }

    @Test
    fun `the locale is the fixed invoice locale, else the order locale, else the platform default`(): Unit = runBlocking {
        config = baseConfig(locale = "ru")

        assertEquals("ru", issued(issue(place(ten, locale = "tr"))).locale, "the fixed locale wins")

        config = baseConfig(locale = "")

        assertEquals("tr", issued(issue(place(ten, locale = "tr"))).locale)

        platformLocale = "de-DE"

        assertEquals("de-DE", issued(issue(place(ten, locale = null))).locale)
        assertEquals("de-DE", issued(issue(place(ten, locale = "  "))).locale)
    }

    @Test
    fun `the series of the configuration is used and each series counts on its own`(): Unit = runBlocking {
        config = baseConfig(series = "SHOP", creditNoteSeries = "CRN")

        assertEquals("SHOP-2025-000001", issued(issue(place(ten))).number)

        config = baseConfig(series = "WEB")

        assertEquals("WEB-2025-000001", issued(issue(place(ten))).number)

        config = baseConfig(series = "SHOP")

        // changing back continues the counter of that series, old documents are untouched
        assertEquals("SHOP-2025-000002", issued(issue(place(ten))).number)
        assertEquals(listOf("SHOP-2025-000001", "WEB-2025-000001", "SHOP-2025-000002"), invoiceRows().map { it.number })
    }

    @Test
    fun `a test-mode order is numbered in the TEST series and leaves the real counter alone`(): Unit = runBlocking {
        val test = issued(issue(place(ten, testMode = true)))

        assertEquals("TEST", test.series)
        assertEquals("TEST-2025-000001", test.number)
        assertTrue(snapshotOf(test).getBoolean("testMode"))
        assertNull(counter("INV"), "the real series was not touched")
        assertEquals(1L, counter("TEST"))

        val real = issued(issue(place(ten)))

        assertEquals("INV-2025-000001", real.number)
        assertEquals("TEST-2025-000002", issued(issue(place(ten, testMode = true))).number)
    }

    @Test
    fun `a credit-only order is invoiced only with invoiceCreditOrders`(): Unit = runBlocking {
        val credits = place(ten, creditValue = 1100)

        assertEquals(InvoiceSkip.CREDITS_ONLY, (issue(credits) as InvoiceOutcome.Skipped).reason)
        assertEquals(0L, count("market_invoice"))
        assertNull(counter("INV"), "a skipped order takes no number")
        assertNull(w.orders.getById(credits.order.id, pool)!!.invoiceId)

        config = baseConfig(creditOrders = true)

        val invoice = issued(issue(credits))

        assertEquals("INV-2025-000001", invoice.number)
        assertEquals(1100L, snapshotOf(invoice).getJsonObject("totals").getLong("creditValue"))
        assertEquals(0L, snapshotOf(invoice).getJsonObject("totals").getLong("gatewayAmount"))
    }

    @Test
    fun `a mixed order is invoiced in full with its payment breakdown`(): Unit = runBlocking {
        val mixed = place(ten, twenty, creditValue = 500)
        val totals = snapshotOf(issued(issue(mixed))).getJsonObject("totals")

        assertEquals(3500L, totals.getLong("total"))
        assertEquals(3000L, totals.getLong("gatewayAmount"))
        assertEquals(500L, totals.getLong("creditValue"))
    }

    @Test
    fun `a switched-off store, a free order and an externally priced order get no invoice and take no number`(): Unit = runBlocking {
        val normal = place(ten)

        config = baseConfig(enabled = false)

        assertEquals(InvoiceSkip.DISABLED, (issue(normal) as InvoiceOutcome.Skipped).reason)

        config = baseConfig()

        assertEquals(InvoiceSkip.EXTERNAL_PRICING, (issue(place(ten, pricingMode = PricingMode.EXTERNAL)) as InvoiceOutcome.Skipped).reason)
        assertEquals(InvoiceSkip.EXTERNAL_PRICING, (issue(place(ten, pricingMode = PricingMode.EXTERNAL_TAX)) as InvoiceOutcome.Skipped).reason)
        assertEquals(InvoiceSkip.ZERO_TOTAL, (issue(place(L(0, 0, 2000, "Free"))) as InvoiceOutcome.Skipped).reason)

        assertEquals(0L, count("market_invoice"))
        assertNull(counter("INV"))
        assertEquals(0L, count("market_order_event", "`type` = 'NOTE'"), "a skip is not a failure: no timeline note")
    }

    @Test
    fun `an explicit admin issue ignores the switch and the credit-only rule but not an unpaid order`(): Unit = runBlocking {
        config = baseConfig(enabled = false)

        val paid = place(ten, creditValue = 1100)

        assertEquals("INV-2025-000001", issued(issue(paid, admin = true)).number)

        val unpaid = place(ten, status = OrderStatus.PENDING)

        assertEquals(InvoiceSkip.NOT_PAID, (issue(unpaid, admin = true) as InvoiceOutcome.Skipped).reason)
        assertEquals(1L, count("market_invoice"))
    }

    // ===== a document that cannot be issued never costs a number nor the order =============================================

    @Test
    fun `a snapshot that fails its checks leaves no row, no number and a timeline note, and the order still completes`(): Unit = runBlocking {
        // VAT larger than the line: the builder refuses (NEGATIVE_AMOUNT)
        val broken = place(L(1000, 1200, 2000, "Broken"))
        val outcome = w.db.tx { conn ->
            val result = service.issueForOrder(conn, freshOrder(conn, broken.order.id), freshItems(conn, broken.order.id))

            // the transaction goes on after the failed issue: the rest of the order flow still commits
            conn.preparedQuery("UPDATE `pano_market_order` SET `note` = ? WHERE `id` = ?").execute(Tuple.of("completed after the invoice failed", broken.order.id)).coAwait()

            result
        }

        assertEquals("NEGATIVE_AMOUNT", (outcome as InvoiceOutcome.NotIssued).reason)
        assertEquals(0L, count("market_invoice"))
        assertNull(counter("INV"), "the counter row was not even created")
        assertEquals(listOf("INVOICE_NOT_ISSUED:NEGATIVE_AMOUNT"), notes(broken.order.id))
        assertEquals("completed after the invoice failed", w.orders.getById(broken.order.id, pool)!!.note)
        assertEquals(OrderStatus.COMPLETED, w.orders.getById(broken.order.id, pool)!!.status)

        // the next order is numbered 1: the failure took nothing
        assertEquals("INV-2025-000001", issued(issue(place(ten))).number)
    }

    @Test
    fun `an unknown currency and a total that does not add up are refused with the same note`(): Unit = runBlocking {
        val odd = place(ten, currency = "XXX")

        assertEquals("UNKNOWN_CURRENCY", (issue(odd) as InvoiceOutcome.NotIssued).reason)
        assertEquals(listOf("INVOICE_NOT_ISSUED:UNKNOWN_CURRENCY"), notes(odd.order.id))

        val mismatch = place(ten)

        Fixtures.setColumns(pool, "market_order_item", mismatch.items[0].id, mapOf("lineTotal" to 1000))
        Fixtures.setColumns(pool, "market_order", mismatch.order.id, mapOf("totalPrice" to 1100, "subtotal" to 1100))

        // the order says 1100, the line says 1000: refused (the order is then invalid for I8, so repair it before the base checks run)
        assertEquals("TOTAL_MISMATCH", (issue(mismatch) as InvoiceOutcome.NotIssued).reason)
        Fixtures.setColumns(pool, "market_order_item", mismatch.items[0].id, mapOf("lineTotal" to 1100))

        assertEquals(0L, count("market_invoice"))
        assertNull(counter("INV"))
    }

    @Test
    fun `a failure after the number was taken rolls the number back and the next document reuses it`(): Unit = runBlocking {
        val first = place(ten)

        invoices.failNextAdd = IllegalStateException("disk full")

        val outcome = issue(first)

        assertEquals("ERROR", (outcome as InvoiceOutcome.NotIssued).reason)
        assertEquals(0L, count("market_invoice"))
        assertNull(counter("INV"), "ROLLBACK TO SAVEPOINT also undid the counter row it created")
        assertEquals(listOf("INVOICE_NOT_ISSUED:ERROR"), notes(first.order.id))
        assertNull(w.orders.getById(first.order.id, pool)!!.invoiceId)

        // retry of the same order (the panel regenerates): number 1, nothing was consumed
        val retry = issued(issue(first, admin = true))

        assertEquals("INV-2025-000001", retry.number)
        assertEquals(1L, counter("INV"))
    }

    @Test
    fun `a counter that points at a number already taken gives the number back and says so`(): Unit = runBlocking {
        // an invoice of sequence 6 exists while the counter stands at 5 (a restored table, a hand edit)
        val old = place(ten)

        sql("INSERT INTO `pano_market_sequence` (`name`, `value`, `createdAt`, `updatedAt`) VALUES ('invoice:INV', 5, 1, 1)")
        w.invoices.add(
            MarketInvoice(orderId = old.order.id, type = InvoiceType.INVOICE, series = "INV", sequence = 6, number = "INV-2025-000006", locale = "tr", currency = "EUR", total = 1100, snapshot = "{}", issuedAt = 1),
            pool
        )

        val next = place(ten)
        val outcome = issue(next)

        assertEquals("SEQUENCE_CONFLICT", (outcome as InvoiceOutcome.NotIssued).reason)
        assertEquals(5L, counter("INV"), "the counter is back where it was")
        assertEquals(1L, count("market_invoice"))
        assertEquals(listOf("INVOICE_NOT_ISSUED:SEQUENCE_CONFLICT"), notes(next.order.id))

        // the admin moves the counter past the taken number: the same order is then issued with the next free one
        assertEquals(SequenceChange.Changed, w.db.tx { conn -> service.setNextNumber(conn, "INV", 7) })
        assertEquals("INV-2025-000007", issued(issue(next, admin = true)).number)
    }

    @Test
    fun `a deadlock or lock wait error is not swallowed and the transaction runs again from the start`(): Unit = runBlocking {
        val placed = place(ten)

        invoices.failNextAdd = MySQLException("Deadlock found when trying to get lock", 1213, "40001")

        val outcome = issue(placed)

        // MarketDb.tx saw the 1213, rolled the first attempt back (counter included) and ran the block again
        assertEquals("INV-2025-000001", issued(outcome).number)
        assertEquals(1L, count("market_invoice"))
        assertEquals(1L, counter("INV"))
        assertEquals(emptyList<String>(), notes(placed.order.id), "a retried transaction leaves no failure note")
    }

    // ===== concurrency (R-19 twin) =========================================================================================

    @Test
    fun `R-19 twenty orders paid at the same moment get twenty contiguous numbers`(): Unit = runBlocking {
        val effects = InvoiceEffects(service, w.orders)
        val db = marketDb(lockWaitSeconds = 30)
        var expected = 0L

        repeat(Race.rounds) { round ->
            val outcomes = Race.runWithSetup(20, { place(ten) }) { placed ->
                // the way O2 runs it: the order is locked first, the counter row last
                db.tx { conn ->
                    locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                        effects.apply(conn, locked, OrderEffect.IssueInvoice)
                    }
                }
            }

            assertTrue(outcomes.all { it.isSuccess }, outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }.toString())

            expected += 20

            val sequences = sql("SELECT `sequence` FROM `pano_market_invoice` WHERE `series` = 'INV' ORDER BY `sequence`").map { it.getLong(0) }

            assertEquals((1L..expected).toList(), sequences, "round $round: contiguous, no duplicate, no gap")
            assertEquals(expected, counter("INV"))
        }

        assertEquals(expected, count("market_invoice"))
        // I15 in its own words: MAX - MIN + 1 = COUNT per series
        val check = sql("SELECT `series` FROM `pano_market_invoice` GROUP BY `series` HAVING MAX(`sequence`) - MIN(`sequence`) + 1 <> COUNT(*)")

        assertEquals(0, check.size)
        assertEquals(expected, sql("SELECT COUNT(DISTINCT `number`) FROM `pano_market_invoice`").single().getLong(0))
    }

    @Test
    fun `concurrent issues for one order under its lock are one invoice`(): Unit = runBlocking {
        val db = marketDb(lockWaitSeconds = 30)
        val placed = place(ten)
        val outcomes = Race.run(8) {
            db.tx { conn ->
                locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked -> service.issueForOrder(conn, locked.order, locked.items) }
            }
        }

        assertTrue(outcomes.all { it.isSuccess }, outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }.toString())
        assertEquals(1, outcomes.count { it.getOrThrow() is InvoiceOutcome.Issued })
        assertEquals(7, outcomes.count { it.getOrThrow() is InvoiceOutcome.Existing })
        assertEquals(1L, count("market_invoice"))
        assertEquals(1L, counter("INV"))
    }

    @Test
    fun `concurrent issues for one order without its lock still give one invoice and no consumed number`(): Unit = runBlocking {
        // the unique key is the last line of defence: the loser takes a number, loses the insert and rolls the number back
        val db = marketDb(lockWaitSeconds = 30)
        val placed = place(ten)
        val outcomes = Race.run(8) { db.tx { conn -> service.issueForOrder(conn, freshOrder(conn, placed.order.id), freshItems(conn, placed.order.id)) } }

        assertTrue(outcomes.all { it.isSuccess }, outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }.toString())
        assertEquals(1, outcomes.count { it.getOrThrow() is InvoiceOutcome.Issued }, outcomes.map { describe(it.getOrThrow()) }.toString())
        assertEquals(1L, count("market_invoice"))
        assertEquals(1L, counter("INV"), "every loser gave its number back")
        assertEquals(emptyList<String>(), notes(placed.order.id))
    }

    // ===== credit notes ====================================================================================================

    @Test
    fun `a credit note is issued per succeeded refund with its own series and line allocation`(): Unit = runBlocking {
        val placed = place(ten, twenty)
        val invoice = issued(issue(placed))
        val itemised = refund(placed, 1200, rows = listOf(placed.items[1] to (1 to 1200L)))
        val amountOnly = refund(placed, 100, reason = "goodwill")

        val first = issued(creditNote(placed, itemised))

        assertEquals(InvoiceType.CREDIT_NOTE, first.type)
        assertEquals(itemised, first.refundId)
        assertEquals("CN", first.series)
        assertEquals("CN-2025-000001", first.number)
        assertEquals(1200L, first.total)
        assertEquals(200L, first.vatTotal)

        val itemisedSnapshot = snapshotOf(first)

        assertEquals("CREDIT_NOTE", itemisedSnapshot.getString("type"))
        assertEquals(1, itemisedSnapshot.getJsonArray("lines").size())
        assertEquals("Twenty", itemisedSnapshot.getJsonArray("lines").getJsonObject(0).getString("name"))
        assertEquals(invoice.number, itemisedSnapshot.getJsonObject("ref").getString("number"))
        assertEquals(invoice.issuedAt, itemisedSnapshot.getJsonObject("ref").getLong("issuedAt"))
        assertEquals(itemised, itemisedSnapshot.getJsonObject("ref").getLong("refundId"))
        assertEquals("defective", itemisedSnapshot.getJsonObject("ref").getString("reason"))

        val second = issued(creditNote(placed, amountOnly))

        assertEquals("CN-2025-000002", second.number)
        assertEquals(100L, second.total)

        val lines = snapshotOf(second).getJsonArray("lines").map { it as JsonObject }

        assertEquals(listOf("REFUND", "REFUND"), lines.map { it.getString("kind") })
        assertEquals(listOf(1000L, 2000L), lines.map { it.getLong("vatPercent") })
        assertEquals(listOf(31L, 69L), lines.map { it.getLong("gross") }, "largest remainder over the invoice's VAT rows")
        assertEquals(100L, lines.sumOf { it.getLong("gross") })
        assertEquals("goodwill", snapshotOf(second).getJsonObject("ref").getString("reason"))

        // the invoice and the credit notes count separately; the order keeps pointing at its invoice
        assertEquals(1L, counter("INV"))
        assertEquals(2L, counter("CN"))
        assertEquals(invoice.id, w.orders.getById(placed.order.id, pool)!!.invoiceId)
        assertEquals(3L, count("market_invoice"))
    }

    @Test
    fun `a credit note of the same refund is issued once`(): Unit = runBlocking {
        val placed = place(ten)

        issued(issue(placed))

        val refundId = refund(placed, 300)
        val first = issued(creditNote(placed, refundId))
        val again = creditNote(placed, refundId)

        assertTrue(again is InvoiceOutcome.Existing, describe(again))
        assertEquals(first.id, (again as InvoiceOutcome.Existing).invoice.id)
        assertEquals(1L, counter("CN"))
        assertEquals(2L, count("market_invoice"))
    }

    @Test
    fun `a credit note is skipped without an invoice, for a refund that did not succeed, for no money and for another order's refund`(): Unit = runBlocking {
        val placed = place(ten)
        val refundId = refund(placed, 300)

        // no invoice yet
        assertEquals(InvoiceSkip.NO_INVOICE, (creditNote(placed, refundId) as InvoiceOutcome.Skipped).reason)

        issued(issue(placed))

        val pending = refund(placed, 200, status = RefundStatus.PENDING)
        val failed = refund(placed, 200, status = RefundStatus.FAILED)
        val zero = refund(placed, 0)
        val other = place(ten)

        issued(issue(other))

        val foreign = refund(other, 100)

        assertEquals(InvoiceSkip.REFUND_NOT_SUCCEEDED, (creditNote(placed, pending) as InvoiceOutcome.Skipped).reason)
        assertEquals(InvoiceSkip.REFUND_NOT_SUCCEEDED, (creditNote(placed, failed) as InvoiceOutcome.Skipped).reason)
        assertEquals(InvoiceSkip.ZERO_REFUND, (creditNote(placed, zero) as InvoiceOutcome.Skipped).reason)
        assertEquals(InvoiceSkip.REFUND_NOT_SUCCEEDED, (creditNote(placed, foreign) as InvoiceOutcome.Skipped).reason, "a refund of another order is no refund of this one")
        assertEquals(InvoiceSkip.REFUND_NOT_SUCCEEDED, (creditNote(placed, 99_999) as InvoiceOutcome.Skipped).reason)

        assertNull(counter("CN"), "none of them took a number")
        assertEquals(2L, count("market_invoice"))
    }

    @Test
    fun `a credit note is still issued when invoices were switched off after the invoice`(): Unit = runBlocking {
        val placed = place(ten)

        issued(issue(placed))

        config = baseConfig(enabled = false)

        assertEquals("CN-2025-000001", issued(creditNote(placed, refund(placed, 1100))).number)
    }

    @Test
    fun `the credit note of a test-mode order is numbered in TEST`(): Unit = runBlocking {
        val placed = place(ten, testMode = true)

        assertEquals("TEST-2025-000001", issued(issue(placed)).number)
        assertEquals("TEST-2025-000002", issued(creditNote(placed, refund(placed, 500))).number)
        assertNull(counter("CN"))
        assertNull(counter("INV"))
    }

    @Test
    fun `a refund that cannot be built into a credit note leaves a note and no number`(): Unit = runBlocking {
        val placed = place(ten)

        issued(issue(placed))

        // a refund row naming a line the order does not have
        val refundId = refund(placed, 300)

        sql("INSERT INTO `pano_market_refund_item` (`refundId`, `orderItemId`, `quantity`, `amount`, `createdAt`, `updatedAt`) VALUES (?, 99999, 1, 300, 1, 1)", refundId)

        assertEquals("UNKNOWN_ITEM", (creditNote(placed, refundId) as InvoiceOutcome.NotIssued).reason)
        assertEquals(listOf("CREDIT_NOTE_NOT_ISSUED:UNKNOWN_ITEM"), notes(placed.order.id))
        assertNull(counter("CN"))
        assertEquals(1L, count("market_invoice"))
    }

    @Test
    fun `the missing credit notes of an order are issued in refund order, once`(): Unit = runBlocking {
        val placed = place(ten, twenty)

        issued(issue(placed))

        val a = refund(placed, 100)
        refund(placed, 50, status = RefundStatus.FAILED)
        val b = refund(placed, 200)
        refund(placed, 0)
        val c = refund(placed, 300)

        // one of them already has its note
        issued(creditNote(placed, b))

        val outcomes = w.db.tx { conn -> service.issueMissingCreditNotes(conn, freshOrder(conn, placed.order.id), freshItems(conn, placed.order.id)) }

        assertEquals(listOf(a, c), outcomes.map { (it as InvoiceOutcome.Issued).invoice.refundId })
        assertEquals(listOf("CN-2025-000001", "CN-2025-000002", "CN-2025-000003"), invoiceRows().filter { it.type == InvoiceType.CREDIT_NOTE }.map { it.number }.sorted(), "b came first, then a and c")

        val second = w.db.tx { conn -> service.issueMissingCreditNotes(conn, freshOrder(conn, placed.order.id), freshItems(conn, placed.order.id)) }

        assertEquals(emptyList<InvoiceOutcome>(), second)
        assertEquals(4L, count("market_invoice"))
    }

    @Test
    fun `an order without an invoice has no missing credit notes`(): Unit = runBlocking {
        val placed = place(ten)

        refund(placed, 100)

        assertEquals(emptyList<InvoiceOutcome>(), w.db.tx { conn -> service.issueMissingCreditNotes(conn, freshOrder(conn, placed.order.id), freshItems(conn, placed.order.id)) })
        assertEquals(0L, count("market_invoice"))
    }

    // ===== the counter (T-DB-6) ============================================================================================

    @Test
    fun `the sequence only moves upwards and the next issued number honours it`(): Unit = runBlocking {
        suspend fun set(series: String, next: Long) = w.db.tx { conn -> service.setNextNumber(conn, series, next) }

        // forward from nothing: the first invoice of the series carries the chosen number
        assertEquals(SequenceChange.Changed, set("INV", 100))
        assertEquals(99L, counter("INV"))
        assertEquals("INV-2025-000100", issued(issue(place(ten))).number)

        // backwards is refused with the smallest acceptable value, and nothing moves
        val low = set("INV", 100) as SequenceChange.TooLow

        assertEquals(101L, low.minimum)
        assertEquals(101L, (set("INV", 50) as SequenceChange.TooLow).minimum)
        assertEquals(101L, (set("INV", 0) as SequenceChange.TooLow).minimum)
        assertEquals(101L, (set("INV", -5) as SequenceChange.TooLow).minimum)
        assertEquals(100L, counter("INV"))

        // the next free number is allowed (nothing changes), and the numbers go on from there
        assertEquals(SequenceChange.Changed, set("INV", 101))
        assertEquals(100L, counter("INV"))
        assertEquals("INV-2025-000101", issued(issue(place(ten))).number)

        // a series that was never used starts at 1 at the lowest
        assertEquals(1L, (set("NEW", 0) as SequenceChange.TooLow).minimum)
        assertEquals(SequenceChange.Changed, set("NEW", 1))
        assertEquals(0L, counter("NEW"))
    }

    @Test
    fun `jumping ahead in a series that has invoices leaves a deliberate gap and is honoured`(): Unit = runBlocking {
        suspend fun set(series: String, next: Long) = w.db.tx { conn -> service.setNextNumber(conn, series, next) }

        assertEquals("INV-2025-000001", issued(issue(place(ten))).number)
        assertEquals(SequenceChange.Changed, set("INV", 5000))
        assertEquals(4999L, counter("INV"))
        assertEquals("INV-2025-005000", issued(issue(place(ten))).number)
        assertEquals("INV-2025-005001", issued(issue(place(ten))).number)

        // I15 (contiguous per series) cannot hold across an administrator's jump by design (12 section 6.2): the rows go
        // before the base class checks the invariants, the jump itself is what this test proves
        sql("DELETE FROM `pano_market_invoice`")
        sql("UPDATE `pano_market_order` SET `invoiceId` = NULL")
    }

    @Test
    fun `a bad series or an enormous number is refused before anything is written`(): Unit = runBlocking {
        suspend fun set(series: String, next: Long) = w.db.tx { conn -> service.setNextNumber(conn, series, next) }

        for (bad in listOf("", "inv", "TOO-LONG", "TOOLONGSERIES", "A B", "İNV")) {
            assertSame(SequenceChange.InvalidSeries, set(bad, 10), "series '$bad'")
        }

        assertTrue(set("INV", Long.MAX_VALUE) is SequenceChange.TooHigh)
        assertTrue(set("INV", 1_000_000_000_000_000L + 1) is SequenceChange.TooHigh)
        assertEquals(0L, count("market_sequence", "`name` LIKE 'invoice:%'"))
        assertEquals(SequenceChange.Changed, set("INV", 1_000_000_000_000_000L), "the largest value that still fits the number column")
    }

    @Test
    fun `a rolled back change of the start value leaves the counter alone`(): Unit = runBlocking {
        assertEquals(SequenceChange.Changed, w.db.tx { conn -> service.setNextNumber(conn, "INV", 10) })

        runCatching {
            w.db.tx { conn ->
                assertEquals(SequenceChange.Changed, service.setNextNumber(conn, "INV", 500))

                error("the settings request failed afterwards")
            }
        }

        assertEquals(9L, counter("INV"))
    }

    @Test
    fun `the settings page sees every counter and the configured series`(): Unit = runBlocking {
        assertEquals(listOf("CN" to 0L, "INV" to 0L), service.sequences(pool).map { it.series to it.lastNumber }, "the two configured series show up before any document")

        issued(issue(place(ten)))
        issued(issue(place(ten)))
        issued(issue(place(ten, testMode = true)))

        val sequences = service.sequences(pool)

        assertEquals(listOf("CN" to 0L, "INV" to 2L, "TEST" to 1L), sequences.map { it.series to it.lastNumber })
        assertEquals(listOf(1L, 3L, 2L), sequences.map { it.nextNumber })

        // a fixup marker is not a series
        sql("INSERT INTO `pano_market_sequence` (`name`, `value`, `createdAt`, `updatedAt`) VALUES ('fixup:test-marker', 1, 1, 1)")

        assertEquals(3, service.sequences(pool).size)
    }

    // ===== the ForeignEffects adapter ======================================================================================

    private class RecordingEffects : ForeignEffects {
        val seen = mutableListOf<OrderEffect>()

        override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
            seen += effect
        }
    }

    @Test
    fun `the IssueInvoice effect issues the invoice from the order as it is in the database and every other effect goes on`(): Unit = runBlocking {
        val next = RecordingEffects()
        val effects = InvoiceEffects(service, w.orders, next)
        val placed = place(ten)

        // the LockedOrder was read before StampPaid: it still shows the order unpaid
        val stale = LockedOrder(placed.order.let { MarketOrder(id = it.id, playerUsername = it.playerUsername, totalPrice = it.totalPrice, status = OrderStatus.PENDING, paidAt = null) }, placed.items, emptyList(), OrderLockScope.COMMIT)

        w.db.tx { conn ->
            effects.apply(conn, stale, OrderEffect.QueueMail("ORDER_CONFIRMATION"))
            effects.apply(conn, stale, OrderEffect.IssueInvoice)
            effects.apply(conn, stale, OrderEffect.GrantEntitlements)
        }

        assertEquals(listOf<OrderEffect>(OrderEffect.QueueMail("ORDER_CONFIRMATION"), OrderEffect.GrantEntitlements), next.seen, "the invoice effect is not passed on")

        val invoice = invoiceRows().single()

        assertEquals("INV-2025-000001", invoice.number)
        assertEquals(placed.order.paidAt, snapshotOf(invoice).getJsonObject("order").getLong("paidAt"), "built from the fresh order, not the stale object")
        assertEquals(1100L, invoice.total)
    }

    @Test
    fun `the IssueInvoice effect never fails the order, whatever happens to the document`(): Unit = runBlocking {
        val effects = InvoiceEffects(service, w.orders)
        val broken = place(L(1000, 1200, 2000, "Broken"))
        val locked = LockedOrder(broken.order, broken.items, emptyList(), OrderLockScope.COMMIT)

        // a failed document, then a skipped one, in one transaction that still commits
        w.db.tx { conn -> effects.apply(conn, locked, OrderEffect.IssueInvoice) }

        config = baseConfig(enabled = false)

        w.db.tx { conn -> effects.apply(conn, locked, OrderEffect.IssueInvoice) }

        assertEquals(0L, count("market_invoice"))
        assertEquals(listOf("INVOICE_NOT_ISSUED:NEGATIVE_AMOUNT"), notes(broken.order.id))
    }

    @Test
    fun `an order that is not in the database fails the effect instead of inventing an invoice`(): Unit = runBlocking {
        val effects = InvoiceEffects(service, w.orders)
        val ghost = LockedOrder(MarketOrder(id = 987_654), emptyList(), emptyList(), OrderLockScope.COMMIT)
        val failure = runCatching { w.db.tx { conn -> effects.apply(conn, ghost, OrderEffect.IssueInvoice) } }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, failure.toString())
        assertEquals(0L, count("market_invoice"))
    }

    // ===== a document is a record =========================================================================================

    @Test
    fun `an invoice row is never changed by issuing again`(): Unit = runBlocking {
        val placed = place(ten, twenty)
        val first = issued(issue(placed))

        w.clock.advance(3_600_000)

        val again = (issue(placed) as InvoiceOutcome.Existing).invoice

        assertEquals(first.number, again.number)
        assertEquals(first.issuedAt, again.issuedAt, "issuedAt is the payment time and does not move")
        assertNotEquals(w.clock.now(), again.issuedAt)
        assertEquals(first.snapshot, again.snapshot)
        assertEquals(1L, count("market_invoice"))
    }
}
