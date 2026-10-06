package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.error.NoPermission
import com.panomc.plugins.market.core.abuse.ActionGuard
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.error.InvalidProduct
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.platform.ServerRoster
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * `ProductActionRules` (08 section 2.2, 11 sections 8.2 and 14.4): the strict action rules with the catalogue context, the privilege rule, the webhook
 * secret protocol and the canonical text, with a stubbed roster, cipher and caller (no database: the connection is never used).
 */
class ProductActionRulesTest {
    private val conn: SqlClient = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SqlClient::class.java)) { _, method, _ ->
        throw UnsupportedOperationException(method.name)
    } as SqlClient

    private val cipher = SecretCipher(ByteArray(32) { it.toByte() })

    private class Caller(val admin: Boolean = false, val groups: Boolean = false, val consoles: Set<Long> = emptySet(), val global: Boolean = false) : ActionGuard.Caller {
        override suspend fun isAdmin() = admin

        override suspend fun canManagePermissionGroups() = groups

        override suspend fun canConsole(serverId: Long) = global || serverId in consoles

        override suspend fun canConsoleGlobally() = global
    }

    private fun rules(servers: List<Long> = listOf(1, 2), cipher: SecretCipher? = this.cipher, allowPrivate: Boolean = false, hosted: Boolean = false) =
        ProductActionRules(ServerRoster { ServerRoster.Roster(servers, servers, emptyMap()) }, cipher, { allowPrivate }, { hosted })

    private fun request(
        submitted: String,
        stored: String? = null,
        billing: BillingMode = BillingMode.ONE_TIME,
        maxQuantity: Int? = null,
        choices: String? = null,
        storedChoices: String? = null,
        fields: Map<String, Boolean> = emptyMap(),
        caller: ActionGuard.Caller? = Caller(admin = true)
    ) = ProductActionCheck.Request(stored, storedChoices, submitted, billing, maxQuantity, choices, fields, caller)

    private fun save(r: ProductActionCheck.Request, rules: ProductActionRules = rules()) = runBlocking { rules.onSave(conn, r) }

    private fun errors(r: ProductActionCheck.Request, rules: ProductActionRules = rules()): Map<String, String> {
        val e = runCatching { save(r, rules) }.exceptionOrNull()

        assertTrue(e is InvalidProduct, "expected INVALID_PRODUCT, got $e")

        return JsonObject((e as InvalidProduct).encode(emptyMap())).getJsonObject("fieldErrors").map.mapValues { it.value as String }
    }

    private fun command(extra: String = "", targets: String = "[1]") =
        """[{"id":"a1","type":"COMMAND","value":["give {username} diamond"],"serverMode":"FIXED","targetServers":$targets$extra}]"""

    @Test
    fun `a valid list is stored in its canonical form with the defaults written out`() {
        val out = save(request("""[{"id":"a1","type":"CREDIT","value":2.5},{"id":"a2","type":"COMMAND","value":["/say hi "],"targetServers":[1,1]}]"""))
        val array = JsonArray(out.json)

        assertEquals(2.5, array.getJsonObject(0).getDouble("value"))
        assertEquals("GRANT", array.getJsonObject(0).getString("phase"))
        assertEquals(listOf("say hi"), array.getJsonObject(1).getJsonArray("value").map { it as String })
        assertEquals(listOf(1), array.getJsonObject(1).getJsonArray("targetServers").map { it as Int })
        assertEquals(emptyMap<String, String>(), out.generatedSecrets)
    }

    @Test
    fun `the rules of the catalogue reach the field errors with the dotted path`() {
        // a server that does not exist
        assertEquals("UNKNOWN_SERVER", errors(request(command(targets = "[9]")))["actions.0.targetServers"])
        // a server action on a site without a server
        assertEquals("NO_SERVERS", errors(request(command()), rules(servers = emptyList()))["actions.0.serverMode"])
        // buyer choice without choices on the product
        assertEquals(
            "SERVER_CHOICES_REQUIRED",
            errors(request("""[{"id":"a1","type":"COMMAND","value":["x"],"serverMode":"BUYER_CHOICE"}]"""))["actions.0.serverMode"]
        )
        assertTrue(save(request("""[{"id":"a1","type":"COMMAND","value":["x"],"serverMode":"BUYER_CHOICE"}]""", choices = "[1,2]")).json.isNotEmpty())
        // a command variable of a field that may not be used
        assertEquals("FIELD_NOT_USABLE", errors(request("""[{"id":"a1","type":"COMMAND","value":["say {field.nick}"],"targetServers":[1]}]""", fields = mapOf("nick" to false)))["actions.0.value"])
        assertTrue(save(request("""[{"id":"a1","type":"COMMAND","value":["say {field.nick}"],"targetServers":[1]}]""", fields = mapOf("nick" to true))).json.isNotEmpty())
        // per unit needs a quantity limit up to 100
        assertEquals("PER_UNIT_NEEDS_MAX_QUANTITY", errors(request(command(""","perUnit":true""")))["actions.0.perUnit"])
        assertEquals("PER_UNIT_NEEDS_MAX_QUANTITY", errors(request(command(""","perUnit":true"""), maxQuantity = 101))["actions.0.perUnit"])
        assertTrue(save(request(command(""","perUnit":true"""), maxQuantity = 100)).json.isNotEmpty())
        // EXPIRE and RENEW only on timed products; credits and permissions only in GRANT / RENEW
        assertEquals("INVALID_PHASE", errors(request("""[{"id":"a1","type":"COMMAND","value":["x"],"phase":"EXPIRE","targetServers":[1]}]"""))["actions.0.phase"])
        assertTrue(save(request("""[{"id":"a1","type":"COMMAND","value":["x"],"phase":"EXPIRE","targetServers":[1]}]""", billing = BillingMode.TIMED)).json.isNotEmpty())
        assertEquals("INVALID_PHASE", errors(request("""[{"id":"a1","type":"CREDIT","value":1,"phase":"REVOKE"}]"""))["actions.0.phase"])
        // a credit above the ceiling
        assertEquals("INVALID_VALUE", errors(request("""[{"id":"a1","type":"CREDIT","value":1000001}]"""))["actions.0.value"])
        // a permission node outside the alphabet
        assertEquals("INVALID_VALUE", errors(request("""[{"id":"a1","type":"PERMISSION","value":["Bad Node"]}]"""))["actions.0.value"])
    }

    @Test
    fun `an invalid list never reaches the privilege check`() {
        val broken = request(command(targets = "[9]"), caller = Caller())

        assertTrue(runCatching { save(broken) }.exceptionOrNull() is InvalidProduct)
    }

    @Test
    fun `a changed command or permission needs the right of the caller, an unchanged one passes`() {
        val storedJson = JsonArray(save(request(command() + "")).json).encode()
        val nobody = Caller()

        // the same text again by a caller with no rights: unchanged
        assertEquals(storedJson, save(request(command(), stored = storedJson, caller = nobody)).json)

        // another command line is a change
        val changed = """[{"id":"a1","type":"COMMAND","value":["op {username}"],"serverMode":"FIXED","targetServers":[1]}]"""

        assertThrows(NoPermission::class.java) { save(request(changed, stored = storedJson, caller = nobody)) }
        assertThrows(NoPermission::class.java) { save(request(changed, stored = storedJson, caller = Caller(groups = true))) }
        assertEquals(1, JsonArray(save(request(changed, stored = storedJson, caller = Caller(consoles = setOf(1)))).json).size())

        // a permission
        val perm = """[{"id":"a1","type":"PERMISSION","value":["group.vip"]}]"""

        assertThrows(NoPermission::class.java) { save(request(perm, caller = nobody)) }
        assertThrows(NoPermission::class.java) { save(request(perm, caller = null)) }
        assertEquals(1, JsonArray(save(request(perm, caller = Caller(groups = true))).json).size())
        assertThrows(NoPermission::class.java) { save(request("""[{"id":"a1","type":"PERMISSION","value":["pano.manage.servers"]}]""", caller = Caller(groups = true))) }
    }

    @Test
    fun `a clone needs the right for every action of the source`() {
        val source = """[{"id":"a1","type":"COMMAND","value":["x"],"serverMode":"FIXED","targetServers":[1]},{"id":"a2","type":"CREDIT","value":1}]"""

        assertThrows(NoPermission::class.java) { runBlocking { rules().onClone(conn, source, null, Caller()) } }
        assertThrows(NoPermission::class.java) { runBlocking { rules().onClone(conn, source, null, null) } }
        runBlocking { rules().onClone(conn, source, null, Caller(consoles = setOf(1))) }
        runBlocking { rules().onClone(conn, """[{"id":"a1","type":"CREDIT","value":1}]""", null, Caller()) }
        runBlocking { rules().onClone(conn, null, null, Caller()) }
    }

    // ----- webhooks ---------------------------------------------------------------------------------------------------

    private fun webhook(value: String) = """[{"id":"w1","type":"WEBHOOK","value":$value}]"""

    @Test
    fun `a webhook url goes through the policy and a private address is refused unless allowed`() {
        assertEquals("INVALID_WEBHOOK_URL", errors(request(webhook("""{"url":"http://127.0.0.1/hook"}""")))["actions.0.value.url"])
        assertEquals("INVALID_WEBHOOK_URL", errors(request(webhook("""{"url":"http://169.254.169.254/latest"}""")))["actions.0.value.url"])
        assertEquals("INVALID_WEBHOOK_URL", errors(request(webhook("""{"url":"ftp://example.com/x"}""")))["actions.0.value.url"])
        assertEquals("INVALID_WEBHOOK_URL", errors(request(webhook("""{"url":"https://example.com/{order}"}""")))["actions.0.value.url"])
        assertEquals("INVALID_WEBHOOK_URL", errors(request(webhook("""{"url":"https://user:pw@example.com/x"}""")))["actions.0.value.url"])

        assertTrue(save(request(webhook("""{"url":"https://example.com/hook"}"""))).json.contains("example.com"))
        // the flag lets a private address through, but never on a hosted instance and never link-local
        assertTrue(save(request(webhook("""{"url":"http://10.0.0.5/hook"}""")), rules(allowPrivate = true)).json.contains("10.0.0.5"))
        assertEquals("INVALID_WEBHOOK_URL", errors(request(webhook("""{"url":"http://10.0.0.5/hook"}""")), rules(allowPrivate = true, hosted = true))["actions.0.value.url"])
        assertEquals("INVALID_WEBHOOK_URL", errors(request(webhook("""{"url":"http://169.254.169.254/"}""")), rules(allowPrivate = true))["actions.0.value.url"])
        assertEquals("INVALID_VALUE", errors(request(webhook("""{"url":"https://example.com/h","format":"XML"}""")))["actions.0.value.format"])
        assertEquals("INVALID_VALUE", errors(request(webhook("""{"url":"https://example.com/h","signing":"MD5"}""")))["actions.0.value.signing"])
    }

    @Test
    fun `a webhook secret is stored encrypted, a given value replaces it and the mask or blank keeps the stored one`() {
        val first = save(request(webhook("""{"url":"https://example.com/h","signing":"HMAC_SHA256","secret":"0123456789abcdef"}""")))
        val stored = JsonArray(first.json).getJsonObject(0).getJsonObject("value").getString("secret")

        assertTrue(stored.startsWith("v1:"))
        assertFalse(first.json.contains("0123456789abcdef"))
        assertEquals("0123456789abcdef", cipher.decrypt(stored))
        assertTrue(first.generatedSecrets.isEmpty())

        for (keep in listOf("\"secret\":\"********\"", "\"secret\":\"\"", "\"x\":1")) {
            val text = webhook("""{"url":"https://example.com/h","signing":"HMAC_SHA256",$keep}""")
            val again = runCatching { save(request(text, stored = first.json)) }.getOrElse { e ->
                throw AssertionError("$keep -> " + ((e as? InvalidProduct)?.encode(emptyMap()) ?: e.toString()))
            }

            assertEquals(stored, JsonArray(again.json).getJsonObject(0).getJsonObject("value").getString("secret"), keep)
            assertTrue(again.generatedSecrets.isEmpty())
        }

        val replaced = save(request(webhook("""{"url":"https://example.com/h","signing":"HMAC_SHA256","secret":"another-secret-value"}"""), stored = first.json))
        val replacedText = JsonArray(replaced.json).getJsonObject(0).getJsonObject("value").getString("secret")

        assertNotEquals(stored, replacedText)
        assertEquals("another-secret-value", cipher.decrypt(replacedText))
    }

    @Test
    fun `HMAC without any secret creates one and returns it once, no signing keeps none`() {
        val created = save(request(webhook("""{"url":"https://example.com/h","signing":"HMAC_SHA256"}""")))
        val secret = created.generatedSecrets.getValue("w1")
        val stored = JsonArray(created.json).getJsonObject(0).getJsonObject("value").getString("secret")

        assertTrue(secret.startsWith("whsec_"))
        assertEquals(6 + 43, secret.length)
        assertEquals(secret, cipher.decrypt(stored))
        assertFalse(created.json.contains(secret))

        // the next save keeps it and creates nothing new
        val next = save(request(webhook("""{"url":"https://example.com/h","signing":"HMAC_SHA256","secret":"********"}"""), stored = created.json))

        assertTrue(next.generatedSecrets.isEmpty())
        assertEquals(stored, JsonArray(next.json).getJsonObject(0).getJsonObject("value").getString("secret"))

        // signing off drops the secret
        val none = save(request(webhook("""{"url":"https://example.com/h","signing":"NONE","secret":"0123456789abcdef"}"""), stored = created.json))

        assertFalse(JsonArray(none.json).getJsonObject(0).getJsonObject("value").containsKey("secret"))
    }

    @Test
    fun `a secret of the wrong shape and a missing cipher are refused`() {
        for (bad in listOf("short", "x".repeat(129), "tab\tinside-secret-value", "\u00fcnicode-secret-value!")) {
            val value = """{"url":"https://example.com/h","signing":"HMAC_SHA256","secret":${io.vertx.core.json.Json.encode(bad)}}"""

            assertEquals("INVALID_VALUE", errors(request(webhook(value)))["actions.0.value.secret"], bad)
        }

        // no cipher: a webhook action cannot be stored (fail closed), other actions can
        assertEquals("INVALID", errors(request(webhook("""{"url":"https://example.com/h"}""")), rules(cipher = null))["actions.0.value"])
        assertTrue(save(request("""[{"id":"a1","type":"CREDIT","value":1}]"""), rules(cipher = null)).json.isNotEmpty())
    }

    @Test
    fun `a webhook action changes nothing the guard asks about`() {
        assertTrue(save(request(webhook("""{"url":"https://example.com/h"}"""), caller = Caller())).json.isNotEmpty())
    }

    private fun unchanged(
        stored: String,
        storedChoices: String? = null,
        choices: String? = null,
        billing: BillingMode = BillingMode.ONE_TIME,
        maxQuantity: Int? = null,
        caller: ActionGuard.Caller? = Caller(admin = true)
    ) = ProductActionCheck.Unchanged(stored, storedChoices, billing, maxQuantity, choices, emptyMap(), caller)

    private fun check(r: ProductActionCheck.Unchanged) = runBlocking { rules().onUnchanged(conn, r) }

    @Test
    fun `stored actions are re-checked when a save without actions moves what they depend on`() {
        val choice = save(request("""[{"id":"a1","type":"COMMAND","value":["x"],"serverMode":"BUYER_CHOICE"}]""", choices = "[1]")).json

        // widening the choices needs the console of the new server, narrowing or keeping needs nothing
        assertThrows(NoPermission::class.java) { check(unchanged(choice, storedChoices = "[1]", choices = "[1,2]", caller = Caller(consoles = setOf(1)))) }
        check(unchanged(choice, storedChoices = "[1]", choices = "[1,2]", caller = Caller(consoles = setOf(2))))
        check(unchanged(choice, storedChoices = "[1,2]", choices = "[1]", caller = Caller()))
        check(unchanged(choice, storedChoices = "[1]", choices = "[1]", caller = Caller()))
        assertThrows(NoPermission::class.java) { check(unchanged(choice, storedChoices = "[1]", choices = "[1,2]", caller = null)) }

        // the strict rules apply to the stored list with the new product: no choices left, EXPIRE on a product that is no longer timed, per unit without a limit
        assertEquals("SERVER_CHOICES_REQUIRED", unchangedErrors(unchanged(choice, storedChoices = "[1]", choices = null))["actions.0.serverMode"])

        val timed = save(request("""[{"id":"a1","type":"COMMAND","value":["x"],"phase":"EXPIRE","targetServers":[1]}]""", billing = BillingMode.TIMED)).json

        assertEquals("INVALID_PHASE", unchangedErrors(unchanged(timed, billing = BillingMode.ONE_TIME))["actions.0.phase"])
        check(unchanged(timed, billing = BillingMode.TIMED))

        val perUnit = save(request(command(""","perUnit":true"""), maxQuantity = 5)).json

        assertEquals("PER_UNIT_NEEDS_MAX_QUANTITY", unchangedErrors(unchanged(perUnit, maxQuantity = null))["actions.0.perUnit"])
    }

    private fun unchangedErrors(r: ProductActionCheck.Unchanged): Map<String, String> {
        val e = runCatching { check(r) }.exceptionOrNull()

        assertTrue(e is InvalidProduct, "expected INVALID_PRODUCT, got $e")

        return JsonObject((e as InvalidProduct).encode(emptyMap())).getJsonObject("fieldErrors").map.mapValues { it.value as String }
    }
}
