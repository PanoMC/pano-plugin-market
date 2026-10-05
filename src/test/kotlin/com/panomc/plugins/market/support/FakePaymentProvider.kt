package com.panomc.plugins.market.support

import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.CancelPaymentRequest
import com.panomc.plugins.market.spi.payment.CancelPaymentResult
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryRefundRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeResult
import com.panomc.plugins.market.spi.payment.RefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scriptable payment provider for the order / payment state machine tests (02 section 14.4, 17 section 5.5). Id `fake`
 * unless given. Set [caps] and the `on...` lambdas; every call lands in [calls]; [failNext] makes the next call of an
 * operation throw; [delay] holds the next call of an operation open until the test completes the returned gate (or
 * the optional timeout passes), to let a second actor act meanwhile.
 */
class FakePaymentProvider(override val id: String = "fake") : PaymentProvider {
    enum class Op { START, INBOUND, QUERY, CANCEL, REFUND, QUERY_REFUND, CHARGE_RECURRING, CANCEL_SUBSCRIPTION }

    /** One recorded call: the operation and its request object (a [PaymentInboundRequest], [RefundRequest], ...). */
    class Call(val op: Op, val request: Any)

    override val descriptor = ProviderDescriptor(LocalizedText.of("Fake payment"), LocalizedText.of("Scriptable test provider"), "credit-card")

    @Volatile var caps: PaymentCapabilities = PaymentCapabilities()
    @Volatile var onStart: (StartPaymentRequest) -> StartPaymentResult = { StartPaymentResult.Redirect("https://gateway.invalid/pay/${it.attempt.reference}") }
    @Volatile var onInbound: (PaymentInboundRequest) -> InboundResult = { InboundResult.ignored(HttpReply.empty()) }
    @Volatile var onQuery: (QueryPaymentRequest) -> PaymentQueryResult = { PaymentQueryResult.unknown() }
    @Volatile var onRefund: (RefundRequest) -> RefundResult = { RefundResult.Succeeded() }
    @Volatile var onCancel: (CancelPaymentRequest) -> CancelPaymentResult = { CancelPaymentResult.cancelled() }
    @Volatile var onChargeRecurring: (RecurringChargeRequest) -> RecurringChargeResult = { RecurringChargeResult(emptyList()) }
    @Volatile var onCancelSubscription: (CancelSubscriptionRequest) -> CancelSubscriptionResult = { CancelSubscriptionResult.localOnly() }

    private val recorded = CopyOnWriteArrayList<Call>()
    private val failures = ConcurrentHashMap<Op, java.util.concurrent.ConcurrentLinkedQueue<ProviderException>>()
    private val gates = ConcurrentHashMap<Op, java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>>()

    val calls: List<Call> get() = recorded.toList()

    fun calls(op: Op): List<Call> = recorded.filter { it.op == op }

    fun failNext(op: Op, error: ProviderException) {
        failures.computeIfAbsent(op) { java.util.concurrent.ConcurrentLinkedQueue() }.add(error)
    }

    /** The next call of [op] suspends until the returned deferred completes; [ms] > 0 completes it by itself after that long. */
    fun delay(op: Op, ms: Long = 0): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        gates.computeIfAbsent(op) { java.util.concurrent.ConcurrentLinkedQueue() }.add(gate)
        if (ms > 0) {
            val timer = Thread({
                try {
                    Thread.sleep(ms)
                    gate.complete(Unit)
                } catch (_: InterruptedException) {
                }
            }, "fake-payment-delay").also { it.isDaemon = true }
            timer.start()
        }
        return gate
    }

    private suspend fun <R> enter(op: Op, request: Any, block: () -> R): R {
        recorded.add(Call(op, request))
        gates[op]?.poll()?.await()
        failures[op]?.poll()?.let { throw it }
        return block()
    }

    override fun settingsSchema(): SettingsSchema = settingsSchema { }

    override fun capabilities(settings: ProviderSettings): PaymentCapabilities = caps

    override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult = enter(Op.START, request) { onStart(request) }

    override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult = enter(Op.INBOUND, request) { onInbound(request) }

    override suspend fun queryPayment(ctx: PaymentContext, request: QueryPaymentRequest): PaymentQueryResult = enter(Op.QUERY, request) { onQuery(request) }

    override suspend fun cancelPayment(ctx: PaymentContext, request: CancelPaymentRequest): CancelPaymentResult = enter(Op.CANCEL, request) { onCancel(request) }

    override suspend fun refund(ctx: PaymentContext, request: RefundRequest): RefundResult = enter(Op.REFUND, request) { onRefund(request) }

    override suspend fun queryRefund(ctx: PaymentContext, request: QueryRefundRequest): RefundResult = enter(Op.QUERY_REFUND, request) { RefundResult.unknown() }

    override suspend fun chargeRecurring(ctx: PaymentContext, request: RecurringChargeRequest): RecurringChargeResult =
        enter(Op.CHARGE_RECURRING, request) { onChargeRecurring(request) }

    override suspend fun cancelSubscription(ctx: PaymentContext, request: CancelSubscriptionRequest): CancelSubscriptionResult =
        enter(Op.CANCEL_SUBSCRIPTION, request) { onCancelSubscription(request) }
}
