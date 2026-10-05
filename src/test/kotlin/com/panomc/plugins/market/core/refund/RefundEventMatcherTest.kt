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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
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
        createdAt: Long = now - hour * id,
        completedAt: Long? = null
    ) = RefundRow(id, paymentId, providerId, origin, status, key, gatewayRefundId, amount, createdAt, completedAt)

    private fun event(
        state: RefundState = RefundState.SUCCEEDED,
        amount: Long? = null,
        key: String? = null,
        gid: String? = null,
        cumulative: Long? = null,
        eventKey: String? = null,
        hash: String? = "h1"
    ) = Event(state, amount, key, gid, cumulative, eventKey, hash)

    // The documented key formulas, written out here independently of the production code.
    private fun sha256Hex(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun gidKey(providerId: String, gid: String) = "gw:" + sha256Hex("$providerId:$gid").take(61)

    private fun cumKey(paymentId: Long, cumulative: Long) = "gw:" + sha256Hex("$paymentId:cum:$cumulative").take(61)

    private fun unknownKeyKey(providerId: String, refundKey: String) = "gw:" + sha256Hex("$providerId:key:$refundKey").take(61)

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
        val none = match(event(amount = null, eventKey = "e2"), listOf(row(1, status = RefundStatus.SUCCEEDED, createdAt = now - 30 * hour)))
        assertEquals(Match.Insert(10000, "gw:e2"), none)
        val two = match(event(amount = null, eventKey = "e3"), listOf(row(1), row(2)))
        assertEquals(Match.Insert(10000, "gw:e3"), two)
    }

    @Test
    fun `only the rows of this attempt and only open ones are candidates`() {
        val rows = listOf(
            row(1, amount = 2500, paymentId = 99), // another attempt
            row(2, amount = 2500, paymentId = null, providerId = null), // credit-only refund
            row(3, amount = 2500, status = RefundStatus.SUCCEEDED, createdAt = now - 30 * hour), // settled long ago: no candidate, no 24 h hit
            row(4, amount = 2500, status = RefundStatus.FAILED)
        )
        // nothing open for this amount: falls through to the gateway row
        assertEquals(Match.Insert(2500, "gw:e"), match(event(amount = 2500, eventKey = "e"), rows))
    }

    @Test
    fun `an event with a gateway id no row has yet is the open row that has not stored an id, before tx2 or after a timeout`() {
        // the confirming webhook arrives before tx2 stored the gatewayRefundId: it carries re_new, the open row has none
        val rows = listOf(row(1, amount = 2500, gatewayRefundId = null))
        assertEquals(Match.Existing(1, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(amount = 2500, gid = "re_new", eventKey = "e1"), rows))
        // the oldest of two such rows
        val two = listOf(row(1, amount = 2500, createdAt = now - hour), row(2, amount = 2500, createdAt = now - 2 * hour))
        assertEquals(Match.Existing(2, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(amount = 2500, gid = "re_new"), two))
        // without an amount: the only open row that has no id
        assertEquals(Match.Existing(1, Rule.ONLY_OPEN), match(event(amount = null, gid = "re_new"), rows))
        val withOther = listOf(row(1, amount = 2500), row(2, amount = 2500, gatewayRefundId = "re_other", status = RefundStatus.PENDING))
        assertEquals(Match.Existing(1, Rule.ONLY_OPEN), match(event(amount = null, gid = "re_new"), withOther))
        // two rows without an id and no amount: ambiguous, so no match
        assertTrue(match(event(amount = null, gid = "re_new", eventKey = "e1"), listOf(row(1), row(2))) is Match.Insert)
        // the state of the event does not matter for which row it is
        for (state in RefundState.values()) {
            assertEquals(Match.Existing(1, Rule.OLDEST_OPEN_BY_AMOUNT), match(event(state, amount = 2500, gid = "re_new"), rows), state.name)
        }
    }

    @Test
    fun `an event with an unknown gateway id is another refund when the open row already carries a different id or has another amount`() {
        // the open row has its own gateway id: re_new is a different refund of the same amount (a dashboard refund), a gateway row
        val own = listOf(row(1, amount = 2500, gatewayRefundId = "re_own", status = RefundStatus.PENDING))
        assertEquals(Match.Insert(2500, gidKey("stripe", "re_new")), match(event(amount = 2500, gid = "re_new", eventKey = "e1"), own))
        // another amount: another refund
        val other = listOf(row(1, amount = 1000, gatewayRefundId = null))
        assertEquals(Match.Insert(2500, gidKey("stripe", "re_new")), match(event(amount = 2500, gid = "re_new"), other))
        // a settled or failed row without an id is not a candidate either
        val closed = listOf(
            row(1, amount = 2500, status = RefundStatus.FAILED), row(2, amount = 2500, status = RefundStatus.CANCELLED),
            row(3, amount = 2500, status = RefundStatus.SUCCEEDED, createdAt = now - 30 * hour)
        )
        assertEquals(Match.Insert(2500, gidKey("stripe", "re_new")), match(event(amount = 2500, gid = "re_new"), closed))
    }

    @Test
    fun `an unknown refund key is a foreign refund and never matches an open row by amount`() {
        val rows = listOf(row(1, amount = 2500, gatewayRefundId = null))
        assertEquals(
            Match.Insert(2500, unknownKeyKey("stripe", "unknown")),
            match(event(amount = 2500, key = "unknown", eventKey = "e1"), rows)
        )
        // an unknown key with an id that finds a row is that row
        val known = listOf(row(1, gatewayRefundId = "re_1"))
        assertEquals(Match.Existing(1, Rule.GATEWAY_REFUND_ID), match(event(key = "unknown", gid = "re_1"), known))
    }

    // ---------------------------------------------------------------- rule 4: snapshots

    @Test
    fun `a cumulative snapshot that repeats what is known is a no-op`() {
        val a = attempt.copy(refundedAmount = 3000)
        val open = listOf(row(1, amount = 2000, gatewayRefundId = "re_open"))
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
        assertEquals(Match.Insert(1500, cumKey(5, 6500)), match(event(amount = 1500, cumulative = 6500, eventKey = "s2"), open, a))
        // the snapshot carries no amount of its own: the difference is what counts (a row with its own id is not a candidate)
        assertEquals(Match.Insert(1500, gidKey("stripe", "re_x")), match(event(gid = "re_x", cumulative = 6500, eventKey = "s2"), open, a))
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
        assertEquals(Match.Insert(7000, cumKey(5, 12000), clamped = true), match(event(cumulative = 12000, eventKey = "s3"), emptyList(), a))
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
        // a panel refund of the same amount counts as well: the notification of a refund market made itself is no second refund
        val panel = prior.copy(origin = RefundOrigin.PANEL, status = RefundStatus.SUCCEEDED, idempotencyKey = "p")
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(event(amount = 4000, eventKey = "new"), listOf(panel)))
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(event(amount = 4000, eventKey = null, hash = "h"), listOf(panel)))
    }

    // ---------------------------------------------------------------- V-06: a notification delivered twice is booked once

    @Test
    fun `V-06 a notification without ids of a panel refund delivered twice is not booked a second time`() {
        // paid 100.00, panel refund 30.00 open; the id-less webhook confirms it (no eventKey: always re-applied, 02 section 7.3 step 5)
        val confirming = event(RefundState.SUCCEEDED, amount = 3000, eventKey = null, hash = "h-hook")
        val open = listOf(row(1, status = RefundStatus.REQUESTED, amount = 3000, createdAt = now - 5 * 60_000))
        assertEquals(Match.Existing(1, Rule.OLDEST_OPEN_BY_AMOUNT), match(confirming, open))
        // first delivery settled the row (completedAt = now): the redelivery finds no open row, no gw: key
        val settled = listOf(open[0].copy(status = RefundStatus.SUCCEEDED, completedAt = now - 60_000))
        val after = attempt.copy(refundedAmount = 3000)
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(confirming, settled, after))
        // and again, and with the amount missing from the notification
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(confirming, settled, after))
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(event(RefundState.SUCCEEDED, amount = null, hash = "h-hook"), settled, after))
    }

    @Test
    fun `V-06 a row created long ago but settled by the notification still counts, one completed more than 24 hours ago does not`() {
        val confirming = event(RefundState.SUCCEEDED, amount = 3000, eventKey = null, hash = "h-hook")
        val lateRow = row(1, status = RefundStatus.SUCCEEDED, amount = 3000, createdAt = now - 40 * hour, completedAt = now - 10 * 60_000)
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(confirming, listOf(lateRow)))
        val oldRow = lateRow.copy(completedAt = now - 25 * hour)
        assertEquals(Match.Insert(3000, "gw:h-hook"), match(confirming, listOf(oldRow)))
        // a refund the panel settled synchronously (the provider answered Succeeded) and the gateway then notifies without an id
        val sync = row(1, status = RefundStatus.SUCCEEDED, amount = 3000, createdAt = now - 2 * hour, completedAt = now - 2 * hour)
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(confirming, listOf(sync)))
    }

    @Test
    fun `V-06 an amount-less notification is applied once, a replay finds the refund that reached its state within 24 hours`() {
        val noAmount = event(RefundState.SUCCEEDED, amount = null, eventKey = null, hash = "h-full")
        // first delivery: nothing known, the remaining collected amount is booked
        assertEquals(Match.Insert(10000, "gw:h-full"), match(noAmount, emptyList()))
        // after the booking the attempt is fully refunded: the replay is a no-op without the false OVER_REFUND alert
        val booked = listOf(row(7, key = "other", origin = RefundOrigin.GATEWAY, status = RefundStatus.SUCCEEDED, amount = 10000, createdAt = now - 60_000, completedAt = now - 60_000))
        val spent = attempt.copy(refundedAmount = 10000)
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(noAmount, booked, spent))
        // a refund that has not reached the state does not count: a PENDING one for a SUCCEEDED notification (two open rows, no match)
        val twoOpen = listOf(row(1), row(2, createdAt = now - 3 * hour))
        assertEquals(Match.Insert(10000, "gw:h-full"), match(noAmount, twoOpen))
        // and a PENDING notification is already served by a refund that went on to SUCCEEDED
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(noAmount.copy(state = RefundState.PENDING), booked, spent))
    }

    @Test
    fun `a replayed failure of a refund that was recorded failed is not recorded again`() {
        val failed = event(RefundState.FAILED, amount = 4000, eventKey = null, hash = "h-fail")
        val failedRow = row(1, status = RefundStatus.FAILED, amount = 4000, origin = RefundOrigin.GATEWAY, key = "gw:h-fail")
        // the same request: found by its key
        assertEquals(Match.Existing(1, Rule.DUPLICATE_GATEWAY_KEY), match(failed, listOf(failedRow)))
        // another request for the same failure: the recorded failure counts
        assertEquals(Match.NoOp(NoOpReason.SAME_AMOUNT_WITHIN_24H), match(failed.copy(requestHash = "h-fail2"), listOf(failedRow)))
        // but a failed row says nothing about a success of that amount
        assertEquals(Match.Insert(4000, "gw:h-ok"), match(event(RefundState.SUCCEEDED, amount = 4000, hash = "h-ok"), listOf(failedRow)))
    }

    // ---------------------------------------------------------------- two genuine refunds of one amount

    @Test
    fun `two gateway-side refunds of the same amount within 24 hours with different ids are both booked`() {
        // re_A, 40.00, one hour ago; the gateway now reports re_B, also 40.00: money that left must not be dropped (07 section 7.2)
        val reA = row(1, key = gidKey("stripe", "re_A"), origin = RefundOrigin.GATEWAY, status = RefundStatus.SUCCEEDED, amount = 4000, gatewayRefundId = "re_A", createdAt = now - hour, completedAt = now - hour)
        val a = attempt.copy(refundedAmount = 4000)
        val m = match(event(amount = 4000, gid = "re_B", eventKey = "e-b"), listOf(reA), a)
        assertEquals(Match.Insert(4000, gidKey("stripe", "re_B")), m)
        // a different request hash or event key does not change the identity of the refund
        assertEquals(m, match(event(amount = 4000, gid = "re_B", eventKey = "e-b2", hash = "other"), listOf(reA), a))
        // re_A again (a redelivery) is the row of re_A, not a new one
        assertEquals(Match.Existing(1, Rule.GATEWAY_REFUND_ID), match(event(amount = 4000, gid = "re_A", eventKey = "e-a2"), listOf(reA), a))
        // and once re_B is booked and carries its id, its own redelivery finds it
        val reB = reA.copy(id = 2, idempotencyKey = gidKey("stripe", "re_B"), gatewayRefundId = "re_B")
        assertEquals(Match.Existing(2, Rule.GATEWAY_REFUND_ID), match(event(amount = 4000, gid = "re_B"), listOf(reA, reB), a.copy(refundedAmount = 8000)))
        // the cap still holds
        val c = match(event(amount = 4000, gid = "re_C", eventKey = "e-c"), listOf(reA, reB), a.copy(refundedAmount = 8000))
        assertEquals(Match.Insert(2000, gidKey("stripe", "re_C"), clamped = true), c)
    }

    @Test
    fun `an id-carrying event whose key is already a row (the id was not stored on it) is that row`() {
        val stored = row(4, key = gidKey("stripe", "re_Z"), origin = RefundOrigin.GATEWAY, status = RefundStatus.SUCCEEDED, amount = 1000, gatewayRefundId = null)
        assertEquals(Match.Existing(4, Rule.DUPLICATE_GATEWAY_KEY), match(event(amount = 1000, gid = "re_Z"), listOf(stored)))
    }

    // ---------------------------------------------------------------- the gw: key

    @Test
    fun `the key of an inserted row fits the 64 character column for every kind of identity`() {
        val hash64 = "a".repeat(64)
        val eventKey126 = "e".repeat(126)
        val gid191 = "r".repeat(191)
        val long = attempt.copy(providerId = "p".repeat(64))
        val keys = listOf(
            (match(event(amount = 100, hash = hash64, eventKey = null), emptyList()) as Match.Insert).idempotencyKey,
            (match(event(amount = 100, eventKey = eventKey126), emptyList()) as Match.Insert).idempotencyKey,
            (match(event(amount = 100, gid = gid191), emptyList(), long) as Match.Insert).idempotencyKey,
            (match(event(amount = 100, cumulative = 100), emptyList(), long) as Match.Insert).idempotencyKey,
            (match(event(amount = 100, key = "k".repeat(64)), emptyList(), long) as Match.Insert).idempotencyKey,
            (match(event(amount = 100, eventKey = "e".repeat(61)), emptyList()) as Match.Insert).idempotencyKey,
            (match(event(amount = 100, eventKey = "e".repeat(62)), emptyList()) as Match.Insert).idempotencyKey
        )
        for (k in keys) {
            assertTrue(k.startsWith("gw:"), k)
            assertTrue(k.length <= RefundEventMatcher.MAX_KEY_LENGTH, "${k.length} characters: $k")
        }
        // a raw key that fits is kept as it is, a longer one is hashed to the same length for the same input every time
        assertEquals("gw:" + "e".repeat(61), keys[5])
        assertEquals("gw:" + sha256Hex("e".repeat(62)).take(61), keys[6])
        assertEquals("gw:" + sha256Hex(hash64).take(61), keys[0])
        assertEquals(keys[1], (match(event(amount = 100, eventKey = eventKey126), emptyList()) as Match.Insert).idempotencyKey)
        // two long keys that differ only at the end stay different
        val k2 = (match(event(amount = 100, eventKey = "e".repeat(125) + "f"), emptyList()) as Match.Insert).idempotencyKey
        assertNotEquals(keys[1], k2)
    }

    @Test
    fun `two snapshots that arrive with the same request hash are two facts`() {
        // Mollie posts the identical body for every state change of a payment: the hash is no identity (01 section 6.3)
        val first = match(event(cumulative = 1500, eventKey = null, hash = "same"), emptyList()) as Match.Insert
        assertEquals(Match.Insert(1500, cumKey(5, 1500)), first)
        val second = match(
            event(cumulative = 4000, eventKey = null, hash = "same"),
            listOf(row(1, key = first.idempotencyKey, origin = RefundOrigin.GATEWAY, status = RefundStatus.SUCCEEDED, amount = 1500)),
            attempt.copy(refundedAmount = 1500)
        )
        assertEquals(Match.Insert(2500, cumKey(5, 4000)), second)
        assertNotEquals(first.idempotencyKey, (second as Match.Insert).idempotencyKey)
        // the first snapshot again is the first row, not a new one (the key, even when the payment books are behind)
        val again = match(
            event(cumulative = 1500, eventKey = null, hash = "same"),
            listOf(row(1, key = first.idempotencyKey, origin = RefundOrigin.GATEWAY, status = RefundStatus.FAILED, amount = 1500))
        )
        assertEquals(Match.Existing(1, Rule.DUPLICATE_GATEWAY_KEY), again)
    }

    @Test
    fun `an id names the fact, whatever the event key or the request hash say`() {
        val a = match(event(amount = 100, gid = "re_1", eventKey = "x", hash = "h1"), emptyList()) as Match.Insert
        val b = match(event(amount = 100, gid = "re_1", eventKey = null, hash = "h2"), emptyList()) as Match.Insert
        assertEquals(a.idempotencyKey, b.idempotencyKey)
        // another id, or the same id at another provider, is another key
        val c = match(event(amount = 100, gid = "re_2", eventKey = "x"), emptyList()) as Match.Insert
        val d = match(event(amount = 100, gid = "re_1", eventKey = "x"), emptyList(), attempt.copy(providerId = "mollie")) as Match.Insert
        assertEquals(3, setOf(a.idempotencyKey, c.idempotencyKey, d.idempotencyKey).size)
        assertEquals(gidKey("stripe", "re_1"), a.idempotencyKey)
        assertEquals(gidKey("mollie", "re_1"), d.idempotencyKey)
        // an unknown refund key names the fact as well
        val e1 = match(event(amount = 100, key = "foreign-1", eventKey = "x"), emptyList()) as Match.Insert
        val e2 = match(event(amount = 100, key = "foreign-1", eventKey = "y", hash = "z"), emptyList()) as Match.Insert
        assertEquals(e1.idempotencyKey, e2.idempotencyKey)
        assertEquals(unknownKeyKey("stripe", "foreign-1"), e1.idempotencyKey)
    }

    @Test
    fun `an event with no id, no cumulative value, no event key and no request hash cannot be keyed`() {
        // an event of queryPayment / reconcile must carry a synthetic eventKey; "gw:" shared by the whole shop would collide on uq_idem
        assertThrows(IllegalArgumentException::class.java) { match(event(amount = 100, eventKey = null, hash = null), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { match(event(amount = 100, eventKey = "", hash = ""), emptyList()) }
        // a snapshot or an id needs neither
        assertTrue(match(event(cumulative = 100, eventKey = null, hash = null), emptyList()) is Match.Insert)
        assertTrue(match(event(amount = 100, gid = "re_1", eventKey = null, hash = null), emptyList()) is Match.Insert)
        // an event that matches a row never needs a key
        assertTrue(match(event(amount = 2500, eventKey = null, hash = null), listOf(row(1))) is Match.Existing)
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
                            // rule 3: never with a refund key; with a gateway id only a row that has not stored an id yet
                            assertTrue(e.refundKey == null && (e.gatewayRefundId == null || r.gatewayRefundId == null))
                            assertTrue(r.status == RefundStatus.REQUESTED || r.status == RefundStatus.PENDING)
                            assertEquals(a.paymentId, r.paymentId)
                            if (m.rule == Rule.OLDEST_OPEN_BY_AMOUNT) {
                                assertEquals(e.amount, r.gatewayAmount)
                                val older = rows.filter {
                                    it.paymentId == a.paymentId && (it.status == RefundStatus.REQUESTED || it.status == RefundStatus.PENDING) &&
                                        (e.gatewayRefundId == null || it.gatewayRefundId == null) &&
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
                    assertTrue(m.idempotencyKey.length <= RefundEventMatcher.MAX_KEY_LENGTH, "key ${m.idempotencyKey}")
                    val expectedKey = when {
                        e.gatewayRefundId != null -> gidKey(a.providerId, e.gatewayRefundId!!)
                        e.cumulativeRefunded != null -> cumKey(a.paymentId, e.cumulativeRefunded!!)
                        else -> "gw:ev$n"
                    }
                    assertEquals(expectedKey, m.idempotencyKey)
                    // an id-carrying event is never dropped as "the same amount again": two refunds of one amount are two refunds
                    if (e.gatewayRefundId != null) assertTrue(m.amount > 0)
                }
                is Match.NoOp -> noop++
            }
        }
        assertTrue(existing > 2000 && inserted > 2000 && noop > 100, "mix: $existing existing, $inserted inserted, $noop no-op")
    }

    /** What the service does with a match: moves the matched row by the state machine, or inserts the gateway row and stores the id. */
    private fun applied(e: Event, m: Match, rows: List<RefundRow>, a: Attempt): Pair<List<RefundRow>, Attempt> = when (m) {
        is Match.NoOp -> rows to a
        is Match.Existing -> {
            var delta = 0L
            val moved = rows.map { r ->
                if (r.id != m.refundId) r
                else when (val t = RefundStateMachine.decide(RefundRowState(r.status), RefundEvent.Reported(e.state))) {
                    is RefundTransition.Move -> {
                        if (t.to == RefundStatus.SUCCEEDED && r.paymentId == a.paymentId) delta += r.gatewayAmount
                        r.copy(status = t.to, gatewayRefundId = r.gatewayRefundId ?: e.gatewayRefundId, completedAt = if (t.to == RefundStatus.SUCCEEDED) now else r.completedAt)
                    }
                    else -> r
                }
            }
            moved to a.copy(refundedAmount = a.refundedAmount + delta)
        }
        is Match.Insert -> {
            val t = RefundStateMachine.insert(RefundOrigin.GATEWAY, e.state)
            val ok = t.to == RefundStatus.SUCCEEDED
            val row = RefundRow(
                900L + rows.size, a.paymentId, a.providerId, RefundOrigin.GATEWAY, t.to, m.idempotencyKey, e.gatewayRefundId, m.amount, now,
                if (ok) now else null
            )
            (rows + row) to a.copy(refundedAmount = a.refundedAmount + if (ok) m.amount else 0L)
        }
    }

    @Test
    fun `V-06 property, an event delivered a second time is never booked as a second refund`() {
        val rnd = Random(116L)
        val statuses = RefundStatus.values()
        var inserts = 0
        var existing = 0
        repeat(20000) { n ->
            val rows = (1..rnd.nextInt(5)).map {
                row(
                    it.toLong(),
                    status = statuses[rnd.nextInt(statuses.size)],
                    amount = (1 + rnd.nextInt(3)) * 1000L,
                    gatewayRefundId = if (rnd.nextBoolean()) "re_$it" else null,
                    paymentId = if (rnd.nextInt(6) == 0) 99L else 5L,
                    origin = if (rnd.nextInt(3) == 0) RefundOrigin.GATEWAY else RefundOrigin.PANEL,
                    createdAt = now - rnd.nextInt(20) * hour
                )
            }
            val kind = rnd.nextInt(5) // 0, 1: no id; 2: gateway id; 3: snapshot; 4: unknown refund key
            val e = event(
                state = RefundState.values()[rnd.nextInt(4)],
                amount = if (rnd.nextInt(4) == 0) null else (1 + rnd.nextInt(3)) * 1000L,
                key = if (kind == 4) "foreign" else null,
                gid = if (kind == 2) "re_${1 + rnd.nextInt(7)}" else null,
                cumulative = if (kind == 3) rnd.nextInt(12) * 1000L else null,
                eventKey = if (rnd.nextBoolean()) "ev$n" else null,
                hash = "hash$n"
            )
            val a = attempt.copy(refundedAmount = rnd.nextInt(10) * 1000L)
            val first = match(e, rows, a)
            val (rows2, a2) = applied(e, first, rows, a)
            val second = match(e, rows2, a2)
            if (first is Match.Insert) {
                inserts++
                assertTrue(second !is Match.Insert, "event inserted twice: $e first=$first second=$second rows=$rows")
            }
            // an event without a cumulative value that matched a row is not booked again either (a snapshot may legitimately
            // book the part of its total that the matched row did not hold)
            if (first is Match.Existing && e.cumulativeRefunded == null) {
                existing++
                assertTrue(second !is Match.Insert, "event matched a row, then inserted: $e first=$first second=$second rows=$rows")
            }
        }
        assertTrue(inserts > 2000 && existing > 2000, "mix: $inserts inserts, $existing matches")
    }

    @Test
    fun `replaying a snapshot after it was booked is a no-op`() {
        // first delivery: cumulative 4000, nothing known
        val first = match(event(cumulative = 4000, eventKey = "s1"), emptyList())
        assertEquals(Match.Insert(4000, cumKey(5, 4000)), first)
        // after the insert reached SUCCEEDED the payment's refundedAmount is 4000: the same snapshot repeats what is known
        val after = attempt.copy(refundedAmount = 4000)
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(cumulative = 4000, eventKey = "s1b"), emptyList(), after))
        // and a PENDING gateway row counts as known as well
        val pending = listOf(row(1, key = "gw:s1", origin = RefundOrigin.GATEWAY, status = RefundStatus.PENDING, amount = 4000, gatewayRefundId = "re_p"))
        assertEquals(Match.NoOp(NoOpReason.SNAPSHOT_KNOWN), match(event(gid = "re_x", cumulative = 4000, eventKey = "s1c"), pending))
        // a PENDING row that has not stored an id yet is the one an id-carrying snapshot without amount may be about (rule 3)
        val idless = listOf(row(1, key = "gw:s1", origin = RefundOrigin.GATEWAY, status = RefundStatus.PENDING, amount = 4000))
        assertEquals(Match.Existing(1, Rule.ONLY_OPEN), match(event(gid = "re_x", cumulative = 4000, eventKey = "s1c"), idless))
    }
}
