package com.panomc.plugins.market.core.catalog

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProductActionsTest {
    private fun ok(json: String): JsonArray {
        val result = ProductActions.normalize(json)
        assertEquals(emptyMap<String, String>(), result.errors)
        return JsonArray(result.json)
    }

    @Test
    fun `blank text is no actions and malformed text is INVALID`() {
        assertEquals("[]", ProductActions.normalize(null).json)
        assertEquals("[]", ProductActions.normalize("  ").json)
        assertEquals(mapOf("actions" to "INVALID"), ProductActions.normalize("{not json").errors)
    }

    @Test
    fun `an action without id gets a1, a2 and a stored a5 makes the next one a6`() {
        val out = ok("""[{"type":"CREDIT","value":10},{"type":"COMMAND","value":["say hi"]}]""")
        assertEquals("a1", out.getJsonObject(0).getString("id"))
        assertEquals("a2", out.getJsonObject(1).getString("id"))

        val kept = ok("""[{"id":"a5","type":"CREDIT","value":1},{"type":"CREDIT","value":2},{"id":"x9","type":"CREDIT","value":3}]""")
        assertEquals(listOf("a5", "a6", "x9"), kept.map { (it as JsonObject).getString("id") })
    }

    @Test
    fun `a duplicate or malformed id is refused with the dotted path`() {
        val dup = ProductActions.normalize("""[{"id":"a1","type":"CREDIT","value":1},{"id":"a1","type":"CREDIT","value":2}]""")
        assertEquals(mapOf("actions.1.id" to "DUPLICATE_ID"), dup.errors)

        val bad = ProductActions.normalize("""[{"id":"A 1!","type":"CREDIT","value":1}]""")
        assertEquals(mapOf("actions.0.id" to "INVALID"), bad.errors)
    }

    @Test
    fun `unknown types, phases and modes are refused and a WEBHOOK passes structurally for ProductActionRules`() {
        assertEquals("INVALID", ProductActions.normalize("""[{"type":"NOPE"}]""").errors["actions.0.type"])
        // MK-104: the strict rules of a webhook (URL policy, format, signing, secret) are ProductActionRules'; here only the shape counts
        val webhook = ok("""[{"type":"WEBHOOK","value":{"url":"https://x.test","signing":"HMAC_SHA256","secret":"abc"}}]""").getJsonObject(0)
        assertEquals("https://x.test", webhook.getJsonObject("value").getString("url"))
        assertEquals("INVALID_VALUE", ProductActions.normalize("""[{"type":"WEBHOOK","value":"https://x.test"}]""").errors["actions.0.value"])
        // 08 section 2.2: an unknown phase name is INVALID (INVALID_PHASE is the phase that does not fit the type)
        assertEquals("INVALID", ProductActions.normalize("""[{"type":"CREDIT","value":1,"phase":"LATER"}]""").errors["actions.0.phase"])
        assertEquals("INVALID", ProductActions.normalize("""[{"type":"COMMAND","value":["a"],"serverMode":"EVERYWHERE"}]""").errors["actions.0.serverMode"])
        assertEquals("INVALID", ProductActions.normalize("""[{"type":"PERMISSION","value":["a"],"via":"MAGIC"}]""").errors["actions.0.via"])
    }

    @Test
    fun `values are checked per type`() {
        assertEquals("INVALID_VALUE", ProductActions.normalize("""[{"type":"CREDIT","value":"abc"}]""").errors["actions.0.value"])
        assertEquals("INVALID_VALUE", ProductActions.normalize("""[{"type":"COMMAND","value":[]}]""").errors["actions.0.value"])
        assertEquals("INVALID_VALUE", ProductActions.normalize("""[{"type":"COMMAND","value":[1]}]""").errors["actions.0.value"])
        assertEquals("INVALID_VALUE", ProductActions.normalize("""[{"type":"PERMISSION","value":"group.vip"}]""").errors["actions.0.value"])
        assertEquals(10.0, ok("""[{"type":"CREDIT","value":"10"}]""").getJsonObject(0).getDouble("value"))
    }

    @Test
    fun `delay and target servers keep their ranges, unknown keys are dropped`() {
        val out = ok("""[{"type":"COMMAND","value":["give {username} diamond"],"delay":30,"targetServers":[1,"2",2],"junk":true,"currentInput":"x","phase":"GRANT","perUnit":true,"requiresOnline":true}]""")
        val a = out.getJsonObject(0)
        assertEquals(30, a.getInteger("delay"))
        assertEquals(listOf(1L, 2L), a.getJsonArray("targetServers").map { (it as Number).toLong() })
        assertNull(a.getValue("junk"))
        assertNull(a.getValue("currentInput"))
        assertEquals("GRANT", a.getString("phase"))
        assertEquals(true, a.getBoolean("perUnit"))
        assertEquals(true, a.getBoolean("requiresOnline"))

        assertEquals("OUT_OF_RANGE", ProductActions.normalize("""[{"type":"COMMAND","value":["a"],"delay":2592001}]""").errors["actions.0.delay"])
        assertEquals("OUT_OF_RANGE", ProductActions.normalize("""[{"type":"COMMAND","value":["a"],"delay":-1}]""").errors["actions.0.delay"])
        assertEquals("INVALID", ProductActions.normalize("""[{"type":"COMMAND","value":["a"],"targetServers":[0]}]""").errors["actions.0.targetServers"])
    }

    @Test
    fun `more than 30 actions is TOO_MANY`() {
        val many = JsonArray().apply { repeat(31) { add(JsonObject().put("type", "CREDIT").put("value", 1)) } }
        assertEquals(mapOf("actions" to "TOO_MANY"), ProductActions.normalize(many).errors)
        val thirty = JsonArray().apply { repeat(30) { add(JsonObject().put("type", "CREDIT").put("value", 1)) } }
        assertNotNull(ProductActions.normalize(thirty).json)
    }

    @Test
    fun `the panel view gives legacy rows the ids a save would give them`() {
        val view = ProductActions.view("""[{"type":"CREDIT","value":1},{"type":"COMMAND","value":["x"]}]""")
        assertEquals(listOf("a1", "a2"), view.map { (it as JsonObject).getString("id") })

        // a read followed by a save keeps the ids
        val saved = JsonArray(ProductActions.normalize(view).json)
        assertEquals(listOf("a1", "a2"), saved.map { (it as JsonObject).getString("id") })

        assertEquals(0, ProductActions.view("garbage").size())
        assertEquals(0, ProductActions.view(null).size())
    }

    @Test
    fun `the panel view of a webhook secret is the mask or empty, never the stored text`() {
        val view = ProductActions.view(
            """[{"id":"w1","type":"WEBHOOK","value":{"url":"https://x.test","signing":"HMAC_SHA256","secret":"v1:abcdef"}},{"id":"w2","type":"WEBHOOK","value":{"url":"https://y.test"}}]"""
        )

        assertEquals("********", view.getJsonObject(0).getJsonObject("value").getString("secret"))
        assertEquals("", view.getJsonObject(1).getJsonObject("value").getString("secret"))
        assertEquals(false, view.encode().contains("abcdef"))
    }

    @Test
    fun `a permission action keeps its serverMode`() {
        val out = ok("""[{"type":"PERMISSION","value":["a.b"],"via":"SERVER","serverMode":"ALL_CONNECTED"}]""").getJsonObject(0)

        assertEquals("ALL_CONNECTED", out.getString("serverMode"))
    }
}
