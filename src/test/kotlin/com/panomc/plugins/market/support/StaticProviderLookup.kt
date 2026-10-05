package com.panomc.plugins.market.support

import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.provider.ProviderListing
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.ProviderOrigin
import com.panomc.plugins.market.provider.ProviderState
import com.panomc.plugins.market.provider.ResolvedProvider
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The S8 seam for tests (17 section 4): a fixed, mutable provider list instead of the plugin registry.
 * `StaticProviderLookup(listOf(FakePaymentProvider()))`. Every provider counts as a plugin-less built-in and is always
 * AVAILABLE; an id that is not in the list is MISSING. [add] / [remove] simulate a plugin coming and going.
 */
class StaticProviderLookup(payment: List<PaymentProvider> = emptyList(), shipping: List<ShippingProvider> = emptyList()) : ProviderLookup {
    private val payments = CopyOnWriteArrayList(payment)
    private val shippings = CopyOnWriteArrayList(shipping)

    fun add(provider: PaymentProvider) {
        payments.removeAll { it.id == provider.id }
        payments.add(provider)
    }

    fun addShipping(provider: ShippingProvider) {
        shippings.removeAll { it.id == provider.id }
        shippings.add(provider)
    }

    fun remove(id: String) {
        payments.removeAll { it.id == id }
        shippings.removeAll { it.id == id }
    }

    private fun <P : Any> resolved(id: String, provider: P) = ResolvedProvider(id, provider, ProviderOrigin.BUILT_IN, null, MarketSpi.VERSION)

    override fun allPayment(): List<ResolvedProvider<PaymentProvider>> = payments.map { resolved(it.id, it) }

    override fun allShipping(): List<ResolvedProvider<ShippingProvider>> = shippings.map { resolved(it.id, it) }

    override fun state(kind: ProviderKind, id: String): ProviderState {
        val known = when (kind) {
            ProviderKind.PAYMENT -> payments.any { it.id == id }
            ProviderKind.SHIPPING -> shippings.any { it.id == id }
        }
        return if (known) ProviderState(ProviderAvailability.AVAILABLE, MarketSpi.VERSION)
        else ProviderState(ProviderAvailability.MISSING, detail = "no provider is registered under this id")
    }

    override fun listing(kind: ProviderKind): List<ProviderListing> = when (kind) {
        ProviderKind.PAYMENT -> allPayment().map { ProviderListing(kind, it.id, state(kind, it.id), it) }
        ProviderKind.SHIPPING -> allShipping().map { ProviderListing(kind, it.id, state(kind, it.id), it) }
    }
}
