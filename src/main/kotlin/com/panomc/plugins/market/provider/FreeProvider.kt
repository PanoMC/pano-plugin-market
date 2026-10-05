package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.common.HttpReply
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
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult

/**
 * Shared shape of the two built-ins that settle without a gateway (`free`, `credits`): no settings, no inbound traffic,
 * `startPayment` answers `Completed(Succeeded(paid = 0))` (02 section 12). The credit part of an order is a ledger
 * transaction market has already made, so the gateway amount of such an attempt is always zero.
 */
internal abstract class ZeroAmountProvider : PaymentProvider {
    /** Empty: neither built-in has settings (the switches of `credits` are the store's credit settings). */
    override fun settingsSchema(): SettingsSchema = settingsSchema { }

    override fun capabilities(settings: ProviderSettings): PaymentCapabilities = PaymentCapabilities().also {
        it.refund = RefundSupport.NONE
        it.testMode = TestModeSupport.NONE
        it.webhookSetup = WebhookSetup.NONE
        it.mixedCredit = false
        it.guests = guestsAllowed
    }

    protected abstract val guestsAllowed: Boolean

    override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult {
        // Money is never conjured: a non-zero amount means market routed an order to the wrong provider.
        if (!request.amount.isZero()) {
            throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "provider $id settles only a zero gateway amount, got ${request.amount}")
        }
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), Money(0, request.amount.currency))
        return StartPaymentResult.Completed(event)
    }

    /** No route of these providers is reachable from outside: 404, nothing verified, no events. */
    override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult =
        InboundResult.rejected(HttpReply.text("Not found", 404), "$id has no inbound routes")
}

/** `free`: orders whose total is zero after pricing (free product, 100 % coupon, gift code). No settings. */
internal class FreeProvider : ZeroAmountProvider() {
    override val id: String = ID

    override val descriptor = ProviderDescriptor(
        LocalizedText.of("Free order", "tr" to "Ücretsiz sipariş", "ru" to "Бесплатный заказ"),
        LocalizedText.of(
            "Orders with a total of zero are completed without a payment.",
            "tr" to "Toplamı sıfır olan siparişler ödeme alınmadan tamamlanır.",
            "ru" to "Заказы с нулевой суммой завершаются без оплаты."
        ),
        "gift"
    )

    override val guestsAllowed: Boolean = true

    override fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility =
        if (checkout.order.total.isZero()) Eligibility.eligible()
        else Eligibility.ineligible(
            "NOT_FREE",
            LocalizedText.of("Only orders with a total of zero are free.", "tr" to "Yalnızca toplamı sıfır olan siparişler ücretsizdir.", "ru" to "Бесплатны только заказы с нулевой суммой.")
        )

    companion object {
        const val ID = "free"
    }
}
