package com.panomc.plugins.market.routes.api.payment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.BrowserAccess
import com.panomc.platform.model.Route
import com.panomc.plugins.market.routes.api.shipping.ShippingWebhookAPI
import com.panomc.plugins.market.support.PanelEndpointMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Doc 05 section 4 (Origin gate): a payment return, a payment notification, a gateway webhook and a carrier webhook are posted by a page or a server
 * of another origin, so they declare `ANY_ORIGIN`; every other route of the market keeps the default `SAME_SITE`, where a foreign page cannot post.
 */
class BrowserAccessTest {
    private val routes = PanelEndpointMatrix.classesUnder("com.panomc.plugins.market.routes", PaymentWebhookAPI::class.java)
        .filter { Route::class.java.isAssignableFrom(it) && it.isAnnotationPresent(Endpoint::class.java) && !Modifier.isAbstract(it.modifiers) }

    private val gateways = setOf(PaymentWebhookAPI::class.java, PaymentNotifyAPI::class.java, PaymentReturnAPI::class.java, ShippingWebhookAPI::class.java)

    @Test
    fun `the routes are found`() {
        assertTrue(routes.size > 150, "found ${routes.size} routes under routes (site, buyer and panel)")
        assertTrue(routes.containsAll(gateways))
    }

    @Test
    fun `the payment return, notify and webhook routes and the carrier webhook accept a post from any origin`() {
        for (type in gateways) {
            assertEquals(BrowserAccess.ANY_ORIGIN, (PanelEndpointMatrix.instantiate(type) as Route).browserAccess, type.simpleName)
        }
    }

    @Test
    fun `every other route of the store stays on the same-site gate`() {
        for (type in routes - gateways) {
            assertEquals(BrowserAccess.SAME_SITE, (PanelEndpointMatrix.instantiate(type) as Route).browserAccess, type.simpleName)
        }
    }

    @Test
    fun `every inbound route is one of the four, so a new gateway route is decided here`() {
        val inbound = routes.filter { MarketInboundApi::class.java.isAssignableFrom(it) }.toSet()

        assertEquals(gateways, inbound)
    }
}
