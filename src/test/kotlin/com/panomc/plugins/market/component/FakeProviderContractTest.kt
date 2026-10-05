package com.panomc.plugins.market.component

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.testkit.ProviderContractTest
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.SignedSample
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakePayGateway
import com.panomc.plugins.marketfake.FakeProvider
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal abstract class FakeContract(private val providerId: String) : ProviderContractTest() {
    protected val gateway = lazy { FakePayGateway() }

    override fun createProvider(): PaymentProvider = FakeProvider(providerId)

    override fun settingValues(provider: PaymentProvider): Map<String, Any?> =
        TestContexts.defaultValues(provider.settingsSchema()) + mapOf("gatewayUrl" to gateway.value.baseUrl, "secret" to gateway.value.secret)

    override val gatewayIsFake: Boolean get() = true

    override fun startRequest(provider: PaymentProvider): StartPaymentRequest =
        SampleData.startRequest(Money(1234, "EUR"), providerId = provider.id)

    override fun signedNotification(ctx: PaymentContext): SignedSample {
        val data = JsonObject().put("reference", "ABCDEFGHJKMNPQRSTVWX").put("amount", "12.34").put("currency", "EUR")
        val request = gateway.value.inbound("payment.succeeded", data, "evt_contract")
        // the same notification with one more header / query parameter outside the signature
        val withUnsigned = com.panomc.plugins.market.spi.common.InboundRequest(
            InboundKind.WEBHOOK, "default", "POST", "utm=1", mapOf("utm" to listOf("1")), request.headers + ("x-extra" to listOf("1")),
            request.contentType, request.body, request.remoteIp, request.receivedAt
        )
        return SignedSample(request, withUnsigned)
    }

    override fun signedUnknownEvent(ctx: PaymentContext) =
        gateway.value.inbound("payment.invented_later", JsonObject(), "evt_unknown")

    override fun close() {
        if (gateway.isInitialized()) gateway.value.close()
        super.close()
    }
}

/**
 * 02 section 14.1 run against the fake provider plugin (17 section 6): the contract suite of the testkit for both
 * providers, with `FakePayGateway` as the gateway, so the start check really reaches a gateway and the signed
 * notifications are signed the way the provider verifies them. No check may be skipped.
 */
class FakeProviderContractTest {
    @Nested
    internal inner class Fake : FakeContract(FakeProvider.ID) {
        @Test
        fun `the signed notification really is verified`() {
            val ctx = TestContexts.payment(FakeProvider.ID, TestContexts.settings(settingValues(createProvider())), vertx)
            val result = blockingInbound(ctx)
            assertTrue(result)
        }

        private fun blockingInbound(ctx: PaymentContext): Boolean {
            val sample = signedNotification(ctx)
            val provider = createProvider()
            val outcome = kotlinx.coroutines.runBlocking {
                provider.handleInbound(ctx, com.panomc.plugins.market.spi.payment.PaymentInboundRequest(sample.request, null, null, null))
            }
            assertEquals("evt_contract", outcome.eventKey)
            return outcome.verified && outcome.events.size == 1
        }
    }

    @Nested
    internal inner class FakeEur : FakeContract(FakeProvider.ID_EUR)
}
