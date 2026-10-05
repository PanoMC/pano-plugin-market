package com.panomc.plugins.market.spi.testkit

import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingInboundResult
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.TrackingEvent
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.Money
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException

/**
 * Proof that the contract checks bite: each deliberately broken provider must fail exactly the check that guards the
 * broken rule, and the correct example must pass the same check. Without this a green contract suite would prove
 * nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractChecksBiteTest {
    private val payGateway = examplePaymentGateway()
    private val carrier = ExampleCarrierGateway()
    private val opened = ArrayList<AutoCloseable>()

    @AfterAll
    fun closeAll() {
        opened.forEach { it.close() }
        payGateway.close()
        carrier.gateway.close()
    }

    private fun pay(make: () -> PaymentProvider): PaymentContractChecks = object : PaymentContractChecks() {
        override fun createProvider() = make()
        override fun settingValues(provider: PaymentProvider): Map<String, Any?> =
            TestContexts.defaultValues(provider.settingsSchema()) + ("baseUrl" to payGateway.baseUrl)
        override val gatewayIsFake: Boolean get() = true
        override fun signedNotification(ctx: PaymentContext) = ExamplePaymentProvider.signed(ctx.settings.require("webhookSecret"))
        override fun signedUnknownEvent(ctx: PaymentContext) =
            ExamplePaymentProvider.signed(ctx.settings.require("webhookSecret"), type = "payment.invented_later", id = "evt_9").request
    }.also { opened += it }

    private fun ship(
        make: () -> ShippingProvider,
        scenarios: Boolean = true
    ): ShippingContractChecks = object : ShippingContractChecks() {
        override fun createProvider() = make()
        override fun settingValues(provider: ShippingProvider): Map<String, Any?> =
            TestContexts.defaultValues(provider.settingsSchema()) + ("baseUrl" to carrier.gateway.baseUrl)
        override val gatewayIsFake: Boolean get() = true
        override fun unsignedWebhookScenario(provider: ShippingProvider) =
            if (!scenarios) null else UnsignedWebhookScenario(
                TestContexts.shipping(provider.id, TestContexts.settings(settingValues(provider)), vertx), carrier.gateway, carrier.webhook()
            ) { carrier.trackState.set(it) }
        override fun failedCreateScenario(provider: ShippingProvider) = FailedCreateScenario(
            TestContexts.shipping(provider.id, TestContexts.settings(settingValues(provider)), vertx), carrier.gateway,
            SampleData.createShipmentRequest(), { carrier.failBuy.set(true) }
        )
    }.also { opened += it }

    private fun fails(block: () -> Unit) {
        val e = assertThrows<Throwable>("the check should have failed") { block() }
        assertTrue(e is AssertionError, "expected an assertion failure, got $e")
        assertTrue(e !is TestAbortedException, "the check was skipped instead of failing")
    }

    private fun passes(block: () -> Unit) = block()

    // ----- payment ------------------------------------------------------------------------------------------------

    @Test
    fun `payment id outside the pattern fails the descriptor check`() {
        passes(pay({ ExamplePaymentProvider() })::checkDescriptor)
        fails(pay({ object : ExamplePaymentProvider() { override val id = "Bad_ID" } })::checkDescriptor)
        fails(pay({ object : ExamplePaymentProvider() { override val id = "x" } })::checkDescriptor)
    }

    @Test
    fun `payment capabilities that change between calls fail the purity check`() {
        passes(pay({ ExamplePaymentProvider() })::checkCapabilitiesArePure)
        fails(pay({
            object : ExamplePaymentProvider() {
                override fun capabilities(settings: ProviderSettings) = PaymentCapabilities().also { it.recurringMaxCycles = brokenCounter.incrementAndGet() }
            }
        })::checkCapabilitiesArePure)
    }

    @Test
    fun `a payment provider that logs a secret fails the log check, in handleInbound and in startPayment`() {
        passes(pay({ ExamplePaymentProvider() })::checkSecretsNeverLogged)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
                    ctx.log.warn("rejected, secret was ${ctx.settings.string("webhookSecret")}")
                    return super.handleInbound(ctx, request)
                }
            }
        })::checkSecretsNeverLogged)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult {
                    ctx.log.exchange("start", "Authorization: Bearer ${ctx.settings.string("apiKey")}", null, 200, 1)
                    return super.startPayment(ctx, request)
                }
            }
        })::checkSecretsNeverLogged)
    }

    @Test
    fun `a payment provider that throws on garbage fails the garbage check`() {
        passes(pay({ ExamplePaymentProvider() })::checkGarbageInboundIsRejectedOrIgnored)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
                    JsonObject(request.http.bodyAsString())
                    return super.handleInbound(ctx, request)
                }
            }
        })::checkGarbageInboundIsRejectedOrIgnored)
    }

    @Test
    fun `a payment provider that accepts an unsigned body fails the garbage check`() {
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult =
                    InboundResult.accepted(
                        HttpReply.text("OK"),
                        listOf(PaymentEvent.Succeeded(PaymentTarget.Reference("ABCDEFGHJKMNPQRSTVWX"), Money(1000, "EUR")))
                    )
            }
        })::checkGarbageInboundIsRejectedOrIgnored)
    }

    @Test
    fun `a payment provider that fails with anything but CONFIGURATION on empty settings fails the empty settings check`() {
        passes(pay({ ExamplePaymentProvider() })::checkEmptySettingsOnlyConfigurationErrors)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
                    if (ctx.settings.string("webhookSecret") == null) throw IllegalStateException("not configured")
                    return super.handleInbound(ctx, request)
                }
            }
        })::checkEmptySettingsOnlyConfigurationErrors)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
                    throw com.panomc.plugins.market.spi.common.ProviderException(com.panomc.plugins.market.spi.common.ProviderErrorCode.INTERNAL, "boom")
                }
            }
        })::checkEmptySettingsOnlyConfigurationErrors)
    }

    @Test
    fun `a payment provider that turns an unknown event type into events fails the unknown event check`() {
        passes(pay({ ExamplePaymentProvider() })::checkUnknownEventsAreIgnored)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
                    val r = super.handleInbound(ctx, request)
                    if (r.events.isNotEmpty() || !r.verified) return r
                    return InboundResult.accepted(
                        HttpReply.text("OK"), listOf(PaymentEvent.Pending(PaymentTarget.Reference("ABCDEFGHJKMNPQRSTVWX"), PendingReason.OTHER))
                    )
                }
            }
        })::checkUnknownEventsAreIgnored)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
                    val r = super.handleInbound(ctx, request)
                    return if (r.verified && r.events.isEmpty()) InboundResult.ignored(HttpReply.text("retry", 500)) else r
                }
            }
        })::checkUnknownEventsAreIgnored)
    }

    @Test
    fun `a payment provider whose eventKey follows an unsigned field fails the event key check`() {
        passes(pay({ ExamplePaymentProvider() })::checkEventKeyIgnoresUnsignedFields)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult =
                    super.handleInbound(ctx, request).also { if (it.eventKey != null) it.eventKey = it.eventKey + (request.http.rawQuery ?: "") }
            }
        })::checkEventKeyIgnoresUnsignedFields)
    }

    @Test
    fun `a payment provider that cannot start fails the start check, a working one passes it`() {
        passes(pay({ ExamplePaymentProvider() })::checkStartResultIsWellFormed)
        fails(pay({
            object : ExamplePaymentProvider() {
                override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult =
                    throw IllegalStateException("gateway client is broken")
            }
        })::checkStartResultIsWellFormed)
    }

    @Test
    fun `a payment Html result with https origins passes the start check`() {
        passes(pay({
            object : ExamplePaymentProvider() {
                override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult =
                    StartPaymentResult.Html("<form></form>").also { it.scriptOrigins = listOf("https://js.gateway.example"); it.frameOrigins = listOf("https://3ds.gateway.example") }
            }
        })::checkStartResultIsWellFormed)
        assertThrows<IllegalArgumentException> { StartPaymentResult.Html("<p/>").scriptOrigins = listOf("http://js.gateway.example") }
    }

    // ----- shipping -----------------------------------------------------------------------------------------------

    @Test
    fun `shipping id, schema and capabilities checks`() {
        passes(ship({ ExampleShippingProvider() })::checkDescriptor)
        passes(ship({ ExampleShippingProvider() })::checkSchema)
        fails(ship({ object : ExampleShippingProvider() { override val id = "UPPER" } })::checkDescriptor)
        passes(ship({ ExampleShippingProvider() })::checkCapabilitiesArePure)
        fails(ship({
            object : ExampleShippingProvider() {
                override fun capabilities(settings: ProviderSettings) = super.capabilities(settings).also { it.quoteCacheSeconds = brokenCounter.incrementAndGet() }
            }
        })::checkCapabilitiesArePure)
        fails(ship({
            object : ExampleShippingProvider() {
                override fun capabilities(settings: ProviderSettings) = super.capabilities(settings).also { it.trackBatchSize = 0 }
            }
        })::checkCapabilitiesArePure)
        fails(ship({
            object : ExampleShippingProvider() {
                override fun capabilities(settings: ProviderSettings) = super.capabilities(settings).also { it.destinationCountries = setOf("tr") }
            }
        })::checkCapabilitiesArePure)
    }

    @Test
    fun `a shipping provider that logs a secret fails the log check`() {
        passes(ship({ ExampleShippingProvider() })::checkSecretsNeverLogged)
        fails(ship({
            object : ExampleShippingProvider() {
                override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
                    ctx.log.error("failed for key ${ctx.settings.string("apiKey")}", null)
                    return super.handleInbound(ctx, request)
                }
            }
        })::checkSecretsNeverLogged)
    }

    @Test
    fun `shipping garbage and empty settings checks`() {
        passes(ship({ ExampleShippingProvider() })::checkGarbageInboundIsRejectedOrIgnored)
        passes(ship({ ExampleShippingProvider() })::checkEmptySettingsOnlyConfigurationErrors)
        fails(ship({
            object : ExampleShippingProvider() {
                override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
                    JsonObject(request.bodyAsString())
                    return super.handleInbound(ctx, request)
                }
            }
        })::checkGarbageInboundIsRejectedOrIgnored)
        fails(ship({
            object : ExampleShippingProvider() {
                override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult =
                    ShippingInboundResult.accepted(
                        HttpReply.text("OK"),
                        listOf(TrackingUpdate(ShipmentTarget.TrackingNumber("TN1"), listOf(TrackingEvent(ShipmentStatus.DELIVERED, 1L))))
                    )
            }
        })::checkGarbageInboundIsRejectedOrIgnored)
        fails(ship({
            object : ExampleShippingProvider() {
                override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
                    if (ctx.settings.string("apiKey") == null) throw NullPointerException("no key")
                    return super.handleInbound(ctx, request)
                }
            }
        })::checkEmptySettingsOnlyConfigurationErrors)
    }

    /** Takes the status straight from the body, never asking the carrier. */
    private class TrustingShipping : ExampleShippingProvider() {
        override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
            ctx.settings.require("apiKey")
            val number = try { JsonObject(request.bodyAsString()).getString("trackingNumber") } catch (e: Exception) {
                return ShippingInboundResult.rejected(HttpReply.text("bad", 400), "body")
            } ?: return ShippingInboundResult.ignored(HttpReply.text("OK"))
            return ShippingInboundResult.accepted(
                HttpReply.text("OK"), listOf(TrackingUpdate(ShipmentTarget.TrackingNumber(number), listOf(TrackingEvent(ShipmentStatus.DELIVERED, ctx.now()))))
            )
        }
    }

    /** Fetches once and answers every later delivery of the same body from its cache. */
    private class CachingShipping : ExampleShippingProvider() {
        private var cached: ShippingInboundResult? = null

        override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
            val r = super.handleInbound(ctx, request)
            if (r.updates.isEmpty()) return r
            return cached ?: r.also { cached = it }
        }
    }

    @Test
    fun `an unsigned webhook that is trusted without a re-fetch fails the outbound call check`() {
        passes(ship({ ExampleShippingProvider() })::checkUnsignedWebhookRefetchesBeforeTrusting)
        fails(ship({ TrustingShipping() })::checkUnsignedWebhookRefetchesBeforeTrusting)
    }

    @Test
    fun `identical unsigned bodies must both apply when the fetched state differs`() {
        passes(ship({ ExampleShippingProvider() })::checkIdenticalUnsignedBodiesWithDifferentStatesBothApply)
        fails(ship({ TrustingShipping() })::checkIdenticalUnsignedBodiesWithDifferentStatesBothApply)
        fails(ship({ CachingShipping() })::checkIdenticalUnsignedBodiesWithDifferentStatesBothApply)
        fails(ship({
            object : ExampleShippingProvider() {
                override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult =
                    super.handleInbound(ctx, request).also { if (it.updates.isNotEmpty()) it.eventKey = "TN1" }
            }
        })::checkIdenticalUnsignedBodiesWithDifferentStatesBothApply)
    }

    @Test
    fun `an unsigned push provider without a scenario fails, a signed one needs none`() {
        fails(ship({ ExampleShippingProvider() }, scenarios = false)::checkUnsignedWebhookRefetchesBeforeTrusting)
        fails(ship({ ExampleShippingProvider() }, scenarios = false)::checkIdenticalUnsignedBodiesWithDifferentStatesBothApply)
        val signed = object : ExampleShippingProvider() {
            override fun capabilities(settings: ProviderSettings): ShippingCapabilities = super.capabilities(settings).also { it.webhookSigned = true }
        }
        val checks = ship({ signed }, scenarios = false)
        val e = assertThrows<TestAbortedException> { checks.checkUnsignedWebhookRefetchesBeforeTrusting() }
        assertTrue(e.message!!.contains("no unsigned webhooks"))
    }

    @Test
    fun `a failed create must hand its carrierReference back on retry`() {
        passes(ship({ ExampleShippingProvider() })::checkFailedCreateKeepsCarrierReference)
        fails(ship({
            object : ExampleShippingProvider() {
                override suspend fun createShipment(ctx: ShippingContext, request: CreateShipmentRequest): CreateShipmentResult =
                    super.createShipment(
                        ctx,
                        CreateShipmentRequest(
                            request.shipmentId, request.merchantReference, request.orderPublicId, request.from, request.to, request.parcels,
                            request.items, request.serviceCode, request.rateRef, request.preferredLabelFormat, request.declaredValue, request.note,
                            previousCarrierReference = null, previousProviderData = null
                        )
                    )
            }
        })::checkFailedCreateKeepsCarrierReference)
    }
}
