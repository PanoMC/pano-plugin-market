package com.panomc.plugins.market.core.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** The store's event names (without the source: core adds `market.`), the action event id and the owner reference of a direct row (doc 06 section 4). */
class WebhookEventsTest {
    @Test
    fun `the subscribable store events are the ten of the contract and none is an action event`() {
        assertEquals(
            listOf(
                "order.paid", "order.refunded", "order.chargeback", "order.chargeback.won",
                "subscription.started", "subscription.renewed", "subscription.cancelled", "subscription.expired",
                "shipment.shipped", "shipment.delivered"
            ),
            WebhookEvents.SUBSCRIBABLE
        )
        assertTrue(WebhookEvents.SUBSCRIBABLE.none { it in WebhookEvents.ACTION_EVENTS })
        assertEquals(listOf("action.grant", "action.renew", "action.expire", "action.revoke"), WebhookEvents.ACTION_EVENTS)
    }

    @Test
    fun `every name is a valid core event name (segments of a-z 0-9 _ -, at most 63 characters)`() {
        val core = Regex("^[a-z0-9_-]+$")

        for (name in WebhookEvents.SUBSCRIBABLE + WebhookEvents.ACTION_EVENTS) {
            assertTrue(name.length <= 63, name)
            assertTrue(name.split('.').all { core.matches(it) }, name)
            assertTrue(!name.startsWith("market.") && !name.startsWith("core."), "the source is core's to add: $name")
        }
    }

    @Test
    fun `the action event id is deterministic per delivery`() {
        assertEquals(UUID.nameUUIDFromBytes("action:99".toByteArray()).toString(), WebhookEvents.actionEventId(99))
        assertEquals(WebhookEvents.actionEventId(5), WebhookEvents.actionEventId(5))
        assertTrue(WebhookEvents.actionEventId(5) != WebhookEvents.actionEventId(6))
    }

    @Test
    fun `the owner reference of a direct row names its delivery and nothing else does`() {
        assertEquals("delivery:31", WebhookEvents.ownerRefOf(31))
        assertEquals(31L, WebhookEvents.deliveryIdOf(WebhookEvents.ownerRefOf(31)))
        assertNull(WebhookEvents.deliveryIdOf(null))
        assertNull(WebhookEvents.deliveryIdOf("order:31"))
        assertNull(WebhookEvents.deliveryIdOf("delivery:x"))
    }
}
