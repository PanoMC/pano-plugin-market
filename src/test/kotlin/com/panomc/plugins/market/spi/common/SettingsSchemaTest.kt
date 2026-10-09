package com.panomc.plugins.market.spi.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.panomc.plugins.market.util.MarketPaths

class SettingsSchemaTest {
    private fun t(s: String) = LocalizedText.of(s)

    private val schema = settingsSchema {
        group("credentials", LocalizedText.of("Credentials", "tr" to "Kimlik bilgileri"))
        text("merchantId") { label = LocalizedText.of("Merchant ID", "tr" to "Magaza No"); required = true; group = "credentials"; pattern = "^[0-9]{4,10}$" }
        secret("merchantKey") { label = t("Merchant key"); required = true; group = "credentials" }
        secretTextarea("privateKey") { label = t("Private key (PEM)") }
        textarea("note") { label = t("Note") }
        number("timeout") { label = t("Timeout"); min = 1; max = 60; default = 15 }
        select("mode") {
            label = t("Mode"); option("HEADLESS", t("Tebex catalogue")); option("CHECKOUT_API", t("Checkout API")); default = "HEADLESS"
        }
        switch("embedded") { label = t("Open in a popup"); visibleWhen("mode", "HEADLESS") }
        url("logoUrl") { label = t("Logo URL"); placeholder = "https://" }
        webhookUrl("callbackUrl") { label = t("Callback URL"); help = t("Paste into the gateway panel.") }
        webhookUrl("subscriptionHook", channel = "subscription") { label = t("Subscription webhook URL") }
        returnUrlPrefix("returnPrefix") { label = t("Return URL prefix") }
        siteBaseUrl("siteUrl") { label = t("Site URL") }
        notice("ipHint", NoticeLevel.WARNING) { label = t("The gateway must allow-list this server's outgoing IP.") }
        hidden("accountCurrency") { label = t("Account currency"); dependsOn("merchantId") }
        hiddenSecret("webhookId") { label = t("Webhook id") }
        action("test-connection") { label = t("Test connection") }
        action("register-hook") { label = t("Register webhook"); description = t("Creates the hook"); confirm = t("Sure?"); requiresSavedSettings = false }
    }

    @Test
    fun `DSL builds the documented structure`() {
        assertEquals(
            listOf(
                "merchantId", "merchantKey", "privateKey", "note", "timeout", "mode", "embedded", "logoUrl", "callbackUrl",
                "subscriptionHook", "returnPrefix", "siteUrl", "ipHint", "accountCurrency", "webhookId"
            ),
            schema.fields.map { it.key }
        )
        assertEquals(
            listOf(
                FieldType.TEXT, FieldType.PASSWORD, FieldType.SECRET_TEXTAREA, FieldType.TEXTAREA, FieldType.NUMBER, FieldType.SELECT,
                FieldType.SWITCH, FieldType.URL, FieldType.READONLY, FieldType.READONLY, FieldType.READONLY, FieldType.READONLY,
                FieldType.NOTICE, FieldType.HIDDEN, FieldType.HIDDEN
            ),
            schema.fields.map { it.type }
        )
        assertEquals(listOf("credentials"), schema.groups.map { it.key })
        assertEquals(listOf("test-connection", "register-hook"), schema.actions.map { it.id })

        val id = schema.field("merchantId")!!
        assertEquals("credentials", id.group)
        assertTrue(id.required)
        assertEquals("^[0-9]{4,10}$", id.pattern)
        assertEquals("Magaza No", id.label.resolve("tr"))

        val timeout = schema.field("timeout")!!
        assertEquals(1L, timeout.min); assertEquals(60L, timeout.max); assertEquals(15, timeout.default)

        val mode = schema.field("mode")!!
        assertEquals(listOf("HEADLESS", "CHECKOUT_API"), mode.options.map { it.value })
        assertEquals("HEADLESS", mode.default)

        val embedded = schema.field("embedded")!!
        assertEquals("mode", embedded.visibleWhen!!.field)
        assertEquals(setOf("HEADLESS"), embedded.visibleWhen!!.anyOf)

        assertEquals("callbackUrl", schema.field("callbackUrl")!!.key)
        assertEquals("default", (schema.field("callbackUrl")!!.readonly as ReadonlyValue.WebhookUrl).channel)
        assertEquals("subscription", (schema.field("subscriptionHook")!!.readonly as ReadonlyValue.WebhookUrl).channel)
        assertTrue(schema.field("returnPrefix")!!.readonly is ReadonlyValue.ReturnUrlPrefix)
        assertTrue(schema.field("siteUrl")!!.readonly is ReadonlyValue.SiteBaseUrl)
        assertEquals(NoticeLevel.WARNING, schema.field("ipHint")!!.noticeLevel)
        assertEquals(setOf("merchantId"), schema.field("accountCurrency")!!.dependsOn)

        val hook = schema.actions[1]
        assertFalse(hook.requiresSavedSettings)
        assertEquals("Sure?", hook.confirm!!.fallback)
        assertTrue(schema.actions[0].requiresSavedSettings)
        assertNull(schema.actions[0].confirm)
    }

    @Test
    fun `secret flag follows the type and hiddenSecret`() {
        assertEquals(setOf("merchantKey", "privateKey", "webhookId"), schema.secretKeys)
        assertTrue(schema.field("merchantKey")!!.secret)
        assertTrue(schema.field("privateKey")!!.secret)
        assertTrue(schema.field("webhookId")!!.secret)
        assertFalse(schema.field("accountCurrency")!!.secret)
        assertFalse(schema.field("merchantId")!!.secret)
        assertFalse(schema.field("note")!!.secret)
    }

    @Test
    fun `READONLY and NOTICE are never stored, HIDDEN is`() {
        assertEquals(
            listOf("merchantId", "merchantKey", "privateKey", "note", "timeout", "mode", "embedded", "logoUrl", "accountCurrency", "webhookId"),
            schema.storedFields.map { it.key }
        )
    }

    @Test
    fun `defaults of optional properties`() {
        val f = schema.field("note")!!
        assertFalse(f.required)
        assertNull(f.help); assertNull(f.placeholder); assertNull(f.default); assertNull(f.pattern)
        assertNull(f.min); assertNull(f.max); assertNull(f.group); assertNull(f.visibleWhen); assertNull(f.readonly)
        assertEquals(NoticeLevel.INFO, f.noticeLevel)
        assertTrue(f.options.isEmpty()); assertTrue(f.dependsOn.isEmpty()); assertFalse(f.hiddenSecret)
    }

    @Test
    fun `wire format omits HIDDEN fields and resolves readonly values`() {
        val json = schema.toJson { v ->
            when (v) {
                is ReadonlyValue.WebhookUrl -> "https://shop.example${MarketPaths.SITE_ROOT}/payments/x/webhook" + (if (v.channel == "default") "" else "/${v.channel}")
                is ReadonlyValue.SiteBaseUrl -> "https://shop.example"
                else -> null
            }
        }
        val fields = json.getJsonArray("fields").map { it as io.vertx.core.json.JsonObject }
        assertEquals(
            listOf("merchantId", "merchantKey", "privateKey", "note", "timeout", "mode", "embedded", "logoUrl", "callbackUrl", "subscriptionHook", "returnPrefix", "siteUrl", "ipHint"),
            fields.map { it.getString("key") }
        )
        val byKey = fields.associateBy { it.getString("key") }

        val id = byKey.getValue("merchantId")
        assertEquals("TEXT", id.getString("type"))
        assertEquals(true, id.getBoolean("required"))
        assertEquals(false, id.getBoolean("secret"))
        assertEquals("credentials", id.getString("group"))
        assertEquals("""{"default":"Merchant ID","translations":{"tr":"Magaza No"}}""", id.getJsonObject("label").encode())
        assertEquals("^[0-9]{4,10}$", id.getString("pattern"))

        assertEquals(true, byKey.getValue("merchantKey").getBoolean("secret"))
        assertEquals("""{"field":"mode","anyOf":["HEADLESS"]}""", byKey.getValue("embedded").getJsonObject("visibleWhen").encode())
        assertEquals("HEADLESS", byKey.getValue("mode").getString("default"))
        assertEquals(2, byKey.getValue("mode").getJsonArray("options").size())
        assertEquals(1, byKey.getValue("timeout").getLong("min"))
        assertEquals(60, byKey.getValue("timeout").getLong("max"))
        assertEquals(15, byKey.getValue("timeout").getInteger("default"))
        assertEquals("WARNING", byKey.getValue("ipHint").getString("noticeLevel"))
        assertFalse(byKey.getValue("merchantId").containsKey("noticeLevel"))
        assertFalse(byKey.getValue("merchantId").containsKey("options"))
        assertFalse(byKey.getValue("merchantId").containsKey("readonly"))

        assertEquals(
            """{"kind":"WEBHOOK_URL","channel":"default","value":"https://shop.example${MarketPaths.SITE_ROOT}/payments/x/webhook"}""",
            byKey.getValue("callbackUrl").getJsonObject("readonly").encode()
        )
        assertEquals(
            "https://shop.example${MarketPaths.SITE_ROOT}/payments/x/webhook/subscription",
            byKey.getValue("subscriptionHook").getJsonObject("readonly").getString("value")
        )
        assertEquals("""{"kind":"RETURN_URL_PREFIX"}""", byKey.getValue("returnPrefix").getJsonObject("readonly").encode())
        assertEquals("""{"kind":"SITE_BASE_URL","value":"https://shop.example"}""", byKey.getValue("siteUrl").getJsonObject("readonly").encode())

        assertEquals(1, json.getJsonArray("groups").size())
        val actions = json.getJsonArray("actions").map { it as io.vertx.core.json.JsonObject }
        assertEquals(listOf("test-connection", "register-hook"), actions.map { it.getString("id") })
        assertEquals(true, actions[0].getBoolean("requiresSavedSettings"))
        assertEquals(false, actions[1].getBoolean("requiresSavedSettings"))
        assertEquals("""{"default":"Sure?","translations":{}}""", actions[1].getJsonObject("confirm").encode())
        assertFalse(json.encode().contains("webhookId"))
        assertFalse(json.encode().contains("accountCurrency"))
    }

    @Test
    fun `static readonly text goes to the wire`() {
        val s = settingsSchema { readonly("info", ReadonlyValue.Static(t("Fixed text"))) { label = t("Info") } }
        val readonly = s.toJson().getJsonArray("fields").getJsonObject(0).getJsonObject("readonly")
        assertEquals("STATIC", readonly.getString("kind"))
        assertEquals("Fixed text", readonly.getJsonObject("text").getString("default"))
    }

    @Test
    fun `an empty schema is valid`() {
        val s = settingsSchema { }
        assertTrue(s.fields.isEmpty()); assertTrue(s.groups.isEmpty()); assertTrue(s.actions.isEmpty())
        assertEquals("""{"fields":[],"groups":[],"actions":[]}""", s.toJson().encode())
    }

    @Test
    fun `inconsistent schemas are refused at build time`() {
        fun bad(block: SettingsSchemaBuilder.() -> Unit) { assertThrows<IllegalArgumentException> { settingsSchema(block) } }
        bad { text("a") { } }                                                                   // no label
        bad { action("go") { } }                                                                // no label
        bad { text("a") { label = t("A") }; text("a") { label = t("A2") } }                    // duplicate key
        bad { text("1bad") { label = t("A") } }                                                 // key shape
        bad { text("has space") { label = t("A") } }
        bad { group("g", t("G")); group("g", t("G2")) }                                         // duplicate group
        bad { text("a") { label = t("A"); group = "nope" } }                                    // unknown group
        bad { text("a") { label = t("A"); visibleWhen("ghost", "x") } }                        // unknown visibleWhen field
        bad { text("a") { label = t("A"); visibleWhen("a", "x") } }                            // self reference
        bad { text("b") { label = t("B") }; text("a") { label = t("A"); visibleWhen("b") } }   // empty value set
        bad { select("s") { label = t("S") } }                                                  // select without options
        bad { text("a") { label = t("A"); option("x", t("X")) } }                              // options on a text
        bad { select("s") { label = t("S"); option("x", t("X")); option("x", t("Y")) } }       // duplicate option
        bad { select("s") { label = t("S"); option("x", t("X")); default = "y" } }             // default not an option
        bad { text("a") { label = t("A"); pattern = "(a+)+$" } }                                // unsafe pattern
        bad { text("a") { label = t("A"); pattern = "(" } }                                     // bad pattern
        bad { number("n") { label = t("N"); pattern = "^1$" } }                                 // pattern on a number
        bad { text("a") { label = t("A"); min = 1 } }                                           // min on a text
        bad { number("n") { label = t("N"); min = 5; max = 1 } }                                // min > max
        bad { number("n") { label = t("N"); min = 5; default = 1 } }                            // default below min
        bad { number("n") { label = t("N"); max = 5; default = 10L } }                          // default above max
        bad { number("n") { label = t("N"); default = "x" } }                                   // default type
        bad { switch("s") { label = t("S"); default = "true" } }                                // default type
        bad { secret("k") { label = t("K"); default = "x" } }                                   // secret default
        bad { notice("n") { label = t("N"); required = true } }                                 // display only
        bad { webhookUrl("w") { label = t("W"); default = "x" } }
        bad { text("a") { label = t("A"); dependsOn("b") }; text("b") { label = t("B") } }     // dependsOn on a non-hidden field
        bad { hidden("h") { label = t("H"); dependsOn("ghost") } }                              // unknown dependency
        bad { hidden("h") { label = t("H"); dependsOn("h") } }                                  // self dependency
    }

    @Test
    fun `a complete valid corner case set builds`() {
        val s = settingsSchema {
            number("n") { label = t("N"); min = 0; max = 10; default = 10.0 }
            switch("s") { label = t("S"); default = true }
            text("a") { label = t("A"); default = "x"; visibleWhen("s", "true", "false") }
            hidden("h") { label = t("H"); dependsOn("a", "n") }
        }
        assertEquals(4, s.fields.size)
        assertEquals(setOf("a", "n"), s.field("h")!!.dependsOn)
    }
}
