package com.panomc.plugins.market.core.invoice

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.util.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The issue conditions of 12 section 6.1 as a truth table (T-INV-5 of 12 section 12). */
class InvoiceIssueRulesTest {
    private fun order(
        total: Long = 1200,
        gateway: Long = 1200,
        mode: PricingMode = PricingMode.MARKET,
        status: OrderStatus = OrderStatus.COMPLETED
    ) = MarketOrder(id = 1, totalPrice = total, gatewayAmount = gateway, pricingMode = mode, status = status)

    private fun config(enabled: Boolean = true, creditOrders: Boolean = false) = MarketConfig(invoiceEnabled = enabled, invoiceCreditOrders = creditOrders)

    @Test
    fun `a paid market-priced order with a gateway part is invoiced`() {
        assertNull(InvoiceIssueRules.invoiceBlock(config(), order()))
        // the automatic call may see the order object before the status moved: the status is not part of the automatic rule
        assertNull(InvoiceIssueRules.invoiceBlock(config(), order(status = OrderStatus.PENDING)))
    }

    @Test
    fun `condition 1, the invoice switch`() {
        assertEquals(InvoiceSkip.DISABLED, InvoiceIssueRules.invoiceBlock(config(enabled = false), order()))
    }

    @Test
    fun `condition 2, only a market-priced order is invoiced`() {
        assertEquals(InvoiceSkip.EXTERNAL_PRICING, InvoiceIssueRules.invoiceBlock(config(), order(mode = PricingMode.EXTERNAL)))
        assertEquals(InvoiceSkip.EXTERNAL_PRICING, InvoiceIssueRules.invoiceBlock(config(), order(mode = PricingMode.EXTERNAL_TAX)))
    }

    @Test
    fun `condition 3, a free order has no invoice`() {
        assertEquals(InvoiceSkip.ZERO_TOTAL, InvoiceIssueRules.invoiceBlock(config(), order(total = 0, gateway = 0)))
        assertEquals(InvoiceSkip.ZERO_TOTAL, InvoiceIssueRules.invoiceBlock(config(creditOrders = true), order(total = 0, gateway = 0)))
    }

    @Test
    fun `condition 4, a credit-only order is invoiced only with invoiceCreditOrders`() {
        assertEquals(InvoiceSkip.CREDITS_ONLY, InvoiceIssueRules.invoiceBlock(config(creditOrders = false), order(gateway = 0)))
        assertNull(InvoiceIssueRules.invoiceBlock(config(creditOrders = true), order(gateway = 0)))
        // a mixed order has a gateway part: invoiced in full either way
        assertNull(InvoiceIssueRules.invoiceBlock(config(creditOrders = false), order(total = 1200, gateway = 700)))
    }

    @Test
    fun `the conditions are checked in the order of the design`() {
        // disabled wins over everything else
        assertEquals(InvoiceSkip.DISABLED, InvoiceIssueRules.invoiceBlock(config(enabled = false), order(mode = PricingMode.EXTERNAL, total = 0, gateway = 0)))
        // pricing mode wins over the amounts
        assertEquals(InvoiceSkip.EXTERNAL_PRICING, InvoiceIssueRules.invoiceBlock(config(), order(mode = PricingMode.EXTERNAL, total = 0, gateway = 0)))
        // a zero total wins over the credit-only rule
        assertEquals(InvoiceSkip.ZERO_TOTAL, InvoiceIssueRules.invoiceBlock(config(), order(total = 0, gateway = 0)))
    }

    @Test
    fun `an explicit admin issue ignores conditions 1 and 4 and needs a paid status`() {
        assertNull(InvoiceIssueRules.invoiceBlock(config(enabled = false), order(), admin = true))
        assertNull(InvoiceIssueRules.invoiceBlock(config(creditOrders = false), order(gateway = 0), admin = true))

        for (status in listOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)) {
            assertNull(InvoiceIssueRules.invoiceBlock(config(), order(status = status), admin = true), status.name)
        }

        for (status in listOf(OrderStatus.PENDING, OrderStatus.REVIEW, OrderStatus.CHARGEBACK, OrderStatus.FAILED, OrderStatus.CANCELLED, OrderStatus.EXPIRED)) {
            assertEquals(InvoiceSkip.NOT_PAID, InvoiceIssueRules.invoiceBlock(config(), order(status = status), admin = true), status.name)
        }

        // conditions 2 and 3 stay
        assertEquals(InvoiceSkip.EXTERNAL_PRICING, InvoiceIssueRules.invoiceBlock(config(), order(mode = PricingMode.EXTERNAL), admin = true))
        assertEquals(InvoiceSkip.ZERO_TOTAL, InvoiceIssueRules.invoiceBlock(config(), order(total = 0, gateway = 0), admin = true))
    }

    @Test
    fun `a credit note needs an invoice, a refund that moved money and a succeeded refund`() {
        assertNull(InvoiceIssueRules.creditNoteBlock(hasInvoice = true, refundAmount = 500, refundSucceeded = true))
        assertEquals(InvoiceSkip.NO_INVOICE, InvoiceIssueRules.creditNoteBlock(hasInvoice = false, refundAmount = 500, refundSucceeded = true))
        assertEquals(InvoiceSkip.ZERO_REFUND, InvoiceIssueRules.creditNoteBlock(hasInvoice = true, refundAmount = 0, refundSucceeded = true))
        assertEquals(InvoiceSkip.REFUND_NOT_SUCCEEDED, InvoiceIssueRules.creditNoteBlock(hasInvoice = true, refundAmount = 500, refundSucceeded = false))
        assertEquals(InvoiceSkip.NO_INVOICE, InvoiceIssueRules.creditNoteBlock(hasInvoice = false, refundAmount = 0, refundSucceeded = false))
    }
}
