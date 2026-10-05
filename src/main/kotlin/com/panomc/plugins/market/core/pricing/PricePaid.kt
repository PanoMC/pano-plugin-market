package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import java.math.BigDecimal

/**
 * `market_entitlement.pricePaid` of the entitlement a line creates, in the **base** currency (05 section 5.2), so a
 * chained upgrade credits the full value of the lower tier. The line total and VAT come from stage A4; the formulas
 * live here with the upgrade rules they feed.
 */
object PricePaid {
    /**
     * Money order: `fromOrder(basisPerUnit) + fromOrder(upgradeUnitAmount)` with
     * `basisPerUnit = (pricesIncludeVat ? lineTotal : lineTotal - vatAmount) / quantity`.
     * [basisLine] is that numerator (a whole line, order currency); the division by the quantity and the conversion
     * to the base currency are one rounding.
     */
    fun moneyOrder(conversions: Conversions, basisLine: Long, quantity: Int, upgradeUnitAmount: Long): Long {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        val perUnit = Rounding.ratioQ(
            BigDecimal.valueOf(basisLine),
            conversions.fx.multiply(BigDecimal.valueOf(quantity.toLong())),
            conversions.bq
        )
        return Math.addExact(perUnit, conversions.fromOrder(upgradeUnitAmount))
    }

    /**
     * Credit-mode order (05 section 8.1): `halfUp(creditLineTotal / quantity * cv / 100) + fromOrder(upgradeUnitAmount)`.
     */
    fun creditOrder(conversions: Conversions, creditLineTotal: Long, quantity: Int, upgradeUnitAmount: Long): Long {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(conversions.creditValue > 0) { "the credit value must be positive" }
        val perUnit = Rounding.ratioQ(
            BigDecimal.valueOf(creditLineTotal).multiply(BigDecimal.valueOf(conversions.creditValue)),
            BigDecimal.valueOf(quantity.toLong()).multiply(BigDecimal(100)),
            1L
        )
        return Math.addExact(perUnit, conversions.fromOrder(upgradeUnitAmount))
    }
}
