package com.panomc.plugins.market.e2e

import com.panomc.platform.route.ApiPaths
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import com.sun.net.httpserver.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.util.MarketPaths

/**
 * Shipping on a real instance (17 section 9.7, 10): zone, method and rate rows through the panel API, a physical order through checkout and the fake
 * gateway, manual shipments through `POST /orders/:id/shipments` and `PUT /shipments/:id`. SH-01 to SH-03.
 *
 * Every scenario builds the zone and the method it needs and removes both again (the quote offers every method of a matching zone, so a scenario
 * must be the only one that covers its country at that moment); the products are fresh and physical (`weightGrams = 500`).
 */
class ShippingE2E : E2eTestBase() {
    override val tag = "shp"

    private val sequence = AtomicInteger()
    private val sinks = ArrayList<WebhookSink>()

    @AfterAll
    fun closeSinks() {
        sinks.forEach { runCatching { it.close() } }
    }

    // --- fixtures ------------------------------------------------------------------------------------------------------

    private fun physical(price: String = "20.00", stock: Int = 10): Long {
        val n = sequence.incrementAndGet()

        return catalog.product(
            key = "SHP$n", slug = "e2e-shp-${System.currentTimeMillis().toString(36)}-$n", name = "Parcel $n", price = price, stock = stock,
            extra = mapOf("physical" to "true", "weightGrams" to "500")
        )
    }

    private fun address(country: String = "DE"): JsonObject = JsonObject()
        .put("firstName", "Ada").put("lastName", "Lovelace").put("phone", "+4915112345678").put("country", country)
        .put("city", "Berlin").put("line1", "Unter den Linden 1").put("postalCode", "10117")

    private class Shipping(val zoneId: Long, val methodId: Long)

    /**
     * A zone for [country] and one `manual` method with a weight rate (0 to 1999 g = 4.90, from 2000 g = 9.90); both are removed after [block].
     * The instance ships with the catch-all zone `Everywhere` (`countries = ["*"]`, no rates), which would shadow the zone of the scenario and would
     * leave no destination "outside every zone": every other active zone is switched off for the duration and switched on again afterwards.
     */
    private fun <T> withShipping(country: String = "DE", freeShippingThreshold: String? = null, block: (Shipping) -> T): T {
        val n = sequence.incrementAndGet()
        val others = admin.get("${MarketPaths.PANEL_ROOT}/shipping/zones").ok().obj().getJsonArray("items").map { it as JsonObject }.filter { it.getString("status") == "ACTIVE" }.map { it.getLong("id") }

        others.forEach { admin.put("${MarketPaths.PANEL_ROOT}/shipping/zones/$it", JsonObject().put("status", "INACTIVE")).ok() }

        try {
            val zoneId = admin.post(
                "${MarketPaths.PANEL_ROOT}/shipping/zones", JsonObject().put("name", "E2E zone $n").put("countries", JsonArray().add(country)).put("status", "ACTIVE")
            ).ok().obj().getLong("id")

            try {
                val body = JsonObject().put("name", "E2E method $n").put("providerId", "manual").put("rateSource", "RULES").put("status", "ACTIVE")
                    .put(
                        "rates",
                        JsonArray()
                            .add(JsonObject().put("zoneId", zoneId).put("basis", "WEIGHT").put("rangeFrom", 0).put("rangeTo", 1999).put("price", 4.9))
                            .add(JsonObject().put("zoneId", zoneId).put("basis", "WEIGHT").put("rangeFrom", 2000).put("price", 9.9))
                    )
                freeShippingThreshold?.let { body.put("freeShippingThreshold", it.toDouble()) }

                val methodId = admin.post("${MarketPaths.PANEL_ROOT}/shipping/methods", body).ok().obj().getLong("id")

                try {
                    return block(Shipping(zoneId, methodId))
                } finally {
                    admin.delete("${MarketPaths.PANEL_ROOT}/shipping/methods/$methodId")
                }
            } finally {
                admin.delete("${MarketPaths.PANEL_ROOT}/shipping/zones/$zoneId")
            }
        } finally {
            others.forEach { admin.put("${MarketPaths.PANEL_ROOT}/shipping/zones/$it", JsonObject().put("status", "ACTIVE")) }
        }
    }

    private fun quote(client: E2eClient, productId: Long, quantity: Int, address: JsonObject?, methodId: Long? = null): JsonObject {
        val body = JsonObject().put("items", JsonArray().add(line(productId, quantity)))

        address?.let { body.put("shippingAddress", it) }
        methodId?.let { body.put("shippingMethodId", it) }

        return client.post("${MarketPaths.SITE_ROOT}/checkout/quote", body).ok().obj().getJsonObject("quote")
    }

    private fun checkoutBody(productId: Long, quantity: Int, address: JsonObject?, methodId: Long?): JsonObject {
        val body = cart(line(productId, quantity))

        address?.let { body.put("shippingAddress", it) }
        methodId?.let { body.put("shippingMethodId", it) }

        return body
    }

    /** Pays a checkout of [quantity] units through the fake gateway; returns the order public id once the order is `COMPLETED`. */
    private fun paidOrder(client: E2eClient, productId: Long, quantity: Int, methodId: Long): String {
        val publicId = publicIdOf(checkout(client, checkoutBody(productId, quantity, address(), methodId)).ok())

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")

        return publicId
    }

    private fun itemId(orderId: Long): Long = db.long("SELECT `id` FROM `pano_market_order_item` WHERE `orderId` = ? ORDER BY `id` LIMIT 1", orderId)!!

    private fun shippedQuantity(itemId: Long): Long = db.long("SELECT `shippedQuantity` FROM `pano_market_order_item` WHERE `id` = ?", itemId)!!

    private fun shipmentBody(itemId: Long, quantity: Int, tracking: String): JsonObject = JsonObject().put("providerId", "manual")
        .put("items", JsonArray().add(JsonObject().put("orderItemId", itemId).put("quantity", quantity)))
        .put("parcels", JsonArray().add(JsonObject().put("weightGrams", 500 * quantity)))
        .put("manual", JsonObject().put("carrierName", "E2E Post").put("trackingNumber", tracking))

    private fun shippingStatus(publicId: String): String = orderRow(publicId).getString("shippingStatus")

    // --- a sink for the store webhook ----------------------------------------------------------------------------------

    /** A loopback HTTP server that records every body it receives (the store webhook of `shipment.shipped`). */
    private class WebhookSink : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = CopyOnWriteArrayList<JsonObject>()
        val url: String get() = "http://127.0.0.1:${server.address.port}/hook"

        init {
            server.createContext("/hook") { exchange ->
                val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

                runCatching { received += JsonObject(text) }
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    // --- SH-01 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `SH-01 physical order end to end - quote, address check, frozen shipping quote, manual shipment, delivered`() {
        val productId = physical()
        val buyer = buyer()
        val sink = WebhookSink().also { sinks += it }
        val endpointId = admin.post(
            "${ApiPaths.PANEL_ROOT}/webhooks",
            JsonObject().put("name", "E2E shipping sink").put("url", sink.url).put("events", JsonArray().add("market.shipment.shipped")).put("format", "JSON").put("signing", "NONE")
        ).ok().obj().getLong("id")

        try {
            withShipping { shipping ->
                // quote: the order needs shipping, one option of this zone (4.90 for 500 g), not free
                val noAddress = quote(buyer.client, productId, 1, null)

                assertEquals(true, noAddress.getBoolean("requiresShipping"))
                assertEquals(false, noAddress.getBoolean("canCheckout"), "no address, no checkout")

                val quoted = quote(buyer.client, productId, 1, address())
                val options = quoted.getJsonArray("shippingOptions").map { it as JsonObject }

                assertEquals(true, quoted.getBoolean("requiresShipping"))
                assertEquals(listOf(shipping.methodId), options.map { it.getLong("methodId") }, "exactly the method of this zone is offered")
                assertEquals(4.9, options.single().getDouble("price"), 0.0001)
                assertEquals(false, options.single().getBoolean("free"))

                // checkout without an address: 400 SHIPPING_ADDRESS_REQUIRED
                val missing = checkout(buyer.client, checkoutBody(productId, 1, null, shipping.methodId))

                assertEquals(400, missing.status)
                assertEquals("SHIPPING_ADDRESS_REQUIRED", missing.error)

                // with the address: shippingTotal and the frozen shippingQuote
                val publicId = publicIdOf(checkout(buyer.client, checkoutBody(productId, 1, address(), shipping.methodId)).ok())
                val row = orderRow(publicId)
                val orderId = row.getLong("id")

                assertEquals(490L, row.getLong("shippingTotal"))
                assertEquals(1, (row.getValue("requiresShipping") as? Number)?.toInt() ?: if (row.getValue("requiresShipping") == true) 1 else 0)
                assertEquals(shipping.methodId, row.getLong("shippingMethodId"))

                val frozen = JsonObject(row.getString("shippingQuote"))

                assertEquals(shipping.zoneId, frozen.getLong("zoneId"))
                assertEquals(shipping.methodId, frozen.getLong("methodId"))
                assertEquals(4.9, frozen.getDouble("price"), 0.0001)
                assertEquals("RULES", frozen.getString("source"))
                assertEquals("DE", JsonObject(row.getString("shippingAddress")).getString("country"))

                // the quote is frozen: changing the method's rate afterwards does not touch the order
                admin.put(
                    "${MarketPaths.PANEL_ROOT}/shipping/methods/${shipping.methodId}",
                    JsonObject().put(
                        "rates",
                        JsonArray().add(JsonObject().put("zoneId", shipping.zoneId).put("basis", "FLAT").put("price", 50.0))
                    )
                ).ok()

                payViaFake(publicId)
                awaitOrder(publicId, "COMPLETED")

                assertEquals("PENDING", shippingStatus(publicId))
                assertEquals(490L, orderRow(publicId).getLong("shippingTotal"), "the paid order keeps the frozen shipping price")

                // manual shipment: IN_TRANSIT, order SHIPPED, mail once, webhook once
                val itemId = itemId(orderId)
                val created = admin.post("${MarketPaths.PANEL_ROOT}/orders/$orderId/shipments", shipmentBody(itemId, 1, "E2E-TRACK-0001")).ok().obj().getJsonObject("shipment")
                val shipmentId = created.getLong("id")

                assertEquals("IN_TRANSIT", created.getString("status"))
                assertEquals("IN_TRANSIT", db.string("SELECT `status` FROM `pano_market_shipment` WHERE `id` = ?", shipmentId))
                assertEquals("SHIPPED", shippingStatus(publicId))
                assertEquals(1L, shippedQuantity(itemId))
                assertEquals(
                    1L, db.count("market_mail_outbox", "`kind` = 'SHIPMENT_SHIPPED' AND `orderId` = ?", orderId), "SHIPMENT_SHIPPED is queued exactly once"
                )

                Await.until(60_000, 500, "the shipment.shipped webhook reached the sink") { sink.received.isNotEmpty() }

                assertEquals(1, sink.received.count { it.getString("event") == "market.shipment.shipped" }, "the sink got shipment.shipped once")
                assertEquals(
                    1L, db.count("webhook_delivery", "`event` = 'market.shipment.shipped' AND `subjectRef` = ? AND `endpointId` = ?", "order:$orderId", endpointId),
                    "one delivery row"
                )

                // delivered
                admin.put("${MarketPaths.PANEL_ROOT}/shipments/$shipmentId", JsonObject().put("status", "DELIVERED")).ok()

                assertEquals("DELIVERED", db.string("SELECT `status` FROM `pano_market_shipment` WHERE `id` = ?", shipmentId))
                assertEquals("DELIVERED", shippingStatus(publicId))
                assertEquals(
                    1L, db.count("market_mail_outbox", "`kind` = 'SHIPMENT_SHIPPED' AND `orderId` = ?", orderId), "delivering does not queue a second shipped mail"
                )
            }
        } finally {
            admin.delete("${ApiPaths.PANEL_ROOT}/webhooks/$endpointId")
        }
    }

    // --- SH-02 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `SH-02 free shipping threshold and a destination outside every zone`() {
        val cheap = physical(price = "20.00")
        val expensive = physical(price = "120.00")
        val buyer = buyer()

        withShipping(country = "DE", freeShippingThreshold = "100.00") { shipping ->
            // below the threshold the price applies, above it the option is free
            val below = quote(buyer.client, cheap, 1, address()).getJsonArray("shippingOptions").getJsonObject(0)

            assertEquals(false, below.getBoolean("free"))
            assertEquals(4.9, below.getDouble("price"), 0.0001)

            val above = quote(buyer.client, expensive, 1, address()).getJsonArray("shippingOptions").getJsonObject(0)

            assertEquals(true, above.getBoolean("free"))
            assertEquals(0.0, above.getDouble("price"), 0.0001)

            val publicId = publicIdOf(checkout(buyer.client, checkoutBody(expensive, 1, address(), shipping.methodId)).ok())
            val row = orderRow(publicId)

            assertEquals(0L, row.getLong("shippingTotal"))
            assertEquals(true, JsonObject(row.getString("shippingQuote")).getBoolean("free"))

            // a destination that no zone covers: the quote has no option, checkout refuses
            val outside = quote(buyer.client, cheap, 1, address("JP"))

            assertEquals(0, outside.getJsonArray("shippingOptions").size())
            assertEquals(false, outside.getBoolean("canCheckout"))

            val refused = checkout(buyer.client, checkoutBody(cheap, 1, address("JP"), shipping.methodId))

            assertEquals(400, refused.status)
            assertEquals("SHIPPING_UNAVAILABLE", refused.error)
            assertEquals("NO_ZONE", refused.details.getString("reason"))
        }
    }

    // --- SH-03 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `SH-03 partial shipments - PARTIAL then SHIPPED, exact shipped quantity, shipping a third time is refused`() {
        val productId = physical()
        val buyer = buyer()

        withShipping { shipping ->
            val publicId = paidOrder(buyer.client, productId, 3, shipping.methodId)
            val orderId = orderRow(publicId).getLong("id")
            val itemId = itemId(orderId)

            assertEquals(3, db.long("SELECT `quantity` FROM `pano_market_order_item` WHERE `id` = ?", itemId)?.toInt())
            assertEquals("PENDING", shippingStatus(publicId))

            admin.post("${MarketPaths.PANEL_ROOT}/orders/$orderId/shipments", shipmentBody(itemId, 1, "E2E-PART-0001")).ok()

            assertEquals("PARTIAL", shippingStatus(publicId))
            assertEquals(1L, shippedQuantity(itemId))

            admin.post("${MarketPaths.PANEL_ROOT}/orders/$orderId/shipments", shipmentBody(itemId, 2, "E2E-PART-0002")).ok()

            assertEquals("SHIPPED", shippingStatus(publicId))
            assertEquals(3L, shippedQuantity(itemId))
            assertEquals(2L, db.count("market_shipment", "`orderId` = ?", orderId))
            assertEquals(
                3L, db.long("SELECT COALESCE(SUM(si.`quantity`), 0) FROM `pano_market_shipment_item` si JOIN `pano_market_shipment` s ON s.`id` = si.`shipmentId` WHERE s.`orderId` = ?", orderId)
            )

            // everything is shipped: one more unit is refused and nothing changes
            val again = admin.post("${MarketPaths.PANEL_ROOT}/orders/$orderId/shipments", shipmentBody(itemId, 3, "E2E-PART-0003"))

            assertEquals(400, again.status, "shipping 3 again: ${again.error} ${again.json}")
            assertEquals(3L, shippedQuantity(itemId))
            assertEquals(2L, db.count("market_shipment", "`orderId` = ?", orderId))
            assertEquals("SHIPPED", shippingStatus(publicId))
            assertNotNull(again.error)
            assertFalse(again.status in 200..299)
        }
    }
}
