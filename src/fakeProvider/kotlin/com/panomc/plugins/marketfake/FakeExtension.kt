package com.panomc.plugins.marketfake

import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.payment.PaymentProvider

/** The two providers of the fake gateway plugin (17 section 6.2): `fake` and `fake-eur`, so method filtering can be tested. */
class FakeExtension : MarketExtension {
    override val spiVersion: Int = MarketSpi.VERSION

    private val providers: List<PaymentProvider> = listOf(FakeProvider(FakeProvider.ID), FakeProvider(FakeProvider.ID_EUR))

    override fun paymentProviders(): List<PaymentProvider> = providers
}
