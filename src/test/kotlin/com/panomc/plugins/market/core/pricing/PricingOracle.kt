package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.util.CouponScope
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

    // ================================================================ stages A3 and A4 (05 sections 6 and 7)

    class FullLine(
        /** Stage A1 and A2 of the scenario that was chosen (S2 has no automatic discount). */
        val a2: Line,
        val couponShare: Long,
        val creatorShare: Long,
        val basis: Long,
        val vatPercent: Long,
        val vat: Long,
        val total: Long
    )

    class CodeOut(val valid: Boolean, val reason: PricingCode?, val discount: Long)

    class Full(
        val currency: String,
        val lines: List<FullLine>,
        val coupon: CodeOut?,
        val creator: CodeOut?,
        val basis: BigInteger,
        val total: BigInteger,
        val vat: BigInteger,
        val physicalBasis: BigInteger,
        val basisBase: Long,
        val physicalBasisBase: Long
    )

    private class Ctx(val currency: String, val fxNum: BigInteger, val fxDen: BigInteger, val oq: Long, val bq: Long) {
        fun toOrder(baseAmount: Long): Long = roundQ(big(baseAmount).multiply(fxNum), fxDen, oq)
        fun fromOrder(orderAmount: Long): Long = roundQ(big(orderAmount).multiply(fxDen), fxNum, bq)
    }

    private fun context(input: PricingInput): Ctx {
        val cfg = input.config
        val base = cfg.baseCurrency
        val asked = input.requestedCurrency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val rate = asked?.let { c ->
            if (c != base && c in cfg.additionalCurrencies) cfg.rates[c]?.takeIf { it.signum() > 0 } else null
        }
        val currency = if (cfg.currencyMode == CurrencyMode.MULTI && rate != null) asked!! else base
        val fx: BigDecimal = if (currency == base) BigDecimal.ONE else rate!!
        return Ctx(currency, fx.unscaledValue(), BigInteger.TEN.pow(fx.scale()), quantum(currency, cfg.removeCents), quantum(base, cfg.removeCents))
    }

    private class Scenario(
        val a2: Expected,
        val amounts: List<Long>,
        val coupon: CodeOut?,
        val creator: CodeOut?,
        val couponShares: List<Long>,
        val creatorShares: List<Long>
    ) {
        val codeDiscount: Long get() = couponShares.sum() + creatorShares.sum()
        val basis: Long get() = amounts.sum() - codeDiscount
    }

    private fun withoutDiscounts(i: PricingInput) = PricingInput(
        i.config, i.now, i.profile, i.requestedCurrency, i.lines, i.buyer, emptyList(), i.coupon, i.creatorCode, i.pricingMode,
        i.payWithCredits, i.priceOverride
    )

    /** What the engine must return for [input], stages A1 to A4, from the rules of 05 read again and exact rational arithmetic. */
    fun expectFull(input: PricingInput): Full {
        val ctx = context(input)
        val stacked = scenario(input, ctx, suppressed = false)
        val withCodes = input.coupon != null || input.creatorCode != null
        var chosen = stacked
        if (withCodes && !input.config.combineDiscountsAndCoupons && stacked.a2.lines.any { it.unitDiscount > 0 }) {
            val codesInstead = scenario(withoutDiscounts(input), ctx, suppressed = false)
            if (stacked.codeDiscount > 0 || codesInstead.codeDiscount > 0) {
                val discountsInstead = scenario(input, ctx, suppressed = true)
                chosen = if (codesInstead.basis < discountsInstead.basis) codesInstead else discountsInstead
            }
        }
        return build(input, ctx, chosen)
    }

    private fun scenario(input: PricingInput, ctx: Ctx, suppressed: Boolean): Scenario {
        val a2 = expect(input)
        val n = input.lines.size
        val amounts = (0 until n).map { i ->
            val l = a2.lines[i]
            if (l.excluded) 0L else big(l.list - l.unitDiscount - l.upgrade).multiply(big(input.lines[i].quantity.toLong())).longValueExact()
        }
        val couponShares = LongArray(n)
        val creatorShares = LongArray(n)
        val external = input.pricingMode == PricingMode.EXTERNAL
        val pricedSum = amounts.sum()

        val coupon = input.coupon?.let { c ->
            fun out(valid: Boolean, reason: PricingCode?, discount: Long = 0) = CodeOut(valid, reason, discount)
            when {
                external -> out(false, PricingCode.EXTERNAL_PRICING)
                !c.found || !c.active -> out(false, PricingCode.CODE_NOT_FOUND)
                c.startDate != null && input.now < c.startDate -> out(false, PricingCode.CODE_NOT_STARTED)
                c.expiryDate != null && input.now >= c.expiryDate -> out(false, PricingCode.CODE_EXPIRED)
                c.redeemLimit != null && c.usedCount >= c.redeemLimit -> out(false, PricingCode.CODE_LIMIT_REACHED)
                c.customerRedeemLimit != null && c.buyerUses >= c.customerRedeemLimit -> out(false, PricingCode.CODE_LIMIT_REACHED)
                else -> {
                    val eligible = (0 until n).filter { i ->
                        val l = input.lines[i]
                        val byProduct = l.productId?.let { it in c.productIds } == true
                        val byCategory = (l.kind == LineKind.PRODUCT || l.kind == LineKind.BUNDLE) && l.categoryPath.any { it in c.categoryIds }
                        val inScope = when (c.scope) {
                            CouponScope.ALL -> l.kind == LineKind.PRODUCT || l.kind == LineKind.BUNDLE
                            CouponScope.SELECTED -> byProduct || byCategory
                        }
                        !a2.lines[i].excluded && !l.subscription && amounts[i] > 0 && inScope
                    }
                    val minimum = c.minPaymentAmount?.let { ctx.toOrder(it.coerceIn(0, MAX)) }
                    when {
                        eligible.isEmpty() -> out(false, PricingCode.COUPON_NOT_APPLICABLE)
                        minimum != null && pricedSum < minimum -> out(false, PricingCode.CODE_MIN_AMOUNT)
                        suppressed -> out(false, PricingCode.CODE_NOT_COMBINABLE)
                        else -> {
                            val parts = shares(c.unit, c.discount, eligible.map { amounts[it] }, ctx)
                            eligible.forEachIndexed { k, i -> couponShares[i] = parts[k] }
                            out(true, null, parts.sum())
                        }
                    }
                }
            }
        }

        val creator = input.creatorCode?.let { c ->
            fun out(valid: Boolean, reason: PricingCode?, discount: Long = 0) = CodeOut(valid, reason, discount)
            val buyer = input.buyer
            val own = (c.creatorUserId != null && (c.creatorUserId == buyer.userId || c.creatorUserId == buyer.recipientUserId)) ||
                (c.creatorEmail != null && buyer.email != null && c.creatorEmail.trim().lowercase(Locale.ROOT) == buyer.email.trim().lowercase(Locale.ROOT) &&
                    c.creatorEmail.isNotBlank())
            when {
                external -> out(false, PricingCode.EXTERNAL_PRICING)
                !c.found || !c.active -> out(false, PricingCode.CODE_NOT_FOUND)
                own -> out(false, PricingCode.CODE_NOT_FOUND)
                c.startDate != null && input.now < c.startDate -> out(false, PricingCode.CODE_NOT_STARTED)
                c.expiryDate != null && input.now >= c.expiryDate -> out(false, PricingCode.CODE_EXPIRED)
                c.redeemLimit != null && c.usedCount >= c.redeemLimit -> out(false, PricingCode.CODE_LIMIT_REACHED)
                c.discount <= 0 -> out(true, null)
                else -> {
                    val eligible = (0 until n).filter { i ->
                        val l = input.lines[i]
                        !a2.lines[i].excluded && !l.subscription && (l.kind == LineKind.PRODUCT || l.kind == LineKind.BUNDLE) &&
                            amounts[i] - couponShares[i] > 0
                    }
                    when {
                        eligible.isEmpty() -> out(true, PricingCode.COUPON_NOT_APPLICABLE)
                        suppressed -> out(true, PricingCode.CODE_NOT_COMBINABLE)
                        else -> {
                            val parts = shares(c.unit, c.discount, eligible.map { amounts[it] - couponShares[it] }, ctx)
                            eligible.forEachIndexed { k, i -> creatorShares[i] = parts[k] }
                            out(true, null, parts.sum())
                        }
                    }
                }
            }
        }
        return Scenario(a2, amounts, coupon, creator, couponShares.toList(), creatorShares.toList())
    }

    private const val MAX = 1_000_000_000_000L

    private fun shares(unit: DiscountUnit, value: Long, weights: List<Long>, ctx: Ctx): List<Long> =
        when (unit) {
            DiscountUnit.PERCENT -> weights.map { roundQ(big(it).multiply(big(value.coerceIn(0, 10_000))), big(10_000), ctx.oq) }
            DiscountUnit.FIXED -> apportion(minOf(ctx.toOrder(value.coerceIn(0, MAX)), weights.sum()), weights, ctx.oq)
        }

    /** Largest remainder: every line gets `floor(n * w / W)` quanta, the leftover quanta go to the biggest remainders, ties to the lower index. */
    private fun apportion(amount: Long, weights: List<Long>, q: Long): List<Long> {
        if (amount == 0L) return weights.map { 0L }
        val n = big(amount / q)
        val total = big(weights.sum())
        val numerators = weights.map { n.multiply(big(it)) }
        val floors = numerators.map { it.divide(total) }
        val remainders = numerators.map { it.mod(total) }
        val leftover = n.subtract(floors.fold(BigInteger.ZERO) { a, b -> a.add(b) }).toInt()
        val byRemainder = weights.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
        val extra = IntArray(weights.size)
        for (i in byRemainder.take(leftover)) extra[i] = 1
        return weights.indices.map { floors[it].add(BigInteger.valueOf(extra[it].toLong())).multiply(big(q)).longValueExact() }
    }

    private fun build(input: PricingInput, ctx: Ctx, s: Scenario): Full {
        val cfg = input.config
        var basisSum = BigInteger.ZERO
        var totalSum = BigInteger.ZERO
        var vatSum = BigInteger.ZERO
        var physicalSum = BigInteger.ZERO
        val lines = input.lines.indices.map { i ->
            val l = input.lines[i]
            val a2 = s.a2.lines[i]
            val basis = s.amounts[i] - s.couponShares[i] - s.creatorShares[i]
            val bp = (l.vatBp ?: cfg.vatBp).coerceIn(0, 10_000)
            fun inside() = roundQ(big(basis).multiply(big(bp)), big(10_000 + bp), ctx.oq)
            fun onTop() = roundQ(big(basis).multiply(big(bp)), big(10_000), ctx.oq)
            val line = when {
                a2.excluded -> FullLine(a2, 0, 0, 0, 0, 0, 0)
                input.pricingMode == PricingMode.EXTERNAL ->
                    FullLine(a2, s.couponShares[i], s.creatorShares[i], basis, 0, 0, big(a2.list).multiply(big(l.quantity.toLong())).longValueExact())
                input.pricingMode == PricingMode.EXTERNAL_TAX ->
                    FullLine(a2, s.couponShares[i], s.creatorShares[i], basis, 0, 0, if (cfg.pricesIncludeVat) basis - inside() else basis)
                cfg.pricesIncludeVat -> FullLine(a2, s.couponShares[i], s.creatorShares[i], basis, bp, inside(), basis)
                else -> onTop().let { vat -> FullLine(a2, s.couponShares[i], s.creatorShares[i], basis, bp, vat, basis + vat) }
            }
            basisSum = basisSum.add(big(line.basis))
            totalSum = totalSum.add(big(line.total))
            vatSum = vatSum.add(big(line.vat))
            if (l.physical && !a2.excluded) physicalSum = physicalSum.add(big(line.basis))
            line
        }
        return Full(
            ctx.currency, lines, s.coupon, s.creator, basisSum, totalSum, vatSum, physicalSum,
            ctx.fromOrder(basisSum.longValueExact()), ctx.fromOrder(physicalSum.longValueExact())
        )
    }
}
