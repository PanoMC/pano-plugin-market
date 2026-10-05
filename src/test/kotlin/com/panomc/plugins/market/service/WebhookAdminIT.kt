package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketWebhookDelivery
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.db.model.WebhookFormat
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.error.InvalidWebhookUrl
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.routes.panel.webhook.WebhookAdminService
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.support.StubResolver
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.support.WebhookTestSupport
import com.panomc.plugins.market.util.Paging
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `WebhookAdminService` on a real MariaDB (MK-106; 08 sections 15.2, 15.5, 16.3; 04 section 8): the field rules, the secret shown once and masked on every
 * read, encrypted header values, the URL policy on save (`INVALID_WEBHOOK_URL`), `DISCORD` + `HMAC` normalised to `NONE`, the test ping with its L8 limit,
 * redelivery, the delivery log, and what disabling and deleting do to open rows.
 */
class WebhookAdminIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: WebhookHarness
    private lateinit var admin: WebhookAdminService
    private lateinit var resolver: StubResolver
    private lateinit var vertx: Vertx
    private val gateways = ArrayList<FakeGateway>()
    private var allowPrivate = false
    private val limited = ArrayList<Long>()

    @BeforeAll
    fun startVertx() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stopVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        resolver = StubResolver("hooks.example.com" to listOf("93.184.216.34"), "discord.com" to listOf("162.159.135.232"), "internal.example.com" to listOf("10.0.0.5"))
        allowPrivate = false
        limited.clear()
        h = WebhookHarness(w, vertx, resolver, allowPrivate = true)

        val limits = MarketRateLimits { w.config }

        admin = WebhookAdminService(
            w.db, w.clock, w.webhookEndpoints, w.webhookDeliveries, h.service, h.cipher, WebhookTestSupport.outbound(vertx, resolver), { allowPrivate },
            { prefix }, { userId -> limited += userId; limits.panelAction(userId) }
        )
    }

    @AfterEach
    fun closeGateways() {
        gateways.forEach { it.close() }
        gateways.clear()
    }

    private fun gateway(): FakeGateway = FakeGateway.start(vertx).also { gateways += it }

    private fun body(vararg pairs: Pair<String, Any?>): JsonObject {
        val o = JsonObject().put("name", "Ops").put("url", "https://hooks.example.com/in").put("events", JsonArray().add("order.paid"))

        for ((k, v) in pairs) o.put(k, v)

        return o
    }

    private fun fieldErrors(e: Error): JsonObject = JsonObject(e.encode(emptyMap())).getJsonObject("fieldErrors")

    private fun reasonOf(e: Error): String = JsonObject(e.encode(emptyMap())).getString("reason")

    private suspend fun row(id: Long) = w.webhookEndpoints.getById(id, pool)!!

    private suspend fun openRow(endpointId: Long, status: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING, event: String = "order.paid", key: String = w.ids.uuid()): Long {
        val now = w.clock.now()

        return w.webhookDeliveries.add(
            MarketWebhookDelivery(
                endpointId = endpointId, eventId = key, event = event, url = "https://hooks.example.com/in", body = "{}", status = status, attempts = 0, maxAttempts = 8,
                nextAttemptAt = if (status == WebhookDeliveryStatus.PENDING) now else null, createdAt = now, updatedAt = now
            ),
            pool
        )!!
    }

    // ----- secret ---------------------------------------------------------------------------------------------------

    @Test
    fun `a generated HMAC secret is returned once, stored encrypted and masked on every read`(): Unit = runBlocking {
        val saved = admin.create(body("signing" to "HMAC_SHA256"))

        assertTrue(saved.secret!!.startsWith("whsec_"))
        assertEquals(6 + 43, saved.secret!!.length) // whsec_ + base64url(32 bytes) without padding

        val stored = row(saved.id)

        assertEquals(WebhookSigning.HMAC_SHA256, stored.signing)
        assertTrue(stored.secret!!.startsWith("v1:"))
        assertFalse(stored.secret!!.contains(saved.secret!!))
        assertEquals(saved.secret, h.cipher.decrypt(stored.secret!!))

        val listed = admin.list()
        val view = listed.getJsonArray("webhooks").getJsonObject(0)

        assertEquals("********", view.getString("secret"))
        assertFalse(listed.encode().contains(saved.secret!!))
        assertFalse(listed.encode().contains(stored.secret!!))

        // an update that sends nothing or the mask keeps the secret and never returns one
        for (value in listOf(null, "", "********")) {
            val update = admin.update(saved.id, body("signing" to "HMAC_SHA256", "secret" to value))

            assertNull(update.secret)
            assertEquals(saved.secret, h.cipher.decrypt(row(saved.id).secret!!))
        }

        // a new value replaces it and is not echoed
        val replaced = admin.update(saved.id, body("signing" to "HMAC_SHA256", "secret" to "my-own-secret-123456"))

        assertNull(replaced.secret)
        assertEquals("my-own-secret-123456", h.cipher.decrypt(row(saved.id).secret!!))
    }

    @Test
    fun `a given secret is validated, not generated and not returned, NONE keeps no secret`(): Unit = runBlocking {
        val e = assertThrows<InvalidSettings> { runBlocking { admin.create(body("signing" to "HMAC_SHA256", "secret" to "short")) } }

        assertEquals("INVALID", fieldErrors(e).getString("secret"))
        assertThrows<InvalidSettings> { runBlocking { admin.create(body("signing" to "HMAC_SHA256", "secret" to "a".repeat(129))) } }
        assertThrows<InvalidSettings> { runBlocking { admin.create(body("signing" to "HMAC_SHA256", "secret" to "tab\tinside-the-secret-xx")) } }

        val given = admin.create(body("signing" to "HMAC_SHA256", "secret" to "0123456789abcdef"))

        assertNull(given.secret)
        assertEquals("0123456789abcdef", h.cipher.decrypt(row(given.id).secret!!))

        val none = admin.create(body("name" to "Plain", "secret" to "0123456789abcdef"))

        assertNull(none.secret)
        assertNull(row(none.id).secret)
    }

    @Test
    fun `DISCORD with HMAC is normalised to NONE and keeps no secret`(): Unit = runBlocking {
        val saved = admin.create(body("url" to "https://discord.com/api/webhooks/1/abc", "format" to "DISCORD", "signing" to "HMAC_SHA256"))

        assertNull(saved.secret)

        val stored = row(saved.id)

        assertEquals(WebhookFormat.DISCORD, stored.format)
        assertEquals(WebhookSigning.NONE, stored.signing)
        assertNull(stored.secret)

        // switching an HMAC endpoint to DISCORD drops the signature, switching back generates a fresh secret that is shown once
        val hmac = admin.create(body("name" to "Sig", "signing" to "HMAC_SHA256"))

        admin.update(hmac.id, body("name" to "Sig", "url" to "https://discord.com/api/webhooks/1/abc", "format" to "DISCORD", "signing" to "HMAC_SHA256"))
        assertEquals(WebhookSigning.NONE, row(hmac.id).signing)
        assertNull(row(hmac.id).secret)

        val back = admin.update(hmac.id, body("name" to "Sig", "format" to "JSON", "signing" to "HMAC_SHA256"))

        assertNotNull(back.secret)
        assertEquals(WebhookSigning.HMAC_SHA256, row(hmac.id).signing)
    }

    // ----- headers --------------------------------------------------------------------------------------------------------

    @Test
    fun `header values are stored encrypted and read back masked, a masked value on update keeps the stored one`(): Unit = runBlocking {
        val saved = admin.create(body("headers" to JsonObject().put("Authorization", "Bearer s3cr3t-token").put("X-Team", "ops")))
        val stored = row(saved.id)

        assertTrue(stored.headers!!.startsWith("v1:"))
        assertFalse(stored.headers!!.contains("s3cr3t-token"))
        assertEquals("Bearer s3cr3t-token", JsonObject(h.cipher.decrypt(stored.headers!!)!!).getString("Authorization"))

        val view = admin.list().getJsonArray("webhooks").getJsonObject(0)

        assertEquals(JsonObject().put("Authorization", "********").put("X-Team", "********"), view.getJsonObject("headers"))
        assertFalse(admin.list().encode().contains("s3cr3t-token"))

        admin.update(saved.id, body("headers" to JsonObject().put("Authorization", "********").put("X-Team", "devs")))

        val after = JsonObject(h.cipher.decrypt(row(saved.id).headers!!)!!)

        assertEquals("Bearer s3cr3t-token", after.getString("Authorization"))
        assertEquals("devs", after.getString("X-Team"))

        // a masked value for a name that was never stored is refused; no headers key keeps them; null clears them
        val e = assertThrows<InvalidSettings> { runBlocking { admin.update(saved.id, body("headers" to JsonObject().put("X-New", "********"))) } }

        assertEquals("INVALID_VALUE", fieldErrors(e).getString("headers.X-New"))
        admin.update(saved.id, body())
        assertEquals(2, JsonObject(h.cipher.decrypt(row(saved.id).headers!!)!!).size())
        admin.update(saved.id, body("headers" to null))
        assertNull(row(saved.id).headers)
    }

    @Test
    fun `forbidden header names, bad values and too many headers are field errors`(): Unit = runBlocking {
        val headers = JsonObject().put("Host", "x").put("X-Pano-Event", "x").put("Content-Type", "x").put("Good-One", "line1\r\nInjected: 1").put("ok", "fine")
        val e = assertThrows<InvalidSettings> { runBlocking { admin.create(body("headers" to headers)) } }

        assertEquals("INVALID_NAME", fieldErrors(e).getString("headers.Host"))
        assertEquals("INVALID_NAME", fieldErrors(e).getString("headers.X-Pano-Event"))
        assertEquals("INVALID_NAME", fieldErrors(e).getString("headers.Content-Type"))
        assertEquals("INVALID_VALUE", fieldErrors(e).getString("headers.Good-One"))
        assertNull(fieldErrors(e).getString("headers.ok"))

        val many = JsonObject()

        for (i in 1..11) many.put("X-H$i", "v")

        assertEquals("TOO_MANY", fieldErrors(assertThrows<InvalidSettings> { runBlocking { admin.create(body("headers" to many)) } }).getString("headers"))
        assertTrue(w.webhookEndpoints.getAll(pool).isEmpty())
    }

    // ----- URL policy ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a private or malformed target is INVALID_WEBHOOK_URL with the reason`(): Unit = runBlocking {
        suspend fun reason(url: String, format: String = "JSON"): String =
            reasonOf(assertThrows<InvalidWebhookUrl> { runBlocking { admin.create(body("url" to url, "format" to format)) } })

        assertEquals("PRIVATE_ADDRESS", reason("https://127.0.0.1/hook"))
        assertEquals("PRIVATE_ADDRESS", reason("https://internal.example.com/hook"), "a name that resolves to a private address")
        assertEquals("PRIVATE_ADDRESS", reason("http://169.254.169.254/latest/meta-data"))
        assertEquals("SCHEME", reason("ftp://hooks.example.com/x"))
        assertEquals("USERINFO", reason("https://user:pw@hooks.example.com/x"))
        assertEquals("DNS", reason("https://unknown.example.com/x"))
        assertEquals("MALFORMED", reason(""))
        assertEquals("MALFORMED", reason("https://hooks.example.com/" + "a".repeat(1030)))
        assertEquals("DISCORD_URL", reason("https://hooks.example.com/x", "DISCORD"))
        assertTrue(w.webhookEndpoints.getAll(pool).isEmpty())

        // the owner's switch allows a private target, never a link-local one
        allowPrivate = true

        assertNotNull(admin.create(body("url" to "https://internal.example.com/hook")).id)
        assertEquals("PRIVATE_ADDRESS", reason("http://169.254.169.254/latest/meta-data"))
    }

    @Test
    fun `an update that changes the url is checked again, one that keeps it is not resolved`(): Unit = runBlocking {
        val saved = admin.create(body())

        assertEquals("PRIVATE_ADDRESS", reasonOf(assertThrows<InvalidWebhookUrl> { runBlocking { admin.update(saved.id, body("url" to "https://127.0.0.1/x")) } }))
        assertEquals("https://hooks.example.com/in", row(saved.id).url)

        val lookups = resolver.lookups.get()

        admin.update(saved.id, body("name" to "Renamed"))
        assertEquals(lookups, resolver.lookups.get())
        assertEquals("Renamed", row(saved.id).name)
    }

    // ----- field rules -------------------------------------------------------------------------------------------------------

    @Test
    fun `name, events, format, signing, attempts and the endpoint limit are validated`(): Unit = runBlocking {
        fun errors(vararg pairs: Pair<String, Any?>) = fieldErrors(assertThrows<InvalidSettings> { runBlocking { admin.create(body(*pairs)) } })

        assertEquals("REQUIRED", errors("name" to "  ").getString("name"))
        assertEquals("TOO_LONG", errors("name" to "n".repeat(129)).getString("name"))
        assertEquals("REQUIRED", errors("events" to JsonArray()).getString("events"))
        assertEquals("INVALID", errors("events" to JsonArray().add("order.nope")).getString("events"))
        assertEquals("INVALID", errors("events" to JsonArray().add("action.grant")).getString("events"), "action events are not subscribable")
        assertEquals("INVALID", errors("format" to "XML").getString("format"))
        assertEquals("INVALID", errors("signing" to "MD5").getString("signing"))
        assertEquals("OUT_OF_RANGE", errors("maxAttempts" to 0).getString("maxAttempts"))
        assertEquals("OUT_OF_RANGE", errors("maxAttempts" to 21).getString("maxAttempts"))
        assertEquals("NOT_ALLOWED", errors("template" to """{"content":"x"}""").getString("template"))

        val saved = admin.create(body("events" to JsonArray().add("*").add("order.paid").add("order.paid"), "maxAttempts" to 20))

        assertEquals(JsonArray().add("*").add("order.paid"), JsonArray(row(saved.id).events))
        assertEquals(20, row(saved.id).maxAttempts)
        assertEquals(8, row(admin.create(body("name" to "Default")).id).maxAttempts)

        for (i in 1..18) admin.create(body("name" to "E$i"))

        assertEquals(20, w.webhookEndpoints.getAll(pool).size)
        assertEquals("LIMIT_REACHED", errors("name" to "One too many").getString("name"))
    }

    @Test
    fun `a custom Discord template is validated on save`(): Unit = runBlocking {
        val url = "https://discord.com/api/webhooks/1/abc"

        fun save(template: String) = body("url" to url, "format" to "DISCORD", "template" to template)

        val bad = assertThrows<InvalidSettings> { runBlocking { admin.create(save("""{"username":"no content or embeds"}""")) } }

        assertEquals("INVALID_JSON", fieldErrors(bad).getString("template"))
        assertEquals("TOO_LONG", fieldErrors(assertThrows<InvalidSettings> { runBlocking { admin.create(save("""{"content":"${"a".repeat(8200)}"}""")) } }).getString("template"))

        val ok = admin.create(save("""{"content":"{username} bought {items.inline}"}"""))

        assertEquals("""{"content":"{username} bought {items.inline}"}""", row(ok.id).template)
        assertTrue(admin.list().getJsonObject("defaults").getString("discordTemplate").contains("{event.title}"))
        assertEquals(WebhookEvents.SUBSCRIBABLE, admin.list().getJsonArray("eventNames").map { it as String })
    }

    // ----- test ping ------------------------------------------------------------------------------------------------------------

    @Test
    fun `the test ping is sent synchronously, works on a disabled endpoint, is limited per user and 404s for an unknown id`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(204) }

        allowPrivate = true

        val saved = admin.create(body("url" to "${g.baseUrl}/hook", "enabled" to false))
        val result = admin.test(saved.id, 7)

        assertEquals(204, result.statusCode)
        assertNull(result.error)
        assertEquals(listOf(7L), limited)

        val request = g.requestsTo("/hook").single()

        assertEquals("test.ping", request.header("X-Pano-Event"))
        assertEquals("test.ping", JsonObject(request.bodyText()).getString("event"))
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.rows().single().status)
        assertEquals(1, h.rows().single().maxAttempts)
        // the endpoint's failure counter is untouched and it stays disabled
        assertEquals(0, row(saved.id).failureCount)
        assertFalse(row(saved.id).enabled)

        assertThrows<NotFound> { runBlocking { admin.test(99_999, 7) } }

        // L8: 10 a minute per panel user; the first call above and the 404 count too
        var refused = 0

        repeat(12) { try { admin.test(saved.id, 7) } catch (e: TooManyRequests) { refused++ } }

        assertTrue(refused >= 1, "the eleventh call of a minute is refused")
        assertEquals(12 + 2, limited.size)
    }

    @Test
    fun `a failing receiver shows in the ping result and a refused address never reaches the network`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(500) }

        allowPrivate = true

        val saved = admin.create(body("url" to "${g.baseUrl}/hook"))

        assertEquals(500, admin.test(saved.id, 1).statusCode)

        // the owner switch is turned off later: the send-time guard refuses the private target
        h.allowPrivate = false

        val refused = admin.test(saved.id, 2)

        assertNull(refused.statusCode)
        assertEquals("URL_GUARD:PRIVATE_ADDRESS", refused.error)
        assertEquals(1, g.requestsTo("/hook").size)
    }

    // ----- redeliver and log -----------------------------------------------------------------------------------------------------

    @Test
    fun `redeliver puts the same row back to PENDING, refuses SENDING and 404s for an unknown id`(): Unit = runBlocking {
        val saved = admin.create(body())
        val dead = openRow(saved.id, WebhookDeliveryStatus.DEAD)
        val eventId = h.row(dead).eventId

        admin.redeliver(dead)

        val back = h.row(dead)

        assertEquals(WebhookDeliveryStatus.PENDING, back.status)
        assertEquals(0, back.attempts)
        assertEquals(eventId, back.eventId)
        assertEquals(w.clock.now(), back.nextAttemptAt)
        assertEquals(1, h.rows().size)

        for (status in listOf(WebhookDeliveryStatus.SUCCEEDED, WebhookDeliveryStatus.FAILED)) {
            val id = openRow(saved.id, status)

            admin.redeliver(id)
            assertEquals(WebhookDeliveryStatus.PENDING, h.row(id).status)
        }

        val sending = openRow(saved.id, WebhookDeliveryStatus.SENDING)

        assertThrows<BadRequest> { runBlocking { admin.redeliver(sending) } }
        assertEquals(WebhookDeliveryStatus.SENDING, h.row(sending).status)
        assertThrows<NotFound> { runBlocking { admin.redeliver(99_999) } }
    }

    @Test
    fun `the delivery log pages, filters by status and endpoint, and the detail carries body and response`(): Unit = runBlocking {
        val a = admin.create(body("name" to "A")).id
        val b = admin.create(body("name" to "B")).id

        repeat(3) { openRow(a, WebhookDeliveryStatus.SUCCEEDED) }
        repeat(2) { openRow(b, WebhookDeliveryStatus.DEAD) }
        openRow(a, WebhookDeliveryStatus.PENDING)

        val all = admin.deliveries(null, null, Paging.Window(1, 4))

        assertEquals(6, all.count)
        assertEquals(2, all.totalPage)
        assertEquals(4, all.deliveries.size)
        assertEquals(all.deliveries.map { it.id }.sortedDescending(), all.deliveries.map { it.id }, "newest first")
        assertEquals(2, admin.deliveries(null, null, Paging.Window(2, 4)).deliveries.size)
        assertEquals(4, admin.deliveries(a, null, Paging.Window(1, 10)).count)
        assertEquals(2, admin.deliveries(null, WebhookDeliveryStatus.DEAD, Paging.Window(1, 10)).count)
        assertEquals(1, admin.deliveries(a, WebhookDeliveryStatus.PENDING, Paging.Window(1, 10)).count)
        assertThrows<NotFound> { runBlocking { admin.deliveries(99_999, null, Paging.Window(1, 10)) } }
        assertThrows<com.panomc.platform.error.PageNotFound> { runBlocking { admin.deliveries(null, null, Paging.Window(9, 4)) } }
        assertThrows<RequestValueException> { admin.statusOf("NOPE") }
        assertEquals(WebhookDeliveryStatus.DEAD, admin.statusOf("DEAD"))
        assertNull(admin.statusOf(null))

        val detail = admin.delivery(all.deliveries.first().id)

        assertEquals("{}", detail.getString("body"))
        assertTrue(detail.containsKey("lastResponse"))
        assertFalse(detail.containsKey("secret"))
        assertFalse(admin.listView(all.deliveries.first()).containsKey("body"))
        assertThrows<NotFound> { runBlocking { admin.delivery(99_999) } }
    }

    // ----- disable, enable, delete ------------------------------------------------------------------------------------------------

    @Test
    fun `disabling and deleting end the open rows as DEAD and enabling resets the failure counter`(): Unit = runBlocking {
        val saved = admin.create(body())
        val pending = openRow(saved.id, WebhookDeliveryStatus.PENDING)
        val failed = openRow(saved.id, WebhookDeliveryStatus.FAILED)
        val done = openRow(saved.id, WebhookDeliveryStatus.SUCCEEDED)

        sql("UPDATE `${prefix}market_webhook_endpoint` SET `failureCount` = 7 WHERE `id` = ?", saved.id)

        admin.update(saved.id, body("enabled" to false))

        assertFalse(row(saved.id).enabled)
        assertEquals("MANUAL", row(saved.id).disabledReason)
        assertEquals(WebhookDeliveryStatus.DEAD, h.row(pending).status)
        assertEquals("ENDPOINT_DISABLED", h.row(pending).lastError)
        assertEquals(WebhookDeliveryStatus.DEAD, h.row(failed).status)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.row(done).status)

        // no new rows while disabled
        assertEquals(0, h.emit("order.paid", "p1"))

        sql("UPDATE `${prefix}market_webhook_endpoint` SET `disabledReason` = 'AUTO_DISABLED_FAILURES' WHERE `id` = ?", saved.id)
        admin.update(saved.id, body("enabled" to true))

        assertTrue(row(saved.id).enabled)
        assertEquals(0, row(saved.id).failureCount)
        assertNull(row(saved.id).disabledReason)
        assertEquals(1, h.emit("order.paid", "p2"))

        val open = openRow(saved.id, WebhookDeliveryStatus.FAILED)

        admin.delete(saved.id)

        assertNull(w.webhookEndpoints.getById(saved.id, pool))
        assertEquals("ENDPOINT_DELETED", h.row(open).lastError)
        assertEquals(WebhookDeliveryStatus.DEAD, h.row(open).status)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.row(done).status)
        assertNotEquals(WebhookDeliveryStatus.PENDING, h.rows().first { it.event == "order.paid" && it.endpointId == saved.id && it.id != pending && it.id != failed && it.id != done && it.id != open }.status)
        assertThrows<NotFound> { runBlocking { admin.delete(saved.id) } }
        assertThrows<NotFound> { runBlocking { admin.update(saved.id, body()) } }
    }
}
