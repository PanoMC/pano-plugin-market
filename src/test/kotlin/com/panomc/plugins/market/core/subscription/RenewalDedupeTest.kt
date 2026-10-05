package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.core.subscription.RenewalDedupe.Event
import com.panomc.plugins.market.core.subscription.RenewalDedupe.Facts
import com.panomc.plugins.market.core.subscription.RenewalDedupe.Match
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `RenewalDedupe` (09 section 8.2 step 2, test 17 of 09 section 16). */
class RenewalDedupeTest {
    private val day = SubscriptionTimings.DAY_MS
    private val now = 1_800_000_000_000L

    /** A monthly period `[start, end)` that began 10 days ago. */
    private val periodStart = now - 10 * day
    private val periodEnd = periodStart + 30 * day

    private fun facts(
        paymentExists: Boolean = false,
        paidRenewalExists: Boolean = false,
        newestPaidAt: Long? = null,
        start: Long? = periodStart,
        end: Long? = periodEnd
    ) = Facts(paymentExists, paidRenewalExists, start, end, newestPaidAt)

    // ---------------------------------------------------------------- a: gateway transaction id

    @Test
    fun `a known transaction id is a duplicate`() {
        val e = Event("txn_1", periodStart = null)
        assertEquals(Match.TRANSACTION_ID, RenewalDedupe.match(e, facts(paymentExists = true), now))
        assertTrue(RenewalDedupe.isDuplicate(e, facts(paymentExists = true), now))
    }

    @Test
    fun `an unknown transaction id is new, also when the newest payment is recent`() {
        val e = Event("txn_2", periodStart = null)
        assertNull(RenewalDedupe.match(e, facts(paymentExists = false, newestPaidAt = now - 1000), now), "rule c needs neither id nor period start")
        assertFalse(RenewalDedupe.isDuplicate(e, facts(), now))
    }

    @Test
    fun `a blank transaction id counts as absent`() {
        // With a blank id the lookup flag must not matter, and rule c applies.
        val e = Event("  ", periodStart = null)
        assertEquals(Match.RECENT_PAYMENT, RenewalDedupe.match(e, facts(paymentExists = true, newestPaidAt = now - 1000), now))
        assertNull(RenewalDedupe.match(Event("", null), facts(paymentExists = true), now))
    }

    // ---------------------------------------------------------------- b: period start

    @Test
    fun `a period start equal to the current one is a duplicate`() {
        assertEquals(Match.PERIOD_START, RenewalDedupe.match(Event(null, periodStart), facts(), now))
    }

    @Test
    fun `a period start older than the current one is a duplicate`() {
        assertEquals(Match.PERIOD_START, RenewalDedupe.match(Event(null, periodStart - 30 * day), facts(), now))
        assertEquals(Match.PERIOD_START, RenewalDedupe.match(Event(null, periodStart - 1), facts(), now))
    }

    @Test
    fun `a newer period start is new`() {
        assertNull(RenewalDedupe.match(Event(null, periodStart + 1), facts(), now))
        assertNull(RenewalDedupe.match(Event(null, periodEnd), facts(), now))
    }

    @Test
    fun `a newer period start is still a duplicate when a paid renewal row has it`() {
        val e = Event(null, periodEnd)
        assertEquals(Match.PERIOD_START, RenewalDedupe.match(e, facts(paidRenewalExists = true), now))
        assertNull(RenewalDedupe.match(e, facts(paidRenewalExists = false), now))
    }

    @Test
    fun `without a current period start only the renewal row can make it a duplicate`() {
        val e = Event(null, periodStart)
        assertNull(RenewalDedupe.match(e, facts(start = null), now))
        assertEquals(Match.PERIOD_START, RenewalDedupe.match(e, facts(start = null, paidRenewalExists = true), now))
    }

    // ---------------------------------------------------------------- c: nothing given

    @Test
    fun `without id and period start a payment inside half a period is a duplicate`() {
        val e = Event(null, null)
        val half = (periodEnd - periodStart) / 2 // 15 days
        assertEquals(Match.RECENT_PAYMENT, RenewalDedupe.match(e, facts(newestPaidAt = now - 1000), now))
        assertEquals(Match.RECENT_PAYMENT, RenewalDedupe.match(e, facts(newestPaidAt = now - half + 1), now))
    }

    @Test
    fun `without id and period start a payment older than half a period is new`() {
        val e = Event(null, null)
        val half = (periodEnd - periodStart) / 2
        assertNull(RenewalDedupe.match(e, facts(newestPaidAt = now - half), now), "exactly half a period ago is not inside")
        assertNull(RenewalDedupe.match(e, facts(newestPaidAt = now - half - 1), now))
        assertNull(RenewalDedupe.match(e, facts(newestPaidAt = now - 40 * day), now))
    }

    @Test
    fun `without any paid order or without a period the event is new`() {
        val e = Event(null, null)
        assertNull(RenewalDedupe.match(e, facts(newestPaidAt = null), now))
        assertNull(RenewalDedupe.match(e, facts(newestPaidAt = now - 1000, start = null), now), "money that arrived is recorded")
        assertNull(RenewalDedupe.match(e, facts(newestPaidAt = now - 1000, end = null), now))
    }

    @Test
    fun `a daily plan has a half period of 12 hours`() {
        val start = now - 2 * 3_600_000L
        val e = Event(null, null)
        val f = Facts(currentPeriodStart = start, currentPeriodEnd = start + day, newestPaidOrderPaidAt = now - 11 * 3_600_000L)
        assertEquals(Match.RECENT_PAYMENT, RenewalDedupe.match(e, f, now))
        assertNull(RenewalDedupe.match(e, f.copy(newestPaidOrderPaidAt = now - 13 * 3_600_000L), now))
    }

    // ---------------------------------------------------------------- first match wins

    @Test
    fun `the first matching rule wins`() {
        val e = Event("txn_1", periodStart)
        val everything = facts(paymentExists = true, paidRenewalExists = true, newestPaidAt = now - 1000)
        assertEquals(Match.TRANSACTION_ID, RenewalDedupe.match(e, everything, now))
        val noPayment = everything.copy(paymentWithTransactionExists = false)
        assertEquals(Match.PERIOD_START, RenewalDedupe.match(e, noPayment, now))
        // With an id and a period start given, rule c never applies, however recent the newest payment is.
        val onlyRecent = facts(newestPaidAt = now - 1000)
        assertNull(RenewalDedupe.match(Event("txn_9", periodEnd), onlyRecent, now))
        assertNull(RenewalDedupe.match(Event("txn_9", null), onlyRecent, now))
        assertNull(RenewalDedupe.match(Event(null, periodEnd), onlyRecent, now))
    }
}
