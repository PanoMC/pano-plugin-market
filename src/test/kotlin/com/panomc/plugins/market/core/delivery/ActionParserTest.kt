package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import io.vertx.core.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionParserTest {
    private val ctx = ActionParser.Context(
        billingMode = BillingMode.TIMED,
        maxQuantityPerOrder = 10,
        fields = mapOf("nick" to true, "secret" to false),
        serverIds = setOf(1L, 2L),
        hasServerChoices = true
    )

    private fun errors(json: String, c: ActionParser.Context = ctx) = ActionParser.parse(json, c).errors
    private fun ok(json: String, c: ActionParser.Context = ctx): List<ProductAction> {
        val r = ActionParser.parse(json, c)
        assertEquals(emptyMap<String, String>(), r.errors)
        return r.actions
    }

    private fun one(json: String, c: ActionParser.Context = ctx) = ok("[$json]", c).single()

    @Test
    fun `blank is no actions and malformed json is INVALID`() {
        assertTrue(ok("").isEmpty())
        assertTrue(ok("  []").isEmpty())
        assertEquals(mapOf("actions" to "INVALID"), errors("{nope"))
        assertEquals(mapOf("actions.0" to "INVALID"), errors("[1]"))
    }

    @Test
    fun `every action type parses with the defaults of 08 section 2`() {
        val credit = one("""{"type":"CREDIT","value":12.5}""")
        assertEquals("a1", credit.id)
        assertEquals(DeliveryActionType.CREDIT, credit.type)
        assertEquals(DeliveryPhase.GRANT, credit.phase)
        assertEquals(1250L, credit.credit)
        assertFalse(credit.isServerAction)

        val perm = one("""{"type":"PERMISSION","value":["Group.VIP","pano.x"]}""")
        assertEquals(listOf("group.vip", "pano.x"), perm.nodes)
        assertEquals(PermissionVia.PANO, perm.via)
        assertFalse(perm.isServerAction)
        assertTrue(one("""{"type":"PERMISSION","value":["a"],"via":"SERVER"}""").isServerAction)

        val cmd = one("""{"type":"COMMAND","value":["/give {username} diamond {quantity}"]}""")
        assertEquals(listOf("give {username} diamond {quantity}"), cmd.commands)
        assertEquals(ServerMode.FIXED, cmd.serverMode)
        assertTrue(cmd.isServerAction)
        assertFalse(cmd.requiresOnline)
        assertFalse(cmd.perUnit)

        val hook = one("""{"type":"WEBHOOK","value":{"url":"https://example.com/h","format":"DISCORD","signing":"HMAC_SHA256","secret":"whsec_abc"}}""")
        assertEquals(WebhookSpec("https://example.com/h", WebhookFormat.DISCORD, WebhookSigning.HMAC_SHA256, "whsec_abc"), hook.webhook)
        assertEquals(WebhookSpec("https://example.com/h"), one("""{"type":"WEBHOOK","value":{"url":"https://example.com/h"}}""").webhook)
    }

    @Test
    fun `ids are unique, generated after the highest a-number and never reused`() {
        val out = ok("""[{"id":"a5","type":"CREDIT","value":1},{"type":"CREDIT","value":2},{"id":"x9","type":"CREDIT","value":3}]""")
        assertEquals(listOf("a5", "a6", "x9"), out.map { it.id })
        assertEquals(mapOf("actions.1.id" to "DUPLICATE_ID"), errors("""[{"id":"a1","type":"CREDIT","value":1},{"id":"a1","type":"CREDIT","value":2}]"""))
        assertEquals(mapOf("actions.0.id" to "INVALID"), errors("""[{"id":"A 1!","type":"CREDIT","value":1}]"""))
        assertEquals(mapOf("actions.0.id" to "INVALID"), errors("""[{"id":"${"a".repeat(33)}","type":"CREDIT","value":1}]"""))
    }

    @Test
    fun `at most 30 actions`() {
        val thirty = (1..30).joinToString(",", "[", "]") { """{"type":"CREDIT","value":1}""" }
        assertEquals(30, ok(thirty).size)
        val thirtyOne = (1..31).joinToString(",", "[", "]") { """{"type":"CREDIT","value":1}""" }
        assertEquals(mapOf("actions" to "TOO_MANY"), errors(thirtyOne))
    }

    @Test
    fun `unknown type, phase, serverMode and via are INVALID`() {
        assertEquals(mapOf("actions.0.type" to "INVALID"), errors("""[{"type":"NOPE"}]"""))
        assertEquals(mapOf("actions.0.type" to "INVALID"), errors("""[{"value":1}]"""))
        assertEquals("INVALID", errors("""[{"type":"CREDIT","value":1,"phase":"LATER"}]""")["actions.0.phase"])
        assertEquals("INVALID", errors("""[{"type":"COMMAND","value":["a"],"serverMode":"EVERYWHERE"}]""")["actions.0.serverMode"])
        assertEquals("INVALID", errors("""[{"type":"PERMISSION","value":["a"],"via":"MAGIC"}]""")["actions.0.via"])
        assertEquals("INVALID", errors("""[{"type":"COMMAND","value":["a"],"requiresOnline":"maybe"}]""")["actions.0.requiresOnline"])
    }

    @Test
    fun `delay is an integer from 0 to 2592000`() {
        assertEquals(2_592_000, one("""{"type":"COMMAND","value":["a"],"delay":2592000}""").delaySeconds)
        assertEquals(60, one("""{"type":"COMMAND","value":["a"],"delay":"60"}""").delaySeconds)
        for (bad in listOf("-1", "2592001", "1.5", "\"x\"", "true", "[]")) {
            assertEquals("OUT_OF_RANGE", errors("""[{"type":"COMMAND","value":["a"],"delay":$bad}]""")["actions.0.delay"], bad)
        }
    }

    @Test
    fun `CREDIT value is a positive number up to 1000000 with at most two decimals`() {
        assertEquals(1L, one("""{"type":"CREDIT","value":0.01}""").credit)
        assertEquals(100_000_000L, one("""{"type":"CREDIT","value":1000000}""").credit)
        assertEquals(1000L, one("""{"type":"CREDIT","value":"10.00"}""").credit)
        for (bad in listOf("0", "-1", "1000000.01", "1.005", "\"abc\"", "null", "\"\"", "[1]")) {
            assertEquals("INVALID_VALUE", errors("""[{"type":"CREDIT","value":$bad}]""")["actions.0.value"], bad)
        }
    }

    @Test
    fun `CREDIT drops server data, perUnit and requiresOnline but keeps delay`() {
        val a = one("""{"type":"CREDIT","value":1,"delay":5,"serverMode":"ALL_CONNECTED","targetServers":[1],"perUnit":true,"requiresOnline":true}""")
        assertEquals(5, a.delaySeconds)
        assertEquals(ServerMode.FIXED, a.serverMode)
        assertTrue(a.targetServers.isEmpty())
        assertFalse(a.perUnit)
        assertFalse(a.requiresOnline)
    }

    @Test
    fun `phases per billing mode and type`() {
        fun phase(type: String, phase: String, mode: BillingMode?) =
            errors("""[{"type":"$type","value":${if (type == "CREDIT") "1" else """["a"]"""},"phase":"$phase"}]""", ActionParser.Context(billingMode = mode))["actions.0.phase"]

        for (mode in listOf(BillingMode.ONE_TIME, null)) {
            assertEquals(null, phase("COMMAND", "GRANT", mode))
            assertEquals(null, phase("COMMAND", "REVOKE", mode))
            assertEquals("INVALID_PHASE", phase("COMMAND", "RENEW", mode))
            assertEquals("INVALID_PHASE", phase("COMMAND", "EXPIRE", mode))
        }

        for (mode in listOf(BillingMode.TIMED, BillingMode.SUBSCRIPTION)) {
            for (p in DeliveryPhase.entries) assertEquals(null, phase("COMMAND", p.name, mode), "COMMAND $p $mode")
            assertEquals(null, phase("CREDIT", "GRANT", mode))
            assertEquals(null, phase("CREDIT", "RENEW", mode))
            assertEquals("INVALID_PHASE", phase("CREDIT", "EXPIRE", mode))
            assertEquals("INVALID_PHASE", phase("CREDIT", "REVOKE", mode))
            assertEquals("INVALID_PHASE", phase("PERMISSION", "EXPIRE", mode))
            assertEquals("INVALID_PHASE", phase("PERMISSION", "REVOKE", mode))
            assertEquals(null, phase("PERMISSION", "RENEW", mode))
        }

        assertEquals(DeliveryPhase.GRANT, one("""{"type":"COMMAND","value":["a"]}""").phase)
    }

    @Test
    fun `PERMISSION takes 1 to 20 nodes of the node alphabet, lower-cased`() {
        assertEquals(listOf("a.b-c_d*"), one("""{"type":"PERMISSION","value":["A.B-C_D*"]}""").nodes)
        assertEquals(listOf("x"), one("""{"type":"PERMISSION","value":["x","X"]}""").nodes)
        assertEquals(20, one("""{"type":"PERMISSION","value":[${(1..20).joinToString(",") { "\"n$it\"" }}]}""").nodes.size)
        val bad = listOf("[]", """["a b"]""", """["a/b"]""", """[""]""", """["${"a".repeat(129)}"]""", """"group.vip"""", """[1]""",
            "[${(1..21).joinToString(",") { "\"n$it\"" }}]")
        for (b in bad) assertEquals("INVALID_VALUE", errors("""[{"type":"PERMISSION","value":$b}]""")["actions.0.value"], b)
    }

    @Test
    fun `COMMAND takes 1 to 20 single line commands, one leading slash stripped, 512 characters`() {
        assertEquals(listOf("say hi", "/op"), one("""{"type":"COMMAND","value":["  /say hi  ","//op"]}""").commands)
        assertEquals(512, one("""{"type":"COMMAND","value":["${"a".repeat(512)}"]}""").commands.single().length)
        val bad = listOf("[]", """[""]""", """["/"]""", """["  "]""", """["${"a".repeat(513)}"]""", """["a\nb"]""", """["a\rb"]""",
            """["a\u0000b"]""", """["a\tb"]""", """[1]""", "[${(1..21).joinToString(",") { "\"c$it\"" }}]")
        for (b in bad) assertEquals("INVALID_VALUE", errors("""[{"type":"COMMAND","value":$b}]""")["actions.0.value"], b)
    }

    @Test
    fun `perUnit needs a max quantity of 1 to 100 on the product`() {
        assertTrue(one("""{"type":"COMMAND","value":["a"],"perUnit":true}""").perUnit)
        assertTrue(one("""{"type":"WEBHOOK","value":{"url":"https://x.test/h"},"perUnit":"true"}""").perUnit)
        for (max in listOf(null, 0, 101)) {
            val c = ActionParser.Context(maxQuantityPerOrder = max, serverIds = setOf(1L))
            assertEquals("PER_UNIT_NEEDS_MAX_QUANTITY", errors("""[{"type":"COMMAND","value":["a"],"perUnit":true}]""", c)["actions.0.perUnit"], "$max")
            assertEquals("PER_UNIT_NEEDS_MAX_QUANTITY", errors("""[{"type":"WEBHOOK","value":{"url":"https://x.test/h"},"perUnit":true}]""", c)["actions.0.perUnit"])
        }
        assertEquals(null, errors("""[{"type":"COMMAND","value":["a"],"perUnit":true}]""", ActionParser.Context(maxQuantityPerOrder = 100))["actions.0.perUnit"])
    }

    @Test
    fun `field tokens in commands need an existing field with usableInCommands`() {
        assertEquals(listOf("tell {field.nick} hi"), one("""{"type":"COMMAND","value":["tell {field.nick} hi"]}""").commands)
        assertEquals("FIELD_NOT_USABLE", errors("""[{"type":"COMMAND","value":["tell {field.secret} hi"]}]""")["actions.0.value"])
        assertEquals("FIELD_NOT_USABLE", errors("""[{"type":"COMMAND","value":["tell {field.missing|x} hi"]}]""")["actions.0.value"])
        // NBT and unknown tokens are not field references.
        ok("""[{"type":"COMMAND","value":["give {username} sword{Enchantments:[{id:sharpness}]} {display}"]}]""")
    }

    @Test
    fun `server actions need a server, known target ids and choices for BUYER_CHOICE`() {
        assertEquals(listOf(1L, 2L), one("""{"type":"COMMAND","value":["a"],"targetServers":[1,2,1]}""").targetServers)
        assertEquals(emptyList<Long>(), one("""{"type":"COMMAND","value":["a"],"serverMode":"FIXED","targetServers":[]}""").targetServers)
        assertEquals("UNKNOWN_SERVER", errors("""[{"type":"COMMAND","value":["a"],"targetServers":[1,9]}]""")["actions.0.targetServers"])
        assertEquals("INVALID", errors("""[{"type":"COMMAND","value":["a"],"targetServers":[0]}]""")["actions.0.targetServers"])
        assertEquals("INVALID", errors("""[{"type":"COMMAND","value":["a"],"targetServers":"1"}]""")["actions.0.targetServers"])

        assertEquals("SERVER_CHOICES_REQUIRED", errors("""[{"type":"COMMAND","value":["a"],"serverMode":"BUYER_CHOICE"}]""", ActionParser.Context(serverIds = setOf(1L)))["actions.0.serverMode"])
        assertEquals(ServerMode.BUYER_CHOICE, one("""{"type":"COMMAND","value":["a"],"serverMode":"BUYER_CHOICE","targetServers":[7]}""").serverMode)
        assertTrue(one("""{"type":"COMMAND","value":["a"],"serverMode":"BUYER_CHOICE","targetServers":[7]}""").targetServers.isEmpty())
        assertEquals(ServerMode.ALL_CONNECTED, one("""{"type":"COMMAND","value":["a"],"serverMode":"ALL_CONNECTED"}""").serverMode)

        val none = ActionParser.Context(hasServers = false)
        assertEquals("NO_SERVERS", errors("""[{"type":"COMMAND","value":["a"]}]""", none)["actions.0.serverMode"])
        assertEquals("NO_SERVERS", errors("""[{"type":"PERMISSION","value":["a"],"via":"SERVER"}]""", none)["actions.0.serverMode"])
        // Inline actions work on a site without a server.
        ok("""[{"type":"CREDIT","value":1},{"type":"PERMISSION","value":["a"]},{"type":"WEBHOOK","value":{"url":"https://x.test/h"}}]""", none)
    }

    @Test
    fun `requiresOnline is kept for COMMAND and dropped elsewhere`() {
        assertTrue(one("""{"type":"COMMAND","value":["a"],"requiresOnline":true}""").requiresOnline)
        assertFalse(one("""{"type":"PERMISSION","value":["a"],"via":"SERVER","requiresOnline":true}""").requiresOnline)
        assertFalse(one("""{"type":"WEBHOOK","value":{"url":"https://x.test/h"},"requiresOnline":true}""").requiresOnline)
    }

    @Test
    fun `WEBHOOK url, format and signing`() {
        fun hook(value: String) = errors("""[{"type":"WEBHOOK","value":$value}]""")
        for (url in listOf("ftp://x.test/h", "javascript:alert(1)", "https://u:p@x.test/h", "https:///h", "not a url", "https://x.test/{username}", "",
            "https://x.test:99999/h", "https://x.test/${"a".repeat(1025)}")) {
            assertEquals("INVALID_WEBHOOK_URL", hook("""{"url":"$url"}""")["actions.0.value.url"], url)
        }
        assertEquals("INVALID_WEBHOOK_URL", hook("""{}""")["actions.0.value.url"])
        assertEquals("INVALID_VALUE", hook("""{"url":"https://x.test/h","format":"XML"}""")["actions.0.value.format"])
        assertEquals("INVALID_VALUE", hook("""{"url":"https://x.test/h","signing":"MD5"}""")["actions.0.value.signing"])
        assertEquals("INVALID_VALUE", errors("""[{"type":"WEBHOOK","value":"https://x.test/h"}]""")["actions.0.value"])
        assertEquals(emptyMap<String, String>(), hook("""{"url":"http://x.test:8080/h?a=1"}"""))

        val strict = ActionParser.Context(webhookUrlOk = { !it.contains("127.0.0.1") })
        assertEquals("INVALID_WEBHOOK_URL", errors("""[{"type":"WEBHOOK","value":{"url":"http://127.0.0.1/h"}}]""", strict)["actions.0.value.url"])
    }

    @Test
    fun `chargeback and payout lists use their own ids, force GRANT and refuse BUYER_CHOICE`() {
        val cb = ActionParser.Context(kind = ActionParser.Kind.CHARGEBACK, serverIds = setOf(1L))
        val out = ok("""[{"type":"COMMAND","value":["ban {username}"],"phase":"REVOKE","perUnit":true},{"id":"c4","type":"COMMAND","value":["x"]},{"type":"CREDIT","value":1}]""", cb)
        assertEquals(listOf("c5", "c4", "c6"), out.map { it.id })
        assertTrue(out.all { it.phase == DeliveryPhase.GRANT })
        assertFalse(out[0].perUnit)
        assertEquals("INVALID", errors("""[{"type":"COMMAND","value":["a"],"serverMode":"BUYER_CHOICE"}]""", cb)["actions.0.serverMode"])
        assertEquals("p1", ok("""[{"type":"CREDIT","value":2}]""", ActionParser.Context(kind = ActionParser.Kind.PAYOUT)).single().id)
        assertEquals("INVALID", errors("""[{"type":"COMMAND","value":["a"],"serverMode":"BUYER_CHOICE"}]""", ActionParser.Context(kind = ActionParser.Kind.PAYOUT, serverIds = setOf(1L)))["actions.0.serverMode"])
    }

    @Test
    fun `errors are reported for every action at once and one per path`() {
        val e = errors("""[{"type":"CREDIT","value":0},{"type":"COMMAND","value":[]},{"type":"NOPE"}]""")
        assertEquals(mapOf("actions.0.value" to "INVALID_VALUE", "actions.1.value" to "INVALID_VALUE", "actions.2.type" to "INVALID"), e)
        assertTrue(ActionParser.parse("""[{"type":"CREDIT","value":0}]""", ctx).actions.isEmpty())
    }

    @Test
    fun `toJson and parse round trip`() {
        val source = """[{"id":"a1","type":"CREDIT","value":12.5,"phase":"RENEW","delay":3},
            {"id":"a2","type":"PERMISSION","value":["a.b"],"via":"SERVER","serverMode":"FIXED","targetServers":[1]},
            {"id":"a3","type":"COMMAND","value":["say {username}"],"phase":"EXPIRE","serverMode":"ALL_CONNECTED","requiresOnline":true,"perUnit":true},
            {"id":"a4","type":"WEBHOOK","value":{"url":"https://x.test/h","format":"DISCORD","signing":"HMAC_SHA256","secret":"s3cret-secret-value"}}]"""
        val first = ok(source)
        val again = ok(ActionParser.toJson(first).encode())
        assertEquals(first, again)
        assertEquals(first, ActionParser.parseStored(ActionParser.toJson(first).encode()))
    }

    @Test
    fun `parseStored is lenient with legacy rows and drops only broken actions`() {
        val stored = ActionParser.parseStored(
            """[{"type":"COMMAND","value":["/give {username} dirt"]},{"type":"NOPE"},"x",{"id":"a2","type":"CREDIT","value":2},{"type":"CREDIT","value":0},
                {"type":"COMMAND","value":["z"],"phase":"EXPIRE","perUnit":true,"targetServers":[99]}]"""
        )
        assertEquals(listOf("a3", "a2", "a6"), stored.map { it.id })
        assertEquals(listOf("give {username} dirt"), stored[0].commands)
        assertEquals(DeliveryPhase.EXPIRE, stored[2].phase)
        assertTrue(stored[2].perUnit)
        assertTrue(ActionParser.parseStored(null).isEmpty())
        assertTrue(ActionParser.parseStored("{broken").isEmpty())
        assertEquals(JsonArray().size(), ActionParser.parseStored("[]").size)
    }
}
