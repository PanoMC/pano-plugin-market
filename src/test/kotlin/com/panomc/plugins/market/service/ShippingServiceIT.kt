package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.shipping.TrackingSource
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketShippingCarrier
import com.panomc.plugins.market.db.model.MarketShippingMethod
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.shipping.CancelShipmentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.LabelDocument
import com.panomc.plugins.market.spi.shipping.LabelFormat
import com.panomc.plugins.market.spi.shipping.QuoteRequest
import com.panomc.plugins.market.spi.shipping.QuoteResult
import com.panomc.plugins.market.spi.shipping.RateOption
import com.panomc.plugins.market.spi.shipping.SenderKeys
import com.panomc.plugins.market.spi.shipping.ShipmentErrorCode
import com.panomc.plugins.market.spi.shipping.ShipmentPiece
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.TrackRequest
import com.panomc.plugins.market.spi.shipping.TrackingEvent
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.SeqIds
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fulfilment on a real MariaDB (MK-133; 10 sections 7.2, 9 and 16 tests 41 to 60): shipments with partial shipping, manual entries and
 * carrier creation with an idempotent merchant reference, retry, cancel (with `force`), manual status and `releaseItems`, the derived
 * `shippingStatus`, the address edit and the mail / webhook side effects. The carrier is the scripted [ScriptedCarrier] (the same object
 * the service sees through `StaticProviderLookup`); the invariants I1 to I22 run after every test, and [allocated] checks that
 * `shippedQuantity` is exactly what the live shipments carry.
 */
class ShippingServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var carrier: ScriptedCarrier
    private lateinit var lookup: StaticProviderLookup
    private lateinit var service: ShippingService
    private lateinit var webhooks: WebhookHarness
    private lateinit var labels: Path
    private val vertx: Vertx = Vertx.vertx()

    override val poolSize: Int = 24

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool, ids = SeqIds())
        carrier = ScriptedCarrier()
        lookup = StaticProviderLookup(shipping = listOf(ManualShippingProvider(), carrier))
        webhooks = WebhookHarness(w, vertx)
        labels = Files.createTempDirectory("market-labels")
        service = newService()
        runBlocking { carrierRow() }
    }

    private fun newService(createTimeoutMs: Long = 30_000, cancelTimeoutMs: Long = 30_000, ratesTimeoutMs: Long = 15_000, labelsDir: Path = labels) = ShippingService(
        clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers,
        currencyRates = w.currencyRates, addresses = w.addresses, lookup = lookup, cipher = SecretCipher(ByteArray(32) { (it + 5).toByte() }),
        contexts = ShippingContexts { provider, settings, testMode -> TestContexts.shipping(provider.id, settings, vertx, testMode) },
        fulfilment = FulfilmentDeps(
            db = w.db, locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts), orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            shipments = w.shipments, shipmentItems = w.shipmentItems, shipmentEvents = w.shipmentEvents,
            mail = MailOutboxService({ w.config }, w.clock, w.mailOutbox, w.orderEvents), webhooks = webhooks.service, ids = w.ids, config = { w.config },
            labelsDir = labelsDir, siteUrl = { "https://shop.example" }, createTimeoutMs = createTimeoutMs, cancelTimeoutMs = cancelTimeoutMs, ratesTimeoutMs = ratesTimeoutMs
        )
    )

    // ------------------------------------------------------------------------------------------------ fixtures

    private val address = JsonObject()
        .put("firstName", "Hans").put("lastName", "Meier").put("phone", "+4915112345678").put("country", "DE").put("city", "Berlin")
        .put("line1", "Strasse 1").put("postalCode", "10115").put("email", "steve@example.com").encode()

    private class Line(val name: String, val qty: Int, val unit: Long = 2000, val weight: Int = 250, val refunded: Int = 0, val hs: String? = null, val origin: String? = null)

    private class Ref(val id: Long, val items: List<Long>)

    private suspend fun order(
        vararg lines: Line,
        status: OrderStatus = OrderStatus.COMPLETED,
        shippingAddress: String? = address,
        dispute: DisputeStatus = DisputeStatus.NONE,
        requiresShipping: Boolean = true,
        email: String? = "steve@example.com",
        methodId: Long? = null,
        testMode: Boolean = false
    ): Ref {
        val now = w.clock.now()
        val total = lines.sumOf { it.unit * it.qty }
        val id = w.orders.add(
            MarketOrder(
                userId = 5, playerUsername = "Steve", recipientUsername = "Steve", recipientUserId = 5, email = email, locale = "en-US",
                publicId = w.ids.publicId(), status = status, currency = "EUR", subtotal = total, totalPrice = total, gatewayAmount = total, paidAmount = total, paidAt = now,
                createdAt = now, updatedAt = now, buyerKey = "u:5", recipientKey = "u:5", baseCurrency = "EUR", paymentMethodId = "manual",
                reservationState = ReservationState.COMMITTED, requiresShipping = requiresShipping,
                shippingStatus = if (requiresShipping) ShippingStatus.PENDING else ShippingStatus.NOT_REQUIRED, shippingAddress = shippingAddress,
                shippingMethodId = methodId, shippingMethodName = methodId?.let { "Standard" }, disputeStatus = dispute, testMode = testMode
            ),
            pool
        )
        val items = lines.map { l ->
            w.orderItems.add(
                MarketOrderItem(
                    orderId = id, productName = l.name, quantity = l.qty, unitPrice = l.unit, lineTotal = l.unit * l.qty, kind = OrderItemKind.PRODUCT, physical = true,
                    refundedQuantity = l.refunded, sku = "SKU-${l.name}",
                    snapshot = JsonObject().put("kind", "PHYSICAL").put("physical", true).put("weightGrams", l.weight).put("hsCode", l.hs).put("originCountry", l.origin).encode(),
                    createdAt = now, updatedAt = now
                ),
                pool
            )
        }

        return Ref(id, items)
    }

    private suspend fun carrierRow(enabled: Boolean = true): MarketShippingCarrier {
        val now = w.clock.now()
        val settings = JsonObject().put("senderCountry", "TR").put("senderCity", "Istanbul").put("senderLine1", "Depo 1").put("senderPostalCode", "34000").encode()

        w.shippingCarriers.add(MarketShippingCarrier(providerId = carrier.id, enabled = enabled, settings = settings, webhookToken = "b".repeat(40), createdAt = now, updatedAt = now), pool)

        return w.shippingCarriers.getByProviderId(carrier.id, pool)!!
    }

    private suspend fun method(template: String? = null, carrierName: String? = null): Long {
        val now = w.clock.now()

        return w.shippingMethods.add(MarketShippingMethod(name = "Standard", trackingUrlTemplate = template, carrierName = carrierName, createdAt = now, updatedAt = now), pool)
    }

    private fun body(
        vararg items: Pair<Long, Int>, provider: String = "manual", manual: JsonObject? = null, parcels: JsonArray = JsonArray().add(JsonObject().put("weightGrams", 500)),
        extra: Map<String, Any?> = emptyMap()
    ): JsonObject {
        val o = JsonObject().put("providerId", provider).put("parcels", parcels)
            .put("items", JsonArray(items.map { (id, q) -> JsonObject().put("orderItemId", id).put("quantity", q) }))

        if (manual != null) o.put("manual", manual)

        extra.forEach { (k, v) -> o.put(k, v) }

        return o
    }

    private fun manual(carrierName: String? = "DHL", number: String? = "TRK123456", url: String? = null) =
        JsonObject().also { m ->
            carrierName?.let { m.put("carrierName", it) }
            number?.let { m.put("trackingNumber", it) }
            url?.let { m.put("trackingUrl", it) }
        }

    private suspend fun ship(order: Ref, vararg items: Pair<Long, Int>, manual: JsonObject? = manual()): JsonObject =
        service.createShipment(order.id, body(*items, manual = manual), 7, pool)

    private suspend fun carrierShip(order: Ref, vararg items: Pair<Long, Int>): JsonObject =
        service.createShipment(order.id, body(*items, provider = carrier.id), 7, pool)

    private suspend fun orderNow(id: Long): MarketOrder = w.orders.getById(id, pool)!!

    private suspend fun itemNow(id: Long): MarketOrderItem = w.orderItems.getById(id, pool)!!

    private suspend fun shipmentNow(id: Long) = w.shipments.getById(id, pool)!!

    private suspend fun fails(block: suspend () -> Any?): Error {
        try {
            block()
        } catch (e: Error) {
            return e
        }

        error("expected an error")
    }

    private suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject {
        val e = fails(block)

        assertEquals(code, e.getErrorCode(), "error code, body ${e.encode()}")
        assertEquals(status, e.getStatusCode(), "status of $code")

        return JsonObject(e.encode())
    }

    private suspend fun mails(kind: String? = null): List<JsonObject> =
        sql("SELECT `kind`, `refType`, `refId`, `status`, `recipient`, `params` FROM `pano_market_mail_outbox`" + (if (kind != null) " WHERE `kind` = '$kind'" else "") + " ORDER BY `id`").map {
            JsonObject().put("kind", it.getString("kind")).put("refType", it.getString("refType")).put("refId", it.getLong("refId")).put("status", it.getString("status"))
                .put("recipient", it.getString("recipient")).put("params", JsonObject(it.getString("params")))
        }

    private suspend fun events(shipmentId: Long) = sql("SELECT `status`, `source` FROM `pano_market_shipment_event` WHERE `shipmentId` = ? ORDER BY `id`", shipmentId).map { it.getString("status") to it.getString("source") }

    private suspend fun orderEventTypes(orderId: Long) = sql("SELECT `type` FROM `pano_market_order_event` WHERE `orderId` = ? ORDER BY `id`", orderId).map { it.getString("type") }

    /** `shippedQuantity` of every item equals the units of the live (not released, not cancelled) shipments. */
    private suspend fun allocated(order: Ref) {
        for (itemId in order.items) {
            val live = sql(
                "SELECT COALESCE(SUM(si.`quantity`), 0) AS q FROM `pano_market_shipment_item` si JOIN `pano_market_shipment` s ON s.`id` = si.`shipmentId` " +
                    "WHERE si.`orderItemId` = ? AND s.`itemsReleased` = 0 AND s.`status` <> 'CANCELLED'",
                itemId
            ).single().getLong("q")

            assertEquals(live, itemNow(itemId).shippedQuantity.toLong(), "shippedQuantity of item $itemId equals the units of live shipments")
        }
    }

    private fun id(shipment: JsonObject) = shipment.getLong("id")

    private fun cfg(mail: Boolean = true, test: Boolean = false) = com.panomc.plugins.market.config.MarketConfig(
        currency = com.panomc.plugins.market.util.CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC",
        sendEmailAfterPurchase = mail, testMode = test
    )

    // ================================================================================================ test 50: partial shipping

    @Test
    fun `3 units shipped 1 then 2 gives PARTIAL then SHIPPED with the exact shippedQuantity, both delivered gives DELIVERED`(): Unit = runBlocking {
        val o = order(Line("Shirt", 3))
        val item = o.items.single()

        assertEquals(3, shippableOf(o, item))

        val first = ship(o, item to 1)

        assertEquals("IN_TRANSIT", first.getString("status"))
        assertEquals(1, itemNow(item).shippedQuantity)
        assertEquals(ShippingStatus.PARTIAL, orderNow(o.id).shippingStatus)
        assertEquals(2, shippableOf(o, item))

        val second = ship(o, item to 2, manual = manual(number = "TRK777"))

        assertEquals(3, itemNow(item).shippedQuantity)
        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)
        assertEquals(0, shippableOf(o, item))
        allocated(o)

        service.editShipment(id(first), JsonObject().put("status", "DELIVERED"), 7, pool)

        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus, "one parcel is still on its way")

        service.editShipment(id(second), JsonObject().put("status", "DELIVERED"), 7, pool)

        assertEquals(ShippingStatus.DELIVERED, orderNow(o.id).shippingStatus)
        assertNotNull(shipmentNow(id(second)).deliveredAt)
        val types = orderEventTypes(o.id)

        assertEquals(2, types.count { it == "SHIPMENT_CREATED" })
        assertEquals(4, types.count { it == "SHIPMENT_UPDATED" }, "CREATED to IN_TRANSIT twice, IN_TRANSIT to DELIVERED twice")
        allocated(o)
    }

    private suspend fun shippableOf(o: Ref, itemId: Long): Int =
        service.orderShipping(o.id, true, pool).getJsonArray("lines").map { it as JsonObject }.single { it.getLong("orderItemId") == itemId }.getInteger("shippable")

    // ================================================================================================ test 51: over-shipping (SH-03 twin)

    @Test
    fun `over-shipping is refused as EXCEEDS_SHIPPABLE and changes nothing, refunded units are not shippable`(): Unit = runBlocking {
        val o = order(Line("Shirt", 3, refunded = 1))
        val item = o.items.single()

        val e = expect("INVALID_SHIPMENT", 400) { ship(o, item to 3) }

        assertEquals("EXCEEDS_SHIPPABLE", e.getJsonObject("fieldErrors").getString("items"))
        assertEquals(0, itemNow(item).shippedQuantity)
        assertEquals(0, count("market_shipment"))
        assertEquals(0, count("market_shipment_item"))
        assertEquals(2, shippableOf(o, item))

        ship(o, item to 1)

        expect("INVALID_SHIPMENT", 400) { ship(o, item to 2) }

        assertEquals(1, itemNow(item).shippedQuantity)
        assertEquals(1, count("market_shipment"))

        ship(o, item to 1, manual = manual(number = "TRK2"))

        expect("INVALID_SHIPMENT", 400) { ship(o, item to 1, manual = manual(number = "TRK3")) }

        assertEquals(2, itemNow(item).shippedQuantity)
        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus, "the refunded unit is not owed")
        allocated(o)
    }

    @Test
    fun `two concurrent creates for the last unit - exactly one succeeds`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val o = order(Line("Shirt-$round", 1))
            val item = o.items.single()

            val results = Race.run(4) { i -> ship(o, item to 1, manual = manual(number = "RACE$round$i")) }

            assertEquals(1, results.count { it.isSuccess }, "round $round")
            results.filter { it.isFailure }.forEach { r ->
                val e = r.exceptionOrNull() as Error

                assertEquals("INVALID_SHIPMENT", e.getErrorCode())
                assertEquals("EXCEEDS_SHIPPABLE", JsonObject(e.encode()).getJsonObject("fieldErrors").getString("items"))
            }
            assertEquals(1, itemNow(item).shippedQuantity)
            assertEquals(1, count("market_shipment", "`orderId` = ?", o.id))
            assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)
            allocated(o)
        }
    }

    @Test
    fun `refunding every unshipped physical unit makes the order NOT_REQUIRED once a shipment is cancelled`(): Unit = runBlocking {
        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val s = ship(o, item to 1)

        assertEquals(ShippingStatus.PARTIAL, orderNow(o.id).shippingStatus)

        // both units are refunded (the refund slice does this; the deriver is what is under test here)
        sql("UPDATE `pano_market_order_item` SET `refundedQuantity` = 2 WHERE `id` = ?", item)

        assertEquals(0, shippableOf(o, item))

        service.cancelShipment(id(s), false, 7, pool)

        assertEquals(ShippingStatus.NOT_REQUIRED, orderNow(o.id).shippingStatus, "nothing is owed any more")
        assertEquals(0, itemNow(item).shippedQuantity)

        expect("INVALID_SHIPMENT", 400) { ship(o, item to 1) }
        allocated(o)
    }

    // ================================================================================================ manual entry

    @Test
    fun `a manual entry is IN_TRANSIT at once with its tracking data, one MANUAL event, the mail and the webhook`(): Unit = runBlocking {
        val template = method(template = "https://track.example.com/t/{tracking}", carrierName = "Yurtici")
        val o = order(Line("Shirt", 2), methodId = template)
        val item = o.items.single()
        val endpoint = webhooks.endpoint("https://hooks.example.com/market")

        val s = ship(o, item to 2, manual = manual(carrierName = null, number = "AB 12/3"))
        val row = shipmentNow(id(s))

        assertEquals(16, row.merchantReference.length)
        assertTrue(Regex("^[0-9A-HJKMNP-TV-Z]{16}$").matches(row.merchantReference), row.merchantReference)
        assertEquals(row.merchantReference, row.carrierReference)
        assertEquals("manual", row.providerId)
        assertEquals("MANUAL", row.entryMode.name)
        assertEquals(ShipmentStatus.IN_TRANSIT, row.status)
        assertEquals("Yurtici", row.carrierName, "falls back to the method's carrier name")
        assertEquals("AB 12/3", row.trackingNumber)
        assertEquals("https://track.example.com/t/AB%2012%2F3", row.trackingUrl)
        assertEquals(500, row.weightGrams)
        assertNotNull(row.shippedAt)
        assertEquals(w.clock.now(), row.shippedAt)
        assertEquals(7L, row.createdBy)
        assertEquals(listOf("IN_TRANSIT" to "MANUAL"), events(row.id))
        assertEquals("DE", JsonObject(row.toAddress).getString("country"))
        assertEquals(1, JsonObject(row.packages!!.let { "{\"p\":$it}" }).getJsonArray("p").size())

        val mail = mails().single()

        assertEquals("SHIPMENT_SHIPPED", mail.getString("kind"))
        assertEquals("SHIPMENT", mail.getString("refType"))
        assertEquals(row.id, mail.getLong("refId"))
        assertEquals("steve@example.com", mail.getString("recipient"))
        assertEquals(o.id.toString(), mail.getJsonObject("params").getString("orderNumber"))
        assertEquals("AB 12/3", mail.getJsonObject("params").getString("trackingNumber"))
        assertEquals(false, mail.getJsonObject("params").getBoolean("isPartial"))
        assertEquals("Shirt", mail.getJsonObject("params").getJsonArray("items").getJsonObject(0).getString("name"))
        assertNotNull(row.trackingMailSentAt)

        val hooks = webhooks.rows()

        assertEquals(1, hooks.size)
        assertEquals("shipment.shipped", hooks.single().event)
        assertEquals(endpoint.id, hooks.single().endpointId)
        assertFalse(hooks.single().body.contains("Strasse"), "no address in the webhook")
        assertFalse(hooks.single().body.contains("steve@example.com"), "no e-mail in the webhook")

        assertEquals(listOf("SHIPMENT_CREATED", "SHIPMENT_UPDATED", "MAIL_QUEUED").sorted(), orderEventTypes(o.id).sorted().filter { it != "CREATED" })
    }

    @Test
    fun `manual entry validation gives INVALID_SHIPMENT field errors and writes nothing`(): Unit = runBlocking {
        val o = order(Line("Shirt", 3))
        val item = o.items.single()

        fun fieldErrors(e: JsonObject) = e.getJsonObject("fieldErrors")

        assertEquals("REQUIRED", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, JsonObject().put("providerId", "manual").put("parcels", JsonArray().add(JsonObject().put("weightGrams", 5))), 7, pool) }).getString("items"))
        assertEquals("DUPLICATE", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, item to 1), 7, pool) }).getString("items"))
        assertEquals("INVALID_QUANTITY", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 0), 7, pool) }).getString("items"))
        assertEquals("REQUIRED", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, parcels = JsonArray()), 7, pool) }).getString("parcels"))
        assertEquals("OUT_OF_RANGE", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, parcels = JsonArray().add(JsonObject().put("weightGrams", 0))), 7, pool) }).getString("parcels"))
        assertEquals("OUT_OF_RANGE", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, parcels = JsonArray().add(JsonObject().put("weightGrams", 5).put("lengthMm", 10))), 7, pool) }).getString("parcels"))
        assertEquals("TOO_MANY", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, parcels = JsonArray(List(21) { JsonObject().put("weightGrams", 5) })), 7, pool) }).getString("parcels"))
        assertEquals("INVALID", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, manual = manual(number = "bad;number")), 7, pool) }).getString("manual.trackingNumber"))
        assertEquals("INVALID", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, manual = manual(url = "javascript:alert(1)")), 7, pool) }).getString("manual.trackingUrl"))
        assertEquals("UNKNOWN_ITEM", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(9999L to 1), 7, pool) }).getString("items"))
        assertEquals("TOO_LONG", fieldErrors(expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, extra = mapOf("note" to "x".repeat(513))), 7, pool) }).getString("note"))

        assertEquals(0, count("market_shipment"))
        assertEquals(0, itemNow(item).shippedQuantity)
        expect("NOT_FOUND", 404) { service.createShipment(987654, body(item to 1), 7, pool) }.let { }
    }

    @Test
    fun `a manual shipment of up to 20 parcels is accepted, the provider rules decide for the others`(): Unit = runBlocking {
        val o = order(Line("Pallet", 30))
        val item = o.items.single()

        val s = service.createShipment(o.id, body(item to 30, parcels = JsonArray(List(20) { JsonObject().put("weightGrams", 100) })), 7, pool)

        assertEquals(2000, s.getInteger("weightGrams"))
        assertEquals(20, s.getJsonArray("packages").size())

        val o2 = order(Line("Mug", 1))

        carrier.caps = ScriptedCarrier.caps(maxParcels = 2)
        expect("INVALID_SHIPMENT", 400) { service.createShipment(o2.id, body(o2.items.single() to 1, provider = carrier.id, parcels = JsonArray(List(3) { JsonObject().put("weightGrams", 100) })), 7, pool) }
            .let { assertEquals("TOO_MANY", it.getJsonObject("fieldErrors").getString("parcels")) }

        carrier.caps = ScriptedCarrier.caps(requiresDimensions = true)
        expect("INVALID_SHIPMENT", 400) { carrierShip(o2, o2.items.single() to 1) }
            .let { assertEquals("DIMENSIONS_REQUIRED", it.getJsonObject("fieldErrors").getString("parcels")) }
    }

    @Test
    fun `provider rules - unknown or disabled provider, manual on a carrier, carrier without create`(): Unit = runBlocking {
        val o = order(Line("Shirt", 3))
        val item = o.items.single()

        expect("PROVIDER_UNAVAILABLE", 409) { service.createShipment(o.id, body(item to 1, provider = "nosuch"), 7, pool) }
            .let { assertEquals("MISSING", it.getString("state")) }

        sql("UPDATE `pano_market_shipping_carrier` SET `enabled` = 0 WHERE `providerId` = ?", carrier.id)

        expect("PROVIDER_UNAVAILABLE", 409) { carrierShip(o, item to 1) }.let { assertEquals("DISABLED", it.getString("state")) }

        sql("UPDATE `pano_market_shipping_carrier` SET `enabled` = 1 WHERE `providerId` = ?", carrier.id)

        // a manual entry for a carrier that cannot track a foreign number
        expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, provider = carrier.id, manual = manual()), 7, pool) }
            .let { assertEquals("NOT_SUPPORTED", it.getJsonObject("fieldErrors").getString("manual")) }

        carrier.caps = ScriptedCarrier.caps(externalTracking = true)

        expect("INVALID_SHIPMENT", 400) { service.createShipment(o.id, body(item to 1, provider = carrier.id, manual = manual(number = null)), 7, pool) }
            .let { assertEquals("TRACKING_REQUIRED", it.getJsonObject("fieldErrors").getString("manual")) }

        val s = service.createShipment(o.id, body(item to 1, provider = carrier.id, manual = manual(carrierName = "Aras", number = "EXT1")), 7, pool)

        assertEquals("MANUAL", s.getString("entryMode"))
        assertEquals(carrier.id, s.getString("providerId"))
        assertEquals("IN_TRANSIT", s.getString("status"))
        assertNull(shipmentNow(id(s)).nextPollAt, "no pull, no poll")

        carrier.caps = ScriptedCarrier.caps(externalTracking = true, trackingPull = true)

        val polled = service.createShipment(o.id, body(item to 1, provider = carrier.id, manual = manual(carrierName = "Aras", number = "EXT2")), 7, pool)

        assertEquals(w.clock.now() + 3_600_000, shipmentNow(id(polled)).nextPollAt, "an external tracking number is polled first after 1 h")

        carrier.caps = ScriptedCarrier.caps(externalTracking = true)

        carrier.caps = ScriptedCarrier.caps(createShipment = false)

        expect("INVALID_SHIPMENT", 400) { carrierShip(o, item to 1) }.let { assertEquals("CREATE_NOT_SUPPORTED", it.getJsonObject("fieldErrors").getString("providerId")) }
        assertEquals(0, carrier.creates.size)
        allocated(o)
    }

    @Test
    fun `an international carrier shipment needs customs data on every shipped line`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(requiresCustomsData = true)

        val missing = order(Line("Shirt", 1))
        expect("INVALID_SHIPMENT", 400) { carrierShip(missing, missing.items.single() to 1) }
            .let { assertEquals("CUSTOMS_DATA_MISSING", it.getJsonObject("fieldErrors").getString("items")) }

        assertEquals(0, itemNow(missing.items.single()).shippedQuantity)
        assertEquals(0, count("market_shipment"))

        val complete = order(Line("Shirt", 1, hs = "610910", origin = "TR"))

        carrier.onCreate = { r -> CreateShipmentResult.Created("C-${r.shipmentId}") }

        val s = carrierShip(complete, complete.items.single() to 1)

        assertEquals("CREATED", s.getString("status"))
        assertEquals("610910", carrier.creates.single().items.single().hsCode)
        assertEquals("TR", carrier.creates.single().items.single().originCountry)
    }

    // ================================================================================================ test 53 / 54: carrier create and retry

    @Test
    fun `carrier create stores the label file, LABEL_READY, cost, the first poll in 1 h, and the order stays PENDING`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(trackingPull = true, labelFormats = setOf(LabelFormat.PDF))
        carrier.onCreate = { r ->
            CreateShipmentResult.Created("CAR-${r.shipmentId}").also {
                it.trackingNumber = "CT-1"
                it.labels = listOf(LabelDocument(LabelFormat.PDF, "%PDF-fake".toByteArray()))
                it.documents = listOf(LabelDocument(LabelFormat.PDF, "customs".toByteArray()).also { d -> d.kind = LabelDocument.KIND_CUSTOMS })
                it.cost = Money(1234, "EUR")
                it.carrierName = "Fake Express"
                it.status = com.panomc.plugins.market.spi.shipping.ShipmentStatus.LABEL_READY
                it.providerData = JsonObject().put("token", "secret-handle")
            }
        }

        val m = method(template = "https://t.example.com/{tracking}")
        val o = order(Line("Shirt", 2), methodId = m)
        val item = o.items.single()
        val s = carrierShip(o, item to 2)
        val row = shipmentNow(id(s))

        assertEquals("LABEL_READY", s.getString("status"))
        assertEquals("CARRIER", row.entryMode.name)
        assertEquals("CAR-${row.id}", row.carrierReference)
        assertEquals("CT-1", row.trackingNumber)
        assertEquals("https://t.example.com/CT-1", row.trackingUrl)
        assertEquals("Fake Express", row.carrierName)
        assertEquals(1234L, row.cost)
        assertEquals("EUR", row.costCurrency)
        assertEquals("${row.id}-0.pdf", row.labelFile)
        assertEquals("PDF", row.labelFormat)
        assertEquals("%PDF-fake", Files.readString(labels.resolve(row.labelFile!!)))
        assertEquals(1, JsonArray(row.documents).size())
        assertEquals("CUSTOMS", JsonArray(row.documents).getJsonObject(0).getString("type"))
        assertEquals("customs", Files.readString(labels.resolve("${row.id}-1.pdf")))
        assertEquals(w.clock.now() + 3_600_000, row.nextPollAt)
        assertNull(row.claimedUntil)
        assertNull(row.lastErrorCode)
        assertNotNull(row.providerData)
        assertFalse(row.providerData!!.contains("secret-handle"), "provider data is stored encrypted")
        assertEquals(ShippingStatus.PENDING, orderNow(o.id).shippingStatus, "LABEL_READY is not in the carrier's hands yet")
        assertEquals(2, itemNow(item).shippedQuantity, "the units are allocated at once")
        assertEquals(true, s.getBoolean("hasLabel"))
        assertEquals(1, s.getJsonArray("documents").size())
        assertFalse(s.toString().contains("customs.pdf") || s.toString().contains("-1.pdf"), "the panel JSON never carries file names")

        val req = carrier.creates.single()

        assertEquals(row.merchantReference, req.merchantReference)
        assertEquals("TR", req.from.country)
        assertEquals("DE", req.to.country)
        assertEquals(4000L, req.declaredValue.amount)
        assertEquals(2, req.items.single().quantity)
        assertEquals(2000L, req.items.single().unitValue.amount)
        assertNull(req.previousCarrierReference)

        // the carrier hands the parcel over: an event, the status, the derived order state
        service.applyUpdate(row.id, TrackingUpdate(ShipmentTarget.Id(row.id), listOf(TrackingEvent(com.panomc.plugins.market.spi.shipping.ShipmentStatus.IN_TRANSIT, w.clock.now() + 1))), TrackingSource.WEBHOOK)

        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(row.id).status)
        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)
        allocated(o)
    }

    @Test
    fun `a failed carrier create is 502 with the code, the row stays CREATED with its units, the retry sends the same merchantReference`(): Unit = runBlocking {
        val calls = AtomicInteger()

        carrier.onCreate = { r ->
            if (calls.incrementAndGet() == 1) {
                CreateShipmentResult.Failed(ShipmentErrorCode.INSUFFICIENT_BALANCE, "balance is 0.00").also {
                    it.carrierReference = "STEP1-OBJ"
                    it.providerData = JsonObject().put("offer", "o-1")
                }
            } else {
                CreateShipmentResult.Created("CAR-${r.shipmentId}").also { it.trackingNumber = "CT-9" }
            }
        }

        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val failure = expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o, item to 2) }
        val row = w.shipments.getByOrderId(o.id, pool).single()

        assertEquals("INSUFFICIENT_BALANCE", failure.getString("code"))
        assertEquals(row.id, failure.getLong("shipmentId"))
        assertEquals(ShipmentStatus.CREATED, row.status)
        assertEquals("INSUFFICIENT_BALANCE", row.lastErrorCode)
        assertEquals("balance is 0.00", row.lastError)
        assertNull(row.claimedUntil)
        assertEquals("STEP1-OBJ", row.carrierReference, "the carrier object of a two-step carrier is kept")
        assertNotNull(row.providerData)
        assertEquals(2, itemNow(item).shippedQuantity, "the units stay allocated")
        assertEquals(ShippingStatus.PENDING, orderNow(o.id).shippingStatus)
        assertFalse(failure.encode().contains("balance is 0.00"), "the carrier text never reaches the response")
        allocated(o)

        val retried = service.retryShipment(row.id, 7, pool)

        assertEquals(2, carrier.creates.size)
        assertEquals(row.merchantReference, carrier.creates[0].merchantReference)
        assertEquals(row.merchantReference, carrier.creates[1].merchantReference, "the merchant reference is the carrier's idempotency key")
        assertEquals("STEP1-OBJ", carrier.creates[1].previousCarrierReference)
        assertEquals("o-1", carrier.creates[1].previousProviderData!!.getString("offer"))
        assertEquals("CAR-${row.id}", retried.getString("carrierReference"))
        assertNull(retried.getString("lastErrorCode"))
        assertEquals("CT-9", retried.getString("trackingNumber"))
        assertEquals(1, count("market_shipment"))
        assertEquals(2, itemNow(item).shippedQuantity)
        allocated(o)
    }

    @Test
    fun `a carrier exception and a carrier timeout are recorded as failures of the shipment`(): Unit = runBlocking {
        carrier.onCreate = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "connection refused") }

        val o = order(Line("Shirt", 3))
        val item = o.items.single()

        expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o, item to 1) }.let { assertEquals("GATEWAY_UNREACHABLE", it.getString("code")) }

        val first = w.shipments.getByOrderId(o.id, pool).single()

        assertEquals("GATEWAY_UNREACHABLE", first.lastErrorCode)
        assertEquals(ShipmentStatus.CREATED, first.status)

        carrier.onCreate = { throw IllegalStateException("boom with internals") }

        expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o, item to 1) }.let { assertEquals("INTERNAL", it.getString("code")) }
        assertFalse(w.shipments.getByOrderId(o.id, pool).last().lastError!!.contains("internals"), "an unexpected exception's text is not stored")

        service = newService(createTimeoutMs = 150)
        carrier.onCreate = { delay(2_000); CreateShipmentResult.Created("late") }

        val t = expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o, item to 1) }

        assertEquals("TIMEOUT", t.getString("code"))
        assertEquals("TIMEOUT", w.shipments.getByOrderId(o.id, pool).last().lastErrorCode)
        assertEquals(3, itemNow(item).shippedQuantity)
        allocated(o)
    }

    @Test
    fun `retry is refused while the claim is live, for a manual shipment and for a RATE_EXPIRED failure, and two parallel retries call the carrier once`(): Unit = runBlocking {
        val o = order(Line("Shirt", 4))
        val item = o.items.single()
        val m = ship(o, item to 1)

        expect("INVALID_SHIPMENT_TRANSITION", 400) { service.retryShipment(id(m), 7, pool) }

        carrier.onCreate = { CreateShipmentResult.Failed(ShipmentErrorCode.RATE_EXPIRED, "expired") }
        expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o, item to 1) }

        val expired = w.shipments.getByOrderId(o.id, pool).last()

        expect("INVALID_SHIPMENT_TRANSITION", 400) { service.retryShipment(expired.id, 7, pool) }

        // a live claim (a create in flight) blocks a retry
        sql("UPDATE `pano_market_shipment` SET `lastErrorCode` = 'OTHER', `claimedUntil` = ? WHERE `id` = ?", w.clock.now() + 30_000, expired.id)

        expect("INVALID_SHIPMENT_TRANSITION", 400) { service.retryShipment(expired.id, 7, pool) }

        // the claim ran out: two parallel retries, one carrier call
        w.clock.advance(61_000)

        val gate = AtomicInteger()

        carrier.onCreate = { r -> gate.incrementAndGet(); delay(300); CreateShipmentResult.Created("OK-${r.shipmentId}") }

        val results = Race.run(2) { service.retryShipment(expired.id, 7, pool) }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, gate.get(), "exactly one retry reached the carrier")
        results.filter { it.isFailure }.forEach { assertEquals("INVALID_SHIPMENT_TRANSITION", (it.exceptionOrNull() as Error).getErrorCode()) }
        assertEquals("OK-${expired.id}", shipmentNow(expired.id).carrierReference)
        allocated(o)
    }

    @Test
    fun `a retry of a creation that succeeded is refused`(): Unit = runBlocking {
        carrier.onCreate = { r -> CreateShipmentResult.Created("OK-${r.shipmentId}") }

        val o = order(Line("Shirt", 1))
        val s = carrierShip(o, o.items.single() to 1)

        assertEquals(false, s.getJsonObject("allowed").getBoolean("retry"))
        expect("INVALID_SHIPMENT_TRANSITION", 400) { service.retryShipment(id(s), 7, pool) }
        assertEquals(1, carrier.creates.size)
    }

    // ================================================================================================ test 56: cancel

    @Test
    fun `cancel of a LABEL_READY carrier shipment with the provider cancelling releases the units and sets the order back to PENDING`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}").also { it.labels = listOf(LabelDocument(LabelFormat.PDF, byteArrayOf(1))) } }
        carrier.onCancel = { CancelShipmentResult.cancelled() }

        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val s = carrierShip(o, item to 2)

        assertEquals("LABEL_READY", s.getString("status"))
        assertEquals(true, s.getJsonObject("allowed").getBoolean("cancel"))

        val cancelled = service.cancelShipment(id(s), false, 7, pool)
        val row = shipmentNow(id(s))

        assertEquals("CANCELLED", cancelled.getString("status"))
        assertEquals(ShipmentStatus.CANCELLED, row.status)
        assertTrue(row.itemsReleased)
        assertNotNull(row.cancelledAt)
        assertNull(row.nextPollAt)
        assertEquals(0, itemNow(item).shippedQuantity)
        assertEquals(ShippingStatus.PENDING, orderNow(o.id).shippingStatus)
        assertEquals(1, carrier.cancels.size)
        assertTrue("SHIPMENT_CANCELLED" in orderEventTypes(o.id))
        assertEquals(2, shippableOf(o, item))
        allocated(o)

        expect("SHIPMENT_NOT_CANCELLABLE", 409) { service.cancelShipment(id(s), false, 7, pool) }.let { assertEquals("TERMINAL", it.getString("reason")) }

        // the units can be shipped again
        ship(o, item to 2)

        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)
        allocated(o)
    }

    @Test
    fun `a provider that refuses the cancel is 502 and changes nothing, force cancels locally`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}") }
        carrier.onCancel = { CancelShipmentResult.refused("already picked up") }

        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val s = carrierShip(o, item to 2)

        expect("SHIPPING_PROVIDER_ERROR", 502) { service.cancelShipment(id(s), false, 7, pool) }.let {
            assertEquals("GATEWAY_REJECTED", it.getString("code"))
            assertEquals(id(s), it.getLong("shipmentId"))
        }

        val unchanged = shipmentNow(id(s))

        assertEquals(ShipmentStatus.CREATED, unchanged.status)
        assertFalse(unchanged.itemsReleased)
        assertEquals("already picked up", unchanged.lastError)
        assertEquals(2, itemNow(item).shippedQuantity)

        service.cancelShipment(id(s), true, 7, pool)

        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(id(s)).status)
        assertEquals(0, itemNow(item).shippedQuantity)
        assertEquals(2, carrier.cancels.size, "force still asks the carrier first")

        val data = sql("SELECT `data` FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'SHIPMENT_CANCELLED'", o.id).single().getString("data")

        assertEquals(true, JsonObject(data).getBoolean("forced"))
        allocated(o)
    }

    @Test
    fun `a provider exception on cancel is GATEWAY_REJECTED, force cancels anyway, unavailable and unsupported follow the table`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}") }
        carrier.onCancel = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }

        val o = order(Line("Shirt", 4))
        val item = o.items.single()
        val a = carrierShip(o, item to 1)
        val b = carrierShip(o, item to 1)
        val c = carrierShip(o, item to 1)

        expect("SHIPPING_PROVIDER_ERROR", 502) { service.cancelShipment(id(a), false, 7, pool) }.let { assertEquals("GATEWAY_REJECTED", it.getString("code")) }
        service.cancelShipment(id(a), true, 7, pool)

        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(id(a)).status)

        // the provider is gone: 409 unless forced
        lookup.remove(carrier.id)

        expect("PROVIDER_UNAVAILABLE", 409) { service.cancelShipment(id(b), false, 7, pool) }.let { assertEquals("MISSING", it.getString("state")) }
        assertEquals(ShipmentStatus.CREATED, shipmentNow(id(b)).status)

        service.cancelShipment(id(b), true, 7, pool)

        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(id(b)).status)

        // a provider without cancel support
        lookup.addShipping(carrier)
        carrier.caps = ScriptedCarrier.caps(cancel = false)

        expect("SHIPMENT_NOT_CANCELLABLE", 409) { service.cancelShipment(id(c), false, 7, pool) }.let { assertEquals("UNSUPPORTED", it.getString("reason")) }

        service.cancelShipment(id(c), true, 7, pool)

        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(id(c)).status)
        assertEquals(0, itemNow(item).shippedQuantity)
        allocated(o)
    }

    @Test
    fun `cancel rules - a carrier parcel in the carrier's hands, an unfinished create, a manual in-transit shipment, a creation that never reached the carrier`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true, trackingPull = true)
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}") }
        carrier.onCancel = { CancelShipmentResult.cancelled() }

        val o = order(Line("Shirt", 5))
        val item = o.items.single()

        // the carrier has the parcel
        val handed = carrierShip(o, item to 1)

        service.applyUpdate(id(handed), TrackingUpdate(ShipmentTarget.Id(id(handed)), listOf(TrackingEvent(com.panomc.plugins.market.spi.shipping.ShipmentStatus.IN_TRANSIT, w.clock.now()))), TrackingSource.WEBHOOK)

        expect("SHIPMENT_NOT_CANCELLABLE", 409) { service.cancelShipment(id(handed), false, 7, pool) }.let { assertEquals("HANDED_OVER", it.getString("reason")) }
        expect("SHIPMENT_NOT_CANCELLABLE", 409) { service.cancelShipment(id(handed), true, 7, pool) }.let { assertEquals("HANDED_OVER", it.getString("reason")) }

        // a create that is in flight (no carrier reference, live claim)
        val pending = shipmentWithoutCarrierReference(o, item, claimed = true)

        expect("SHIPMENT_NOT_CANCELLABLE", 409) { service.cancelShipment(pending, false, 7, pool) }.let { assertEquals("IN_PROGRESS", it.getString("reason")) }

        // a crash between the carrier call and tx2: no reference, the claim ran out: local cancel, the carrier is not asked
        w.clock.advance(120_000)

        service.cancelShipment(pending, false, 7, pool)

        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(pending).status)
        assertEquals(0, carrier.cancels.size)

        // a manual shipment on its way is cancellable
        val manualShipment = ship(o, item to 1, manual = manual(number = "M1"))

        assertEquals("IN_TRANSIT", manualShipment.getString("status"))

        service.cancelShipment(id(manualShipment), false, 7, pool)

        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(id(manualShipment)).status)
        assertEquals(0, carrier.cancels.size)
        assertEquals(1, itemNow(item).shippedQuantity, "only the handed-over parcel is allocated")
        allocated(o)
    }

    private suspend fun shipmentWithoutCarrierReference(o: Ref, item: Long, claimed: Boolean): Long {
        sql("UPDATE `pano_market_order_item` SET `shippedQuantity` = `shippedQuantity` + 1 WHERE `id` = ?", item)

        val now = w.clock.now()
        val id = w.shipments.add(
            com.panomc.plugins.market.db.model.MarketShipment(
                orderId = o.id, providerId = carrier.id, merchantReference = "PENDING${w.ids.publicId().takeLast(9)}", claimedUntil = if (claimed) now + 60_000 else null,
                fromAddress = "{}", toAddress = address, createdAt = now, updatedAt = now
            ),
            pool
        )!!

        w.shipmentItems.add(com.panomc.plugins.market.db.model.MarketShipmentItem(shipmentId = id, orderItemId = item, quantity = 1, createdAt = now, updatedAt = now), pool)

        return id
    }

    // ================================================================================================ review fixes: cancel decision under the lock

    private suspend fun failedCarrierRow(o: Ref, item: Long): Long {
        carrier.onCreate = { CreateShipmentResult.Failed(ShipmentErrorCode.SERVICE_UNAVAILABLE, "down") }

        expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o, item to 1) }

        carrier.creates.clear()

        return w.shipments.getByOrderId(o.id, pool).last().id
    }

    private suspend fun updateShipment(conn: SqlConnection, shipmentId: Long, set: String, vararg args: Any?) {
        conn.preparedQuery("UPDATE `pano_market_shipment` SET $set WHERE `id` = ?").execute(Tuple.from(args.toList() + shipmentId)).coAwait()
    }

    /** Holds the order lock, lets a cancel of [shipmentId] read the row and queue for that lock, applies [change] to the row, then lets the cancel go on. */
    private suspend fun cancelWhileRowMoves(o: Ref, shipmentId: Long, force: Boolean, change: suspend (SqlConnection) -> Unit): Result<JsonObject> = coroutineScope {
        var cancel: Deferred<Result<JsonObject>>? = null

        w.db.tx { conn ->
            conn.preparedQuery("SELECT `id` FROM `pano_market_order` WHERE `id` = ? FOR UPDATE").execute(Tuple.of(o.id)).coAwait()

            cancel = async(Dispatchers.IO) { runCatching { service.cancelShipment(shipmentId, force, 7, pool) } }

            delay(800)
            change(conn)
        }

        cancel!!.await()
    }

    private fun reasonOf(failure: Throwable?): String? = (failure as Error).let { assertEquals("SHIPMENT_NOT_CANCELLABLE", it.getErrorCode()); JsonObject(it.encode()).getString("reason") }

    @Test
    fun `cancel that read a failed row which a retry claims before the order lock is IN_PROGRESS and changes nothing`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)

        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val sid = failedCarrierRow(o, item)

        val result = cancelWhileRowMoves(o, sid, false) { conn -> updateShipment(conn, sid, "`claimedUntil` = ?", w.clock.now() + 30_000) }

        assertTrue(result.isFailure, "the cancel must not go through over a create in flight")
        assertEquals("IN_PROGRESS", reasonOf(result.exceptionOrNull()))
        assertEquals(ShipmentStatus.CREATED, shipmentNow(sid).status)
        assertEquals(1, itemNow(item).shippedQuantity, "the units stay allocated")
        assertEquals(0, carrier.cancels.size)
    }

    @Test
    fun `cancel that read a row without carrier reference which gained one before the lock asks the carrier first`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)

        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val sid = failedCarrierRow(o, item)

        val result = cancelWhileRowMoves(o, sid, false) { conn -> updateShipment(conn, sid, "`carrierReference` = ?, `lastErrorCode` = NULL", "CAR-LATE") }

        assertTrue(result.isSuccess, "the cancel ends cancelled: ${result.exceptionOrNull()}")
        assertEquals(1, carrier.cancels.size, "the paid shipment is voided at the carrier")
        assertEquals("CAR-LATE", carrier.cancels.single().carrierReference)
        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(sid).status)
        assertEquals(0, itemNow(item).shippedQuantity)

        // the carrier refuses: without force nothing is cancelled locally
        val o2 = order(Line("Shirt", 2))
        val sid2 = failedCarrierRow(o2, o2.items.single())

        carrier.onCancel = { CancelShipmentResult.refused("no") }

        val refused = cancelWhileRowMoves(o2, sid2, false) { conn -> updateShipment(conn, sid2, "`carrierReference` = ?, `lastErrorCode` = NULL", "CAR-LATE2") }

        assertTrue(refused.isFailure)
        assertEquals("SHIPPING_PROVIDER_ERROR", (refused.exceptionOrNull() as Error).getErrorCode())
        assertEquals(ShipmentStatus.CREATED, shipmentNow(sid2).status)
    }

    @Test
    fun `cancel that read a row which reached a handed-over status before the lock is HANDED_OVER, force or not`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)

        val o = order(Line("Shirt", 3))
        val item = o.items.single()
        val sid = failedCarrierRow(o, item)

        val result = cancelWhileRowMoves(o, sid, true) { conn -> updateShipment(conn, sid, "`carrierReference` = ?, `lastErrorCode` = NULL, `status` = 'IN_TRANSIT'", "CAR-LATE") }

        assertTrue(result.isFailure)
        assertEquals("HANDED_OVER", reasonOf(result.exceptionOrNull()))
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(sid).status)
        assertEquals(0, carrier.cancels.size)
        assertEquals(1, itemNow(item).shippedQuantity)
    }

    @Test
    fun `cancel and retry in parallel on a failed row end cancelled without a create or in progress with the shipment intact`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)

        repeat(Race.rounds) { round ->
            val o = order(Line("Shirt", 2))
            val item = o.items.single()
            val sid = failedCarrierRow(o, item)

            carrier.cancels.clear()
            carrier.onCreate = { r -> delay(120); CreateShipmentResult.Created("OK-${r.shipmentId}") }
            carrier.onCancel = { CancelShipmentResult.cancelled() }

            val results = Race.runWithSetup(2, { it }) { i -> if (i == 0) service.retryShipment(sid, 7, pool) else service.cancelShipment(sid, false, 7, pool) }
            val retry = results[0]
            val cancel = results[1]
            val row = shipmentNow(sid)
            val label = "round $round: retry=$retry cancel=$cancel creates=${carrier.creates.size} cancels=${carrier.cancels.size} status=${row.status}"

            if (row.status == ShipmentStatus.CANCELLED) {
                assertTrue(cancel.isSuccess, label)
                assertTrue(carrier.creates.isEmpty() || carrier.cancels.size == 1, "a created carrier shipment is voided: $label")
                assertEquals(0, itemNow(item).shippedQuantity, label)
            } else {
                assertTrue(retry.isSuccess, label)
                assertTrue(cancel.isFailure, label)
                assertEquals("OK-$sid", row.carrierReference, label)
                assertEquals(1, carrier.creates.size, label)
                assertEquals(1, itemNow(item).shippedQuantity, label)
            }
        }
    }

    // ================================================================================================ review fixes: the carrier result is never lost

    @Test
    fun `a carrier that returns more pieces than the request had parcels still has its reference and numbers stored`(): Unit = runBlocking {
        carrier.onCreate = { r ->
            CreateShipmentResult.Created("CAR-${r.shipmentId}").also {
                it.trackingNumber = "MASTER"
                it.pieces = listOf(ShipmentPiece("P-1"), ShipmentPiece("P-2"), ShipmentPiece("P-3"))
            }
        }

        val o = order(Line("Shirt", 2))
        val s = carrierShip(o, o.items.single() to 2)
        val row = shipmentNow(id(s))
        val packages = JsonArray(row.packages)

        assertEquals("CAR-${row.id}", row.carrierReference)
        assertEquals("MASTER", row.trackingNumber)
        assertNull(row.lastErrorCode)
        assertNull(row.claimedUntil)
        assertEquals(1, packages.size(), "only the parcels of the request are numbered")
        assertEquals("P-1", packages.getJsonObject(0).getString("trackingNumber"))
    }

    @Test
    fun `a label that cannot be written does not lose the carrier reference`(): Unit = runBlocking {
        val blocked = Files.createTempFile("market-labels-blocked", ".file")

        service = newService(labelsDir = blocked.resolve("sub"))
        carrier.onCreate = { r ->
            CreateShipmentResult.Created("CAR-${r.shipmentId}").also {
                it.trackingNumber = "CT-5"
                it.labels = listOf(LabelDocument(LabelFormat.PDF, "%PDF-fake".toByteArray()))
            }
        }

        val o = order(Line("Shirt", 1))
        val s = carrierShip(o, o.items.single() to 1)
        val row = shipmentNow(id(s))

        assertEquals("CAR-${row.id}", row.carrierReference)
        assertEquals("CT-5", row.trackingNumber)
        assertNull(row.labelFile, "no label file was written")
        assertNull(row.lastErrorCode)
        assertNull(row.claimedUntil)
        assertEquals(1, itemNow(o.items.single()).shippedQuantity)
    }

    @Test
    fun `a failing tx2 after the carrier answered stores the reference, INTERNAL, and the retry and the cancel work on it`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)
        carrier.onCreate = { r ->
            CreateShipmentResult.Created("CAR-${r.shipmentId}").also {
                it.providerData = JsonObject().put("token", "handle-1")
                it.status = com.panomc.plugins.market.spi.shipping.ShipmentStatus.LABEL_READY
            }
        }

        val o = order(Line("Shirt", 2))
        val o2 = order(Line("Shirt", 1))
        val trigger = "pano_mk133_fail_label_ready"
        var failed: Long = 0
        var failed2: Long = 0

        sql("DROP TRIGGER IF EXISTS `$trigger`")
        sql("CREATE TRIGGER `$trigger` BEFORE UPDATE ON `pano_market_shipment` FOR EACH ROW IF NEW.`status` = 'LABEL_READY' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'boom'; END IF")

        try {
            expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o, o.items.single() to 2) }.let { assertEquals("INTERNAL", it.getString("code")) }
            expect("SHIPPING_PROVIDER_ERROR", 502) { carrierShip(o2, o2.items.single() to 1) }

            failed = w.shipments.getByOrderId(o.id, pool).single().id
            failed2 = w.shipments.getByOrderId(o2.id, pool).single().id
        } finally {
            sql("DROP TRIGGER IF EXISTS `$trigger`")
        }

        val row = shipmentNow(failed)

        assertEquals("CAR-$failed", row.carrierReference, "the paid shipment's reference is stored")
        assertEquals("INTERNAL", row.lastErrorCode)
        assertNull(row.claimedUntil)
        assertEquals(ShipmentStatus.CREATED, row.status)
        assertNotNull(row.providerData)
        assertFalse(row.providerData!!.contains("handle-1"), "provider data is stored encrypted")
        assertEquals(2, itemNow(o.items.single()).shippedQuantity, "the units stay allocated")

        // the retry is idempotent at the carrier: it gets the stored reference back and finishes the row
        val retried = service.retryShipment(failed, 7, pool)

        assertEquals("CAR-$failed", carrier.creates.last().previousCarrierReference)
        assertEquals("handle-1", carrier.creates.last().previousProviderData!!.getString("token"))
        assertEquals("LABEL_READY", retried.getString("status"))
        assertNull(retried.getString("lastErrorCode"))

        // the other one is cancelled: a carrier reference exists, so the carrier is asked to void it
        service.cancelShipment(failed2, false, 7, pool)

        assertEquals(listOf("CAR-$failed2"), carrier.cancels.map { it.carrierReference })
        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(failed2).status)
        assertEquals(0, itemNow(o2.items.single()).shippedQuantity)
    }

    // ================================================================================================ test 57 / 58: release, returned

    @Test
    fun `releaseItems on a RETURNED shipment makes the units shippable again and the order PENDING, on IN_TRANSIT it is refused, a second release too`(): Unit = runBlocking {
        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val s = ship(o, item to 2)

        expect("INVALID_SHIPMENT_TRANSITION", 400) { service.editShipment(id(s), JsonObject().put("releaseItems", true), 7, pool) }

        service.editShipment(id(s), JsonObject().put("status", "DELIVERED"), 7, pool)
        service.editShipment(id(s), JsonObject().put("status", "RETURNED"), 7, pool)

        assertEquals(ShippingStatus.RETURNED, orderNow(o.id).shippingStatus, "all parcels returned and not released")
        assertEquals(2, itemNow(item).shippedQuantity)
        assertEquals(0, shippableOf(o, item))

        val (json, changed) = service.editShipment(id(s), JsonObject().put("releaseItems", true), 7, pool)

        assertEquals(listOf("releaseItems"), changed)
        assertEquals(true, json.getBoolean("itemsReleased"))
        assertEquals(0, itemNow(item).shippedQuantity)
        assertEquals(ShippingStatus.PENDING, orderNow(o.id).shippingStatus)
        assertEquals(2, shippableOf(o, item))
        allocated(o)

        expect("INVALID_SHIPMENT_TRANSITION", 400) { service.editShipment(id(s), JsonObject().put("releaseItems", true), 7, pool) }

        // re-ship
        ship(o, item to 2, manual = manual(number = "AGAIN"))

        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)
        allocated(o)
    }

    @Test
    fun `a LOST shipment can be released with the status change in the same request, a found parcel moves again`(): Unit = runBlocking {
        val o = order(Line("Shirt", 1))
        val item = o.items.single()
        val s = ship(o, item to 1)

        service.editShipment(id(s), JsonObject().put("status", "LOST").put("releaseItems", true).put("note", "lost at the depot"), 7, pool)

        assertEquals(ShipmentStatus.LOST, shipmentNow(id(s)).status)
        assertTrue(shipmentNow(id(s)).itemsReleased)
        assertEquals(0, itemNow(item).shippedQuantity)
        assertEquals(ShippingStatus.PENDING, orderNow(o.id).shippingStatus)
        assertEquals("lost at the depot", events(id(s)).let { sql("SELECT `description` FROM `pano_market_shipment_event` WHERE `shipmentId` = ? AND `status` = 'LOST'", id(s)).single().getString("description") })
        allocated(o)
    }

    @Test
    fun `an unfound LOST shipment can be found again by hand, invalid manual transitions are INVALID_SHIPMENT_TRANSITION with from and to`(): Unit = runBlocking {
        val o = order(Line("Shirt", 3))
        val item = o.items.single()
        val s = ship(o, item to 1)

        w.clock.advance(1_000)
        service.editShipment(id(s), JsonObject().put("status", "LOST"), 7, pool)
        w.clock.advance(1_000)
        service.editShipment(id(s), JsonObject().put("status", "IN_TRANSIT"), 7, pool)

        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(id(s)).status)

        w.clock.advance(1_000)
        service.editShipment(id(s), JsonObject().put("status", "RETURNED"), 7, pool)
        w.clock.advance(1_000)

        val e = expect("INVALID_SHIPMENT_TRANSITION", 400) { service.editShipment(id(s), JsonObject().put("status", "DELIVERED"), 7, pool) }

        assertEquals("RETURNED", e.getString("from"))
        assertEquals("DELIVERED", e.getString("to"))
        assertEquals(ShipmentStatus.RETURNED, shipmentNow(id(s)).status)
        assertEquals(listOf("IN_TRANSIT", "LOST", "IN_TRANSIT", "RETURNED"), events(id(s)).map { it.first }, "a refused edit leaves no event behind")

        expect("INVALID_SHIPMENT", 400) { service.editShipment(id(s), JsonObject().put("status", "CANCELLED"), 7, pool) }
            .let { assertEquals("INVALID", it.getJsonObject("fieldErrors").getString("status")) }
        expect("INVALID_SHIPMENT", 400) { service.editShipment(id(s), JsonObject().put("status", "CREATED"), 7, pool) }
    }

    // ================================================================================================ test 59: shippable rules and address edit

    @Test
    fun `an order that is not COMPLETED, has an open dispute, no address or no shipping is ORDER_NOT_SHIPPABLE with the reason`(): Unit = runBlocking {
        val pending = order(Line("Shirt", 1), status = OrderStatus.PENDING)
        val noAddress = order(Line("Shirt", 1), shippingAddress = null)
        val disputed = order(Line("Shirt", 1), dispute = DisputeStatus.OPEN)
        val digital = order(Line("Shirt", 1), requiresShipping = false)

        expect("ORDER_NOT_SHIPPABLE", 409) { ship(pending, pending.items.single() to 1) }.let { assertEquals("STATUS", it.getString("reason")) }
        expect("ORDER_NOT_SHIPPABLE", 409) { ship(noAddress, noAddress.items.single() to 1) }.let { assertEquals("NO_ADDRESS", it.getString("reason")) }
        expect("ORDER_NOT_SHIPPABLE", 409) { ship(disputed, disputed.items.single() to 1) }.let { assertEquals("DISPUTE", it.getString("reason")) }
        expect("ORDER_NOT_SHIPPABLE", 409) { ship(digital, digital.items.single() to 1) }.let { assertEquals("STATUS", it.getString("reason")) }

        assertEquals(0, count("market_shipment"))

        val partlyRefunded = order(Line("Shirt", 2), status = OrderStatus.PARTIALLY_REFUNDED)

        ship(partlyRefunded, partlyRefunded.items.single() to 2)
    }

    @Test
    fun `the shipping address can be edited until a live shipment exists and again after it was cancelled`(): Unit = runBlocking {
        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val before = orderNow(o.id).updatedAt

        w.clock.advance(5)
        service.editShippingAddress(
            o.id,
            JsonObject().put("firstName", "  Anna ").put("lastName", "Schmidt").put("phone", "0049 30 1234567").put("country", "de").put("city", "Hamburg").put("line1", "Hafen 2").put("postalCode", "20457"),
            7, pool
        )

        val edited = JsonObject(orderNow(o.id).shippingAddress!!)

        assertEquals("Anna", edited.getString("firstName"))
        assertEquals("DE", edited.getString("country"))
        assertEquals("Hamburg", edited.getString("city"))
        assertEquals("steve@example.com", edited.getString("email"), "defaults to the order e-mail")
        assertTrue(orderNow(o.id).updatedAt > before, "every order write grows the version")

        val note = sql("SELECT `message`, `data`, `actorType` FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'NOTE'", o.id).single()

        assertEquals("shipping address updated", note.getString("message"))
        assertNull(note.getString("data"))
        assertEquals("ADMIN", note.getString("actorType"))

        expect("SHIPPING_ADDRESS_REQUIRED", 400) { service.editShippingAddress(o.id, JsonObject().put("country", "DE"), 7, pool) }
            .let { assertTrue(it.getJsonArray("fields").map { f -> f.toString() }.containsAll(listOf("firstName", "city", "line1"))) }
        expect("SHIPPING_ADDRESS_REQUIRED", 400) { service.editShippingAddress(o.id, JsonObject().put("country", "ZZ"), 7, pool) }
        assertEquals("Hamburg", JsonObject(orderNow(o.id).shippingAddress!!).getString("city"), "a refused edit changes nothing")

        val s = ship(o, item to 1)

        expect("ORDER_NOT_SHIPPABLE", 409) { service.editShippingAddress(o.id, JsonObject(address), 7, pool) }.let { assertEquals("HAS_SHIPMENTS", it.getString("reason")) }
        assertEquals("Hamburg", JsonObject(orderNow(o.id).shippingAddress!!).getString("city"))

        service.cancelShipment(id(s), false, 7, pool)
        service.editShippingAddress(o.id, JsonObject(address), 7, pool)

        assertEquals("Berlin", JsonObject(orderNow(o.id).shippingAddress!!).getString("city"), "a cancelled shipment no longer blocks the edit")

        expect("NOT_FOUND", 404) { service.editShippingAddress(98765, JsonObject(address), 7, pool) }
        expect("ORDER_NOT_SHIPPABLE", 409) { service.editShippingAddress(order(Line("Digital", 1), requiresShipping = false).id, JsonObject(address), 7, pool) }
    }

    // ================================================================================================ PUT /shipments/:id

    @Test
    fun `editing a shipment - the first tracking number queues the mail once, a carrier number is read-only, a bad URL is refused`(): Unit = runBlocking {
        val tpl = method(template = "https://t.example.com/{tracking}")
        val o = order(Line("Shirt", 3), methodId = tpl)
        val item = o.items.single()
        val s = ship(o, item to 1, manual = manual(number = null, carrierName = null))

        // a manual shipment is IN_TRANSIT, so the mail went out without a number (second clause of 10 section 11.1)
        assertEquals(1, mails("SHIPMENT_SHIPPED").size)
        assertEquals(false, mails("SHIPMENT_SHIPPED").single().getJsonObject("params").getBoolean("hasTracking"))

        val (json, changed) = service.editShipment(id(s), JsonObject().put("trackingNumber", "LATE-1").put("carrierName", "PTT").put("note", "box 3"), 7, pool)

        assertEquals(listOf("carrierName", "note", "trackingNumber", "trackingUrl").sorted(), changed.sorted())
        assertEquals("LATE-1", json.getString("trackingNumber"))
        assertEquals("https://t.example.com/LATE-1", json.getString("trackingUrl"))
        assertEquals("PTT", json.getString("carrierName"))
        assertEquals("box 3", json.getString("note"))
        assertEquals(1, mails("SHIPMENT_SHIPPED").size, "a later number does not queue a second mail")

        val again = service.editShipment(id(s), JsonObject().put("trackingNumber", "LATE-1"), 7, pool)

        assertEquals(emptyList<String>(), again.second, "the same value changes nothing")

        expect("INVALID_SHIPMENT", 400) { service.editShipment(id(s), JsonObject().put("trackingUrl", "ftp://x.example.com/1"), 7, pool) }
            .let { assertEquals("INVALID", it.getJsonObject("fieldErrors").getString("trackingUrl")) }
        expect("INVALID_SHIPMENT", 400) { service.editShipment(id(s), JsonObject().put("trackingNumber", "bad;number"), 7, pool) }
            .let { assertEquals("INVALID", it.getJsonObject("fieldErrors").getString("trackingNumber")) }
    }

    @Test
    fun `the tracking number of a carrier shipment with a carrier reference is read-only, a cancelled shipment takes no tracking edit`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(cancel = true)
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}").also { it.trackingNumber = "C1" } }

        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val s = carrierShip(o, item to 1)

        expect("INVALID_SHIPMENT", 400) { service.editShipment(id(s), JsonObject().put("trackingNumber", "HACK"), 7, pool) }
            .let { assertEquals("READ_ONLY", it.getJsonObject("fieldErrors").getString("trackingNumber")) }

        service.editShipment(id(s), JsonObject().put("trackingUrl", "https://x.example.com/C1").put("note", "n"), 7, pool)

        assertEquals("https://x.example.com/C1", shipmentNow(id(s)).trackingUrl)

        service.cancelShipment(id(s), false, 7, pool)

        expect("INVALID_SHIPMENT_TRANSITION", 400) { service.editShipment(id(s), JsonObject().put("trackingUrl", "https://x.example.com/again"), 7, pool) }
        expect("NOT_FOUND", 404) { service.editShipment(98765, JsonObject().put("note", "x"), 7, pool) }
    }

    // ================================================================================================ applyUpdate (tests 42 to 49 on the service)

    private fun trackingEvent(status: String, at: Long, id: String? = null) =
        TrackingEvent(com.panomc.plugins.market.spi.shipping.ShipmentStatus.valueOf(status), at).also { it.eventId = id }

    @Test
    fun `tracking updates - newest wins, out-of-order events are stored, terminal states hold, DELIVERED queues one mail and one webhook`(): Unit = runBlocking {
        val endpoint = webhooks.endpoint("https://hooks.example.com/market", events = "[\"shipment.delivered\"]")
        val o = order(Line("Shirt", 1))
        val s = ship(o, o.items.single() to 1)
        val sid = id(s)
        val t0 = w.clock.now()

        fun update(vararg e: TrackingEvent) = TrackingUpdate(ShipmentTarget.Id(sid), e.toList())

        service.applyUpdate(sid, update(trackingEvent("DELIVERED", t0 + 5_000, "e5"), trackingEvent("OUT_FOR_DELIVERY", t0 + 4_000, "e4")), TrackingSource.WEBHOOK)

        val delivered = shipmentNow(sid)

        assertEquals(ShipmentStatus.DELIVERED, delivered.status, "DELIVERED@t5 then OUT_FOR_DELIVERY@t4 stays DELIVERED")
        assertEquals(t0 + 5_000, delivered.deliveredAt)
        assertEquals(2, count("market_shipment_event", "`shipmentId` = ?", sid) - 1, "both stored next to the manual one")
        assertEquals(ShippingStatus.DELIVERED, orderNow(o.id).shippingStatus)
        assertNull(delivered.nextPollAt)
        assertEquals(1, mails("SHIPMENT_DELIVERED").size)
        assertEquals(1, webhooks.rows().filter { it.event == "shipment.delivered" }.size)
        assertEquals(endpoint.id, webhooks.rows().single().endpointId)

        // a replay changes nothing: no second mail, no second webhook, no second event
        val eventsBefore = count("market_shipment_event", "`shipmentId` = ?", sid)
        val replay = service.applyUpdate(sid, update(trackingEvent("DELIVERED", t0 + 5_000, "e5"), trackingEvent("OUT_FOR_DELIVERY", t0 + 4_000, "e4")), TrackingSource.WEBHOOK)

        assertEquals(replay.from, replay.to)
        assertEquals(eventsBefore, count("market_shipment_event", "`shipmentId` = ?", sid))
        assertEquals(1, mails("SHIPMENT_DELIVERED").size)
        assertEquals(1, webhooks.rows().size)

        // a carrier event after DELIVERED is stored and ignored; a manual RETURNED is applied
        service.applyUpdate(sid, update(trackingEvent("IN_TRANSIT", t0 + 9_000)), TrackingSource.POLL)

        assertEquals(ShipmentStatus.DELIVERED, shipmentNow(sid).status)
        assertEquals(eventsBefore + 1, count("market_shipment_event", "`shipmentId` = ?", sid))

        service.applyUpdate(sid, update(trackingEvent("RETURNED", t0 + 10_000)), TrackingSource.MANUAL)

        assertEquals(ShipmentStatus.RETURNED, shipmentNow(sid).status)
        assertEquals(ShippingStatus.RETURNED, orderNow(o.id).shippingStatus)
        allocated(o)
    }

    @Test
    fun `an event without id is de-duplicated by its hash, a failed attempt moves OUT_FOR_DELIVERY back to IN_TRANSIT`(): Unit = runBlocking {
        val o = order(Line("Shirt", 1))
        val s = ship(o, o.items.single() to 1)
        val sid = id(s)
        val t0 = w.clock.now()

        fun update(vararg e: TrackingEvent) = TrackingUpdate(ShipmentTarget.Id(sid), e.toList())

        val e1 = trackingEvent("OUT_FOR_DELIVERY", t0 + 4_000).also { it.location = "Berlin"; it.rawStatus = "ofd" }

        service.applyUpdate(sid, update(e1, e1), TrackingSource.POLL)
        service.applyUpdate(sid, update(trackingEvent("OUT_FOR_DELIVERY", t0 + 4_000).also { it.location = "Berlin"; it.rawStatus = "ofd" }), TrackingSource.POLL)

        assertEquals(2, count("market_shipment_event", "`shipmentId` = ?", sid), "the manual IN_TRANSIT plus one OUT_FOR_DELIVERY")
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shipmentNow(sid).status)

        service.applyUpdate(sid, update(trackingEvent("IN_TRANSIT", t0 + 6_000)), TrackingSource.POLL)

        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(sid).status)
    }

    @Test
    fun `a javascript URL from a provider is discarded, the template renders the number, a future event is clamped, the 201st event of an update is dropped`(): Unit = runBlocking {
        val tpl = method(template = "https://t.example.com/{tracking}")
        val o = order(Line("Shirt", 1), methodId = tpl)
        val s = ship(o, o.items.single() to 1, manual = manual(number = null))
        val sid = id(s)
        val now = w.clock.now()

        service.applyUpdate(
            sid,
            TrackingUpdate(ShipmentTarget.Id(sid), emptyList()).also {
                it.trackingNumber = "A B"
                it.trackingUrl = "javascript:alert(1)"
                it.estimatedDelivery = now + 86_400_000
                it.providerData = JsonObject().put("k", "v")
            },
            TrackingSource.POLL
        )

        val row = shipmentNow(sid)

        assertEquals("A B", row.trackingNumber)
        assertEquals("https://t.example.com/A%20B", row.trackingUrl)
        assertEquals(now + 86_400_000, row.estimatedDeliveryAt)
        assertNotNull(row.providerData)

        service.applyUpdate(sid, TrackingUpdate(ShipmentTarget.Id(sid), listOf(trackingEvent("EXCEPTION", now + 3 * 86_400_000L, "future"))), TrackingSource.POLL)

        assertEquals(now, sql("SELECT `occurredAt` FROM `pano_market_shipment_event` WHERE `shipmentId` = ? AND `dedupeKey` = 'future'", sid).single().getLong("occurredAt"))

        val many = List(201) { trackingEvent("IN_TRANSIT", now + it, "bulk$it") }

        service.applyUpdate(sid, TrackingUpdate(ShipmentTarget.Id(sid), many), TrackingSource.POLL)

        assertEquals(200, count("market_shipment_event", "`dedupeKey` LIKE 'bulk%' AND `shipmentId` = ?", sid))
        assertEquals(0, count("market_shipment_event", "`dedupeKey` = 'bulk200' AND `shipmentId` = ?", sid))
        allocated(o)
    }

    // ================================================================================================ track now

    @Test
    fun `track now applies the updates of the provider that resolve to the shipment and plans the next poll`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(trackingPull = true, cancel = true)
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}").also { it.trackingNumber = "TT1" } }

        val o = order(Line("Shirt", 2))
        val item = o.items.single()
        val s = carrierShip(o, item to 1)
        val t = w.clock.now() + 1_000

        carrier.onTrack = { req ->
            val view = req.shipments.single()

            listOf(
                TrackingUpdate(ShipmentTarget.CarrierReference(view.carrierReference!!), listOf(trackingEvent("IN_TRANSIT", t, "p1"))),
                TrackingUpdate(ShipmentTarget.CarrierReference("somebody-else"), listOf(trackingEvent("DELIVERED", t + 10, "p2")))
            )
        }

        val tracked = service.trackShipment(id(s), pool)

        assertEquals("IN_TRANSIT", tracked.getString("status"))
        assertEquals(1, count("market_shipment_event", "`shipmentId` = ?", id(s)))
        assertNotNull(tracked.getLong("lastPolledAt"))
        assertTrue(tracked.getLong("nextPollAt") > w.clock.now(), "re-planned")
        assertEquals(1, carrier.tracks.size)

        service.applyUpdate(id(s), TrackingUpdate(ShipmentTarget.Id(id(s)), listOf(trackingEvent("DELIVERED", t + 100, "p3"))), TrackingSource.POLL)

        val after = service.trackShipment(id(s), pool)

        assertNull(after.getLong("nextPollAt"), "a terminal shipment is not polled again")

        carrier.onTrack = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        expect("SHIPPING_PROVIDER_ERROR", 502) { service.trackShipment(id(s), pool) }.let { assertEquals("GATEWAY_UNREACHABLE", it.getString("code")) }

        val manualShipment = ship(o, item to 1)

        expect("STATUS_QUERY_NOT_SUPPORTED", 400) { service.trackShipment(id(manualShipment), pool) }

        carrier.caps = ScriptedCarrier.caps(trackingPull = false)
        expect("STATUS_QUERY_NOT_SUPPORTED", 400) { service.trackShipment(id(s), pool) }
    }

    // ================================================================================================ reading

    @Test
    fun `GET order shipping lists the lines, the suggested parcel, the providers and the quote, the address only for OM or PAY`(): Unit = runBlocking {
        val m = method(carrierName = "Yurtici")
        val o = order(Line("Shirt", 3, weight = 400), Line("Cap", 1, weight = 100), methodId = m)

        sql("UPDATE `pano_market_order` SET `shippingQuote` = ? WHERE `id` = ?", JsonObject().put("providerId", carrier.id).put("expiresAt", w.clock.now() - 1).encode(), o.id)

        ship(o, o.items[0] to 1)

        val full = service.orderShipping(o.id, true, pool)

        assertEquals("DE", full.getJsonObject("address").getString("country"))
        assertEquals(m, full.getJsonObject("quote").getLong("methodId"))
        assertEquals("Standard", full.getJsonObject("quote").getString("methodName"))
        assertEquals(true, full.getBoolean("quoteExpired"))

        val lines = full.getJsonArray("lines").map { it as JsonObject }

        assertEquals(listOf(3, 1), lines.map { it.getInteger("quantity") })
        assertEquals(listOf(1, 0), lines.map { it.getInteger("shippedQuantity") })
        assertEquals(listOf(2, 1), lines.map { it.getInteger("shippable") })
        assertEquals(1, full.getJsonArray("suggestedParcels").size())
        assertEquals(900, full.getJsonArray("suggestedParcels").getJsonObject(0).getInteger("weightGrams"), "2 x 400 g + 100 g still to ship")

        val providers = full.getJsonArray("providers").map { it as JsonObject }

        assertEquals(setOf("manual", carrier.id), providers.map { it.getString("id") }.toSet())
        assertEquals(true, providers.single { it.getString("id") == carrier.id }.getBoolean("suggested"))
        assertEquals(false, providers.single { it.getString("id") == "manual" }.getBoolean("suggested"))
        assertEquals(20, providers.single { it.getString("id") == "manual" }.getJsonObject("capabilities").getInteger("maxParcels").coerceAtLeast(20))
        assertNotNull(full.getJsonObject("senderAddress"))

        val limited = service.orderShipping(o.id, false, pool)

        assertNull(limited.getJsonObject("address"))
        assertEquals(2, limited.getJsonArray("lines").size())

        expect("NOT_FOUND", 404) { service.orderShipping(98765, true, pool) }
    }

    @Test
    fun `order rates asks the provider live over the units still to ship and maps its failures`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(rateQuote = true)
        carrier.onQuote = { req ->
            QuoteResult(listOf(RateOption("EXP", "Express", Money(1500, "EUR")).also { it.rateRef = "ref-1"; it.carrierName = "Fake"; it.minDays = 1; it.maxDays = 2 }))
        }

        val o = order(Line("Shirt", 3), Line("Cap", 1))

        ship(o, o.items[0] to 1)

        val rates = service.orderRates(o.id, JsonObject().put("providerId", carrier.id).put("parcels", JsonArray().add(JsonObject().put("weightGrams", 700))), pool)

        assertEquals("EXP", rates.getJsonArray("rates").getJsonObject(0).getString("serviceCode"))
        assertEquals(15.0, rates.getJsonArray("rates").getJsonObject(0).getDouble("price"))
        assertEquals("ref-1", rates.getJsonArray("rates").getJsonObject(0).getString("rateRef"))
        assertEquals(listOf(2, 1), carrier.quotes.single().items.map { it.quantity }, "only the units that are still shippable")
        assertEquals("DE", carrier.quotes.single().to.country)

        carrier.onQuote = { throw ProviderException(ProviderErrorCode.RATE_LIMITED, "slow down") }
        expect("SHIPPING_PROVIDER_ERROR", 502) { service.orderRates(o.id, JsonObject().put("providerId", carrier.id).put("parcels", JsonArray().add(JsonObject().put("weightGrams", 700))), pool) }
            .let { assertEquals("RATE_LIMITED", it.getString("code")) }

        carrier.caps = ScriptedCarrier.caps(rateQuote = false)
        expect("SHIPPING_PROVIDER_ERROR", 502) { service.orderRates(o.id, JsonObject().put("providerId", carrier.id).put("parcels", JsonArray().add(JsonObject().put("weightGrams", 700))), pool) }
            .let { assertEquals("UNSUPPORTED", it.getString("code")) }

        service = newService(ratesTimeoutMs = 100)
        carrier.caps = ScriptedCarrier.caps(rateQuote = true)
        carrier.onQuote = { delay(1_500); QuoteResult(emptyList()) }
        expect("SHIPPING_PROVIDER_ERROR", 502) { service.orderRates(o.id, JsonObject().put("providerId", carrier.id).put("parcels", JsonArray().add(JsonObject().put("weightGrams", 700))), pool) }
            .let { assertEquals("TIMEOUT", it.getString("code")) }

        expect("PROVIDER_UNAVAILABLE", 409) { service.orderRates(o.id, JsonObject().put("providerId", "nosuch").put("parcels", JsonArray().add(JsonObject().put("weightGrams", 700))), pool) }
        expect("INVALID_SHIPMENT", 400) { service.orderRates(o.id, JsonObject().put("providerId", carrier.id).put("parcels", JsonArray()), pool) }
    }

    @Test
    fun `the shipment list filters by status, provider, stale and search and pages newest first`(): Unit = runBlocking {
        val a = order(Line("Shirt", 5))
        val b = order(Line("Cap", 5))
        val s1 = ship(a, a.items.single() to 1, manual = manual(number = "FIND-ME-1"))
        val s2 = ship(a, a.items.single() to 1, manual = manual(number = "OTHER-2"))
        val s3 = ship(b, b.items.single() to 1, manual = manual(number = "THIRD-3"))

        service.editShipment(id(s2), JsonObject().put("status", "DELIVERED"), 7, pool)
        sql("UPDATE `pano_market_shipment` SET `stale` = 1 WHERE `id` = ?", id(s3))

        fun ids(p: ShippingService.ShipmentPage) = p.shipments.map { it.getLong("id") }

        val all = service.listShipments(ShippingService.ShipmentFilter(), Paging.window(1, 10), pool)

        assertEquals(3, all.count)
        assertEquals(setOf(id(s1), id(s2), id(s3)), ids(all).toSet())
        assertEquals(listOf(id(s3), id(s2), id(s1)), ids(all), "newest first")

        assertEquals(listOf(id(s2)), ids(service.listShipments(ShippingService.ShipmentFilter(statuses = listOf(ShipmentStatus.DELIVERED)), Paging.window(1, 10), pool)))
        assertEquals(2, service.listShipments(ShippingService.ShipmentFilter(statuses = listOf(ShipmentStatus.IN_TRANSIT)), Paging.window(1, 10), pool).count)
        assertEquals(3, service.listShipments(ShippingService.ShipmentFilter(statuses = listOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED)), Paging.window(1, 10), pool).count)
        assertEquals(listOf(id(s3)), ids(service.listShipments(ShippingService.ShipmentFilter(stale = true), Paging.window(1, 10), pool)))
        assertEquals(3, service.listShipments(ShippingService.ShipmentFilter(providerId = "manual"), Paging.window(1, 10), pool).count)
        assertEquals(0, service.listShipments(ShippingService.ShipmentFilter(providerId = carrier.id), Paging.window(1, 10), pool).count)
        assertEquals(listOf(id(s1)), ids(service.listShipments(ShippingService.ShipmentFilter(search = "find-me"), Paging.window(1, 10), pool)))
        assertEquals(setOf(id(s1), id(s2)), ids(service.listShipments(ShippingService.ShipmentFilter(search = orderNow(a.id).publicId), Paging.window(1, 10), pool)).toSet())
        assertEquals(3, service.listShipments(ShippingService.ShipmentFilter(search = "Steve"), Paging.window(1, 10), pool).count)
        assertEquals(0, service.listShipments(ShippingService.ShipmentFilter(search = "100%"), Paging.window(1, 10), pool).count, "LIKE wildcards are escaped")

        val page2 = service.listShipments(ShippingService.ShipmentFilter(), Paging.window(2, 2), pool)

        assertEquals(3, page2.count)
        assertEquals(1, page2.shipments.size)
        assertFalse(page2.shipments.single().containsKey("toAddress"), "the list carries no address")
    }

    @Test
    fun `the shipment JSON has the allowed flags and the detail adds items, events and, with permission, the addresses`(): Unit = runBlocking {
        carrier.caps = ScriptedCarrier.caps(trackingPull = true, cancel = true)
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}") }

        val o = order(Line("Shirt", 3, weight = 300))
        val item = o.items.single()
        val manualShipment = ship(o, item to 1)
        val carrierShipment = carrierShip(o, item to 1)

        val m = service.shipmentJson(id(manualShipment), true, pool, detail = true)

        assertEquals(o.id, m.getLong("orderId"))
        assertEquals("Steve", m.getString("playerUsername"))
        assertEquals("IN_TRANSIT", m.getString("status"))
        assertEquals(true, m.getJsonObject("allowed").getBoolean("cancel"))
        assertEquals(true, m.getJsonObject("allowed").getBoolean("editStatus"))
        assertEquals(false, m.getJsonObject("allowed").getBoolean("retry"))
        assertEquals(false, m.getJsonObject("allowed").getBoolean("track"))
        assertEquals(false, m.getJsonObject("allowed").getBoolean("releaseItems"))
        assertEquals("Shirt", m.getJsonArray("items").getJsonObject(0).getString("name"))
        assertEquals(1, m.getJsonArray("items").getJsonObject(0).getInteger("quantity"))
        assertEquals("IN_TRANSIT", m.getJsonArray("events").getJsonObject(0).getString("status"))
        assertEquals("DE", m.getJsonObject("toAddress").getString("country"))
        assertNotNull(m.getJsonObject("fromAddress"))
        assertEquals("Manual shipping", m.getString("providerName"))

        val c = service.shipmentJson(id(carrierShipment), false, pool, detail = true)

        assertEquals(true, c.getJsonObject("allowed").getBoolean("track"))
        assertFalse(c.containsKey("toAddress"), "OV alone sees no address")
        assertFalse(c.containsKey("fromAddress"))
        assertFalse(c.containsKey("providerData"))
        assertFalse(c.containsKey("labelFile"))

        expect("NOT_FOUND", 404) { service.shipmentJson(98765, true, pool) }
    }

    // ================================================================================================ misc

    @Test
    fun `with sendEmailAfterPurchase off the shipment mail is not queued but the webhook still is, an order without e-mail queues no mail`(): Unit = runBlocking {
        webhooks.endpoint("https://hooks.example.com/market")
        w.configure { cfg(mail = false) }

        val a = order(Line("Shirt", 1))

        ship(a, a.items.single() to 1)

        assertEquals(0, mails().size)
        assertEquals(1, webhooks.rows().size)

        w.configure { cfg(mail = true) }

        val b = order(Line("Cap", 1), email = null)
        val s = ship(b, b.items.single() to 1)

        assertEquals(0, mails().size)
        assertNotNull(shipmentNow(id(s)).trackingMailSentAt)
        assertEquals(2, webhooks.rows().size)
    }

    @Test
    fun `a test-mode order or carrier makes a test-mode shipment`(): Unit = runBlocking {
        val a = order(Line("Shirt", 1), testMode = true)
        val b = order(Line("Cap", 1))

        assertTrue(shipmentNow(id(ship(a, a.items.single() to 1))).testMode)
        assertFalse(shipmentNow(id(ship(b, b.items.single() to 1))).testMode)

        w.configure { cfg(test = true) }

        val c = order(Line("Mug", 1))

        assertTrue(shipmentNow(id(ship(c, c.items.single() to 1))).testMode)
    }

    @Test
    fun `the unwired service refuses fulfilment and a missing order is NOT_FOUND`(): Unit = runBlocking {
        val bare = ShippingService(
            clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers, currencyRates = w.currencyRates,
            addresses = w.addresses, lookup = lookup, cipher = SecretCipher(ByteArray(32)), contexts = ShippingContexts { p, s, t -> TestContexts.shipping(p.id, s, vertx, t) }
        )
        val o = order(Line("Shirt", 1))
        var refused = false

        try {
            bare.createShipment(o.id, body(o.items.single() to 1), 7, pool)
        } catch (e: IllegalStateException) {
            refused = true
        }

        assertTrue(refused)
        assertEquals(0, count("market_shipment"))

        var notFound = false

        try {
            service.createShipment(424242, body(1L to 1), 7, pool)
        } catch (e: NotFound) {
            notFound = true
        }

        assertTrue(notFound)
    }

    // ================================================================================================ SH-01: checkout to delivery

    @Test
    fun `SH-01 a physical order bought through the real checkout is COMPLETED and PENDING, shipped by hand in two parcels and delivered`(): Unit = runBlocking {
        val h = CheckoutHarness(w, vertx)

        h.shipper = service

        val shirt = w.fixtures.product("sh01-shirt", price = 0, columns = mapOf("physical" to true, "weightGrams" to 400))
        val zone = w.fixtures.shippingZone("Everywhere", "[\"*\"]", 0)
        val now = w.clock.now()
        val methodId = w.shippingMethods.add(MarketShippingMethod(name = "Standard", freeShippingThreshold = 0, trackingUrlTemplate = "https://t.example.com/{tracking}", createdAt = now, updatedAt = now), pool)

        w.shippingRates.add(
            com.panomc.plugins.market.db.model.MarketShippingRate(methodId = methodId, zoneId = zone.id, basis = com.panomc.plugins.market.db.model.ShippingRateBasis.FLAT, price = 500, createdAt = now, updatedAt = now),
            pool
        )

        val address = mapOf("firstName" to "Hans", "lastName" to "Meier", "phone" to "+4915112345678", "country" to "DE", "city" to "Berlin", "line1" to "Strasse 1", "postalCode" to "10115")
        val result = h.checkout(h.body("items" to listOf(h.line(shirt, 2)), "paymentMethodId" to "free", "shippingAddress" to address, "shippingMethodId" to methodId))
        val order = w.orders.getByPublicId(result.order.getString("publicId"), pool)!!

        assertEquals(ShippingStatus.PENDING, order.shippingStatus)
        assertTrue(order.requiresShipping)
        assertEquals(0, order.totalPrice, "free shipping over the 0.00 threshold")

        val item = w.orderItems.getByOrderIds(listOf(order.id), pool).single()
        val ref = Ref(order.id, listOf(item.id))

        // not paid yet (the pay step belongs to the payment slices): nothing can be shipped
        assertEquals(OrderStatus.PENDING, order.status)
        expect("ORDER_NOT_SHIPPABLE", 409) { ship(ref, item.id to 1) }.let { assertEquals("STATUS", it.getString("reason")) }

        // what a payment event does (the payment slices): the attempt succeeds, the order is paid and its reservation committed
        val attempt = w.payments.getByOrderId(order.id, pool).single()
        val paidAt = w.clock.now()

        sql("UPDATE `pano_market_payment` SET `status` = 'SUCCEEDED', `paidAmount` = ?, `paidAt` = ?, `closedAt` = ? WHERE `id` = ?", order.gatewayAmount, paidAt, paidAt, attempt.id)
        sql(
            "UPDATE `pano_market_order` SET `status` = 'COMPLETED', `reservationState` = 'COMMITTED', `paidAt` = ?, `paymentId` = ?, `paidAmount` = ?, `updatedAt` = ? WHERE `id` = ?",
            paidAt, attempt.id, order.gatewayAmount, paidAt + 1, order.id
        )
        sql("UPDATE `pano_market_product` SET `soldCount` = `soldCount` + 2 WHERE `id` = ?", shirt.id)

        assertEquals(2, shippableOf(ref, item.id))

        val first = ship(ref, item.id to 1, manual = manual(carrierName = "DHL", number = "SH01-A"))

        assertEquals("https://t.example.com/SH01-A", first.getString("trackingUrl"))
        assertEquals(ShippingStatus.PARTIAL, orderNow(order.id).shippingStatus)

        val second = ship(ref, item.id to 1, manual = manual(carrierName = "DHL", number = "SH01-B"))

        assertEquals(ShippingStatus.SHIPPED, orderNow(order.id).shippingStatus)
        assertEquals(2, itemNow(item.id).shippedQuantity)

        w.clock.advance(1_000)
        service.editShipment(id(first), JsonObject().put("status", "DELIVERED"), 7, pool)
        service.editShipment(id(second), JsonObject().put("status", "DELIVERED"), 7, pool)

        assertEquals(ShippingStatus.DELIVERED, orderNow(order.id).shippingStatus)
        assertEquals(OrderStatus.COMPLETED, orderNow(order.id).status, "shipping never moves the order status")
        assertEquals(2, mails("SHIPMENT_SHIPPED").size)
        assertEquals(2, mails("SHIPMENT_DELIVERED").size)
        allocated(ref)
    }

    // ================================================================================================ the scripted carrier

    /** A carrier whose answers a test scripts; every call lands in a log. */
    class ScriptedCarrier(override val id: String = "scripted") : ShippingProvider {
        override val descriptor = ProviderDescriptor(LocalizedText.of("Scripted carrier"), LocalizedText.of("Scriptable test carrier"), "truck")

        @Volatile var caps: ShippingCapabilities = caps()
        @Volatile var onCreate: suspend (CreateShipmentRequest) -> CreateShipmentResult = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}") }
        @Volatile var onCancel: suspend (ShipmentView) -> CancelShipmentResult = { CancelShipmentResult.cancelled() }
        @Volatile var onTrack: suspend (TrackRequest) -> List<TrackingUpdate> = { emptyList() }
        @Volatile var onQuote: suspend (QuoteRequest) -> QuoteResult = { QuoteResult(emptyList()) }

        val creates = CopyOnWriteArrayList<CreateShipmentRequest>()
        val cancels = CopyOnWriteArrayList<ShipmentView>()
        val tracks = CopyOnWriteArrayList<TrackRequest>()
        val quotes = CopyOnWriteArrayList<QuoteRequest>()

        override fun settingsSchema(): SettingsSchema = settingsSchema {
            SenderKeys.ADDRESS.forEach { key -> text(key) { label = LocalizedText.of(key) } }
            SenderKeys.PARCEL.forEach { key -> number(key) { label = LocalizedText.of(key) } }
        }

        override fun capabilities(settings: ProviderSettings): ShippingCapabilities = caps

        override suspend fun createShipment(ctx: ShippingContext, request: CreateShipmentRequest): CreateShipmentResult {
            creates += request

            return onCreate(request)
        }

        override suspend fun cancelShipment(ctx: ShippingContext, shipment: ShipmentView): CancelShipmentResult {
            cancels += shipment

            return onCancel(shipment)
        }

        override suspend fun track(ctx: ShippingContext, request: TrackRequest): List<TrackingUpdate> {
            tracks += request

            return onTrack(request)
        }

        override suspend fun quote(ctx: ShippingContext, request: QuoteRequest): QuoteResult {
            quotes += request

            return onQuote(request)
        }

        companion object {
            fun caps(
                createShipment: Boolean = true, cancel: Boolean = false, trackingPull: Boolean = false, externalTracking: Boolean = false, maxParcels: Int = 5,
                requiresDimensions: Boolean = false, requiresCustomsData: Boolean = false, rateQuote: Boolean = false, labelFormats: Set<LabelFormat> = emptySet()
            ) = ShippingCapabilities().also {
                it.createShipment = createShipment
                it.cancel = cancel
                it.trackingPull = trackingPull
                it.externalTracking = externalTracking
                it.maxParcels = maxParcels
                it.requiresDimensions = requiresDimensions
                it.requiresCustomsData = requiresCustomsData
                it.rateQuote = rateQuote
                it.labelFormats = labelFormats
            }
        }
    }
}
