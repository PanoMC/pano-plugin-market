package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.core.refund.RefundEventMatcher.Attempt
import com.panomc.plugins.market.core.refund.RefundEventMatcher.Event
import com.panomc.plugins.market.core.refund.RefundEventMatcher.Match
import com.panomc.plugins.market.core.refund.RefundEventMatcher.NoOpReason
import com.panomc.plugins.market.core.refund.RefundEventMatcher.RefundRow
import com.panomc.plugins.market.core.refund.RefundEventMatcher.Rule
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.spi.payment.RefundState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** `RefundEventMatcher` (21 section 4, 02 section 7.4): RD-U2 and V-06. */
class RefundEventMatcherTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    /** An attempt that captured 100.00 and has nothing refunded yet. */
    private val attempt = Attempt(paymentId = 5, providerId = "stripe", paidAmount = 10000, refundedAmount = 0)

    private fun row(
        id: Long,
        status: RefundStatus = RefundStatus.REQUESTED,
        amount: Long = 2500,
        key: String = "rk-$id",
        gatewayRefundId: String? = null,
        paymentId: Long? = 5,
        providerId: String? = "stripe",
        origin: RefundOrigin = RefundOrigin.PANEL,
        createdAt: Long = now - hour * id
    ) = RefundRow(id, paymentId, providerId, origin, status, key, gatewayRefundId, amount, createdAt)

    private fun event(
        state: RefundState = RefundState.SUCCEEDED,
        amount: Long? = null,
        key: String? = null,
        gid: String? = null,
        cumulative: Long? = null,
        eventKey: String? = null,
        hash: String = "h1"
    ) = Event(state, amount, key, gid, cumulative, eventKey, hash)

    private fun match(e: Event, rows: List<RefundRow>, a: Attempt = attempt) = RefundEventMatcher.match(e, a, rows, now)

    // ---------------------------------------------------------------- rules 1 and 2

    @Test
    fun `the refund key finds the row by its idempotency key`() {
        val rows = listOf(row(1, key = "k-a"), row(2, key = "k-b"))
        assertEquals(Match.Existing(2, Rule.REFUND_KEY), match(event(key = "k-b"), rows))
    }

    @Test
    fun `the gateway refund id finds the row of the same provider`() {
        val rows = listOf(row(1, gatewayRefundId = "re_1"), row(2, gatewayRefundId = "re_2"), row(3, gatewayRefundId = "re_2", providerId = "mollie"))
        assertEquals(Match.Existing(2, Rule.GATEWAY_REFUND_ID), match(event(gid = "re_2"), rows))
        // the same id at another provider is another refund
        assertEquals(Match.Existing(3, Rule.GATEWAY_REFUND_ID), match(event(gid = "re_2"), rows, attempt.copy(providerId = "mollie")))
    }

    @Test
    fun `the key wins over the id, and an id of an earlier status still matches`() {
        val rows = listOf(row(1, key = "k-a", gatewayRefundId = "re_1"), row(2, key = "k-b", gatewayRefundId = "re_2", status = RefundStatus.SUCCEEDED))
        assertEquals(Match.Existing(1, Rule.REFUND_KEY), match(event(key = "k-a", gid = "re_2"), rows))
        // a settled row is still the row the event is about (the state machine then treats it as a replay)
        assertEquals(Match.Existing(2, Rule.GATEWAY_REFUND_ID), match(event(gid = "re_2"), rows))
    }

    @Test
    fun `an unknown key falls through to the id`() {
        val rows = listOf(row(1, key = "k-a", gatewayRefundId = "re_1"))
        assertEquals(Match.Existing(1, Rule.GATEWAY_REFUND_ID), match(event(key = "other", gid = "re_1"), rows))
    }

    // ---------------------------------------------------------------- rule 3: no id at all

    @Test
    fun `no id and an equal amount picks the oldest open row`() {
        val rows = listOf(
            row(1, amount = 2500, createdAt = now - 3 * hour), // oldest
            row(2, amount = 2500, createdAt = now - 1 * hour),
            row(3, amount = 4000, createdAt = now - 5 * hour)
        )
        assertEquals(Match.Existing(1, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(amount = 2500), rows))
        assertEquals(Match.Existing(3, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(amount = 4000), rows))
    }

    @Test
    fun `V-06 a RefundUpdated without ids after a panel refund is matched to the open row, not booked twice`() {
        val rows = listOf(row(1, status = RefundStatus.REQUESTED, amount = 3000, gatewayRefundId = null))
        val m = match(event(RefundState.SUCCEEDED, amount = 3000, eventKey = "e1"), rows)
        assertEquals(Match.Existing(1, Rule.OLDEST_OPEN_BY_AMOUNT), m)
        // and a PENDING row is open too
        val pending = listOf(row(1, status = RefundStatus.PENDING, amount = 3000, gatewayRefundId = "re_9"))
        assertEquals(Match.Existing(1, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(amount = 3000), pending))
    }

    @Test
    fun `two open rows of equal amount match the oldest, ties on the creation time go to the lower id`() {
        val same = now - 2 * hour
        val rows = listOf(row(8, amount = 1000, createdAt = same), row(7, amount = 1000, createdAt = same))
        assertEquals(Match.Existing(7, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(amount = 1000), rows))
    }

    @Test
    fun `a null amount matches only when exactly one refund is open`() {
        assertEquals(Match.Existing(1, Rule.ONLY_OPEN), match(event(amount = null), listOf(row(1))))
        // settled, failed and cancelled rows are not open
        val mixed = listOf(
            row(1, status = RefundStatus.SUCCEEDED), row(2, status = RefundStatus.FAILED), row(3, status = RefundStatus.CANCELLED), row(4)
        )
        assertEquals(Match.Existing(4, Rule.ONLY_OPEN), match(event(amount = null), mixed))
        // none or two: no match, so the event is booked as a gateway refund of the remaining collected amount
        val none = match(event(amount = null, eventKey = "e2"), listOf(row(1, status = RefundStatus.SUCCEEDED)))
        assertEquals(Match.Insert(10000, "gw:e2"), none)
        val two = match(event(amount = null, eventKey = "e3"), listOf(row(1), row(2)))
        assertEquals(Match.Insert(10000, "gw:e3"), two)
    }

    @Test
    fun `only the rows of this attempt and only open ones are candidates`() {
        val rows = listOf(
            row(1, amount = 2500, paymentId = 99), // another attempt
            row(2, amount = 2500, paymentId = null, providerId = null), // credit-only refund
            row(3, amount = 2500, status = RefundStatus.SUCCEEDED),
            row(4, amount = 2500, status = RefundStatus.FAILED)
        )
        // nothing open for this amount: falls through to the gateway row
        assertEquals(Match.Insert(2500, "gw:e"), match(event(amount = 2500, eventKey = "e"), rows))
    }

    @Test
    fun `rule 3 is not used for an event that carries an id the rows do not know`() {
        val rows = listOf(row(1, amount = 2500, gatewayRefundId = null))
        // the gateway says re_new for 25.00; the open row has the same amount but the id is unknown: a gateway-originated row
        assertEquals(Match.Insert(2500, "gw:e1"), match(event(amount = 2500, gid = "re_new", eventKey = "e1"), rows))
        assertEquals(Match.Insert(2500, "gw:e1"), match(event(amount = 2500, key = "unknown", eventKey = "e1"), rows))
    }

    // ---------------------------------------------------------------- rule 4: snapshots

    @Test
    fun `a cumulative snapshot that repeats what is known is a no-op`() {
        val a = attempt.copy(refundedAmount = 3000)
        val open = listOf(row(1, amount = 2000))
        // known = 3000 refunded + 2000 open
        // a snapshot of a refund this order does not know by id (a dashboard refund), so no row is matched
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(gid = "re_x", cumulative = 5000, eventKey = "s1"), open, a))
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(gid = "re_x", cumulative = 4000, eventKey = "s1"), open, a))
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(cumulative = 0, eventKey = "s1"), emptyList(), a.copy(refundedAmount = 0)))
    }

    @Test
    fun `a cumulative snapshot that grew inserts the difference`() {
        val a = attempt.copy(refundedAmount = 3000)
        val open = listOf(row(1, amount = 2000, gatewayRefundId = "re_1"))
        assertEquals(Match.Insert(1500, "gw:s2"), match(event(amount = 1500, cumulative = 6500, eventKey = "s2"), open, a))
        // the snapshot carries no amount of its own: the difference is what counts
        assertEquals(Match.Insert(1500, "gw:s2"), match(event(gid = "re_x", cumulative = 6500, eventKey = "s2"), open, a))
        // with no id and no amount, a single open refund is the one the snapshot is about (rule 3 comes first)
        assertEquals(Match.Existing(1, Rule.ONLY_OPEN), match(event(cumulative = 6500, eventKey = "s2"), open, a))
    }

    @Test
    fun `a snapshot that matches a row by id is that row`() {
        val open = listOf(row(1, amount = 2000, gatewayRefundId = "re_1"))
        assertEquals(Match.Existing(1, Rule.GATEWAY_REFUND_ID), match(event(gid = "re_1", cumulative = 2000), open))
    }

    @Test
    fun `a snapshot beyond what the attempt took is cut back and with nothing left it asks for the alert`() {
        val a = attempt.copy(paidAmount = 10000, refundedAmount = 3000)
        assertEquals(Match.Insert(7000, "gw:s3", clamped = true), match(event(cumulative = 12000, eventKey = "s3"), emptyList(), a))
        val full = attempt.copy(paidAmount = 10000, refundedAmount = 10000)
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(cumulative = 10000, eventKey = "s4"), emptyList(), full))
        assertEquals(Match.NoOp(NoOpReason.NOTHING_REFUNDABLE, alert = true), match(event(cumulative = 12000, eventKey = "s4"), emptyList(), full))
    }

    // ---------------------------------------------------------------- rule 4: a gateway-originated refund

    @Test
    fun `an unmatched event becomes a gateway row keyed by the event key, else by the request hash`() {
        assertEquals(Match.Insert(4000, "gw:evt-1"), match(event(amount = 4000, eventKey = "evt-1", hash = "h"), emptyList()))
        assertEquals(Match.Insert(4000, "gw:h"), match(event(amount = 4000, eventKey = null, hash = "h"), emptyList()))
    }

    @Test
    fun `the same event again finds its row by the gw key`() {
        val rows = listOf(row(9, key = "gw:evt-1", origin = RefundOrigin.GATEWAY, status = RefundStatus.SUCCEEDED, amount = 4000))
        assertEquals(Match.Existing(9, Rule.DUPLICATE_GATEWAY_KEY), match(event(amount = 4000, eventKey = "evt-1"), rows))
    }

    @Test
    fun `an unmatched event is applied at most once per payment and amount within 24 hours`() {
        val prior = row(1, key = "gw:old", origin = RefundOrigin.GATEWAY, status = RefundStatus.SUCCEEDED, amount = 4000, createdAt = now - 23 * hour)
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(event(amount = 4000, eventKey = "new"), listOf(prior)))
        // another amount, or older than 24 h, or of a failed / cancelled attempt: applied
        assertEquals(Match.Insert(4100, "gw:new"), match(event(amount = 4100, eventKey = "new"), listOf(prior)))
        val old = prior.copy(createdAt = now - 25 * hour)
        assertEquals(Match.Insert(4000, "gw:new"), match(event(amount = 4000, eventKey = "new"), listOf(old)))
        for (status in listOf(RefundStatus.FAILED, RefundStatus.CANCELLED)) {
            assertEquals(Match.Insert(4000, "gw:new"), match(event(amount = 4000, eventKey = "new"), listOf(prior.copy(status = status))), status.name)
        }
        // another payment's refund of the same amount does not count
        assertEquals(Match.Insert(4000, "gw:new"), match(event(amount = 4000, eventKey = "new"), listOf(prior.copy(paymentId = 77))))
        // a panel refund of the same amount is not a gateway-originated one
        assertEquals(Match.Insert(4000, "gw:new"), match(event(amount = 4000, eventKey = "new"), listOf(prior.copy(origin = RefundOrigin.PANEL, status = RefundStatus.SUCCEEDED, idempotencyKey = "p"))))
    }

    @Test
    fun `an unmatched event never takes more than the attempt can still give back`() {
        val a = attempt.copy(paidAmount = 10000, refundedAmount = 7000)
        assertEquals(Match.Insert(3000, "gw:e", clamped = true), match(event(amount = 5000, eventKey = "e"), emptyList(), a))
        assertEquals(Match.Insert(3000, "gw:e"), match(event(amount = 3000, eventKey = "e"), emptyList(), a))
        // null amount: the remaining collected amount
        assertEquals(Match.Insert(3000, "gw:e"), match(event(amount = null, eventKey = "e"), emptyList(), a))
        // nothing left: no row, and the service is asked for the alert
        val spent = attempt.copy(paidAmount = 10000, refundedAmount = 10000)
        assertEquals(Match.NoOp(NoOpReason.NOTHING_REFUNDABLE, alert = true), match(event(amount = 100, eventKey = "e"), emptyList(), spent))
        assertEquals(Match.NoOp(NoOpReason.NOTHING_REFUNDABLE, alert = true), match(event(amount = null, eventKey = "e"), emptyList(), spent))
        // an amount of 0 with room left is nothing to book and no alert
        assertEquals(Match.NoOp(NoOpReason.NOTHING_REFUNDABLE), match(event(amount = 0, eventKey = "e"), emptyList(), a))
    }

    @Test
    fun `the state of the event does not change which row it is`() {
        val rows = listOf(row(1, amount = 2500))
        for (state in RefundState.values()) {
            assertEquals(Match.Existing(1, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(state, amount = 2500), rows), state.name)
        }
    }

    // ---------------------------------------------------------------- properties

    @Test
    fun `random histories never book a notification twice nor match a row that is not open without an id`() {
        val rnd = Random(115L)
        val statuses = RefundStatus.values()
        var existing = 0
        var inserted = 0
        var noop = 0
        repeat(20000) { n ->
            val rows = (1..rnd.nextInt(5)).map {
                row(
                    it.toLong(),
                    status = statuses[rnd.nextInt(statuses.size)],
                    amount = (1 + rnd.nextInt(3)) * 1000L,
                    gatewayRefundId = if (rnd.nextBoolean()) "re_$it" else null,
                    paymentId = if (rnd.nextInt(6) == 0) 99L else 5L,
                    origin = if (rnd.nextInt(3) == 0) RefundOrigin.GATEWAY else RefundOrigin.PANEL,
                    createdAt = now - rnd.nextInt(48) * hour
                )
            }
            val withId = rnd.nextInt(4) == 0
            val e = event(
                state = RefundState.values()[rnd.nextInt(4)],
                amount = if (rnd.nextInt(5) == 0) null else (1 + rnd.nextInt(3)) * 1000L,
                gid = if (withId) "re_${1 + rnd.nextInt(6)}" else null,
                cumulative = if (rnd.nextInt(8) == 0) rnd.nextInt(12) * 1000L else null,
                eventKey = "ev$n"
            )
            val a = attempt.copy(refundedAmount = rnd.nextInt(10) * 1000L)
            val m = match(e, rows, a)
            when (m) {
                is Match.Existing -> {
                    existing++
                    val r = rows.single { it.id == m.refundId }
                    when (m.rule) {
                        Rule.GATEWAY_REFUND_ID -> assertTrue(r.gatewayRefundId == e.gatewayRefundId && r.providerId == a.providerId)
                        Rule.OLDEST_OPEN_BY_AMOUNT, Rule.ONLY_OPEN -> {
                            assertTrue(e.gatewayRefundId == null && e.refundKey == null)
                            assertTrue(r.status == RefundStatus.REQUESTED || r.status == RefundStatus.PENDING)
                            assertEquals(a.paymentId, r.paymentId)
                            if (m.rule == Rule.OLDEST_OPEN_BY_AMOUNT) {
                                assertEquals(e.amount, r.gatewayAmount)
                                val older = rows.filter {
                                    it.paymentId == a.paymentId && (it.status == RefundStatus.REQUESTED || it.status == RefundStatus.PENDING) &&
                                        it.gatewayAmount == e.amount && (it.createdAt < r.createdAt || (it.createdAt == r.createdAt && it.id < r.id))
                                }
                                assertTrue(older.isEmpty(), "an older open row of the same amount exists")
                            }
                        }
                        else -> Unit
                    }
                }
                is Match.Insert -> {
                    inserted++
                    assertTrue(m.amount > 0, "an inserted row has an amount")
                    assertTrue(m.amount <= a.paidAmount - a.refundedAmount, "never beyond paidAmount - refundedAmount")
                    assertEquals("gw:ev$n", m.idempotencyKey)
                }
                is Match.NoOp -> noop++
            }
        }
        assertTrue(existing > 2000 && inserted > 2000 && noop > 100, "mix: $existing existing, $inserted inserted, $noop no-op")
    }

    @Test
    fun `replaying a snapshot after it was booked is a no-op`() {
        // first delivery: cumulative 4000, nothing known
        val first = match(event(cumulative = 4000, eventKey = "s1"), emptyList())
        assertEquals(Match.Insert(4000, "gw:s1"), first)
        // after the insert reached SUCCEEDED the payment's refundedAmount is 4000: the same snapshot repeats what is known
        val after = attempt.copy(refundedAmount = 4000)
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(cumulative = 4000, eventKey = "s1b"), emptyList(), after))
        // and a PENDING gateway row counts as known as well
        val pending = listOf(row(1, key = "gw:s1", origin = RefundOrigin.GATEWAY, status = RefundStatus.PENDING, amount = 4000))
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(gid = "re_x", cumulative = 4000, eventKey = "s1c"), pending))
    }
}
