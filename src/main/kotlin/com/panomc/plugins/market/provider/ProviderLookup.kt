package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.shipping.ShippingProvider

enum class ProviderKind { PAYMENT, SHIPPING }

enum class ProviderOrigin { BUILT_IN, PLUGIN }

/** A provider that passed every registry check and can be used right now. [pluginId] is `null` for a built-in. */
class ResolvedProvider<out P : Any>(
    val id: String,
    val provider: P,
    val origin: ProviderOrigin,
    val pluginId: String?,
    /** The SPI version the plugin was built against; market's own version for a built-in. */
    val spiVersion: Int
)

/**
 * What the registry knows about a provider id right now (02 section 2 / 11). `MISSING` is "no provider is
 * registered under this id": plugin not installed, stopped or failed its license check; market maps it to the method
 * state `UNAVAILABLE` and never throws because of it.
 */
enum class ProviderAvailability {
    /** Registered, compatible and not shadowed. */
    AVAILABLE,

    /** The plugin was built for an SPI version outside `MIN_SUPPORTED..VERSION` ("INCOMPATIBLE (built for SPI n)"). */
    INCOMPATIBLE,

    /** Another provider holds the id: the built-in wins, then the first plugin by plugin id. Reported in the panel. */
    SHADOWED,

    /** The id is not `[a-z0-9-]{2,32}`, or the plugin threw while the registry read it. */
    INVALID,

    MISSING
}

class ProviderState(
    val availability: ProviderAvailability,
    /** Set for [ProviderAvailability.INCOMPATIBLE]: the SPI version the plugin declares. */
    val spiVersion: Int? = null,
    val pluginId: String? = null,
    /** Short English text for the panel / logs. */
    val detail: String? = null
) {
    val usable: Boolean get() = availability == ProviderAvailability.AVAILABLE

    override fun toString(): String = "ProviderState($availability${pluginId?.let { ", plugin=$it" } ?: ""}${detail?.let { ", $it" } ?: ""})"
}

/** One row of the panel's provider list: [id] is `null` when the plugin could not even name its providers. */
class ProviderListing(
    val kind: ProviderKind,
    val id: String?,
    val state: ProviderState,
    val resolved: ResolvedProvider<Any>?
)

/**
 * The seam between market code and the plugin event bus (17 section 4 S8). Production: [ProviderRegistry]; tests:
 * `StaticProviderLookup(listOf(FakePaymentProvider()))`. Every call looks the world up afresh (plugins may start and
 * stop at any time) and none of them throws because a provider is missing.
 */
interface ProviderLookup {
    /** Usable payment providers: built-ins first, then plugins by plugin id. */
    fun allPayment(): List<ResolvedProvider<PaymentProvider>>

    fun allShipping(): List<ResolvedProvider<ShippingProvider>>

    fun payment(id: String): ResolvedProvider<PaymentProvider>? = allPayment().firstOrNull { it.id == id }

    fun shipping(id: String): ResolvedProvider<ShippingProvider>? = allShipping().firstOrNull { it.id == id }

    /** Availability of [id] (see [ProviderAvailability]). */
    fun state(kind: ProviderKind, id: String): ProviderState

    /** Everything the registry found, usable or not (panel list). */
    fun listing(kind: ProviderKind): List<ProviderListing>
}
