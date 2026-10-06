package com.panomc.plugins.market.notification

import com.panomc.platform.notification.NotificationType
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The notification types of MK-172: the names the host derives from the class names, the market prefix, the 48-id-safe payloads and the mapping of a message. */
class MarketNotificationsTest {
    @Test
    fun `the host names the types as the alert type constants`() {
        assertEquals(AlertTypes.ORDER_REVIEW, MarketOrderReviewNotification().getName())
        assertEquals(AlertTypes.ORDER_ALERT, MarketOrderAlertNotification().getName())
        assertEquals(AlertTypes.DELIVERY_WAITING, MarketDeliveryWaitingNotification().getName())
        assertEquals("MARKET_DELIVERY_WAITING", AlertTypes.DELIVERY_WAITING)
        assertEquals("MARKET_ORDER_REVIEW", AlertTypes.ORDER_REVIEW)
    }

    @Test
    fun `every type is prefixed and none is a core name`() {
        val names = listOf(AlertTypes.ORDER_REVIEW, AlertTypes.ORDER_ALERT, AlertTypes.DELIVERY_WAITING)

        assertTrue(names.all { it.startsWith("MARKET_") })
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `a review message becomes the review notification with its fields`() {
        val n = notificationOf(AlertMessage(AlertTypes.ORDER_REVIEW, AlertAudience.PAYMENTS, mapOf("orderId" to 7L, "reason" to "LATE", "href" to "/market/orders/detail/7")))

        assertTrue(n is MarketOrderReviewNotification)

        val json = JsonObject.mapFrom(n)

        assertEquals(7L, json.getLong("orderId"))
        assertEquals("LATE", json.getString("reason"))
        assertEquals("/market/orders/detail/7", json.getString("href"))
        assertEquals("MARKET_ORDER_REVIEW", (n as NotificationType).getName())
    }

    @Test
    fun `an urgent delivery message carries the order and the flags, a waiting one the count`() {
        val urgent = JsonObject.mapFrom(
            notificationOf(
                AlertMessage(AlertTypes.DELIVERY_WAITING, AlertAudience.FULFILMENT, mapOf("orderId" to 7L, "deliveryId" to 11L, "serverId" to 3L, "urgent" to true, "failed" to true, "href" to "/x"))
            )
        )

        assertEquals(true, urgent.getBoolean("urgent"))
        assertEquals(true, urgent.getBoolean("failed"))
        assertEquals(7L, urgent.getLong("orderId"))
        assertEquals(11L, urgent.getLong("deliveryId"))

        val waiting = JsonObject.mapFrom(
            notificationOf(AlertMessage(AlertTypes.DELIVERY_WAITING, AlertAudience.FULFILMENT, mapOf("serverId" to 3, "serverName" to "Lobby", "count" to 4, "urgent" to false, "failed" to false)))
        )

        assertFalse(waiting.getBoolean("urgent"))
        assertEquals(4L, waiting.getLong("count"))
        assertEquals("Lobby", waiting.getString("serverName"))
        assertEquals(3L, waiting.getLong("serverId"))
    }

    @Test
    fun `an order alert message becomes the alert notification and an unknown type is refused`() {
        val n = notificationOf(AlertMessage(AlertTypes.ORDER_ALERT, AlertAudience.PAYMENTS, mapOf("orderId" to 7, "code" to "OVER_REFUND")))

        assertEquals("OVER_REFUND", JsonObject.mapFrom(n).getString("code"))
        assertEquals(7L, JsonObject.mapFrom(n).getLong("orderId"))
        assertThrows(IllegalArgumentException::class.java) { notificationOf(AlertMessage("MARKET_NOPE", AlertAudience.PAYMENTS, emptyMap())) }
    }

    @Test
    fun `the audiences name the nodes of the spec`() {
        assertEquals(setOf("PAY"), AlertAudience.PAYMENTS.nodes.map { it.shortName }.toSet())
        assertEquals(setOf("OM", "SET"), AlertAudience.FULFILMENT.nodes.map { it.shortName }.toSet())
    }
}
