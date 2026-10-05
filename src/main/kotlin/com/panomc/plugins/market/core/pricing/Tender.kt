package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.db.model.PaymentFeeMode
import java.math.BigDecimal
import java.math.RoundingMode

/** The frozen part of an order that stage C works on: items, shipping and what is known about the buyer's credits. */
internal class TenderBase(
    val conversions: Conversions,
    val pricingMode: PricingMode,
    val profile: PricingProfile,
    val itemsTotal: Long,
    val itemsVat: Long,
    val shippingTotal: Long,
    val shippingVat: Long,
    /** `config.vatBp`: the VAT contained in the payment fee. */
    val vatBp: Long,
    val creditsEnabled: Boolean,
    val onlyAcceptCredits: Boolean,
    val allowMixedCreditPayment: Boolean,
    val loggedIn: Boolean,
    val creditBalance: Long,
    /** No credit purchase and no subscription line (rule M4 of 07 section 6.2). */
    val mixedCreditCart: Boolean,
    /** The items in credits when the order is payable in credits, else null. */
    val creditItemsTotal: Long?,
    /** The fee frozen on a renewal order (05 section 12), else null. */
    val renewalFee: Long?,
    /** A panel `priceOverride` sets the total: shipping and fee are 0 (05 section 12). */
    val noFee: Boolean,
    val rates: Map<String, BigDecimal>
)

/** One tender to price. [fullCredit]: the whole order in credits. [keep]: a pending order keeps this credit part (credits, value). */
internal class TenderRequest(
    val useCredits: Long?,
    val method: MethodInput?,
    val strict: Boolean,
    val fullCredit: Boolean,
    val keep: Pair<Long, Long>? = null
)

/**
 * Stages B and C of 05 (sections 8 to 10): shipping, credit tender (full and mixed), the payment fee and the totals.
 * Pure: the items and the shipping are given as figures and are never changed here.
 */
internal object Tender {
    /** 05 section 9.1: the shipping total and its VAT, in the price basis of the store, rounded once. */
    class ShippingFigures(val total: Long, val vatPercent: Long, val vat: Long)

    fun shipping(price: Long, vatBp: Long?, configVatBp: Long, includesVat: Boolean, oq: Long): ShippingFigures {
        if (price < 0 || price > PricingLimits.MAX_AMOUNT) {
            throw PricingException(PricingError.INVALID_INPUT, "shipping price $price is out of bounds")
        }
        val bp = DiscountStage.clampBp(vatBp ?: configVatBp)
        val charge = Rounding.roundQ(price, oq)
        return if (includesVat) {
            ShippingFigures(charge, bp, Rounding.vatInside(charge, bp, oq))
        } else {
            val vat = Rounding.vatOnTop(charge, bp, oq)
            ShippingFigures(Math.addExact(charge, vat), bp, vat)
        }
    }

    /** `ceil(fromOrder(shippingTotal) x 100 / cv)`: the shipping of a full-credit order, rounded up to 0.01 credit (05 section 8.1). */
    fun shippingCredits(c: Conversions, shippingTotal: Long): Long {
        if (shippingTotal == 0L || c.creditValue <= 0L) return 0L
        return BigDecimal.valueOf(c.fromOrder(shippingTotal)).multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(c.creditValue), 0, RoundingMode.CEILING)
            .longValueExact()
    }

    fun compute(b: TenderBase, r: TenderRequest): TenderBreakdown {
        val c = b.conversions
        val oq = c.oq
        val method = r.method
        val messages = ArrayList<PricingMessage>()
        var unavailable: PricingCode? = null
        val preFee = Math.addExact(b.itemsTotal, b.shippingTotal)

        val creditsReady = b.creditsEnabled && b.loggedIn && b.pricingMode == PricingMode.MARKET && c.creditValue > 0L
        val shippingCredits = shippingCredits(c, b.shippingTotal)
        val payable = creditsReady && b.creditItemsTotal != null
        val creditTotal = if (payable) Math.addExact(b.creditItemsTotal!!, shippingCredits) else 0L

        var creditAmount = 0L
        var creditValue = 0L
        var maxApplicable = 0L
        var creditRefused = false

        if (r.fullCredit) {
            // 05 section 8.1: the credit run is authoritative; the money total is the record value
            when {
                !payable -> {
                    unavailable = when {
                        !b.creditsEnabled -> PricingCode.CREDITS_DISABLED
                        !b.loggedIn -> PricingCode.LOGIN_REQUIRED
                        b.pricingMode != PricingMode.MARKET -> PricingCode.EXTERNAL_PRICING
                        else -> PricingCode.NOT_PAYABLE_WITH_CREDITS
                    }
                    creditRefused = true
                }
                b.creditBalance < creditTotal -> {
                    messages += PricingMessage(PricingCode.INSUFFICIENT_CREDITS)
                    creditRefused = true
                }
                else -> {
                    creditAmount = creditTotal
                    creditValue = preFee
                }
            }
        } else {
            val methodMixed = method?.mixedCredit ?: true // no method chosen yet: assumed capable (07 section 6.2 M5)
            val eligible = creditsReady && b.allowMixedCreditPayment && !b.onlyAcceptCredits && b.mixedCreditCart &&
                b.profile == PricingProfile.STOREFRONT
            val wanted = r.useCredits != null && r.useCredits > 0L
            val mixed = if (eligible && methodMixed) {
                val minimum = ProviderLimits.inOrderCurrency(method?.providerMin, c, b.rates)
                // a pending order that keeps its credit part is not re-asked: only the maximum is reported
                MixedPayment.apply(c, preFee, minimum, b.creditBalance, if (r.keep != null) null else r.useCredits, r.strict)
            } else {
                null
            }
            maxApplicable = mixed?.maxApplicable ?: 0L
            when {
                r.keep != null -> {
                    if (r.keep.first > 0L && !methodMixed) {
                        unavailable = PricingCode.MIXED_CREDIT_NOT_SUPPORTED
                    } else {
                        creditAmount = r.keep.first
                        creditValue = r.keep.second
                    }
                }
                mixed != null -> {
                    creditAmount = mixed.applied
                    creditValue = mixed.appliedValue
                    if (mixed.reduced) messages += PricingMessage(PricingCode.CREDITS_REDUCED)
                    if (mixed.rejected) messages += PricingMessage(PricingCode.INSUFFICIENT_CREDITS)
                }
                wanted -> {
                    // credits asked for where mixed payment does not apply: the quote ignores them, checkout refuses
                    if (r.strict) unavailable = PricingCode.MIXED_CREDIT_NOT_SUPPORTED
                    else messages += PricingMessage(PricingCode.MIXED_CREDIT_NOT_SUPPORTED)
                }
            }
        }
        if (creditValue > preFee) {
            throw PricingException(PricingError.INVALID_INPUT, "the credit part $creditValue is above the total $preFee")
        }

        // 05 section 9.2: the fee is a plain surcharge on what the gateway collects after the credit part
        val remainder = Math.subtractExact(preFee, creditValue)
        val paymentFee = when {
            r.fullCredit -> 0L
            b.renewalFee != null -> b.renewalFee
            b.noFee -> 0L
            method == null || method.feeMode != PaymentFeeMode.BUYER || method.feeExempt -> 0L
            remainder == 0L || b.pricingMode != PricingMode.MARKET -> 0L
            else -> Math.addExact(
                Rounding.pctQ(remainder, DiscountStage.clampBp(method.feePercent), oq),
                c.toOrder(DiscountStage.clampAmount(method.feeFixed))
            )
        }
        val feeVatPercent = DiscountStage.clampBp(b.vatBp)
        val feeVat = if (b.pricingMode == PricingMode.MARKET) Rounding.vatInside(paymentFee, feeVatPercent, oq) else 0L

        // 05 section 9.3
        val total = Math.addExact(preFee, paymentFee)
        val vatTotal = Math.addExact(Math.addExact(b.itemsVat, b.shippingVat), feeVat)
        val gatewayAmount = Math.subtractExact(total, creditValue)

        // 05 section 9.4: a free order has no provider, an order the credits cover is the credits provider
        val methodId: String? = when {
            r.fullCredit -> if (!creditRefused && gatewayAmount == 0L && creditAmount == 0L) MethodInput.FREE else MethodInput.CREDITS
            gatewayAmount == 0L && creditAmount == 0L -> MethodInput.FREE
            else -> method?.id
        }

        check(total == b.itemsTotal + b.shippingTotal + paymentFee) { "stage C identity broken: total $total" }
        check(gatewayAmount + creditValue == total) { "stage C identity broken: gateway $gatewayAmount + credit value $creditValue != $total" }
        check(r.fullCredit || creditAmount == 0L || gatewayAmount > 0L || r.keep != null) {
            "a mixed order must leave a gateway remainder: gateway $gatewayAmount"
        }

        val credits = if (!b.creditsEnabled) null else CreditQuote(
            balance = b.creditBalance,
            payableInCredits = payable,
            creditTotal = creditTotal,
            shippingCredits = if (payable) shippingCredits else 0L,
            maxApplicable = maxApplicable,
            applied = creditAmount,
            appliedValue = creditValue
        )
        return TenderBreakdown(
            paymentMethodId = methodId,
            preFee = preFee,
            creditAmount = creditAmount,
            creditValue = creditValue,
            paymentFee = paymentFee,
            paymentFeeVatPercent = feeVatPercent,
            paymentFeeVatAmount = feeVat,
            total = total,
            vatTotal = vatTotal,
            gatewayAmount = gatewayAmount,
            credits = credits,
            messages = messages,
            unavailable = unavailable
        )
    }

    // ---------------------------------------------------------------- stage B and C of a priced cart

    private fun bad(condition: Boolean, message: () -> String) {
        if (condition) throw PricingException(PricingError.INVALID_INPUT, message())
    }

    private fun shipsUnder(items: ItemsResult): Boolean =
        (items.profile == PricingProfile.STOREFRONT || items.profile == PricingProfile.PANEL) && !items.priceOverridden

    private fun shippingOf(items: ItemsResult, shipping: ShippingCharge?): ShippingFigures {
        if (!shipsUnder(items) || !items.requiresShipping || shipping == null) return ShippingFigures(0L, 0L, 0L)
        return shipping(shipping.price, shipping.vatBp, items.terms.vatBp, items.pricesIncludeVat, items.conversions.oq)
    }

    private fun baseOf(items: ItemsResult, ship: ShippingFigures, pricingMode: PricingMode): TenderBase {
        val t = items.terms
        val creditProfile = items.profile == PricingProfile.STOREFRONT || items.profile == PricingProfile.INGAME
        return TenderBase(
            conversions = items.conversions,
            pricingMode = pricingMode,
            profile = items.profile,
            itemsTotal = items.itemsTotal,
            itemsVat = items.itemsVat,
            shippingTotal = ship.total,
            shippingVat = ship.vat,
            vatBp = t.vatBp,
            creditsEnabled = t.creditsEnabled && creditProfile,
            onlyAcceptCredits = t.onlyAcceptCredits,
            allowMixedCreditPayment = t.allowMixedCreditPayment,
            loggedIn = t.loggedIn,
            creditBalance = t.creditBalance,
            mixedCreditCart = !t.creditGranting && !t.hasSubscription,
            creditItemsTotal = items.credit?.takeIf { it.payable }?.itemsTotal,
            renewalFee = t.renewalFee,
            noFee = items.priceOverridden,
            rates = t.rates
        )
    }

    fun finalize(items: ItemsResult, shipping: ShippingCharge?, tender: TenderInput): PriceBreakdown {
        val asked = tender.method
        val profile = items.profile
        // a buyer who asked for credits and a gateway is a caller bug; a store that only takes credits and a gateway method on a
        // product cart is the buyer's choice: the quote is the full-credit one and the choice is refused (CREDITS_REQUIRED)
        val creditsRequired = items.payWithCredits && asked != null && asked.id != MethodInput.CREDITS && items.creditsForced
        val method = if (creditsRequired) null else asked
        if (items.payWithCredits) {
            bad(method != null && method.id != MethodInput.CREDITS) { "payWithCredits together with the payment method '${method?.id}'" }
        } else {
            bad(method?.id == MethodInput.CREDITS) { "the credits method needs payWithCredits in priceItems" }
            bad(profile == PricingProfile.INGAME) { "an in-game purchase is always paid with credits" }
        }
        if (method != null && method.id != MethodInput.CREDITS) {
            bad(method.pricingMode != items.pricingMode) {
                "method '${method.id}' prices in ${method.pricingMode}, the items were priced in ${items.pricingMode}"
            }
        }
        bad(profile == PricingProfile.RENEWAL && items.terms.renewalFee == null) { "a renewal is finalized with its frozen charge" }

        val ship = shippingOf(items, shipping)
        val base = baseOf(items, ship, items.pricingMode)
        var tb = compute(base, TenderRequest(tender.useCredits, method, tender.strict, items.payWithCredits))
        if (creditsRequired) tb = tb.copy(unavailable = tb.unavailable ?: PricingCode.CREDITS_REQUIRED)
        // 05 section 9.4 / 9.5: the chosen method must be able to take this amount, in this currency, for these goods
        if (!items.payWithCredits) tb = withSelectedMethod(tb, method, items.conversions, items.requiresShipping, items.terms.rates)

        // 05 section 10: the minimum order amount looks at the merchandise after discounts and is checked only where a gateway is paid
        val t = items.terms
        if (profile == PricingProfile.STOREFRONT && !t.creditTopUp && !items.priceOverridden && t.minimumOrderAmount > 0L &&
            tb.gatewayAmount > 0L &&
            items.itemsBasis < items.conversions.toOrder(DiscountStage.clampAmount(t.minimumOrderAmount))
        ) {
            tb = tb.copy(messages = tb.messages + PricingMessage(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED))
        }

        val missing = shipsUnder(items) && items.requiresShipping && shipping == null
        return PriceBreakdown(items, ship.total, ship.vatPercent, ship.vat, tb, missing)
    }

    fun retender(f: FrozenOrder, tender: TenderInput): TenderBreakdown {
        val method = tender.method
        val fullCredit = method?.id == MethodInput.CREDITS
        val base = TenderBase(
            conversions = f.conversions,
            pricingMode = f.pricingMode,
            profile = f.profile,
            itemsTotal = f.itemsTotal,
            itemsVat = f.itemsVat,
            shippingTotal = f.shippingTotal,
            shippingVat = f.shippingVat,
            vatBp = f.vatBp,
            creditsEnabled = f.creditsEnabled,
            onlyAcceptCredits = f.onlyAcceptCredits,
            allowMixedCreditPayment = f.allowMixedCreditPayment,
            loggedIn = f.loggedIn,
            creditBalance = f.creditBalance,
            mixedCreditCart = f.mixedCreditCart,
            creditItemsTotal = f.creditItemsTotal,
            renewalFee = f.renewalFee,
            noFee = false,
            rates = f.rates
        )
        // 05 section 9.6: no useCredits keeps the credit part as it is; switching away from a full-credit order drops it
        val keep = if (tender.useCredits == null && !fullCredit) {
            if (f.currentMethodId == MethodInput.CREDITS) 0L to 0L else f.creditAmount to f.creditValue
        } else {
            null
        }
        val tb = compute(base, TenderRequest(tender.useCredits, method, strict = true, fullCredit = fullCredit, keep = keep))
        // a method that prices differently would change the item amounts: refused, never re-priced here
        val modeChanged = method != null && !fullCredit && method.pricingMode != f.pricingMode
        if (modeChanged) return if (tb.unavailable == null) tb.copy(unavailable = PricingCode.EXTERNAL_PRICING) else tb
        // the kept or requested credit part may leave the new gateway below its own limits (05 section 9.5 check 6)
        return if (fullCredit) tb else withSelectedMethod(tb, method, f.conversions, f.requiresShipping, f.rates)
    }

    fun evaluate(items: ItemsResult, shipping: ShippingCharge?, tender: TenderInput, methods: List<MethodInput>): List<MethodEvaluation> {
        val t = items.terms
        // 07 section 13: with onlyAcceptCredits a cart that is not a credit purchase is paid with credits only
        if (t.onlyAcceptCredits && !t.creditGranting) return emptyList()
        val ship = shippingOf(items, shipping)
        val c = items.conversions
        val wantsCredits = tender.useCredits != null && tender.useCredits > 0L
        return methods.map { m ->
            val tb = compute(
                baseOf(items, ship, m.pricingMode),
                TenderRequest(tender.useCredits, m, strict = false, fullCredit = false)
            )
            val reason = unavailableReason(items, m, tb, c, wantsCredits)
            MethodEvaluation(m.id, m.pricingMode, tb.paymentFee, tb.gatewayAmount, reason == null, reason)
        }
    }

    private fun unavailableReason(items: ItemsResult, m: MethodInput, tb: TenderBreakdown, c: Conversions, wantsCredits: Boolean): PricingCode? =
        amountReason(m, tb, c, items.requiresShipping, items.terms.rates, wantsCredits)

    /**
     * The amount-related checks of 05 section 9.5, in the order of the table; the first failing one is the reason.
     * [wantsCredits] switches check 3 (a method that cannot take a credit part) on: the list of methods needs it, while the
     * tender of an order has its own rule for it (`compute`).
     */
    private fun amountReason(
        m: MethodInput, tb: TenderBreakdown, c: Conversions, requiresShipping: Boolean, rates: Map<String, BigDecimal>, wantsCredits: Boolean
    ): PricingCode? {
        val currency = c.orderCurrency
        if (m.providerCurrencies != null && currency !in m.providerCurrencies) return PricingCode.CURRENCY_NOT_SUPPORTED
        if (m.adminCurrencies != null && currency !in m.adminCurrencies) return PricingCode.CURRENCY_NOT_SUPPORTED
        if (requiresShipping && (m.pricingMode != PricingMode.MARKET || !m.physicalGoods)) return PricingCode.PHYSICAL_NOT_SUPPORTED
        if (wantsCredits && !m.mixedCredit) return PricingCode.MIXED_CREDIT_NOT_SUPPORTED
        // the admin window is the cart total before the fee and before credits
        if (m.minAmount != null && tb.preFee < c.toOrder(DiscountStage.clampAmount(m.minAmount))) return PricingCode.AMOUNT_BELOW_MINIMUM
        if (m.maxAmount != null && tb.preFee > c.toOrder(DiscountStage.clampAmount(m.maxAmount))) return PricingCode.AMOUNT_ABOVE_MAXIMUM
        // the provider's hard limits are the amount the gateway collects, fee included
        val min = ProviderLimits.inOrderCurrency(m.providerMin, c, rates)
        if (min != null && tb.gatewayAmount < min) return PricingCode.AMOUNT_BELOW_MINIMUM
        val max = ProviderLimits.inOrderCurrency(m.providerMax, c, rates)
        if (max != null && tb.gatewayAmount > max) return PricingCode.AMOUNT_ABOVE_MAXIMUM
        return null
    }

    /**
     * The method a tender names must be one the order may use (05 section 9.4: a gateway amount needs an available
     * method). Only where a gateway is paid: a free order and a credit-only order ignore the method. The credit rules of the
     * tender itself come first; a method that prices differently is refused by the callers before this runs.
     */
    private fun withSelectedMethod(tb: TenderBreakdown, m: MethodInput?, c: Conversions, requiresShipping: Boolean, rates: Map<String, BigDecimal>): TenderBreakdown {
        if (m == null || tb.unavailable != null || tb.gatewayAmount <= 0L || tb.paymentMethodId != m.id) return tb
        val reason = amountReason(m, tb, c, requiresShipping, rates, wantsCredits = false) ?: return tb
        return tb.copy(unavailable = reason)
    }
}
