package com.panomc.plugins.market.support

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.shipping.AddressResolution
import com.panomc.plugins.market.spi.shipping.QuoteRequest
import com.panomc.plugins.market.spi.shipping.SenderKeys
import com.panomc.plugins.market.spi.shipping.QuoteResult
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scriptable carrier for the shipping quote tests (10 section 16, tests 27 to 34). Id `fakecarrier` unless given. [onQuote] and
 * [onResolve] answer (they may throw, which the service must survive); [quoteDelayMs] / [resolveDelayMs] hold a call open (a
 * timeout test); every call lands in [quotes] / [resolves].
 */
class FakeShippingProvider(override val id: String = "fakecarrier") : ShippingProvider {
    override val descriptor = ProviderDescriptor(LocalizedText.of("Fake carrier"), LocalizedText.of("Scriptable test carrier"), "truck")

    @Volatile var caps: ShippingCapabilities = ShippingCapabilities().also { it.rateQuote = true; it.createShipment = true }
    @Volatile var onQuote: (QuoteRequest) -> QuoteResult = { QuoteResult(emptyList()) }
    @Volatile var onResolve: (Address) -> AddressResolution = { AddressResolution.unsupported() }
    @Volatile var quoteDelayMs: Long = 0
    @Volatile var resolveDelayMs: Long = 0

    private val quoteLog = CopyOnWriteArrayList<QuoteRequest>()
    private val resolveLog = CopyOnWriteArrayList<Address>()

    val quotes: List<QuoteRequest> get() = quoteLog.toList()
    val resolves: List<Address> get() = resolveLog.toList()

    /** The reserved sender and default parcel keys (10 section 8.1), none of them required, so a carrier without a sender is still configured. */
    override fun settingsSchema(): SettingsSchema = settingsSchema {
        SenderKeys.ADDRESS.forEach { key -> text(key) { label = LocalizedText.of(key) } }
        SenderKeys.PARCEL.forEach { key -> number(key) { label = LocalizedText.of(key) } }
    }

    override fun capabilities(settings: ProviderSettings): ShippingCapabilities = caps

    override suspend fun quote(ctx: ShippingContext, request: QuoteRequest): QuoteResult {
        quoteLog += request

        if (quoteDelayMs > 0) delay(quoteDelayMs)

        return onQuote(request)
    }

    override suspend fun resolveAddress(ctx: ShippingContext, address: Address): AddressResolution {
        resolveLog += address

        if (resolveDelayMs > 0) delay(resolveDelayMs)

        return onResolve(address)
    }
}
