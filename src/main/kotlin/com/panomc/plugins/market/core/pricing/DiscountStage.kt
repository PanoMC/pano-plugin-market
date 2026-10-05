package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit

/**
 * How a stage runs in one unit: the quantum of the amounts and the conversion of an admin-entered base-currency
 * amount (FIXED discount, threshold) into it. The money run uses the order currency (`oq`, `toOrder`); the credit
 * run of 05 section 8.1 uses credits (`q = 1`, `moneyToCredits`) with the same code.
 */
internal class AmountUnit(val quantum: Long, val fromBase: (Long) -> Long)

/** What stage A2 decided for one line. */
internal class DiscountOutcome(
    val discountId: Long?,
    val unitDiscount: Long,
    val upgradeUnitAmount: Long,
    val upgradeFromEntitlementId: Long?
) {
    companion object {
        val NONE = DiscountOutcome(null, 0L, 0L, null)
    }
}

/** Stage A2, automatic discount and upgrade deduction, per unit (05 section 5). */
internal object DiscountStage {
    class Settings(
        val unit: AmountUnit,
        val now: Long,
        /** Pre-discount subtotal of the priced lines, in [unit] (the `minPaymentAmount` base, 05 section 5.1 rule 4). */
        val subtotal: Long,
        val discounts: List<DiscountInput>,
        /** Automatic discounts may apply (profile allows them, pricing mode is not `EXTERNAL`, no price override). */
        val discountsEnabled: Boolean,
        /** `GIFT_CODE`: every line is discounted down to 0 (05 section 12). */
        val fullGift: Boolean,
        /** The upgrade link to the owned lower tier is recorded (profile allows upgrades, no price override). */
        val upgradeLink: Boolean,
        /** ... and its price is deducted (also not `EXTERNAL`). */
        val upgradeDeduction: Boolean,
        val recipientTiers: List<OwnedTier>,
        /**
         * Line keys that may claim an owned entitlement (see [upgradeClaimants]): any other tier line of the cart
         * records no upgrade link and gets no deduction, so one owned entitlement finances at most one line.
         */
        val upgradeClaimants: Set<String>,
        /**
         * Replay of the credit run (05 section 8.1): line key to the id of the discount that won there (null = none). The
         * money run of a full-credit order takes exactly these winners and recomputes their amounts without checking
         * the candidate rules again. Null = decide by the rules.
         */
        val forcedWinners: Map<String, Long?>? = null
    ) {
        /** The same run with the automatic discounts switched off (scenario S2 of 05 section 6.4; upgrades still apply). */
        fun withoutDiscounts() = Settings(
            unit, now, subtotal, discounts, false, fullGift, upgradeLink, upgradeDeduction, recipientTiers, upgradeClaimants, forcedWinners
        )

        /** The same run with the winners of the credit run replayed. */
        fun replaying(winners: Map<String, Long?>) = Settings(
            unit, now, subtotal, discounts, discountsEnabled, fullGift, upgradeLink, upgradeDeduction, recipientTiers, upgradeClaimants, winners
        )
    }

    /**
     * One owned entitlement finances **one** line per run (05 section 5.2): per tiered category the claimant is the
     * non-excluded tier line with the highest `tierRank`, a tie goes to the first of the cart. Without it a cart with
     * several tier lines of one category (Silver and Gold, or the same tier twice with different field values) would
     * deduct the same owned tier on every line and link it to every new entitlement.
     */
    fun upgradeClaimants(listed: List<ListedLine>): Set<String> {
        val claimant = HashMap<Long, ListedLine>()
        for (l in listed) {
            if (l.excluded) continue
            val tier = l.line.tier ?: continue
            val current = claimant[tier.categoryId]
            if (current == null || tier.tierRank > current.line.tier!!.tierRank) claimant[tier.categoryId] = l
        }
        return claimant.values.mapTo(HashSet()) { it.line.lineKey }
    }

    fun apply(listed: ListedLine, s: Settings): DiscountOutcome {
        if (listed.excluded) return DiscountOutcome.NONE
        val line = listed.line
        val list = listed.listUnitPrice

        if (s.fullGift) return DiscountOutcome(null, list, 0L, null)

        val winner = when {
            !s.discountsEnabled || !eligibleForDiscounts(line) -> null
            s.forcedWinners != null -> forcedDiscount(listed, s)
            else -> bestDiscount(listed, s)
        }
        val unitDiscount = winner?.second ?: 0L
        val upgrade = upgrade(line, list - unitDiscount, s)
        return DiscountOutcome(winner?.first?.id, unitDiscount, upgrade.first, upgrade.second)
    }

    /** Rule 5 of 05 section 5.1 that depends on the line alone: not a top-up, not a subscription. */
    private fun eligibleForDiscounts(line: LineInput): Boolean =
        line.kind != LineKind.CREDIT_TOPUP && !line.subscription

    /**
     * **Exactly one** discount applies per line: the largest per-unit amount, ties to the lowest id, so the result
     * does not depend on the order the discounts arrive in. A candidate that would take nothing off is not a
     * winner (no redemption row for it).
     */
    private fun bestDiscount(listed: ListedLine, s: Settings): Pair<DiscountInput, Long>? {
        var best: DiscountInput? = null
        var bestAmount = 0L
        for (d in s.discounts) {
            val amount = candidateAmount(d, listed, s) ?: continue
            if (amount <= 0L) continue
            if (amount > bestAmount || (amount == bestAmount && d.id < best!!.id)) {
                best = d
                bestAmount = amount
            }
        }
        return best?.let { it to bestAmount }
    }

    /**
     * The winner the credit run chose for this line, priced in this run's unit: its candidate rules are not checked again
     * (they were decided on the credit amounts), only its amount is recomputed. A winner that takes nothing here is none.
     */
    private fun forcedDiscount(listed: ListedLine, s: Settings): Pair<DiscountInput, Long>? {
        val id = s.forcedWinners!![listed.line.lineKey] ?: return null
        val d = s.discounts.firstOrNull { it.id == id } ?: return null
        val amount = amountOf(d, listed, s)
        return if (amount > 0L) d to amount else null
    }

    /** The per-unit amount of [d] on the line, or null when [d] is no candidate (05 section 5.1 rules 1 to 4). */
    private fun candidateAmount(d: DiscountInput, listed: ListedLine, s: Settings): Long? {
        if (!isCandidate(d, listed, s)) return null
        return amountOf(d, listed, s)
    }

    private fun isCandidate(d: DiscountInput, listed: ListedLine, s: Settings): Boolean {
        val line = listed.line
        if (d.startDate != null && s.now < d.startDate) return false
        if (d.expiryDate != null && s.now >= d.expiryDate) return false
        if (d.usageLimit != null && d.usedCount >= d.usageLimit) return false
        val inScope = when (d.scope) {
            DiscountScope.ALL -> line.kind == LineKind.PRODUCT || line.kind == LineKind.BUNDLE
            DiscountScope.PRODUCTS -> line.productId != null && line.productId in d.productIds
            DiscountScope.CATEGORIES -> line.kind != LineKind.CREDIT_PACK && line.categoryPath.any { it in d.categoryIds }
        }
        if (!inScope) return false
        return !(d.minPaymentAmount != null && s.subtotal < s.unit.fromBase(clampAmount(d.minPaymentAmount)))
    }

    private fun amountOf(d: DiscountInput, listed: ListedLine, s: Settings): Long {
        val list = listed.listUnitPrice
        return when (d.unit) {
            DiscountUnit.PERCENT -> Rounding.pctQ(list, clampBp(d.value), s.unit.quantum)
            DiscountUnit.FIXED -> minOf(s.unit.fromBase(clampAmount(d.value)), list)
        }
    }

    /**
     * Upgrade deduction for a tiered line (05 section 5.2): the highest owned lower tier of the same category (then the
     * highest entitlement id). `FULL` mode, a subscription line and a pricing mode that is not `MARKET` deduct
     * nothing but still record the entitlement that is replaced. Only the claimant line of its category
     * ([upgradeClaimants]) takes part; the other tier lines of the category get neither deduction nor link.
     */
    private fun upgrade(line: LineInput, discounted: Long, s: Settings): Pair<Long, Long?> {
        val tier = line.tier ?: return 0L to null
        if (!s.upgradeLink || line.lineKey !in s.upgradeClaimants) return 0L to null
        val owned = s.recipientTiers
            .filter { it.tierCategoryId == tier.categoryId && it.tierRank < tier.tierRank }
            .maxWithOrNull(compareBy<OwnedTier>({ it.tierRank }, { it.entitlementId }))
            ?: return 0L to null
        val deduct = s.upgradeDeduction && !line.subscription && tier.upgradeMode == UpgradeMode.DIFFERENCE
        val amount = if (deduct) minOf(s.unit.fromBase(owned.pricePaid), discounted) else 0L
        return amount to owned.entitlementId
    }

    /** 05 section 2 rule 4: basis points from the database are clamped to 0..10000. */
    internal fun clampBp(bp: Long): Long = bp.coerceIn(0L, 10_000L)

    /** A stored admin amount outside 0..MAX_AMOUNT is clamped, so one bad row never fails a whole quote. */
    internal fun clampAmount(amount: Long): Long = amount.coerceIn(0L, PricingLimits.MAX_AMOUNT)
}
