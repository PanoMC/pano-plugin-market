package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.ProductFieldType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class CommandRendererTest {
    // 2026-10-05T10:30:00Z; entitlement ends 2026-11-04T10:00:00Z (29 d 23 h 30 min later).
    private val now = 1_791_196_200_000L
    private val end = 1_793_786_400_000L

    private fun input(
        username: String = "Steve",
        buyer: String = "Alex_1",
        fields: Map<String, FieldValue> = emptyMap(),
        expires: Long? = end,
        serverId: Long? = 3,
        origin: UsernameOrigin = UsernameOrigin.BUYER,
        variantAttributes: Map<String, String> = mapOf("color" to "Dark Red", "size" to "XL"),
        productName: String = "VIP Rank (30 days)",
        giftMessage: String? = "Happy birthday!"
    ) = VariableContext.Input(
        recipientUsername = username,
        buyerUsername = buyer,
        recipientOrigin = origin,
        playerUuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        orderId = 42,
        orderPublicId = "ABCD1234EFGH5678JKMN",
        orderTotal = 1999,
        orderCurrency = "USD",
        giftMessage = giftMessage,
        productId = 7,
        productName = productName,
        productSlug = "vip-rank",
        productSku = "VIP-30",
        bundleName = "Starter Bundle",
        variantName = "Gold",
        variantSku = "VIP-30-G",
        variantAttributes = variantAttributes,
        fields = fields,
        quantity = 2,
        unit = 1,
        serverId = serverId,
        serverName = "Survival-1",
        lineTotal = 1999,
        lineQuantity = 3,
        expiresAtMillis = expires,
        phase = DeliveryPhase.RENEW,
        now = now,
        zone = ZoneId.of("Europe/Istanbul")
    )

    private fun ctx(i: VariableContext.Input = input()) = VariableContext.build(i)

    private fun ok(template: String, c: VariableContext = ctx()): String {
        val r = CommandRenderer.render(template, c)
        check(r is CommandRenderer.Result.Ok) { "expected Ok but got ${(r as CommandRenderer.Result.Fail).message}" }
        return r.command
    }

    private fun fail(template: String, c: VariableContext = ctx()): CommandRenderer.Result.Fail {
        val r = CommandRenderer.render(template, c)
        check(r is CommandRenderer.Result.Fail) { "expected Fail but got Ok(${(r as CommandRenderer.Result.Ok).command})" }
        assertEquals("RENDER_ERROR", r.code)
        return r
    }

    @Test
    fun `every variable of 08 section 3_2 renders`() {
        val expected = linkedMapOf(
            "username" to "Steve", "player" to "Steve", "recipient.username" to "Steve", "buyer.username" to "Alex_1",
            "order.id" to "42", "order.publicId" to "ABCD1234EFGH5678JKMN", "order.total" to "19.99", "order.currency" to "USD",
            "product.id" to "7", "product.name" to "VIP Rank (30 days)", "product" to "VIP Rank (30 days)", "product.slug" to "vip-rank",
            "product.sku" to "VIP-30", "bundle.name" to "Starter Bundle",
            "variant.name" to "Gold", "variant.sku" to "VIP-30-G", "variant.color" to "Dark Red", "variant.size" to "XL",
            "quantity" to "2", "unit" to "1", "server.id" to "3", "server.name" to "Survival-1",
            "price" to "6.66", "expiresAt" to "1793786400", "expiresAt.iso" to "2026-11-04T10:00:00Z",
            "period.days" to "30", "period.seconds" to "2590200",
            "date" to "2026-10-05", "time" to "13:30", "phase" to "renew"
        )

        for ((name, value) in expected) assertEquals(value, ok("{$name}"), name)

        assertEquals("give Steve 2 VIP-30", ok("give {username} {quantity} {product.sku}"))
    }

    @Test
    fun `uuid stays late-bound and is not counted as an empty variable`() {
        assertEquals("lp user {uuid} info", ok("lp user {uuid} info"))
        assertEquals("a {uuid} b", ok("a {uuid|zzz} b"))
        val noUuid = VariableContext.build(VariableContext.Input(recipientUsername = "Steve"))
        assertEquals("x {uuid}", ok("x {uuid}", noUuid))
    }

    @Test
    fun `price and period values are exact`() {
        assertEquals("33.33", ok("{price}", ctx(VariableContext.Input(recipientUsername = "Steve", lineTotal = 6666, lineQuantity = 2))))
        // 1.00 / 3 = 0.333.. -> 0.33, 2.00 / 3 = 0.666.. -> 0.67 (half up on the x100 value).
        assertEquals("0.33", ok("{price}", ctx(VariableContext.Input(recipientUsername = "Steve", lineTotal = 100, lineQuantity = 3))))
        assertEquals("0.67", ok("{price}", ctx(VariableContext.Input(recipientUsername = "Steve", lineTotal = 200, lineQuantity = 3))))
        // exactly one day left is one day, one second more is two (rounded up); an end in the past is 0.
        val c = { e: Long -> ctx(VariableContext.Input(recipientUsername = "Steve", expiresAtMillis = e, now = 1000)) }
        assertEquals("1 86400", ok("{period.days} {period.seconds}", c(1000 + 86_400_000)))
        assertEquals("2 86401", ok("{period.days} {period.seconds}", c(1000 + 86_401_000)))
        assertEquals("2 86401", ok("{period.days} {period.seconds}", c(1000 + 86_400_000 + 1))) // a started second counts
        assertEquals("0 0", ok("{period.days} {period.seconds}", c(500)))
    }

    @Test
    fun `permanent ownership leaves the expiry variables empty`() {
        val c = ctx(input(expires = null))
        for (name in listOf("expiresAt", "expiresAt.iso", "period.days", "period.seconds")) {
            val r = fail("x {$name}", c)
            assertEquals(name, r.variable)
            assertEquals("EMPTY_VARIABLE", r.reason)
        }
        assertEquals("tempadd 30", ok("tempadd {period.days|30}", c))
    }

    @Test
    fun `a name outside the catalogue is left verbatim`() {
        val nbt = "give {username} diamond_sword{Enchantments:[{id:sharpness,lvl:5}],display:{Name:'x'}} {display} {Unknown} {a.b.c}"
        assertEquals("give Steve diamond_sword{Enchantments:[{id:sharpness,lvl:5}],display:{Name:'x'}} {display} {Unknown} {a.b.c}", ok(nbt))
        assertEquals("{ username } {USERNAME} {event.title}", ok("{ username } {USERNAME} {event.title}"))
    }

    @Test
    fun `rejects CR, LF, NUL and other control characters in the command`() {
        for (c in listOf("\r", "\n", "\u0000", "\t", "\u001b", "\u007f")) {
            assertEquals("INVALID_COMMAND", fail("say a${c}b").reason, c.codePointAt(0).toString())
        }
        // in a substituted value: the name fails its alphabet
        for (bad in listOf("Steve\n/op", "Steve\r", "Steve\u0000")) {
            assertEquals("INVALID_VALUE", fail("say {username}", ctx(input(username = bad))).reason, bad)
        }
    }

    @Test
    fun `rejects a leading slash and empty commands`() {
        assertEquals("INVALID_COMMAND", fail("/say hi").reason)
        assertEquals("INVALID_COMMAND", fail("  /say hi").reason)
        assertEquals("INVALID_COMMAND", fail("").reason)
        assertEquals("INVALID_COMMAND", fail("   ").reason)
        assertEquals("say /hi", ok("say /hi"))
        assertEquals("say hi", ok("  say hi  "))
    }

    @Test
    fun `buyer usernames are 3 to 16 of letters digits underscore`() {
        for (name in listOf("Steve", "abc", "A_b_C_d_E_f_G_h_", "x1_")) assertEquals("kick $name", ok("kick {username}", ctx(input(username = name))), name)
        for (bad in listOf("ab", "a".repeat(17), "Ste ve", "St.ve", "*Steve", ".Steve", "Ste;ve", "Steve&", "émile", "___", "Steve|op", "@a", "Steve-1", "")) {
            val r = fail("kick {username}", ctx(input(username = bad)))
            assertEquals("username", r.variable, bad)
            assertTrue(r.reason == "INVALID_VALUE" || r.reason == "EMPTY_VARIABLE", bad)
        }
        assertEquals("INVALID_VALUE", fail("{buyer.username}", ctx(input(buyer = "no spaces"))).reason)
    }

    @Test
    fun `admin usernames allow dot and star, 1 to 32, one alphanumeric`() {
        for (name in listOf(".Steve", "*Steve", "a", "x.y*z", "a".repeat(32), "_a_")) {
            assertEquals("op $name", ok("op {username}", ctx(input(username = name, origin = UsernameOrigin.ADMIN))), name)
        }
        for (bad in listOf("a".repeat(33), "..", "**", "___", "Ste ve", "St;ve", "St\$ve", "@a", "St/ve", "St%ve", "St{ve")) {
            assertEquals("INVALID_VALUE", fail("op {username}", ctx(input(username = bad, origin = UsernameOrigin.ADMIN))).reason, bad)
        }
        assertTrue(CommandRenderer.isAdminUsername(".Steve"))
        assertFalse(CommandRenderer.isAdminUsername("***"))
        assertTrue(CommandRenderer.isBuyerUsername("Steve"))
        assertFalse(CommandRenderer.isBuyerUsername(".Steve"))
    }

    @Test
    fun `free text keeps only its alphabet and loses control characters`() {
        assertEquals("tell Steve VIP Rank (30 days)", ok("tell {username} {product.name}"))
        assertEquals("a b-c_d.e,f:g+h#i(j)[k]", ok("{product.name}", ctx(input(productName = "a b-c_d.e,f:g+h#i(j)[k]"))))
        assertEquals("AB", ok("{product.name}", ctx(input(productName = "A\u0001B"))))
        assertEquals("ABC", ok("{product.name}", ctx(input(productName = "A\r\nB\u0000C"))))
        for (bad in listOf("a\"b", "a'b", "a\\b", "a/b", "a@b", "a&b", "a§b", "a;b", "a|b", "a\$b", "a%b", "a{b", "a}b", "a".repeat(129), "@everyone")) {
            assertEquals("INVALID_VALUE", fail("{product.name}", ctx(input(productName = bad))).reason, bad)
        }
        assertEquals("Ünïcode Ürün", ok("{product}", ctx(input(productName = "Ünïcode Ürün"))))
        assertEquals("INVALID_VALUE", fail("{variant.color}", ctx(input(variantAttributes = mapOf("color" to "a;b")))).reason)
        assertEquals("EMPTY_VARIABLE", fail("{variant.material}").reason)
    }

    @Test
    fun `identifier decimal and number kinds are validated`() {
        assertEquals("INVALID_VALUE", fail("{order.publicId}", ctx(VariableContext.Input(recipientUsername = "Steve", orderPublicId = "a b"))).reason)
        assertEquals("INVALID_VALUE", fail("{order.currency}", ctx(VariableContext.Input(recipientUsername = "Steve", orderCurrency = "US;D"))).reason)
        assertEquals("INVALID_VALUE", fail("{product.slug}", ctx(VariableContext.Input(recipientUsername = "Steve", productSlug = "a/b"))).reason)
        assertEquals("-5", ok("{quantity}", ctx(VariableContext.Input(recipientUsername = "Steve", quantity = -5))))
        assertEquals("0.00", ok("{order.total}", ctx(VariableContext.Input(recipientUsername = "Steve", orderTotal = 0))))
        assertEquals("EMPTY_VARIABLE", fail("{server.id}", ctx(input(serverId = null))).reason)
        assertEquals("EMPTY_VARIABLE", fail("{server.id}", ctx(input(serverId = 0))).reason)
        assertEquals("EMPTY_VARIABLE", fail("{variant.name}", ctx(VariableContext.Input(recipientUsername = "Steve"))).reason)
        assertEquals("vip", ok("{variant.name|vip}", ctx(VariableContext.Input(recipientUsername = "Steve"))))
    }

    @Test
    fun `an empty value without default is EMPTY_VARIABLE and an empty default does not count`() {
        val empty = ctx(VariableContext.Input(recipientUsername = "Steve"))
        val r = fail("give {username} {bundle.name}", empty)
        assertEquals("bundle.name", r.variable)
        assertEquals("EMPTY_VARIABLE", r.reason)
        assertEquals("bundle.name: EMPTY_VARIABLE", r.message)
        assertEquals("EMPTY_VARIABLE", fail("give {username} {bundle.name|}", empty).reason)
        assertEquals("give Steve none", ok("give {username} {bundle.name|none}", empty))
        assertEquals("give Steve a b:c", ok("give {username} {bundle.name|a b:c}", empty))
        assertEquals("EMPTY_VARIABLE", fail("{product.sku}", ctx(VariableContext.Input(recipientUsername = "Steve", productSku = "  "))).reason)
    }

    @Test
    fun `custom fields are validated by their type`() {
        fun f(type: ProductFieldType, value: String, pattern: String? = null, usable: Boolean = true) =
            ctx(input(fields = mapOf("k" to FieldValue(type, value, pattern, usable))))

        assertEquals("tell Notch", ok("tell {field.k}", f(ProductFieldType.USERNAME, "Notch")))
        assertEquals("INVALID_VALUE", fail("tell {field.k}", f(ProductFieldType.USERNAME, "Not ch")).reason)
        assertEquals("INVALID_VALUE", fail("tell {field.k}", f(ProductFieldType.USERNAME, ".Notch")).reason)
        assertEquals("12", ok("{field.k}", f(ProductFieldType.NUMBER, "12")))
        assertEquals("INVALID_VALUE", fail("{field.k}", f(ProductFieldType.NUMBER, "12.5")).reason)
        assertEquals("INVALID_VALUE", fail("{field.k}", f(ProductFieldType.NUMBER, "1; op Steve")).reason)
        assertEquals("red_1", ok("{field.k}", f(ProductFieldType.SELECT, "red_1")))
        assertEquals("INVALID_VALUE", fail("{field.k}", f(ProductFieldType.SELECT, "red 1")).reason)
        assertEquals("true", ok("{field.k}", f(ProductFieldType.CHECKBOX, "true")))
        assertEquals("a.b+c@example.com", ok("{field.k}", f(ProductFieldType.EMAIL, "a.b+c@example.com")))
        assertEquals("INVALID_VALUE", fail("{field.k}", f(ProductFieldType.EMAIL, "a@b")).reason)
        assertEquals("INVALID_VALUE", fail("{field.k}", f(ProductFieldType.EMAIL, "a b@example.com")).reason)
        assertEquals("123456789012345678", ok("{field.k}", f(ProductFieldType.DISCORD_ID, "123456789012345678")))
        assertEquals("INVALID_VALUE", fail("{field.k}", f(ProductFieldType.DISCORD_ID, "1234")).reason)
        assertEquals("INVALID_VALUE", fail("{field.k}", f(ProductFieldType.DISCORD_ID, "abc123456789012345")).reason)
    }

    @Test
    fun `TEXT fields obey the free text alphabet and their own pattern, default without spaces`() {
        fun f(value: String, pattern: String? = null) = ctx(input(fields = mapOf("k" to FieldValue(ProductFieldType.TEXT, value, pattern))))

        assertEquals("Hello_World-1", ok("{field.k}", f("Hello_World-1")))
        assertEquals("INVALID_VALUE", fail("{field.k}", f("two words")).reason) // 11 section 6.4 default: a space adds arguments
        assertEquals("two words", ok("{field.k}", f("two words", "^[a-z ]{1,20}$")))
        assertEquals("INVALID_VALUE", fail("{field.k}", f("TWO", "^[a-z ]{1,20}$")).reason)
        assertEquals("INVALID_VALUE", fail("{field.k}", f("a;b", "^.*$")).reason) // own pattern is not enough: free text alphabet applies too
        assertEquals("INVALID_VALUE", fail("{field.k}", f("a".repeat(65))).reason)
        assertEquals("INVALID_VALUE", fail("{field.k}", f("@a")).reason)
        assertEquals("EMPTY_VARIABLE", fail("{field.k}", f("")).reason)
    }

    @Test
    fun `a field with usableInCommands=0 is never substituted`() {
        val c = ctx(input(fields = mapOf("secret" to FieldValue(ProductFieldType.TEXT, "hunter2", null, usableInCommands = false))))
        val r = fail("say {field.secret}", c)
        assertEquals("field.secret", r.variable)
        assertEquals("FIELD_NOT_USABLE", r.reason)
        assertEquals("FIELD_NOT_USABLE", fail("say {field.secret|x}", c).reason)
        // never leaks into the message either
        assertFalse(r.message.contains("hunter2"))
        // a missing field is empty, not a literal token
        assertEquals("EMPTY_VARIABLE", fail("say {field.nope}").reason)
        assertEquals("say fallback", ok("say {field.nope|fallback}"))
    }

    @Test
    fun `gift message is never allowed in a command`() {
        val r = fail("say {gift.message}")
        assertEquals("gift.message", r.variable)
        assertEquals("FORBIDDEN_VARIABLE", r.reason)
        assertEquals("FORBIDDEN_VARIABLE", fail("say {gift.message|hi}", ctx(input(giftMessage = null))).reason)
    }

    @Test
    fun `a value never starts with an at sign`() {
        assertEquals("INVALID_VALUE", fail("{product.name}", ctx(input(productName = "@a"))).reason)
        assertEquals("INVALID_VALUE", fail("{variant.color}", ctx(input(variantAttributes = mapOf("color" to "@e")))).reason)
        assertEquals("INVALID_VALUE", fail("{field.k}", ctx(input(fields = mapOf("k" to FieldValue(ProductFieldType.TEXT, "@p"))))).reason)
    }

    @Test
    fun `rendered length over 1024 bytes is COMMAND_TOO_LONG, never truncated`() {
        assertEquals(1024, ok("a".repeat(1024)).toByteArray().size)
        assertEquals("COMMAND_TOO_LONG", fail("a".repeat(1025)).reason)
        // bytes, not characters: 513 two-byte characters
        assertEquals("COMMAND_TOO_LONG", fail("é".repeat(513)).reason)
        assertEquals(1024, ok("é".repeat(512)).toByteArray().size)
        // growth through substitution counts
        assertEquals("COMMAND_TOO_LONG", fail("x".repeat(1020) + "{username}").reason)
        assertEquals(null, fail("x".repeat(1020) + "{username}").variable)
    }

    @Test
    fun `substitution is single pass and never re-expands`() {
        // A value that contains a token is refused by its alphabet, so it can never reach the output.
        assertEquals("INVALID_VALUE", fail("{product.name}", ctx(input(productName = "{username}"))).reason)
        assertEquals("INVALID_VALUE", fail("{variant.color}", ctx(input(variantAttributes = mapOf("color" to "{order.id}")))).reason)
        assertEquals("INVALID_VALUE", fail("{product.name} {username}", ctx(input(productName = "x{username}"))).reason)
        assertEquals("a {uuid} {uuid}", ok("a {uuid} {uuid}"))
        assertEquals("Steve Steve", ok("{username} {player}"))
    }

    @Test
    fun `the default text is used for an empty value only`() {
        assertEquals("give Steve", ok("give {username|Alex}"))
        assertEquals("give Alex", ok("give {username|Alex}", ctx(VariableContext.Input(recipientUsername = ""))))
    }
}
