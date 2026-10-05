package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import java.math.BigDecimal

/*
 * The result models of the credit run (05 section 8.1) and of stages B and C (05 sections 9 and 10): shipping, tender,
 * fee and totals. Every figure is exact `Long` x 100 in the order currency, except where the field says credits.
 */

/** One priced line of the credit run (05 section 8.1), credits x 100, no VAT. */
data class CreditLine(
    val lineKey: String,
    /** `creditPrice - unitDiscount - upgradeUnitAmount`, the credit unit price after the automatic discount and the upgrade. */
    val unitPrice: Long,
    /** `unitPrice * quantity`. */
    val lineAmount: Long,
    /** The coupon and creator-code shares of this line, in credits. */
    val couponAmount: Long,
    /** `lineAmount - couponAmount`: what the line costs in credits. */
    val lineTotal: Long
)

/**
 * The credit run (05 section 8.1): stage A priced in credits (list price `creditPrice`, FIXED amounts, thresholds and the
 * upgrade deduction converted with `moneyToCredits`, no VAT, no fee). It is authoritative for what a full-credit buyer
 * pays; its decisions are replayed by the money run.
 */
data class CreditRun(
    /** One entry per priced cart line (bundle children excluded), in cart order. */
    val lines: List<CreditLine>,
    /** `sum(lineTotal)`, credits x 100, before the shipping that finalize converts. */
    val itemsTotal: Long,
    /**
     * The order could be paid in credits as far as the cart goes: credits on, buyer logged in, `MARKET` pricing, no
     * credit purchase in the cart and every line either has a credit price or is free (05 section 8.1 preconditions).
     * The balance is checked by `finalize`, because the shipping is part of the total.
     */
    val payable: Boolean,
    /** The coupon and creator code as the credit run decided them (what a full-credit order is bound to). */
    val coupon: CodeOutcome?,
    val creatorCode: CodeOutcome?
)

/** What stages B and C take from the config and the buyer; carried by [ItemsResult] so that `finalize` needs nothing else. */
data class TenderTerms(
    /** `config.vatBp`: the rate of the payment fee's VAT and the default of the shipping VAT. */
    val vatBp: Long,
    /** Base currency, price basis. */
    val minimumOrderAmount: Long,
    val creditsEnabled: Boolean,
    val onlyAcceptCredits: Boolean,
    val allowMixedCreditPayment: Boolean,
    val loggedIn: Boolean,
    /** Credits x 100; 0 for a guest. */
    val creditBalance: Long,
    /** The cart has a `CREDIT_PACK` or `CREDIT_TOPUP` line. */
    val creditGranting: Boolean,
    /** The cart has a subscription line. */
    val hasSubscription: Boolean,
    /** The cart is the free-amount credit top-up (no minimum order amount). */
    val creditTopUp: Boolean,
    /** `market_currency_rate`, for the provider limits that are stated in a third currency. */
    val rates: Map<String, BigDecimal>,
    val cashbackBp: Long,
    /** The fee frozen on a renewal order (05 section 12); null for every other profile. */
    val renewalFee: Long?
)

/** What the buyer can pay with credits (`Quote.credits`), credits x 100 unless the field says money. */
data class CreditQuote(
    val balance: Long,
    /** The cart can be paid entirely in credits (the alternative to a gateway), balance not considered. */
    val payableInCredits: Boolean,
    /** What the whole order costs in credits, shipping included; 0 when it is not [payableInCredits]. */
    val creditTotal: Long,
    /** The shipping part of [creditTotal]: `ceil(fromOrder(shippingTotal) x 100 / cv)`. */
    val shippingCredits: Long,
    /** The most credits a mixed payment can take (05 section 8.2); 0 when mixed payment does not apply. */
    val maxApplicable: Long,
    /** Credits this order spends; money value in [appliedValue] (order currency). */
    val applied: Long,
    val appliedValue: Long
)

/**
 * Stage C: how the order is paid (05 sections 8 and 9). Also what `retender` returns for a pending order: the item and
 * shipping figures are frozen there and only these change.
 */
data class TenderBreakdown(
    /** `free`, `credits`, the chosen method, or null when no method was chosen and a gateway amount is due. */
    val paymentMethodId: String?,
    /** `itemsTotal + shippingTotal`: the total without the payment fee (the admin window's base). */
    val preFee: Long,
    /** `order.creditAmount`: credits spent, x 100. */
    val creditAmount: Long,
    /** `order.creditValue`: the money value of those credits, order currency. */
    val creditValue: Long,
    val paymentFee: Long,
    /** `config.vatBp` at the time (the fee is always gross; the VAT in it is reported only). */
    val paymentFeeVatPercent: Long,
    val paymentFeeVatAmount: Long,
    /** `order.totalPrice = preFee + paymentFee`. */
    val total: Long,
    /** `itemsVat + shippingVat + paymentFeeVatAmount`. */
    val vatTotal: Long,
    /** `total - creditValue`: what the gateway must collect. */
    val gatewayAmount: Long,
    /** Null when credits are switched off (`Quote.credits = null`). */
    val credits: CreditQuote?,
    /** Messages of stages B and C (05 section 14). */
    val messages: List<PricingMessage>,
    /**
     * The tender cannot be used as asked: checkout and `/pay` answer 400 `PAYMENT_METHOD_UNAVAILABLE {reason}`
     * (`MIXED_CREDIT_NOT_SUPPORTED`, `EXTERNAL_PRICING`, `CREDITS_REQUIRED`, and for the chosen gateway the amount checks of
     * 05 section 9.5: `CURRENCY_NOT_SUPPORTED`, `PHYSICAL_NOT_SUPPORTED`, `AMOUNT_BELOW_MINIMUM`, `AMOUNT_ABOVE_MAXIMUM`).
     * Null when it can.
     */
    val unavailable: PricingCode?
)

/** The `DISPLAY` mode figures of an order (`Quote.display`): informative, each converted on its own (05 section 4.1). */
data class DisplayBlock(val currency: String, val rate: BigDecimal, val subtotal: Long, val total: Long, val gatewayAmount: Long)

/**
 * Stages A to C of a cart: `Quote` / the persisted order (05 section 11). Field names equal the `Quote` keys of
 * 04 section 2 where the quote has one.
 */
data class PriceBreakdown(
    val items: ItemsResult,
    /** What the buyer pays for shipping, VAT included (05 section 9.1). */
    val shippingTotal: Long,
    val shippingVatPercent: Long,
    val shippingVat: Long,
    val tender: TenderBreakdown,
    /** A physical line needs a shipping charge and the caller gave none (`SHIPPING_ADDRESS_REQUIRED` / `SHIPPING_UNAVAILABLE`). */
    val shippingMissing: Boolean
) {
    val lines: List<PricedLine> get() = items.lines
    val conversions: Conversions get() = items.conversions
    val currency: String get() = items.currency
    val subtotal: Long get() = items.subtotal
    val discountTotal: Long get() = items.discountTotal
    val couponDiscount: Long get() = items.couponDiscount
    val creatorDiscount: Long get() = items.creatorDiscount
    val upgradeDiscount: Long get() = items.upgradeDiscount
    val paymentFee: Long get() = tender.paymentFee
    val vatTotal: Long get() = tender.vatTotal

    /** `order.totalPrice`. */
    val total: Long get() = tender.total
    val creditAmount: Long get() = tender.creditAmount
    val creditValue: Long get() = tender.creditValue
    val gatewayAmount: Long get() = tender.gatewayAmount
    val paymentMethodId: String? get() = tender.paymentMethodId
    val credits: CreditQuote? get() = tender.credits

    /** `OrderSnapshot.discount` (02 section 6): every kind of reduction of the order. */
    val snapshotDiscount: Long
        get() = Math.addExact(
            Math.addExact(items.discountTotal, items.couponDiscount),
            Math.addExact(items.creatorDiscount, items.upgradeDiscount)
        )

    /** Stage A messages first, then those of stages B and C. */
    val messages: List<PricingMessage> get() = items.messages + tender.messages

    /** The `DISPLAY` block, null outside `DISPLAY` mode. */
    val display: DisplayBlock?
        get() = items.display?.let {
            DisplayBlock(
                it.currency, it.rate,
                items.conversions.toDisplay(items.subtotal), items.conversions.toDisplay(tender.total),
                items.conversions.toDisplay(tender.gatewayAmount)
            )
        }

    /**
     * The pricing part of `canCheckout` (05 section 10): no line error, no error-level message, every code that was passed
     * is valid, the tender is usable and a physical cart has its shipping charge. The cart validation of the caller may
     * still say no.
     */
    val canCheckout: Boolean
        get() = lines.none { it.errors.isNotEmpty() } &&
            messages.none { it.level == MessageLevel.ERROR } &&
            items.coupon?.valid != false &&
            items.creatorCode?.valid != false &&
            tender.unavailable == null &&
            !shippingMissing
}

/** What `evaluateMethods` says about one payment method (`PaymentMethodOption`, 05 section 9.5). */
data class MethodEvaluation(
    val methodId: String,
    /** The pricing mode that selecting the method puts the quote in. */
    val pricing: PricingMode,
    /** `paymentFee` for this method (0 for a method that does not price on the market's terms). */
    val feeAmount: Long,
    /** What the gateway would collect, fee included. */
    val gatewayAmount: Long,
    val available: Boolean,
    /** `CURRENCY_NOT_SUPPORTED`, `PHYSICAL_NOT_SUPPORTED`, `MIXED_CREDIT_NOT_SUPPORTED`, `AMOUNT_BELOW_MINIMUM`, `AMOUNT_ABOVE_MAXIMUM`. */
    val unavailableReason: PricingCode?
)

/**
 * A pending order as `retender` sees it (05 section 9.6, `POST /orders/:publicId/pay`): the item and shipping figures
 * are frozen at O1 and never change; only the payment method, the fee and the credit part do.
 */
class FrozenOrder(
    val conversions: Conversions,
    val pricingMode: PricingMode,
    val profile: PricingProfile,
    val itemsTotal: Long,
    val itemsVat: Long,
    val shippingTotal: Long,
    val shippingVat: Long,
    /** A priced line of the order ships: a physical-goods method is needed (05 section 9.5 check 2). */
    val requiresShipping: Boolean,
    /** `config.vatBp` for the VAT contained in the payment fee. */
    val vatBp: Long,
    /** The method the order is on now (`credits` for a full-credit order); a switch away from it drops the credits. */
    val currentMethodId: String?,
    /** The credit part now held for the order (`order.creditAmount` / `creditValue`). */
    val creditAmount: Long,
    val creditValue: Long,
    /** The buyer's balance plus the order's own outstanding hold (07 section 6.4). */
    val creditBalance: Long,
    val loggedIn: Boolean,
    val creditsEnabled: Boolean,
    val allowMixedCreditPayment: Boolean,
    val onlyAcceptCredits: Boolean,
    /** No credit purchase and no subscription line in the order (rule M4 of 07 section 6.2). */
    val mixedCreditCart: Boolean,
    /**
     * What the items cost in credits when the order is paid entirely in credits (the credit run's `itemsTotal`, 05 section
     * 8.1; the shipping is added here). Null when the order cannot be paid in credits.
     */
    val creditItemsTotal: Long?,
    /** The fee frozen on a renewal order, never recomputed; null for every other order. */
    val renewalFee: Long? = null,
    val rates: Map<String, BigDecimal> = emptyMap()
)
