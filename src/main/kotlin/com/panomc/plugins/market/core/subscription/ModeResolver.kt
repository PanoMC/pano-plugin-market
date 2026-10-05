package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.RecurringSupport

/** The plan a subscription checkout would create, as the offer table needs it (09 section 4.2). */
data class RecurringPlan(
    val currency: String,
    val intervalUnit: IntervalUnit,
    val intervalCount: Int,
    val maxCycles: Int?
) {
    /** `unit x count` in days with `MONTH = 30` and `YEAR = 365` (the unit of `recurringMin/MaxIntervalDays`). */
    val intervalDays: Long
        get() = intervalCount.toLong() * when (intervalUnit) {
            IntervalUnit.DAY -> 1L
            IntervalUnit.WEEK -> 7L
            IntervalUnit.MONTH -> 30L
            IntervalUnit.YEAR -> 365L
        }
}

/** What one payment method offers for a subscription plan (`PaymentMethodOption.recurring`, 09 section 4.2). */
sealed class ModeOffer {
    /** Recurring billing; [mode] is `GATEWAY` (gateway-managed) or `MERCHANT` (merchant-initiated). */
    data class Auto(val mode: SubscriptionMode) : ModeOffer()

    /** A normal one-off payment plus a prepared renewal order and a reminder. */
    data object Manual : ModeOffer()

    /** The method is not offered; [reason] is the error / reason code (`RECURRING_NOT_SUPPORTED`, `PROVIDER_INELIGIBLE`). */
    data class Unavailable(val reason: String) : ModeOffer()

    /** `"AUTO"` / `"MANUAL"` for the theme notice, `null` when the method is not offered. */
    val recurring: String?
        get() = when (this) {
            is Auto -> "AUTO"
            Manual -> "MANUAL"
            is Unavailable -> null
        }
}

/**
 * Chooses the billing mode (09 sections 4.2, 4.4, 8.4). Pure functions of the provider's capabilities and of what a
 * paying attempt actually delivered; the services read the capabilities and call these.
 */
object ModeResolver {
    const val RECURRING_NOT_SUPPORTED = "RECURRING_NOT_SUPPORTED"
    const val PROVIDER_INELIGIBLE = "PROVIDER_INELIGIBLE"

    /**
     * The offer table of 09 section 4.2. `AUTO` when the capabilities fit the plan, else `MANUAL` when
     * [manualFallback] (`subscriptionManualFallback`, default on), else unavailable with `RECURRING_NOT_SUPPORTED`.
     */
    fun offer(caps: PaymentCapabilities, plan: RecurringPlan, manualFallback: Boolean): ModeOffer {
        val mode = when (caps.recurring) {
            RecurringSupport.GATEWAY_MANAGED -> SubscriptionMode.GATEWAY
            RecurringSupport.MERCHANT_INITIATED -> SubscriptionMode.MERCHANT
            RecurringSupport.NONE -> null
        }
        if (mode != null && fits(caps, plan)) return ModeOffer.Auto(mode)
        return fallback(manualFallback)
    }

    private fun fits(caps: PaymentCapabilities, plan: RecurringPlan): Boolean {
        val currencies = caps.recurringCurrencies
        if (currencies != null && currencies.none { it.equals(plan.currency, ignoreCase = true) }) return false
        val intervals = caps.recurringIntervals
        if (intervals != null && plan.intervalUnit !in intervals) return false
        val planMax = plan.maxCycles
        val capsMax = caps.recurringMaxCycles
        if (planMax != null && capsMax != null && planMax > capsMax) return false
        val minDays = caps.recurringMinIntervalDays
        if (minDays != null && plan.intervalDays < minDays) return false
        val maxDays = caps.recurringMaxIntervalDays
        if (maxDays != null && plan.intervalDays > maxDays) return false
        return true
    }

    private fun fallback(manualFallback: Boolean): ModeOffer =
        if (manualFallback) ModeOffer.Manual else ModeOffer.Unavailable(RECURRING_NOT_SUPPORTED)

    /**
     * `checkEligibility` for an `AUTO` offer (09 section 4.2): `oneOffOnly` downgrades the offer to `MANUAL` (or to
     * unavailable without the fallback) instead of removing the method, `ineligible` removes it. A `MANUAL` or
     * unavailable offer is returned unchanged: market does not ask the provider about those.
     */
    fun applyEligibility(offer: ModeOffer, eligibility: Eligibility, manualFallback: Boolean): ModeOffer {
        if (offer !is ModeOffer.Auto) return offer
        if (!eligibility.eligible) return ModeOffer.Unavailable(PROVIDER_INELIGIBLE)
        if (eligibility.oneOffOnly) return fallback(manualFallback)
        return offer
    }

    /**
     * Mode at activation, from what the paying attempt actually delivered (09 section 4.4 step 1): a gateway
     * subscription => `GATEWAY`; else a stored method from a `MERCHANT_INITIATED` provider => `MERCHANT`; else
     * `MANUAL`. A stored method from a provider that is not merchant-initiated is ignored.
     */
    fun atActivation(caps: PaymentCapabilities, hasGatewaySubscription: Boolean, hasStoredMethod: Boolean): SubscriptionMode = when {
        hasGatewaySubscription -> SubscriptionMode.GATEWAY
        hasStoredMethod && caps.recurring == RecurringSupport.MERCHANT_INITIATED -> SubscriptionMode.MERCHANT
        else -> SubscriptionMode.MANUAL
    }

    fun atActivation(caps: PaymentCapabilities, succeeded: PaymentEvent.Succeeded): SubscriptionMode =
        atActivation(caps, succeeded.subscription != null, succeeded.storedMethod != null)

    /**
     * Mode after a paid renewal (09 section 8.4 step 4). A `GATEWAY` subscription stays `GATEWAY` whatever the
     * paying attempt carried: the gateway keeps billing it, so switching it to a stored method would bill the buyer
     * twice. Otherwise a stored method from a `MERCHANT_INITIATED` provider => `MERCHANT` (how a buyer replaces a
     * card by paying the pending renewal order by hand).
     *
     * [chargedWithStoredMethod] is true when the paying attempt was market's own merchant-initiated charge made with
     * the row's existing stored method (`SubscriptionJob` step A, `chargeRecurring`, 09 section 8.3). A token gateway
     * answers such a charge with a plain `Succeeded` that carries no new stored method, so "no stored method came
     * back" cannot tell it from a payment by hand: a `MERCHANT` row paid that way keeps `MERCHANT` and its token and
     * the next charge is scheduled (09 section 16 test 34). `MANUAL` remains for a `MERCHANT` / `MANUAL` row paid by
     * hand without a stored method.
     */
    fun atRenewal(
        current: SubscriptionMode,
        caps: PaymentCapabilities,
        hasStoredMethod: Boolean,
        chargedWithStoredMethod: Boolean
    ): SubscriptionMode = when {
        current == SubscriptionMode.GATEWAY -> SubscriptionMode.GATEWAY
        hasStoredMethod && caps.recurring == RecurringSupport.MERCHANT_INITIATED -> SubscriptionMode.MERCHANT
        current == SubscriptionMode.MERCHANT && chargedWithStoredMethod -> SubscriptionMode.MERCHANT
        else -> SubscriptionMode.MANUAL
    }

    /** True when the activation fell back from the provisional recurring mode to `MANUAL` (order event `NOTE`, 09 section 4.4). */
    fun isDowngrade(provisional: SubscriptionMode, actual: SubscriptionMode): Boolean =
        provisional != SubscriptionMode.MANUAL && actual == SubscriptionMode.MANUAL
}
