package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShipmentEvent
import com.panomc.plugins.market.db.model.MarketShipmentItem
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.routes.api.OrderRole
import com.panomc.plugins.market.routes.api.OrderViews
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** E2E-18: the `shipments[]` of the owner `OrderView` (04 section 2) is the buyer's tracking link; nothing of the admin side leaves with it. */
class BuyerShipmentViewsTest {
    private val shipment = MarketShipment(
        id = 7, orderId = 3, providerId = "manual", status = ShipmentStatus.IN_TRANSIT, carrierName = "E2E Post", trackingNumber = "E2E41TRACK",
        trackingUrl = "https://tracking.example.com/t/E2E41TRACK", shippedAt = 1_000L, deliveredAt = null, estimatedDeliveryAt = 9_000L,
        toAddress = """{"firstName":"Ada","line1":"Unter den Linden 1"}""", labelFile = "label.pdf", cost = 490L, note = "fragile", lastError = "carrier said no"
    )

    @Test
    fun `a shipment carries what the buyer needs to follow it`() {
        val view = BuyerShipmentViews.view(
            shipment,
            listOf(MarketShipmentItem(id = 2, shipmentId = 7, orderItemId = 12, quantity = 2), MarketShipmentItem(id = 1, shipmentId = 7, orderItemId = 11, quantity = 1)),
            listOf(
                MarketShipmentEvent(id = 5, shipmentId = 7, status = ShipmentStatus.IN_TRANSIT, description = "Left the depot", location = "Berlin", occurredAt = 2_000L),
                MarketShipmentEvent(id = 4, shipmentId = 7, status = ShipmentStatus.CREATED, description = null, location = null, occurredAt = 1_000L)
            )
        )

        assertEquals(7L, view.getLong("id"))
        assertEquals("IN_TRANSIT", view.getString("status"))
        assertEquals("E2E Post", view.getString("carrierName"))
        assertEquals("E2E41TRACK", view.getString("trackingNumber"))
        assertEquals("https://tracking.example.com/t/E2E41TRACK", view.getString("trackingUrl"))
        assertEquals(1_000L, view.getLong("shippedAt"))
        assertNull(view.getValue("deliveredAt"))
        assertEquals(9_000L, view.getLong("estimatedDeliveryAt"))

        // items in id order, events oldest first
        assertEquals(listOf(11L, 12L), view.getJsonArray("items").map { (it as JsonObject).getLong("orderItemId") })
        assertEquals(listOf("CREATED", "IN_TRANSIT"), view.getJsonArray("events").map { (it as JsonObject).getString("status") })
        assertEquals("Left the depot", view.getJsonArray("events").getJsonObject(1).getString("description"))
        assertEquals("Berlin", view.getJsonArray("events").getJsonObject(1).getString("location"))
    }

    @Test
    fun `nothing of the admin side is in the buyer view`() {
        val text = BuyerShipmentViews.view(shipment, emptyList(), emptyList()).encode()

        for (secret in listOf("Unter den Linden", "label.pdf", "fragile", "carrier said no", "providerId", "cost", "toAddress", "merchantReference")) {
            assertFalse(text.contains(secret), "the buyer view must not contain $secret: $text")
        }
    }

    @Test
    fun `the limited view of a guest without the token keeps only the status and the two dates`() {
        val owner = JsonObject()
            .put("publicId", "P").put("status", "COMPLETED").put("fulfillmentStatus", "NONE").put("shippingStatus", "SHIPPED").put("createdAt", 1L).put("paidAt", 2L)
            .put("currency", "EUR").put("testMode", false).put("isGift", false).put("totals", JsonObject().put("total", 24.9)).put("items", JsonArray())
            .put("shipments", JsonArray().add(BuyerShipmentViews.view(shipment, emptyList(), emptyList())))

        val limited = OrderViews.forRole(owner, OrderRole.LIMITED).getJsonArray("shipments").getJsonObject(0)

        assertEquals(setOf("status", "shippedAt", "deliveredAt"), limited.fieldNames())
        assertEquals("IN_TRANSIT", limited.getString("status"))
        assertTrue(!limited.encode().contains("E2E41TRACK"))
    }
}
