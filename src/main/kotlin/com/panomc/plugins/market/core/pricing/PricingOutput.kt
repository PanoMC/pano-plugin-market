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
    INVALID_INPUT
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
    CREDITS_REDUCED(MessageLevel.WARNING)
}

/** [level] defaults to the code's own level; a few codes are `INFO` in a specific place (05 section 14). */
data class PricingMessage(val code: PricingCode, val level: MessageLevel = code.level, val lineKey: String? = null)

/** The `DISPLAY` mode block of an order: informative figures in [currency] at [rate] (05 section 4.1). */
data class DisplayInfo(val currency: String, val rate: BigDecimal)

/** One `market_redemption(kind = DISCOUNT)` row: the winning automatic discount and what it took off the order. */
data class DiscountRedemption(val discountId: Long, val amount: Long)

/**
 * One priced line after stages A1 and A2 (05 sections 4, 5), all amounts in the order currency, price basis.
 * Bundle children are lines of their own ([OrderItemKind.BUNDLE_CHILD], [parentLineKey] set, every amount 0).
 *
 * `unitPrice * quantity` is exact: automatic discount and upgrade deduction are per unit.
 */
data class PricedLine(
    val lineKey: String,
    val kind: OrderItemKind,
    val productId: Long?,
    val variantId: Long,
    val parentLineKey: String?,
    val quantity: Int,
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
    /** `creditPrice` as is (no currency, no conversion). */
    val creditUnitPrice: Long,
    /** `CREDIT_TOPUP`: the credits x 100 bought; 0 for every other line at this stage. */
    val creditAmount: Long,
    /** Line errors of this stage (`NOT_IN_CURRENCY`); a line with errors is [excluded]. */
    val errors: List<PricingCode>,
    /** Left out of every sum (no price in the currency under `HIDE`); all its amounts are 0. */
    val excluded: Boolean
)

/**
 * Result of `PricingEngine.priceItems` up to stage A2 (automatic discount and upgrade). The later stages of 05
 * (codes, VAT and line totals, shipping, tender) extend this result; nothing here is ever recomputed by them.
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
    val discountRedemptions: List<DiscountRedemption>,
    val messages: List<PricingMessage>
) {
    val currency: String get() = conversions.orderCurrency
    val baseCurrency: String get() = conversions.baseCurrency
    val fxRate: BigDecimal get() = conversions.fx

    /** The `DISPLAY` mode block; convert a figure with [Conversions.toDisplay] (per shown figure, 05 section 4.1). */
    val display: DisplayInfo?
        get() = conversions.displayCurrency?.let { DisplayInfo(it, conversions.displayRate!!) }

    /** No line carries an error (the cart validation of the caller may still say no). */
    val canCheckout: Boolean get() = lines.none { it.errors.isNotEmpty() }
}
