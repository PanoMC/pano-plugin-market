package com.panomc.plugins.market.routes.api.store

import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.routes.api.GetProductImageAPI
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.base.MarketGate
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.panel.settings.currency.PanelCurrencyRatesAPI
import com.panomc.plugins.market.routes.panel.settings.currency.PanelRefreshCurrencyRatesAPI
import com.panomc.plugins.market.runtime.MarketRuntime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `storeEnabled = false` (04 section 1, the B-05 twin): every store endpoint of this slice is a `MarketApi` that keeps the
 * store switch (so it answers 503 `STORE_DISABLED`), while the currency settings endpoints are `MarketPanelApi`s, which
 * never look at the switch: the panel keeps working with the store switched off.
 */
class StoreGateTest {
    @AfterEach
    fun restore() {
        MarketGate.storeEnabled = { true }
        MarketRuntime.reset()
    }

    private val storeRoutes = listOf(GetStoreAPI::class.java, GetStoreProductsAPI::class.java, GetStoreProductAPI::class.java, GetProductImageAPI::class.java)

    @Test
    fun `every store route is a market public route that keeps the store switch`() {
        for (route in storeRoutes) {
            assertTrue(MarketApi::class.java.isAssignableFrom(route), "${route.simpleName} is a MarketApi")
            assertTrue(route.declaredMethods.none { it.name == "getRequiresStoreEnabled" }, "${route.simpleName} does not opt out of the store switch")
            assertFalse(MarketPanelApi::class.java.isAssignableFrom(route))
        }
    }

    @Test
    fun `the currency settings routes are panel routes`() {
        for (route in listOf(PanelCurrencyRatesAPI::class.java, PanelRefreshCurrencyRatesAPI::class.java)) {
            assertTrue(MarketPanelApi::class.java.isAssignableFrom(route), route.simpleName)
            assertFalse(MarketApi::class.java.isAssignableFrom(route), "${route.simpleName} is not gated by the store switch")
        }
    }

    @Test
    fun `with the store off the store gate answers STORE_DISABLED and the panel gate lets the request through`() {
        MarketRuntime.starting()
        MarketRuntime.finish(null, emptyList(), degraded = false)
        MarketGate.storeEnabled = { false }

        // what MarketApi.marketChecks does ...
        MarketGate.requireReady()
        val e = assertThrows(StoreDisabled::class.java) { MarketGate.requireStoreEnabled() }
        assertEquals(503, e.getStatusCode())
        assertEquals("STORE_DISABLED", e.getErrorCode())

        // ... and MarketPanelApi.marketChecks (only the runtime gate)
        MarketGate.requireReady()
    }

    @Test
    fun `a market that is not READY is STORE_UNAVAILABLE for both kinds`() {
        MarketRuntime.reset()

        val e = assertThrows(StoreUnavailable::class.java) { MarketGate.requireReady() }

        assertEquals("STORE_UNAVAILABLE", e.getErrorCode())
    }
}
