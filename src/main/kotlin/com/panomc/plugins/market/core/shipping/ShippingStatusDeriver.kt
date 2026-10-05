package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShippingStatus

/** What the deriver needs of the order. [paid] = `paidAt != null`. */
class ShippingOrderFacts(val requiresShipping: Boolean, val paid: Boolean)

/** An order item; [shippable] = physical and not a `BUNDLE` (only those take part, 10 section 7.3). */
class ShippingItemFacts(val orderItemId: Long, val quantity: Int, val refundedQuantity: Int, val shippable: Boolean)

/** A shipment of the order with the units it carries; a `CANCELLED` shipment is always [released]. */
class ShipmentFacts(val id: Long, val status: ShipmentStatus, val released: Boolean, val items: Map<Long, Int>)

/** `ShippingStatusDeriver.derive` (10 section 7.3). Pure. */
object ShippingStatusDeriver {
    fun derive(order: ShippingOrderFacts, items: List<ShippingItemFacts>, shipments: List<ShipmentFacts>): ShippingStatus {
        if (!order.requiresShipping) return ShippingStatus.NOT_REQUIRED

        val live = shipments.filter { !it.released && it.status != ShipmentStatus.CANCELLED }
        val handedStatuses = ShipmentStateMachine.HANDED
        var need = 0L
        var handed = 0L

        for (item in items.filter { it.shippable }) {
            val h = live.filter { it.status in handedStatuses }.sumOf { (it.items[item.orderItemId] ?: 0).toLong() }
            need += maxOf((item.quantity - item.refundedQuantity).toLong(), h)
            handed += h
        }

        if (!order.paid) return ShippingStatus.NOT_REQUIRED
        if (need == 0L) return ShippingStatus.NOT_REQUIRED
        if (handed == 0L) return ShippingStatus.PENDING
        if (handed < need) return ShippingStatus.PARTIAL

        val inHands = live.filter { it.status in handedStatuses }

        return when {
            inHands.all { it.status == ShipmentStatus.RETURNED } -> ShippingStatus.RETURNED
            inHands.all { it.status == ShipmentStatus.DELIVERED || it.status == ShipmentStatus.RETURNED } -> ShippingStatus.DELIVERED
            else -> ShippingStatus.SHIPPED
        }
    }

    /**
     * `market_order_item.shippedQuantity`: units allocated to a live (not released, not cancelled) shipment, including
     * `CREATED` and `LABEL_READY` ones, per order item id.
     */
    fun allocated(shipments: List<ShipmentFacts>): Map<Long, Long> {
        val result = HashMap<Long, Long>()

        for (s in shipments) {
            if (s.released || s.status == ShipmentStatus.CANCELLED) continue

            for ((itemId, q) in s.items) result[itemId] = (result[itemId] ?: 0L) + q
        }

        return result
    }
}
