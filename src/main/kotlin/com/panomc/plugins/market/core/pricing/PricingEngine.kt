package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.db.model.OrderItemKind

/**
 * The pricing engine (05 sections 1 to 12): one deterministic function family, plain Kotlin, no clock, no I/O,
 * no `Double`. Same input, same output.
 *
 * Stage A is complete here: order currency and list price (A1, 05 section 4), automatic discount and upgrade deduction
 * (A2, section 5), coupon and creator code per line with the combine rule (A3, section 6) and VAT and line totals
 * (A4, section 7). Shipping, tender and totals (B, C) are added by the following slices on top of [ItemsResult].
 */
object PricingEngine {
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
        val listed = input.lines.map { ListPrice.list(it, conversions, config) }
        var subtotal = 0L
        for (l in listed) subtotal = Math.addExact(subtotal, Math.multiplyExact(l.listUnitPrice, l.line.quantity.toLong()))

        // A2: automatic discount and upgrade deduction, per unit
        val external = input.pricingMode == PricingMode.EXTERNAL
        val overridden = input.priceOverride != null
        val settings = DiscountStage.Settings(
            unit = AmountUnit(conversions.oq, conversions::toOrder),
            now = input.now,
            subtotal = subtotal,
            discounts = input.discounts,
            discountsEnabled = input.profile.discounts && !external && !overridden,
            fullGift = input.profile == PricingProfile.GIFT_CODE,
            upgradeLink = input.profile.upgrade && !overridden,
            upgradeDeduction = input.profile.upgrade && !overridden && !external,
            recipientTiers = input.buyer.recipientTiers,
            upgradeClaimants = DiscountStage.upgradeClaimants(listed)
        )

        // A3: coupon and creator code per line, with the combine rule of 05 section 6.4
        val codeSettings = CodeStage.Settings(settings.unit, input.now, external, input.buyer, input.coupon, input.creatorCode)
        val withCodes = input.coupon != null || input.creatorCode != null

        fun scenario(discounts: DiscountStage.Settings, suppressed: Boolean): Scenario {
            val outcomes = listed.map { DiscountStage.apply(it, discounts) }
            val amounts = listed.mapIndexed { i, l -> lineAmount(l, outcomes[i]) }
            val codes = if (withCodes) {
                CodeStage.apply(listed.mapIndexed { i, l -> CodeLine(l.line, l.excluded, amounts[i]) }, codeSettings, suppressed)
            } else {
                CodeResult.none(listed.size)
            }
            var basis = 0L
            for (a in amounts) basis = Math.addExact(basis, a)
            basis = Math.subtractExact(Math.subtractExact(basis, codes.couponDiscount), codes.creatorDiscount)
            return Scenario(outcomes, codes, basis)
        }

        val stacked = scenario(settings, suppressed = false)
        val chosen = chooseScenario(
            config, withCodes, stacked,
            codesInstead = { scenario(settings.withoutDiscounts(), suppressed = false) }, // S2
            discountsInstead = { scenario(settings, suppressed = true) } // S1
        )

        // A4: VAT and line totals
        val oq = conversions.oq
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
            val line = priced(l, outcome, chosen.codes.couponShares[i], chosen.codes.creatorShares[i], input, oq)
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
            messages = messages
        )
    }

    /**
     * A priced line that ships (05 section 9.1, 10 section 2): a physical product, or a bundle with at least one physical
     * child (the bundle's own flag is forced to 0, so its children carry the physical lines; the whole bundle basis is the
     * shippable value, 10 section 2.2). A hidden line is not priced and never ships.
     */
    private fun shippable(l: ListedLine): Boolean =
        !l.excluded && (l.line.physical || (l.line.kind == LineKind.BUNDLE && l.line.children.any { it.physical }))

    /** One run of A2 and A3: the per-line automatic discount outcomes, what the codes did, and the merchandise left (S1 / S2 of 05 section 6.4). */
    private class Scenario(val outcomes: List<DiscountOutcome>, val codes: CodeResult, val basis: Long)

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
        config: PricingConfig,
        withCodes: Boolean,
        stacked: Scenario,
        codesInstead: () -> Scenario,
        discountsInstead: () -> Scenario
    ): Scenario {
        if (!withCodes || config.combineDiscountsAndCoupons || stacked.outcomes.none { it.unitDiscount > 0L }) return stacked
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

    /** One output line: stage A2 (per unit), A3 (shares) and A4 (VAT, total). */
    private fun priced(l: ListedLine, o: DiscountOutcome, couponShare: Long, creatorShare: Long, input: PricingInput, quantum: Long): PricedLine {
        val line = l.line
        val quantity = line.quantity.toLong()
        val unitPrice = unitPrice(l, o)
        val lineAmount = Math.multiplyExact(unitPrice, quantity)
        val couponAmount = Math.addExact(couponShare, creatorShare)
        val lineBasis = lineAmount - couponAmount
        check(lineBasis >= 0) { "line '${line.lineKey}': codes take $couponAmount off $lineAmount" }
        val vat = VatStage.line(l, lineBasis, input.pricingMode, input.config.pricesIncludeVat, input.config.vatBp, quantum)
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
            listUnitPrice = l.listUnitPrice,
            discountId = o.discountId,
            unitDiscount = o.unitDiscount,
            discountAmount = Math.multiplyExact(o.unitDiscount, quantity),
            upgradeUnitAmount = o.upgradeUnitAmount,
            upgradeAmount = Math.multiplyExact(o.upgradeUnitAmount, quantity),
            upgradeFromEntitlementId = o.upgradeFromEntitlementId,
            unitPrice = unitPrice,
            lineAmount = lineAmount,
            couponShare = couponShare,
            creatorShare = creatorShare,
            couponAmount = couponAmount,
            lineBasis = lineBasis,
            vatPercent = vat.vatPercent,
            vatAmount = vat.vatAmount,
            lineTotal = vat.lineTotal,
            creditUnitPrice = if (l.excluded) 0L else line.creditPrice,
            creditAmount = if (line.kind == LineKind.CREDIT_TOPUP) line.topUpCredits!! else 0L,
            errors = l.errors,
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
