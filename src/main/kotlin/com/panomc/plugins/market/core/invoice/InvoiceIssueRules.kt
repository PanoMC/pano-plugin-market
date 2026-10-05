package com.panomc.plugins.market.core.invoice

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.util.OrderStatus

/** Why an invoice is not issued although nothing went wrong (12 section 6.1): nothing is written for these. */
enum class InvoiceSkip {
    /** `MarketConfig.invoiceEnabled = false` (condition 1). */
    DISABLED,

    /** `pricingMode` is `EXTERNAL` / `EXTERNAL_TAX`: the gateway is the seller of record (condition 2). */
    EXTERNAL_PRICING,

    /** `totalPrice = 0` (condition 3). */
    ZERO_TOTAL,

    /** Paid with credits only and `invoiceCreditOrders = false`: invoiced when the credits were bought (condition 4). */
    CREDITS_ONLY,

    /** An explicit admin issue for an order that is not paid (12 section 8.4 b). */
    NOT_PAID,

    /** A credit note needs an invoice of the order. */
    NO_INVOICE,

    /** A credit note for a refund of nothing (`amount = 0`). */
    ZERO_REFUND,

    /** A credit note is issued once its refund is `SUCCEEDED`. */
    REFUND_NOT_SUCCEEDED
}

/**
 * The truth table of 12 section 6.1, as a pure function. The fifth condition (no `(orderId, INVOICE, 0)` row yet) needs the
 * database and is checked by the service.
 */
object InvoiceIssueRules {
    /** The statuses of an order that was paid (an explicit issue is allowed for these only). */
    val PAID_STATUSES: Set<OrderStatus> = setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)

    /**
     * `null` when an `INVOICE` may be issued for [order], else the first condition that fails. [admin] is the explicit panel
     * action (regenerate): conditions 1 and 4 are ignored and the order must be in a paid status (the automatic call runs in
     * the O2 / O4 transaction, where the order object may still carry the status it had before the transition).
     */
    fun invoiceBlock(config: MarketConfig, order: MarketOrder, admin: Boolean = false): InvoiceSkip? {
        if (admin) {
            if (order.status !in PAID_STATUSES) return InvoiceSkip.NOT_PAID
        } else if (!config.invoiceEnabled) {
            return InvoiceSkip.DISABLED
        }

        if (order.pricingMode != PricingMode.MARKET) return InvoiceSkip.EXTERNAL_PRICING
        if (order.totalPrice <= 0) return InvoiceSkip.ZERO_TOTAL
        if (!admin && order.gatewayAmount <= 0 && !config.invoiceCreditOrders) return InvoiceSkip.CREDITS_ONLY

        return null
    }

    /**
     * `null` when a `CREDIT_NOTE` may be issued: the order has an invoice and the refund moved money. `invoiceEnabled` does not
     * matter (an invoice that was issued is always corrected by a credit note), chargebacks never get here.
     */
    fun creditNoteBlock(hasInvoice: Boolean, refundAmount: Long, refundSucceeded: Boolean): InvoiceSkip? = when {
        !hasInvoice -> InvoiceSkip.NO_INVOICE
        refundAmount <= 0 -> InvoiceSkip.ZERO_REFUND
        !refundSucceeded -> InvoiceSkip.REFUND_NOT_SUCCEEDED
        else -> null
    }
}
