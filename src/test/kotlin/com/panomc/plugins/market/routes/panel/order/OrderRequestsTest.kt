package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.error.NoPermission
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.service.OrderExportColumns
import com.panomc.plugins.market.util.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The query of `GET /orders` and `GET /orders/export` (04 section 7, 11 section 14.5; MK-170): what is read and what is refused. */
class OrderRequestsTest {
    private fun filter(
        status: String? = null, method: String? = null, fulfillment: String? = null, shipping: String? = null, from: String? = null, to: String? = null,
        testMode: String? = null, source: String? = null, search: String? = null
    ) = parseOrderFilter(status, method, fulfillment, shipping, from, to, testMode, source, search)

    private fun refused(field: String, block: () -> Unit) {
        assertEquals(field, assertThrows(RequestValueException::class.java) { block() }.field)
    }

    @Test
    fun `an empty query filters nothing`() {
        val f = filter()

        assertTrue(f.statuses.isEmpty() && f.fulfillmentStatuses.isEmpty() && f.shippingStatuses.isEmpty() && f.sources.isEmpty())
        assertNull(f.paymentMethodId)
        assertNull(f.from)
        assertNull(f.to)
        assertNull(f.testMode)
        assertNull(f.search)
        assertEquals("{}", f.describe().encode())
    }

    @Test
    fun `csv filters are split, trimmed and de-duplicated`() {
        val f = filter(
            status = "COMPLETED, PARTIALLY_REFUNDED,COMPLETED", fulfillment = "FAILED,PARTIAL", shipping = "PENDING", source = "STOREFRONT,PANEL"
        )

        assertEquals(setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED), f.statuses)
        assertEquals(setOf(FulfillmentStatus.FAILED, FulfillmentStatus.PARTIAL), f.fulfillmentStatuses)
        assertEquals(setOf(ShippingStatus.PENDING), f.shippingStatuses)
        assertEquals(setOf(OrderSource.STOREFRONT, OrderSource.PANEL), f.sources)
    }

    @Test
    fun `an unknown enum value is a 400 naming the field, never a silent default`() {
        refused("status") { filter(status = "COMPLETED,DONE") }
        refused("fulfillmentStatus") { filter(fulfillment = "lost") }
        refused("shippingStatus") { filter(shipping = "SENT") }
        refused("source") { filter(source = "WEB") }
    }

    @Test
    fun `testMode takes true, false and all`() {
        assertEquals(true, filter(testMode = "true").testMode)
        assertEquals(false, filter(testMode = "false").testMode)
        assertNull(filter(testMode = "all").testMode)
        assertNull(filter(testMode = "").testMode)
        refused("testMode") { filter(testMode = "maybe") }
    }

    @Test
    fun `from and to are epoch milliseconds, to cannot precede from`() {
        val f = filter(from = "1000", to = "2000")

        assertEquals(1000L, f.from)
        assertEquals(2000L, f.to)
        assertEquals(1000L, filter(from = "1000", to = "1000").from, "an equal range is a single instant, not an error")
        refused("to") { filter(from = "2000", to = "1000") }
        refused("from") { filter(from = "yesterday") }
        refused("to") { filter(to = "-5") }
        refused("from") { filter(from = "1.5") }
    }

    @Test
    fun `the payment method and the search are bounded text`() {
        assertEquals("bank-transfer", filter(method = " bank-transfer ").paymentMethodId)
        assertEquals("Steve", filter(search = " Steve ").search)
        assertNull(filter(search = "   ").search)
        refused("search") { filter(search = "x".repeat(SEARCH_MAX + 1)) }
        refused("paymentMethodId") { filter(method = "x".repeat(65)) }
    }

    @Test
    fun `the filter that goes into the activity log names the keys and never the search text`() {
        val d = filter(status = "COMPLETED", search = "john@example.com", testMode = "false", from = "5").describe()

        assertEquals("COMPLETED", d.getString("status"))
        assertEquals(true, d.getBoolean("search"))
        assertEquals(false, d.getBoolean("testMode"))
        assertEquals(5L, d.getLong("from"))
        assertFalse(d.encode().contains("john@example.com"))
    }

    // ---- export columns

    @Test
    fun `an export without columns takes every column the caller may select`() {
        val all = OrderExportColumns.parse(null, pii = true)
        val plain = OrderExportColumns.parse("", pii = false)

        assertEquals(OrderExportColumns.ALL, all)
        assertEquals(28, all.size)
        assertEquals(OrderExportColumns.ALL - setOf("email", "country"), plain)
    }

    @Test
    fun `the column keys are the ones of 04 section 7, in that order`() {
        assertEquals(
            "orderId,publicId,createdAt,paidAt,status,source,playerUsername,recipientUsername,email,productName,variantName,sku,quantity,unitPrice,lineTotal,currency," +
                "orderTotal,couponCode,creatorCode,paymentMethod,gatewayTransactionId,gatewayAmount,creditValue,refundedTotal,fulfillmentStatus,shippingStatus,country,testMode",
            OrderExportColumns.ALL.joinToString(",")
        )
    }

    @Test
    fun `columns are trimmed, de-duplicated and keep the order asked for`() {
        assertEquals(listOf("lineTotal", "orderId", "status"), OrderExportColumns.parse(" lineTotal, orderId ,lineTotal,status", pii = false))
    }

    @Test
    fun `an unknown column is a 400`() {
        refused("columns") { OrderExportColumns.parse("orderId,password", pii = true) }
        refused("columns") { OrderExportColumns.parse("orderid", pii = true) }
    }

    @Test
    fun `a PII column below the PII tier is 403, with the tier it is allowed`() {
        assertThrows(NoPermission::class.java) { OrderExportColumns.parse("orderId,email", pii = false) }
        assertThrows(NoPermission::class.java) { OrderExportColumns.parse("country", pii = false) }
        assertEquals(listOf("orderId", "email", "country"), OrderExportColumns.parse("orderId,email,country", pii = true))
    }

    @Test
    fun `an unknown column is refused before the permission check answers`() {
        // a typo is a 400 for everybody; the 403 is only for a real PII column
        refused("columns") { OrderExportColumns.parse("emial", pii = false) }
    }

    @Test
    fun `the filter keys of the list and of the export are the same`() {
        assertEquals(listOf("status", "paymentMethodId", "fulfillmentStatus", "shippingStatus", "from", "to", "testMode", "source", "search"), ORDER_FILTER_QUERY)
    }
}
