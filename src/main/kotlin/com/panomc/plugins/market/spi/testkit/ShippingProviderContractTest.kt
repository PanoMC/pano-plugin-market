package com.panomc.plugins.market.spi.testkit

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingInboundResult
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * An unsigned webhook (`webhookSigned = false`) against a [gateway] that answers the provider's re-fetch. [request] is
 * the webhook as the carrier sends it (identical for every state); [prepare] makes the gateway answer the re-fetch
 * with the carrier state number `i` (0, 1, ...), where state 0 and state 1 differ in the tracking status.
 */
class UnsignedWebhookScenario(
    val context: ShippingContext,
    val gateway: FakeGateway,
    val request: InboundRequest,
    val prepare: (state: Int) -> Unit
)

/**
 * A two-step carrier whose paid step fails after an object was created. [armFailure] makes the next
 * `createShipment` end in `Failed` with a `carrierReference`; afterwards the gateway answers normally. [reachedGateway]
 * tells whether a recorded request carries that reference (default: it appears in the path, query or body).
 */
class FailedCreateScenario(
    val context: ShippingContext,
    val gateway: FakeGateway,
    val request: CreateShipmentRequest,
    val armFailure: () -> Unit,
    val reachedGateway: (com.panomc.plugins.market.spi.testkit.Recorded, String) -> Boolean = { r, ref ->
        r.path.contains(ref) || r.query.contains(ref) || r.bodyText().contains(ref)
    }
)

/**
 * The checks of 03 section 9 for a [ShippingProvider], as plain functions (see [PaymentContractChecks] for why).
 * Hooks left at their defaults skip the check that needs them, except that an unsigned push provider must supply
 * [unsignedWebhookScenario].
 */
abstract class ShippingContractChecks : AutoCloseable {
    protected abstract fun createProvider(): ShippingProvider

    protected open fun settingValues(provider: ShippingProvider): Map<String, Any?> = TestContexts.defaultValues(provider.settingsSchema())

    /** See [PaymentContractChecks]: settings point at a [FakeGateway]; the checks that call out then run. */
    protected open val gatewayIsFake: Boolean get() = false

    protected open fun unsignedWebhookScenario(provider: ShippingProvider): UnsignedWebhookScenario? = null

    protected open fun failedCreateScenario(provider: ShippingProvider): FailedCreateScenario? = null

    private val vertxHolder = lazy { Vertx.vertx() }
    protected val vertx: Vertx get() = vertxHolder.value

    override fun close() {
        if (vertxHolder.isInitialized()) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun settingsOf(provider: ShippingProvider): ProviderSettings = TestContexts.settings(settingValues(provider))

    private fun context(provider: ShippingProvider, settings: ProviderSettings = settingsOf(provider)) =
        TestContexts.shipping(provider.id, settings, vertx)

    private fun handle(provider: ShippingProvider, ctx: ShippingContext, request: InboundRequest): ShippingInboundResult =
        blocking { provider.handleInbound(ctx, request) }

    fun checkDescriptor() {
        val p = createProvider()
        assertTrue(ID_PATTERN.matches(p.id), "provider id '${p.id}' must match [a-z0-9-]{2,32}")
        assertTrue(p.descriptor.displayName.resolve("en-US").isNotBlank(), "descriptor displayName has no English text")
        assertTrue(p.descriptor.description.resolve("en-US").isNotBlank(), "descriptor description has no English text")
        assertTrue(p.descriptor.icon.isNotBlank(), "descriptor icon is blank")
    }

    fun checkSchema() {
        val schema = createProvider().settingsSchema()
        val keys = schema.fields.map { it.key }
        assertTrue(keys.all { it.isNotEmpty() }, "empty schema key")
        assertEquals(keys.size, keys.toSet().size, "duplicate schema keys")
        assertTrue(keys.containsAll(schema.secretKeys), "secret keys must be schema fields")
        schema.toJson()
    }

    fun checkCapabilitiesArePure() {
        val p = createProvider()
        val values = settingValues(p)
        val a = snapshot(p.capabilities(TestContexts.settings(values)))
        assertEquals(a, snapshot(p.capabilities(TestContexts.settings(values))), "capabilities() gave different answers for the same settings")
        assertEquals(a, snapshot(createProvider().capabilities(TestContexts.settings(values))), "capabilities() differs between two provider instances")
        val caps: ShippingCapabilities = p.capabilities(TestContexts.settings(values))
        assertTrue(caps.trackBatchSize >= 1, "trackBatchSize must be at least 1")
        assertTrue(caps.maxParcels >= 1, "maxParcels must be at least 1")
        assertTrue(caps.quoteCacheSeconds >= 0, "quoteCacheSeconds must not be negative")
        caps.originCountries?.forEach { assertEquals(it.uppercase(), it, "originCountries must be upper case") }
        caps.destinationCountries?.forEach { assertEquals(it.uppercase(), it, "destinationCountries must be upper case") }
    }

    fun checkSecretsNeverLogged() {
        val provider = createProvider()
        val settings = settingsOf(provider)
        val secrets = provider.settingsSchema().secretKeys.mapNotNull { settings.string(it) }.filter { it.length >= 4 }
        val ctx = context(provider, settings)
        provider.capabilities(settings)
        for (req in Garbage.requests(InboundKind.WEBHOOK)) runCatching { handle(provider, ctx, req) }
        if (gatewayIsFake) {
            runCatching { blocking { provider.validateSettings(ctx, settings) } }
            runCatching { blocking { provider.onSettingsSaved(ctx, null) } }
            runCatching { blocking { provider.listServices(ctx) } }
        }
        val logged = ctx.recordedLog.everything()
        for (secret in secrets) assertFalse(logged.contains(secret), "a secret setting value was written to ProviderLog")
    }

    fun checkGarbageInboundIsRejectedOrIgnored() {
        val provider = createProvider()
        val ctx = context(provider)
        for (req in Garbage.requests(InboundKind.WEBHOOK)) {
            val result = try {
                handle(provider, ctx, req)
            } catch (e: Throwable) {
                fail("handleInbound threw ${e.javaClass.name} for garbage ${req.method} (${req.body.size} bytes, ${req.contentType})", e)
            }
            assertTrue(result.reply.status in 100..599)
            assertTrue(result.updates.isEmpty(), "garbage (${req.body.size} bytes, ${req.contentType}) produced ${result.updates.size} tracking update(s)")
        }
    }

    fun checkEmptySettingsOnlyConfigurationErrors() {
        val provider = createProvider()
        val ctx = TestContexts.shipping(provider.id, TestContexts.settings(), vertx)
        for (req in Garbage.requests(InboundKind.WEBHOOK)) {
            try {
                handle(provider, ctx, req)
            } catch (e: ProviderException) {
                assertEquals(ProviderErrorCode.CONFIGURATION, e.code, "with empty settings handleInbound may only throw CONFIGURATION, got ${e.code}")
            } catch (e: Throwable) {
                fail("with empty settings handleInbound threw ${e.javaClass.name} instead of ProviderException(CONFIGURATION)", e)
            }
        }
    }

    private fun statuses(updates: List<TrackingUpdate>) = updates.flatMap { u -> u.events.map { it.status } }

    fun checkUnsignedWebhookRefetchesBeforeTrusting() {
        val provider = createProvider()
        val caps = provider.capabilities(settingsOf(provider))
        assumeTrue(caps.trackingPush && !caps.webhookSigned, "provider takes no unsigned webhooks")
        val scenario = unsignedWebhookScenario(provider)
            ?: fail("webhookSigned = false with trackingPush = true: unsignedWebhookScenario() must be supplied (03 section 9)")
        scenario.gateway.clearRequests()
        scenario.prepare(0)
        val result = handle(provider, scenario.context, scenario.request)
        if (result.updates.isNotEmpty()) {
            assertTrue(scenario.gateway.requests.isNotEmpty(), "an unsigned webhook produced tracking updates without any outbound call (it was trusted as received)")
        }
    }

    fun checkIdenticalUnsignedBodiesWithDifferentStatesBothApply() {
        val provider = createProvider()
        val caps = provider.capabilities(settingsOf(provider))
        assumeTrue(caps.trackingPush && !caps.webhookSigned, "provider takes no unsigned webhooks")
        val scenario = unsignedWebhookScenario(provider)
            ?: fail("unsignedWebhookScenario() must be supplied for an unsigned push provider")
        scenario.prepare(0)
        val first = handle(provider, scenario.context, scenario.request)
        scenario.prepare(1)
        val second = handle(provider, scenario.context, scenario.request)
        assertTrue(first.updates.isNotEmpty() && second.updates.isNotEmpty(), "both deliveries must produce updates")
        assertNotEquals(statuses(first.updates), statuses(second.updates), "two identical bodies with different fetched states gave the same state change")
        assertNull(first.eventKey, "an unsigned trigger webhook must not set eventKey (03 section 6): the body is identical for every state, so no key can identify a delivery")
        assertNull(second.eventKey, "an unsigned trigger webhook must not set eventKey (03 section 6): the body is identical for every state, so no key can identify a delivery")
    }

    fun checkFailedCreateKeepsCarrierReference() {
        assumeTrue(gatewayIsFake, "gatewayIsFake is false: createShipment is not exercised")
        val provider = createProvider()
        val scenario = failedCreateScenario(provider)
        assumeTrue(scenario != null, "provider test supplies no failedCreateScenario")
        scenario!!
        scenario.armFailure()
        val first = blocking { provider.createShipment(scenario.context, scenario.request) }
        val failed = first as? CreateShipmentResult.Failed
            ?: fail<CreateShipmentResult.Failed>("the armed failure did not produce CreateShipmentResult.Failed (got ${first.javaClass.simpleName})")
        val reference = failed.carrierReference
        assumeTrue(reference != null, "single-step carrier: Failed carries no carrierReference")
        val before = scenario.gateway.requests.size
        val retry = CreateShipmentRequest(
            shipmentId = scenario.request.shipmentId, merchantReference = scenario.request.merchantReference,
            orderPublicId = scenario.request.orderPublicId, from = scenario.request.from, to = scenario.request.to,
            parcels = scenario.request.parcels, items = scenario.request.items, serviceCode = scenario.request.serviceCode,
            rateRef = scenario.request.rateRef, preferredLabelFormat = scenario.request.preferredLabelFormat,
            declaredValue = scenario.request.declaredValue, note = scenario.request.note,
            previousCarrierReference = reference, previousProviderData = failed.providerData
        )
        blocking { provider.createShipment(scenario.context, retry) }
        val sent = scenario.gateway.requests.drop(before)
        assertTrue(sent.any { scenario.reachedGateway(it, reference!!) }, "the retry did not hand the previous carrierReference back to the carrier")
    }
}

/** The shipping provider contract of 03 section 9. A plugin test extends this class; see [ShippingContractChecks]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ShippingProviderContractTest : ShippingContractChecks() {
    @Test fun `descriptor is complete`() = checkDescriptor()

    @Test fun `schema keys are unique and non-empty`() = checkSchema()

    @Test fun `capabilities are pure`() = checkCapabilitiesArePure()

    @Test fun `secret settings never reach the provider log`() = checkSecretsNeverLogged()

    @Test fun `garbage inbound traffic is rejected or ignored and never throws`() = checkGarbageInboundIsRejectedOrIgnored()

    @Test fun `with empty settings inbound traffic only fails with CONFIGURATION`() = checkEmptySettingsOnlyConfigurationErrors()

    @Test fun `an unsigned webhook is re-fetched before it is trusted`() = checkUnsignedWebhookRefetchesBeforeTrusting()

    @Test fun `identical unsigned bodies with different fetched states both apply`() = checkIdenticalUnsignedBodiesWithDifferentStatesBothApply()

    @Test fun `a failed create hands its carrierReference back on retry`() = checkFailedCreateKeepsCarrierReference()

    @AfterAll
    fun closeContractResources() = close()
}
