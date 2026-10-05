package com.panomc.plugins.market.component

import com.panomc.platform.PluginEventManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.FieldType
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ReadonlyValue
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.CancelPaymentRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryReason
import com.panomc.plugins.market.spi.payment.QueryRefundRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.RefundLine
import com.panomc.plugins.market.spi.payment.RefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.spi.payment.SubscriptionPlan
import com.panomc.plugins.market.spi.payment.SubscriptionView
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.spi.testkit.TestPaymentContext
import com.panomc.plugins.market.support.FakePayGateway
import com.panomc.plugins.market.support.FakePayGateway.Op
import com.panomc.plugins.market.support.FakePayGateway.Signature
import com.panomc.plugins.marketfake.FakeExtension
import com.panomc.plugins.marketfake.FakePlugin
import com.panomc.plugins.marketfake.FakeProvider
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * T1 test of the fake payment provider plugin (17 section 6) against the `FakePayGateway` simulator: the full
 * start -> webhook -> query -> refund -> cancel round trip, every row of the error mapping table, every inbound kind,
 * the capability / eligibility rules and the "inert unless the flag is set" rule of the plugin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FakeGatewayProviderTest {
    private val vertx: Vertx = Vertx.vertx()
    private lateinit var gateway: FakePayGateway
    private val provider = FakeProvider(FakeProvider.ID)
    private val eurProvider = FakeProvider(FakeProvider.ID_EUR)

    @BeforeEach
    fun startGateway() {
        gateway = FakePayGateway(vertx = vertx)
    }

    @AfterEach
    fun stopGateway() {
        gateway.close()
    }

    @AfterAll
    fun closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun ctx(
        extra: Map<String, Any?> = emptyMap(),
        providerId: String = FakeProvider.ID,
        testMode: Boolean = true,
        url: String = gateway.baseUrl
    ): TestPaymentContext =
        TestContexts.payment(providerId, TestContexts.settings(mapOf("gatewayUrl" to url, "secret" to gateway.secret) + extra), vertx, testMode)

    private fun start(amount: Money = Money(1234, "EUR"), providerId: String = FakeProvider.ID, key: String = "idem-1"): com.panomc.plugins.market.spi.payment.StartPaymentRequest {
        val base = SampleData.startRequest(amount, "https://shop.example", providerId)
        return com.panomc.plugins.market.spi.payment.StartPaymentRequest(
            base.attempt, base.amount, base.order, base.buyer, null, null, null, base.urls, key, "en-US", base.expiresAt, null
        )
    }

    private fun attempt(
        amount: Money = Money(1234, "EUR"),
        gatewayId: String? = null,
        paid: Money? = null,
        refunded: Long = 0
    ) = PaymentAttemptView(
        id = 5, reference = REFERENCE, token = "tok", status = "PENDING", amount = amount, orderId = 1, orderPublicId = REFERENCE,
        gatewayTransactionId = gatewayId, gatewayRefs = emptyMap(), providerData = null, testMode = true, createdAt = TestContexts.START_MS,
        expiresAt = null, subscription = null, paidAmount = paid, refundedAmount = Money(refunded, amount.currency), paidAt = paid?.let { TestContexts.START_MS }
    )

    private fun webhook(
        ctx: PaymentContext,
        type: String,
        data: JsonObject,
        id: String = gateway.nextEventId(),
        signature: Signature = Signature.VALID
    ): InboundResult = run { provider.handleInbound(ctx, PaymentInboundRequest(gateway.inbound(type, data, id, signature), null, null, null)) }

    private fun data(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    private fun refundRequest(
        attempt: PaymentAttemptView,
        amount: Money,
        full: Boolean = false,
        key: String = "refund-1",
        lines: List<RefundLine> = emptyList()
    ) = RefundRequest(1, key, attempt, SampleData.order(attempt.amount), amount, full, lines, null, TestContexts.START_MS)

    private fun target(event: PaymentEvent) = (event.target as PaymentTarget.Reference).reference

    // ---- the round trip ---------------------------------------------------------------------------------------------

    @Test
    fun `start, webhook, query, refund and cancel make a complete round trip`() {
        val ctx = ctx()
        // start
        val started = run { provider.startPayment(ctx, start()) }
        started as StartPaymentResult.Redirect
        assertEquals("${gateway.baseUrl}/pay/$REFERENCE", started.url)
        assertEquals("pay_1", started.gatewayTransactionId)
        assertEquals(mapOf("session" to "sess_1"), started.gatewayRefs)
        assertEquals(gateway.payments[REFERENCE]!!.id, started.gatewayTransactionId)

        // query while the buyer has not paid
        val pending = run { provider.queryPayment(ctx, QueryPaymentRequest(attempt(gatewayId = "pay_1"), QueryReason.RECONCILE)) }
        assertTrue(pending.events.single() is PaymentEvent.Pending)
        assertEquals(PendingReason.AWAITING_BUYER, (pending.events.single() as PaymentEvent.Pending).reason)

        // the buyer pays: signed webhook through the simulator's own state
        gateway.setStatus(REFERENCE, "paid")
        val result = webhook(ctx, "payment.succeeded", data("reference" to REFERENCE, "amount" to "12.34", "currency" to "EUR"), "evt_round")
        assertTrue(result.verified)
        assertEquals("evt_round", result.eventKey)
        assertEquals(200, result.reply.status)
        assertEquals("OK", String(result.reply.body))
        val succeeded = result.events.single() as PaymentEvent.Succeeded
        assertEquals(Money(1234, "EUR"), succeeded.paid)
        assertEquals(REFERENCE, target(succeeded))

        // query again: paid
        val paid = run { provider.queryPayment(ctx, QueryPaymentRequest(attempt(gatewayId = "pay_1"), QueryReason.PANEL)) }
        assertEquals(Money(1234, "EUR"), (paid.events.single() as PaymentEvent.Succeeded).paid)

        // partial refund
        val view = attempt(gatewayId = "pay_1", paid = Money(1234, "EUR"))
        val refund = run { provider.refund(ctx, refundRequest(view, Money(500, "EUR"))) }
        assertTrue(refund is RefundResult.Succeeded)
        assertEquals("ref_1", refund.gatewayRefundId)
        assertEquals(Money(500, "EUR"), refund.refundedAmount)
        val sent = JsonObject(gateway.requests(Op.REFUND).single().bodyText())
        assertEquals("pay_1", sent.getString("paymentId"))
        assertEquals("5.00", sent.getString("amount"))
        assertEquals("EUR", sent.getString("currency"))
        assertEquals("refund-1", gateway.requests(Op.REFUND).single().header("Idempotency-Key"))

        // a refund above what is still captured is refused by the gateway
        val tooMuch = assertThrows<ProviderException> {
            run { provider.refund(ctx, refundRequest(view, Money(1000, "EUR"), key = "refund-2")) }
        }
        assertEquals(ProviderErrorCode.GATEWAY_REJECTED, tooMuch.code)
        assertEquals("amount exceeds captured", tooMuch.adminMessage)

        // cancel: a paid payment cannot be cancelled ...
        val cancelPaid = run { provider.cancelPayment(ctx, CancelPaymentRequest(attempt(gatewayId = "pay_1"))) }
        assertTrue(cancelPaid.supported)
        assertFalse(cancelPaid.cancelled)

        // ... a fresh pending one can, and then reads as expired
        val other = SampleData.startRequest(Money(2000, "EUR"), "https://shop.example", "fake")
        val second = PaymentAttemptView(
            9, "ZYXWVUTSRQPNMKJHGFED", "tok2", "PENDING", Money(2000, "EUR"), 2, "ZYXWVUTSRQPNMKJHGFED", null, emptyMap(), null, true,
            TestContexts.START_MS, null, null, null, Money(0, "EUR"), null
        )
        run {
            provider.startPayment(
                ctx,
                com.panomc.plugins.market.spi.payment.StartPaymentRequest(
                    com.panomc.plugins.market.spi.payment.AttemptRef(9, "ZYXWVUTSRQPNMKJHGFED", "tok2"), Money(2000, "EUR"), other.order, other.buyer, null, null, null,
                    other.urls, "idem-2", "en-US", other.expiresAt, null
                )
            )
        }
        val cancelled = run { provider.cancelPayment(ctx, CancelPaymentRequest(second)) }
        assertTrue(cancelled.cancelled)
        val afterCancel = run { provider.queryPayment(ctx, QueryPaymentRequest(second, QueryReason.RECONCILE)) }
        assertTrue(afterCancel.events.single() is PaymentEvent.Expired)
    }

    // ---- start ------------------------------------------------------------------------------------------------------

    @Test
    fun `start sends the documented request`() {
        val ctx = ctx()
        val request = start()
        run { provider.startPayment(ctx, request) }
        val recorded = gateway.requests(Op.CREATE).single()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/payments", recorded.path)
        assertEquals("Bearer ${gateway.secret}", recorded.header("Authorization"))
        assertEquals("idem-1", recorded.header("Idempotency-Key"))
        val body = JsonObject(recorded.bodyText())
        assertEquals(REFERENCE, body.getString("reference"))
        assertEquals("12.34", body.getString("amount"))
        assertEquals("EUR", body.getString("currency"))
        assertEquals(request.urls.success, body.getString("returnSuccess"))
        assertEquals(request.urls.cancel, body.getString("returnCancel"))
        assertEquals(request.urls.notify, body.getString("notifyUrl"))
        assertFalse(body.containsKey("subscription"))
    }

    @Test
    fun `start sends a subscription plan and a zero-decimal amount as whole units`() {
        val ctx = ctx()
        val plan = SubscriptionPlan(3, "plan-key-1", "Monthly", Money(50000, "JPY"), IntervalUnit.MONTH, 1, null)
        val base = start(Money(50000, "JPY"))
        val request = com.panomc.plugins.market.spi.payment.StartPaymentRequest(
            base.attempt, base.amount, base.order, base.buyer, null, null, plan, base.urls, "idem-sub", "en-US", base.expiresAt, null
        )
        run { provider.startPayment(ctx, request) }
        val body = JsonObject(gateway.requests(Op.CREATE).single().bodyText())
        assertEquals("500", body.getString("amount"))
        assertEquals("JPY", body.getString("currency"))
        val sub = body.getJsonObject("subscription")
        assertEquals("plan-key-1", sub.getString("planKey"))
        assertEquals("MONTH", sub.getString("intervalUnit"))
        assertEquals(1, sub.getInteger("intervalCount"))
    }

    @Test
    fun `the same idempotency key gives the same gateway payment`() {
        val ctx = ctx()
        val a = run { provider.startPayment(ctx, start()) }
        val b = run { provider.startPayment(ctx, start()) }
        assertEquals(a.gatewayTransactionId, b.gatewayTransactionId)
        assertEquals(1, gateway.payments.size)
    }

    @Test
    fun `every start kind has its own result shape`() {
        for (kind in FakeProvider.StartKind.entries) {
            val ctx = ctx(mapOf("startKind" to kind.name))
            val result = run { provider.startPayment(ctx, start(key = "idem-${kind.name}")) }
            val payUrl = "${gateway.baseUrl}/pay/$REFERENCE"
            assertEquals("pay_${gateway.requests(Op.CREATE).size}", result.gatewayTransactionId, kind.name)
            assertEquals(payUrl, result.providerData!!.getString("payUrl"), kind.name)
            when (kind) {
                FakeProvider.StartKind.REDIRECT -> assertEquals(payUrl, (result as StartPaymentResult.Redirect).url)
                FakeProvider.StartKind.FORM_POST -> {
                    result as StartPaymentResult.FormPost
                    assertEquals(payUrl, result.actionUrl)
                    assertEquals(REFERENCE, result.fields["reference"])
                    assertEquals("12.34", result.fields["amount"])
                }
                FakeProvider.StartKind.IFRAME -> assertEquals(payUrl, (result as StartPaymentResult.Iframe).url)
                FakeProvider.StartKind.HTML -> {
                    result as StartPaymentResult.Html
                    assertTrue(result.document.contains(payUrl))
                    assertTrue(result.scriptOrigins.isEmpty() && result.frameOrigins.isEmpty() && result.connectOrigins.isEmpty())
                }
                FakeProvider.StartKind.INSTRUCTIONS -> {
                    result as StartPaymentResult.Instructions
                    assertEquals(listOf(REFERENCE, payUrl), result.fields.map { it.value })
                    assertFalse(result.buyerConfirms)
                }
                FakeProvider.StartKind.EMBEDDED -> {
                    result as StartPaymentResult.Embedded
                    assertEquals(payUrl, result.props.getString("payUrl"))
                    assertEquals(listOf("code"), result.fields.map { it.key })
                }
                FakeProvider.StartKind.COMPLETED -> {
                    val event = (result as StartPaymentResult.Completed).event
                    assertEquals(Money(1234, "EUR"), event.paid)
                    assertEquals(REFERENCE, target(event))
                }
            }
            // the browser-facing JSON of every kind is well formed
            assertEquals(kind.name, result.toPaymentStartJson("en-US", "https://shop.example/attempt/page").getString("kind"))
        }
    }

    @Test
    fun `an unknown startKind setting falls back to a redirect`() {
        val result = run { provider.startPayment(ctx(mapOf("startKind" to "NOPE")), start()) }
        assertTrue(result is StartPaymentResult.Redirect)
    }

    @Test
    fun `continuePayment redirects to the stored payment page`() {
        val ctx = ctx()
        val view = PaymentAttemptView(
            5, REFERENCE, "tok", "PENDING", Money(1234, "EUR"), 1, REFERENCE, "pay_1", emptyMap(), JsonObject().put("payUrl", "https://gateway.example/pay/x"),
            true, TestContexts.START_MS, null, null, null, Money(0, "EUR"), null
        )
        val result = run { provider.continuePayment(ctx, ContinuePaymentRequest(view, JsonObject(), SampleData.buyer(), SampleData.attemptUrls())) }
        assertEquals("https://gateway.example/pay/x", (result as StartPaymentResult.Redirect).url)
        val noData = assertThrows<ProviderException> {
            run { provider.continuePayment(ctx, ContinuePaymentRequest(attempt(), JsonObject(), SampleData.buyer(), SampleData.attemptUrls())) }
        }
        assertEquals(ProviderErrorCode.INVALID_REQUEST, noData.code)
    }

    @Test
    fun `a gateway answer without id or payUrl is rejected`() {
        val odd = FakeGateway.start(vertx)
        try {
            odd.on("POST", "/v1/payments") { Reply.json("""{"id":"pay_1"}""", 201) }
            val e = assertThrows<ProviderException> { run { provider.startPayment(ctx(url = odd.baseUrl), start()) } }
            assertEquals(ProviderErrorCode.GATEWAY_REJECTED, e.code)
        } finally {
            odd.close()
        }
    }

    // ---- error mapping ----------------------------------------------------------------------------------------------

    private class Mapped(val status: Int, val code: ProviderErrorCode, val retryable: Boolean, val admin: String?)

    private val mappingTable = listOf(
        Mapped(401, ProviderErrorCode.AUTHENTICATION, false, "bad key"),
        Mapped(429, ProviderErrorCode.RATE_LIMITED, true, "slow down"),
        Mapped(400, ProviderErrorCode.GATEWAY_REJECTED, false, "amount is wrong"),
        Mapped(402, ProviderErrorCode.GATEWAY_REJECTED, false, "card declined"),
        Mapped(422, ProviderErrorCode.GATEWAY_REJECTED, false, "unprocessable"),
        Mapped(500, ProviderErrorCode.GATEWAY_UNREACHABLE, true, "boom"),
        Mapped(502, ProviderErrorCode.GATEWAY_UNREACHABLE, true, "bad gateway"),
        Mapped(503, ProviderErrorCode.GATEWAY_UNREACHABLE, true, "maintenance")
    )

    @Test
    fun `status codes map to provider errors on every outbound operation`() {
        val ctx = ctx()
        val paidView = attempt(gatewayId = "pay_1", paid = Money(1234, "EUR"))
        val operations: List<Pair<Op, suspend () -> Any?>> = listOf(
            Op.CREATE to { provider.startPayment(ctx, start()) },
            Op.QUERY to { provider.queryPayment(ctx, QueryPaymentRequest(attempt(), QueryReason.RECONCILE)) },
            Op.CANCEL to { provider.cancelPayment(ctx, CancelPaymentRequest(attempt())) },
            Op.REFUND to { provider.refund(ctx, refundRequest(paidView, Money(100, "EUR"))) },
            Op.QUERY_REFUND to { provider.queryRefund(ctx, QueryRefundRequest(1, "k", "ref_1", paidView, Money(100, "EUR"))) },
            Op.CHARGE to { provider.chargeRecurring(ctx, recurringRequest()) },
            Op.CANCEL_SUBSCRIPTION to { provider.cancelSubscription(ctx, CancelSubscriptionRequest(subscriptionView(), false, null, null)) }
        )
        for ((op, call) in operations) for (row in mappingTable) {
            gateway.failNext(op, row.status, row.admin)
            val e = assertThrows<ProviderException>("$op ${row.status}") { run { call() } }
            assertEquals(row.code, e.code, "$op ${row.status}")
            assertEquals(row.retryable, e.retryable, "$op ${row.status}")
            assertEquals(row.admin, e.adminMessage, "$op ${row.status}")
        }
    }

    @Test
    fun `a gateway that is not listening is GATEWAY_UNREACHABLE and retryable`() {
        val dead = FakePayGateway(vertx = vertx)
        val url = dead.baseUrl
        dead.close()
        val e = assertThrows<ProviderException> { run { provider.startPayment(ctx(url = url), start()) } }
        assertEquals(ProviderErrorCode.GATEWAY_UNREACHABLE, e.code)
        assertTrue(e.retryable)
    }

    @Test
    fun `a gateway that never answers times out as GATEWAY_UNREACHABLE and retryable`() {
        gateway.hang(Op.CREATE)
        val started = System.nanoTime()
        val e = assertThrows<ProviderException> { run { provider.startPayment(ctx(mapOf("timeoutMs" to 300)), start()) } }
        assertEquals(ProviderErrorCode.GATEWAY_UNREACHABLE, e.code)
        assertTrue(e.retryable)
        assertTrue((System.nanoTime() - started) / 1_000_000L < 5_000, "the timeout setting was not honoured")
    }

    @Test
    fun `404 and 409 are answers, not errors, where the protocol defines them`() {
        val ctx = ctx()
        assertTrue(run { provider.queryPayment(ctx, QueryPaymentRequest(attempt(), QueryReason.RECONCILE)) }.unknown)
        assertEquals(RefundResult.Unknown::class, run { provider.queryRefund(ctx, QueryRefundRequest(1, "k", "ref_404", attempt(), Money(1, "EUR"))) }::class)
        // 404 on start is an ordinary 4xx: GATEWAY_REJECTED
        gateway.failNext(Op.CREATE, 404)
        assertEquals(ProviderErrorCode.GATEWAY_REJECTED, assertThrows<ProviderException> { run { provider.startPayment(ctx, start()) } }.code)
    }

    // ---- query ------------------------------------------------------------------------------------------------------

    @Test
    fun `query maps every gateway status`() {
        val ctx = ctx()
        run { provider.startPayment(ctx, start()) }
        fun events(): List<PaymentEvent> = run { provider.queryPayment(ctx, QueryPaymentRequest(attempt(gatewayId = "pay_1"), QueryReason.RECONCILE)) }.events

        gateway.setStatus(REFERENCE, "pending")
        assertTrue(events().single() is PaymentEvent.Pending)
        gateway.setStatus(REFERENCE, "paid", BigDecimal("12.34"))
        (events().single() as PaymentEvent.Succeeded).let {
            assertEquals(Money(1234, "EUR"), it.paid)
            assertEquals("pay_1", it.gatewayTransactionId)
        }
        gateway.setStatus(REFERENCE, "failed")
        (events().single() as PaymentEvent.Failed).let {
            assertEquals("failed", it.code)
            assertTrue(it.final)
        }
        gateway.setStatus(REFERENCE, "expired")
        assertTrue(events().single() is PaymentEvent.Expired)
        gateway.setStatus(REFERENCE, "review", BigDecimal("10.00"))
        (events().single() as PaymentEvent.NeedsReview).let {
            assertEquals(ReviewReason.OTHER, it.reason)
            assertEquals(Money(1000, "EUR"), it.received)
        }
        // a status the provider does not know is "no answer yet", never a failure
        gateway.setStatus(REFERENCE, "something-new")
        assertTrue(run { provider.queryPayment(ctx, QueryPaymentRequest(attempt(gatewayId = "pay_1"), QueryReason.RECONCILE)) }.unknown)
    }

    // ---- refunds ----------------------------------------------------------------------------------------------------

    private fun paidPayment(ctx: PaymentContext, amount: Money = Money(1234, "EUR")): PaymentAttemptView {
        run { provider.startPayment(ctx, start(amount)) }
        gateway.setStatus(REFERENCE, "paid", amount.toDecimal())
        return attempt(amount, "pay_1", amount)
    }

    @Test
    fun `refund results follow the gateway status`() {
        val ctx = ctx()
        val view = paidPayment(ctx)
        gateway.refundMode = FakePayGateway.RefundMode.PENDING
        assertTrue(run { provider.refund(ctx, refundRequest(view, Money(100, "EUR"), key = "k1")) } is RefundResult.Pending)
        gateway.refundMode = FakePayGateway.RefundMode.FAILED
        val failed = run { provider.refund(ctx, refundRequest(view, Money(100, "EUR"), key = "k2")) } as RefundResult.Failed
        assertEquals("refund_declined", failed.code)
        assertNull(failed.refundedAmount)
        gateway.refundMode = FakePayGateway.RefundMode.SUCCEEDED
        assertTrue(run { provider.refund(ctx, refundRequest(view, Money(100, "EUR"), key = "k3")) } is RefundResult.Succeeded)
    }

    @Test
    fun `queryRefund reads the gateway and knows when it has no answer`() {
        val ctx = ctx()
        val view = paidPayment(ctx)
        gateway.refundMode = FakePayGateway.RefundMode.PENDING
        val refund = run { provider.refund(ctx, refundRequest(view, Money(100, "EUR"))) }
        assertTrue(refund is RefundResult.Pending)
        val status = run { provider.queryRefund(ctx, QueryRefundRequest(1, "refund-1", refund.gatewayRefundId, view, Money(100, "EUR"))) }
        assertTrue(status is RefundResult.Pending)
        assertEquals(refund.gatewayRefundId, status.gatewayRefundId)
        assertTrue(run { provider.queryRefund(ctx, QueryRefundRequest(1, "refund-1", null, view, Money(100, "EUR"))) } is RefundResult.Unknown)
        assertEquals(1, gateway.requests(Op.QUERY_REFUND).size) // the null id never reached the gateway
    }

    @Test
    fun `a full refund returns everything the gateway still holds, surcharge included`() {
        val ctx = ctx(mapOf("buyerMayPayMore" to true))
        // the buyer paid 12.50 for a 12.34 order, 1.00 of it already refunded
        run { provider.startPayment(ctx, start()) }
        gateway.setStatus(REFERENCE, "paid", BigDecimal("12.50"))
        val view = attempt(Money(1234, "EUR"), "pay_1", Money(1250, "EUR"), refunded = 100)
        gateway.payments[REFERENCE]!!.refunded = BigDecimal("1.00")
        val result = run { provider.refund(ctx, refundRequest(view, Money(1234, "EUR"), full = true)) }
        assertTrue(result is RefundResult.Succeeded)
        assertEquals("11.50", JsonObject(gateway.requests(Op.REFUND).single().bodyText()).getString("amount"))
        assertEquals(Money(1150, "EUR"), result.refundedAmount)
    }

    @Test
    fun `refund without a gateway transaction id is an invalid request that never reaches the gateway`() {
        val e = assertThrows<ProviderException> { run { provider.refund(ctx(), refundRequest(attempt(), Money(100, "EUR"))) } }
        assertEquals(ProviderErrorCode.INVALID_REQUEST, e.code)
        assertTrue(gateway.requests(Op.REFUND).isEmpty())
    }

    @Test
    fun `refund lines are sent only when the provider is set to PER_LINE`() {
        val lines = listOf(RefundLine(1, null, 1, Money(300, "EUR")))
        val ctx = ctx()
        val view = paidPayment(ctx)
        run { provider.refund(ctx, refundRequest(view, Money(300, "EUR"), key = "a", lines = lines)) }
        assertFalse(JsonObject(gateway.requests(Op.REFUND).last().bodyText()).containsKey("lines"))

        val perLine = ctx(mapOf("refundSupport" to "PER_LINE"))
        run { provider.refund(perLine, refundRequest(view, Money(300, "EUR"), key = "b", lines = lines)) }
        val sent = JsonObject(gateway.requests(Op.REFUND).last().bodyText()).getJsonArray("lines").getJsonObject(0)
        assertEquals(1L, sent.getLong("orderItemId"))
        assertEquals(1, sent.getInteger("quantity"))
        assertEquals("3.00", sent.getString("amount"))
    }

    // ---- recurring --------------------------------------------------------------------------------------------------

    private fun subscriptionView(gatewayId: String? = "sub_9", periodEnd: Long? = null) = SubscriptionView(
        3, "ACTIVE", gatewayId, null, Money(500, "EUR"), IntervalUnit.MONTH, 1, periodEnd, null, true
    )

    private fun recurringRequest(key: String = "renew-1") = RecurringChargeRequest(
        com.panomc.plugins.market.spi.payment.AttemptRef(8, "RENEWALREFERENCE0001", "tok8"), Money(500, "EUR"), subscriptionView(),
        StoredPaymentMethod("tok_stored"), SampleData.order(Money(500, "EUR")), SampleData.buyer(), key, "https://shop.example/notify"
    )

    @Test
    fun `a merchant-initiated charge sends the stored method and maps the answer`() {
        val ctx = ctx()
        for ((status, type) in listOf("paid" to PaymentEvent.Succeeded::class, "pending" to PaymentEvent.Pending::class, "failed" to PaymentEvent.Failed::class)) {
            gateway.chargeStatus = status
            val result = run { provider.chargeRecurring(ctx, recurringRequest("renew-$status")) }
            val event = result.events.single()
            assertEquals(type, event::class, status)
            assertEquals("RENEWALREFERENCE0001", target(event))
        }
        val body = JsonObject(gateway.requests(Op.CHARGE).first().bodyText())
        assertEquals("RENEWALREFERENCE0001", body.getString("reference"))
        assertEquals("5.00", body.getString("amount"))
        assertEquals("tok_stored", body.getString("storedMethod"))
        assertEquals("renew-paid", gateway.requests(Op.CHARGE).first().header("Idempotency-Key"))
        gateway.chargeStatus = "paid"
        assertEquals("chg_4", (run { provider.chargeRecurring(ctx, recurringRequest("renew-again")) }.events.single()).gatewayTransactionId)
    }

    @Test
    fun `cancelling a subscription at period end schedules, cancelling now cancels`() {
        val ctx = ctx(providerId = FakeProvider.ID_EUR)
        val scheduled = run { eurProvider.cancelSubscription(ctx, CancelSubscriptionRequest(subscriptionView(), true, null, null)) }
        assertTrue((scheduled as CancelSubscriptionResult.Scheduled).endsAt > TestContexts.START_MS)
        assertEquals(true, JsonObject(gateway.requests(Op.CANCEL_SUBSCRIPTION).last().bodyText()).getBoolean("atPeriodEnd"))
        val now = run { eurProvider.cancelSubscription(ctx, CancelSubscriptionRequest(subscriptionView(), false, null, null)) }
        assertNull((now as CancelSubscriptionResult.Cancelled).effectiveAt)
        assertEquals(false, JsonObject(gateway.requests(Op.CANCEL_SUBSCRIPTION).last().bodyText()).getBoolean("atPeriodEnd"))
        assertEquals(setOf("sub_9"), gateway.cancelledSubscriptions.keys)
        val e = assertThrows<ProviderException> {
            run { eurProvider.cancelSubscription(ctx, CancelSubscriptionRequest(subscriptionView(gatewayId = null), false, null, null)) }
        }
        assertEquals(ProviderErrorCode.INVALID_REQUEST, e.code)
    }

    // ---- webhooks ---------------------------------------------------------------------------------------------------

    @Test
    fun `every documented event type becomes its normalized event`() {
        val ctx = ctx()
        fun one(type: String, data: JsonObject, id: String = "evt_$type"): Pair<InboundResult, PaymentEvent> {
            val result = webhook(ctx, type, data, id)
            assertTrue(result.verified, type)
            assertEquals(id, result.eventKey, type)
            return result to result.events.single()
        }
        val ref = "reference" to REFERENCE

        val (_, succeeded) = one(
            "payment.succeeded",
            data(
                ref, "amount" to "12.34", "currency" to "EUR", "fee" to "0.50", "paymentId" to "pay_77",
                "storedMethod" to data("token" to "tok_1", "label" to "Visa 4242", "expiresAt" to 99L),
                "subscription" to data("id" to "sub_1", "periodStart" to 1000L, "periodEnd" to 2000L)
            )
        )
        succeeded as PaymentEvent.Succeeded
        assertEquals(Money(1234, "EUR"), succeeded.paid)
        assertEquals(Money(50, "EUR"), succeeded.gatewayFee)
        assertEquals("pay_77", succeeded.gatewayTransactionId)
        assertEquals("tok_1", succeeded.storedMethod!!.token)
        assertEquals("Visa 4242", succeeded.storedMethod!!.label)
        assertEquals(99L, succeeded.storedMethod!!.expiresAt)
        assertEquals("sub_1", succeeded.subscription!!.gatewaySubscriptionId)
        assertEquals(GatewaySubscriptionStatus.ACTIVE, succeeded.subscription!!.status)
        assertEquals(1000L, succeeded.subscription!!.currentPeriodStart)
        assertEquals(2000L, succeeded.subscription!!.currentPeriodEnd)

        val (_, plain) = one("payment.succeeded", data(ref, "amount" to 5, "currency" to "EUR", "storedMethod" to "tok_plain"), "evt_plain")
        assertEquals(Money(500, "EUR"), (plain as PaymentEvent.Succeeded).paid)
        assertEquals("tok_plain", plain.storedMethod!!.token)

        (one("payment.pending", data(ref, "reason" to "AWAITING_BANK")).second as PaymentEvent.Pending).let { assertEquals(PendingReason.AWAITING_BANK, it.reason) }
        (one("payment.pending", data(ref), "evt_p2").second as PaymentEvent.Pending).let { assertEquals(PendingReason.AWAITING_BUYER, it.reason) }
        (one("payment.pending", data(ref, "reason" to "FROM_THE_FUTURE"), "evt_p3").second as PaymentEvent.Pending).let { assertEquals(PendingReason.OTHER, it.reason) }

        (one("payment.failed", data(ref, "code" to "card_declined", "message" to "Declined", "final" to true)).second as PaymentEvent.Failed).let {
            assertEquals("card_declined", it.code)
            assertEquals("Declined", it.message)
            assertTrue(it.final)
        }
        (one("payment.failed", data(ref), "evt_f2").second as PaymentEvent.Failed).let {
            assertEquals("failed", it.code)
            assertFalse(it.final)
        }
        assertTrue(one("payment.cancelled", data(ref)).second is PaymentEvent.Cancelled)
        assertTrue(one("payment.expired", data(ref)).second is PaymentEvent.Expired)
        (one("payment.review", data(ref, "reason" to "UNDERPAID", "amount" to "3.00", "currency" to "EUR")).second as PaymentEvent.NeedsReview).let {
            assertEquals(ReviewReason.UNDERPAID, it.reason)
            assertEquals(Money(300, "EUR"), it.received)
        }

        (one("refund.updated", data(ref, "state" to "SUCCEEDED", "amount" to "5.00", "currency" to "EUR", "refundId" to "ref_5", "idempotencyKey" to "refund-1")).second as PaymentEvent.RefundUpdated).let {
            assertEquals(RefundState.SUCCEEDED, it.state)
            assertEquals(Money(500, "EUR"), it.amount)
            assertEquals("ref_5", it.gatewayRefundId)
            assertEquals("refund-1", it.refundKey)
        }
        (one("refund.updated", data(ref, "state" to "FAILED"), "evt_r2").second as PaymentEvent.RefundUpdated).let {
            assertEquals(RefundState.FAILED, it.state)
            assertNull(it.amount)
        }
        (one("dispute.updated", data(ref, "state" to "OPENED", "amount" to "12.34", "currency" to "EUR", "disputeId" to "dp_1", "reason" to "fraudulent")).second as PaymentEvent.DisputeUpdated).let {
            assertEquals(DisputeState.OPENED, it.state)
            assertEquals("dp_1", it.gatewayDisputeId)
            assertEquals(Money(1234, "EUR"), it.amount)
            assertEquals("fraudulent", it.reason)
        }

        val (_, renewed) = one(
            "subscription.renewed",
            data("subscriptionId" to "sub_1", "amount" to "5.00", "currency" to "EUR", "periodStart" to 10L, "periodEnd" to 20L, "paymentId" to "pay_renew")
        )
        renewed as PaymentEvent.SubscriptionRenewed
        assertEquals(PaymentTarget.Subscription::class, renewed.target::class)
        assertEquals("sub_1", (renewed.target as PaymentTarget.Subscription).gatewaySubscriptionId)
        assertEquals(Money(500, "EUR"), renewed.paid)
        assertEquals(10L, renewed.periodStart)
        assertEquals(20L, renewed.periodEnd)
        assertEquals("pay_renew", renewed.gatewayTransactionId)
        // without a paymentId the event id keeps the renewal de-duplicable
        val (_, renewed2) = one("subscription.renewed", data("subscriptionId" to "sub_1", "amount" to "5.00", "currency" to "EUR"), "evt_renew2")
        assertEquals("evt_renew2", renewed2.gatewayTransactionId)

        (one("subscription.payment_failed", data("subscriptionId" to "sub_1", "final" to true, "attemptCount" to 3, "nextRetryAt" to 55L)).second as PaymentEvent.SubscriptionPaymentFailed).let {
            assertTrue(it.final)
            assertEquals(3, it.attemptCount)
            assertEquals(55L, it.nextRetryAt)
        }
        (one("subscription.updated", data("subscriptionId" to "sub_1", "status" to "CANCEL_SCHEDULED", "endsAt" to 777L)).second as PaymentEvent.SubscriptionUpdated).let {
            assertEquals("sub_1", it.state.gatewaySubscriptionId)
            assertEquals(GatewaySubscriptionStatus.CANCEL_SCHEDULED, it.state.status)
            assertEquals(777L, it.state.endsAt)
        }
    }

    @Test
    fun `an unknown event type is acknowledged and ignored`() {
        val result = webhook(ctx(), "payment.invented_later", data("reference" to REFERENCE), "evt_x")
        assertTrue(result.verified)
        assertTrue(result.events.isEmpty())
        assertEquals(200, result.reply.status)
        assertNull(result.eventKey)
    }

    @Test
    fun `a webhook with a bad, missing or stale signature is rejected with 400 and no events`() {
        val ctx = ctx()
        val body = data("reference" to REFERENCE, "amount" to "12.34", "currency" to "EUR")
        for (mode in listOf(Signature.INVALID, Signature.MISSING, Signature.STALE)) {
            val result = webhook(ctx, "payment.succeeded", body, signature = mode)
            assertFalse(result.verified, mode.name)
            assertTrue(result.events.isEmpty(), mode.name)
            assertEquals(400, result.reply.status, mode.name)
            assertEquals("bad signature", String(result.reply.body), mode.name)
            assertNull(result.eventKey, mode.name)
        }
        // signed with the right secret but received long after: a replay
        val replay = gateway.inbound("payment.succeeded", body, "evt_old", Signature.VALID, TestContexts.START_MS - 3_600_000L)
        val replayed = run { provider.handleInbound(ctx, PaymentInboundRequest(replay, null, null, null)) }
        assertFalse(replayed.verified)
        // a body tampered with after signing
        val good = gateway.inbound("payment.succeeded", body, "evt_t")
        val tampered = InboundRequest(
            InboundKind.WEBHOOK, "default", "POST", null, emptyMap(), good.headers, good.contentType,
            String(good.body).replace("12.34", "99.99").toByteArray(), good.remoteIp, good.receivedAt
        )
        val result = run { provider.handleInbound(ctx, PaymentInboundRequest(tampered, null, null, null)) }
        assertFalse(result.verified)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `signed but malformed events are rejected without throwing`() {
        val ctx = ctx()
        fun signed(raw: String): InboundResult =
            run { provider.handleInbound(ctx, PaymentInboundRequest(gateway.inbound("x", JsonObject(), rawBody = raw.toByteArray()), null, null, null)) }
        for (raw in listOf(
            "not json", "[]", """{"id":"e","data":{}}""", """{"id":"e","type":"payment.succeeded","data":{}}""",
            """{"id":"e","type":"payment.succeeded","data":{"reference":"R","amount":"abc","currency":"EUR"}}""",
            """{"id":"e","type":"payment.succeeded","data":{"reference":"R","amount":"1.00","currency":"ZZZ"}}""",
            """{"id":"e","type":"refund.updated","data":{"reference":"R","state":"NOT_A_STATE"}}""",
            """{"id":"e","type":"payment.succeeded","data":"oops"}"""
        )) {
            val result = signed(raw)
            assertFalse(result.verified, raw)
            assertTrue(result.events.isEmpty(), raw)
            assertEquals(400, result.reply.status, raw)
        }
    }

    // ---- NOTIFY / RETURN --------------------------------------------------------------------------------------------

    private fun notify(ctx: PaymentContext, body: String, attempt: PaymentAttemptView? = attempt()): InboundResult {
        val http = InboundRequest(
            InboundKind.NOTIFY, "default", "POST", null, emptyMap(), mapOf("content-type" to listOf("application/x-www-form-urlencoded")),
            "application/x-www-form-urlencoded", body.toByteArray(), "203.0.113.9", TestContexts.START_MS
        )
        return run { provider.handleInbound(ctx, PaymentInboundRequest(http, attempt, null, null)) }
    }

    private fun browserReturn(ctx: PaymentContext, outcome: ReturnOutcome, attempt: PaymentAttemptView? = attempt()): InboundResult {
        val http = InboundRequest(InboundKind.RETURN, "default", "GET", null, emptyMap(), emptyMap(), null, ByteArray(0), "203.0.113.9", TestContexts.START_MS)
        return run { provider.handleInbound(ctx, PaymentInboundRequest(http, attempt, outcome, null)) }
    }

    @Test
    fun `an unsigned notification is answered by re-fetching the payment`() {
        val ctx = ctx()
        run { provider.startPayment(ctx, start()) }
        gateway.setStatus(REFERENCE, "paid")
        val result = notify(ctx, "id=pay_1")
        assertTrue(result.verified)
        assertEquals(200, result.reply.status)
        assertEquals(Money(1234, "EUR"), (result.events.single() as PaymentEvent.Succeeded).paid)
        assertEquals(1, gateway.requests(Op.QUERY).size)
        assertEquals("/v1/payments/$REFERENCE", gateway.requests(Op.QUERY).single().path)
        assertNull(result.eventKey) // an unsigned trigger is never de-duplicated by key
    }

    @Test
    fun `a notification about a payment the gateway does not know is retried later`() {
        val result = notify(ctx(), "id=pay_404")
        assertTrue(result.events.isEmpty())
        assertEquals(503, result.reply.status)
    }

    @Test
    fun `a notification when the gateway is down asks for a retry and carries no events`() {
        val dead = FakePayGateway(vertx = vertx)
        val url = dead.baseUrl
        dead.close()
        val result = notify(ctx(url = url), "id=pay_1")
        assertFalse(result.verified)
        assertTrue(result.events.isEmpty())
        assertEquals(503, result.reply.status)
    }

    @Test
    fun `notifications without an id, without an attempt or with a broken body are rejected and never query`() {
        val ctx = ctx()
        for (body in listOf("", "x=1", "id=", "%ZZ", "id=%")) {
            val result = notify(ctx, body)
            assertFalse(result.verified, body)
            assertEquals(400, result.reply.status, body)
        }
        assertEquals(400, notify(ctx, "id=pay_1", attempt = null).reply.status)
        assertTrue(gateway.requests(Op.QUERY).isEmpty())
    }

    @Test
    fun `a browser return is never trusted and success is believed only after our own query`() {
        val ctx = ctx()
        run { provider.startPayment(ctx, start()) }

        // the buyer claims success but the gateway still says pending: no Succeeded event
        val forged = browserReturn(ctx, ReturnOutcome.SUCCESS)
        assertTrue(forged.events.none { it is PaymentEvent.Succeeded })
        assertTrue(forged.events.single() is PaymentEvent.Pending)
        assertTrue(forged.reply.orderPage)
        assertEquals(1, gateway.requests(Op.QUERY).size)

        // the gateway has the money: now the query's answer is the event
        gateway.setStatus(REFERENCE, "paid")
        val real = browserReturn(ctx, ReturnOutcome.SUCCESS)
        assertTrue(real.verified)
        assertEquals(Money(1234, "EUR"), (real.events.single() as PaymentEvent.Succeeded).paid)
        assertTrue(real.reply.orderPage)

        // RESULT (single callback URL) queries too
        assertTrue(browserReturn(ctx, ReturnOutcome.RESULT).events.single() is PaymentEvent.Succeeded)
    }

    @Test
    fun `other return outcomes never query and never produce events`() {
        val ctx = ctx()
        run { provider.startPayment(ctx, start()) }
        gateway.setStatus(REFERENCE, "paid")
        for (outcome in listOf(ReturnOutcome.CANCEL, ReturnOutcome.PENDING, ReturnOutcome.STEP)) {
            val result = browserReturn(ctx, outcome)
            assertTrue(result.events.isEmpty(), outcome.name)
            assertTrue(result.reply.orderPage, outcome.name)
        }
        assertTrue(gateway.requests(Op.QUERY).isEmpty())
    }

    @Test
    fun `a return for a payment the gateway does not know or cannot reach yields no events and still lands on the order page`() {
        val unknown = browserReturn(ctx(), ReturnOutcome.SUCCESS)
        assertTrue(unknown.events.isEmpty())
        assertTrue(unknown.reply.orderPage)

        val dead = FakePayGateway(vertx = vertx)
        val url = dead.baseUrl
        dead.close()
        val unreachable = browserReturn(ctx(url = url), ReturnOutcome.SUCCESS)
        assertFalse(unreachable.verified)
        assertTrue(unreachable.events.isEmpty())
        assertTrue(unreachable.reply.orderPage)
        assertEquals(400, browserReturn(ctx(), ReturnOutcome.SUCCESS, attempt = null).reply.status)
    }

    @Test
    fun `an unconfigured provider answers every kind of inbound traffic with CONFIGURATION`() {
        val empty = TestContexts.payment(FakeProvider.ID, TestContexts.settings(), vertx)
        for (kind in InboundKind.entries) {
            val http = InboundRequest(kind, "default", "POST", null, emptyMap(), emptyMap(), null, ByteArray(0), "203.0.113.9", TestContexts.START_MS)
            val e = assertThrows<ProviderException>(kind.name) { run { provider.handleInbound(empty, PaymentInboundRequest(http, attempt(), ReturnOutcome.SUCCESS, null)) } }
            assertEquals(ProviderErrorCode.CONFIGURATION, e.code, kind.name)
        }
    }

    // ---- capabilities, schema, eligibility --------------------------------------------------------------------------

    @Test
    fun `fake and fake-eur declare the documented defaults`() {
        val settings = TestContexts.settings()
        val fake = provider.capabilities(settings)
        assertNull(fake.currencies)
        assertEquals(RefundSupport.PARTIAL, fake.refund)
        assertEquals(RecurringSupport.MERCHANT_INITIATED, fake.recurring)
        assertTrue(fake.statusQuery && fake.cancelPending && fake.disputeEvents && fake.mixedCredit && fake.guests && fake.physicalGoods)
        assertFalse(fake.buyerMayPayMore)
        assertEquals(WebhookSetup.MANUAL_URL, fake.webhookSetup)
        assertEquals(TestModeSupport.FLAG, fake.testMode)
        assertNull(fake.minAmount)

        val eur = eurProvider.capabilities(settings)
        assertEquals(setOf("EUR"), eur.currencies)
        assertEquals(RefundSupport.FULL_ONLY, eur.refund)
        assertEquals(RecurringSupport.GATEWAY_MANAGED, eur.recurring)
        assertFalse(eur.statusQuery)
        assertFalse(eur.mixedCredit)
        assertFalse(eur.guests)
        assertEquals(Money(100, "EUR"), eur.minAmount)
        assertEquals(Money(50_000, "EUR"), eur.maxAmount)
        assertEquals(TestModeSupport.FLAG, eur.testMode)
    }

    @Test
    fun `settings override the default capabilities and invalid values are ignored`() {
        val overridden = provider.capabilities(
            TestContexts.settings(mapOf("refundSupport" to "PER_LINE", "recurring" to "NONE", "statusQuery" to false, "buyerMayPayMore" to true))
        )
        assertEquals(RefundSupport.PER_LINE, overridden.refund)
        assertEquals(RecurringSupport.NONE, overridden.recurring)
        assertFalse(overridden.statusQuery)
        assertTrue(overridden.buyerMayPayMore)
        val eur = eurProvider.capabilities(TestContexts.settings(mapOf("refundSupport" to "NONE", "statusQuery" to true)))
        assertEquals(RefundSupport.NONE, eur.refund)
        assertTrue(eur.statusQuery)
        val bad = provider.capabilities(TestContexts.settings(mapOf("refundSupport" to "BOGUS", "recurring" to "")))
        assertEquals(RefundSupport.PARTIAL, bad.refund)
        assertEquals(RecurringSupport.MERCHANT_INITIATED, bad.recurring)
    }

    @Test
    fun `the descriptor is labelled test-only and unverified`() {
        for (p in listOf(provider, eurProvider)) {
            assertEquals("Fake gateway (TEST ONLY)", p.descriptor.displayName.fallback)
            assertEquals(com.panomc.plugins.market.spi.common.Verification.UNVERIFIED, p.descriptor.verification)
            assertTrue(p.descriptor.displayName.resolve("tr").isNotBlank() && p.descriptor.displayName.resolve("ru").isNotBlank())
        }
    }

    @Test
    fun `the schema declares the documented settings and only the secret is secret`() {
        for (p in listOf(provider, eurProvider)) {
            val schema = p.settingsSchema()
            assertEquals(
                listOf("gatewayUrl", "secret", "startKind", "refundSupport", "recurring", "statusQuery", "buyerMayPayMore", "timeoutMs", "webhook"),
                schema.fields.map { it.key }
            )
            assertEquals(setOf("secret"), schema.secretKeys)
            assertEquals(FieldType.URL, schema.field("gatewayUrl")!!.type)
            assertTrue(schema.field("gatewayUrl")!!.required && schema.field("secret")!!.required)
            assertEquals(FieldType.PASSWORD, schema.field("secret")!!.type)
            assertEquals(
                listOf("REDIRECT", "FORM_POST", "IFRAME", "HTML", "INSTRUCTIONS", "EMBEDDED", "COMPLETED"),
                schema.field("startKind")!!.options.map { it.value }
            )
            assertEquals("REDIRECT", schema.field("startKind")!!.default)
            assertTrue(schema.field("webhook")!!.readonly is ReadonlyValue.WebhookUrl)
            assertEquals(listOf("test-connection"), schema.actions.map { it.id })
            schema.toJson { "https://shop.example/webhook" }
        }
        assertEquals("PARTIAL", provider.settingsSchema().field("refundSupport")!!.default)
        assertEquals("FULL_ONLY", eurProvider.settingsSchema().field("refundSupport")!!.default)
        assertEquals(true, provider.settingsSchema().field("statusQuery")!!.default)
        assertEquals(false, eurProvider.settingsSchema().field("statusQuery")!!.default)
    }

    @Test
    fun `the fake gateway can only be used in test mode`() {
        val snapshot = CheckoutSnapshot(SampleData.order(Money(1000, "EUR")), SampleData.buyer(), null, false)
        for (p in listOf(provider, eurProvider)) {
            val live = p.checkEligibility(ctx(testMode = false, providerId = p.id), snapshot)
            assertFalse(live.eligible)
            assertEquals("FAKE_REQUIRES_TEST_MODE", live.code)
            assertNotNull(live.reason)
            assertTrue(p.checkEligibility(ctx(testMode = true, providerId = p.id), snapshot).eligible)
        }
    }

    @Test
    fun `validateSettings checks the url and the secret`() {
        val ok = run { provider.validateSettings(ctx(), ctx().settings) }
        assertTrue(ok.ok)
        for (url in listOf("not a url", "ftp://host/x", "javascript:alert(1)", "http://")) {
            val bad = run { provider.validateSettings(ctx(), TestContexts.settings(mapOf("gatewayUrl" to url, "secret" to "s"))) }
            assertFalse(bad.ok, url)
            assertTrue(bad.fieldErrors.containsKey("gatewayUrl"), url)
        }
        val noSecret = run { provider.validateSettings(ctx(), TestContexts.settings(mapOf("gatewayUrl" to "https://x.example"))) }
        assertTrue(noSecret.fieldErrors.containsKey("secret"))
        assertEquals(0, gateway.requests(Op.PING).size) // validation does no I/O
    }

    @Test
    fun `test-connection pings the gateway and reports success or failure`() {
        val ctx = ctx()
        val ok = run { provider.runAction(ctx, "test-connection", JsonObject()) } as ActionResult.Message
        assertTrue(ok.success)
        assertEquals("/v1/ping", gateway.requests(Op.PING).single().path)
        assertEquals("Bearer ${gateway.secret}", gateway.requests(Op.PING).single().header("Authorization"))

        gateway.failNext(Op.PING, 500)
        assertFalse((run { provider.runAction(ctx, "test-connection", JsonObject()) } as ActionResult.Message).success)

        val wrongKey = TestContexts.payment(FakeProvider.ID, TestContexts.settings(mapOf("gatewayUrl" to gateway.baseUrl, "secret" to "wrong")), vertx)
        assertFalse((run { provider.runAction(wrongKey, "test-connection", JsonObject()) } as ActionResult.Message).success)

        val unknown = assertThrows<ProviderException> { run { provider.runAction(ctx, "nope", JsonObject()) } }
        assertEquals(ProviderErrorCode.UNSUPPORTED, unknown.code)
    }

    @Test
    fun `the secret never reaches the provider log`() {
        gateway.close()
        gateway = FakePayGateway(secret = "SECRET-never-log-0123456789", vertx = vertx)
        val live = TestContexts.payment(
            FakeProvider.ID,
            TestContexts.settings(mapOf("gatewayUrl" to gateway.baseUrl, "secret" to "SECRET-never-log-0123456789")), vertx, true
        )
        val view = paidPayment(live)
        run { provider.queryPayment(live, QueryPaymentRequest(view, QueryReason.PANEL)) }
        run { provider.refund(live, refundRequest(view, Money(100, "EUR"))) }
        run { provider.cancelPayment(live, CancelPaymentRequest(view)) }
        gateway.failNext(Op.QUERY, 401)
        runCatching { run { provider.queryPayment(live, QueryPaymentRequest(view, QueryReason.PANEL)) } }
        webhook(live, "payment.pending", data("reference" to REFERENCE))
        assertTrue(live.recordedLog.exchanges.isNotEmpty())
        assertFalse(live.recordedLog.everything().contains("SECRET-never-log-0123456789"))
    }

    // ---- the plugin -------------------------------------------------------------------------------------------------

    @Test
    fun `the extension exposes both providers at the current SPI version`() {
        val extension = FakeExtension()
        assertEquals(MarketSpi.VERSION, extension.spiVersion)
        assertEquals(listOf("fake", "fake-eur"), extension.paymentProviders().map { it.id })
        assertTrue(extension.shippingProviders().isEmpty())
    }

    @Nested
    inner class PluginSwitch {
        private val manager = PluginEventManager()
        private val installed = ArrayList<PanoPlugin>()
        private val flag = FakePlugin.ENABLE_PROPERTY
        private var previous: String? = null

        @BeforeEach
        fun remember() {
            previous = System.getProperty(flag)
        }

        @AfterEach
        fun restore() {
            if (previous == null) System.clearProperty(flag) else System.setProperty(flag, previous!!)
            for (p in installed) internal("unregisterPlugin", p)
            installed.clear()
        }

        private fun internal(name: String, vararg args: Any) {
            val m = PluginEventManager::class.java.declaredMethods.single { it.name.startsWith(name) && it.parameterCount == args.size }
            m.isAccessible = true
            m.invoke(manager, *args)
        }

        private fun plugin(): FakePlugin {
            val plugin = FakePlugin()
            for ((name, value) in listOf("pluginId" to "pano-plugin-market-fake", "pluginEventManager" to manager)) {
                val field = PanoPlugin::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(plugin, value)
            }
            AnnotationConfigApplicationContext().use { context ->
                context.refresh()
                internal("initializePlugin", plugin, context)
            }
            installed.add(plugin)
            return plugin
        }

        private fun extensionsOf(plugin: PanoPlugin) = PluginEventManager.getEventListeners()[plugin].orEmpty().filterIsInstance<MarketExtension>()

        @Test
        fun `without the flag the plugin registers nothing`() {
            System.clearProperty(flag)
            val plugin = plugin()
            runBlocking { plugin.onStart() }
            assertTrue(extensionsOf(plugin).isEmpty())
            runBlocking { plugin.onStop() }
            assertTrue(extensionsOf(plugin).isEmpty())
        }

        @Test
        fun `any value other than true keeps it inert`() {
            for (value in listOf("false", "TRUE", "1", "yes", "")) {
                System.setProperty(flag, value)
                val plugin = plugin()
                runBlocking { plugin.onStart() }
                assertTrue(extensionsOf(plugin).isEmpty(), "value '$value'")
            }
        }

        @Test
        fun `with the flag it registers its extension and unregisters it on stop`() {
            System.setProperty(flag, "true")
            val plugin = plugin()
            runBlocking { plugin.onStart() }
            val extensions = extensionsOf(plugin)
            assertEquals(1, extensions.size)
            assertEquals(listOf("fake", "fake-eur"), extensions.single().paymentProviders().map { it.id })
            runBlocking { plugin.onStop() }
            assertTrue(extensionsOf(plugin).isEmpty())
        }

        @Test
        fun `the inert message names the property to set`() {
            assertEquals(
                "pano-plugin-market-fake is a test-only plugin and is disabled (start the JVM with -Dpano.market.fakeProvider=true)",
                FakePlugin.DISABLED_MESSAGE
            )
        }
    }

    // ---- the simulator itself ---------------------------------------------------------------------------------------

    @Test
    fun `the simulator refuses a wrong token and serves the documented errors`() {
        val client = io.vertx.ext.web.client.WebClient.create(vertx)
        fun status(method: io.vertx.core.http.HttpMethod, path: String, token: String? = gateway.secret): Int {
            val request = client.requestAbs(method, gateway.baseUrl + path)
            if (token != null) request.putHeader("Authorization", "Bearer $token")
            return request.send().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).statusCode()
        }
        assertEquals(401, status(io.vertx.core.http.HttpMethod.GET, "/v1/ping", token = null))
        assertEquals(401, status(io.vertx.core.http.HttpMethod.GET, "/v1/ping", token = "wrong"))
        assertEquals(200, status(io.vertx.core.http.HttpMethod.GET, "/v1/ping"))
        assertEquals(404, status(io.vertx.core.http.HttpMethod.GET, "/v1/payments/NOPE"))
        assertEquals(404, status(io.vertx.core.http.HttpMethod.POST, "/v1/payments/NOPE/cancel"))
        assertEquals(404, status(io.vertx.core.http.HttpMethod.GET, "/v1/refunds/ref_404"))
        client.close()
    }

    @Test
    fun `the simulator delivers webhooks to a target, several copies at once`() {
        val sink = FakeGateway.start(vertx)
        try {
            sink.on("POST", "/api/market/payments/fake/webhook") { Reply.text("OK") }
            val sender = FakePayGateway(vertx = vertx, webhookTarget = { sink.baseUrl + "/api/market/payments/fake/webhook" })
            try {
                val sequential = sender.sendWebhook("payment.pending", data("reference" to "R1"), id = "evt_a", copies = 2)
                assertEquals(listOf(200, 200), sequential.map { it.statusCode() })
                val concurrent = sender.sendWebhook("payment.pending", data("reference" to "R1"), id = "evt_b", copies = 5, concurrent = true)
                assertEquals(5, concurrent.size)
                assertTrue(concurrent.all { it.statusCode() == 200 })
                assertEquals(7, sink.requests.size)
                assertEquals(1, sink.requests.map { it.bodyText() }.drop(2).toSet().size) // five copies of one event, byte for byte
                assertTrue(sink.requests.all { it.header("X-Fake-Signature")!!.startsWith("t=") })
            } finally {
                sender.close()
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun `the simulator's store-webhook sink follows scripted answers`() {
        val client = io.vertx.ext.web.client.WebClient.create(vertx)
        fun post(): Int = client.postAbs(gateway.baseUrl + "/hooks/store").sendJsonObject(JsonObject().put("n", 1))
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).statusCode()
        gateway.hookStatus("store", 500, 500, 200)
        assertEquals(listOf(500, 500, 200, 200), listOf(post(), post(), post(), post()))
        assertEquals(4, gateway.hooks("store").size)
        assertTrue(gateway.hooks("other").isEmpty())
        client.close()
    }

    companion object {
        const val REFERENCE = "ABCDEFGHJKMNPQRSTVWX"
    }
}
