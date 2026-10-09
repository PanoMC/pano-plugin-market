package com.panomc.plugins.market.service

import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.webhook.PoolWebhookStore
import com.panomc.platform.webhook.WebhookEndpointService
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.support.WebhookTestSupport
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The start-up move of the store's webhook endpoints onto core's system on a real MariaDB (MK-15, doc 06 section 4.4 step 1): the old `market_webhook_endpoint` /
 * `market_webhook_delivery` tables as the earlier schema created them, the SQL adapter, core's real `WebhookEndpointService.importEndpoint` over core's real tables.
 * The names are prefixed, the secret and the headers open with core's key, both old tables are dropped, the second start does nothing.
 */
class WebhookImportIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private lateinit var vertx: Vertx
    private val marketCipher = WebhookTestSupport.cipher()

    @BeforeAll
    fun startVertx() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stopVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @BeforeEach
    fun dropLegacy(): Unit = runBlocking {
        sql("DROP TABLE IF EXISTS `${prefix}market_webhook_delivery`, `${prefix}market_webhook_endpoint`")
    }

    private suspend fun createLegacyTables() {
        sql(
            "CREATE TABLE `${prefix}market_webhook_endpoint` (`id` bigint NOT NULL AUTO_INCREMENT, `name` varchar(128) NOT NULL, `url` varchar(1024) NOT NULL, `events` text NOT NULL, " +
                "`format` varchar(16) NOT NULL DEFAULT 'JSON', `signing` varchar(16) NOT NULL DEFAULT 'NONE', `secret` text, `headers` text, `template` text, " +
                "`enabled` tinyint(1) NOT NULL DEFAULT 1, `maxAttempts` int NOT NULL DEFAULT 8, `failureCount` int NOT NULL DEFAULT 0, `createdAt` bigint NOT NULL, `updatedAt` bigint NOT NULL, " +
                "PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        )
        sql(
            "CREATE TABLE `${prefix}market_webhook_delivery` (`id` bigint NOT NULL AUTO_INCREMENT, `endpointId` bigint NOT NULL DEFAULT 0, `eventId` char(36) NOT NULL, `event` varchar(64) NOT NULL, " +
                "`body` text NOT NULL, `createdAt` bigint NOT NULL, `updatedAt` bigint NOT NULL, PRIMARY KEY (`id`), UNIQUE KEY `uq_eventId` (`eventId`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        )
    }

    private suspend fun legacyEndpoint(name: String, events: String, signing: String = "NONE", secret: String? = null, headers: String? = null, format: String = "JSON", enabled: Int = 1) {
        sql(
            "INSERT INTO `${prefix}market_webhook_endpoint` (`name`, `url`, `events`, `format`, `signing`, `secret`, `headers`, `enabled`, `maxAttempts`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 5, 1, 1)",
            name, "https://example.com/$name", events, format, signing, secret, headers, enabled
        )
    }

    private suspend fun tableExists(name: String): Boolean =
        sql("SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", "$prefix$name").isNotEmpty()

    private fun importer(h: WebhookHarness): WebhookEndpointImporter {
        val service = WebhookEndpointService(
            store = PoolWebhookStore { pool }, endpoints = w.webhookEndpoints, deliveries = w.webhookDeliveries, webhooks = h.core, registry = h.registry, cipher = { h.cipher },
            checkUrl = { _, _ -> null }, resetFailures = { _, _ -> }
        )

        return WebhookEndpointImporter { name, url, events, format, signing, secret, headers, template, enabled, maxAttempts ->
            service.importEndpoint(name, url, events, format, signing, secret, headers, template, enabled, maxAttempts)
        }
    }

    private fun import(h: WebhookHarness) = WebhookImport(SqlLegacyWebhookTables({ pool }, { prefix }), marketCipher, importer(h))

    @Test
    fun `the endpoints arrive with market names and secrets that open with core's key, and both old tables are dropped`(): Unit = runBlocking {
        val h = WebhookHarness(w, vertx)

        createLegacyTables()
        legacyEndpoint(
            "paid", "[\"order.paid\",\"order.refunded\"]", "HMAC_SHA256", marketCipher.encrypt("whsec_0123456789abcdef"),
            marketCipher.encrypt(JsonObject().put("X-Token", "abc").encode())
        )
        legacyEndpoint("discord", "[\"*\"]", format = "DISCORD", enabled = 0)
        sql("INSERT INTO `${prefix}market_webhook_delivery` (`eventId`, `event`, `body`, `createdAt`, `updatedAt`) VALUES (UUID(), 'order.paid', '{}', 1, 1)")

        val result = import(h).run()

        assertEquals(2, result.imported)
        assertEquals(0, result.unreadable)
        assertTrue(result.dropped)

        assertFalse(tableExists("market_webhook_endpoint"))
        assertFalse(tableExists("market_webhook_delivery"), "the delivery rows are not copied, their table goes with the endpoints")

        val rows = h.core.let { w.webhookEndpoints.getAll(pool) }

        assertEquals(listOf("paid", "discord"), rows.map { it.name })

        val paid = rows[0]

        assertEquals(listOf("market.order.paid", "market.order.refunded"), JsonArray(paid.events).list)
        assertEquals(WebhookSigning.HMAC_SHA256, paid.signing)
        assertEquals(5, paid.maxAttempts)
        assertTrue(paid.enabled)
        assertEquals("whsec_0123456789abcdef", h.cipher.decrypt(paid.secret!!))
        assertEquals("abc", JsonObject(h.cipher.decrypt(paid.headers!!)).getString("X-Token"))

        val discord = rows[1]

        assertEquals(listOf("market.*"), JsonArray(discord.events).list)
        assertEquals(WebhookFormat.DISCORD, discord.format)
        assertFalse(discord.enabled)
        assertNull(discord.secret)
    }

    @Test
    fun `a second start finds no table and does nothing, so does a fresh install`(): Unit = runBlocking {
        val h = WebhookHarness(w, vertx)

        // a fresh install never had the tables
        val fresh = import(h).run()

        assertEquals(0, fresh.imported)
        assertFalse(fresh.dropped)

        createLegacyTables()
        legacyEndpoint("one", "[\"order.paid\"]")

        assertEquals(1, import(h).run().imported)

        val again = import(h).run()

        assertEquals(0, again.imported)
        assertFalse(again.dropped)
        assertEquals(1, w.webhookEndpoints.count(pool))
    }
}
