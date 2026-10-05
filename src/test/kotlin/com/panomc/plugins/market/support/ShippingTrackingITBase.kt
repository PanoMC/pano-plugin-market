package com.panomc.plugins.market.support

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShippingCarrier
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.FulfilmentDeps
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.service.ShippingContexts
import com.panomc.plugins.market.service.ShippingService
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.SenderKeys
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingInboundResult
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.TrackRequest
import com.panomc.plugins.market.spi.shipping.TrackingEvent
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import com.panomc.plugins.market.spi.shipping.ShipmentStatus as SpiStatus

/**
 * What the tracking ITs (MK-134: [com.panomc.plugins.market.job.ShipmentTrackingJobIT], [com.panomc.plugins.market.routes.api.shipping.ShippingWebhookIT])
 * share: a real MariaDB, the fulfilment half of `ShippingService` over it, a scripted carrier registered under the id `tracker`, orders with physical
 * lines and carrier shipments created through the service (so `nextPollAt`, `carrierReference` and the order bookkeeping are exactly what production writes).
 */
abstract class ShippingTrackingITBase : MarketDaoITBase() {
    protected lateinit var w: TestWiring
    protected lateinit var carrier: TrackingCarrier
    protected lateinit var lookup: StaticProviderLookup
    protected lateinit var service: ShippingService
    protected lateinit var webhooks: WebhookHarness
    protected lateinit var labels: Path
    protected val vertx: Vertx = Vertx.vertx()

    override val poolSize: Int = 24

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshTrackingState() {
        runBlocking { resetState() }
        w = TestWiring(pool, ids = SeqIds())
        carrier = TrackingCarrier()
        lookup = StaticProviderLookup(shipping = listOf(ManualShippingProvider(), carrier))
        webhooks = WebhookHarness(w, vertx)
        labels = Files.createTempDirectory("market-labels")
        service = newService()
        runBlocking { carrierRow() }
    }

    protected fun newService() = ShippingService(
        clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers,
        currencyRates = w.currencyRates, addresses = w.addresses, lookup = lookup, cipher = SecretCipher(ByteArray(32) { (it + 5).toByte() }),
        contexts = ShippingContexts { provider, settings, testMode -> TestContexts.shipping(provider.id, settings, vertx, testMode) },
        fulfilment = FulfilmentDeps(
            db = w.db, locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts), orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            shipments = w.shipments, shipmentItems = w.shipmentItems, shipmentEvents = w.shipmentEvents,
            mail = MailOutboxService({ w.config }, w.clock, w.mailOutbox, w.orderEvents), webhooks = webhooks.service, ids = w.ids, config = { w.config },
            labelsDir = labels, siteUrl = { "https://shop.example" }
        )
    )

    // ------------------------------------------------------------------------------------------------ fixtures

    protected val address: String = JsonObject()
        .put("firstName", "Hans").put("lastName", "Meier").put("phone", "+4915112345678").put("country", "DE").put("city", "Berlin")
        .put("line1", "Strasse 1").put("postalCode", "10115").put("email", "steve@example.com").encode()

    protected class Ref(val id: Long, val items: List<Long>)

    /** A paid, completed order with one physical line per `(name, quantity)`. */
    protected suspend fun order(vararg lines: Pair<String, Int>, email: String? = "steve@example.com"): Ref {
        val now = w.clock.now()
        val total = lines.sumOf { 2000L * it.second }
        val id = w.orders.add(
            MarketOrder(
                userId = 5, playerUsername = "Steve", recipientUsername = "Steve", recipientUserId = 5, email = email, locale = "en-US",
                publicId = w.ids.publicId(), status = OrderStatus.COMPLETED, currency = "EUR", subtotal = total, totalPrice = total, gatewayAmount = total, paidAmount = total,
                paidAt = now, createdAt = now, updatedAt = now, buyerKey = "u:5", recipientKey = "u:5", baseCurrency = "EUR", paymentMethodId = "manual",
                reservationState = ReservationState.COMMITTED, requiresShipping = true, shippingStatus = ShippingStatus.PENDING, shippingAddress = address
            ),
            pool
        )
        val items = lines.map { (name, quantity) ->
            w.orderItems.add(
                MarketOrderItem(
                    orderId = id, productName = name, quantity = quantity, unitPrice = 2000, lineTotal = 2000L * quantity, kind = OrderItemKind.PRODUCT, physical = true,
                    sku = "SKU-$name", snapshot = JsonObject().put("kind", "PHYSICAL").put("physical", true).put("weightGrams", 250).encode(), createdAt = now, updatedAt = now
                ),
                pool
            )
        }

        return Ref(id, items)
    }

    protected suspend fun carrierRow(enabled: Boolean = true, providerId: String = carrier.id, token: String = TOKEN): MarketShippingCarrier {
        val now = w.clock.now()
        val settings = JsonObject().put("senderCountry", "TR").put("senderCity", "Istanbul").put("senderLine1", "Depo 1").put("senderPostalCode", "34000").encode()

        w.shippingCarriers.add(MarketShippingCarrier(providerId = providerId, enabled = enabled, settings = settings, webhookToken = token, createdAt = now, updatedAt = now), pool)

        return w.shippingCarriers.getByProviderId(providerId, pool)!!
    }

    /** One carrier shipment of [quantity] units of the line [itemId]; the carrier answers `CAR-<id>` (and the tracking number [number] when given). */
    protected suspend fun shipCarrier(order: Ref, itemId: Long = order.items.first(), quantity: Int = 1, provider: ShippingProvider = carrier): MarketShipment {
        val body = JsonObject().put("providerId", provider.id).put("parcels", JsonArray().add(JsonObject().put("weightGrams", 500)))
            .put("items", JsonArray().add(JsonObject().put("orderItemId", itemId).put("quantity", quantity)))
        val shipment = service.createShipment(order.id, body, 7, pool)

        return w.shipments.getById(shipment.getLong("id"), pool)!!
    }

    protected suspend fun shipmentNow(id: Long): MarketShipment = w.shipments.getById(id, pool)!!

    protected suspend fun orderNow(id: Long): MarketOrder = w.orders.getById(id, pool)!!

    protected fun event(status: SpiStatus, at: Long, id: String? = null) = TrackingEvent(status, at).also { it.eventId = id }

    protected suspend fun shipmentEvents(shipmentId: Long) =
        sql("SELECT `status`, `source`, `dedupeKey` FROM `pano_market_shipment_event` WHERE `shipmentId` = ? ORDER BY `id`", shipmentId)
            .map { Triple(it.getString("status"), it.getString("source"), it.getString("dedupeKey")) }

    protected suspend fun mails(kind: String) =
        sql("SELECT `refId` FROM `pano_market_mail_outbox` WHERE `kind` = ? AND `refType` = 'SHIPMENT' ORDER BY `id`", kind).map { it.getLong("refId") }

    companion object {
        const val TOKEN = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"
    }

    /**
     * A carrier that can track and that is called by its own webhook. [onTrack] answers a poll, [onInbound] a webhook; every call is recorded
     * ([tracks], [inbound], and the context a webhook call saw in [contexts]). `create` hands out `CAR-<shipmentId>`.
     */
    class TrackingCarrier(override val id: String = "tracker") : ShippingProvider {
        override val descriptor = ProviderDescriptor(LocalizedText.of("Tracking carrier"), LocalizedText.of("Scriptable tracking carrier"), "truck")

        @Volatile var caps: ShippingCapabilities = caps()
        @Volatile var onCreate: suspend (CreateShipmentRequest) -> CreateShipmentResult = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}") }
        @Volatile var onTrack: suspend (TrackRequest) -> List<TrackingUpdate> = { emptyList() }
        @Volatile var onInbound: suspend (ShippingContext, InboundRequest) -> ShippingInboundResult = { _, _ -> ShippingInboundResult.ignored(com.panomc.plugins.market.spi.common.HttpReply.empty(200)) }

        val tracks = CopyOnWriteArrayList<TrackRequest>()
        val inbound = CopyOnWriteArrayList<InboundRequest>()
        val contexts = CopyOnWriteArrayList<ShippingContext>()

        override fun settingsSchema(): SettingsSchema = settingsSchema {
            SenderKeys.ADDRESS.forEach { key -> text(key) { label = LocalizedText.of(key) } }
            SenderKeys.PARCEL.forEach { key -> number(key) { label = LocalizedText.of(key) } }
        }

        override fun capabilities(settings: ProviderSettings): ShippingCapabilities = caps

        override suspend fun createShipment(ctx: ShippingContext, request: CreateShipmentRequest): CreateShipmentResult = onCreate(request)

        override suspend fun track(ctx: ShippingContext, request: TrackRequest): List<TrackingUpdate> {
            tracks += request

            return onTrack(request)
        }

        override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
            inbound += request
            contexts += ctx

            return onInbound(ctx, request)
        }

        companion object {
            fun caps(trackingPull: Boolean = true, trackBatchSize: Int = 10, externalTracking: Boolean = false) = ShippingCapabilities().also {
                it.createShipment = true
                it.trackingPull = trackingPull
                it.trackBatchSize = trackBatchSize
                it.externalTracking = externalTracking
                it.trackingPush = true
                it.maxParcels = 5
            }
        }
    }

    /** `TrackingUpdate` of a carrier reference, the shape of every poll answer of these tests. */
    protected fun updateFor(row: MarketShipment, vararg events: TrackingEvent) = TrackingUpdate(ShipmentTarget.CarrierReference(row.carrierReference!!), events.toList())
}
