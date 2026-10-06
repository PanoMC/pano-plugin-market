package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.dao.MarketShipmentEventDao
import com.panomc.plugins.market.db.dao.MarketShipmentItemDao
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShipmentEvent
import com.panomc.plugins.market.db.model.MarketShipmentItem
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

/** The `shipments[]` member of the owner `OrderView` (04 section 2); the limited view and the recipient view are cut from it by `OrderViews`. */
fun interface OrderShipmentViews {
    suspend fun forOrder(orderId: Long, sqlClient: SqlClient): JsonArray

    companion object {
        /** A service that shows no shipments (the member stays an empty list). */
        val NONE = OrderShipmentViews { _, _ -> JsonArray() }
    }
}

/**
 * The shipments of an order as the buyer reads them (04 section 2): `{id, status, carrierName, trackingNumber, trackingUrl, shippedAt, deliveredAt,
 * estimatedDeliveryAt, items: [{orderItemId, quantity}], events: [{status, description, location, occurredAt}]}`, oldest shipment first, events oldest first.
 * Nothing of the admin side leaves: no address, label, cost, provider id, error text or note.
 */
class BuyerShipmentViews(
    private val shipments: MarketShipmentDao,
    private val items: MarketShipmentItemDao,
    private val events: MarketShipmentEventDao
) : OrderShipmentViews {
    override suspend fun forOrder(orderId: Long, sqlClient: SqlClient): JsonArray = JsonArray(
        shipments.getByOrderId(orderId, sqlClient).sortedBy { it.id }.map { shipment ->
            view(shipment, items.getByShipmentId(shipment.id, sqlClient), events.getByShipmentId(shipment.id, sqlClient))
        }
    )

    companion object {
        fun view(shipment: MarketShipment, items: List<MarketShipmentItem>, events: List<MarketShipmentEvent>): JsonObject = JsonObject()
            .put("id", shipment.id)
            .put("status", shipment.status.name)
            .put("carrierName", shipment.carrierName)
            .put("trackingNumber", shipment.trackingNumber)
            .put("trackingUrl", shipment.trackingUrl)
            .put("shippedAt", shipment.shippedAt)
            .put("deliveredAt", shipment.deliveredAt)
            .put("estimatedDeliveryAt", shipment.estimatedDeliveryAt)
            .put("items", JsonArray(items.sortedBy { it.id }.map { JsonObject().put("orderItemId", it.orderItemId).put("quantity", it.quantity) }))
            .put(
                "events",
                JsonArray(
                    events.sortedWith(compareBy<MarketShipmentEvent> { it.occurredAt }.thenBy { it.id }).map {
                        JsonObject().put("status", it.status.name).put("description", it.description).put("location", it.location).put("occurredAt", it.occurredAt)
                    }
                )
            )
    }
}
