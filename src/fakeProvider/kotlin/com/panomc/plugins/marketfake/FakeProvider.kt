package com.panomc.plugins.marketfake

import com.panomc.plugins.market.spi.common.FieldType
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsField
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.Verification
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.CancelPaymentRequest
import com.panomc.plugins.market.spi.payment.CancelPaymentResult
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionState
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.InstructionField
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryRefundRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeResult
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.RefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.spi.payment.SettingsValidation
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

/**
 * The fake payment gateway provider of the T3 tier (17 section 6): a complete, honest [PaymentProvider] that talks to
 * `FakePayGateway` (or any server speaking the wire protocol of 17 section 6.3). Two instances exist, `fake` and
 * `fake-eur`, which differ only in their default capabilities.
 *
 * Every setting that changes a capability (`refundSupport`, `recurring`, `statusQuery`, `buyerMayPayMore`) overrides
 * the default of the instance; `startKind` picks the shape of the answer to `startPayment`. Test mode is mandatory
 * ([checkEligibility]): orders paid through it are always flagged test orders.
 */
class FakeProvider(override val id: String) : PaymentProvider {
    private val eur = id == ID_EUR

    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        displayName = LocalizedText.of("Fake gateway (TEST ONLY)", "tr" to "Sahte ödeme geçidi (YALNIZCA TEST)", "ru" to "Фиктивный шлюз (ТОЛЬКО ТЕСТ)"),
        description = LocalizedText.of(
            if (eur) "Test-only gateway for EUR, full refunds and gateway-managed subscriptions."
            else "Test-only gateway that talks to the fake payment simulator.",
            "tr" to if (eur) "Yalnızca test için EUR, tam iade ve geçit yönetimli abonelik geçidi." else "Sahte ödeme simülatörüyle konuşan yalnızca test geçidi.",
            "ru" to if (eur) "Тестовый шлюз для EUR, полных возвратов и подписок на стороне шлюза." else "Тестовый шлюз, работающий с имитатором платежей."
        ),
        icon = "flask"
    ).also { it.verification = Verification.UNVERIFIED }

    private val defaultRefund = if (eur) RefundSupport.FULL_ONLY else RefundSupport.PARTIAL
    private val defaultRecurring = if (eur) RecurringSupport.GATEWAY_MANAGED else RecurringSupport.MERCHANT_INITIATED
    private val defaultStatusQuery = !eur

    override fun settingsSchema(): SettingsSchema = settingsSchema {
        url("gatewayUrl") {
            label = text("Gateway URL", "Geçit adresi", "Адрес шлюза")
            required = true
        }
        secret("secret") {
            label = text("Secret", "Gizli anahtar", "Секрет")
            required = true
        }
        select("startKind") {
            label = text("Start kind", "Başlatma türü", "Тип запуска")
            default = StartKind.REDIRECT.name
            for (kind in StartKind.entries) option(kind.name, LocalizedText.of(kind.name))
        }
        select("refundSupport") {
            label = text("Refund support", "İade desteği", "Поддержка возвратов")
            default = defaultRefund.name
            for (value in RefundSupport.entries) option(value.name, LocalizedText.of(value.name))
        }
        select("recurring") {
            label = text("Recurring payments", "Yinelenen ödemeler", "Регулярные платежи")
            default = defaultRecurring.name
            for (value in RecurringSupport.entries) option(value.name, LocalizedText.of(value.name))
        }
        switch("statusQuery") {
            label = text("Status query", "Durum sorgusu", "Запрос статуса")
            default = defaultStatusQuery
        }
        switch("buyerMayPayMore") {
            label = text("Buyer may pay more", "Alıcı fazla ödeyebilir", "Покупатель может доплатить")
            default = false
        }
        number("timeoutMs") {
            label = text("Request timeout (ms)", "İstek zaman aşımı (ms)", "Тайм-аут запроса (мс)")
            default = DEFAULT_TIMEOUT_MS
            min = MIN_TIMEOUT_MS
            max = MAX_TIMEOUT_MS
        }
        webhookUrl("webhook") {
            label = text("Webhook URL", "Webhook adresi", "Адрес webhook")
        }
        action("test-connection") {
            label = text("Test connection", "Bağlantıyı test et", "Проверить соединение")
        }
    }

    override fun capabilities(settings: ProviderSettings): PaymentCapabilities = PaymentCapabilities().also {
        if (eur) {
            it.currencies = setOf("EUR")
            it.minAmount = Money(100, "EUR")
            it.maxAmount = Money(50_000, "EUR")
            it.mixedCredit = false
            it.guests = false
        } else {
            it.currencies = null
            it.mixedCredit = true
            it.guests = true
        }
        it.refund = enumSetting(settings, "refundSupport", defaultRefund) { v -> RefundSupport.valueOf(v) }
        it.recurring = enumSetting(settings, "recurring", defaultRecurring) { v -> RecurringSupport.valueOf(v) }
        it.statusQuery = settings.boolean("statusQuery", defaultStatusQuery)
        it.cancelPending = true
        it.disputeEvents = true
        it.buyerMayPayMore = settings.boolean("buyerMayPayMore", false)
        it.webhookSetup = WebhookSetup.MANUAL_URL
        it.physicalGoods = true
        it.testMode = TestModeSupport.FLAG
    }

    override suspend fun validateSettings(ctx: PaymentContext, settings: ProviderSettings): SettingsValidation {
        val errors = LinkedHashMap<String, LocalizedText>()
        val url = settings.string("gatewayUrl")
        if (url == null || !isHttpUrl(url)) {
            errors["gatewayUrl"] = LocalizedText.of("Enter an absolute http or https URL.", "tr" to "Mutlak bir http veya https adresi girin.", "ru" to "Укажите абсолютный адрес http или https.")
        }
        if (settings.string("secret") == null) {
            errors["secret"] = LocalizedText.of("The secret is required.", "tr" to "Gizli anahtar gerekli.", "ru" to "Секрет обязателен.")
        }
        return if (errors.isEmpty()) SettingsValidation.ok() else SettingsValidation.invalid(errors)
    }

    override suspend fun runAction(ctx: PaymentContext, actionId: String, input: JsonObject): ActionResult {
        if (actionId != "test-connection") return super.runAction(ctx, actionId, input)
        return try {
            call(ctx, HttpMethod.GET, "/v1/ping", "ping", expect404 = false)
            ActionResult.Message(LocalizedText.of("Connection works.", "tr" to "Bağlantı çalışıyor.", "ru" to "Соединение работает."), true)
        } catch (e: ProviderException) {
            if (e.code == ProviderErrorCode.CONFIGURATION) throw e
            ActionResult.Message(LocalizedText.of("Connection failed: ${e.message}"), false)
        }
    }

    override fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility =
        if (ctx.testMode) {
            Eligibility.eligible()
        } else {
            Eligibility.ineligible(
                "FAKE_REQUIRES_TEST_MODE",
                LocalizedText.of(
                    "The fake gateway can only be used in test mode.",
                    "tr" to "Sahte geçit yalnızca test modunda kullanılabilir.",
                    "ru" to "Фиктивный шлюз доступен только в тестовом режиме."
                )
            )
        }

    // ---- start -----------------------------------------------------------------------------------------------------

    override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult {
        val kind = enumSetting(ctx.settings, "startKind", StartKind.REDIRECT) { v -> StartKind.valueOf(v) }
        val reference = request.attempt.reference
        val body = JsonObject()
            .put("reference", reference)
            .put("amount", request.amount.toDecimalString())
            .put("currency", request.amount.currency)
            .put("returnSuccess", request.urls.success)
            .put("returnCancel", request.urls.cancel)
            .put("notifyUrl", request.urls.notify)
        request.subscription?.let {
            body.put(
                "subscription",
                JsonObject().put("planKey", it.planKey).put("intervalUnit", it.intervalUnit.name).put("intervalCount", it.intervalCount)
            )
        }
        val reply = call(ctx, HttpMethod.POST, "/v1/payments", "start", body, request.idempotencyKey)
        val json = reply.json
        val gatewayId = json?.getString("id")
        val payUrl = json?.getString("payUrl")
        if (gatewayId.isNullOrBlank() || payUrl.isNullOrBlank()) {
            throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "The gateway answered a payment without id or payUrl")
        }

        val result: StartPaymentResult = when (kind) {
            StartKind.REDIRECT -> StartPaymentResult.Redirect(payUrl)
            StartKind.FORM_POST -> StartPaymentResult.FormPost(
                payUrl, linkedMapOf("reference" to reference, "amount" to request.amount.toDecimalString(), "currency" to request.amount.currency)
            )
            StartKind.IFRAME -> StartPaymentResult.Iframe(payUrl)
            StartKind.HTML -> StartPaymentResult.Html(
                "<!doctype html><html><head><meta charset=\"utf-8\"><title>Fake gateway</title></head><body>" +
                    "<p>Fake gateway payment $reference</p><a href=\"${escapeHtml(payUrl)}\">Pay</a></body></html>"
            )
            StartKind.INSTRUCTIONS -> StartPaymentResult.Instructions(
                LocalizedText.of("Pay at the fake gateway with the reference below."),
                listOf(
                    InstructionField(LocalizedText.of("Reference"), reference),
                    InstructionField(LocalizedText.of("Payment page"), payUrl)
                )
            ).also { it.buyerConfirms = false }
            StartKind.EMBEDDED -> StartPaymentResult.Embedded(JsonObject().put("reference", reference).put("payUrl", payUrl)).also {
                it.fields = listOf(SettingsField("code", FieldType.TEXT, LocalizedText.of("Code")))
            }
            StartKind.COMPLETED -> StartPaymentResult.Completed(
                PaymentEvent.Succeeded(PaymentTarget.Reference(reference), request.amount).also { it.gatewayTransactionId = gatewayId }
            )
        }
        result.gatewayTransactionId = gatewayId
        json.getString("session")?.let { result.gatewayRefs = mapOf("session" to it) }
        result.providerData = JsonObject().put("payUrl", payUrl)
        return result
    }

    override suspend fun continuePayment(ctx: PaymentContext, request: ContinuePaymentRequest): StartPaymentResult {
        val payUrl = request.attempt.providerData?.getString("payUrl")
            ?: throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "The attempt has no payment page to continue to")
        return StartPaymentResult.Redirect(payUrl).also { it.gatewayTransactionId = request.attempt.gatewayTransactionId }
    }

    // ---- inbound ---------------------------------------------------------------------------------------------------

    override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        // Settings first: an unconfigured provider fails with CONFIGURATION for every kind of traffic.
        val secret = ctx.settings.require("secret")
        ctx.settings.require("gatewayUrl")
        return when (request.http.kind) {
            InboundKind.WEBHOOK -> webhook(ctx, request, secret)
            InboundKind.NOTIFY -> notify(ctx, request)
            InboundKind.RETURN -> browserReturn(ctx, request)
        }
    }

    private fun webhook(ctx: PaymentContext, request: PaymentInboundRequest, secret: String): InboundResult {
        val http = request.http
        if (!FakeSignature.verify(secret, http.header(FakeSignature.HEADER), http.body, ctx.now())) {
            return InboundResult.rejected(HttpReply.text("bad signature", 400), "bad signature")
        }
        val json = try {
            // the signature was checked over the exact bytes above; only the decoded text drops a leading UTF-8 byte order mark (17 section 9.3 F-17)
            JsonObject(http.bodyAsString().removePrefix("\uFEFF"))
        } catch (e: Exception) {
            return InboundResult.rejected(HttpReply.text("bad body", 400), "body is not a JSON object")
        }
        val eventId = json.getValue("id") as? String
        val type = json.getValue("type") as? String
        val data = try {
            json.getJsonObject("data") ?: JsonObject()
        } catch (e: ClassCastException) {
            return InboundResult.rejected(HttpReply.text("bad body", 400), "data is not an object")
        }
        if (type.isNullOrBlank()) return InboundResult.rejected(HttpReply.text("bad body", 400), "event without type")
        val events = try {
            eventsOf(type, eventId, data)
        } catch (e: Exception) {
            return InboundResult.rejected(HttpReply.text("bad event", 400), "malformed $type event: ${e.javaClass.simpleName}")
        } ?: return InboundResult.ignored(HttpReply.text("OK"))
        return InboundResult.accepted(HttpReply.text("OK"), events, eventKey = eventId?.takeIf { it.isNotBlank() })
    }

    /** Null = a type this provider does not know (ignored, never an error: the gateway may add types later). */
    private fun eventsOf(type: String, eventId: String?, data: JsonObject): List<PaymentEvent>? {
        fun reference() = PaymentTarget.Reference(requireText(data, "reference"))
        return when (type) {
            "payment.succeeded" -> listOf(
                PaymentEvent.Succeeded(reference(), money(data, "amount")).also { e ->
                    data.getString("paymentId")?.let { e.gatewayTransactionId = it }
                    if (data.containsKey("fee")) e.gatewayFee = money(data, "fee")
                    storedMethod(data.getValue("storedMethod"))?.let { e.storedMethod = it }
                    data.getJsonObject("subscription")?.let { s ->
                        e.subscription = GatewaySubscriptionState(requireText(s, "id"), GatewaySubscriptionStatus.ACTIVE).also {
                            it.currentPeriodStart = s.getLong("periodStart")
                            it.currentPeriodEnd = s.getLong("periodEnd")
                        }
                    }
                }
            )
            "payment.pending" -> listOf(PaymentEvent.Pending(reference(), enumOr(data.getString("reason"), PendingReason.OTHER, PendingReason.AWAITING_BUYER)))
            "payment.failed" -> listOf(
                PaymentEvent.Failed(reference(), data.getString("code") ?: "failed", data.getString("message")).also { it.final = data.getBoolean("final", false) }
            )
            "payment.cancelled" -> listOf(PaymentEvent.Cancelled(reference()))
            "payment.expired" -> listOf(PaymentEvent.Expired(reference()))
            "payment.review" -> listOf(
                PaymentEvent.NeedsReview(reference(), enumOr(data.getString("reason"), ReviewReason.OTHER, ReviewReason.OTHER)).also { e ->
                    if (data.containsKey("amount")) e.received = money(data, "amount")
                }
            )
            "refund.updated" -> listOf(
                PaymentEvent.RefundUpdated(
                    reference(), RefundState.valueOf(requireText(data, "state")),
                    if (data.containsKey("amount")) money(data, "amount") else null
                ).also {
                    it.gatewayRefundId = data.getString("refundId") ?: data.getString("gatewayRefundId")
                    it.refundKey = data.getString("idempotencyKey")
                }
            )
            "dispute.updated" -> listOf(
                PaymentEvent.DisputeUpdated(reference(), DisputeState.valueOf(requireText(data, "state"))).also {
                    it.gatewayDisputeId = data.getString("disputeId") ?: data.getString("gatewayDisputeId")
                    if (data.containsKey("amount")) it.amount = money(data, "amount")
                    it.reason = data.getString("reason")
                }
            )
            "subscription.renewed" -> listOf(
                PaymentEvent.SubscriptionRenewed(requireText(data, "subscriptionId"), money(data, "amount")).also {
                    it.periodStart = data.getLong("periodStart")
                    it.periodEnd = data.getLong("periodEnd")
                    // Market de-duplicates renewals on the gateway charge id or the period start.
                    it.gatewayTransactionId = data.getString("paymentId") ?: eventId
                    if (data.containsKey("fee")) it.gatewayFee = money(data, "fee")
                }
            )
            "subscription.payment_failed" -> listOf(
                PaymentEvent.SubscriptionPaymentFailed(requireText(data, "subscriptionId")).also {
                    it.final = data.getBoolean("final", false)
                    it.attemptCount = data.getInteger("attemptCount")
                    it.nextRetryAt = data.getLong("nextRetryAt")
                }
            )
            "subscription.updated" -> listOf(
                PaymentEvent.SubscriptionUpdated(
                    GatewaySubscriptionState(requireText(data, "subscriptionId"), GatewaySubscriptionStatus.valueOf(requireText(data, "status"))).also {
                        it.currentPeriodStart = data.getLong("periodStart")
                        it.currentPeriodEnd = data.getLong("periodEnd")
                        it.endsAt = data.getLong("endsAt")
                    }
                )
            )
            else -> null
        }
    }

    /** Mollie model: the notification carries only an id, the facts are fetched. */
    private suspend fun notify(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        val attempt = request.attempt ?: return InboundResult.rejected(HttpReply.text("unknown attempt", 400), "no attempt")
        val form = try {
            request.http.form()
        } catch (e: IllegalArgumentException) {
            return InboundResult.rejected(HttpReply.text("bad body", 400), "malformed form body")
        }
        if (form["id"]?.firstOrNull().isNullOrBlank()) return InboundResult.rejected(HttpReply.text("missing id", 400), "no payment id")
        val fetched = try {
            fetch(ctx, attempt.reference)
        } catch (e: ProviderException) {
            if (e.code == ProviderErrorCode.CONFIGURATION) throw e
            return InboundResult.rejected(HttpReply.retryLater(), "gateway query failed: ${e.code}")
        } ?: return InboundResult.ignored(HttpReply.retryLater())
        return InboundResult.accepted(HttpReply.text("OK"), fetched)
    }

    /** A browser return is never trusted: success is only believed after our own query. */
    private suspend fun browserReturn(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        val attempt = request.attempt ?: return InboundResult.rejected(HttpReply.text("unknown attempt", 400), "no attempt")
        return when (request.outcome) {
            ReturnOutcome.SUCCESS, ReturnOutcome.RESULT -> {
                val fetched = try {
                    fetch(ctx, attempt.reference)
                } catch (e: ProviderException) {
                    if (e.code == ProviderErrorCode.CONFIGURATION) throw e
                    return InboundResult.rejected(HttpReply.toOrderPage(), "gateway query failed: ${e.code}")
                }
                if (fetched == null) InboundResult.ignored(HttpReply.toOrderPage())
                else InboundResult.accepted(HttpReply.toOrderPage(), fetched)
            }
            else -> InboundResult.ignored(HttpReply.toOrderPage())
        }
    }

    // ---- query / cancel --------------------------------------------------------------------------------------------

    override suspend fun queryPayment(ctx: PaymentContext, request: QueryPaymentRequest): PaymentQueryResult {
        val events = fetch(ctx, request.attempt.reference) ?: return PaymentQueryResult.unknown()
        return if (events.isEmpty()) PaymentQueryResult.unknown() else PaymentQueryResult(events)
    }

    /** Null = the gateway does not know the payment (404) or reported a status this provider does not know. */
    private suspend fun fetch(ctx: PaymentContext, reference: String): List<PaymentEvent>? {
        val reply = call(ctx, HttpMethod.GET, "/v1/payments/${encode(reference)}", "query", expect404 = true)
        val json = reply.json
        if (reply.status == 404 || json == null) return null
        val target = PaymentTarget.Reference(reference)
        val gatewayId = json.getString("id")
        val event: PaymentEvent = when (json.getString("status")) {
            "pending" -> PaymentEvent.Pending(target, PendingReason.AWAITING_BUYER)
            "paid" -> PaymentEvent.Succeeded(target, money(json, "paidAmount", json.getString("currency")))
            "failed" -> PaymentEvent.Failed(target, json.getString("code") ?: "failed", json.getString("message")).also { it.final = true }
            "expired" -> PaymentEvent.Expired(target)
            "review" -> PaymentEvent.NeedsReview(target, ReviewReason.OTHER).also {
                if (json.getValue("paidAmount") != null) it.received = money(json, "paidAmount", json.getString("currency"))
            }
            else -> return null
        }
        if (gatewayId != null) event.gatewayTransactionId = gatewayId
        return listOf(event)
    }

    override suspend fun cancelPayment(ctx: PaymentContext, request: CancelPaymentRequest): CancelPaymentResult {
        val reply = call(ctx, HttpMethod.POST, "/v1/payments/${encode(request.attempt.reference)}/cancel", "cancel", expect409 = true)
        return if (reply.status == 409) CancelPaymentResult.notCancellable() else CancelPaymentResult.cancelled()
    }

    // ---- refunds ---------------------------------------------------------------------------------------------------

    override suspend fun refund(ctx: PaymentContext, request: RefundRequest): RefundResult {
        val attempt = request.attempt
        val paymentId = attempt.gatewayTransactionId
            ?: throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "The attempt has no gateway transaction id to refund")
        // A full refund returns what the gateway still holds, which can exceed the order part (surcharge).
        val amount = if (request.full) {
            val paid = attempt.paidAmount ?: attempt.amount
            Money(paid.amount - attempt.refundedAmount.amount, paid.currency)
        } else {
            request.amount
        }
        val body = JsonObject().put("paymentId", paymentId).put("amount", amount.toDecimalString()).put("currency", amount.currency)
        val perLine = enumSetting(ctx.settings, "refundSupport", defaultRefund) { v -> RefundSupport.valueOf(v) } == RefundSupport.PER_LINE
        if (perLine && request.lines.isNotEmpty()) {
            body.put(
                "lines",
                JsonArray(request.lines.map {
                    JsonObject().put("orderItemId", it.orderItemId).put("quantity", it.quantity).put("amount", it.amount.toDecimalString())
                })
            )
        }
        val reply = call(ctx, HttpMethod.POST, "/v1/refunds", "refund", body, request.idempotencyKey)
        return refundResult(reply.json, amount)
    }

    override suspend fun queryRefund(ctx: PaymentContext, request: QueryRefundRequest): RefundResult {
        val id = request.gatewayRefundId ?: return RefundResult.unknown()
        val reply = call(ctx, HttpMethod.GET, "/v1/refunds/${encode(id)}", "queryRefund", expect404 = true)
        if (reply.status == 404) return RefundResult.unknown()
        return refundResult(reply.json, request.amount)
    }

    private fun refundResult(json: JsonObject?, amount: Money): RefundResult {
        val result: RefundResult = when (json?.getString("status")) {
            "succeeded" -> RefundResult.Succeeded()
            "pending" -> RefundResult.Pending()
            "failed" -> RefundResult.Failed(json.getString("code") ?: "refund_failed", json.getString("message"))
            else -> throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "The gateway answered a refund without a known status")
        }
        result.gatewayRefundId = json.getString("id")
        if (result !is RefundResult.Failed) result.refundedAmount = amount
        return result
    }

    // ---- recurring -------------------------------------------------------------------------------------------------

    override suspend fun chargeRecurring(ctx: PaymentContext, request: RecurringChargeRequest): RecurringChargeResult {
        val body = JsonObject()
            .put("reference", request.attempt.reference)
            .put("amount", request.amount.toDecimalString())
            .put("currency", request.amount.currency)
            .put("storedMethod", request.storedMethod.token)
        val reply = call(ctx, HttpMethod.POST, "/v1/charges", "charge", body, request.idempotencyKey)
        val json = reply.json
        val target = PaymentTarget.Reference(request.attempt.reference)
        val event: PaymentEvent = when (json?.getString("status")) {
            "paid", "succeeded" -> PaymentEvent.Succeeded(target, request.amount)
            "pending" -> PaymentEvent.Pending(target, PendingReason.AWAITING_BANK)
            "failed" -> PaymentEvent.Failed(target, json.getString("code") ?: "failed", json.getString("message"))
            else -> throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "The gateway answered a charge without a known status")
        }
        json.getString("id")?.let { event.gatewayTransactionId = it }
        return RecurringChargeResult(listOf(event))
    }

    override suspend fun cancelSubscription(ctx: PaymentContext, request: CancelSubscriptionRequest): CancelSubscriptionResult {
        val subscription = request.subscription
        val gatewayId = subscription.gatewaySubscriptionId
            ?: throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "The subscription has no gateway id to cancel")
        val reply = call(
            ctx, HttpMethod.POST, "/v1/subscriptions/${encode(gatewayId)}/cancel", "cancelSubscription",
            JsonObject().put("atPeriodEnd", request.atPeriodEnd)
        )
        val endsAt = reply.json?.getLong("endsAt")
        val scheduledFor = endsAt ?: subscription.currentPeriodEnd
        return if (request.atPeriodEnd && scheduledFor != null) CancelSubscriptionResult.Scheduled(scheduledFor)
        else CancelSubscriptionResult.Cancelled(endsAt)
    }

    // ---- wire ------------------------------------------------------------------------------------------------------

    private class Reply(val status: Int, val json: JsonObject?)

    /**
     * One call of the wire protocol (17 section 6.3): bearer secret, optional `Idempotency-Key`, per-request timeout.
     * [expect404] / [expect409] hand that status back instead of mapping it to an error. Every other non-2xx answer
     * becomes a [ProviderException]: 401 AUTHENTICATION, 429 RATE_LIMITED, other 4xx GATEWAY_REJECTED (the gateway's
     * `message` as admin message), 5xx and every transport failure GATEWAY_UNREACHABLE (retryable).
     */
    private suspend fun call(
        ctx: PaymentContext,
        method: HttpMethod,
        path: String,
        channel: String,
        body: JsonObject? = null,
        idempotencyKey: String? = null,
        expect404: Boolean = false,
        expect409: Boolean = false
    ): Reply {
        val base = ctx.settings.require("gatewayUrl").trimEnd('/')
        val secret = ctx.settings.require("secret")
        val timeout = (ctx.settings.long("timeoutMs") ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val started = System.nanoTime()
        val response = try {
            val http = ctx.http.requestAbs(method, base + path)
                .timeout(timeout)
                .putHeader("Authorization", "Bearer $secret")
                .putHeader("Accept", "application/json")
            if (idempotencyKey != null) http.putHeader("Idempotency-Key", idempotencyKey)
            if (body != null) http.sendJsonObject(body).coAwait() else http.send().coAwait()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ctx.log.exchange(channel, body?.encode(), e.javaClass.simpleName, null, elapsedMs(started))
            throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "The gateway could not be reached (${e.javaClass.simpleName})", retryable = true, cause = e)
        }
        val text = response.bodyAsString()
        val status = response.statusCode()
        ctx.log.exchange(channel, body?.encode(), text?.take(2000), status, elapsedMs(started))
        val json = try {
            if (text.isNullOrBlank()) null else JsonObject(text)
        } catch (e: Exception) {
            null
        }
        if (status in 200..299) return Reply(status, json)
        if (status == 404 && expect404) return Reply(status, json)
        if (status == 409 && expect409) return Reply(status, json)
        val message = json?.getValue("message")?.toString()
        throw when {
            status == 401 -> ProviderException(ProviderErrorCode.AUTHENTICATION, "The gateway rejected the credentials", adminMessage = message)
            status == 429 -> ProviderException(ProviderErrorCode.RATE_LIMITED, "The gateway rate limit was hit", adminMessage = message, retryable = true)
            status in 400..499 -> ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "The gateway rejected the request ($status)", adminMessage = message)
            else -> ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "The gateway failed ($status)", adminMessage = message, retryable = true)
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun requireText(json: JsonObject, key: String): String =
        (json.getValue(key) as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$key is missing")

    private fun money(json: JsonObject, key: String, currencyFallback: String? = null): Money {
        val raw = json.getValue(key) ?: throw IllegalArgumentException("$key is missing")
        val currency = json.getString("currency") ?: currencyFallback ?: throw IllegalArgumentException("currency is missing")
        return Money.ofDecimal(BigDecimal(raw.toString()), currency)
    }

    private fun storedMethod(value: Any?): StoredPaymentMethod? = when (value) {
        null -> null
        is String -> StoredPaymentMethod(value)
        is JsonObject -> StoredPaymentMethod(requireText(value, "token")).also {
            it.label = value.getString("label")
            it.expiresAt = value.getLong("expiresAt")
            it.gatewayCustomerId = value.getString("gatewayCustomerId")
        }
        else -> throw IllegalArgumentException("storedMethod has the wrong type")
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, unknown: E, absent: E): E {
        if (value == null) return absent
        return enumValues<E>().firstOrNull { it.name == value } ?: unknown
    }

    private fun <E : Enum<E>> enumSetting(settings: ProviderSettings, key: String, default: E, parse: (String) -> E): E {
        val raw = settings.string(key) ?: return default
        return try {
            parse(raw)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    private fun isHttpUrl(url: String): Boolean = try {
        val uri = URI(url)
        (uri.scheme?.lowercase() == "http" || uri.scheme?.lowercase() == "https") && !uri.host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

    private fun escapeHtml(text: String): String = buildString {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun text(en: String, tr: String, ru: String) = LocalizedText.of(en, "tr" to tr, "ru" to ru)

    /** `startKind` setting: the shape of the answer to `startPayment`. */
    enum class StartKind { REDIRECT, FORM_POST, IFRAME, HTML, INSTRUCTIONS, EMBEDDED, COMPLETED }

    companion object {
        const val ID = "fake"
        const val ID_EUR = "fake-eur"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 60_000L
    }
}
