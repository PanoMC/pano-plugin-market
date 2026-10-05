package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.db.model.OrderItemKind
import java.math.BigDecimal

/** Why the engine refuses an input. Both map to HTTP 400 `INVALID_CART` at the endpoints (05 section 14). */
enum class PricingError {
    /** `Math.multiplyExact` / `addExact` / `longValueExact` overflowed: the cart is out of bounds. */
    AMOUNT_OVERFLOW,

    /** The caller broke the input contract of 05 section 2 / 3 (a bug of the caller, not a business case). */
    INVALID_INPUT,

    /** A panel `priceOverride` above the gross list total of the lines (05 section 12; the endpoint answers 400 `BAD_REQUEST`). */
    PRICE_OVERRIDE_OUT_OF_RANGE
}

class PricingException(val error: PricingError, message: String, cause: Throwable? = null) :
    RuntimeException("$error: $message", cause)

enum class MessageLevel { INFO, WARNING, ERROR }

/** The codes of 05 section 14 (message, line error, code reason or unavailable reason). */
enum class PricingCode(val level: MessageLevel) {
    NOT_IN_CURRENCY(MessageLevel.ERROR),
    CREDITS_ONLY(MessageLevel.ERROR),
    NOT_PAYABLE_WITH_CREDITS(MessageLevel.ERROR),
    CODE_NOT_FOUND(MessageLevel.ERROR),
    CODE_NOT_COMBINABLE(MessageLevel.ERROR),
    CODE_NOT_STARTED(MessageLevel.ERROR),
    CODE_EXPIRED(MessageLevel.ERROR),
    CODE_LIMIT_REACHED(MessageLevel.ERROR),
    CODE_MIN_AMOUNT(MessageLevel.ERROR),
    COUPON_NOT_APPLICABLE(MessageLevel.ERROR),
    EXTERNAL_PRICING(MessageLevel.ERROR),
    CURRENCY_NOT_SUPPORTED(MessageLevel.WARNING),
    MIXED_CREDIT_NOT_SUPPORTED(MessageLevel.WARNING),
    AMOUNT_BELOW_MINIMUM(MessageLevel.INFO),
    AMOUNT_ABOVE_MAXIMUM(MessageLevel.INFO),
    PHYSICAL_NOT_SUPPORTED(MessageLevel.INFO),
    MINIMUM_ORDER_AMOUNT_NOT_REACHED(MessageLevel.ERROR),
    INSUFFICIENT_CREDITS(MessageLevel.ERROR),
    LOGIN_REQUIRED(MessageLevel.ERROR),
    CREDITS_REDUCED(MessageLevel.WARNING),

    /** Pay-with-credits was asked while credits are switched off (07 section 6.1 rule F1; checkout: `PAYMENT_METHOD_UNAVAILABLE`). */
    CREDITS_DISABLED(MessageLevel.ERROR),

    /** `onlyAcceptCredits`: a product cart is paid with credits and the buyer chose a gateway (07 section 13; checkout: `PAYMENT_METHOD_UNAVAILABLE`). */
    CREDITS_REQUIRED(MessageLevel.ERROR)
}

/** [level] defaults to the code's own level; a few codes are `INFO` in a specific place (05 section 14). */
data class PricingMessage(val code: PricingCode, val level: MessageLevel = code.level, val lineKey: String? = null)

/** The `DISPLAY` mode block of an order: informative figures in [currency] at [rate] (05 section 4.1). */
data class DisplayInfo(val currency: String, val rate: BigDecimal)

/** One `market_redemption(kind = DISCOUNT)` row: the winning automatic discount and what it took off the order. */
data class DiscountRedemption(val discountId: Long, val amount: Long)

/**
 * What stage A3 decided about a coupon or a creator code (05 section 6.3): `Quote.coupon` / `Quote.creatorCode`.
 *
 * [valid] is false when the code is refused ([reason] says why) and for a coupon that the combine rule of 05 section
 * 6.4 gave up (`CODE_NOT_COMBINABLE`). A creator code stays valid when only checks 8 or 10 zero its discount: [reason]
 * then carries that info code and [discount] is 0 (attribution and commission are kept). [discount] is what the code
 * took off the order, order currency, price basis (`couponDiscount` / `creatorDiscount`).
 */
data class CodeOutcome(
    /** Row id of the code, null when it was not found. */
    val id: Long?,
    val code: String,
    val valid: Boolean,
    val reason: PricingCode?,
    val discount: Long
)

/**
 * One priced line after stages A1 to A4 (05 sections 4 to 7), all amounts in the order currency, price basis unless
 * the field says otherwise. Bundle children are lines of their own ([OrderItemKind.BUNDLE_CHILD], [parentLineKey]
 * set, every amount 0).
 *
 * `unitPrice * quantity` is exact: automatic discount and upgrade deduction are per unit. Coupon and creator-code
 * shares, VAT and line totals are per line (00 section 6.6).
 */
data class PricedLine(
    val lineKey: String,
    val kind: OrderItemKind,
    val productId: Long?,
    val variantId: Long,
    val parentLineKey: String?,
    val quantity: Int,
    /** What the line was in the cart; [kind] folds `CREDIT_PACK` into `PRODUCT`, this keeps them apart (earning and cashback skip packs). */
    val lineKind: LineKind,
    /** Catalogue unit price in the order currency, quantised. */
    val listUnitPrice: Long,
    /** The winning automatic discount (null when none, or under the `GIFT_CODE` profile). */
    val discountId: Long?,
    /** Automatic discount per unit. */
    val unitDiscount: Long,
    /** `unitDiscount * quantity`. */
    val discountAmount: Long,
    /** Tier deduction per unit. */
    val upgradeUnitAmount: Long,
    /** `upgradeUnitAmount * quantity` (`market_order_item.upgradeAmount`). */
    val upgradeAmount: Long,
    /** The owned lower-tier entitlement this line replaces (set in both upgrade modes). */
    val upgradeFromEntitlementId: Long?,
    /** `listUnitPrice - unitDiscount - upgradeUnitAmount`, never negative. */
    val unitPrice: Long,
    /** `unitPrice * quantity`, price basis, before coupons and creator codes. */
    val lineAmount: Long,
    /** The coupon's share of this line (05 section 6.2), 0 when there is none. */
    val couponShare: Long,
    /** The creator code's share of this line, taken from what the coupon left. */
    val creatorShare: Long,
    /** `couponShare + creatorShare` (`market_order_item.couponAmount`). */
    val couponAmount: Long,
    /** `lineAmount - couponAmount`: the final amount of the line in the price basis (VAT-inclusive or net). */
    val lineBasis: Long,
    /** Stored `market_order_item.vatPercent` in basis points: the rate used, 0 when the gateway adds the tax or sets the price. */
    val vatPercent: Long,
    /** VAT contained in [lineTotal] (0 when the gateway adds the tax or sets the price). */
    val vatAmount: Long,
    /** What the buyer pays for the line (05 section 7). */
    val lineTotal: Long,
    /**
     * The credit run's unit price after the automatic discount and the upgrade deduction (05 section 8.1); `creditPrice`
     * as is when no credit run was made (credits off, a profile without credits). No currency, no conversion.
     */
    val creditUnitPrice: Long,
    /** `CREDIT_TOPUP`: the credits x 100 bought; 0 for every other line at this stage. */
    val creditAmount: Long,
    /** Line errors of this stage (`NOT_IN_CURRENCY`); a line with errors is [excluded]. */
    val errors: List<PricingCode>,
    /** Left out of every sum (no price in the currency under `HIDE`); all its amounts are 0. */
    val excluded: Boolean
)

/**
 * Result of `PricingEngine.priceItems` (stage A, 05 sections 4 to 7): currency and list price, automatic discount and
 * upgrade, coupon and creator code, VAT and line totals, plus the credit run of section 8.1. The later stages of 05
 * (shipping, tender, totals: `PricingEngine.finalize`) extend this result; nothing here is ever recomputed by them.
 *
 * Identities (asserted by the engine before it returns; a violation is an `IllegalStateException`, a bug):
 * `sum(lineBasis) = subtotal - discountTotal - upgradeDiscount - couponDiscount - creatorDiscount`, and in `MARKET`
 * pricing mode `itemsTotal = sum(lineBasis) + (pricesIncludeVat ? 0 : itemsVat)`.
 */
data class ItemsResult(
    val conversions: Conversions,
    val currencyMode: CurrencyMode,
    val pricingMode: PricingMode,
    val pricesIncludeVat: Boolean,
    val lines: List<PricedLine>,
    /** `sum(listUnitPrice * quantity)` over the priced (not excluded) lines, price basis. */
    val subtotal: Long,
    /** `sum(discountAmount)`. */
    val discountTotal: Long,
    /** `sum(upgradeAmount)`. */
    val upgradeDiscount: Long,
    /** `sum(lineAmount)`: the merchandise after automatic discount and upgrade, before codes. */
    val itemsAmount: Long,
    /** `sum(couponShare)`. */
    val couponDiscount: Long,
    /** `sum(creatorShare)`. */
    val creatorDiscount: Long,
    /** `sum(lineBasis)`: the merchandise after every discount, price basis, before shipping, fee and VAT on top. */
    val itemsBasis: Long,
    /** `fromOrder(itemsBasis)`: what the rate engine's free-shipping threshold and `AMOUNT` rows look at (03 section 2.4). */
    val itemsBasisBase: Long,
    /** `sum(lineBasis)` of the physical lines. */
    val physicalBasis: Long,
    /** `fromOrder(physicalBasis)`. */
    val physicalBasisBase: Long,
    /** `sum(lineTotal)`. */
    val itemsTotal: Long,
    /** `sum(vatAmount)`. */
    val itemsVat: Long,
    /** Any priced line is physical. */
    val requiresShipping: Boolean,
    /** Outcome of the coupon the caller passed, null when none was passed. */
    val coupon: CodeOutcome?,
    /** Outcome of the creator code the caller passed, null when none was passed. */
    val creatorCode: CodeOutcome?,
    val discountRedemptions: List<DiscountRedemption>,
    val messages: List<PricingMessage>,
    /** The profile the cart was priced under (05 section 12). */
    val profile: PricingProfile,
    /** The whole order is paid in credits (05 section 8.1): asked for, or forced by `onlyAcceptCredits`. */
    val payWithCredits: Boolean,
    /** [payWithCredits] was not asked for: `onlyAcceptCredits` put a product cart on credits (07 section 13). */
    val creditsForced: Boolean,
    /** A panel `priceOverride` set the totals (05 section 12): such an order has no shipping and no fee. */
    val priceOverridden: Boolean,
    /** The credit run (05 section 8.1); null when credits are off or the profile / pricing mode has none. */
    val credit: CreditRun?,
    /** What stages B and C need from the config and the buyer. */
    val terms: TenderTerms
) {
    val currency: String get() = conversions.orderCurrency
    val baseCurrency: String get() = conversions.baseCurrency
    val fxRate: BigDecimal get() = conversions.fx

    /** The `DISPLAY` mode block; convert a figure with [Conversions.toDisplay] (per shown figure, 05 section 4.1). */
    val display: DisplayInfo?
        get() = conversions.displayCurrency?.let { DisplayInfo(it, conversions.displayRate!!) }

    /**
     * The pricing part of `canCheckout` (05 section 10): no line error, no error-level message, and every code that
     * was passed is valid (the cart validation of the caller may still say no).
     */
    val canCheckout: Boolean
        get() = lines.none { it.errors.isNotEmpty() } &&
            messages.none { it.level == MessageLevel.ERROR } &&
            coupon?.valid != false &&
            creatorCode?.valid != false
}
