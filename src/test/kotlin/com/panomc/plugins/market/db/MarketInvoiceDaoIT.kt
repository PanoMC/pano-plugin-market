package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.impl.MarketInvoiceDaoImpl
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MarketInvoice
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_invoice` (01 section 6.7). */
class MarketInvoiceDaoIT : MarketDaoITBase() {
    private val dao = MarketInvoiceDaoImpl()

    private fun invoice(order: Long = 1, type: InvoiceType = InvoiceType.CREDIT_NOTE, refund: Long = 5, series: String = "INV", sequence: Long = 1) =
        MarketInvoice(
            orderId = order, type = type, refundId = refund, series = series, sequence = sequence,
            number = "$series-2026-" + sequence.toString().padStart(6, '0'), locale = "tr", currency = "TRY", total = 12_345,
            vatTotal = 2_057, snapshot = "{\"lines\":[]}", fileName = "2026/$series.pdf", issuedAt = 99, createdAt = 10, updatedAt = 20
        )

    @Test
    fun `an invoice round-trips every column`(): Unit = runBlocking {
        val written = invoice()
        EntityRoundTrip.differsFromDefaults(written, MarketInvoice())
        val id = dao.add(written, pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `a series number is issued once`(): Unit = runBlocking {
        assertNotNull(dao.add(invoice(order = 1, type = InvoiceType.INVOICE, refund = 0), pool))
        assertNull(dao.add(invoice(order = 2, type = InvoiceType.INVOICE, refund = 0), pool))
        val raw = runCatching {
            sql("INSERT INTO `pano_market_invoice` (`orderId`, `type`, `series`, `sequence`, `number`, `locale`, `currency`, `total`, `vatTotal`, `snapshot`, `issuedAt`, `createdAt`, `updatedAt`) VALUES (3, 'INVOICE', 'INV', 1, 'x', 'en', 'EUR', 1, 0, '{}', 1, 1, 1)")
        }.exceptionOrNull()
        assertTrue(raw != null && raw.isDuplicateKey())
        // the same sequence in another series is another number
        assertNotNull(dao.add(invoice(order = 2, type = InvoiceType.INVOICE, refund = 0, series = "TEST"), pool))
        assertEquals(2L, count("market_invoice"))
    }

    @Test
    fun `an order has one invoice and one credit note per refund`(): Unit = runBlocking {
        assertNotNull(dao.add(invoice(type = InvoiceType.INVOICE, refund = 0, sequence = 1), pool))
        assertNull(dao.add(invoice(type = InvoiceType.INVOICE, refund = 0, sequence = 2), pool))
        assertNotNull(dao.add(invoice(refund = 5, sequence = 3), pool))
        assertNull(dao.add(invoice(refund = 5, sequence = 4), pool))
        assertNotNull(dao.add(invoice(refund = 6, sequence = 5), pool))
        assertEquals(3, dao.getByOrderId(1, pool).size)
        assertEquals(3L, dao.getByOrderTypeRefund(1, InvoiceType.CREDIT_NOTE, 5, pool)!!.sequence)
        assertEquals(1L, dao.getByOrderTypeRefund(1, InvoiceType.INVOICE, 0, pool)!!.sequence)
        assertNull(dao.getByOrderTypeRefund(1, InvoiceType.CREDIT_NOTE, 77, pool))
        assertEquals(emptyList<MarketInvoice>(), dao.getByOrderId(2, pool))
    }
}
