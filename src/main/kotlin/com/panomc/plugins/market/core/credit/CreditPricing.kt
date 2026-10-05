package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.db.model.OrderItemKind
import java.math.BigDecimal

/**
 * The full-credit total of an order (07 sections 4 and 6.1; the cases of 07 section 19.2): what a buyer pays when the whole
 * order is paid in credits, valued from the figures of the **money run**.
 *
 * For an order the pricing engine's credit run (05 section 8.1) is the only authority: it prices stage A in credits directly,
 * so a percentage discount, a fixed amount and a coupon are applied in credits. This object is the closed-form companion the
 * credits spec describes (a line costs its credit price reduced by the **same ratio** as its money price), used where only
 * the money figures of an order are at hand (a stored order, a preview, an invoice line) and as the contract of the
 * `creditPrice` rules (inheritance, "not sold for credits", bundles). For an order without any reduction the two are equal;
 * with a reduction they can differ by the engine's per-unit rounding, which is why the engine's `CreditRun` is what gets
 * written to `market_order.creditAmount`.
 *
 * ```
 * reduction  = min(discountAmount + upgradeAmount + couponAmount, listTotal)
 * lineTotal  = creditUnitPrice x quantity                                   listTotal == 0 or no reduction
 *            = halfUp(creditUnitPrice x quantity x (listTotal - reduction) / listTotal)     otherwise, one rounding
 * total      = sum(lineTotal) + shippingCredits          (no VAT, no fee; the total is the sum of the rounded lines)
 * ```
 */
object CreditPricing {
    /**
     * The credit unit price of a line (07 section 4): the variant's own price, or the product's when the variant has none
     * (`NULL`). `0` is a price: this variant (or product) is not sold for credits.
     */
    fun effectiveCreditPrice(productCreditPrice: Long, variantCreditPrice: Long?): Long {
        require(productCreditPrice >= 0) { "a credit price is never negative: $productCreditPrice" }
        require(variantCreditPrice == null || variantCreditPrice >= 0) { "a credit price is never negative: $variantCreditPrice" }
        return variantCreditPrice ?: productCreditPrice
    }

    /**
     * A line can be bought with credits when it has a credit price, or when it is free anyway (a money price of 0 costs 0
     * credits, 05 section 8.1). A priced line with credit price 0 is "not sold for credits".
     */
    fun sellableForCredits(creditUnitPrice: Long, listUnitPrice: Long): Boolean = creditUnitPrice > 0L || listUnitPrice == 0L

    /**
     * One priced line of the money run. [kind] is the order item kind: `BUNDLE_CHILD` lines cost nothing (the bundle's own
     * credit price is counted once on the `BUNDLE` line), `CREDIT_TOPUP` is not payable with credits.
     * [listTotal] is the money list total of the line (`listUnitPrice x quantity`); the three amounts are the money run's
     * reductions of the line (order currency, price basis).
     */
    class Line(
        val kind: OrderItemKind,
        val creditUnitPrice: Long,
        val quantity: Int,
        val listTotal: Long,
        val discountAmount: Long = 0,
        val upgradeAmount: Long = 0,
        val couponAmount: Long = 0
    ) {
        init {
            require(quantity >= 1) { "quantity must be at least 1: $quantity" }
            require(creditUnitPrice >= 0 && listTotal >= 0 && discountAmount >= 0 && upgradeAmount >= 0 && couponAmount >= 0) {
                "credit and money figures are never negative"
            }
        }
    }

    /** What the buyer pays for [line] in credits (credits x 100). */
    fun lineCredits(line: Line): Long {
        when (line.kind) {
            OrderItemKind.BUNDLE_CHILD -> return 0L
            OrderItemKind.CREDIT_TOPUP -> throw IllegalArgumentException("a credit top-up is not payable with credits")
            OrderItemKind.PRODUCT, OrderItemKind.BUNDLE -> Unit
        }
        val gross = Math.multiplyExact(line.creditUnitPrice, line.quantity.toLong())
        if (line.listTotal == 0L) return gross // a money price of 0 has no ratio: the line costs its credit price
        val reduction = minOf(
            Math.addExact(Math.addExact(line.discountAmount, line.upgradeAmount), line.couponAmount),
            line.listTotal
        )
        if (reduction == 0L) return gross
        val net = Math.subtractExact(line.listTotal, reduction)
        return Rounding.ratioQ(
            BigDecimal.valueOf(gross).multiply(BigDecimal.valueOf(net)),
            BigDecimal.valueOf(line.listTotal),
            1L
        )
    }

    /** The sum of the rounded lines. */
    fun itemsCredits(lines: List<Line>): Long = lines.fold(0L) { total, line -> Math.addExact(total, lineCredits(line)) }

    /**
     * Shipping of a full-credit order: the shipping value at the credit rate, rounded **up** to 0.01 credit (07 section 18
     * default 11). [rateMinor] and [fxRate] as in [CreditMath]; 0 for no shipping.
     */
    fun shippingCredits(shippingValue: Long, rateMinor: Long, fxRate: BigDecimal = BigDecimal.ONE): Long =
        CreditMath.shippingCredits(shippingValue, rateMinor, fxRate)

    /**
     * The same figure as the engine's `finalize`: the order-currency shipping is taken to the base currency first
     * (`fromOrder`, rounded to the base quantum) and then converted at the credit value, rounded up.
     */
    fun shippingCredits(shippingTotal: Long, conversions: Conversions): Long {
        if (shippingTotal == 0L || conversions.creditValue <= 0L) return 0L
        return CreditMath.shippingCredits(conversions.fromOrder(shippingTotal), conversions.creditValue, BigDecimal.ONE)
    }

    /** The credit total of an order: the lines plus the shipping. */
    data class Total(val itemsCredits: Long, val shippingCredits: Long) {
        val total: Long get() = Math.addExact(itemsCredits, shippingCredits)
    }

    fun total(lines: List<Line>, shippingCredits: Long): Total {
        require(shippingCredits >= 0) { "shipping credits are never negative: $shippingCredits" }
        return Total(itemsCredits(lines), shippingCredits)
    }
}
