package com.panomc.plugins.market.frontend

import com.panomc.platform.api.ExternalUrlProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketExternalUrlProviderTest {
    @Test
    fun `a payment provider lists its webhook and return address under the v1 prefix`() {
        val urls = MarketExternalUrlProvider.urlsOf("https://shop.example", paymentIds = listOf("stripe"), shippingIds = emptyList())

        assertEquals(
            listOf(
                "https://shop.example/api/plugins/pano-plugin-market/payments/stripe/webhook",
                "https://shop.example/api/plugins/pano-plugin-market/payments/stripe/return/{attemptToken}/result"
            ),
            urls.map { it.url }
        )
        assertEquals(listOf("stripe payment webhook", "stripe payment return"), urls.map { it.label })
    }

    @Test
    fun `a carrier lists its webhook with the install token left to fill`() {
        val urls = MarketExternalUrlProvider.urlsOf("https://shop.example", paymentIds = emptyList(), shippingIds = listOf("dhl"))

        assertEquals(listOf("https://shop.example/api/plugins/pano-plugin-market/shipping/dhl/webhook/{installToken}"), urls.map { it.url })
    }

    @Test
    fun `no providers list nothing and no address carries the old prefix`() {
        assertEquals(emptyList<Any>(), MarketExternalUrlProvider.urlsOf("https://shop.example", emptyList(), emptyList()))

        val all = MarketExternalUrlProvider.urlsOf("https://shop.example", listOf("a1", "b2"), listOf("c3"))

        assertEquals(5, all.size)
        assertTrue(all.none { it.url.contains("/api/market/") })
    }

    @Test
    fun `the provider is a bean the platform finds by type`() {
        assertTrue(ExternalUrlProvider::class.java.isAssignableFrom(MarketExternalUrlProvider::class.java))
        assertTrue(MarketExternalUrlProvider::class.java.isAnnotationPresent(org.springframework.stereotype.Component::class.java))
    }
}
