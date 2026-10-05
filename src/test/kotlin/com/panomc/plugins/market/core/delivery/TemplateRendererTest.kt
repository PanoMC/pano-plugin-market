package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.ProductFieldType
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TemplateRendererTest {
    private fun ctx(
        username: String = "Steve",
        productName: String = "VIP",
        gift: String? = null,
        uuid: String? = "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        fields: Map<String, FieldValue> = emptyMap()
    ) = VariableContext.build(
        VariableContext.Input(
            recipientUsername = username, buyerUsername = "Buyer", playerUuid = uuid, orderId = 9, orderTotal = 500, orderCurrency = "EUR",
            productName = productName, giftMessage = gift, fields = fields, quantity = 3
        )
    )

    @Test
    fun `tokens are replaced and unknown names stay verbatim`() {
        assertEquals("""{"p":"Steve","n":"VIP","q":"3","t":"5.00 EUR","x":"{display}"}""",
            TemplateRenderer.render("""{"p":"{username}","n":"{product.name}","q":"{quantity}","t":"{order.total} {order.currency}","x":"{display}"}""", ctx()))
    }

    @Test
    fun `values are JSON-string escaped so a hostile name keeps the document valid`() {
        val nasty = "Ste\"ve\\ \n x\r\t\u0001   {username}"
        val rendered = TemplateRenderer.render("""{"content":"bought by {username} for {product.name}"}""", ctx(username = nasty, productName = "a\"b\nc"))
        val json = JsonObject(rendered)
        assertEquals("bought by $nasty for a\"b\nc", json.getString("content"))
        assertEquals("\\\"", TemplateRenderer.jsonEscape("\""))
        assertEquals("\\u0000", TemplateRenderer.jsonEscape("\u0000"))
        assertEquals("\\u001f", TemplateRenderer.jsonEscape("\u001f"))
        assertEquals("\\u2028\\u2029", TemplateRenderer.jsonEscape("  "))
        assertEquals("plain Ünï", TemplateRenderer.jsonEscape("plain Ünï"))
    }

    @Test
    fun `no validators apply, gift message and free text pass`() {
        assertEquals("""{"m":"Happy birthday; \"you\" @everyone /op"}""",
            TemplateRenderer.render("""{"m":"{gift.message}"}""", ctx(gift = "Happy birthday; \"you\" @everyone /op")))
        val f = mapOf("note" to FieldValue(ProductFieldType.TEXT, "two words & more", null, usableInCommands = false))
        assertEquals("two words & more", TemplateRenderer.render("{field.note}", ctx(fields = f)))
    }

    @Test
    fun `empty values render as empty strings or their default`() {
        assertEquals("[][none]", TemplateRenderer.render("[{bundle.name}][{bundle.name|none}]", ctx()))
        assertEquals("[]", TemplateRenderer.render("[{expiresAt}]", ctx()))
    }

    @Test
    fun `uuid is the platform uuid or empty`() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", TemplateRenderer.render("{uuid}", ctx()))
        assertEquals("", TemplateRenderer.render("{uuid}", ctx(uuid = null)))
    }

    @Test
    fun `substitution is single pass, a value holding a token is not expanded`() {
        assertEquals("{order.id} Steve", TemplateRenderer.render("{product.name} {username}", ctx(productName = "{order.id}")))
        assertEquals("{username}{username}", TemplateRenderer.render("{player}", ctx(username = "{username}{username}")))
    }

    @Test
    fun `webhook-only names resolve from the extras and never in commands`() {
        val c = ctx().withExtras(mapOf("event.title" to "New \"purchase\"", "event.color" to "3066993", "store.name" to "My Store"))
        assertEquals("""{"t":"New \"purchase\"","c":3066993,"s":"My Store"}""", TemplateRenderer.render("""{"t":"{event.title}","c":{event.color},"s":"{store.name}"}""", c))
        assertEquals("x {event.title}", (CommandRenderer.render("x {event.title}", c) as CommandRenderer.Result.Ok).command)
        // without the extras the name stays verbatim
        assertEquals("{event.title}", TemplateRenderer.render("{event.title}", ctx()))
        assertTrue(TemplateRenderer.render("{event.title}", c).startsWith("New"))
    }

    @Test
    fun `text without tokens is unchanged`() {
        val t = """{"embeds":[{"title":"No tokens {here_}","color":1}]}"""
        assertEquals(t, TemplateRenderer.render(t, ctx()))
    }
}
