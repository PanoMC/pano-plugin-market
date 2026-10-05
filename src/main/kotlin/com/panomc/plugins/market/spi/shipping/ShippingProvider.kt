package com.panomc.plugins.market.spi.shipping

import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderContext
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.SettingsValidation
import io.vertx.core.json.JsonObject

/** What a shipping provider sees on top of [ProviderContext] (03 section 5). */
interface ShippingContext : ProviderContext {
    val urls: ShippingUrls
    val shipments: ShipmentLookup
}

interface ShippingUrls {
    /** `{base}/api/market/shipping/{providerId}/webhook/{installToken}[/{channel}]`. */
    fun webhook(channel: String = MarketSpi.DEFAULT_CHANNEL): String
}

/** Read-only views of this provider's shipments only. */
interface ShipmentLookup {
    suspend fun byMerchantReference(reference: String): ShipmentView?

    suspend fun byCarrierReference(reference: String): ShipmentView?

    /** Also matches per-piece numbers (`market_shipment.packages`). */
    suspend fun byTrackingNumber(trackingNumber: String): ShipmentView?
}

/**
 * A carrier or aggregator. Methods with a body are JVM default methods (market compiles with `-jvm-default=enable`),
 * so market can add methods without breaking plugins built earlier (02 section 9).
 */
interface ShippingProvider {
    /** `[a-z0-9-]{2,32}`, stable. Equals `market_shipping_carrier.providerId`. */
    val id: String

    val descriptor: ProviderDescriptor

    /** Credentials, sender address, default parcel, label format. */
    fun settingsSchema(): SettingsSchema

    /** Pure: the same settings give the same answer, no I/O. */
    fun capabilities(settings: ProviderSettings): ShippingCapabilities

    suspend fun validateSettings(ctx: ShippingContext, settings: ProviderSettings): SettingsValidation = SettingsValidation.ok()

    suspend fun onSettingsSaved(ctx: ShippingContext, previous: ProviderSettings?): ActionResult = ActionResult.none()

    suspend fun runAction(ctx: ShippingContext, actionId: String, input: JsonObject): ActionResult =
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "unknown action $actionId")

    /** Carrier services this configuration can ship with; binds a shipping method to a service code. */
    suspend fun listServices(ctx: ShippingContext): List<ShippingService> = emptyList()

    suspend fun quote(ctx: ShippingContext, request: QuoteRequest): QuoteResult = QuoteResult.unsupported()

    /** Maps free-text city / district to the carrier's own ids, or validates an address. */
    suspend fun resolveAddress(ctx: ShippingContext, address: Address): AddressResolution = AddressResolution.unsupported()

    suspend fun createShipment(ctx: ShippingContext, request: CreateShipmentRequest): CreateShipmentResult =
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "createShipment")

    /** Labels that were not returned by [createShipment] (carriers that issue them asynchronously). */
    suspend fun fetchLabel(ctx: ShippingContext, shipment: ShipmentView): LabelResult = LabelResult.none()

    suspend fun cancelShipment(ctx: ShippingContext, shipment: ShipmentView): CancelShipmentResult = CancelShipmentResult.unsupported()

    suspend fun track(ctx: ShippingContext, request: TrackRequest): List<TrackingUpdate> = emptyList()

    suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult =
        ShippingInboundResult.rejected(HttpReply.empty(404), "no webhook")

    /** Prepaid aggregators: remaining balance, shown in the panel before a label is bought. */
    suspend fun balance(ctx: ShippingContext): Money? = null
}
