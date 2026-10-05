package com.panomc.plugins.market.spi.shipping

import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.lang.reflect.Modifier

/** The shipping SPI of 03 section 5: defaults, result types, binary-compatibility rules, sender / parcel schema helpers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShippingSpiTest {
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun close() {
        vertx.close()
    }

    /** The smallest legal provider: only the abstract members. */
    private class Minimal : ShippingProvider {
        override val id = "minimal"
        override val descriptor = ProviderDescriptor(LocalizedText.of("Minimal"), LocalizedText.of("Minimal"), "truck")
        override fun settingsSchema(): SettingsSchema = settingsSchema { }
        override fun capabilities(settings: ProviderSettings) = ShippingCapabilities()
    }

    private val ctx get() = TestContexts.shipping("minimal", TestContexts.settings(), vertx)

    @Test
    fun `capabilities start with the defaults of the spec`() {
        val c = ShippingCapabilities()
        assertFalse(c.rateQuote)
        assertFalse(c.createShipment)
        assertEquals(emptySet<LabelFormat>(), c.labelFormats)
        assertFalse(c.cancel)
        assertFalse(c.trackingPull)
        assertEquals(1, c.trackBatchSize)
        assertFalse(c.trackingPush)
        assertFalse(c.webhookSigned)
        assertEquals(WebhookSetup.MANUAL_URL, c.webhookSetup)
        assertFalse(c.externalTracking)
        assertFalse(c.addressResolve)
        assertFalse(c.prepaidBalance)
        assertFalse(c.returns)
        assertNull(c.originCountries)
        assertNull(c.destinationCountries)
        assertFalse(c.requiresDimensions)
        assertFalse(c.requiresCustomsData)
        assertEquals(emptySet<AddressField>(), c.requiredAddressFields)
        assertEquals(1, c.maxParcels)
        assertEquals(600, c.quoteCacheSeconds)
        assertEquals(TestModeSupport.FLAG, c.testMode)
    }

    @Test
    fun `enums keep their order, new values may only be appended`() {
        assertEquals(listOf("DISTRICT", "NEIGHBORHOOD", "POSTAL_CODE", "STATE", "PHONE", "EMAIL", "IDENTITY_NUMBER"), AddressField.entries.map { it.name })
        assertEquals(listOf("PDF", "PNG", "GIF", "ZPL", "EPL", "SVG", "HTML"), LabelFormat.entries.map { it.name })
        assertEquals(
            listOf("CREATED", "LABEL_READY", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION", "RETURNING", "RETURNED", "CANCELLED", "LOST"),
            ShipmentStatus.entries.map { it.name }
        )
        assertEquals(
            listOf("INSUFFICIENT_BALANCE", "ADDRESS_INVALID", "SERVICE_UNAVAILABLE", "RATE_EXPIRED", "WEIGHT_LIMIT", "REJECTED", "OTHER"),
            ShipmentErrorCode.entries.map { it.name }
        )
    }

    @Test
    fun `only id, descriptor, settingsSchema and capabilities are abstract, every other method is a real JVM default method`() {
        val abstractNames = setOf("getId", "getDescriptor", "settingsSchema", "capabilities")
        val methods = ShippingProvider::class.java.declaredMethods.filter { !it.isSynthetic }
        assertEquals(abstractNames, methods.filter { Modifier.isAbstract(it.modifiers) }.map { it.name }.toSet())
        val withBody = methods.filter { it.name !in abstractNames }
        assertEquals(
            setOf("validateSettings", "onSettingsSaved", "runAction", "listServices", "quote", "resolveAddress", "createShipment", "fetchLabel", "cancelShipment", "track", "handleInbound", "balance"),
            withBody.map { it.name }.toSet()
        )
        withBody.forEach { assertTrue(it.isDefault, "${it.name} must be a JVM default method (-jvm-default=enable)") }
    }

    @Test
    fun `the default methods of a minimal provider behave as specified`() = runBlocking {
        val p = Minimal()
        val c = ctx
        assertTrue(p.validateSettings(c, c.settings).ok)
        assertTrue(p.onSettingsSaved(c, null) is ActionResult.None)
        assertEquals(ProviderErrorCode.UNSUPPORTED, assertThrows<ProviderException> { runBlocking { p.runAction(c, "x", JsonObject()) } }.code)
        assertEquals(emptyList<ShippingService>(), p.listServices(c))
        val quote = p.quote(c, QuoteRequest(SampleData.address(), SampleData.address(), listOf(SampleData.parcel()), listOf(SampleData.shipItem()), Money(2000, "EUR"), "EUR", null))
        assertFalse(quote.supported)
        assertEquals(emptyList<RateOption>(), quote.rates)
        val resolution = p.resolveAddress(c, SampleData.address())
        assertFalse(resolution.supported)
        assertEquals(ProviderErrorCode.UNSUPPORTED, assertThrows<ProviderException> { runBlocking { p.createShipment(c, SampleData.createShipmentRequest()) } }.code)
        val view = shipmentView()
        assertFalse(p.fetchLabel(c, view).notReady)
        assertTrue(p.fetchLabel(c, view).documents.isEmpty())
        val cancel = p.cancelShipment(c, view)
        assertFalse(cancel.supported)
        assertFalse(cancel.cancelled)
        assertEquals(emptyList<TrackingUpdate>(), p.track(c, TrackRequest(listOf(view))))
        val inbound = p.handleInbound(c, InboundRequest(InboundKind.WEBHOOK, "default", "POST", null, emptyMap(), emptyMap(), null, ByteArray(0), "203.0.113.9", 1L))
        assertEquals(404, inbound.reply.status)
        assertFalse(inbound.verified)
        assertNotNull(inbound.rejectReason)
        assertNull(p.balance(c))
        assertEquals(1, p.capabilities(TestContexts.settings()).trackBatchSize)
    }

    private fun shipmentView() = ShipmentView(
        id = 9, merchantReference = "SHP-9", carrierReference = null, trackingNumber = null, status = ShipmentStatus.CREATED, serviceCode = null,
        to = SampleData.address(), providerData = null, testMode = false, createdAt = 1L
    )

    @Test
    fun `the SPI has no data class and the marketing extension gets shippingProviders as a default method`() {
        val dir = File(ShippingProvider::class.java.protectionDomain.codeSource.location.toURI().path + "com/panomc/plugins/market/spi/shipping")
        val classes = dir.listFiles { f -> f.name.endsWith(".class") }!!.map { it.name.removeSuffix(".class") }.filter { !it.endsWith("Kt") }
        assertTrue(classes.size > 30, "expected the shipping types, found $classes")
        for (name in classes) {
            val methods = Class.forName("com.panomc.plugins.market.spi.shipping.$name").declaredMethods.map { it.name }
            assertFalse(methods.contains("copy"), "$name looks like a data class (copy)")
            assertTrue(methods.none { it.matches(Regex("component\\d+")) }, "$name looks like a data class (componentN)")
        }
        val m = MarketExtension::class.java.getMethod("shippingProviders")
        assertTrue(m.isDefault, "shippingProviders must be a JVM default method so older plugins keep loading")
        val oldStyle = object : MarketExtension {
            override val spiVersion = MarketSpi.VERSION
        }
        assertEquals(emptyList<ShippingProvider>(), oldStyle.shippingProviders())
        assertEquals(emptyList<Any>(), oldStyle.paymentProviders())
    }

    @Test
    fun `Created and Failed carry their optionals as vars with the documented defaults`() {
        val created = CreateShipmentResult.Created("obj_1")
        assertEquals(ShipmentStatus.CREATED, created.status)
        assertEquals(emptyList<LabelDocument>(), created.labels)
        assertEquals(emptyList<ShipmentPiece>(), created.pieces)
        assertNull(created.trackingNumber)
        created.trackingNumber = "TN"
        created.pieces = listOf(ShipmentPiece("P1").also { it.label = LabelDocument(LabelFormat.PDF, byteArrayOf(1)) })
        assertEquals("P1", created.pieces.single().trackingNumber)
        val failed = CreateShipmentResult.Failed(ShipmentErrorCode.RATE_EXPIRED, null)
        assertNull(failed.carrierReference)
        failed.carrierReference = "obj_1"
        assertEquals("obj_1", failed.carrierReference)
    }

    @Test
    fun `label documents default to LABEL and refuse an unknown kind`() {
        val doc = LabelDocument(LabelFormat.ZPL, "^XA".toByteArray())
        assertEquals("LABEL", doc.kind)
        doc.kind = "CUSTOMS"
        assertEquals("CUSTOMS", doc.kind)
        doc.kind = "INVOICE"
        assertThrows<IllegalArgumentException> { doc.kind = "label" }
        assertThrows<IllegalArgumentException> { doc.kind = "" }
        assertEquals("INVOICE", doc.kind)
    }

    @Test
    fun `LabelResult tells ready, not ready and none apart`() {
        val ready = LabelResult.of(listOf(LabelDocument(LabelFormat.PDF, ByteArray(3))))
        assertEquals(1, ready.documents.size)
        assertFalse(ready.notReady)
        val notReady = LabelResult.notReady()
        assertTrue(notReady.notReady)
        assertTrue(notReady.documents.isEmpty())
        val none = LabelResult.none()
        assertFalse(none.notReady)
        assertTrue(none.documents.isEmpty())
        assertThrows<IllegalArgumentException> { LabelResult.of(emptyList()) }
    }

    @Test
    fun `CancelShipmentResult and AddressResolution factories set the three flags consistently`() {
        val cancelled = CancelShipmentResult.cancelled()
        assertTrue(cancelled.cancelled && cancelled.supported)
        assertNull(cancelled.message)
        val refused = CancelShipmentResult.refused("already picked up")
        assertTrue(!refused.cancelled && refused.supported)
        assertEquals("already picked up", refused.message)
        val unsupported = CancelShipmentResult.unsupported()
        assertTrue(!unsupported.cancelled && !unsupported.supported)

        val address = SampleData.address()
        val ok = AddressResolution.ok(address)
        assertTrue(ok.supported && ok.valid)
        assertSame(address, ok.normalized)
        val invalid = AddressResolution.invalid("no such street")
        assertTrue(invalid.supported && !invalid.valid)
        assertNull(invalid.normalized)
        assertEquals("no such street", invalid.message)
        val none = AddressResolution.unsupported()
        assertTrue(!none.supported && !none.valid)
    }

    @Test
    fun `QuoteResult unsupported differs from an empty answer`() {
        val unsupported = QuoteResult.unsupported()
        assertFalse(unsupported.supported)
        assertTrue(unsupported.rates.isEmpty())
        val empty = QuoteResult(emptyList())
        assertTrue(empty.supported)
        val rate = RateOption("std", "Standard", Money(499, "EUR"))
        assertTrue(rate.priceIncludesTax)
        assertNull(rate.rateRef)
        assertEquals(emptyMap<String, String>(), QuoteResult(listOf(rate)).unavailable)
    }

    @Test
    fun `ShippingInboundResult accepted ignored and rejected set verified, key and reason`() {
        val update = TrackingUpdate(ShipmentTarget.TrackingNumber("TN1"), listOf(TrackingEvent(ShipmentStatus.DELIVERED, 5L)))
        val accepted = ShippingInboundResult.accepted(HttpReply.text("OK"), listOf(update), "key-1")
        assertTrue(accepted.verified)
        assertEquals("key-1", accepted.eventKey)
        assertEquals(1, accepted.updates.size)
        assertThrows<IllegalArgumentException> { ShippingInboundResult.accepted(HttpReply.text("OK"), listOf(update), " ") }
        assertNull(ShippingInboundResult.accepted(HttpReply.text("OK"), listOf(update)).eventKey)
        val ignored = ShippingInboundResult.ignored(HttpReply.text("OK"))
        assertTrue(ignored.verified && ignored.updates.isEmpty())
        val rejected = ShippingInboundResult.rejected(HttpReply.text("no", 401), "signature")
        assertFalse(rejected.verified)
        assertEquals("signature", rejected.rejectReason)
        assertEquals(401, rejected.reply.status)
    }

    @Test
    fun `tracking events and updates carry their optionals`() {
        val e = TrackingEvent(ShipmentStatus.IN_TRANSIT, 7L)
        assertNull(e.eventId)
        e.eventId = "ev-1"
        e.location = "Berlin"
        val u = TrackingUpdate(ShipmentTarget.CarrierReference("obj_1"), listOf(e))
        assertNull(u.estimatedDelivery)
        u.estimatedDelivery = 99L
        assertEquals(99L, u.estimatedDelivery)
        assertEquals(setOf("Id", "MerchantReference", "CarrierReference", "TrackingNumber"), ShipmentTarget::class.java.classes.map { it.simpleName }.toSet())
    }

    // ----- schema helpers -----------------------------------------------------------------------------------------

    private val reservedAddressKeys = listOf(
        "senderName", "senderCompany", "senderPhone", "senderEmail", "senderCountry", "senderState", "senderCity", "senderDistrict",
        "senderNeighborhood", "senderLine1", "senderLine2", "senderPostalCode"
    )

    @Test
    fun `senderAddress declares exactly the reserved sender keys and defaultParcel exactly the parcel keys`() {
        val schema = settingsSchema { senderAddress(); defaultParcel() }
        assertEquals(reservedAddressKeys + listOf("defaultParcelLengthMm", "defaultParcelWidthMm", "defaultParcelHeightMm"), schema.fields.map { it.key })
        assertEquals(reservedAddressKeys, SenderKeys.ADDRESS)
        assertEquals(listOf("defaultParcelLengthMm", "defaultParcelWidthMm", "defaultParcelHeightMm"), SenderKeys.PARCEL)
        assertEquals(setOf("senderCountry", "senderLine1"), schema.fields.filter { it.required }.map { it.key }.toSet())
        assertTrue(schema.secretKeys.isEmpty())
        assertEquals(setOf("en-US", "tr", "ru"), schema.fields.flatMap { it.label.values.keys }.toSet())
        schema.toJson()
        assertEquals(1L, schema.field("defaultParcelWidthMm")!!.min)
    }

    @Test
    fun `the helpers can put the fields into a group, an unknown group is refused`() {
        val grouped = settingsSchema {
            group("sender", LocalizedText.of("Sender"))
            senderAddress("sender")
            defaultParcel("sender")
        }
        assertTrue(grouped.fields.all { it.group == "sender" })
        assertThrows<IllegalArgumentException> { settingsSchema { senderAddress("nope") } }
        val twice = assertThrows<IllegalArgumentException> { settingsSchema { senderAddress(); senderAddress() } }
        assertTrue(twice.message!!.contains("Duplicate"))
    }

    @Test
    fun `SenderAddress from returns null without country or first line and reads every reserved key otherwise`() {
        assertNull(SenderAddress.from(TestContexts.settings()))
        assertNull(SenderAddress.from(TestContexts.settings(mapOf("senderCountry" to "TR"))))
        assertNull(SenderAddress.from(TestContexts.settings(mapOf("senderLine1" to "Street 1"))))
        assertNull(SenderAddress.from(TestContexts.settings(mapOf("senderCountry" to "  ", "senderLine1" to "Street 1"))))
        val full = SenderAddress.from(
            TestContexts.settings(
                mapOf(
                    "senderName" to "Shop", "senderCompany" to "Shop Ltd", "senderPhone" to "+90", "senderEmail" to "a@b.c", "senderCountry" to "tr",
                    "senderState" to "Istanbul", "senderCity" to "Istanbul", "senderDistrict" to "Kadikoy", "senderNeighborhood" to "Moda",
                    "senderLine1" to "Street 1", "senderLine2" to "Floor 2", "senderPostalCode" to "34710"
                )
            )
        )!!
        assertEquals("TR", full.country)
        assertEquals("Shop", full.firstName)
        assertEquals("Shop Ltd", full.company)
        assertEquals("+90", full.phone)
        assertEquals("a@b.c", full.email)
        assertEquals("Istanbul", full.state)
        assertEquals("Istanbul", full.city)
        assertEquals("Kadikoy", full.district)
        assertEquals("Moda", full.neighborhood)
        assertEquals("Street 1", full.line1)
        assertEquals("Floor 2", full.line2)
        assertEquals("34710", full.postalCode)
        val minimal = SenderAddress.from(TestContexts.settings(mapOf("senderCountry" to "DE", "senderLine1" to "Hauptstr. 1")))!!
        assertNull(minimal.city)
        assertNull(minimal.line2)
    }

    @Test
    fun `default parcel dimensions are read as millimetres`() {
        assertEquals(Triple(null, null, null), SenderAddress.defaultParcelMm(TestContexts.settings()))
        assertEquals(
            Triple(300, 200, 100),
            SenderAddress.defaultParcelMm(TestContexts.settings(mapOf("defaultParcelLengthMm" to 300, "defaultParcelWidthMm" to "200", "defaultParcelHeightMm" to 100L)))
        )
        assertEquals(Triple(300, null, null), SenderAddress.defaultParcelMm(TestContexts.settings(mapOf("defaultParcelLengthMm" to 300, "defaultParcelWidthMm" to "wide"))))
    }
}
