package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PriceAuthority
import java.math.BigDecimal
import java.math.BigInteger

/**
 * An independent reference for stages B and C of 05 (sections 8.1, 8.2 and 9.1 to 9.5), written for the property loop. It
 * shares no code with `Tender.kt` / `MixedPayment.kt`: rational arithmetic on `BigInteger` only (`round(n / d)` is
 * `(2n + d) / 2d`), its own reading of the rules, its own quantum table. It starts from the stage A result (the item totals are
 * proven by [PricingOracle] and the A3 / A4 loops) and from the raw input.
 *
 * One deliberate difference from the letter of 05 section 8.2, shared with the engine and recorded in the evidence: the
 * provider's minimum is rounded **up** to the quantum before it becomes `minRemainder`, so that the gateway remainder is
 * always a whole number of quanta and never below the provider's minimum.
 */
object TenderOracle {
    class Expected(
        val shippingTotal: Long,
        val shippingVat: Long,
        val preFee: Long,
        val creditAmount: Long,
        val creditValue: Long,
        val maxApplicable: Long,
        val paymentFee: Long,
        val feeVat: Long,
        val total: Long,
        val vatTotal: Long,
        val gatewayAmount: Long,
        val methodId: String?,
        val fullCredit: Boolean,
        val creditsBlock: Boolean,
        val payable: Boolean,
        val creditTotal: Long,
        val shippingCredits: Long,
        val unavailable: PricingCode?,
        val messages: List<PricingCode>,
        /** Which branch of the tender ran, for the loop's counters. */
        val branches: List<String>
    )

    private val ZERO_DECIMAL = setOf("JPY", "KRW")
    private val EXEMPT = setOf("credits", "free", "manual")
    private const val MAX_AMOUNT = 1_000_000_000_000L

    private fun big(v: Long) = BigInteger.valueOf(v)

    private fun quantum(currency: String, removeCents: Boolean): Long = if (currency in ZERO_DECIMAL || removeCents) 100L else 1L

    private fun halfUpDiv(n: BigInteger, d: BigInteger): BigInteger = n.shiftLeft(1).add(d).divide(d.shiftLeft(1))

    private fun floorDiv(n: BigInteger, d: BigInteger): BigInteger = n.divide(d)

    private fun ceilDiv(n: BigInteger, d: BigInteger): BigInteger = n.add(d).subtract(BigInteger.ONE).divide(d)

    /** `round(n / d / q) * q`. */
    private fun roundQ(n: BigInteger, d: BigInteger, q: Long): Long = halfUpDiv(n, d.multiply(big(q))).multiply(big(q)).longValueExact()

    private fun roundQ(n: Long, d: Long, q: Long): Long = roundQ(big(n), big(d), q)

    private fun clampBp(v: Long) = v.coerceIn(0L, 10_000L)

    private fun clampAmount(v: Long) = v.coerceIn(0L, MAX_AMOUNT)

    fun expect(input: PricingInput, items: ItemsResult, shipping: ShippingCharge?, tender: TenderInput): Expected {
        val cfg = input.config
        val profile = input.profile
        val currency = items.currency
        val oq = quantum(currency, cfg.removeCents)
        val bq = quantum(cfg.baseCurrency, cfg.removeCents)
        val fx: BigDecimal = items.conversions.fx
        val fxNum = fx.unscaledValue()
        val fxDen = BigInteger.TEN.pow(fx.scale())
        val cv = cfg.creditValue
        val branches = ArrayList<String>()

        fun toOrder(base: Long): Long = roundQ(big(base).multiply(fxNum), fxDen, oq)
        fun fromOrder(order: Long): Long = roundQ(big(order).multiply(fxDen), fxNum, bq)

        // what the lines say about the cart (hidden lines count for nothing)
        val hidden = items.lines.filter { it.parentLineKey == null && it.excluded }.mapTo(HashSet()) { it.lineKey }
        val live = input.lines.filter { it.lineKey !in hidden }
        val creditGranting = live.any { it.kind == LineKind.CREDIT_PACK || it.kind == LineKind.CREDIT_TOPUP }
        val hasSubscription = live.any { it.subscription }
        val shippable = live.any { it.physical || (it.kind == LineKind.BUNDLE && it.children.any { c -> c.physical }) }
        check(shippable == items.requiresShipping) { "stage A says requiresShipping=${items.requiresShipping}, the lines say $shippable" }

        // 9.1 shipping (B)
        var shippingTotal = 0L
        var shippingVat = 0L
        val ships = (profile == PricingProfile.STOREFRONT || profile == PricingProfile.PANEL) && input.priceOverride == null
        if (ships && shippable && shipping != null) {
            val bp = clampBp(shipping.vatBp ?: cfg.vatBp)
            val charge = roundQ(shipping.price, 1, oq)
            if (cfg.pricesIncludeVat) {
                shippingTotal = charge
                shippingVat = roundQ(big(charge).multiply(big(bp)), big(10_000 + bp), oq)
            } else {
                shippingVat = roundQ(big(charge).multiply(big(bp)), big(10_000), oq)
                shippingTotal = charge + shippingVat
            }
            branches += "shipping charged"
        }
        val preFee = items.itemsTotal + shippingTotal

        val payWithCredits = items.payWithCredits
        val creditsRequired = payWithCredits && tender.method != null && tender.method.id != "credits" && !input.payWithCredits
        val method: MethodInput? = if (creditsRequired) null else tender.method
        val strict = tender.strict

        val creditProfile = profile == PricingProfile.STOREFRONT || profile == PricingProfile.INGAME
        val creditsOn = cfg.creditsEnabled && creditProfile
        val loggedIn = input.buyer.loggedIn
        val balance = if (loggedIn) maxOf(0L, input.buyer.creditBalance) else 0L
        val creditsReady = creditsOn && loggedIn && input.pricingMode == PricingMode.MARKET && cv > 0

        // the credit run's figures come from stage A; the shipping is converted here (8.1)
        val creditRun = items.credit
        val payable = creditsReady && creditRun != null && creditRun.payable
        val shippingCredits = if (shippingTotal == 0L || cv <= 0) 0L
        else ceilDiv(big(fromOrder(shippingTotal)).multiply(big(100)), big(cv)).longValueExact()
        val creditTotal = if (payable) creditRun!!.itemsTotal + shippingCredits else 0L

        var creditAmount = 0L
        var creditValue = 0L
        var maxApplicable = 0L
        var unavailable: PricingCode? = null
        val messages = ArrayList<PricingCode>()
        var refused = false

        if (payWithCredits) {
            when {
                !payable -> {
                    unavailable = when {
                        !creditsOn -> PricingCode.CREDITS_DISABLED
                        !loggedIn -> PricingCode.LOGIN_REQUIRED
                        input.pricingMode != PricingMode.MARKET -> PricingCode.EXTERNAL_PRICING
                        else -> PricingCode.NOT_PAYABLE_WITH_CREDITS
                    }
                    refused = true
                    branches += "credits refused: not payable"
                }
                balance < creditTotal -> {
                    messages += PricingCode.INSUFFICIENT_CREDITS
                    refused = true
                    branches += "credits refused: balance"
                }
                else -> {
                    creditAmount = creditTotal
                    creditValue = preFee
                    branches += "full credit"
                }
            }
        } else {
            val methodMixed = method?.mixedCredit ?: true
            val eligible = creditsReady && cfg.allowMixedCreditPayment && !cfg.onlyAcceptCredits && !creditGranting && !hasSubscription &&
                profile == PricingProfile.STOREFRONT
            val request = tender.useCredits
            val wanted = request != null && request > 0L
            if (eligible && methodMixed) {
                val providerMin = inOrder(method?.providerMin, items, fxNum, fxDen, bq, cfg, ::toOrder)
                val minRemainder = maxOf(oq, providerMin?.let { ceilDiv(big(it), big(oq)).multiply(big(oq)).longValueExact() } ?: 0L)
                val cap = preFee - minRemainder
                maxApplicable = if (cap <= 0L) 0L
                else minOf(balance, floorDiv(big(cap).multiply(big(100)).multiply(fxDen), big(cv).multiply(fxNum)).min(big(Long.MAX_VALUE)).longValueExact())
                var applied = 0L
                if (wanted) {
                    applied = when {
                        request == MixedPayment.MAX -> maxApplicable
                        request!! <= maxApplicable -> request
                        strict -> {
                            messages += PricingCode.INSUFFICIENT_CREDITS
                            branches += "mixed rejected"
                            0L
                        }
                        else -> {
                            messages += PricingCode.CREDITS_REDUCED
                            branches += "mixed clamped"
                            maxApplicable
                        }
                    }
                }
                var value = 0L
                if (applied > 0L) {
                    value = minOf(roundQ(big(applied).multiply(big(cv)).multiply(fxNum), big(100).multiply(fxDen), oq), cap)
                    if (value <= 0L) applied = 0L
                }
                creditAmount = applied
                creditValue = value
                if (applied > 0L) branches += "mixed applied"
            } else if (wanted) {
                if (strict) unavailable = PricingCode.MIXED_CREDIT_NOT_SUPPORTED else messages += PricingCode.MIXED_CREDIT_NOT_SUPPORTED
                branches += "mixed not supported"
            }
        }

        // 9.2 fee (C)
        val remainder = preFee - creditValue
        val renewal = input.renewal
        val paymentFee = when {
            payWithCredits -> 0L
            renewal != null -> renewal.paymentFee
            input.priceOverride != null -> 0L
            method == null || method.feeMode != PaymentFeeMode.BUYER || method.id in EXEMPT -> 0L
            remainder == 0L || input.pricingMode != PricingMode.MARKET -> 0L
            else -> roundQ(big(remainder).multiply(big(clampBp(method.feePercent))), big(10_000), oq) + toOrder(clampAmount(method.feeFixed))
        }
        if (paymentFee > 0L) branches += "fee"
        val feeVat = if (input.pricingMode == PricingMode.MARKET) {
            val bp = clampBp(cfg.vatBp)
            roundQ(big(paymentFee).multiply(big(bp)), big(10_000 + bp), oq)
        } else {
            0L
        }

        // 9.3 totals
        val total = preFee + paymentFee
        val vatTotal = items.itemsVat + shippingVat + feeVat
        val gateway = total - creditValue

        // 9.4 method resolution
        val methodId: String? = when {
            payWithCredits -> if (!refused && gateway == 0L && creditAmount == 0L) "free" else "credits"
            gateway == 0L && creditAmount == 0L -> "free"
            else -> method?.id
        }
        if (methodId == "free") branches += "free order"

        // 9.5 the chosen method must take this amount
        if (creditsRequired) unavailable = unavailable ?: PricingCode.CREDITS_REQUIRED
        if (!payWithCredits && unavailable == null && method != null && gateway > 0L) {
            unavailable = amountReason(method, items, currency, preFee, gateway, shippable, fxNum, fxDen, bq, cfg, ::toOrder)
            if (unavailable != null) branches += "method refused: $unavailable"
        }

        // 10 minimum order amount
        val topUp = input.lines.size == 1 && input.lines[0].kind == LineKind.CREDIT_TOPUP
        if (profile == PricingProfile.STOREFRONT && !topUp && input.priceOverride == null && cfg.minimumOrderAmount > 0L &&
            gateway > 0L && items.itemsBasis < toOrder(clampAmount(cfg.minimumOrderAmount))
        ) {
            messages += PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED
            branches += "minimum order"
        }

        return Expected(
            shippingTotal, shippingVat, preFee, creditAmount, creditValue, maxApplicable, paymentFee, feeVat, total, vatTotal, gateway,
            methodId, payWithCredits, creditsOn, payable, creditTotal, if (payable) shippingCredits else 0L, unavailable, messages, branches
        )
    }

    /** A provider limit in the order currency: the order currency itself, the base currency, or a third currency through the base. */
    private fun inOrder(
        limit: Money?, items: ItemsResult, fxNum: BigInteger, fxDen: BigInteger, bq: Long, cfg: PricingConfig, toOrder: (Long) -> Long
    ): Long? {
        if (limit == null) return null
        return when (limit.currency) {
            items.currency -> limit.amount
            cfg.baseCurrency -> toOrder(limit.amount)
            else -> {
                val rate = cfg.rates[limit.currency]?.takeIf { it.signum() > 0 } ?: return null
                // amount / rate in the base currency, rounded to its quantum, then into the order currency
                toOrder(roundQ(big(limit.amount).multiply(BigInteger.TEN.pow(rate.scale())), rate.unscaledValue(), bq))
            }
        }
    }

    private fun amountReason(
        m: MethodInput, items: ItemsResult, currency: String, preFee: Long, gateway: Long, shippable: Boolean,
        fxNum: BigInteger, fxDen: BigInteger, bq: Long, cfg: PricingConfig, toOrder: (Long) -> Long
    ): PricingCode? {
        if (m.providerCurrencies != null && currency !in m.providerCurrencies) return PricingCode.CURRENCY_NOT_SUPPORTED
        if (m.adminCurrencies != null && currency !in m.adminCurrencies) return PricingCode.CURRENCY_NOT_SUPPORTED
        if (shippable && (m.priceAuthority != PriceAuthority.MARKET || !m.physicalGoods)) return PricingCode.PHYSICAL_NOT_SUPPORTED
        if (m.minAmount != null && preFee < toOrder(clampAmount(m.minAmount))) return PricingCode.AMOUNT_BELOW_MINIMUM
        if (m.maxAmount != null && preFee > toOrder(clampAmount(m.maxAmount))) return PricingCode.AMOUNT_ABOVE_MAXIMUM
        val min = inOrder(m.providerMin, items, fxNum, fxDen, bq, cfg, toOrder)
        if (min != null && gateway < min) return PricingCode.AMOUNT_BELOW_MINIMUM
        val max = inOrder(m.providerMax, items, fxNum, fxDen, bq, cfg, toOrder)
        if (max != null && gateway > max) return PricingCode.AMOUNT_ABOVE_MAXIMUM
        return null
    }
}
