package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountUnit

/** A line as stage A3 sees it: what it is, whether it is out of every sum, and what it costs after stage A2 (`lineAmount`). */
internal class CodeLine(val line: LineInput, val excluded: Boolean, val amount: Long)

/** What stage A3 decided: the outcomes and, per line (same index as the lines given), the coupon and creator shares. */
internal class CodeResult(
    val coupon: CodeOutcome?,
    val creatorCode: CodeOutcome?,
    val couponShares: List<Long>,
    val creatorShares: List<Long>,
    /** Indices of the lines the coupon was spread over (empty when it was refused), for the replay of the credit run. */
    val couponLines: List<Int> = emptyList(),
    /** Indices of the lines the creator code was spread over (empty when it was refused or gave attribution only). */
    val creatorLines: List<Int> = emptyList()
) {
    val couponDiscount: Long = couponShares.fold(0L) { a, b -> Math.addExact(a, b) }
    val creatorDiscount: Long = creatorShares.fold(0L) { a, b -> Math.addExact(a, b) }

    /** A code takes something off the order. */
    val hasDiscount: Boolean get() = couponDiscount > 0L || creatorDiscount > 0L

    companion object {
        fun none(lines: Int) = CodeResult(null, null, List(lines) { 0L }, List(lines) { 0L })
    }
}

/**
 * What the credit run decided about the codes (05 section 8.1), replayed by the money run of a full-credit order: the
 * outcomes (valid or refused, with the reason) and the lines each code was spread over. The money run recomputes the
 * shares on its own amounts and checks no rule again.
 */
internal class CodeReplay(val coupon: CodeOutcome?, val couponLines: List<Int>, val creator: CodeOutcome?, val creatorLines: List<Int>) {
    companion object {
        fun of(result: CodeResult) = CodeReplay(result.coupon, result.couponLines, result.creatorCode, result.creatorLines)
    }
}

/**
 * Stage A3, coupon and creator code per line (05 section 6). Pure and unit-agnostic like [DiscountStage]: amounts are in
 * the [AmountUnit] of the run (order currency, or credits for the credit run of 05 section 8.1), so the credit run can
 * call the same code.
 *
 * The coupon is applied first, the creator code second on what is left. Both are shares per line, rounded per line
 * (PERCENT) or allocated by largest remainder (FIXED), so the lines add up exactly to what the code took off.
 */
internal object CodeStage {
    class Settings(
        val unit: AmountUnit,
        val now: Long,
        /** The selected method sets the price itself (`PricingMode.EXTERNAL`): no code can apply (check 1). */
        val external: Boolean,
        val buyer: BuyerContext,
        val coupon: CouponInput?,
        val creatorCode: CreatorCodeInput?
    )

    /**
     * Evaluates the codes of [settings] on [lines]. [suppressed] is scenario S1 of the combine rule (05 section 6.4):
     * the automatic discounts were chosen, so a code that passed every other check is not applied
     * (`CODE_NOT_COMBINABLE`, check 10).
     */
    fun apply(lines: List<CodeLine>, settings: Settings, suppressed: Boolean, replay: CodeReplay? = null): CodeResult {
        val couponShares = LongArray(lines.size)
        val creatorShares = LongArray(lines.size)
        val couponLines = ArrayList<Int>()
        val creatorLines = ArrayList<Int>()
        val coupon = settings.coupon?.let {
            if (replay?.coupon != null) replayCoupon(it, replay.coupon, replay.couponLines, lines, settings, couponShares, couponLines)
            else couponOutcome(it, lines, settings, suppressed, couponShares, couponLines)
        }
        val creator = settings.creatorCode?.let {
            if (replay?.creator != null) replayCreator(it, replay.creator, replay.creatorLines, lines, settings, couponShares, creatorShares, creatorLines)
            else creatorOutcome(it, lines, settings, suppressed, couponShares, creatorShares, creatorLines)
        }
        return CodeResult(coupon, creator, couponShares.toList(), creatorShares.toList(), couponLines, creatorLines)
    }

    /** The coupon as the credit run decided it: a refused coupon stays refused, a valid one is spread over the same lines on this run's amounts. */
    private fun replayCoupon(
        c: CouponInput, decided: CodeOutcome, eligible: List<Int>, lines: List<CodeLine>, s: Settings, shares: LongArray, used: MutableList<Int>
    ): CodeOutcome {
        if (!decided.valid) return CodeOutcome(decided.id, decided.code, false, decided.reason, 0L)
        val parts = split(c.unit, c.discount, eligible.map { lines[it].amount }, s.unit)
        eligible.forEachIndexed { k, index -> shares[index] = parts[k] }
        used += eligible
        return CodeOutcome(decided.id, decided.code, true, null, sum(parts))
    }

    /** The creator code as the credit run decided it; an attribution-only code (no line, a reason or no discount) stays so. */
    private fun replayCreator(
        c: CreatorCodeInput, decided: CodeOutcome, eligible: List<Int>, lines: List<CodeLine>, s: Settings,
        couponShares: LongArray, shares: LongArray, used: MutableList<Int>
    ): CodeOutcome {
        if (!decided.valid || eligible.isEmpty()) return CodeOutcome(decided.id, decided.code, decided.valid, decided.reason, 0L)
        val parts = split(c.unit, c.discount, eligible.map { lines[it].amount - couponShares[it] }, s.unit)
        eligible.forEachIndexed { k, index -> shares[index] = parts[k] }
        used += eligible
        return CodeOutcome(decided.id, decided.code, true, null, sum(parts))
    }

    // ---------------------------------------------------------------- coupon

    private fun couponOutcome(
        c: CouponInput, lines: List<CodeLine>, s: Settings, suppressed: Boolean, shares: LongArray, used: MutableList<Int>
    ): CodeOutcome {
        val id = if (c.found) c.id else null
        fun refused(reason: PricingCode) = CodeOutcome(id, c.code, false, reason, 0L)

        // 05 section 6.3: the first failing check gives the reason
        if (s.external) return refused(PricingCode.EXTERNAL_PRICING) // 1
        if (!c.found || !c.active) return refused(PricingCode.CODE_NOT_FOUND) // 2
        if (c.startDate != null && s.now < c.startDate) return refused(PricingCode.CODE_NOT_STARTED) // 4
        if (c.expiryDate != null && s.now >= c.expiryDate) return refused(PricingCode.CODE_EXPIRED) // 5
        if (c.redeemLimit != null && c.usedCount >= c.redeemLimit) return refused(PricingCode.CODE_LIMIT_REACHED) // 6
        if (c.customerRedeemLimit != null && c.buyerUses >= c.customerRedeemLimit) return refused(PricingCode.CODE_LIMIT_REACHED) // 7

        val eligible = lines.indices.filter { couponEligible(c, lines[it]) }
        if (eligible.isEmpty()) return refused(PricingCode.COUPON_NOT_APPLICABLE) // 8
        if (c.minPaymentAmount != null && pricedSum(lines) < s.unit.fromBase(DiscountStage.clampAmount(c.minPaymentAmount))) {
            return refused(PricingCode.CODE_MIN_AMOUNT) // 9
        }
        if (suppressed) return refused(PricingCode.CODE_NOT_COMBINABLE) // 10

        val parts = split(c.unit, c.discount, eligible.map { lines[it].amount }, s.unit)
        eligible.forEachIndexed { k, index -> shares[index] = parts[k] }
        used += eligible
        return CodeOutcome(id, c.code, true, null, sum(parts))
    }

    /** 05 section 6.1: scope `ALL` takes products and bundles, `SELECTED` the listed products (a credit pack only by its own id) and categories. */
    private fun couponEligible(c: CouponInput, l: CodeLine): Boolean {
        val line = l.line
        if (l.excluded || line.subscription || l.amount <= 0L) return false
        return when (c.scope) {
            CouponScope.ALL -> line.kind == LineKind.PRODUCT || line.kind == LineKind.BUNDLE
            CouponScope.SELECTED ->
                (line.productId != null && line.productId in c.productIds) ||
                    (line.kind != LineKind.CREDIT_PACK && line.kind != LineKind.CREDIT_TOPUP && line.categoryPath.any { it in c.categoryIds })
        }
    }

    // ---------------------------------------------------------------- creator code

    private fun creatorOutcome(
        c: CreatorCodeInput, lines: List<CodeLine>, s: Settings, suppressed: Boolean, couponShares: LongArray, shares: LongArray,
        used: MutableList<Int>
    ): CodeOutcome {
        val id = if (c.found) c.id else null
        fun refused(reason: PricingCode) = CodeOutcome(id, c.code, false, reason, 0L)

        if (s.external) return refused(PricingCode.EXTERNAL_PRICING) // 1
        if (!c.found || !c.active) return refused(PricingCode.CODE_NOT_FOUND) // 2
        if (usesOwnCode(c, s.buyer)) return refused(PricingCode.CODE_NOT_FOUND) // 3
        if (c.startDate != null && s.now < c.startDate) return refused(PricingCode.CODE_NOT_STARTED) // 4
        if (c.expiryDate != null && s.now >= c.expiryDate) return refused(PricingCode.CODE_EXPIRED) // 5
        if (c.redeemLimit != null && c.usedCount >= c.redeemLimit) return refused(PricingCode.CODE_LIMIT_REACHED) // 6

        // From here on the code is valid (attribution, commission). Checks 8 and 10 only zero its discount; a code
        // that has no discount at all has nothing to zero.
        fun attribution(reason: PricingCode?) = CodeOutcome(id, c.code, true, reason, 0L)
        if (c.discount <= 0L) return attribution(null)

        val eligible = lines.indices.filter { creatorEligible(lines[it], couponShares[it]) }
        if (eligible.isEmpty()) return attribution(PricingCode.COUPON_NOT_APPLICABLE) // 8
        if (suppressed) return attribution(PricingCode.CODE_NOT_COMBINABLE) // 10

        val parts = split(c.unit, c.discount, eligible.map { lines[it].amount - couponShares[it] }, s.unit)
        eligible.forEachIndexed { k, index -> shares[index] = parts[k] }
        used += eligible
        return CodeOutcome(id, c.code, true, null, sum(parts))
    }

    /** 05 section 6.1: products and bundles only (no credit purchase), not a subscription, something left after the coupon. */
    private fun creatorEligible(l: CodeLine, couponShare: Long): Boolean {
        val line = l.line
        if (l.excluded || line.subscription) return false
        if (line.kind != LineKind.PRODUCT && line.kind != LineKind.BUNDLE) return false
        return l.amount - couponShare > 0L
    }

    /**
     * Check 3: the creator would benefit from their own code, as the payer, as the recipient of a gift, or as the
     * account behind the order e-mail (a guest purchase gifted to the creator does not sidestep the rule).
     */
    private fun usesOwnCode(c: CreatorCodeInput, buyer: BuyerContext): Boolean {
        val creator = c.creatorUserId
        if (creator != null && (creator == buyer.userId || creator == buyer.recipientUserId)) return true
        val creatorEmail = c.creatorEmail?.trim()?.lowercase()
        val orderEmail = buyer.email?.trim()?.lowercase()
        return !creatorEmail.isNullOrEmpty() && creatorEmail == orderEmail
    }

    // ---------------------------------------------------------------- amounts

    /**
     * One code over its eligible lines (05 section 6.2): PERCENT is `pctQ` per line, FIXED is `min(amount, sum)` allocated
     * by largest remainder. Stored values outside their range are clamped (05 section 2 rule 4).
     */
    private fun split(unit: DiscountUnit, value: Long, amounts: List<Long>, au: AmountUnit): List<Long> =
        when (unit) {
            DiscountUnit.PERCENT -> amounts.map { Rounding.pctQ(it, DiscountStage.clampBp(value), au.quantum) }
            DiscountUnit.FIXED -> {
                val total = sum(amounts)
                val amount = minOf(au.fromBase(DiscountStage.clampAmount(value)), total)
                Rounding.allocate(amount, amounts, au.quantum)
            }
        }

    /** Sum of every priced line (check 9 looks at the whole cart, not only at the eligible lines). */
    private fun pricedSum(lines: List<CodeLine>): Long = lines.fold(0L) { a, l -> if (l.excluded) a else Math.addExact(a, l.amount) }

    private fun sum(values: List<Long>): Long = values.fold(0L) { a, b -> Math.addExact(a, b) }
}
