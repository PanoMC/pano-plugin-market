package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShipmentStatus.*
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.db.model.ShippingStatus.NOT_REQUIRED
import com.panomc.plugins.market.db.model.ShippingStatus.PARTIAL
import com.panomc.plugins.market.db.model.ShippingStatus.PENDING
import com.panomc.plugins.market.db.model.ShippingStatus.SHIPPED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `ShippingStatusDeriver` (10 section 7.3, tests 50 to 52 and 58 of section 16, pure part). */
class ShippingStatusDeriverTest {
    private val paid = ShippingOrderFacts(requiresShipping = true, paid = true)

    private fun item(id: Long, qty: Int, refunded: Int = 0, shippable: Boolean = true) = ShippingItemFacts(id, qty, refunded, shippable)

    private fun ship(id: Long, status: ShipmentStatus, vararg items: Pair<Long, Int>, released: Boolean = false) =
        ShipmentFacts(id, status, released || status == CANCELLED, items.toMap())

    private fun derive(items: List<ShippingItemFacts>, vararg shipments: ShipmentFacts, order: ShippingOrderFacts = paid) =
        ShippingStatusDeriver.derive(order, items, shipments.toList())

    private val three = listOf(item(1, 3))

    @Test
    fun `no shipping needed or not paid yet is not required`() {
        assertEquals(NOT_REQUIRED, derive(three, order = ShippingOrderFacts(false, true)))
        assertEquals(NOT_REQUIRED, derive(three, order = ShippingOrderFacts(true, false)))
        assertEquals(NOT_REQUIRED, derive(three, ship(1, IN_TRANSIT, 1L to 3), order = ShippingOrderFacts(true, false)))
    }

    @Test
    fun `paid and nothing handed over is pending also with created and label ready shipments`() {
        assertEquals(PENDING, derive(three))
        assertEquals(PENDING, derive(three, ship(1, CREATED, 1L to 2)))
        assertEquals(PENDING, derive(three, ship(1, LABEL_READY, 1L to 3)))
    }

    @Test
    fun `partial then shipped then delivered`() {
        assertEquals(PARTIAL, derive(three, ship(1, IN_TRANSIT, 1L to 2)))
        assertEquals(SHIPPED, derive(three, ship(1, IN_TRANSIT, 1L to 2), ship(2, IN_TRANSIT, 1L to 1)))
        assertEquals(SHIPPED, derive(three, ship(1, DELIVERED, 1L to 2), ship(2, OUT_FOR_DELIVERY, 1L to 1)))
        assertEquals(ShippingStatus.DELIVERED, derive(three, ship(1, DELIVERED, 1L to 2), ship(2, DELIVERED, 1L to 1)))
        assertEquals(SHIPPED, derive(three, ship(1, EXCEPTION, 1L to 3)))
        assertEquals(SHIPPED, derive(three, ship(1, RETURNING, 1L to 3)))
        assertEquals(SHIPPED, derive(three, ship(1, LOST, 1L to 3)))
    }

    @Test
    fun `all handed shipments returned is returned and a mix with delivered is delivered`() {
        assertEquals(ShippingStatus.RETURNED, derive(three, ship(1, RETURNED, 1L to 3)))
        assertEquals(ShippingStatus.RETURNED, derive(three, ship(1, RETURNED, 1L to 2), ship(2, RETURNED, 1L to 1)))
        assertEquals(ShippingStatus.DELIVERED, derive(three, ship(1, DELIVERED, 1L to 2), ship(2, RETURNED, 1L to 1)))
    }

    @Test
    fun `released and cancelled shipments do not count`() {
        assertEquals(PENDING, derive(three, ship(1, RETURNED, 1L to 3, released = true)))
        assertEquals(PENDING, derive(three, ship(1, CANCELLED, 1L to 3)))
        assertEquals(PARTIAL, derive(three, ship(1, IN_TRANSIT, 1L to 1), ship(2, RETURNED, 1L to 2, released = true)))
        assertEquals(SHIPPED, derive(three, ship(1, IN_TRANSIT, 1L to 3), ship(2, CANCELLED, 1L to 3)))
    }

    @Test
    fun `refunded units are not shippable and refunding every unshipped unit ends the need`() {
        assertEquals(PENDING, derive(listOf(item(1, 3, refunded = 1))))
        assertEquals(SHIPPED, derive(listOf(item(1, 3, refunded = 1)), ship(1, IN_TRANSIT, 1L to 2)))
        assertEquals(NOT_REQUIRED, derive(listOf(item(1, 3, refunded = 3))))
        // 3 units, 1 shipped, the 2 unshipped refunded: need = max(0, 1) = 1 = handed
        assertEquals(SHIPPED, derive(listOf(item(1, 3, refunded = 2)), ship(1, IN_TRANSIT, 1L to 1)))
        // a unit refunded after it shipped still counts as handed (need = max(quantity - refunded, handed))
        assertEquals(SHIPPED, derive(listOf(item(1, 3, refunded = 3)), ship(1, IN_TRANSIT, 1L to 3)))
    }

    @Test
    fun `mixed orders only look at shippable items`() {
        val items = listOf(item(1, 2), item(2, 5, shippable = false), item(3, 1, shippable = false))

        assertEquals(PENDING, derive(items))
        assertEquals(SHIPPED, derive(items, ship(1, IN_TRANSIT, 1L to 2)))
        assertEquals(NOT_REQUIRED, derive(listOf(item(2, 5, shippable = false))))
    }

    @Test
    fun `several items must all be covered`() {
        val items = listOf(item(1, 1), item(2, 1))

        assertEquals(PARTIAL, derive(items, ship(1, IN_TRANSIT, 1L to 1)))
        assertEquals(SHIPPED, derive(items, ship(1, IN_TRANSIT, 1L to 1, 2L to 1)))
        assertEquals(PARTIAL, derive(items, ship(1, IN_TRANSIT, 1L to 1), ship(2, CREATED, 2L to 1)))
    }

    @Test
    fun `allocated counts every live shipment including created and label ready`() {
        val shipments = listOf(
            ship(1, CREATED, 1L to 1),
            ship(2, LABEL_READY, 1L to 1, 2L to 4),
            ship(3, IN_TRANSIT, 2L to 1),
            ship(4, CANCELLED, 1L to 9),
            ship(5, RETURNED, 1L to 7, released = true)
        )

        assertEquals(mapOf(1L to 2L, 2L to 5L), ShippingStatusDeriver.allocated(shipments))
        assertEquals(emptyMap<Long, Long>(), ShippingStatusDeriver.allocated(emptyList()))
    }

    @Test
    fun `every shipment status has a defined outcome`() {
        for (status in ShipmentStatus.values()) {
            val result: ShippingStatus = derive(three, ship(1, status, 1L to 3))

            val expected = when (status) {
                CREATED, LABEL_READY, CANCELLED -> PENDING
                DELIVERED -> ShippingStatus.DELIVERED
                RETURNED -> ShippingStatus.RETURNED
                else -> SHIPPED
            }
            assertEquals(expected, result, status.name)
        }
    }
}
