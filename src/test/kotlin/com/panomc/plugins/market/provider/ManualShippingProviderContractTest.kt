package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.shipping.CancelShipmentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.SenderAddress
import com.panomc.plugins.market.spi.shipping.SenderKeys
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.ShippingProviderContractTest
import com.panomc.plugins.market.spi.testkit.TestContexts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shipping provider contract of 03 section 9 run against the built-in `manual` provider (all checks of the testkit),
 * plus its own rules of 03 section 7: no network, no carrier ids, the sender address as the default `from`.
 */
class ManualShippingProviderContractTest : ShippingProviderContractTest() {
    override fun createProvider(): ShippingProvider = ManualShippingProvider()

    private val provider = ManualShippingProvider()
    private val settings = TestContexts.settings(emptyMap())

    @Test
    fun `capabilities are those of 03 section 7`() {
        val c = provider.capabilities(settings)

        assertFalse(c.rateQuote)
        assertTrue(c.createShipment)
        assertTrue(c.labelFormats.isEmpty())
        assertTrue(c.cancel)
        assertFalse(c.trackingPull)
        assertFalse(c.trackingPush)
        assertFalse(c.externalTracking)
        assertEquals(WebhookSetup.NONE, c.webhookSetup)
    }

    @Test
    fun `createShipment needs no network and answers the merchant reference`(): Unit = runBlocking {
        val ctx = TestContexts.shipping(provider.id, settings, io.vertx.core.Vertx.vertx())
        val request = SampleData.createShipmentRequest()

        val result = provider.createShipment(ctx, request)

        val created = result as CreateShipmentResult.Created
        assertEquals(request.merchantReference, created.carrierReference)
        assertEquals(ShipmentStatus.IN_TRANSIT, created.status)
        assertNull(created.trackingNumber, "the admin types the tracking number")
        assertTrue(created.labels.isEmpty())
    }

    @Test
    fun `cancel always succeeds locally`(): Unit = runBlocking {
        val ctx = TestContexts.shipping(provider.id, settings, io.vertx.core.Vertx.vertx())
        val view = ShipmentView(1, "MR-1", "MR-1", null, ShipmentStatus.IN_TRANSIT, null, SampleData.address(), null, false, 0L)

        val result: CancelShipmentResult = provider.cancelShipment(ctx, view)

        assertTrue(result.cancelled && result.supported, "a manual shipment is cancelled locally")
    }

    @Test
    fun `the sender address of the settings is read by SenderAddress`() {
        val values = mapOf(SenderKeys.COUNTRY to "tr", SenderKeys.LINE1 to "Main St 1", SenderKeys.CITY to "Izmir", SenderKeys.NAME to "Shop")
        val sender = SenderAddress.from(TestContexts.settings(values))!!

        assertEquals("TR", sender.country)
        assertEquals("Main St 1", sender.line1)
        assertNull(SenderAddress.from(settings), "no sender configured")
    }

    @Test
    fun `no field is required so the provider is never NOT_CONFIGURED`() {
        val schema = provider.settingsSchema()

        assertTrue(schema.fields.none { it.required })
        assertEquals(setOf("A6", "A4"), schema.field(ManualShippingProvider.KEY_LABEL_PAPER)!!.options.map { it.value }.toSet())
        assertEquals("manual", provider.id)
        assertTrue(schema.secretKeys.isEmpty())
    }
}
