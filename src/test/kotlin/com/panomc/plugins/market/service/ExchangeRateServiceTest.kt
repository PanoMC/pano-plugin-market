package com.panomc.plugins.market.service

import com.panomc.plugins.market.MarketPlugin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Currency codes are plain strings: unsupported codes give no rate and never reach the network. */
class ExchangeRateServiceTest {
    // The service only touches the plugin lazily (web client), which none of these paths reach.
    private val service: ExchangeRateService = run {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe

        ExchangeRateService(unsafe.allocateInstance(MarketPlugin::class.java) as MarketPlugin)
    }

    @Test
    fun `an unsupported code yields no rate instead of an exception`() = runBlocking {
        assertNull(service.fetchRate("XXX", "TRY"))
        assertNull(service.fetchRate("TRY", "KWD"))
        assertNull(service.fetchRate("try", "USD"))
        assertNull(service.fetchRateForDate("KWD", "KWD", 0L))
        assertNull(service.fetchRateForDate("USD", "ZZZ", 0L))
    }

    @Test
    fun `the same supported code, zero-decimal ones included, is 1`() = runBlocking {
        assertEquals(1.0, service.fetchRate("JPY", "JPY"))
        assertEquals(1.0, service.fetchRateForDate("JPY", "JPY", 0L))
    }
}
