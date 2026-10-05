package com.panomc.plugins.market.spi.shipping

import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup

/**
 * What a shipping provider can do for one configuration (03 section 5). Every property is a `var` with the default
 * of the spec; a new optional capability becomes a new `var` (02 section 9 rule 2).
 */
class ShippingCapabilities {
    /** Can answer [ShippingProvider.quote]. */
    var rateQuote: Boolean = false

    /** False = rates / tracking only (FedEx before label certification). */
    var createShipment: Boolean = false

    /** Empty = no label API: market renders a generic label. */
    var labelFormats: Set<LabelFormat> = emptySet()

    var cancel: Boolean = false

    var trackingPull: Boolean = false

    /** Largest `TrackRequest.shipments` size this provider accepts. */
    var trackBatchSize: Int = 1

    var trackingPush: Boolean = false

    /** False = the webhook body is not trusted: re-fetch before returning updates (convention + contract test). */
    var webhookSigned: Boolean = false

    var webhookSetup: WebhookSetup = WebhookSetup.MANUAL_URL

    /** Can track a number that was not created through this provider. */
    var externalTracking: Boolean = false

    var addressResolve: Boolean = false

    var prepaidBalance: Boolean = false

    /** Reserved, unused in v1 (returns are status-only, 03 section 1). */
    var returns: Boolean = false

    /** Upper-case ISO codes; null = any. */
    var originCountries: Set<String>? = null

    /** Upper-case ISO codes; null = any. */
    var destinationCountries: Set<String>? = null

    var requiresDimensions: Boolean = false

    /** International: hsCode, originCountry, declared value. */
    var requiresCustomsData: Boolean = false

    /** DISTRICT, POSTAL_CODE, STATE, PHONE, EMAIL, IDENTITY_NUMBER ... */
    var requiredAddressFields: Set<AddressField> = emptySet()

    var maxParcels: Int = 1

    var quoteCacheSeconds: Int = 600

    var testMode: TestModeSupport = TestModeSupport.FLAG
}

/** New values only at the end; both sides treat an unknown value as "other". */
enum class AddressField { DISTRICT, NEIGHBORHOOD, POSTAL_CODE, STATE, PHONE, EMAIL, IDENTITY_NUMBER }

/** New values only at the end. */
enum class LabelFormat { PDF, PNG, GIF, ZPL, EPL, SVG, HTML }

/** New values only at the end; both sides treat an unknown value as "in transit". */
enum class ShipmentStatus { CREATED, LABEL_READY, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, RETURNING, RETURNED, CANCELLED, LOST }
