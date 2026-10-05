package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Rounding
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Values derived from a priced order that are written when it is paid (05 section 10): the creator earning and the
 * cashback. Pure formulas on a [PriceBreakdown]; the posting, the hold days and the conditions under which they apply are
 * the services' business (`21` section 7, `07` section 9).
 */
object OrderValues {
    /** `market_creator_earning`: [baseAmount] is what the commission is taken from (base currency, VAT excluded), [amount] the commission. */
    data class CreatorEarning(val baseAmount: Long, val amount: Long)

    /**
     * ```
     * baseAmount = fromOrder(sum(lineTotal - vatAmount) over PRODUCT / BUNDLE lines)     excl. VAT, shipping, fee
     *              credit-mode order: halfUp(sum(credit lineTotal) x cv / 100)
     * amount     = pctQ(baseAmount, commissionBp, bq)
     * ```
     * A credit pack or top-up never earns: a creator code takes no discount there and the credits are a purchase of money.
     */
    fun creatorEarning(order: PriceBreakdown, commissionBp: Long): CreatorEarning {
        val c = order.conversions
        val earning = order.lines.filter { it.lineKind == LineKind.PRODUCT || it.lineKind == LineKind.BUNDLE }
        val creditMode = order.paymentMethodId == MethodInput.CREDITS && order.creditAmount > 0L
        val baseAmount = if (creditMode) {
            val credit = order.items.credit!!
            val keys = earning.mapTo(HashSet()) { it.lineKey }
            val credits = credit.lines.filter { it.lineKey in keys }.fold(0L) { a, l -> Math.addExact(a, l.lineTotal) }
            Rounding.ratioQ(BigDecimal.valueOf(credits).multiply(BigDecimal.valueOf(c.creditValue)), BigDecimal(100), 1L)
        } else {
            var net = 0L
            for (l in earning) net = Math.addExact(net, Math.subtractExact(l.lineTotal, l.vatAmount))
            c.fromOrder(net)
        }
        return CreatorEarning(baseAmount, Rounding.pctQ(baseAmount, DiscountStage.clampBp(commissionBp), c.bq))
    }

    /**
     * Cashback in credits x 100 (05 section 10), one rounding, `floor`:
     * ```
     * eligible     = sum(lineTotal) of PRODUCT / BUNDLE lines (not CREDIT_PACK)
     * denominator  = total - paymentFee
     * gatewayShare = (gatewayAmount - paymentFee) / denominator          (0 when denominator == 0)
     * cashback     = floor(eligible x gatewayShare x 100 / (cv x fx) x cashbackBp / 10000)
     * ```
     * Only money a gateway collected earns: credit-paid value, shipping, the fee and credit purchases never do.
     * [cashbackBp] defaults to the config value the order was priced with.
     */
    fun cashback(order: PriceBreakdown, cashbackBp: Long = order.items.terms.cashbackBp): Long {
        val c = order.conversions
        val bp = DiscountStage.clampBp(cashbackBp)
        val denominator = Math.subtractExact(order.total, order.paymentFee)
        if (denominator <= 0L || bp == 0L || c.creditValue <= 0L) return 0L
        val gatewayPart = Math.subtractExact(order.gatewayAmount, order.paymentFee)
        if (gatewayPart <= 0L) return 0L
        var eligible = 0L
        for (l in order.lines) {
            if (l.lineKind == LineKind.PRODUCT || l.lineKind == LineKind.BUNDLE) eligible = Math.addExact(eligible, l.lineTotal)
        }
        val numerator = BigDecimal.valueOf(eligible).multiply(BigDecimal.valueOf(gatewayPart))
            .multiply(BigDecimal(100)).multiply(BigDecimal.valueOf(bp))
        val divisor = BigDecimal.valueOf(denominator).multiply(BigDecimal.valueOf(c.creditValue)).multiply(c.fx)
            .multiply(BigDecimal(10_000))
        return numerator.divide(divisor, 0, RoundingMode.FLOOR).longValueExact()
    }
}
