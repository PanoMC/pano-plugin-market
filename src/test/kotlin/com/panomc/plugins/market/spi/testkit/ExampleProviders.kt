package com.panomc.plugins.market.spi.testkit

import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.ShipmentErrorCode
import com.panomc.plugins.market.spi.shipping.ShipmentStatus
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingInboundResult
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.TrackingEvent
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.spi.shipping.defaultParcel
import com.panomc.plugins.market.spi.shipping.senderAddress
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private fun text(s: String) = LocalizedText.of(s, "tr" to s, "ru" to s)

/** HMAC-SHA256 as lower-case hex: the signature scheme of the example gateway. */
internal fun hmacHex(secret: String, body: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(body).joinToString("") { "%02x".format(it) }
}

/**
 * A small, correct payment provider for the example gateway (`POST /v1/payments`, signed webhooks with
 * `x-signature = hmac-sha256(secret, body)`). It is the positive control of the contract suite; subclasses in the
 * tests break one rule at a time.
 */
open class ExamplePaymentProvider : PaymentProvider {
    override val id = "example-pay"
    override val descriptor = ProviderDescriptor(text("Example Pay"), text("Example gateway for the contract suite"), "credit-card")

    override fun settingsSchema(): SettingsSchema = settingsSchema {
        url("baseUrl") { label = text("Gateway URL"); required = true }
        secret("apiKey") { label = text("API key"); required = true }
        secret("webhookSecret") { label = text("Webhook secret"); required = true }
    }

    override fun capabilities(settings: ProviderSettings) = PaymentCapabilities()

    override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult {
        val base = ctx.settings.require("baseUrl")
        val response = ctx.http.postAbs("$base/v1/payments")
            .putHeader("authorization", "Bearer " + ctx.settings.require("apiKey"))
            .sendJsonObject(JsonObject().put("reference", request.attempt.reference).put("amount", request.amount.amount).put("currency", request.amount.currency))
            .coAwait()
        ctx.log.exchange("start", request.attempt.reference, response.bodyAsString(), response.statusCode(), 1)
        ctx.log.info("started payment ${request.attempt.reference}")
        return StartPaymentResult.Redirect(response.bodyAsJsonObject().getString("url")).also {
            it.gatewayTransactionId = response.bodyAsJsonObject().getString("id")
        }
    }

    override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        val secret = ctx.settings.require("webhookSecret")
        val http = request.http
        val signature = http.header("x-signature")
        if (signature.isNullOrEmpty() || !signature.equals(hmacHex(secret, http.body), ignoreCase = true)) {
            return InboundResult.rejected(HttpReply.text("bad signature", 401), "signature")
        }
        val json = try {
            JsonObject(http.bodyAsString())
        } catch (e: Exception) {
            return InboundResult.rejected(HttpReply.text("bad body", 400), "body")
        }
        return when (json.getString("type")) {
            "payment.succeeded" -> InboundResult.accepted(
                HttpReply.text("OK"),
                listOf(PaymentEvent.Succeeded(PaymentTarget.Reference(json.getString("reference")), Money(json.getLong("amount"), json.getString("currency")))),
                eventKey = json.getString("id")
            )
            else -> InboundResult.ignored(HttpReply.text("OK"))
        }
    }

    companion object {
        /** A correctly signed notification and the same with an unsigned query parameter added. */
        fun signed(secret: String, type: String = "payment.succeeded", id: String = "evt_1"): SignedSample {
            val body = JsonObject().put("id", id).put("type", type).put("reference", "ABCDEFGHJKMNPQRSTVWX").put("amount", 1000).put("currency", "EUR")
                .encode().toByteArray()
            val headers = mapOf("x-signature" to listOf(hmacHex(secret, body)), "content-type" to listOf("application/json"))
            fun req(query: Map<String, List<String>>) = InboundRequest(
                kind = com.panomc.plugins.market.spi.common.InboundKind.WEBHOOK, channel = "default", method = "POST",
                rawQuery = query.entries.joinToString("&") { (k, v) -> "$k=${v.first()}" }.ifEmpty { null }, query = query, headers = headers,
                contentType = "application/json", body = body, remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
            )
            return SignedSample(req(emptyMap()), req(mapOf("utm" to listOf("1"))))
        }
    }
}

/**
 * A correct shipping provider for the example carrier: unsigned push webhooks `{"trackingNumber": "TN1"}` that are
 * re-fetched with `GET /v1/track/{number}`, and a two-step create (`POST /v1/shipments`, then
 * `POST /v1/shipments/{ref}/buy`) whose retry reuses the carrier object.
 */
open class ExampleShippingProvider : ShippingProvider {
    override val id = "example-ship"
    override val descriptor = ProviderDescriptor(text("Example Carrier"), text("Example carrier for the contract suite"), "truck")

    override fun settingsSchema(): SettingsSchema = settingsSchema {
        url("baseUrl") { label = text("Carrier URL"); required = true }
        secret("apiKey") { label = text("API key"); required = true }
        senderAddress()
        defaultParcel()
    }

    override fun capabilities(settings: ProviderSettings): ShippingCapabilities = ShippingCapabilities().also {
        it.createShipment = true
        it.trackingPush = true
        it.webhookSigned = false
        it.trackBatchSize = 10
    }

    protected suspend fun get(ctx: ShippingContext, path: String): io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> =
        ctx.http.getAbs(ctx.settings.require("baseUrl") + path).putHeader("authorization", "Bearer " + ctx.settings.require("apiKey")).send().coAwait()

    protected suspend fun post(ctx: ShippingContext, path: String, body: JsonObject): io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer> =
        ctx.http.postAbs(ctx.settings.require("baseUrl") + path).putHeader("authorization", "Bearer " + ctx.settings.require("apiKey")).sendJsonObject(body).coAwait()

    override suspend fun handleInbound(ctx: ShippingContext, request: InboundRequest): ShippingInboundResult {
        ctx.settings.require("apiKey")
        val number = try {
            JsonObject(request.bodyAsString()).getString("trackingNumber")
        } catch (e: Exception) {
            return ShippingInboundResult.rejected(com.panomc.plugins.market.spi.common.HttpReply.text("bad body", 400), "body")
        } ?: return ShippingInboundResult.ignored(com.panomc.plugins.market.spi.common.HttpReply.text("OK"))
        val fetched = get(ctx, "/v1/track/$number").bodyAsJsonObject().getString("status")
        val status = ShipmentStatus.valueOf(fetched)
        return ShippingInboundResult.accepted(
            com.panomc.plugins.market.spi.common.HttpReply.text("OK"),
            listOf(TrackingUpdate(ShipmentTarget.TrackingNumber(number), listOf(TrackingEvent(status, ctx.now())))),
            eventKey = "$number:$fetched"
        )
    }

    override suspend fun createShipment(ctx: ShippingContext, request: CreateShipmentRequest): CreateShipmentResult {
        val reference = request.previousCarrierReference
            ?: post(ctx, "/v1/shipments", JsonObject().put("reference", request.merchantReference)).bodyAsJsonObject().getString("id")
        val bought = post(ctx, "/v1/shipments/$reference/buy", JsonObject())
        if (bought.statusCode() == 402) {
            return CreateShipmentResult.Failed(ShipmentErrorCode.INSUFFICIENT_BALANCE, "balance").also { it.carrierReference = reference }
        }
        return CreateShipmentResult.Created(reference).also { it.trackingNumber = bought.bodyAsJsonObject().getString("trackingNumber") }
    }
}

/** Counts how often a counter-based answer is asked (used by the deliberately broken providers). */
internal val brokenCounter = AtomicInteger()
