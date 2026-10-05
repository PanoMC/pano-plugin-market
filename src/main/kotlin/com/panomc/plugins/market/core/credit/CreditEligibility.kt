package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.pricing.LineKind
import com.panomc.plugins.market.core.pricing.MethodInput
import com.panomc.plugins.market.core.pricing.PricingCode
import com.panomc.plugins.market.core.pricing.PricingMode
import com.panomc.plugins.market.db.model.OrderSource

/**
 * Who may pay with credits, and how (07 sections 6.1 to 6.3 and 13). Pure decisions over plain facts; the first failing rule
 * gives the reason, in the order of the rule tables. The pricing engine computes the same facts inside its tender (a test
 * runs both over a grid of carts); this object is what services, the quote and the theme's payment options report from.
 *
 * Deviations from the letter of 07, taken so that eligibility agrees with the engine that prices the order:
 * - **F6** also accepts a line that is free in money (list price 0): it costs 0 credits (05 section 8.1 "or `creditPrice == 0 && listUnitPrice == 0`").
 * - **F7** fails only when the order is free in money **and** in credits. A product priced in credits only (money 0, credit
 *   price above 0) has a money total of 0 but must never become free (05 section 8.1 "credits-only products", row 53), so it
 *   stays payable with credits. The same reading applies to the `onlyAcceptCredits` free case ([onlyAcceptCredits]).
 * - **F5** never rejects by itself: with provider `credits` a subscription is always `MANUAL` (09 section 4.1), so a
 *   subscription with a credit price is payable in full and one without a credit price fails F6.
 */
object CreditEligibility {
    enum class Rule { F1, F2, F3, F4, F5, F6, F7, M1, M2, M3, M4, M5, M6 }

    /**
     * [failed] is the first rule that does not hold (null = eligible); [reason] the code to report (null where the rule has
     * none: F7 means "ignored, the order is free", M6 means "nothing to spend"); [lineKeys] the lines that fail F4 or F6.
     */
    data class Verdict(val failed: Rule?, val reason: PricingCode?, val lineKeys: List<String> = emptyList()) {
        val ok: Boolean get() = failed == null

        companion object {
            val OK = Verdict(null, null)
        }
    }

    /** One cart line as the rules need it. [creditUnitPrice] is the effective one (07 section 4), [listUnitPrice] the money list price. */
    class LineFacts(
        val lineKey: String,
        val kind: LineKind,
        val subscription: Boolean,
        val creditUnitPrice: Long,
        val listUnitPrice: Long
    ) {
        val creditGranting: Boolean get() = kind == LineKind.CREDIT_PACK || kind == LineKind.CREDIT_TOPUP
    }

    class FullCreditFacts(
        val creditsEnabled: Boolean,
        val loggedIn: Boolean,
        val pricingMode: PricingMode,
        val lines: List<LineFacts>,
        /** `Quote.total` without credits: the money total of the order. */
        val moneyTotal: Long,
        /** `Quote.credits.creditTotal`: what the order costs in credits, shipping included. */
        val creditTotal: Long
    )

    /** `payableInCredits` (07 section 6.1): rules F1 to F7. The balance is not part of it ([optionReason]). */
    fun fullCredit(f: FullCreditFacts): Verdict {
        if (!f.creditsEnabled) return Verdict(Rule.F1, PricingCode.CREDITS_DISABLED)
        if (!f.loggedIn) return Verdict(Rule.F2, PricingCode.LOGIN_REQUIRED)
        if (f.pricingMode != PricingMode.MARKET) return Verdict(Rule.F3, PricingCode.EXTERNAL_PRICING)
        val granting = f.lines.filter { it.creditGranting }
        if (granting.isNotEmpty()) return Verdict(Rule.F4, PricingCode.NOT_PAYABLE_WITH_CREDITS, granting.map { it.lineKey })
        // F5: no rejection, see the class comment
        val unsellable = f.lines.filter { !CreditPricing.sellableForCredits(it.creditUnitPrice, it.listUnitPrice) }
        if (unsellable.isNotEmpty()) return Verdict(Rule.F6, PricingCode.NOT_PAYABLE_WITH_CREDITS, unsellable.map { it.lineKey })
        if (f.moneyTotal == 0L && f.creditTotal == 0L) return Verdict(Rule.F7, null)
        return Verdict.OK
    }

    /**
     * `PaymentMethodOption.unavailableReason` of the `credits` option (07 section 6.1): the failed rule's reason, else
     * `INSUFFICIENT_CREDITS` when the balance does not cover [creditTotal], else null (available). F7 leaves no reason: the
     * order is free and goes to the `free` provider.
     */
    fun optionReason(verdict: Verdict, balance: Long, creditTotal: Long): PricingCode? = when {
        !verdict.ok -> verdict.reason
        balance < creditTotal -> PricingCode.INSUFFICIENT_CREDITS
        else -> null
    }

    class MixedFacts(
        val creditsEnabled: Boolean,
        val allowMixedCreditPayment: Boolean,
        val onlyAcceptCredits: Boolean,
        val loggedIn: Boolean,
        val pricingMode: PricingMode,
        /** A `CREDIT_PACK` or `CREDIT_TOPUP` line is in the cart. */
        val hasCreditGranting: Boolean,
        val hasSubscription: Boolean,
        val source: OrderSource,
        /** `PaymentCapabilities.mixedCredit` of the selected method; null = no method selected yet (assumed true). */
        val methodMixedCredit: Boolean?,
        val balance: Long
    )

    /**
     * `Quote.credits.maxApplicable > 0` requires M1 to M6 (07 section 6.2). Failing M1 to M5 gives
     * `MIXED_CREDIT_NOT_SUPPORTED` (the quote ignores `useCredits` with that message, checkout answers
     * `PAYMENT_METHOD_UNAVAILABLE`; M5 marks only the method's option). M6 has no reason: with nothing to spend the amount
     * rules (`MixedPayment`) clamp or refuse the request.
     */
    fun mixed(f: MixedFacts): Verdict {
        val notSupported = PricingCode.MIXED_CREDIT_NOT_SUPPORTED
        if (!(f.creditsEnabled && f.allowMixedCreditPayment && !f.onlyAcceptCredits)) return Verdict(Rule.M1, notSupported)
        if (!f.loggedIn) return Verdict(Rule.M2, notSupported)
        if (f.pricingMode != PricingMode.MARKET) return Verdict(Rule.M3, notSupported)
        if (f.hasCreditGranting || f.hasSubscription || f.source != OrderSource.STOREFRONT) return Verdict(Rule.M4, notSupported)
        if (f.methodMixedCredit == false) return Verdict(Rule.M5, notSupported)
        if (f.balance <= 0L) return Verdict(Rule.M6, null)
        return Verdict.OK
    }

    // ---------------------------------------------------------------- which mode applies (07 section 6.3)

    enum class Mode {
        /** No credits are applied. */
        NONE,

        /** A mixed payment (`useCredits > 0`). */
        MIXED,

        /** The whole order in credits (`payWithCredits` or method `credits`). */
        FULL_CREDIT,

        /** `payWithCredits = true` with another `paymentMethodId`: 400 `BAD_REQUEST`. */
        INVALID
    }

    /**
     * The table of 07 section 6.3. `payWithCredits = true` and `paymentMethodId = "credits"` are equivalent; the first with
     * another method is a contradiction. [useCredits] is credits x 100 ([com.panomc.plugins.market.core.pricing.MixedPayment.MAX] counts as above 0);
     * it is ignored in full-credit mode.
     */
    fun requestMode(payWithCredits: Boolean, paymentMethodId: String?, useCredits: Long?): Mode {
        val byMethod = paymentMethodId == MethodInput.CREDITS
        if (payWithCredits && paymentMethodId != null && !byMethod) return Mode.INVALID
        if (payWithCredits || byMethod) return Mode.FULL_CREDIT
        return if ((useCredits ?: 0L) > 0L) Mode.MIXED else Mode.NONE
    }

    // ---------------------------------------------------------------- onlyAcceptCredits (07 section 13)

    enum class CartClass {
        /** No line. */
        EMPTY,

        /** Every line is a `CREDIT_PACK` or the `CREDIT_TOPUP` line. */
        CREDIT_GRANTING,

        /** No such line. */
        PRODUCT,

        /** Both kinds. */
        COMBINED
    }

    fun classify(kinds: Collection<LineKind>): CartClass {
        if (kinds.isEmpty()) return CartClass.EMPTY
        val granting = kinds.count { it == LineKind.CREDIT_PACK || it == LineKind.CREDIT_TOPUP }
        return when (granting) {
            0 -> CartClass.PRODUCT
            kinds.size -> CartClass.CREDIT_GRANTING
            else -> CartClass.COMBINED
        }
    }

    enum class OnlyCredits {
        /** The mode does not change the cart: credit purchases are normal gateway checkouts. */
        UNAFFECTED,

        /** A product cart that costs nothing: the `free` provider, as always. */
        FREE,

        /** A product cart that costs something: the `credits` option only; checkout needs `payWithCredits` / method `credits`. */
        CREDITS_ONLY,

        /** Credit purchases next to products: line error `CREDIT_PACK_SEPARATE_ORDER`, `canCheckout = false`, 400 `INVALID_CART`. */
        SEPARATE_ORDER
    }

    /**
     * What `onlyAcceptCredits` means for a cart (the table of 07 section 13). A product cart is free only when it costs
     * nothing in money **and** in credits ([creditTotal] is what the credit run says, 0 when not payable).
     */
    fun onlyAcceptCredits(cart: CartClass, moneyTotal: Long, creditTotal: Long): OnlyCredits = when (cart) {
        CartClass.EMPTY, CartClass.CREDIT_GRANTING -> OnlyCredits.UNAFFECTED
        CartClass.PRODUCT -> if (moneyTotal == 0L && creditTotal == 0L) OnlyCredits.FREE else OnlyCredits.CREDITS_ONLY
        CartClass.COMBINED -> OnlyCredits.SEPARATE_ORDER
    }

    /**
     * Checkout of a [OnlyCredits.CREDITS_ONLY] cart without `payWithCredits` / method `credits` is 400
     * `PAYMENT_METHOD_UNAVAILABLE {reason: "CREDITS_REQUIRED"}`; null for every other case.
     */
    fun onlyCreditsCheckoutReason(outcome: OnlyCredits, mode: Mode): PricingCode? =
        if (outcome == OnlyCredits.CREDITS_ONLY && mode != Mode.FULL_CREDIT) PricingCode.CREDITS_REQUIRED else null
}
