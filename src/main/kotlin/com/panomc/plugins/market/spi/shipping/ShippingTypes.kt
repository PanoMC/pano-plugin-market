package com.panomc.plugins.market.spi.shipping

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.Money
import io.vertx.core.json.JsonObject

/** A carrier service a configuration can ship with (a shipping method binds to its [code]). */
class ShippingService(val code: String, val name: String) {
    var carrierName: String? = null
    var international: Boolean = false
}

/** One parcel; dimensions are optional unless `ShippingCapabilities.requiresDimensions`. */
class Parcel(val weightGrams: Int, val lengthMm: Int?, val widthMm: Int?, val heightMm: Int?)

/** One order line as the carrier needs it (customs data included). */
class ShipItem(
    val orderItemId: Long?,
    val name: String,
    val sku: String?,
    val quantity: Int,
    val unitWeightGrams: Int,
    val unitValue: Money,
    val hsCode: String?,
    val originCountry: String?
)

class QuoteRequest(
    val from: Address,
    val to: Address,
    val parcels: List<Parcel>,
    val items: List<ShipItem>,
    val orderValue: Money,
    /** Currency the buyer pays in (a hint; the carrier answers in its own). */
    val currency: String,
    /** Null = all services. */
    val serviceCode: String?
)

/** One purchasable rate. */
class RateOption(val serviceCode: String, val serviceName: String, val price: Money) {
    var carrierName: String? = null

    /** Opaque id to buy exactly this rate (Geliver offer, EasyPost rate). */
    var rateRef: String? = null
    var minDays: Int? = null
    var maxDays: Int? = null
    var expiresAt: Long? = null
    var priceIncludesTax: Boolean = true
}

class QuoteResult(val rates: List<RateOption>) {
    /** `serviceCode` to reason. */
    var unavailable: Map<String, String> = emptyMap()

    /** True only for [unsupported]: the provider has no rate API (an empty result means "no rates for this parcel"). */
    var supported: Boolean = true
        internal set

    companion object {
        fun unsupported(): QuoteResult = QuoteResult(emptyList()).also { it.supported = false }
    }
}

class CreateShipmentRequest(
    val shipmentId: Long,
    /** Our reference; also the idempotency key. */
    val merchantReference: String,
    val orderPublicId: String,
    val from: Address,
    val to: Address,
    val parcels: List<Parcel>,
    val items: List<ShipItem>,
    val serviceCode: String?,
    /** Providers use whichever purchase shape they have. */
    val rateRef: String?,
    val preferredLabelFormat: LabelFormat?,
    val declaredValue: Money,
    val note: String?,
    /** Retry: what the last [CreateShipmentResult.Failed] returned. */
    val previousCarrierReference: String?,
    val previousProviderData: JsonObject?
)

sealed class CreateShipmentResult {
    class Created(val carrierReference: String) : CreateShipmentResult() {
        /** May be assigned later (found by track / webhook). */
        var trackingNumber: String? = null
        var trackingUrl: String? = null
        var carrierName: String? = null
        var labels: List<LabelDocument> = emptyList()

        /** Customs forms. */
        var documents: List<LabelDocument> = emptyList()
        var cost: Money? = null

        /** [ShipmentStatus.LABEL_READY] when labels are attached. */
        var status: ShipmentStatus = ShipmentStatus.CREATED
        var providerData: JsonObject? = null

        /** Multi-piece shipments: one number and label per parcel; [trackingNumber] stays the master number. */
        var pieces: List<ShipmentPiece> = emptyList()
    }

    class Failed(val code: ShipmentErrorCode, val message: String?) : CreateShipmentResult() {
        /** Two-step carriers (Geliver, BasitKargo): the object created before the paid step failed. */
        var carrierReference: String? = null
        var providerData: JsonObject? = null
    }
}

class ShipmentPiece(val trackingNumber: String) {
    var label: LabelDocument? = null
}

/** New values only at the end; both sides treat an unknown value as `OTHER`. */
enum class ShipmentErrorCode { INSUFFICIENT_BALANCE, ADDRESS_INVALID, SERVICE_UNAVAILABLE, RATE_EXPIRED, WEIGHT_LIMIT, REJECTED, OTHER }

class LabelDocument(val format: LabelFormat, val bytes: ByteArray) {
    /** `LABEL`, `CUSTOMS` or `INVOICE`. */
    var kind: String = KIND_LABEL
        set(value) {
            require(value in KINDS) { "Unknown document kind '$value'" }
            field = value
        }

    companion object {
        const val KIND_LABEL = "LABEL"
        const val KIND_CUSTOMS = "CUSTOMS"
        const val KIND_INVOICE = "INVOICE"
        val KINDS = setOf(KIND_LABEL, KIND_CUSTOMS, KIND_INVOICE)
    }
}

/** Answer of `fetchLabel`. Build it with [of], [notReady] or [none]. */
class LabelResult private constructor(val documents: List<LabelDocument>, val notReady: Boolean) {
    companion object {
        fun of(documents: List<LabelDocument>): LabelResult {
            require(documents.isNotEmpty()) { "Use notReady() or none() for an answer without documents" }
            return LabelResult(documents, false)
        }

        /** The carrier is still preparing the label: market asks again later. */
        fun notReady(): LabelResult = LabelResult(emptyList(), true)

        /** This provider has no label to give (market renders a generic one). */
        fun none(): LabelResult = LabelResult(emptyList(), false)
    }
}

/** Answer of `cancelShipment`. Build it with [cancelled], [refused] or [unsupported]. */
class CancelShipmentResult private constructor(val cancelled: Boolean, val supported: Boolean, val message: String?) {
    companion object {
        fun cancelled(): CancelShipmentResult = CancelShipmentResult(true, true, null)

        fun refused(message: String): CancelShipmentResult = CancelShipmentResult(false, true, message)

        fun unsupported(): CancelShipmentResult = CancelShipmentResult(false, false, null)
    }
}

/** A shipment as market stores it, read-only for the provider. */
class ShipmentView(
    val id: Long,
    val merchantReference: String,
    val carrierReference: String?,
    val trackingNumber: String?,
    val status: ShipmentStatus,
    val serviceCode: String?,
    val to: Address,
    val providerData: JsonObject?,
    val testMode: Boolean,
    val createdAt: Long
)

/** `shipments.size <= capabilities.trackBatchSize`. */
class TrackRequest(val shipments: List<ShipmentView>)

class TrackingEvent(val status: ShipmentStatus, val occurredAt: Long) {
    var rawStatus: String? = null
    var description: String? = null
    var location: String? = null

    /** Carrier event id; becomes the de-duplication key. */
    var eventId: String? = null
}

class TrackingUpdate(val target: ShipmentTarget, val events: List<TrackingEvent>) {
    var trackingNumber: String? = null
    var trackingUrl: String? = null
    var estimatedDelivery: Long? = null
    var providerData: JsonObject? = null
}

/** Which shipment an update is about. */
sealed class ShipmentTarget {
    class Id(val shipmentId: Long) : ShipmentTarget()

    class MerchantReference(val reference: String) : ShipmentTarget()

    class CarrierReference(val reference: String) : ShipmentTarget()

    class TrackingNumber(val trackingNumber: String) : ShipmentTarget()
}

/** Answer of a shipping `handleInbound`. Build it with [accepted], [ignored] or [rejected]. */
class ShippingInboundResult(val reply: HttpReply) {
    /** True when the provider verified the request (signature, or a re-fetch of the state for unsigned webhooks). */
    var verified: Boolean = false
    var eventKey: String? = null
    var updates: List<TrackingUpdate> = emptyList()
    var rejectReason: String? = null

    companion object {
        fun accepted(reply: HttpReply, updates: List<TrackingUpdate>, eventKey: String? = null): ShippingInboundResult {
            require(eventKey == null || eventKey.isNotBlank()) { "eventKey must be null or a non-blank delivery id" }
            return ShippingInboundResult(reply).also {
                it.verified = true
                it.updates = updates
                it.eventKey = eventKey
            }
        }

        fun ignored(reply: HttpReply): ShippingInboundResult = ShippingInboundResult(reply).also { it.verified = true }

        fun rejected(reply: HttpReply, reason: String): ShippingInboundResult =
            ShippingInboundResult(reply).also {
                it.verified = false
                it.rejectReason = reason
            }
    }
}

/** Answer of `resolveAddress`. Build it with [ok], [invalid] or [unsupported]. */
class AddressResolution private constructor(val supported: Boolean, val valid: Boolean, val normalized: Address?, val message: String?) {
    companion object {
        fun ok(normalized: Address): AddressResolution = AddressResolution(true, true, normalized, null)

        fun invalid(message: String): AddressResolution = AddressResolution(true, false, null, message)

        fun unsupported(): AddressResolution = AddressResolution(false, false, null, null)
    }
}
