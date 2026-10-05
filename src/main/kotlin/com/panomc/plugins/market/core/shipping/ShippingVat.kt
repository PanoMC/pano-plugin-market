package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.core.money.Rounding

/** A shipping amount split into what the buyer pays and the VAT inside it (both `Long` x 100, order currency). */
class VatSplit(val gross: Long, val vat: Long)

/** Shipping VAT (10 section 5.3 step 5). Parts are split at quantum 1, [ShippingPriceCalculator] fixes the quantum last. */
object ShippingVat {
    /**
     * `inclusive`: gross = [amount], vat = `halfUp(amount x bp / (10000 + bp))` (the same as `amount - amount x 10000 / (10000 + bp)`);
     * otherwise vat = `halfUp(amount x bp / 10000)` and gross = amount + vat.
     */
    fun split(amount: Long, bp: Long, inclusive: Boolean): VatSplit =
        if (inclusive) {
            VatSplit(amount, Rounding.vatInside(amount, bp, 1))
        } else {
            val vat = Rounding.vatOnTop(amount, bp, 1)

            VatSplit(Math.addExact(amount, vat), vat)
        }
}
