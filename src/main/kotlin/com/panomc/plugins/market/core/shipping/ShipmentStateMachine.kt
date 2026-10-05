package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShipmentStatus.CANCELLED
import com.panomc.plugins.market.db.model.ShipmentStatus.CREATED
import com.panomc.plugins.market.db.model.ShipmentStatus.DELIVERED
import com.panomc.plugins.market.db.model.ShipmentStatus.EXCEPTION
import com.panomc.plugins.market.db.model.ShipmentStatus.IN_TRANSIT
import com.panomc.plugins.market.db.model.ShipmentStatus.LABEL_READY
import com.panomc.plugins.market.db.model.ShipmentStatus.LOST
import com.panomc.plugins.market.db.model.ShipmentStatus.OUT_FOR_DELIVERY
import com.panomc.plugins.market.db.model.ShipmentStatus.RETURNED
import com.panomc.plugins.market.db.model.ShipmentStatus.RETURNING

/** Where a tracking event comes from (`ShippingService.applyUpdate`, 10 section 7.2). */
enum class TrackingSource { WEBHOOK, POLL, MANUAL }

/** A stored `market_shipment_event` as the state machine reads it. */
class TrackedEvent(val id: Long, val status: ShipmentStatus, val occurredAt: Long, val source: TrackingSource)

/** The decision of [ShipmentStateMachine.resolve]: what the shipment becomes after the events were stored. */
class TrackingResolution(
    /** The new status, `null` = no change. */
    val next: ShipmentStatus?,
    /** `occurredAt` of the earliest stored event whose status is in [ShipmentStateMachine.HANDED] (set `shippedAt` on first entry). */
    val shippedAt: Long?,
    /** `occurredAt` of the event that made the shipment `DELIVERED` (only when [next] is `DELIVERED`). */
    val deliveredAt: Long?,
    /** Entering a terminal state clears `nextPollAt`. */
    val clearNextPoll: Boolean
)

/** The shipment transition table (10 section 7.1, 03 section 4, 00 section 7.6). Pure. */
object ShipmentStateMachine {
    /** The carrier has the parcel. */
    val HANDED: Set<ShipmentStatus> = setOf(IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION, RETURNING, DELIVERED, RETURNED, LOST)

    val MOVING: Set<ShipmentStatus> = setOf(IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION, RETURNING)

    val TERMINAL: Set<ShipmentStatus> = setOf(DELIVERED, RETURNED, CANCELLED, LOST)

    /**
     * The status [current] becomes when [candidate] is reported by [source], `null` = no change (the event is stored
     * anyway). `CANCELLED` from a `MANUAL` source is not a status edit (the cancel use case does it) and is refused.
     */
    fun decide(current: ShipmentStatus, candidate: ShipmentStatus, source: TrackingSource): ShipmentStatus? {
        if (candidate == current) return null
        if (candidate == CANCELLED && source == TrackingSource.MANUAL) return null

        val allowed: Boolean = when (current) {
            CREATED -> candidate == LABEL_READY || candidate in HANDED || candidate == CANCELLED
            LABEL_READY -> candidate in HANDED || candidate == CANCELLED
            in MOVING -> candidate in MOVING || candidate == DELIVERED || candidate == RETURNED || candidate == LOST
            DELIVERED -> source == TrackingSource.MANUAL && (candidate == RETURNING || candidate == RETURNED)
            LOST -> source == TrackingSource.MANUAL && (candidate == IN_TRANSIT || candidate == DELIVERED)
            else -> false  // RETURNED, CANCELLED
        }

        return candidate.takeIf { allowed }
    }

    /** The stored event with the greatest `(occurredAt, id)`: the one that counts (10 section 7.2 step 5). */
    fun newest(events: List<TrackedEvent>): TrackedEvent? = events.maxWithOrNull(compareBy({ it.occurredAt }, { it.id }))

    /** Steps 5 and 6 of 10 section 7.2 over all stored events of a shipment. */
    fun resolve(current: ShipmentStatus, events: List<TrackedEvent>): TrackingResolution {
        val newest = newest(events) ?: return TrackingResolution(null, null, null, false)
        val next = decide(current, newest.status, newest.source)

        return TrackingResolution(
            next = next,
            shippedAt = events.filter { it.status in HANDED }.minOfOrNull { it.occurredAt },
            deliveredAt = if (next == DELIVERED) newest.occurredAt else null,
            clearNextPoll = next != null && next in TERMINAL
        )
    }
}
