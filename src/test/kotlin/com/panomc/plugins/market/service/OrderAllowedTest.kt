package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `allowed{}` block of `GET /orders/:id` (04 section 7, 13 section 6.2; MK-170): one flag per action, by the state of the order and the node of the caller. */
class OrderAllowedTest {
    private val pay = OrderViewer.of(manage = false, pay = true)
    private val manage = OrderViewer.of(manage = true, pay = false)
    private val both = OrderViewer.of(manage = true, pay = true)
    private val viewOnly = OrderViewer.NOBODY
    private val flags = listOf(
        "markPaid", "cancel", "refund", "review", "bankTransfer", "dispute", "rerunDelivery", "revoke", "createShipment", "editShippingAddress", "resendMail", "anonymize",
        "runChargebackActions"
    )

    private fun order(
        status: OrderStatus, total: Long = 1000, paid: Boolean = status != OrderStatus.PENDING && status != OrderStatus.REVIEW, shipping: Boolean = false, dispute: DisputeStatus = DisputeStatus.NONE,
        refunded: Long = 0
    ) = MarketOrder(
        id = 1, userId = 5, status = status, totalPrice = total, gatewayAmount = total, paidAmount = if (paid) total else 0, paidAt = if (paid) 1L else null, refundedTotal = refunded,
        refundedGatewayAmount = refunded, requiresShipping = shipping, shippingAddress = if (shipping) "{\"country\":\"TR\"}" else null, disputeStatus = dispute, paymentId = 9
    )

    private fun item(quantity: Int = 1, refundedQuantity: Int = 0, physical: Boolean = false, kind: OrderItemKind = OrderItemKind.PRODUCT, shipped: Int = 0, total: Long = 1000, refundedAmount: Long = 0) =
        MarketOrderItem(id = 1, orderId = 1, quantity = quantity, refundedQuantity = refundedQuantity, physical = physical, kind = kind, shippedQuantity = shipped, lineTotal = total, refundedAmount = refundedAmount)

    private fun delivery(
        status: DeliveryStatus, phase: DeliveryPhase = DeliveryPhase.GRANT, source: DeliverySourceType = DeliverySourceType.ORDER_ITEM, unit: Int = 0, group: Int = 0,
        error: String? = null, sourceId: Long? = null
    ) = MarketDelivery(
        id = 1, orderId = 1, orderItemId = 1, sourceType = source, sourceId = sourceId, phase = phase, actionId = "a", serverId = 2, unitIndex = unit, attemptGroup = group, status = status, lastErrorCode = error
    )

    private fun compute(
        order: MarketOrder, viewer: OrderViewer, items: List<MarketOrderItem> = listOf(item()), payments: List<MarketPayment> = emptyList(), refunds: List<MarketRefund> = emptyList(),
        deliveries: List<MarketDelivery> = emptyList(), shipments: List<MarketShipment> = emptyList()
    ): JsonObject = OrderAllowed.compute(order, items, payments, refunds, deliveries, shipments, viewer)

    private fun JsonObject.on() = flags.filter { getBoolean(it) == true }.toSet()

    @Test
    fun `every flag the contract names is present, and refundMax and refundModes too`() {
        val allowed = compute(order(OrderStatus.COMPLETED), both)

        for (flag in flags + listOf("refundMax", "refundModes")) assertTrue(allowed.containsKey(flag), flag)
        assertEquals(listOf("markPaid", "cancel", "refund", "refundMax", "refundModes", "review", "bankTransfer"), allowed.fieldNames().toList().take(7))
    }

    @Test
    fun `a pending order can be marked paid or cancelled with PAY, and nothing else money moves`() {
        assertEquals(setOf("markPaid", "cancel", "resendMail"), compute(order(OrderStatus.PENDING), both).on())
        assertEquals(setOf("markPaid", "cancel"), compute(order(OrderStatus.PENDING), pay).on())
        assertEquals(setOf("resendMail"), compute(order(OrderStatus.PENDING), manage).on(), "OM never moves money")
        assertEquals(emptySet<String>(), compute(order(OrderStatus.PENDING), viewOnly).on(), "OV alone holds no action")
    }

    @Test
    fun `an order in review can only be reviewed, by PAY`() {
        assertEquals(setOf("review", "resendMail"), compute(order(OrderStatus.REVIEW), both).on())
        assertFalse(compute(order(OrderStatus.REVIEW), manage).getBoolean("review"))
        assertFalse(compute(order(OrderStatus.REVIEW), both).getBoolean("markPaid"), "the review endpoint is the only way out of REVIEW")
    }

    @Test
    fun `a completed order can be refunded, disputed and anonymised with PAY and re-run or revoked with OM`() {
        val granted = listOf(delivery(DeliveryStatus.CONFIRMED))
        val failed = listOf(delivery(DeliveryStatus.FAILED))
        val withPay = compute(order(OrderStatus.COMPLETED), pay, deliveries = granted).on()
        val withManage = compute(order(OrderStatus.COMPLETED), manage, deliveries = granted).on()

        assertEquals(setOf("refund", "dispute", "anonymize"), withPay)
        assertEquals(setOf("revoke", "resendMail"), withManage, "a confirmed grant is only repeated with PAY too")
        assertEquals(setOf("rerunDelivery", "resendMail"), compute(order(OrderStatus.COMPLETED), manage, deliveries = failed).on())
        assertEquals(setOf("refund", "dispute", "anonymize", "rerunDelivery", "revoke", "resendMail"), compute(order(OrderStatus.COMPLETED), both, deliveries = granted).on())
    }

    @Test
    fun `refund needs money left, and a refund in flight takes its share of the remainder`() {
        val base = order(OrderStatus.PARTIALLY_REFUNDED, total = 1000, refunded = 400)
        val open = MarketRefund(id = 1, orderId = 1, paymentId = 9, status = RefundStatus.PENDING, amount = 300, gatewayAmount = 300)
        val done = order(OrderStatus.REFUNDED, total = 1000, refunded = 1000)

        assertEquals(6.0, compute(base, pay).getDouble("refundMax"))
        assertEquals(3.0, compute(base, pay, refunds = listOf(open)).getDouble("refundMax"), "the in-flight 3.00 is reserved")
        assertFalse(compute(done, pay).getBoolean("refund"))
        assertEquals(0.0, compute(done, pay).getDouble("refundMax"))
        assertEquals(0, compute(done, pay).getJsonArray("refundModes").size())
        assertFalse(compute(order(OrderStatus.COMPLETED), manage).getBoolean("refund"), "OM does not refund")
        assertEquals(0.0, compute(order(OrderStatus.COMPLETED), manage).getDouble("refundMax"))
    }

    @Test
    fun `a refund of another attempt is not on the books of the order`() {
        val other = MarketRefund(id = 1, orderId = 1, paymentId = 77, status = RefundStatus.PENDING, amount = 900, gatewayAmount = 900)

        assertEquals(10.0, compute(order(OrderStatus.COMPLETED), pay, refunds = listOf(other)).getDouble("refundMax"))
    }

    @Test
    fun `refund modes follow the lines left to refund`() {
        fun modes(items: List<MarketOrderItem>) = compute(order(OrderStatus.COMPLETED), pay, items = items).getJsonArray("refundModes").map { it.toString() }

        assertEquals(listOf("FULL", "PARTIAL", "PER_LINE", "MANUAL"), modes(listOf(item())))
        assertEquals(listOf("FULL", "PARTIAL", "MANUAL"), modes(listOf(item(quantity = 1, refundedQuantity = 1, refundedAmount = 1000))), "no line left: no per-line refund")
        assertEquals(listOf("FULL", "PARTIAL", "MANUAL"), modes(listOf(item(kind = OrderItemKind.CREDIT_TOPUP))))
    }

    @Test
    fun `bank transfer follows the open attempt, and a late approval the released order`() {
        fun attempt(status: PaymentStatus, provider: String = "bank-transfer") = MarketPayment(id = 1, orderId = 1, providerId = provider, status = status)

        assertTrue(compute(order(OrderStatus.PENDING), pay, payments = listOf(attempt(PaymentStatus.PROCESSING))).getBoolean("bankTransfer"))
        assertTrue(compute(order(OrderStatus.PENDING), pay, payments = listOf(attempt(PaymentStatus.PENDING))).getBoolean("bankTransfer"))
        assertFalse(compute(order(OrderStatus.PENDING), pay, payments = listOf(attempt(PaymentStatus.FAILED))).getBoolean("bankTransfer"))
        assertFalse(compute(order(OrderStatus.PENDING), pay, payments = listOf(attempt(PaymentStatus.PENDING, "stripe"))).getBoolean("bankTransfer"), "another provider")
        assertFalse(compute(order(OrderStatus.PENDING), pay).getBoolean("bankTransfer"), "no attempt")
        assertTrue(compute(order(OrderStatus.EXPIRED), pay, payments = listOf(attempt(PaymentStatus.EXPIRED))).getBoolean("bankTransfer"))
        assertFalse(compute(order(OrderStatus.EXPIRED), pay, payments = listOf(attempt(PaymentStatus.SUCCEEDED))).getBoolean("bankTransfer"))
        assertFalse(compute(order(OrderStatus.COMPLETED), pay, payments = listOf(attempt(PaymentStatus.SUCCEEDED))).getBoolean("bankTransfer"))
        assertFalse(compute(order(OrderStatus.PENDING), manage, payments = listOf(attempt(PaymentStatus.PENDING))).getBoolean("bankTransfer"))
    }

    @Test
    fun `a dispute can be opened on a paid or refunded order only`() {
        for (status in OrderStatus.entries) {
            assertEquals(status in setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED), compute(order(status), pay).getBoolean("dispute"), status.name)
        }
    }

    @Test
    fun `anonymise needs a settled order`() {
        for (status in OrderStatus.entries) {
            assertEquals(status != OrderStatus.PENDING && status != OrderStatus.REVIEW, compute(order(status), pay).getBoolean("anonymize"), status.name)
        }
    }

    @Test
    fun `re-run needs a paid order and a row that can run again`() {
        val failed = listOf(delivery(DeliveryStatus.FAILED))

        assertTrue(compute(order(OrderStatus.COMPLETED), manage, deliveries = failed).getBoolean("rerunDelivery"))
        assertTrue(compute(order(OrderStatus.PARTIALLY_REFUNDED), manage, deliveries = failed).getBoolean("rerunDelivery"))
        assertFalse(compute(order(OrderStatus.REFUNDED), manage, deliveries = failed).getBoolean("rerunDelivery"))
        assertFalse(compute(order(OrderStatus.COMPLETED), manage, deliveries = emptyList()).getBoolean("rerunDelivery"))
        assertFalse(compute(order(OrderStatus.COMPLETED), manage, deliveries = listOf(delivery(DeliveryStatus.SENT))).getBoolean("rerunDelivery"), "an open row is skipped")
        assertFalse(compute(order(OrderStatus.COMPLETED), manage, deliveries = listOf(delivery(DeliveryStatus.CONFIRMED))).getBoolean("rerunDelivery"), "a taken effect needs PAY")
        assertTrue(compute(order(OrderStatus.COMPLETED), both, deliveries = listOf(delivery(DeliveryStatus.CONFIRMED))).getBoolean("rerunDelivery"))
        assertFalse(compute(order(OrderStatus.COMPLETED), pay, deliveries = failed).getBoolean("rerunDelivery"), "re-run is an OM action")
    }

    @Test
    fun `revoke needs a granted unit that no live REVOKE row covers yet`() {
        val granted = delivery(DeliveryStatus.CONFIRMED)
        val revoked = delivery(DeliveryStatus.CONFIRMED, phase = DeliveryPhase.REVOKE)
        val cancelledRevoke = delivery(DeliveryStatus.CANCELLED, phase = DeliveryPhase.REVOKE)

        assertTrue(compute(order(OrderStatus.COMPLETED), manage, deliveries = listOf(granted)).getBoolean("revoke"))
        assertFalse(compute(order(OrderStatus.COMPLETED), manage, deliveries = listOf(granted, revoked)).getBoolean("revoke"))
        assertTrue(compute(order(OrderStatus.COMPLETED), manage, deliveries = listOf(granted, cancelledRevoke)).getBoolean("revoke"), "a cancelled revoke did not take the unit back")
        assertTrue(compute(order(OrderStatus.COMPLETED), manage, deliveries = listOf(granted, revoked.let { delivery(DeliveryStatus.CONFIRMED, phase = DeliveryPhase.REVOKE, unit = 1) })).getBoolean("revoke"), "another unit")
        assertFalse(compute(order(OrderStatus.COMPLETED), manage, deliveries = emptyList()).getBoolean("revoke"))
        assertFalse(compute(order(OrderStatus.PENDING), manage, deliveries = listOf(granted)).getBoolean("revoke"), "unpaid")
        assertFalse(compute(order(OrderStatus.COMPLETED), pay, deliveries = listOf(granted)).getBoolean("revoke"))
    }

    @Test
    fun `create shipment needs a paid shipping order with an address, an open line and no open dispute`() {
        val line = listOf(item(quantity = 2, physical = true))
        val shipping = order(OrderStatus.COMPLETED, shipping = true)

        assertTrue(compute(shipping, manage, items = line).getBoolean("createShipment"))
        assertFalse(compute(shipping, pay, items = line).getBoolean("createShipment"), "OM action")
        assertFalse(compute(order(OrderStatus.COMPLETED, shipping = false), manage, items = line).getBoolean("createShipment"))
        assertFalse(compute(order(OrderStatus.PENDING, shipping = true), manage, items = line).getBoolean("createShipment"))
        assertFalse(compute(order(OrderStatus.COMPLETED, shipping = true, dispute = DisputeStatus.OPEN), manage, items = line).getBoolean("createShipment"))
        assertFalse(compute(shipping, manage, items = listOf(item(quantity = 2, physical = true, shipped = 2))).getBoolean("createShipment"), "everything shipped")
        assertFalse(compute(shipping, manage, items = listOf(item(quantity = 2, physical = true, refundedQuantity = 2))).getBoolean("createShipment"), "everything refunded")
        assertFalse(compute(shipping, manage, items = listOf(item(physical = false))).getBoolean("createShipment"), "no physical line")
        assertFalse(compute(shipping, manage, items = listOf(item(physical = true, kind = OrderItemKind.BUNDLE))).getBoolean("createShipment"), "the bundle line itself does not ship")
        assertFalse(compute(MarketOrder(id = 1, userId = 5, status = OrderStatus.COMPLETED, paidAt = 1, requiresShipping = true, shippingAddress = null), manage, items = line).getBoolean("createShipment"))
    }

    @Test
    fun `the shipping address can be edited until a live shipment exists`() {
        val shipping = order(OrderStatus.COMPLETED, shipping = true)
        val live = MarketShipment(id = 1, orderId = 1, itemsReleased = false)
        val released = MarketShipment(id = 2, orderId = 1, itemsReleased = true)

        assertTrue(compute(shipping, manage).getBoolean("editShippingAddress"))
        assertTrue(compute(order(OrderStatus.PENDING, shipping = true), manage).getBoolean("editShippingAddress"))
        assertFalse(compute(shipping, manage, shipments = listOf(live)).getBoolean("editShippingAddress"))
        assertTrue(compute(shipping, manage, shipments = listOf(released)).getBoolean("editShippingAddress"), "a returned parcel freed the order again")
        assertFalse(compute(order(OrderStatus.COMPLETED, shipping = false), manage).getBoolean("editShippingAddress"))
        assertFalse(compute(order(OrderStatus.CANCELLED, shipping = true), manage).getBoolean("editShippingAddress"))
        assertFalse(compute(shipping, pay).getBoolean("editShippingAddress"))
    }

    @Test
    fun `an e-mail can be resent for a paid or open order, with OM`() {
        for (status in OrderStatus.entries) {
            val paid = order(status)
            val expected = paid.paidAt != null || status == OrderStatus.PENDING || status == OrderStatus.REVIEW

            assertEquals(expected, compute(paid, manage).getBoolean("resendMail"), status.name)
        }

        assertFalse(compute(order(OrderStatus.COMPLETED), pay).getBoolean("resendMail"))
        assertFalse(compute(order(OrderStatus.CANCELLED, paid = false), manage).getBoolean("resendMail"))
    }

    @Test
    fun `chargeback actions are offered only while one is held for confirmation`() {
        val held = delivery(DeliveryStatus.CANCELLED, source = DeliverySourceType.CHARGEBACK_ACTION, sourceId = 4, error = "NEEDS_CONFIRMATION")
        val confirmed = delivery(DeliveryStatus.CONFIRMED, source = DeliverySourceType.CHARGEBACK_ACTION, sourceId = 4, group = 1)
        val cancelledOther = delivery(DeliveryStatus.CANCELLED, source = DeliverySourceType.CHARGEBACK_ACTION, sourceId = 4, error = "SERVER_REMOVED")

        assertTrue(compute(order(OrderStatus.CHARGEBACK), pay, deliveries = listOf(held)).getBoolean("runChargebackActions"))
        assertFalse(compute(order(OrderStatus.CHARGEBACK), pay, deliveries = listOf(held, confirmed)).getBoolean("runChargebackActions"), "the later attempt replaced the held row")
        assertFalse(compute(order(OrderStatus.CHARGEBACK), pay, deliveries = listOf(cancelledOther)).getBoolean("runChargebackActions"))
        assertFalse(compute(order(OrderStatus.CHARGEBACK), pay).getBoolean("runChargebackActions"))
        assertFalse(compute(order(OrderStatus.CHARGEBACK), manage, deliveries = listOf(held)).getBoolean("runChargebackActions"))
    }

    @Test
    fun `OV alone holds no action in any state`() {
        for (status in OrderStatus.entries) {
            val allowed = compute(order(status, shipping = true), viewOnly, items = listOf(item(physical = true)), deliveries = listOf(delivery(DeliveryStatus.FAILED)))

            assertEquals(emptySet<String>(), allowed.on(), status.name)
            assertEquals(0.0, allowed.getDouble("refundMax"))
        }
    }
}
