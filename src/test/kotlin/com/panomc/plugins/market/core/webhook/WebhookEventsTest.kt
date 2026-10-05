package com.panomc.plugins.market.core.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WebhookEventsTest {
    @Test
    fun `the subscribable names are the list of 01 section 9_3`() {
        assertEquals(
            listOf(
                "order.paid", "order.refunded", "order.chargeback", "order.chargeback.won", "subscription.started",
                "subscription.renewed", "subscription.cancelled", "subscription.expired", "shipment.shipped", "shipment.delivered", "test.ping"
            ),
            WebhookEvents.SUBSCRIBABLE
        )
        assertFalse(WebhookEvents.isSubscribable("action.grant"))
        assertFalse(WebhookEvents.isSubscribable("order.created"))
    }

    @Test
    fun `an endpoint matches its listed names and the wildcard`() {
        assertTrue(WebhookEvents.matches("""["order.paid"]""", "order.paid"))
        assertFalse(WebhookEvents.matches("""["order.paid"]""", "order.refunded"))
        assertTrue(WebhookEvents.matches("""["*"]""", "order.refunded"))
        assertTrue(WebhookEvents.matches("""["order.paid","order.refunded"]""", "order.refunded"))
        assertFalse(WebhookEvents.matches("[]", "order.paid"))
    }

    @Test
    fun `the wildcard never covers test ping or action events`() {
        assertFalse(WebhookEvents.matches("""["*"]""", "test.ping"))
        assertFalse(WebhookEvents.matches("""["*"]""", "action.grant"))
        assertTrue(WebhookEvents.matches("""["test.ping"]""", "test.ping"))
    }

    @Test
    fun `an unreadable subscription matches nothing`() {
        assertFalse(WebhookEvents.matches("not json", "order.paid"))
        assertFalse(WebhookEvents.matches("""{"a":1}""", "order.paid"))
        assertFalse(WebhookEvents.matches("""[1,"*"]""", "order.paid"))
        assertFalse(WebhookEvents.matches("", "order.paid"))
    }

    @Test
    fun `the event id is deterministic per event, subject and endpoint`() {
        val a = WebhookEvents.eventId("order.paid", "42", 7)
        assertEquals(a, WebhookEvents.eventId("order.paid", "42", 7))
        assertEquals(UUID.nameUUIDFromBytes("order.paid:42:7".toByteArray()).toString(), a)
        assertNotEquals(a, WebhookEvents.eventId("order.paid", "42", 8))
        assertNotEquals(a, WebhookEvents.eventId("order.paid", "43", 7))
        assertNotEquals(a, WebhookEvents.eventId("order.refunded", "42", 7))
        assertEquals(UUID.nameUUIDFromBytes("action:99".toByteArray()).toString(), WebhookEvents.actionEventId(99))
    }
}
