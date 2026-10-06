package com.panomc.plugins.market.routes.panel.mail

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShipmentItem
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.mail.MailInput
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The made-up data behind "send test" of one kind (04 section 8 `POST /settings/mail/test`): a sample order, never a real row, marked as a test order so
 * the subject carries `[TEST] ` and the banner says so. Every link points at the sample public id, so no real order can be opened from it.
 */
internal object MailSamples {
    private const val DAY = 86_400_000L

    fun input(kind: MailKind, locale: String, config: MarketConfig, now: Long): MailInput {
        val currency = config.currency.name
        val order = MarketOrder(
            id = 1001, playerUsername = "Steve", totalPrice = 10_000, currency = currency, paymentLabel = "Credit card", status = OrderStatus.COMPLETED, createdAt = now,
            publicId = "SAMPLEORDER0000000000", email = "buyer@example.com", locale = locale, subtotal = 10_000, gatewayAmount = 10_000, paidAt = now, testMode = true,
            isGift = kind == MailKind.GIFT_RECEIVED, giftMessage = if (kind == MailKind.GIFT_RECEIVED) "Enjoy!" else null, recipientUsername = if (kind == MailKind.GIFT_RECEIVED) "Alex" else ""
        )
        val items = listOf(MarketOrderItem(id = 1, orderId = 1001, productName = "Sample rank", quantity = 1, lineTotal = 10_000, variantName = "30 days"))
        val refund = MarketRefund(id = 1, orderId = 1001, amount = 5_000, gatewayAmount = 5_000, currency = currency, reason = "Sample refund")
        val subscription = MarketSubscription(
            id = 1, productName = "Sample rank", initialOrderId = 1001, mode = SubscriptionMode.GATEWAY, status = SubscriptionStatus.ACTIVE, price = 10_000, currency = currency,
            currentPeriodStart = now, currentPeriodEnd = now + 3 * DAY, graceEndsAt = now + 6 * DAY, storedMethodLabel = "Visa **** 4242", endReason = "BUYER_CANCEL"
        )
        val shipment = MarketShipment(
            id = 1, orderId = 1001, status = if (kind == MailKind.SHIPMENT_DELIVERED) ShipmentStatus.DELIVERED else ShipmentStatus.IN_TRANSIT, carrierName = "Sample Carrier",
            trackingNumber = "SAMPLE0000", estimatedDeliveryAt = now + 3 * DAY, shippedAt = now, deliveredAt = now
        )
        val params = when (kind) {
            MailKind.BANK_TRANSFER_INSTRUCTIONS -> JsonObject()
                .put("instructions", JsonObject().put("body", "Transfer the total to the account below.").put("fields", JsonArray().add(JsonObject().put("label", "IBAN").put("value", "XX00 0000 0000 0000"))))
                .put("expiresAt", now + 3 * DAY)
            MailKind.EXPIRY_REMINDER -> JsonObject().put("expiresAt", now + 3 * DAY)
            else -> JsonObject()
        }

        return MailInput(
            kind, locale, order, items, params,
            refund = if (kind == MailKind.ORDER_REFUNDED) refund else null,
            refundItems = if (kind == MailKind.ORDER_REFUNDED) listOf(MarketRefundItem(id = 1, refundId = 1, orderItemId = 1, quantity = 1, amount = 5_000)) else emptyList(),
            subscription = subscription.takeIf { kind.name.startsWith("SUBSCRIPTION_") },
            entitlement = MarketEntitlement(id = 1, orderId = 1001, orderItemId = 1, expiresAt = now + 3 * DAY).takeIf { kind == MailKind.EXPIRY_REMINDER },
            shipment = shipment.takeIf { kind.name.startsWith("SHIPMENT_") },
            shipmentItems = if (kind.name.startsWith("SHIPMENT_")) listOf(MarketShipmentItem(id = 1, shipmentId = 1, orderItemId = 1, quantity = 1)) else emptyList()
        )
    }
}
