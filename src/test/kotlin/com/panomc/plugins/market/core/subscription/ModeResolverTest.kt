package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionState
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `ModeResolver` (09 sections 4.2, 4.4, 8.4; tests 7, 8, 9 and 77 of 09 section 16 / 18). */
class ModeResolverTest {
    private fun caps(support: RecurringSupport, configure: PaymentCapabilities.() -> Unit = {}): PaymentCapabilities =
        PaymentCapabilities().apply {
            recurring = support
            configure()
        }

    private fun plan(
        currency: String = "EUR",
        unit: IntervalUnit = IntervalUnit.MONTH,
        count: Int = 1,
        maxCycles: Int? = null
    ) = RecurringPlan(currency, unit, count, maxCycles)

    private val auto = ModeOffer.Auto(SubscriptionMode.GATEWAY)

    // ---------------------------------------------------------------- 7, offer table

    @Test
    fun `gateway-managed and merchant-initiated within their constraints are AUTO`() {
        val gateway = ModeResolver.offer(caps(RecurringSupport.GATEWAY_MANAGED), plan(), manualFallback = true)
        assertEquals(ModeOffer.Auto(SubscriptionMode.GATEWAY), gateway)
        assertEquals("AUTO", gateway.recurring)

        val merchant = ModeResolver.offer(caps(RecurringSupport.MERCHANT_INITIATED), plan(), manualFallback = true)
        assertEquals(ModeOffer.Auto(SubscriptionMode.MERCHANT), merchant)
        assertEquals("AUTO", merchant.recurring)

        // The same with the fallback off: constraints met means AUTO either way.
        assertEquals(ModeOffer.Auto(SubscriptionMode.GATEWAY), ModeResolver.offer(caps(RecurringSupport.GATEWAY_MANAGED), plan(), false))
    }

    @Test
    fun `no recurring support is MANUAL with the fallback on and unavailable with it off`() {
        val none = caps(RecurringSupport.NONE)
        assertEquals(ModeOffer.Manual, ModeResolver.offer(none, plan(), manualFallback = true))
        assertEquals("MANUAL", ModeResolver.offer(none, plan(), true).recurring)
        val off = ModeResolver.offer(none, plan(), manualFallback = false)
        assertEquals(ModeOffer.Unavailable(ModeResolver.RECURRING_NOT_SUPPORTED), off)
        assertNull(off.recurring)
        assertEquals("RECURRING_NOT_SUPPORTED", ModeResolver.RECURRING_NOT_SUPPORTED)
    }

    @Test
    fun `the full offer matrix of support, fit and fallback`() {
        // support x fits x manualFallback -> expected
        for (support in RecurringSupport.values()) for (fits in listOf(true, false)) for (fallback in listOf(true, false)) {
            val c = caps(support) { if (!fits) recurringCurrencies = setOf("TRY") }
            val expected: ModeOffer = when {
                support == RecurringSupport.GATEWAY_MANAGED && fits -> ModeOffer.Auto(SubscriptionMode.GATEWAY)
                support == RecurringSupport.MERCHANT_INITIATED && fits -> ModeOffer.Auto(SubscriptionMode.MERCHANT)
                fallback -> ModeOffer.Manual
                else -> ModeOffer.Unavailable("RECURRING_NOT_SUPPORTED")
            }
            assertEquals(expected, ModeResolver.offer(c, plan(), fallback), "$support fits=$fits fallback=$fallback")
        }
    }

    // ---------------------------------------------------------------- 8, constraint violations

    @Test
    fun `currency outside the recurring currencies is not AUTO`() {
        val c = caps(RecurringSupport.MERCHANT_INITIATED) { recurringCurrencies = setOf("TRY", "USD") }
        assertEquals(ModeOffer.Manual, ModeResolver.offer(c, plan(currency = "EUR"), true))
        assertEquals(ModeOffer.Unavailable("RECURRING_NOT_SUPPORTED"), ModeResolver.offer(c, plan(currency = "EUR"), false))
        assertEquals(ModeOffer.Auto(SubscriptionMode.MERCHANT), ModeResolver.offer(c, plan(currency = "USD"), true))
        assertEquals(ModeOffer.Auto(SubscriptionMode.MERCHANT), ModeResolver.offer(c, plan(currency = "usd"), true), "currency codes compare ignoring case")
    }

    @Test
    fun `interval unit outside the recurring intervals is not AUTO`() {
        val c = caps(RecurringSupport.GATEWAY_MANAGED) { recurringIntervals = setOf(IntervalUnit.MONTH, IntervalUnit.YEAR) }
        assertEquals(ModeOffer.Manual, ModeResolver.offer(c, plan(unit = IntervalUnit.WEEK), true))
        assertEquals(ModeOffer.Unavailable("RECURRING_NOT_SUPPORTED"), ModeResolver.offer(c, plan(unit = IntervalUnit.DAY), false))
        assertEquals(auto, ModeResolver.offer(c, plan(unit = IntervalUnit.YEAR), true))
        // An empty set allows nothing; null allows everything.
        val none = caps(RecurringSupport.GATEWAY_MANAGED) { recurringIntervals = emptySet() }
        assertEquals(ModeOffer.Manual, ModeResolver.offer(none, plan(), true))
        assertEquals(auto, ModeResolver.offer(caps(RecurringSupport.GATEWAY_MANAGED), plan(unit = IntervalUnit.DAY), true))
    }

    @Test
    fun `a plan with more cycles than the gateway allows is not AUTO, an open plan and an unlimited gateway are`() {
        val limited = caps(RecurringSupport.MERCHANT_INITIATED) { recurringMaxCycles = 12 }
        assertEquals(ModeOffer.Manual, ModeResolver.offer(limited, plan(maxCycles = 13), true))
        assertEquals(ModeOffer.Unavailable("RECURRING_NOT_SUPPORTED"), ModeResolver.offer(limited, plan(maxCycles = 121), false))
        assertEquals(ModeOffer.Auto(SubscriptionMode.MERCHANT), ModeResolver.offer(limited, plan(maxCycles = 12), true), "equal is allowed")
        assertEquals(ModeOffer.Auto(SubscriptionMode.MERCHANT), ModeResolver.offer(limited, plan(maxCycles = null), true), "an open plan is allowed (09 section 4.2)")
        val unlimited = caps(RecurringSupport.MERCHANT_INITIATED)
        assertEquals(ModeOffer.Auto(SubscriptionMode.MERCHANT), ModeResolver.offer(unlimited, plan(maxCycles = 1200), true))
    }

    @Test
    fun `interval length limits are inclusive and use MONTH = 30 and YEAR = 365 days`() {
        val c = caps(RecurringSupport.GATEWAY_MANAGED) {
            recurringMinIntervalDays = 3
            recurringMaxIntervalDays = 365
        }
        assertEquals(ModeOffer.Manual, ModeResolver.offer(c, plan(unit = IntervalUnit.DAY, count = 2), true), "2 days is under the minimum")
        assertEquals(auto, ModeResolver.offer(c, plan(unit = IntervalUnit.DAY, count = 3), true), "3 days is the minimum")
        assertEquals(auto, ModeResolver.offer(c, plan(unit = IntervalUnit.YEAR), true), "one year is 365 days, the maximum")
        assertEquals(auto, ModeResolver.offer(c, plan(unit = IntervalUnit.MONTH, count = 12), true), "12 x 30 = 360")
        assertEquals(ModeOffer.Manual, ModeResolver.offer(c, plan(unit = IntervalUnit.WEEK, count = 53), true), "53 weeks = 371 days")
        assertEquals(30L, plan(unit = IntervalUnit.MONTH).intervalDays)
        assertEquals(365L, plan(unit = IntervalUnit.YEAR).intervalDays)
        assertEquals(14L, plan(unit = IntervalUnit.WEEK, count = 2).intervalDays)
        assertEquals(90L, plan(unit = IntervalUnit.MONTH, count = 3).intervalDays)
        assertEquals(7L, plan(unit = IntervalUnit.DAY, count = 7).intervalDays)
    }

    // ---------------------------------------------------------------- 77, eligibility

    @Test
    fun `oneOffOnly downgrades an AUTO offer to MANUAL, not unavailable`() {
        // monthly x 12 on a provider with recurringMaxIntervalDays = 365: 360 days fit the caps, so the offer is AUTO first.
        val c = caps(RecurringSupport.GATEWAY_MANAGED) { recurringMaxIntervalDays = 365 }
        val offer = ModeResolver.offer(c, plan(unit = IntervalUnit.MONTH, count = 12), manualFallback = true)
        assertEquals(auto, offer)

        val downgraded = ModeResolver.applyEligibility(offer, Eligibility.oneOffOnly("PLAN_TOO_LONG"), manualFallback = true)
        assertEquals(ModeOffer.Manual, downgraded)
        assertEquals("MANUAL", downgraded.recurring)

        val off = ModeResolver.applyEligibility(offer, Eligibility.oneOffOnly("PLAN_TOO_LONG"), manualFallback = false)
        assertEquals(ModeOffer.Unavailable("RECURRING_NOT_SUPPORTED"), off)
    }

    @Test
    fun `ineligible removes the method and eligible leaves the offer alone`() {
        assertEquals(
            ModeOffer.Unavailable("PROVIDER_INELIGIBLE"),
            ModeResolver.applyEligibility(auto, ineligible(), manualFallback = true)
        )
        assertSame(auto, ModeResolver.applyEligibility(auto, Eligibility.eligible(), manualFallback = true))
        assertSame(auto, ModeResolver.applyEligibility(auto, Eligibility.eligible(), manualFallback = false))
    }

    @Test
    fun `eligibility is not applied to MANUAL or unavailable offers`() {
        assertSame(ModeOffer.Manual, ModeResolver.applyEligibility(ModeOffer.Manual, ineligible(), true))
        val unavailable = ModeOffer.Unavailable("RECURRING_NOT_SUPPORTED")
        assertSame(unavailable, ModeResolver.applyEligibility(unavailable, Eligibility.oneOffOnly("X"), true))
    }

    private fun ineligible(): Eligibility =
        Eligibility.ineligible("NOPE", com.panomc.plugins.market.spi.common.LocalizedText.of("No"))

    // ---------------------------------------------------------------- 9, activation

    private fun succeeded(withSubscription: Boolean, withStoredMethod: Boolean): PaymentEvent.Succeeded =
        PaymentEvent.Succeeded(PaymentTarget.Attempt(1L), Money(1000L, "EUR")).apply {
            if (withSubscription) subscription = GatewaySubscriptionState("sub_1", GatewaySubscriptionStatus.ACTIVE)
            if (withStoredMethod) storedMethod = StoredPaymentMethod("tok_1")
        }

    @Test
    fun `activation mode from what the paying attempt delivered`() {
        val gatewayCaps = caps(RecurringSupport.GATEWAY_MANAGED)
        val merchantCaps = caps(RecurringSupport.MERCHANT_INITIATED)
        val noneCaps = caps(RecurringSupport.NONE)

        // a gateway subscription => GATEWAY
        assertEquals(SubscriptionMode.GATEWAY, ModeResolver.atActivation(gatewayCaps, succeeded(true, false)))
        // a stored method with merchant-initiated capabilities => MERCHANT
        assertEquals(SubscriptionMode.MERCHANT, ModeResolver.atActivation(merchantCaps, succeeded(false, true)))
        // neither => MANUAL, whatever the capabilities promised
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atActivation(gatewayCaps, succeeded(false, false)))
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atActivation(merchantCaps, succeeded(false, false)))
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atActivation(noneCaps, succeeded(false, false)))
    }

    @Test
    fun `a stored method from a provider that is not merchant-initiated is MANUAL`() {
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atActivation(caps(RecurringSupport.NONE), succeeded(false, true)))
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atActivation(caps(RecurringSupport.GATEWAY_MANAGED), succeeded(false, true)))
    }

    @Test
    fun `a gateway subscription wins over a stored method`() {
        assertEquals(SubscriptionMode.GATEWAY, ModeResolver.atActivation(caps(RecurringSupport.MERCHANT_INITIATED), succeeded(true, true)))
        assertEquals(SubscriptionMode.GATEWAY, ModeResolver.atActivation(caps(RecurringSupport.NONE), succeeded(true, false)), "09 section 4.4: subscription set => GATEWAY")
    }

    @Test
    fun `the fact based overload agrees with the event based one for every combination`() {
        for (support in RecurringSupport.values()) for (sub in listOf(true, false)) for (stored in listOf(true, false)) {
            val c = caps(support)
            assertEquals(
                ModeResolver.atActivation(c, succeeded(sub, stored)),
                ModeResolver.atActivation(c, sub, stored),
                "$support sub=$sub stored=$stored"
            )
        }
    }

    // ---------------------------------------------------------------- renewal mode (09 section 8.4 step 4)

    @Test
    fun `mode after a paid renewal`() {
        val merchantCaps = caps(RecurringSupport.MERCHANT_INITIATED)
        val gatewayCaps = caps(RecurringSupport.GATEWAY_MANAGED)
        val noneCaps = caps(RecurringSupport.NONE)

        // A buyer replaces the card by paying the pending renewal order by hand: stored method + merchant provider.
        assertEquals(SubscriptionMode.MERCHANT, ModeResolver.atRenewal(SubscriptionMode.MANUAL, merchantCaps, true, false))
        assertEquals(SubscriptionMode.MERCHANT, ModeResolver.atRenewal(SubscriptionMode.MERCHANT, merchantCaps, true, false))
        // Paid by hand and no stored method came back: MERCHANT and MANUAL end up MANUAL.
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atRenewal(SubscriptionMode.MERCHANT, merchantCaps, false, false))
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atRenewal(SubscriptionMode.MANUAL, noneCaps, false, false))
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atRenewal(SubscriptionMode.MANUAL, gatewayCaps, true, false), "a gateway-managed provider is used as a plain one-off payment")
        // GATEWAY stays GATEWAY, always (the gateway keeps billing; a stored method would bill twice).
        for (stored in listOf(true, false)) for (charged in listOf(true, false)) for (c in listOf(merchantCaps, gatewayCaps, noneCaps)) {
            assertEquals(SubscriptionMode.GATEWAY, ModeResolver.atRenewal(SubscriptionMode.GATEWAY, c, stored, charged))
        }
    }

    @Test
    fun `the automatic merchant charge keeps the MERCHANT mode and its token although no new stored method came back`() {
        // Review fix: chargeRecurring answers with a plain Succeeded (FakeProvider, a token gateway's POST /v1/charges), so
        // "no stored method came back" used to turn every MERCHANT row into MANUAL after its first automatic renewal.
        val merchantCaps = caps(RecurringSupport.MERCHANT_INITIATED)
        assertEquals(SubscriptionMode.MERCHANT, ModeResolver.atRenewal(SubscriptionMode.MERCHANT, merchantCaps, hasStoredMethod = false, chargedWithStoredMethod = true))
        // The same payment by hand (no token involved) still downgrades.
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atRenewal(SubscriptionMode.MERCHANT, merchantCaps, hasStoredMethod = false, chargedWithStoredMethod = false))
        // A provider that also returns a refreshed stored method stays MERCHANT either way.
        assertEquals(SubscriptionMode.MERCHANT, ModeResolver.atRenewal(SubscriptionMode.MERCHANT, merchantCaps, hasStoredMethod = true, chargedWithStoredMethod = true))
        // Only a MERCHANT row can have been charged with its stored method: a MANUAL row has no token.
        assertEquals(SubscriptionMode.MANUAL, ModeResolver.atRenewal(SubscriptionMode.MANUAL, merchantCaps, hasStoredMethod = false, chargedWithStoredMethod = true))
    }

    @Test
    fun `mode after a paid renewal obeys the invariants for every combination`() {
        for (current in SubscriptionMode.values()) for (support in RecurringSupport.values()) for (stored in listOf(true, false)) for (charged in listOf(true, false)) {
            val result = ModeResolver.atRenewal(current, caps(support), stored, charged)
            val label = "$current $support stored=$stored charged=$charged -> $result"
            // GATEWAY if and only if it was GATEWAY: a renewal never creates a gateway subscription.
            assertEquals(current == SubscriptionMode.GATEWAY, result == SubscriptionMode.GATEWAY, label)
            if (current != SubscriptionMode.GATEWAY && result == SubscriptionMode.MERCHANT) {
                // MERCHANT needs a token: a fresh one from a merchant-initiated provider, or the row's own that was just used.
                val freshToken = stored && support == RecurringSupport.MERCHANT_INITIATED
                val usedToken = current == SubscriptionMode.MERCHANT && charged
                assertTrue(freshToken || usedToken, label)
            }
            // A MERCHANT row that was charged with its token never loses it.
            if (current == SubscriptionMode.MERCHANT && charged) assertEquals(SubscriptionMode.MERCHANT, result, label)
        }
    }

    @Test
    fun `a fall back from the provisional recurring mode is a downgrade`() {
        assertTrue(ModeResolver.isDowngrade(SubscriptionMode.GATEWAY, SubscriptionMode.MANUAL))
        assertTrue(ModeResolver.isDowngrade(SubscriptionMode.MERCHANT, SubscriptionMode.MANUAL))
        assertFalse(ModeResolver.isDowngrade(SubscriptionMode.MANUAL, SubscriptionMode.MANUAL))
        assertFalse(ModeResolver.isDowngrade(SubscriptionMode.GATEWAY, SubscriptionMode.GATEWAY))
        assertFalse(ModeResolver.isDowngrade(SubscriptionMode.MERCHANT, SubscriptionMode.MERCHANT))
        assertFalse(ModeResolver.isDowngrade(SubscriptionMode.MANUAL, SubscriptionMode.GATEWAY))
    }
}
