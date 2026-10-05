package com.panomc.plugins.market.core.webhook

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** `DiscordRenderer` (MK-106; 08 section 16): escaping, the forced mention filter, limits, empty fields, the template check and the variables of an envelope. */
class DiscordRendererTest {
    private val labels = DiscordLabels("New purchase", "{username} bought {items.inline}", "Player", "Total", "Items")

    private fun paid(username: String = "Steve", items: List<JsonObject> = listOf(item("Gold Rank", 1)), createdAt: Long = 1_790_000_000_000L): JsonObject =
        JsonObject()
            .put("id", "e1").put("event", "order.paid").put("createdAt", createdAt).put("apiVersion", 1).put("testMode", false)
            .put("store", JsonObject().put("name", "Test Craft").put("url", "https://shop.example.com"))
            .put(
                "data",
                JsonObject()
                    .put("order", JsonObject().put("id", 42L).put("publicId", "AB12CD").put("url", "https://shop.example.com/store/order/AB12CD").put("total", BigDecimal("19.90")).put("currency", "EUR"))
                    .put("buyer", JsonObject().put("username", username).put("userId", 5L).put("uuid", null).put("email", "secret@example.com"))
                    .put("recipient", JsonObject().put("username", username).put("userId", 5L).put("uuid", null))
                    .put("items", JsonArray(items))
            )

    private fun item(name: String, quantity: Int, variant: String? = null) =
        JsonObject().put("productName", name).put("quantity", quantity).put("variantName", variant)

    private fun render(template: String?, envelope: JsonObject = paid(), event: String = "order.paid"): JsonObject =
        JsonObject(DiscordRenderer.render(template, DiscordRenderer.vars(event, envelope, labels)))

    private fun embed(body: JsonObject): JsonObject = body.getJsonArray("embeds").getJsonObject(0)

    @Test
    fun `the built-in body is valid JSON for names with quotes, backslashes and newlines`() {
        val nasty = "Ste\"ve\\\n\r\t{x} "
        val body = render(null, paid(username = nasty, items = listOf(item("Rank \"VIP\"\nline2", 2, "Gold\\Plus"))))
        val fields = embed(body).getJsonArray("fields")

        assertEquals(nasty, fields.getJsonObject(0).getString("value"))
        assertTrue(fields.getJsonObject(2).getString("value").startsWith("2× Rank \"VIP\"\nline2 (Gold\\Plus)"))
        assertEquals("Test Craft", body.getString("username"))
        assertEquals("19.90 EUR", fields.getJsonObject(1).getString("value"))
        assertEquals("https://shop.example.com/store/order/AB12CD", embed(body).getString("url"))
        assertEquals(3066993, embed(body).getInteger("color"))
        assertEquals("Test Craft · #42", embed(body).getJsonObject("footer").getString("text"))
    }

    @Test
    fun `allowed_mentions is forced even when the template sets something else, and an everyone ping stays plain text`() {
        val template = """{"content":"hi {username}","allowed_mentions":{"parse":["everyone","users"]}}"""
        val body = render(template, paid(username = "@everyone"))

        assertEquals(JsonObject().put("parse", JsonArray()), body.getJsonObject("allowed_mentions"))
        assertEquals("hi @everyone", body.getString("content"))
        assertEquals(JsonObject().put("parse", JsonArray()), render(null).getJsonObject("allowed_mentions"))
    }

    @Test
    fun `a custom template with a broken shape falls back to the built-in one and says so`() {
        val vars = DiscordRenderer.vars("order.paid", paid(), labels)
        val broken = DiscordRenderer.renderChecked("""{"content": {username} }""", vars)

        assertTrue(broken.templateError)
        assertNotNull(JsonObject(broken.body).getJsonArray("embeds"))

        val ok = DiscordRenderer.renderChecked("""{"content":"{username}"}""", vars)

        assertFalse(ok.templateError)
        assertEquals("Steve", JsonObject(ok.body).getString("content"))
        assertFalse(DiscordRenderer.renderChecked(null, vars).templateError)
    }

    @Test
    fun `the Discord limits are enforced by truncation with an ellipsis`() {
        val long = "x".repeat(5000)
        val template = JsonObject()
            .put("content", long)
            .put(
                "embeds",
                JsonArray().add(
                    JsonObject().put("title", long).put("description", long)
                        .put("footer", JsonObject().put("text", long)).put("author", JsonObject().put("name", long))
                        .put("fields", JsonArray().add(JsonObject().put("name", long).put("value", long)))
                )
            ).encode()
        val body = JsonObject(DiscordRenderer.render(template, emptyMap()))

        assertEquals(2000, body.getString("content").length)
        assertTrue(body.getString("content").endsWith("…"))

        val e = embed(body)

        assertEquals(256, e.getString("title").length)
        assertEquals(4096, e.getString("description").length)
        assertEquals(2048, e.getJsonObject("footer").getString("text").length)
        assertEquals(256, e.getJsonObject("author").getString("name").length)
        // title 256 + description 4096 + footer 2048 + author 256 already exceed 6000: the field is dropped to fit
        assertNull(e.getJsonArray("fields"))
    }

    @Test
    fun `fields beyond 25 and embeds beyond 10 are dropped and a field set is cut from the end to fit 6000`() {
        val fields = JsonArray()

        for (i in 1..30) fields.add(JsonObject().put("name", "n$i").put("value", "v$i"))

        val embeds = JsonArray()

        for (i in 1..12) embeds.add(JsonObject().put("title", "e$i").put("fields", fields.copy()))

        val body = JsonObject(DiscordRenderer.render(JsonObject().put("embeds", embeds).encode(), emptyMap()))

        assertEquals(10, body.getJsonArray("embeds").size())
        assertEquals(25, embed(body).getJsonArray("fields").size())

        val wide = JsonArray()

        for (i in 1..10) wide.add(JsonObject().put("name", "n$i").put("value", "y".repeat(1000)))

        val fitted = embed(JsonObject(DiscordRenderer.render(JsonObject().put("embeds", JsonArray().add(JsonObject().put("title", "t").put("fields", wide))).encode(), emptyMap())))

        // 1 + 10 * (2 + 1000) = 10021 > 6000: fields are dropped from the end until it fits
        assertTrue(fitted.getJsonArray("fields").size() in 1..5)
        assertEquals("n1", fitted.getJsonArray("fields").getJsonObject(0).getString("name"))
    }

    @Test
    fun `fields with an empty value and empty url or timestamp keys are dropped`() {
        val template = """{"embeds":[{"title":"t","url":"{order.url}","timestamp":"{shipment.trackingNumber}",
            "fields":[{"name":"a","value":"{refund.reason}"},{"name":"b","value":"keep"}]}]}"""
        val e = embed(render(template, paid(), "order.paid"))

        assertEquals("https://shop.example.com/store/order/AB12CD", e.getString("url"))
        assertFalse(e.containsKey("timestamp"))
        assertEquals(1, e.getJsonArray("fields").size())
        assertEquals("b", e.getJsonArray("fields").getJsonObject(0).getString("name"))
    }

    @Test
    fun `an event without an order drops the empty fields, the url and the dangling order number`() {
        val ping = JsonObject()
            .put("id", "p").put("event", "test.ping").put("createdAt", 1_790_000_000_000L)
            .put("store", JsonObject().put("name", "Test Craft").put("url", "https://shop.example.com"))
            .put("data", JsonObject().put("message", "ping").put("endpointId", 3))
        val e = embed(render(null, ping, "test.ping"))

        assertFalse(e.containsKey("url"))
        assertEquals("Test Craft", e.getJsonObject("footer").getString("text"))
        assertEquals(9807270, e.getInteger("color"))
        assertFalse(e.containsKey("fields"))
    }

    @Test
    fun `event colours follow the table`() {
        assertEquals(3066993, DiscordRenderer.colorOf("order.paid"))
        assertEquals(15105570, DiscordRenderer.colorOf("order.refunded"))
        assertEquals(15158332, DiscordRenderer.colorOf("order.chargeback"))
        assertEquals(3447003, DiscordRenderer.colorOf("order.chargeback.won"))
        assertEquals(10181046, DiscordRenderer.colorOf("subscription.renewed"))
        assertEquals(1752220, DiscordRenderer.colorOf("shipment.delivered"))
        assertEquals(9807270, DiscordRenderer.colorOf("action.grant"))
        assertEquals(9807270, DiscordRenderer.colorOf("test.ping"))
    }

    @Test
    fun `items lists one line per item, at most 10 then a counter, inline joined with commas`() {
        val many = (1..13).map { item("P$it", it) }
        val vars = DiscordRenderer.vars("order.paid", paid(items = many), labels)
        val lines = vars.getValue("items").split("\n")

        assertEquals(11, lines.size)
        assertEquals("1× P1", lines[0])
        assertEquals("+3 more", lines[10])
        assertTrue(vars.getValue("items.inline").startsWith("1× P1, 2× P2"))
        assertEquals("Steve bought 1× Gold Rank", DiscordRenderer.vars("order.paid", paid(), labels).getValue("event.description"))
    }

    @Test
    fun `the variables never carry an e-mail address and an unknown token stays verbatim`() {
        val vars = DiscordRenderer.vars("order.paid", paid(), labels)

        assertFalse(vars.values.any { it.contains("secret@example.com") })
        assertEquals("19.90", vars.getValue("order.total"))
        assertEquals("42", vars.getValue("order.id"))

        val body = render("""{"content":"{Enchantments:[{id:sharpness}]} {display} {nope.x}"}""")

        assertEquals("{Enchantments:[{id:sharpness}]} {display} {nope.x}", body.getString("content"))
    }

    @Test
    fun `a token default is used when the value is empty`() {
        val body = render("""{"content":"{refund.reason|no reason} / {username|nobody}"}""")

        assertEquals("no reason / Steve", body.getString("content"))
    }

    @Test
    fun `validate accepts the built-in template and the shapes of 16-3 and refuses the rest`() {
        assertNull(DiscordRenderer.validate(DiscordRenderer.BUILT_IN_TEMPLATE))
        assertNull(DiscordRenderer.validate("""{"content":"hello {username}"}"""))
        assertNull(DiscordRenderer.validate("""{"embeds":[{"color":{event.color},"title":"{event.title}"}]}"""))
        assertEquals("INVALID_JSON", DiscordRenderer.validate("not json"))
        assertEquals("INVALID_JSON", DiscordRenderer.validate("""{"username":"x"}"""))
        assertEquals("INVALID_JSON", DiscordRenderer.validate("""{"embeds":[]}"""))
        assertEquals("INVALID_JSON", DiscordRenderer.validate("""{"embeds":[1]}"""))
        assertEquals("INVALID_JSON", DiscordRenderer.validate("""{"content":{username}}"""), "a string token outside a string is not JSON")
        assertEquals("INVALID_JSON", DiscordRenderer.validate("""{"embeds":[${(1..11).joinToString(",") { "{}" }}]}"""))
        assertEquals("TOO_LONG", DiscordRenderer.validate("""{"content":"${"a".repeat(9000)}"}"""))
    }

    @Test
    fun `cut never splits a surrogate pair`() {
        val text = "a".repeat(9) + "😀" + "b"
        val out = DiscordRenderer.cut(text, 11)

        assertEquals(10, out.length)
        assertTrue(out.endsWith("…"))
        assertEquals("abc", DiscordRenderer.cut("abc", 3))
    }
}
