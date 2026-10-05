package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Rounding

/** What stage A4 decided for one line (05 section 7). */
internal class LineVat(val vatPercent: Long, val vatAmount: Long, val lineTotal: Long) {
    companion object {
        val NONE = LineVat(0L, 0L, 0L)
    }
}

/**
 * Stage A4, VAT and line totals (05 section 7). VAT is rounded per line, on the line's final amount in the price basis
 * ([lineBasis] = `lineAmount - couponAmount`); order totals are sums of lines (00 section 6.6).
 *
 * ```
 * pricingMode   pricesIncludeVat   vatAmount                    lineTotal                        vatPercent
 * MARKET        true               vatInside(basis, bp)         basis                            bp
 * MARKET        false              vatOnTop(basis, bp)          basis + vatAmount                bp
 * EXTERNAL_TAX  true               0                            basis - vatInside(basis, bp)     0   (the gateway adds its own tax)
 * EXTERNAL_TAX  false              0                            basis                            0
 * EXTERNAL      any                0                            listUnitPrice * quantity         0   (an estimate; the gateway sets the price)
 * ```
 */
internal object VatStage {
    fun line(
        listed: ListedLine,
        lineBasis: Long,
        mode: PricingMode,
        pricesIncludeVat: Boolean,
        configVatBp: Long,
        quantum: Long
    ): LineVat {
        if (listed.excluded) return LineVat.NONE
        // 05 section 2 rule 4: VAT is clamped to 0..10000 whatever the database holds
        val bp = DiscountStage.clampBp(listed.line.vatBp ?: configVatBp)
        return when (mode) {
            PricingMode.MARKET ->
                if (pricesIncludeVat) {
                    LineVat(bp, Rounding.vatInside(lineBasis, bp, quantum), lineBasis)
                } else {
                    val vat = Rounding.vatOnTop(lineBasis, bp, quantum)
                    LineVat(bp, vat, Math.addExact(lineBasis, vat))
                }
            PricingMode.EXTERNAL_TAX ->
                LineVat(0L, 0L, if (pricesIncludeVat) lineBasis - Rounding.vatInside(lineBasis, bp, quantum) else lineBasis)
            PricingMode.EXTERNAL ->
                LineVat(0L, 0L, Math.multiplyExact(listed.listUnitPrice, listed.line.quantity.toLong()))
        }
    }
}
