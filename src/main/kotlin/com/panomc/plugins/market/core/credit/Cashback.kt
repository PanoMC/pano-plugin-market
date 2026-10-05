package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import com.panomc.plugins.market.db.model.PricingMode as OrderPricingMode

/**
 * Cashback on a paid order and its reversal (07 sections 9.1 to 9.3, formula of 05 section 10). Pure; the posting
 * (`CASHBACK` key `order:<orderId>:cashback`, `CASHBACK_REVERSAL` keys `refund:<refundId>:cashback` /
 * `dispute:<disputeId>:cashback`, policy `TAKE_AVAILABLE`, `ALLOW_DEBT` on a chargeback) is `CreditService`'s.
 *
 * It works on the persisted order (`market_order`, `market_order_item`), so the pricing engine's quote-time
 * `OrderValues.cashback` and this function must agree for every order; `CashbackTest` runs both over thousands of seeded
 * orders.
 */
object Cashback {
    /** Basis points are clamped to 0..10000 like everywhere in pricing (05 section 2 rule 4). */
    private const val BP_MAX = 10_000L

    /** One `market_order_item`. [creditPack] is `snapshot.kind == CREDIT_PACK` (a credit purchase never earns cashback). */
    class Item(val kind: OrderItemKind, val lineTotal: Long, val creditPack: Boolean = false)

    /** The order columns the rules read. [fxRate] is `market_order.fxRate` (1 in `SINGLE` / `DISPLAY`). */
    class OrderFacts(
        val userId: Long?,
        val testMode: Boolean,
        val pricingMode: OrderPricingMode,
        val source: OrderSource,
        val totalPrice: Long,
        val paymentFee: Long,
        val gatewayAmount: Long,
        val fxRate: BigDecimal,
        val items: List<Item>
    )

    /** The config values at payment time (07 section 9.1): `cashbackBp = MoneyUtil.toMinor(cashbackPercent)`, [rateMinor] = `creditValue`. */
    class Settings(val creditsEnabled: Boolean, val cashbackBp: Long, val rateMinor: Long)

    /**
     * 07 section 9.1: credits on, `cashbackBp > 0`, a logged-in payer, no test-mode order, `pricingMode != EXTERNAL` and
     * `source` in `STOREFRONT` / `RENEWAL`. The recipient of the credits is the **payer** (`order.userId`), also for a gift.
     */
    fun applies(order: OrderFacts, settings: Settings): Boolean =
        settings.creditsEnabled &&
            settings.cashbackBp > 0L &&
            settings.rateMinor >= 1L &&
            order.userId != null &&
            !order.testMode &&
            order.pricingMode != OrderPricingMode.EXTERNAL &&
            (order.source == OrderSource.STOREFRONT || order.source == OrderSource.RENEWAL)

    /**
     * The cashback in credits x 100; 0 means "no transaction" (07 section 9.2):
     * ```
     * eligible     = sum(lineTotal) of PRODUCT / BUNDLE items that are not CREDIT_PACK
     * denominator  = totalPrice - paymentFee
     * gatewayShare = (gatewayAmount - paymentFee) / denominator          (0 when denominator == 0)
     * cashback     = floor(eligible x gatewayShare x 100 / (rateMinor x fxRate) x cashbackBp / 10000)
     * ```
     * Only money a gateway collected earns: credit-paid value, shipping, the fee and credit purchases never do. One division,
     * one rounding (`FLOOR`).
     */
    fun compute(order: OrderFacts, settings: Settings): Long {
        if (!applies(order, settings)) return 0L
        val denominator = order.totalPrice - order.paymentFee
        val gatewayPart = order.gatewayAmount - order.paymentFee
        if (denominator <= 0L || gatewayPart <= 0L) return 0L
        var eligible = 0L
        for (item in order.items) {
            if ((item.kind == OrderItemKind.PRODUCT || item.kind == OrderItemKind.BUNDLE) && !item.creditPack) {
                eligible = Math.addExact(eligible, item.lineTotal)
            }
        }
        if (eligible <= 0L) return 0L
        val numerator = BigDecimal.valueOf(eligible).multiply(BigDecimal.valueOf(gatewayPart))
            .multiply(BigDecimal(100)).multiply(BigDecimal.valueOf(minOf(settings.cashbackBp, BP_MAX)))
        val divisor = BigDecimal.valueOf(denominator).multiply(BigDecimal.valueOf(settings.rateMinor)).multiply(order.fxRate)
            .multiply(BigDecimal(10_000))
        return numerator.divide(divisor, 0, RoundingMode.FLOOR).longValueExact()
    }

    /**
     * What to take back when a refund succeeds (07 section 9.3, O10), credits x 100; 0 or less means "skip".
     * ```
     * target  = cashback                                            when the order becomes REFUNDED (refundedTotal == totalPrice)
     *         = floor(cashback x refundedTotalAfter / totalPrice)   otherwise
     * request = target - already                                    already = sum(amount + shortfall) of its CASHBACK_REVERSAL txs
     * ```
     * The sum of all requests never exceeds [cashback], shortfalls counted.
     */
    fun reversal(cashback: Long, alreadyReversed: Long, refundedTotalAfter: Long, totalPrice: Long): Long {
        require(cashback >= 0 && alreadyReversed >= 0 && refundedTotalAfter >= 0 && totalPrice >= 0) { "figures are never negative" }
        if (cashback == 0L) return 0L
        val target = if (totalPrice == 0L || refundedTotalAfter >= totalPrice) {
            cashback
        } else {
            BigInteger.valueOf(cashback).multiply(BigInteger.valueOf(refundedTotalAfter)).divide(BigInteger.valueOf(totalPrice)).longValueExact()
        }
        return maxOf(0L, target - alreadyReversed)
    }

    /** What a chargeback takes back (07 section 9.3, O11): everything not reversed yet. A won dispute re-grants nothing. */
    fun chargeback(cashback: Long, alreadyReversed: Long): Long {
        require(cashback >= 0 && alreadyReversed >= 0) { "figures are never negative" }
        return maxOf(0L, cashback - alreadyReversed)
    }
}
