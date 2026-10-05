package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/**
 * An independent reference for stages A1 and A2 of 05, written for the property loop. It shares no code with the
 * engine: rational arithmetic on `BigInteger` only (`round(n / d)` is `(2n + d) / 2d`), its own currency table,
 * its own reading of the rules of 05 sections 4 and 5.
 */
object PricingOracle {
    class Line(val excluded: Boolean, val list: Long, val unitDiscount: Long, val upgrade: Long, val discountId: Long?, val from: Long?)

    class Expected(val currency: String, val lines: List<Line>, val subtotal: BigInteger)

    private val ZERO_DECIMAL = setOf("JPY", "KRW")

    private fun quantum(currency: String, removeCents: Boolean): Long =
        if (currency in ZERO_DECIMAL || removeCents) 100L else 1L

    private fun halfUpDiv(n: BigInteger, d: BigInteger): BigInteger = n.shiftLeft(1).add(d).divide(d.shiftLeft(1))

    private fun big(v: Long) = BigInteger.valueOf(v)

    /** `round(n / d / q) * q`. */
    private fun roundQ(n: BigInteger, d: BigInteger, q: Long): Long = halfUpDiv(n, d.multiply(big(q))).multiply(big(q)).longValueExact()

    fun expect(input: PricingInput): Expected {
        val cfg = input.config
        val base = cfg.baseCurrency
        val asked = input.requestedCurrency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val rate = asked?.let { c ->
            if (c != base && c in cfg.additionalCurrencies) cfg.rates[c]?.takeIf { it.signum() > 0 } else null
        }
        val currency = if (cfg.currencyMode == CurrencyMode.MULTI && rate != null) asked!! else base
        val fx: BigDecimal = if (currency == base) BigDecimal.ONE else rate!!
        val fxNum = fx.unscaledValue()
        val fxDen = BigInteger.TEN.pow(fx.scale())
        val oq = quantum(currency, cfg.removeCents)

        fun toOrder(baseAmount: Long): Long = roundQ(big(baseAmount).multiply(fxNum), fxDen, oq)

        // A1
        val listed = input.lines.map { l ->
            if (currency == base) return@map roundQ(big(l.basePrice), BigInteger.ONE, oq) to false
            val explicit = l.currencyPrices[currency]
            when {
                explicit != null -> roundQ(big(explicit), BigInteger.ONE, oq) to false
                // a priced product costs at least one quantum, a free one stays free
                cfg.multiCurrencyFallback == MultiCurrencyFallback.CONVERT ->
                    (if (l.basePrice > 0) maxOf(oq, toOrder(l.basePrice)) else 0L) to false
                else -> 0L to true
            }
        }
        var subtotal = BigInteger.ZERO
        for ((i, l) in input.lines.withIndex()) subtotal = subtotal.add(big(listed[i].first).multiply(big(l.quantity.toLong())))

        val external = input.pricingMode == PricingMode.EXTERNAL
        val override = input.priceOverride != null
        val discountsOn = input.profile in setOf(PricingProfile.STOREFRONT, PricingProfile.PANEL, PricingProfile.INGAME) && !external && !override
        val gift = input.profile == PricingProfile.GIFT_CODE
        val linkOn = input.profile in setOf(PricingProfile.STOREFRONT, PricingProfile.PANEL, PricingProfile.INGAME) && !override
        val deductOn = linkOn && !external

        // one owned entitlement finances one line: per tiered category the non-excluded tier line of the highest rank,
        // a tie to the lowest index (read by index here, the engine reads it by line key)
        val claimantIndex = HashSet<Int>()
        input.lines.mapIndexed { i, l -> i to l.tier }
            .filter { (i, t) -> t != null && !listed[i].second }
            .groupBy { it.second!!.categoryId }
            .values
            .forEach { group -> claimantIndex += group.sortedWith(compareBy({ -it.second!!.tierRank }, { it.first })).first().first }

        val lines = input.lines.mapIndexed { i, l ->
            val (list, excluded) = listed[i]
            if (excluded) return@mapIndexed Line(true, 0, 0, 0, null, null)
            if (gift) return@mapIndexed Line(false, list, list, 0, null, null)

            var best: DiscountInput? = null
            var bestAmount = 0L
            if (discountsOn && l.kind != LineKind.CREDIT_TOPUP && !l.subscription) {
                for (d in input.discounts) {
                    if (d.startDate != null && input.now < d.startDate) continue
                    if (d.expiryDate != null && input.now >= d.expiryDate) continue
                    if (d.usageLimit != null && d.usedCount >= d.usageLimit) continue
                    val inScope = when (d.scope) {
                        DiscountScope.ALL -> l.kind == LineKind.PRODUCT || l.kind == LineKind.BUNDLE
                        DiscountScope.PRODUCTS -> l.productId in d.productIds
                        DiscountScope.CATEGORIES -> l.kind != LineKind.CREDIT_PACK && l.categoryPath.any { it in d.categoryIds }
                    }
                    if (!inScope) continue
                    if (d.minPaymentAmount != null && subtotal < big(toOrder(d.minPaymentAmount))) continue
                    val amount = when (d.unit) {
                        DiscountUnit.PERCENT -> roundQ(big(list).multiply(big(d.value)), big(10_000), oq)
                        DiscountUnit.FIXED -> minOf(toOrder(d.value), list)
                    }
                    if (amount <= 0) continue
                    if (amount > bestAmount || (amount == bestAmount && d.id < best!!.id)) {
                        best = d
                        bestAmount = amount
                    }
                }
            }

            var upgrade = 0L
            var from: Long? = null
            val tier = l.tier
            if (tier != null && linkOn && i in claimantIndex) {
                val owned = input.buyer.recipientTiers
                    .filter { it.tierCategoryId == tier.categoryId && it.tierRank < tier.tierRank }
                    .sortedWith(compareBy({ it.tierRank }, { it.entitlementId }))
                    .lastOrNull()
                if (owned != null) {
                    from = owned.entitlementId
                    if (deductOn && !l.subscription && tier.upgradeMode == UpgradeMode.DIFFERENCE) {
                        upgrade = minOf(toOrder(owned.pricePaid), list - bestAmount)
                    }
                }
            }
            Line(false, list, bestAmount, upgrade, best?.id, from)
        }
        return Expected(currency, lines, subtotal)
    }
}
