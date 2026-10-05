package com.panomc.plugins.market.spi.testkit

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Gateway of the example payment provider: `POST /v1/payments` answers an id and a redirect URL. */
internal fun examplePaymentGateway(): FakeGateway = FakeGateway.start().also { g ->
    g.on("POST", "/v1/payments") { Reply.json("""{"id":"pay_1","url":"https://gateway.invalid/pay/1"}""") }
}

/** The carrier of the example shipping provider. [trackState] picks the fetched status; [failBuy] fails the next paid step once. */
internal class ExampleCarrierGateway {
    val trackState = AtomicInteger(0)
    val failBuy = AtomicBoolean(false)
    private val ids = AtomicInteger(0)
    val gateway: FakeGateway = FakeGateway.start().also { g ->
        g.on("GET", "/v1/track/*") {
            Reply.json("""{"status":"${if (trackState.get() == 0) "IN_TRANSIT" else "DELIVERED"}"}""")
        }
        g.on("POST", "/v1/shipments") { Reply.json("""{"id":"obj_${ids.incrementAndGet()}"}""") }
        g.on("POST", "/v1/shipments/*") {
            if (failBuy.compareAndSet(true, false)) Reply.json("""{"message":"insufficient balance"}""", 402)
            else Reply.json("""{"trackingNumber":"TN1"}""")
        }
    }

    fun webhook(): InboundRequest = InboundRequest(
        kind = InboundKind.WEBHOOK, channel = "default", method = "POST", rawQuery = null, query = emptyMap(),
        headers = mapOf("content-type" to listOf("application/json")), contentType = "application/json",
        body = """{"trackingNumber":"TN1"}""".toByteArray(), remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
    )
}

/** The positive control: the contract suite against the correct example payment provider. Every check must run (none skipped). */
class ExamplePaymentContractTest : ProviderContractTest() {
    private val gatewayHolder = lazy { examplePaymentGateway() }

    override fun createProvider() = ExamplePaymentProvider()

    override fun settingValues(provider: com.panomc.plugins.market.spi.payment.PaymentProvider): Map<String, Any?> =
        TestContexts.defaultValues(provider.settingsSchema()) + ("baseUrl" to gatewayHolder.value.baseUrl)

    override val gatewayIsFake: Boolean get() = true

    override fun signedNotification(ctx: com.panomc.plugins.market.spi.payment.PaymentContext) =
        ExamplePaymentProvider.signed(ctx.settings.require("webhookSecret"))

    override fun signedUnknownEvent(ctx: com.panomc.plugins.market.spi.payment.PaymentContext) =
        ExamplePaymentProvider.signed(ctx.settings.require("webhookSecret"), type = "payment.invented_later", id = "evt_9").request

    override fun close() {
        if (gatewayHolder.isInitialized()) gatewayHolder.value.close()
        super.close()
    }
}

/** The positive control for shipping: unsigned push webhook re-fetched, two-step create that keeps its carrier object. */
class ExampleShippingContractTest : ShippingProviderContractTest() {
    private val carrierHolder = lazy { ExampleCarrierGateway() }

    override fun createProvider(): ShippingProvider = ExampleShippingProvider()

    override fun settingValues(provider: ShippingProvider): Map<String, Any?> =
        TestContexts.defaultValues(provider.settingsSchema()) + ("baseUrl" to carrierHolder.value.gateway.baseUrl)

    override val gatewayIsFake: Boolean get() = true

    override fun unsignedWebhookScenario(provider: ShippingProvider): UnsignedWebhookScenario {
        val carrier = carrierHolder.value
        val ctx = TestContexts.shipping(provider.id, TestContexts.settings(settingValues(provider)), vertx)
        return UnsignedWebhookScenario(ctx, carrier.gateway, carrier.webhook()) { carrier.trackState.set(it) }
    }

    override fun failedCreateScenario(provider: ShippingProvider): FailedCreateScenario {
        val carrier = carrierHolder.value
        val ctx = TestContexts.shipping(provider.id, TestContexts.settings(settingValues(provider)), vertx)
        return FailedCreateScenario(ctx, carrier.gateway, SampleData.createShipmentRequest(), { carrier.failBuy.set(true) })
    }

    override fun close() {
        if (carrierHolder.isInitialized()) carrierHolder.value.gateway.close()
        super.close()
    }
}
