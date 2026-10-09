package com.panomc.plugins.market.service

import com.panomc.platform.db.dao.WebhookEndpointDao
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.webhook.WebhookRegistry
import com.panomc.platform.webhook.WebhookEndpointService
import com.panomc.platform.webhook.WebhookService as CoreWebhookService
import com.panomc.plugins.market.support.WebhookTestSupport
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Proxy

/**
 * The move of the market's webhook endpoints onto core's system at plugin start (MK-15, doc 06 section 4.4 step 1), against **core's real
 * `WebhookEndpointService.importEndpoint`** and its cipher: the names get the `market.` prefix, the secret and the headers are decrypted with the market's cipher
 * and come out sealed with core's key, the legacy rows and both legacy tables are gone afterwards, a second start does nothing.
 */
class WebhookImportTest {
    private val marketCipher = WebhookTestSupport.cipher()
    private val coreCipher = WebhookTestSupport.coreCipher()

    /** What core's DAO would hold: an in-memory list, ids from 1. */
    private class MemoryEndpoints : WebhookEndpointDao() {
        val rows = ArrayList<WebhookEndpoint>()

        override suspend fun init(sqlClient: SqlClient) = Unit

        override suspend fun add(endpoint: WebhookEndpoint, sqlClient: SqlClient): Long {
            val id = rows.size + 1L
            rows += WebhookEndpoint(
                id = id, name = endpoint.name, url = endpoint.url, events = endpoint.events, format = endpoint.format, signing = endpoint.signing, secret = endpoint.secret,
                headers = endpoint.headers, template = endpoint.template, enabled = endpoint.enabled, maxAttempts = endpoint.maxAttempts,
                createdAt = endpoint.createdAt, updatedAt = endpoint.updatedAt
            )
            return id
        }

        override suspend fun getById(id: Long, sqlClient: SqlClient) = rows.firstOrNull { it.id == id }

        override suspend fun getAll(sqlClient: SqlClient) = rows.toList()

        override suspend fun count(sqlClient: SqlClient) = rows.size

        override suspend fun update(endpoint: WebhookEndpoint, now: Long, sqlClient: SqlClient) = false

        override suspend fun delete(id: Long, sqlClient: SqlClient) = false

        override suspend fun recordOutcome(id: Long, success: Boolean, statusCode: Int?, now: Long, disableAfter: Int, sqlClient: SqlClient) = false
    }

    private class FakeTables(var present: Boolean, val rows: MutableList<LegacyWebhookEndpoint>) : LegacyWebhookTables {
        var dropped = 0

        override suspend fun exists() = present

        override suspend fun endpoints() = rows.toList()

        override suspend fun remove(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun drop() {
            dropped++
            present = false
            rows.clear()
        }
    }

    private val endpoints = MemoryEndpoints()

    /** Core's real service on the in-memory DAO; only `importEndpoint` is used, the other collaborators are never reached. */
    private val core: WebhookEndpointService = run {
        val objenesis = ObjenesisStd()
        val client = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SqlClient::class.java)) { _, method, _ -> error("not reached: ${method.name}") } as SqlClient

        WebhookEndpointService(
            store = object : com.panomc.platform.webhook.WebhookStore {
                override suspend fun <T> read(block: suspend (SqlClient) -> T): T = block(client)

                override suspend fun <T> write(block: suspend (SqlClient) -> T): T = block(client)
            },
            endpoints = endpoints, deliveries = com.panomc.platform.db.implementation.WebhookDeliveryDaoImpl(),
            webhooks = objenesis.newInstance(CoreWebhookService::class.java), registry = WebhookRegistry(null), cipher = { coreCipher },
            checkUrl = { _, _ -> null }, resetFailures = { _, _ -> }
        )
    }

    private val importer = WebhookEndpointImporter { name, url, events, format, signing, secret, headers, template, enabled, maxAttempts ->
        core.importEndpoint(name, url, events, format, signing, secret, headers, template, enabled, maxAttempts)
    }

    private fun legacy(
        id: Long, name: String = "Hook $id", events: String = "[\"order.paid\",\"order.refunded\"]", format: String = "JSON", signing: String = "NONE", secret: String? = null,
        headers: String? = null, template: String? = null, enabled: Boolean = true, maxAttempts: Int = 8
    ) = LegacyWebhookEndpoint(id, name, "https://example.com/hook/$id", events, format, signing, secret, headers, template, enabled, maxAttempts)

    private fun import(tables: LegacyWebhookTables) = WebhookImport(tables, marketCipher, importer)

    @Test
    fun `names get the market prefix and secrets and headers come out sealed with core's key`() = runBlocking {
        val tables = FakeTables(
            true,
            mutableListOf(
                legacy(
                    1, signing = "HMAC_SHA256", secret = marketCipher.encrypt("whsec_0123456789abcdef"),
                    headers = marketCipher.encrypt(JsonObject().put("X-Token", "abc").put("X-Env", "prod").encode()), maxAttempts = 5
                ),
                legacy(2, events = "[\"*\"]", format = "DISCORD", signing = "NONE", template = "{\"content\":\"hi\"}", enabled = false)
            )
        )

        val result = import(tables).run()

        assertEquals(2, result.imported)
        assertEquals(0, result.unreadable)
        assertTrue(result.dropped)

        val first = endpoints.rows[0]

        assertEquals("Hook 1", first.name)
        assertEquals("https://example.com/hook/1", first.url)
        assertEquals(listOf("market.order.paid", "market.order.refunded"), JsonArray(first.events).list)
        assertEquals(WebhookSigning.HMAC_SHA256, first.signing)
        assertEquals(WebhookFormat.JSON, first.format)
        assertEquals(5, first.maxAttempts)
        assertTrue(first.enabled)
        assertTrue(first.secret!!.startsWith("v1:"))
        assertEquals("whsec_0123456789abcdef", coreCipher.decrypt(first.secret!!), "decrypts with the core key")
        assertNull(marketCipher.decrypt(first.secret!!), "and no longer with the market's")
        assertEquals(JsonObject().put("X-Token", "abc").put("X-Env", "prod"), JsonObject(coreCipher.decrypt(first.headers!!)))

        val second = endpoints.rows[1]

        assertEquals(listOf("market.*"), JsonArray(second.events).list)
        assertEquals(WebhookFormat.DISCORD, second.format)
        assertEquals(WebhookSigning.NONE, second.signing)
        assertEquals("{\"content\":\"hi\"}", second.template)
        assertFalse(second.enabled)
        assertNull(second.secret)
    }

    @Test
    fun `the legacy rows and both tables are gone afterwards and a second start is a no-op`() = runBlocking {
        val tables = FakeTables(true, mutableListOf(legacy(1), legacy(2)))
        val run = import(tables)

        assertEquals(2, run.run().imported)
        assertTrue(tables.rows.isEmpty())
        assertFalse(tables.present)
        assertEquals(1, tables.dropped)

        val again = run.run()

        assertEquals(0, again.imported)
        assertFalse(again.dropped)
        assertEquals(1, tables.dropped, "nothing is dropped twice")
        assertEquals(2, endpoints.rows.size, "nothing is imported twice")
    }

    @Test
    fun `no legacy table means nothing happens`() = runBlocking {
        val tables = FakeTables(false, mutableListOf())

        val result = import(tables).run()

        assertEquals(0, result.imported)
        assertFalse(result.dropped)
        assertEquals(0, tables.dropped)
        assertTrue(endpoints.rows.isEmpty())
    }

    @Test
    fun `an empty legacy table is dropped without importing`() = runBlocking {
        val tables = FakeTables(true, mutableListOf())

        val result = import(tables).run()

        assertEquals(0, result.imported)
        assertTrue(result.dropped)
        assertFalse(tables.present)
    }

    @Test
    fun `test ping is not an event any more and an unreadable list goes in disabled with nothing subscribed`() = runBlocking {
        val tables = FakeTables(true, mutableListOf(legacy(1, events = "[\"test.ping\",\"order.paid\",\"order.paid\"]"), legacy(2, events = "not json")))

        val result = import(tables).run()

        assertEquals(1, result.unreadable)
        assertEquals(listOf("market.order.paid"), JsonArray(endpoints.rows[0].events).list)
        assertTrue(endpoints.rows[0].enabled)
        assertEquals(emptyList<Any>(), JsonArray(endpoints.rows[1].events).list)
        assertFalse(endpoints.rows[1].enabled)
    }

    @Test
    fun `a secret or headers the market cannot read import the endpoint disabled and never unsigned by accident`() = runBlocking {
        val tables = FakeTables(
            true,
            mutableListOf(
                legacy(1, signing = "HMAC_SHA256", secret = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                legacy(2, signing = "HMAC_SHA256", secret = null),
                legacy(3, headers = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                legacy(4, headers = marketCipher.encrypt("[1,2]"))
            )
        )

        val result = import(tables).run()

        assertEquals(4, result.imported)
        assertEquals(4, result.unreadable)
        assertTrue(endpoints.rows.none { it.enabled }, "they wait for the owner's decision in the panel")
        assertEquals(listOf(WebhookSigning.NONE, WebhookSigning.NONE), endpoints.rows.take(2).map { it.signing })
        assertTrue(endpoints.rows.all { it.secret == null && it.headers == null })
    }

    @Test
    fun `an import that fails half way keeps the tables and the rows not yet imported, and the next start finishes without a duplicate`() = runBlocking {
        val tables = FakeTables(true, mutableListOf(legacy(1), legacy(2), legacy(3)))
        var calls = 0
        val flaky = WebhookEndpointImporter { name, url, events, format, signing, secret, headers, template, enabled, maxAttempts ->
            if (++calls == 2) throw IllegalStateException("the database went away")

            importer.import(name, url, events, format, signing, secret, headers, template, enabled, maxAttempts)
        }

        assertThrows(IllegalStateException::class.java) { runBlocking { WebhookImport(tables, marketCipher, flaky).run() } }

        assertEquals(1, endpoints.rows.size)
        assertEquals(listOf(2L, 3L), tables.rows.map { it.id }, "the imported row is removed from the legacy table at once")
        assertTrue(tables.present)
        assertEquals(0, tables.dropped)

        runWebhookImport(WebhookImport(tables, marketCipher, flaky))

        assertEquals(listOf("Hook 1", "Hook 2", "Hook 3"), endpoints.rows.map { it.name })
        assertFalse(tables.present)
    }

    @Test
    fun `runWebhookImport never throws`() = runBlocking {
        val tables = FakeTables(true, mutableListOf(legacy(1)))

        runWebhookImport(WebhookImport(tables, marketCipher) { _, _, _, _, _, _, _, _, _, _ -> throw IllegalStateException("boom") })

        assertTrue(tables.present)
        assertEquals(1, tables.rows.size)
    }

    @Test
    fun `event names and headers are read the way the old columns held them`() {
        assertEquals(listOf("market.order.paid", "market.*"), WebhookImport.eventsOf("[\"order.paid\",\"*\"]"))
        assertNull(WebhookImport.eventsOf("[1]"))
        assertNull(WebhookImport.eventsOf("{}"))
        assertEquals(mapOf("A" to "1"), WebhookImport.headersOf("{\"A\":\"1\"}"))
        assertNull(WebhookImport.headersOf("[]"))
    }
}
