package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.db.model.OrderItemKind

/**
 * The pricing engine (05 sections 1 to 12): one deterministic function family, plain Kotlin, no clock, no I/O,
 * no `Double`. Same input, same output.
 *
 * This slice (MK-060) provides stage A up to A2: order currency and list price (05 section 4) and automatic
 * discount and upgrade deduction (05 section 5). Codes, VAT and line totals (A3, A4), shipping, tender and totals
 * (B, C) are added by the following slices on top of [ItemsResult].
 */
object PricingEngine {
    /**
     * Stage A1 and A2 of [input]: every figure is exact, per unit where the spec says so, and rounded once.
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
            recipientTiers = input.buyer.recipientTiers
        )

        val lines = ArrayList<PricedLine>(listed.size)
        var discountTotal = 0L
        var upgradeTotal = 0L
        var itemsAmount = 0L
        val redeemed = sortedMapOf<Long, Long>()
        for (l in listed) {
            val outcome = DiscountStage.apply(l, settings)
            val line = priced(l, outcome)
            lines += line
            discountTotal = Math.addExact(discountTotal, line.discountAmount)
            upgradeTotal = Math.addExact(upgradeTotal, line.upgradeAmount)
            itemsAmount = Math.addExact(itemsAmount, line.lineAmount)
            if (outcome.discountId != null) redeemed.merge(outcome.discountId, line.discountAmount) { a, b -> Math.addExact(a, b) }
            if (l.line.kind == LineKind.BUNDLE && !l.excluded) lines += bundleChildren(l.line)
        }

        val messages = ArrayList<PricingMessage>(currency.messages)
        if (external) messages += PricingMessage(PricingCode.EXTERNAL_PRICING, MessageLevel.INFO)

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
            discountRedemptions = redeemed.map { DiscountRedemption(it.key, it.value) },
            messages = messages
        )
    }

    /** `unitPrice = listUnitPrice - unitDiscount - upgradeAmount`, `lineAmount = unitPrice * quantity` (05 section 5.3). */
    private fun priced(l: ListedLine, o: DiscountOutcome): PricedLine {
        val line = l.line
        val quantity = line.quantity.toLong()
        val unitPrice = l.listUnitPrice - o.unitDiscount - o.upgradeUnitAmount
        check(unitPrice >= 0) { "line '${line.lineKey}': negative unit price $unitPrice (list ${l.listUnitPrice}, discount ${o.unitDiscount}, upgrade ${o.upgradeUnitAmount})" }
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
            lineAmount = Math.multiplyExact(unitPrice, quantity),
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
                creditUnitPrice = 0L,
                creditAmount = 0L,
                errors = emptyList(),
                excluded = false
            )
        }
}
