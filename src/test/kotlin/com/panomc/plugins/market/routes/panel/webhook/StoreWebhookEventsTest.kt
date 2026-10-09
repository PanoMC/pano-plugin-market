package com.panomc.plugins.market.routes.panel.webhook

import com.panomc.plugins.market.core.webhook.DiscordLabelSource
import com.panomc.plugins.market.core.webhook.DiscordLabels
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.core.webhook.WebhookSamples
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the market declares to core's webhook system at start (doc 06 section 4.4): the events with their samples, the Discord renderer on core's envelope. */
class StoreWebhookEventsTest {
    @Test
    fun `the declared events are the ten subscribable ones with a sample and the four action events that nobody can subscribe to`() {
        val events = storeWebhookEvents()

        assertEquals(WebhookEvents.SUBSCRIBABLE + WebhookEvents.ACTION_EVENTS, events.map { it.name })
        assertTrue(events.filter { it.name in WebhookEvents.SUBSCRIBABLE }.all { it.subscribable && it.sample != null })
        assertTrue(events.filter { it.name in WebhookEvents.ACTION_EVENTS }.all { !it.subscribable })
        // unique, and no source in a name: core adds `market.`
        assertEquals(events.size, events.map { it.name }.toSet().size)
        assertTrue(events.none { it.name.startsWith("market.") })
    }

    @Test
    fun `the samples are plain data of the shape each event carries and never an address or a token`() {
        for (name in WebhookEvents.SUBSCRIBABLE) {
            val sample = WebhookSamples.of(name)

            assertNotNull(sample, name)
            assertFalse(sample!!.encode().contains("shippingAddress"), name)
        }

        assertNotNull(WebhookSamples.of(WebhookEvents.ORDER_PAID)!!.getJsonObject("order"))
        assertNotNull(WebhookSamples.of(WebhookEvents.ORDER_REFUNDED)!!.getJsonObject("refund"))
        assertNotNull(WebhookSamples.of(WebhookEvents.ORDER_CHARGEBACK_WON)!!.getJsonObject("dispute"))
        assertNotNull(WebhookSamples.of(WebhookEvents.SUBSCRIPTION_STARTED)!!.getJsonObject("subscription"))
        assertNotNull(WebhookSamples.of(WebhookEvents.SHIPMENT_DELIVERED)!!.getJsonObject("shipment").getLong("deliveredAt"))
        assertNull(WebhookSamples.of("action.grant"))
    }

    @Test
    fun `the Discord renderer builds the body from core's envelope with the name without the source`(): Unit = runBlocking {
        val labels = DiscordLabelSource { DiscordLabels("New purchase", "{username} bought {items.inline}", "Player", "Total", "Items") }
        val envelope = JsonObject()
            .put("id", "e1").put("event", "market.order.paid").put("source", "market").put("createdAt", 1_790_000_000_000L).put("apiVersion", 1)
            .put("site", JsonObject().put("name", "Test Craft").put("url", "https://shop.example.com")).put("testMode", false)
            .put("data", WebhookSamples.of(WebhookEvents.ORDER_PAID))

        val rendered = DiscordWebhookRenderer(labels).render("order.paid", envelope, null)
        val embed = JsonObject(rendered.body).getJsonArray("embeds").getJsonObject(0)

        assertNull(rendered.warning)
        assertEquals("New purchase", embed.getString("title"))
        assertEquals("Steve bought 1× VIP Rank", embed.getString("description"))
        assertEquals("Test Craft · #1042", embed.getJsonObject("footer").getString("text"))

        val broken = DiscordWebhookRenderer(labels).render("order.paid", envelope, "{ not json")

        assertEquals(DiscordWebhookRenderer.TEMPLATE_ERROR, broken.warning)
        assertEquals("New purchase", JsonObject(broken.body).getJsonArray("embeds").getJsonObject(0).getString("title"))
    }
}
