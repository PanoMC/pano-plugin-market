package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Currencies

/** The bounds of 05 section 2 rule 2, enforced by the caller and checked again by the engine. */
object PricingLimits {
    const val MAX_QUANTITY = 100_000

    /** Largest unit price / credit price / top-up amount, x100 scale. */
    const val MAX_AMOUNT = 1_000_000_000_000L
}

/**
 * Refuses an input that breaks the contract of 05 sections 2 and 3. These are bugs of the caller (the cart
 * validation clamps quantities, the catalogue validates prices), never business outcomes: a quote does not fail
 * for business reasons, so every business case is a message or a line error instead.
 */
internal object PricingValidator {
    fun check(input: PricingInput) {
        val config = input.config
        bad(!Currencies.isSupported(config.baseCurrency)) { "unsupported base currency '${config.baseCurrency}'" }

        val keys = HashSet<String>()
        for (line in input.lines) {
            val key = line.lineKey
            bad(!keys.add(key)) { "duplicate lineKey '$key'" }
            bad(line.quantity !in 1..PricingLimits.MAX_QUANTITY) { "line '$key': quantity ${line.quantity} is out of 1..${PricingLimits.MAX_QUANTITY}" }
            bad(line.basePrice !in 0..PricingLimits.MAX_AMOUNT) { "line '$key': base price ${line.basePrice} is out of bounds" }
            bad(line.creditPrice !in 0..PricingLimits.MAX_AMOUNT) { "line '$key': credit price ${line.creditPrice} is out of bounds" }
            for ((currency, price) in line.currencyPrices) {
                bad(price !in 0..PricingLimits.MAX_AMOUNT) { "line '$key': $currency price $price is out of bounds" }
            }
            bad(line.tier != null && line.quantity != 1) { "line '$key': a tiered line has quantity 1" }

            when (line.kind) {
                LineKind.CREDIT_TOPUP -> {
                    val credits = line.topUpCredits
                    bad(line.productId != null) { "line '$key': a credit top-up has no product" }
                    bad(line.quantity != 1) { "line '$key': a credit top-up has quantity 1" }
                    bad(credits == null || credits !in 1..PricingLimits.MAX_AMOUNT) { "line '$key': top-up credits $credits are out of bounds" }
                    bad(input.lines.size != 1) { "a credit top-up is the only line of its cart" }
                    bad(config.creditValue <= 0) { "a credit top-up needs a positive credit value" }
                    bad(input.profile == PricingProfile.GIFT_CODE) { "a gift code cannot redeem a credit top-up" }
                    bad(line.subscription || line.tier != null) { "line '$key': a credit top-up is neither a subscription nor tiered" }
                }
                LineKind.BUNDLE -> {
                    bad(line.productId == null) { "line '$key': a bundle needs its product id" }
                    for (child in line.children) {
                        bad(child.quantity !in 1..PricingLimits.MAX_QUANTITY) { "line '$key': bundle child quantity ${child.quantity} is out of bounds" }
                        bad(child.quantity.toLong() * line.quantity > Int.MAX_VALUE) { "line '$key': bundle child quantity overflows" }
                    }
                }
                else -> bad(line.productId == null) { "line '$key': a product line needs its product id" }
            }
        }

        for (owned in input.buyer.recipientTiers) {
            bad(owned.pricePaid < 0) { "owned tier ${owned.entitlementId}: negative price paid" }
        }

        // 05 section 12: only the storefront takes codes; another profile that receives one is a caller bug, not a quote
        bad((input.coupon != null || input.creatorCode != null) && !input.profile.codes) {
            "the ${input.profile} profile takes no coupon or creator code"
        }

        bad(input.buyer.creditBalance < 0) { "the credit balance is negative" }
        // 05 section 8.1: a whole order in credits is a storefront or in-game purchase; a renewal switches to credits on /pay (retender)
        bad(input.payWithCredits && input.profile != PricingProfile.STOREFRONT && input.profile != PricingProfile.INGAME) {
            "the ${input.profile} profile is never paid with credits"
        }

        val override = input.priceOverride
        if (override != null) {
            bad(input.profile != PricingProfile.PANEL) { "priceOverride is for the PANEL profile only" }
            bad(override < 0) { "priceOverride is negative" }
            bad(input.pricingMode != PricingMode.MARKET) { "priceOverride sets a gross total: the order must be priced by the market" }
        }

        val renewal = input.renewal
        if (renewal != null) {
            bad(input.profile != PricingProfile.RENEWAL) { "a renewal charge is for the RENEWAL profile only" }
            bad(input.lines.size != 1 || input.lines[0].quantity != 1) { "a renewal is one line of quantity 1" }
            bad(input.lines[0].kind != LineKind.PRODUCT) { "a renewal line is a product" }
            bad(renewal.price !in 0..PricingLimits.MAX_AMOUNT) { "renewal price ${renewal.price} is out of bounds" }
            bad(renewal.paymentFee !in 0..renewal.price) { "renewal fee ${renewal.paymentFee} is not part of the price ${renewal.price}" }
        }
    }

    private inline fun bad(condition: Boolean, message: () -> String) {
        if (condition) throw PricingException(PricingError.INVALID_INPUT, message())
    }
}
