package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.core.shipping.TrackingSource.MANUAL
import com.panomc.plugins.market.core.shipping.TrackingSource.POLL
import com.panomc.plugins.market.core.shipping.TrackingSource.WEBHOOK
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShipmentStatus.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `ShipmentStateMachine` (10 section 7.1 and 7.2 steps 5 and 6, tests 41 to 44 of section 16). */
class ShipmentStateMachineTest {
    private val carrier = listOf(WEBHOOK, POLL)

    /** The table of 10 section 7.1, written out again from the spec: (current) -> candidates allowed for carrier sources. */
    private val carrierTable: Map<ShipmentStatus, Set<ShipmentStatus>> = mapOf(
        CREATED to setOf(LABEL_READY, IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION, RETURNING, DELIVERED, RETURNED, LOST, CANCELLED),
        LABEL_READY to setOf(IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION, RETURNING, DELIVERED, RETURNED, LOST, CANCELLED),
        IN_TRANSIT to setOf(OUT_FOR_DELIVERY, EXCEPTION, RETURNING, DELIVERED, RETURNED, LOST),
        OUT_FOR_DELIVERY to setOf(IN_TRANSIT, EXCEPTION, RETURNING, DELIVERED, RETURNED, LOST),
        EXCEPTION to setOf(IN_TRANSIT, OUT_FOR_DELIVERY, RETURNING, DELIVERED, RETURNED, LOST),
        RETURNING to setOf(IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION, DELIVERED, RETURNED, LOST),
        DELIVERED to emptySet(),
        RETURNED to emptySet(),
        CANCELLED to emptySet(),
        LOST to emptySet()
    )

    private val manualExtra: Map<ShipmentStatus, Set<ShipmentStatus>> = mapOf(
        DELIVERED to setOf(RETURNING, RETURNED),
        LOST to setOf(IN_TRANSIT, DELIVERED)
    )

    @Test
    fun `every current and candidate pair for carrier sources matches the table`() {
        for (current in ShipmentStatus.values()) {
            for (candidate in ShipmentStatus.values()) {
                for (source in carrier) {
                    val expected = candidate.takeIf { it in carrierTable.getValue(current) }

                    assertEquals(expected, ShipmentStateMachine.decide(current, candidate, source), "$current -> $candidate by $source")
                }
            }
        }
    }

    @Test
    fun `every pair for a manual source is the table plus the two manual exits and never cancelled`() {
        for (current in ShipmentStatus.values()) {
            for (candidate in ShipmentStatus.values()) {
                val allowed = (carrierTable.getValue(current) + (manualExtra[current] ?: emptySet())) - CANCELLED
                val expected = candidate.takeIf { it in allowed }

                assertEquals(expected, ShipmentStateMachine.decide(current, candidate, MANUAL), "$current -> $candidate by MANUAL")
            }
        }
    }

    @Test
    fun `terminal states are never left by a carrier`() {
        for (terminal in ShipmentStateMachine.TERMINAL) {
            for (candidate in ShipmentStatus.values()) {
                carrier.forEach { assertNull(ShipmentStateMachine.decide(terminal, candidate, it), "$terminal -> $candidate") }
            }
        }
    }

    @Test
    fun `manual may leave delivered and lost but not returned or cancelled`() {
        assertEquals(RETURNING, ShipmentStateMachine.decide(DELIVERED, RETURNING, MANUAL))
        assertEquals(RETURNED, ShipmentStateMachine.decide(DELIVERED, RETURNED, MANUAL))
        assertNull(ShipmentStateMachine.decide(DELIVERED, IN_TRANSIT, MANUAL))
        assertEquals(IN_TRANSIT, ShipmentStateMachine.decide(LOST, IN_TRANSIT, MANUAL))
        assertEquals(DELIVERED, ShipmentStateMachine.decide(LOST, DELIVERED, MANUAL))
        assertNull(ShipmentStateMachine.decide(LOST, RETURNED, MANUAL))
        ShipmentStatus.values().forEach {
            assertNull(ShipmentStateMachine.decide(RETURNED, it, MANUAL))
            assertNull(ShipmentStateMachine.decide(CANCELLED, it, MANUAL))
        }
    }

    @Test
    fun `created and label ready are never re-entered after the carrier has the parcel`() {
        for (current in ShipmentStateMachine.HANDED) {
            for (source in TrackingSource.values()) {
                assertNull(ShipmentStateMachine.decide(current, CREATED, source))
                assertNull(ShipmentStateMachine.decide(current, LABEL_READY, source))
            }
        }
    }

    @Test
    fun `a failed delivery attempt may go back to in transit`() {
        assertEquals(IN_TRANSIT, ShipmentStateMachine.decide(OUT_FOR_DELIVERY, IN_TRANSIT, POLL))
    }

    @Test
    fun `cancelled by a carrier is possible before pick-up only and a manual cancel is not a status edit`() {
        assertEquals(CANCELLED, ShipmentStateMachine.decide(CREATED, CANCELLED, WEBHOOK))
        assertEquals(CANCELLED, ShipmentStateMachine.decide(LABEL_READY, CANCELLED, POLL))
        assertNull(ShipmentStateMachine.decide(IN_TRANSIT, CANCELLED, WEBHOOK))
        assertNull(ShipmentStateMachine.decide(CREATED, CANCELLED, MANUAL))
    }

    @Test
    fun `the same status is no change`() {
        ShipmentStatus.values().forEach { s -> TrackingSource.values().forEach { assertNull(ShipmentStateMachine.decide(s, s, it)) } }
    }

    @Test
    fun `status sets`() {
        assertEquals(setOf(IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION, RETURNING), ShipmentStateMachine.MOVING)
        assertEquals(setOf(DELIVERED, RETURNED, CANCELLED, LOST), ShipmentStateMachine.TERMINAL)
        assertEquals(ShipmentStateMachine.MOVING + setOf(DELIVERED, RETURNED, LOST), ShipmentStateMachine.HANDED)
        assertFalse(CREATED in ShipmentStateMachine.HANDED || LABEL_READY in ShipmentStateMachine.HANDED || CANCELLED in ShipmentStateMachine.HANDED)
    }

    // ---- out-of-order events (resolve) ----

    private fun ev(id: Long, status: ShipmentStatus, at: Long, source: TrackingSource = POLL) = TrackedEvent(id, status, at, source)

    @Test
    fun `delivered then an older out for delivery stays delivered`() {
        val first = ShipmentStateMachine.resolve(IN_TRANSIT, listOf(ev(1, DELIVERED, 5)))
        assertEquals(DELIVERED, first.next)
        assertEquals(5L, first.deliveredAt)
        assertTrue(first.clearNextPoll)

        // the older event arrives later: both stored, newest is still DELIVERED, current is DELIVERED -> no change
        val second = ShipmentStateMachine.resolve(DELIVERED, listOf(ev(1, DELIVERED, 5), ev(2, OUT_FOR_DELIVERY, 4)))
        assertNull(second.next)
        assertNull(second.deliveredAt)
        assertFalse(second.clearNextPoll)
    }

    @Test
    fun `out for delivery then a newer in transit goes back to in transit`() {
        val r = ShipmentStateMachine.resolve(OUT_FOR_DELIVERY, listOf(ev(1, OUT_FOR_DELIVERY, 4), ev(2, IN_TRANSIT, 6)))

        assertEquals(IN_TRANSIT, r.next)
        assertNull(r.deliveredAt)
        assertFalse(r.clearNextPoll)
    }

    @Test
    fun `the newest event wins regardless of arrival order and ties go to the higher id`() {
        val events = listOf(ev(3, IN_TRANSIT, 10), ev(1, EXCEPTION, 20), ev(2, OUT_FOR_DELIVERY, 20))

        assertEquals(2L, ShipmentStateMachine.newest(events)!!.id)
        assertEquals(OUT_FOR_DELIVERY, ShipmentStateMachine.resolve(IN_TRANSIT, events).next)
        assertEquals(OUT_FOR_DELIVERY, ShipmentStateMachine.resolve(IN_TRANSIT, events.reversed()).next)
        assertNull(ShipmentStateMachine.newest(emptyList()))
        assertNull(ShipmentStateMachine.resolve(IN_TRANSIT, emptyList()).next)
    }

    @Test
    fun `a carrier event after delivered is ignored and a manual returned is applied`() {
        val ignored = ShipmentStateMachine.resolve(DELIVERED, listOf(ev(1, DELIVERED, 5), ev(2, IN_TRANSIT, 9, WEBHOOK)))
        assertNull(ignored.next)

        val manual = ShipmentStateMachine.resolve(DELIVERED, listOf(ev(1, DELIVERED, 5), ev(2, RETURNED, 9, MANUAL)))
        assertEquals(RETURNED, manual.next)
        assertTrue(manual.clearNextPoll)
        assertNull(manual.deliveredAt)
    }

    @Test
    fun `shipped at is the earliest handed event and a replay changes nothing`() {
        val events = listOf(ev(1, LABEL_READY, 1), ev(2, IN_TRANSIT, 7), ev(3, OUT_FOR_DELIVERY, 9), ev(4, IN_TRANSIT, 5))
        val r = ShipmentStateMachine.resolve(LABEL_READY, events)

        assertEquals(5L, r.shippedAt)
        assertEquals(OUT_FOR_DELIVERY, r.next)

        val replay = ShipmentStateMachine.resolve(OUT_FOR_DELIVERY, events)
        assertNull(replay.next)
        assertEquals(5L, replay.shippedAt)

        assertNull(ShipmentStateMachine.resolve(CREATED, listOf(ev(1, LABEL_READY, 1))).shippedAt)
    }

    @Test
    fun `entering a terminal state clears the next poll`() {
        assertTrue(ShipmentStateMachine.resolve(LABEL_READY, listOf(ev(1, CANCELLED, 3))).clearNextPoll)
        assertTrue(ShipmentStateMachine.resolve(IN_TRANSIT, listOf(ev(1, LOST, 3))).clearNextPoll)
        assertFalse(ShipmentStateMachine.resolve(IN_TRANSIT, listOf(ev(1, EXCEPTION, 3))).clearNextPoll)
    }
}
