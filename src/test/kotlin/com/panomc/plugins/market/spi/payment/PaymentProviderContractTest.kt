package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.PaymentTestData.eur
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier

class PaymentProviderContractTest {
    /** The smallest legal provider: only the abstract members. */
    private class Minimal : PaymentProvider {
        override val id = "minimal"
        override val descriptor = ProviderDescriptor(LocalizedText.of("Minimal"), LocalizedText.of("Minimal"), "credit-card")
        override fun settingsSchema(): SettingsSchema = settingsSchema { }
        override fun capabilities(settings: ProviderSettings) = PaymentCapabilities()
        override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult =
            StartPaymentResult.Redirect("https://g.example/x")
        override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult =
            InboundResult.ignored(HttpReply.text("OK"))
    }

    private val abstractMembers = setOf("getId", "getDescriptor", "settingsSchema", "capabilities", "startPayment", "handleInbound")

    @Test
    fun `only the six core members are abstract and every other method is a real JVM default method`() {
        val methods = PaymentProvider::class.java.declaredMethods.filter { !it.isSynthetic }
        val abstractOnes = methods.filter { Modifier.isAbstract(it.modifiers) }.map { it.name }.toSet()
        assertEquals(abstractMembers, abstractOnes)
        val withBody = methods.filter { !Modifier.isAbstract(it.modifiers) }
        assertTrue(withBody.size >= 14, "expected the optional hooks, found ${withBody.map { it.name }}")
        for (m in withBody) assertTrue(m.isDefault, "${m.name} has a body but is not a JVM default method (-jvm-default=enable missing?)")
        assertEquals(
            setOf(
                "validateSettings", "onSettingsSaved", "runAction", "productMetaSchema", "checkEligibility", "continuePayment",
                "queryPayment", "cancelPayment", "refund", "queryRefund", "chargeRecurring", "cancelSubscription",
                "resumeSubscription", "querySubscription", "retrySubscriptionCharge", "subscriptionPortal", "onFulfillment"
            ),
            withBody.map { it.name }.toSet()
        )
    }

    @Test
    fun `the default hooks answer as documented`() = runBlocking {
        val p: PaymentProvider = Minimal()
        val ctx = NoContext.value
        val attempt = PaymentTestData.attemptView()
        val sub = PaymentTestData.subscriptionView()

        assertTrue(p.validateSettings(ctx, NoSettings).ok)
        assertTrue(p.onSettingsSaved(ctx, null) is ActionResult.None)
        assertNull(p.productMetaSchema(NoSettings))
        assertTrue(p.checkEligibility(ctx, CheckoutSnapshot(PaymentTestData.order(emptyList()), PaymentTestData.buyer(), null, false)).eligible)

        assertTrue(p.queryPayment(ctx, QueryPaymentRequest(attempt, QueryReason.PANEL)).unsupported)
        val cancel = p.cancelPayment(ctx, CancelPaymentRequest(attempt))
        assertFalse(cancel.supported)
        assertFalse(cancel.cancelled)
        assertTrue(p.queryRefund(ctx, QueryRefundRequest(1, "k", null, attempt, eur(1))) is RefundResult.Unknown)
        assertTrue(p.resumeSubscription(ctx, ResumeSubscriptionRequest(sub)) is ResumeSubscriptionResult.Unsupported)
        assertTrue(p.querySubscription(ctx, QuerySubscriptionRequest(sub)).unsupported)
        assertTrue(p.retrySubscriptionCharge(ctx, QuerySubscriptionRequest(sub)).unsupported)
        assertTrue(p.subscriptionPortal(ctx, SubscriptionPortalRequest(sub, "https://x.example", PortalPurpose.MANAGE)) is SubscriptionPortalResult.Unsupported)
        p.onFulfillment(ctx, FulfillmentUpdate(attempt, "FULFILLED", PaymentTestData.buyer(), null))
    }

    @Test
    fun `the default hooks that cannot be answered safely refuse with UNSUPPORTED`() = runBlocking {
        val p: PaymentProvider = Minimal()
        val ctx = NoContext.value
        val attempt = PaymentTestData.attemptView()
        val order = PaymentTestData.order(emptyList())
        val sub = PaymentTestData.subscriptionView()

        suspend fun expectUnsupported(what: String, block: suspend () -> Unit) {
            val e = try {
                block(); null
            } catch (e: ProviderException) {
                e
            }
            assertEquals(ProviderErrorCode.UNSUPPORTED, e?.code, what)
        }
        expectUnsupported("runAction") { p.runAction(ctx, "x", JsonObject()) }
        expectUnsupported("continuePayment") { p.continuePayment(ctx, ContinuePaymentRequest(attempt, JsonObject(), PaymentTestData.buyer(), PaymentTestData.urls())) }
        expectUnsupported("refund") { p.refund(ctx, RefundRequest(1, "k", attempt, order, eur(1), false, emptyList(), null, 0)) }
        expectUnsupported("chargeRecurring") {
            p.chargeRecurring(ctx, RecurringChargeRequest(AttemptRef(1, "R", "t"), eur(1), sub, StoredPaymentMethod("t"), order, PaymentTestData.buyer(), "k", "https://n.example"))
        }
        // Deliberately not "local only": a gateway keeps charging when market ends the subscription locally.
        expectUnsupported("cancelSubscription") { p.cancelSubscription(ctx, CancelSubscriptionRequest(sub, false, null, null)) }
    }

    @Test
    fun `MarketExtension is an interface with a default paymentProviders and the SPI version constants exist`() {
        val ext = MarketExtension::class.java
        assertTrue(ext.isInterface)
        val payment = ext.getMethod("paymentProviders")
        assertTrue(payment.isDefault)
        assertTrue(Modifier.isAbstract(ext.getMethod("getSpiVersion").modifiers))
        assertEquals(1, MarketSpi.VERSION)
        assertEquals(1, MarketSpi.MIN_SUPPORTED)
        assertTrue(MarketSpi.MIN_SUPPORTED <= MarketSpi.VERSION)
        assertEquals("default", MarketSpi.DEFAULT_CHANNEL)
    }

    @Test
    fun `an extension that only supplies a version answers an empty provider list`() {
        val ext = object : MarketExtension {
            override val spiVersion = MarketSpi.VERSION
        }
        assertEquals(MarketSpi.VERSION, ext.spiVersion)
        assertEquals(emptyList<PaymentProvider>(), ext.paymentProviders())
    }

    @Test
    fun `a provider with the minimal members starts a payment and ignores an inbound request`() = runBlocking {
        val p: PaymentProvider = Minimal()
        val ctx = NoContext.value
        val started = p.startPayment(ctx, StartPaymentRequest(
            AttemptRef(5, "ABCDEFGHJKMNPQRSTVWX", "tok"), eur(1000), PaymentTestData.order(emptyList()), PaymentTestData.buyer(),
            null, null, null, PaymentTestData.urls(), "idem", "en-US", 1L, null
        ))
        assertEquals("REDIRECT", started.kind)
        val result = p.handleInbound(ctx, PaymentInboundRequest(
            com.panomc.plugins.market.spi.common.InboundRequest(
                com.panomc.plugins.market.spi.common.InboundKind.WEBHOOK, "default", "POST", null, emptyMap(), emptyMap(), null, ByteArray(0), "203.0.113.5", 1L
            ), null, null, null
        ))
        assertTrue(result.verified)
        assertEquals(emptyList<PaymentEvent>(), result.events)
        assertEquals(200, result.reply.status)
    }
}

/** A context is never touched by the default hooks; any use is a bug in the test. */
private object NoContext {
    val value: PaymentContext = java.lang.reflect.Proxy.newProxyInstance(
        PaymentContext::class.java.classLoader, arrayOf(PaymentContext::class.java)
    ) { _, method, _ -> throw AssertionError("the default hook touched ctx.${method.name}") } as PaymentContext
}

private object NoSettings : ProviderSettings {
    override fun string(key: String): String? = null
    override fun require(key: String): String = throw ProviderException(ProviderErrorCode.CONFIGURATION, "missing $key")
    override fun boolean(key: String, default: Boolean) = default
    override fun long(key: String): Long? = null
    override fun asJson(): JsonObject = JsonObject()
}
