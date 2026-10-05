package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.db.model.OrderItemKind

/**
 * The pricing engine (05 sections 1 to 12): one deterministic function family, plain Kotlin, no clock, no I/O,
 * no `Double`. Same input, same output.
 *
 * Stage A (`priceItems`): order currency and list price (A1, 05 section 4), automatic discount and upgrade deduction
 * (A2, section 5), coupon and creator code per line with the combine rule (A3, section 6) and VAT and line totals
 * (A4, section 7), plus the credit run of section 8.1 and the panel `priceOverride` and renewal totals of section 12.
 * Stages B and C (`finalize`, `retender`, `evaluateMethods`): shipping, credit tender (full and mixed), the payment fee
 * and the totals (sections 8 to 10); item amounts are never touched again after stage A.
 */
object PricingEngine {
    /**
     * Stages B and C on a priced cart (05 sections 8 to 10): shipping from the rate engine's [shipping] charge, the credit
     * tender and the gateway fee of [tender], the totals. Item amounts stay exactly as [items] has them.
     *
     * Throws [PricingException] when the combination breaks the contract: a method that prices in another mode than
     * [items], the credits method without `payWithCredits`, an in-game purchase without credits, a renewal without its
     * frozen charge, a negative or oversized shipping price.
     */
    fun finalize(items: ItemsResult, shipping: ShippingCharge?, tender: TenderInput): PriceBreakdown =
        arithmetic { Tender.finalize(items, shipping, tender) }

    /**
     * Stage C alone on a `PENDING` order (05 section 9.6, `POST /orders/:publicId/pay`): the frozen item and shipping
     * amounts stay, the method, fee and credit part change. Always strict (a number above what can be applied is
     * refused, never clamped). Returns the new tender; [TenderBreakdown.unavailable] says when the order cannot go there.
     * A full-credit order (`currentMethodId == credits`) never leaves the credits method: any other request is answered with
     * `CREDITS_REQUIRED` and the credit part as it is (06 section 9.3 step 2).
     */
    fun retender(frozen: FrozenOrder, tender: TenderInput): TenderBreakdown = arithmetic { Tender.retender(frozen, tender) }

    /**
     * The amount-related availability and the fee of every method of [methods] (05 section 9.5), for the quote's
     * payment method list. The other checks (enabled, configured, guests, recurring, test mode) belong to the caller.
     * Empty when `onlyAcceptCredits` is on and the cart is not a credit purchase.
     */
    fun evaluateMethods(items: ItemsResult, shipping: ShippingCharge?, tender: TenderInput, methods: List<MethodInput>): List<MethodEvaluation> =
        arithmetic { Tender.evaluate(items, shipping, tender, methods) }

    private fun <T> arithmetic(block: () -> T): T =
        try {
            block()
        } catch (e: ArithmeticException) {
            throw PricingException(PricingError.AMOUNT_OVERFLOW, e.message ?: "arithmetic overflow", e)
        }

    /**
     * Stage A (A1 to A4) of [input]: every figure is exact, per unit where the spec says so, per line for codes and VAT,
     * and rounded once.
     *
     * Throws [PricingException] when the input breaks the contract ([PricingError.INVALID_INPUT]) or an amount does
     * not fit a `Long` ([PricingError.AMOUNT_OVERFLOW], HTTP 400 `INVALID_CART`).
     */
    fun priceItems(input: PricingInput): ItemsResult =
        try {
            compute(input)
        } catch (e: ArithmeticException) {
            throw PricingException(PricingError.AMOUNT_OVERFLOW, e.message ?: "arithmetic overflow", e)
        }

    private fun compute(input: PricingInput): ItemsResult {
        PricingValidator.check(input)
        val config = input.config
        val profile = input.profile

        // A1: currency and list price
        val currency = OrderCurrencies.resolve(config, input.requestedCurrency)
        val conversions = Conversions(
            baseCurrency = config.baseCurrency,
            orderCurrency = currency.currency,
            fx = currency.fx,
            creditValue = config.creditValue,
            removeCents = config.removeCents,
            displayCurrency = currency.displayCurrency,
            displayRate = currency.displayRate
        )
        val oq = conversions.oq
        // a renewal charges what the subscription froze (05 section 12): its one line is priced from that, not from the catalogue
        val renewal = input.renewal?.let { RenewalFigures.of(input, conversions) }
        val listed = if (renewal != null) {
            listOf(ListedLine(input.lines.single(), renewal.basis, excluded = false, errors = emptyList()))
        } else {
            input.lines.map { ListPrice.list(it, conversions, config) }
        }
        var subtotal = 0L
        for (l in listed) subtotal = Math.addExact(subtotal, Math.multiplyExact(l.listUnitPrice, l.line.quantity.toLong()))

        // A2: automatic discount and upgrade deduction, per unit
        val external = input.pricingMode == PricingMode.EXTERNAL
        val overridden = input.priceOverride != null
        val settings = DiscountStage.Settings(
            unit = AmountUnit(oq, conversions::toOrder),
            now = input.now,
            subtotal = subtotal,
            discounts = input.discounts,
            discountsEnabled = profile.discounts && !external && !overridden,
            fullGift = profile == PricingProfile.GIFT_CODE,
            upgradeLink = profile.upgrade && !overridden,
            upgradeDeduction = profile.upgrade && !overridden && !external,
            recipientTiers = input.buyer.recipientTiers,
            upgradeClaimants = DiscountStage.upgradeClaimants(listed)
        )
        val codeSettings = CodeStage.Settings(settings.unit, input.now, external, input.buyer, input.coupon, input.creatorCode)
        val withCodes = input.coupon != null || input.creatorCode != null

        // 05 section 8.1: the credit run is stage A in credits; when the whole order is paid in credits it decides, the money run replays
        val creditGranting = listed.any { !it.excluded && (it.line.kind == LineKind.CREDIT_PACK || it.line.kind == LineKind.CREDIT_TOPUP) }
        val hasSubscription = listed.any { !it.excluded && it.line.subscription }
        val onlyCredits = config.creditsEnabled && config.onlyAcceptCredits
        val forcedByStore = profile == PricingProfile.STOREFRONT && onlyCredits && !creditGranting && listed.isNotEmpty()
        val payWithCredits = input.payWithCredits || forcedByStore
        val credit = if ((profile == PricingProfile.STOREFRONT || profile == PricingProfile.INGAME) && config.creditsEnabled &&
            config.creditValue > 0L && input.pricingMode == PricingMode.MARKET
        ) {
            creditPass(input, conversions, listed, settings, codeSettings, withCodes)
        } else {
            null
        }
        val problems = listed.map { creditProblem(it) }
        val payable = credit != null && input.buyer.loggedIn && problems.none { it }
        val replay = if (payWithCredits && payable) {
            Decisions(
                listed.indices.associate { listed[it].line.lineKey to credit!!.scenario.outcomes[it].discountId },
                CodeReplay.of(credit!!.scenario.codes)
            )
        } else {
            null
        }

        // A3: coupon and creator code per line, with the combine rule of 05 section 6.4
        val chosen = runStageA(listed, settings, codeSettings, withCodes, config.combineDiscountsAndCoupons, replay)

        // A4: VAT and line totals (or the panel's price override, or the renewal's frozen charge)
        val figures = when {
            input.priceOverride != null -> overrideFigures(input, conversions, listed, input.priceOverride)
            else -> listed.indices.map { normalFigures(listed[it], chosen.outcomes[it], chosen.codes.couponShares[it], chosen.codes.creatorShares[it], input, oq, renewal) }
        }
        val lines = ArrayList<PricedLine>(listed.size)
        var discountTotal = 0L
        var upgradeTotal = 0L
        var itemsAmount = 0L
        var itemsBasis = 0L
        var physicalBasis = 0L
        var itemsTotal = 0L
        var itemsVat = 0L
        var requiresShipping = false
        val redeemed = sortedMapOf<Long, Long>()
        for ((i, l) in listed.withIndex()) {
            val outcome = chosen.outcomes[i]
            val line = priced(l, outcome, figures[i], extraError(input, l, payWithCredits, creditGranting, onlyCredits, problems[i]), credit?.unitPrice(i))
            lines += line
            discountTotal = Math.addExact(discountTotal, line.discountAmount)
            upgradeTotal = Math.addExact(upgradeTotal, line.upgradeAmount)
            itemsAmount = Math.addExact(itemsAmount, line.lineAmount)
            itemsBasis = Math.addExact(itemsBasis, line.lineBasis)
            itemsTotal = Math.addExact(itemsTotal, line.lineTotal)
            itemsVat = Math.addExact(itemsVat, line.vatAmount)
            if (shippable(l)) {
                physicalBasis = Math.addExact(physicalBasis, line.lineBasis)
                requiresShipping = true
            }
            if (outcome.discountId != null) redeemed.merge(outcome.discountId, line.discountAmount) { a, b -> Math.addExact(a, b) }
            if (l.line.kind == LineKind.BUNDLE && !l.excluded) lines += bundleChildren(l.line)
        }
        val couponDiscount = chosen.codes.couponDiscount
        val creatorDiscount = chosen.codes.creatorDiscount

        // 05 section 7: the engine proves its own identities before it returns (a violation is a bug, never an input case)
        check(itemsBasis == subtotal - discountTotal - upgradeTotal - couponDiscount - creatorDiscount) {
            "stage A identity broken: basis $itemsBasis, subtotal $subtotal, discount $discountTotal, upgrade $upgradeTotal, " +
                "coupon $couponDiscount, creator $creatorDiscount"
        }
        if (input.pricingMode == PricingMode.MARKET) {
            check(itemsTotal == itemsBasis + (if (config.pricesIncludeVat) 0L else itemsVat)) {
                "stage A identity broken: total $itemsTotal, basis $itemsBasis, vat $itemsVat, includesVat ${config.pricesIncludeVat}"
            }
        }

        val messages = ArrayList<PricingMessage>(currency.messages)
        if (external) messages += PricingMessage(PricingCode.EXTERNAL_PRICING, MessageLevel.INFO)
        mirror(chosen.codes.coupon, messages)
        mirror(chosen.codes.creatorCode, messages)
        if (payWithCredits) {
            // 05 section 8.1 preconditions that are not about a line
            if (!config.creditsEnabled) messages += PricingMessage(PricingCode.CREDITS_DISABLED)
            if (!input.buyer.loggedIn) messages += PricingMessage(PricingCode.LOGIN_REQUIRED)
            if (input.pricingMode != PricingMode.MARKET) messages += PricingMessage(PricingCode.EXTERNAL_PRICING)
        }

        val creditRun = credit?.let { cp ->
            val creditLines = listed.indices.map { cp.line(it) }
            CreditRun(
                lines = creditLines,
                itemsTotal = creditLines.fold(0L) { a, l -> Math.addExact(a, l.lineTotal) },
                payable = payable,
                coupon = cp.scenario.codes.coupon,
                creatorCode = cp.scenario.codes.creatorCode
            )
        }

        return ItemsResult(
            conversions = conversions,
            currencyMode = config.currencyMode,
            pricingMode = input.pricingMode,
            pricesIncludeVat = config.pricesIncludeVat,
            lines = lines,
            subtotal = subtotal,
            discountTotal = discountTotal,
            upgradeDiscount = upgradeTotal,
            itemsAmount = itemsAmount,
            couponDiscount = couponDiscount,
            creatorDiscount = creatorDiscount,
            itemsBasis = itemsBasis,
            itemsBasisBase = conversions.fromOrder(itemsBasis),
            physicalBasis = physicalBasis,
            physicalBasisBase = conversions.fromOrder(physicalBasis),
            itemsTotal = itemsTotal,
            itemsVat = itemsVat,
            requiresShipping = requiresShipping,
            coupon = chosen.codes.coupon,
            creatorCode = chosen.codes.creatorCode,
            discountRedemptions = redeemed.map { DiscountRedemption(it.key, it.value) },
            messages = messages,
            profile = profile,
            payWithCredits = payWithCredits,
            creditsForced = payWithCredits && !input.payWithCredits,
            priceOverridden = overridden,
            credit = creditRun,
            terms = TenderTerms(
                vatBp = config.vatBp,
                minimumOrderAmount = config.minimumOrderAmount,
                creditsEnabled = config.creditsEnabled,
                onlyAcceptCredits = config.onlyAcceptCredits,
                allowMixedCreditPayment = config.allowMixedCreditPayment,
                loggedIn = input.buyer.loggedIn,
                creditBalance = if (input.buyer.loggedIn) maxOf(0L, input.buyer.creditBalance) else 0L,
                creditGranting = creditGranting,
                hasSubscription = hasSubscription,
                creditTopUp = listed.size == 1 && listed[0].line.kind == LineKind.CREDIT_TOPUP,
                rates = config.rates,
                cashbackBp = config.cashbackBp,
                renewalFee = input.renewal?.paymentFee
            )
        )
    }

    // ---------------------------------------------------------------- stage A runs

    /** One run of A2 and A3: the per-line automatic discount outcomes, what the codes did, and the merchandise left (S1 / S2 of 05 section 6.4). */
    private class Scenario(val outcomes: List<DiscountOutcome>, val codes: CodeResult, val basis: Long)

    /** What the credit run decided; the money run of a full-credit order takes the same winners and the same code outcomes (05 section 8.1). */
    private class Decisions(val winners: Map<String, Long?>, val codes: CodeReplay)

    /** The credit run's lines and chosen scenario; amounts are credits x 100. */
    private class CreditPass(val listed: List<ListedLine>, val scenario: Scenario) {
        fun unitPrice(i: Int): Long = unitPrice(listed[i], scenario.outcomes[i])

        fun line(i: Int): CreditLine {
            val l = listed[i]
            val unitPrice = unitPrice(i)
            val lineAmount = Math.multiplyExact(unitPrice, l.line.quantity.toLong())
            val coupon = Math.addExact(scenario.codes.couponShares[i], scenario.codes.creatorShares[i])
            return CreditLine(l.line.lineKey, unitPrice, lineAmount, coupon, lineAmount - coupon)
        }
    }

    /** Stage A2 and A3 for [listed] in the unit of [settings], with the combine rule; or the replay of a credit run's [replay]. */
    private fun runStageA(
        listed: List<ListedLine>,
        settings: DiscountStage.Settings,
        codeSettings: CodeStage.Settings,
        withCodes: Boolean,
        combine: Boolean,
        replay: Decisions?
    ): Scenario {
        fun scenario(discounts: DiscountStage.Settings, suppressed: Boolean, codesReplay: CodeReplay?): Scenario {
            val outcomes = listed.map { DiscountStage.apply(it, discounts) }
            val amounts = listed.mapIndexed { i, l -> lineAmount(l, outcomes[i]) }
            val codes = if (withCodes) {
                CodeStage.apply(listed.mapIndexed { i, l -> CodeLine(l.line, l.excluded, amounts[i]) }, codeSettings, suppressed, codesReplay)
            } else {
                CodeResult.none(listed.size)
            }
            var basis = 0L
            for (a in amounts) basis = Math.addExact(basis, a)
            basis = Math.subtractExact(Math.subtractExact(basis, codes.couponDiscount), codes.creatorDiscount)
            return Scenario(outcomes, codes, basis)
        }

        if (replay != null) return scenario(settings.replaying(replay.winners), suppressed = false, codesReplay = replay.codes)
        val stacked = scenario(settings, suppressed = false, codesReplay = null)
        return chooseScenario(
            combine, withCodes, stacked,
            codesInstead = { scenario(settings.withoutDiscounts(), suppressed = false, codesReplay = null) }, // S2
            discountsInstead = { scenario(settings, suppressed = true, codesReplay = null) } // S1
        )
    }

    /** The credit run (05 section 8.1): the same stage A in credits, `q = 1`, list price `creditPrice`, base amounts through `moneyToCredits`. */
    private fun creditPass(
        input: PricingInput,
        conversions: Conversions,
        listed: List<ListedLine>,
        money: DiscountStage.Settings,
        moneyCodes: CodeStage.Settings,
        withCodes: Boolean
    ): CreditPass {
        val unit = AmountUnit(1L, conversions::moneyToCredits)
        val creditListed = listed.map { ListedLine(it.line, if (it.excluded) 0L else it.line.creditPrice, it.excluded, it.errors) }
        var creditSubtotal = 0L
        for (l in creditListed) creditSubtotal = Math.addExact(creditSubtotal, Math.multiplyExact(l.listUnitPrice, l.line.quantity.toLong()))
        val settings = DiscountStage.Settings(
            unit = unit,
            now = input.now,
            subtotal = creditSubtotal,
            discounts = input.discounts,
            discountsEnabled = money.discountsEnabled,
            fullGift = false,
            upgradeLink = money.upgradeLink,
            upgradeDeduction = money.upgradeDeduction,
            recipientTiers = input.buyer.recipientTiers,
            upgradeClaimants = money.upgradeClaimants
        )
        val codes = CodeStage.Settings(unit, input.now, moneyCodes.external, input.buyer, input.coupon, input.creatorCode)
        return CreditPass(creditListed, runStageA(creditListed, settings, codes, withCodes, input.config.combineDiscountsAndCoupons, null))
    }

    /**
     * A line that cannot be paid in credits (05 section 8.1): a credit purchase, or a product without a credit price that is not free.
     * A hidden line is out of every sum and never a problem.
     */
    private fun creditProblem(l: ListedLine): Boolean {
        if (l.excluded) return false
        return when (l.line.kind) {
            LineKind.CREDIT_PACK, LineKind.CREDIT_TOPUP -> true
            else -> !(l.line.creditPrice > 0L || l.listUnitPrice == 0L)
        }
    }

    /**
     * The line error of this stage beyond `NOT_IN_CURRENCY` (05 section 8.1). A whole-order credit payment that asks for a
     * line that has no credit price: `NOT_PAYABLE_WITH_CREDITS`. A money quote of a storefront cart: a product that is
     * priced in credits only (price 0, credit price above 0, or any product of an `onlyAcceptCredits` store next to a credit
     * purchase) must never become free: `CREDITS_ONLY`.
     */
    private fun extraError(
        input: PricingInput, l: ListedLine, payWithCredits: Boolean, creditGranting: Boolean, onlyCredits: Boolean, problem: Boolean
    ): PricingCode? {
        if (l.excluded) return null
        if (payWithCredits) return if (problem) PricingCode.NOT_PAYABLE_WITH_CREDITS else null
        if (input.profile != PricingProfile.STOREFRONT) return null
        val product = l.line.kind == LineKind.PRODUCT || l.line.kind == LineKind.BUNDLE
        if (!product) return null
        val creditsOnly = (l.listUnitPrice == 0L && l.line.creditPrice > 0L) || (onlyCredits && creditGranting)
        return if (creditsOnly) PricingCode.CREDITS_ONLY else null
    }

    /**
     * A priced line that ships (05 section 9.1, 10 section 2): a physical product, or a bundle with at least one physical
     * child (the bundle's own flag is forced to 0, so its children carry the physical lines; the whole bundle basis is the
     * shippable value, 10 section 2.2). A hidden line is not priced and never ships.
     */
    private fun shippable(l: ListedLine): Boolean =
        !l.excluded && (l.line.physical || (l.line.kind == LineKind.BUNDLE && l.line.children.any { it.physical }))

    /**
     * The combine rule (05 section 6.4). With `combineDiscountsAndCoupons` the automatic discounts and the codes stack:
     * the [stacked] run is the result. Without it the order takes either the automatic discounts (S1) or the codes (S2),
     * whichever is cheaper for the buyer, a tie keeping the automatic discounts (the coupon is not consumed).
     *
     * There is nothing to choose when no line has an automatic discount, or when no code takes anything off in S0 (the
     * stacked run) or in S2. Looking at S2 as well as at S0 matters for a code whose minimum amount or eligible line
     * exists only without the automatic discounts: it is checked "on S2 amounts" (05 section 6.4) and can win there.
     */
    private fun chooseScenario(
        combine: Boolean,
        withCodes: Boolean,
        stacked: Scenario,
        codesInstead: () -> Scenario,
        discountsInstead: () -> Scenario
    ): Scenario {
        if (!withCodes || combine || stacked.outcomes.none { it.unitDiscount > 0L }) return stacked
        val s2 = codesInstead()
        if (!stacked.codes.hasDiscount && !s2.codes.hasDiscount) return stacked
        val s1 = discountsInstead()
        return if (s2.basis < s1.basis) s2 else s1
    }

    /** A code reason is mirrored in `messages` (05 section 14); `EXTERNAL_PRICING` is already reported once on the quote itself. */
    private fun mirror(outcome: CodeOutcome?, into: MutableList<PricingMessage>) {
        val reason = outcome?.reason ?: return
        if (reason == PricingCode.EXTERNAL_PRICING) return
        into += PricingMessage(reason, if (outcome.valid) MessageLevel.INFO else MessageLevel.ERROR)
    }

    /** `unitPrice = listUnitPrice - unitDiscount - upgradeAmount` (05 section 5.3), never negative. */
    private fun unitPrice(l: ListedLine, o: DiscountOutcome): Long {
        val unitPrice = l.listUnitPrice - o.unitDiscount - o.upgradeUnitAmount
        check(unitPrice >= 0) { "line '${l.line.lineKey}': negative unit price $unitPrice (list ${l.listUnitPrice}, discount ${o.unitDiscount}, upgrade ${o.upgradeUnitAmount})" }
        return unitPrice
    }

    private fun lineAmount(l: ListedLine, o: DiscountOutcome): Long = Math.multiplyExact(unitPrice(l, o), l.line.quantity.toLong())

    /** What one line is worth after stages A2 to A4 (05 sections 5 to 7), apart from the discount and upgrade outcome. */
    private class Figures(
        val unitDiscount: Long,
        val discountAmount: Long,
        val unitPrice: Long,
        val lineAmount: Long,
        val couponShare: Long,
        val creatorShare: Long,
        val lineBasis: Long,
        val vat: LineVat
    )

    /** The usual line: `unitPrice x quantity`, the shares of the codes, VAT of 05 section 7 (or the renewal's frozen split). */
    private fun normalFigures(
        l: ListedLine, o: DiscountOutcome, couponShare: Long, creatorShare: Long, input: PricingInput, quantum: Long, renewal: RenewalFigures?
    ): Figures {
        val quantity = l.line.quantity.toLong()
        val unitPrice = unitPrice(l, o)
        val lineAmount = Math.multiplyExact(unitPrice, quantity)
        val couponAmount = Math.addExact(couponShare, creatorShare)
        val lineBasis = lineAmount - couponAmount
        check(lineBasis >= 0) { "line '${l.line.lineKey}': codes take $couponAmount off $lineAmount" }
        val vat = renewal?.vat ?: VatStage.line(l, lineBasis, input.pricingMode, input.config.pricesIncludeVat, input.config.vatBp, quantum)
        return Figures(o.unitDiscount, Math.multiplyExact(o.unitDiscount, quantity), unitPrice, lineAmount, couponShare, creatorShare, lineBasis, vat)
    }

    /**
     * The panel's `priceOverride` (05 section 12): a gross total that is spread over the lines by largest remainder
     * (`allocate(override, grossList, oq)`), so the order costs exactly what the admin typed. There is no automatic
     * discount, upgrade or code; what the override takes off the list is the line's `discountAmount`, in the price basis.
     * `unitPrice` is informative here (`lineBasis / quantity` rounded to the quantum, so `unitPrice x quantity` may differ
     * from the line); the VAT is the part contained in the line's gross total, which keeps `lineTotal` exact.
     */
    private fun overrideFigures(input: PricingInput, conversions: Conversions, listed: List<ListedLine>, override: Long): List<Figures> {
        val oq = conversions.oq
        val config = input.config
        val gross = listed.map { l ->
            if (l.excluded) {
                0L
            } else {
                val list = Math.multiplyExact(l.listUnitPrice, l.line.quantity.toLong())
                if (config.pricesIncludeVat) list else Math.addExact(list, Rounding.vatOnTop(list, vatBp(l, config), oq))
            }
        }
        val total = gross.fold(0L) { a, b -> Math.addExact(a, b) }
        val amount = Rounding.roundQ(override, oq)
        if (amount > total) {
            throw PricingException(PricingError.PRICE_OVERRIDE_OUT_OF_RANGE, "the price override $amount is above the gross list total $total")
        }
        val shares = Rounding.allocate(amount, gross, oq)
        return listed.mapIndexed { i, l ->
            if (l.excluded) return@mapIndexed Figures(0L, 0L, 0L, 0L, 0L, 0L, 0L, LineVat.NONE)
            val bp = vatBp(l, config)
            val lineTotal = shares[i]
            val vat = Rounding.vatInside(lineTotal, bp, oq)
            val basis = if (config.pricesIncludeVat) lineTotal else lineTotal - vat
            val list = Math.multiplyExact(l.listUnitPrice, l.line.quantity.toLong())
            val unitPrice = Rounding.ratioQ(java.math.BigDecimal.valueOf(basis), java.math.BigDecimal.valueOf(l.line.quantity.toLong()), oq)
            check(basis <= list && unitPrice <= l.listUnitPrice) { "price override: line '${l.line.lineKey}' costs $basis, list $list" }
            Figures(l.listUnitPrice - unitPrice, list - basis, unitPrice, basis, 0L, 0L, basis, LineVat(bp, vat, lineTotal))
        }
    }

    private fun vatBp(l: ListedLine, config: PricingConfig): Long = DiscountStage.clampBp(l.line.vatBp ?: config.vatBp)

    /**
     * What a renewal order charges (05 section 12): the frozen gross `price` less the frozen fee is the line total; the VAT
     * contained in it is back-calculated at the current rate (`vatInside`), so only the VAT split can change, never the total.
     * Under `EXTERNAL_TAX` / `EXTERNAL` the gateway handles the tax and the line carries none.
     */
    private class RenewalFigures(val basis: Long, val vat: LineVat) {
        companion object {
            fun of(input: PricingInput, c: Conversions): RenewalFigures {
                val renewal = input.renewal!!
                val total = renewal.price - renewal.paymentFee
                if (input.pricingMode != PricingMode.MARKET) return RenewalFigures(total, LineVat(0L, 0L, total))
                val bp = DiscountStage.clampBp(input.lines.single().vatBp ?: input.config.vatBp)
                val vat = Rounding.vatInside(total, bp, c.oq)
                return RenewalFigures(if (input.config.pricesIncludeVat) total else total - vat, LineVat(bp, vat, total))
            }
        }
    }

    /** One output line from its [Figures] (stage A2 per unit, A3 shares, A4 VAT and total). */
    private fun priced(l: ListedLine, o: DiscountOutcome, f: Figures, extraError: PricingCode?, creditUnitPrice: Long?): PricedLine {
        val line = l.line
        val quantity = line.quantity.toLong()
        return PricedLine(
            lineKey = line.lineKey,
            kind = when (line.kind) {
                LineKind.PRODUCT, LineKind.CREDIT_PACK -> OrderItemKind.PRODUCT
                LineKind.BUNDLE -> OrderItemKind.BUNDLE
                LineKind.CREDIT_TOPUP -> OrderItemKind.CREDIT_TOPUP
            },
            productId = line.productId,
            variantId = line.variantId,
            parentLineKey = null,
            quantity = line.quantity,
            lineKind = line.kind,
            listUnitPrice = l.listUnitPrice,
            discountId = o.discountId,
            unitDiscount = f.unitDiscount,
            discountAmount = f.discountAmount,
            upgradeUnitAmount = o.upgradeUnitAmount,
            upgradeAmount = Math.multiplyExact(o.upgradeUnitAmount, quantity),
            upgradeFromEntitlementId = o.upgradeFromEntitlementId,
            unitPrice = f.unitPrice,
            lineAmount = f.lineAmount,
            couponShare = f.couponShare,
            creatorShare = f.creatorShare,
            couponAmount = Math.addExact(f.couponShare, f.creatorShare),
            lineBasis = f.lineBasis,
            vatPercent = f.vat.vatPercent,
            vatAmount = f.vat.vatAmount,
            lineTotal = f.vat.lineTotal,
            creditUnitPrice = if (l.excluded) 0L else creditUnitPrice ?: line.creditPrice,
            creditAmount = if (line.kind == LineKind.CREDIT_TOPUP) line.topUpCredits!! else 0L,
            errors = if (extraError == null) l.errors else l.errors + extraError,
            excluded = l.excluded
        )
    }

    /** Every child of a bundle is a `BUNDLE_CHILD` line: quantity `child * bundle`, every amount 0 (05 section 4.2). */
    private fun bundleChildren(bundle: LineInput): List<PricedLine> =
        bundle.children.mapIndexed { index, child ->
            PricedLine(
                lineKey = "${bundle.lineKey}/c$index",
                kind = OrderItemKind.BUNDLE_CHILD,
                productId = child.productId,
                variantId = child.variantId,
                parentLineKey = bundle.lineKey,
                quantity = Math.multiplyExact(child.quantity, bundle.quantity),
                lineKind = LineKind.PRODUCT,
                listUnitPrice = 0L,
                discountId = null,
                unitDiscount = 0L,
                discountAmount = 0L,
                upgradeUnitAmount = 0L,
                upgradeAmount = 0L,
                upgradeFromEntitlementId = null,
                unitPrice = 0L,
                lineAmount = 0L,
                couponShare = 0L,
                creatorShare = 0L,
                couponAmount = 0L,
                lineBasis = 0L,
                vatPercent = 0L,
                vatAmount = 0L,
                lineTotal = 0L,
                creditUnitPrice = 0L,
                creditAmount = 0L,
                errors = emptyList(),
                excluded = false
            )
        }
}
