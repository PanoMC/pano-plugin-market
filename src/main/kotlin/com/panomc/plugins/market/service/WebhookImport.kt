package com.panomc.plugins.market.service

import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.plugins.market.provider.SecretCipher
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** One row of the old `market_webhook_endpoint` table, `secret` and `headers` still sealed with the market's cipher. */
class LegacyWebhookEndpoint(
    val id: Long,
    val name: String,
    val url: String,
    /** The JSON list of the `events` column. */
    val events: String,
    val format: String,
    val signing: String,
    val secret: String?,
    val headers: String?,
    val template: String?,
    val enabled: Boolean,
    val maxAttempts: Int
)

/** The old market tables, as the import sees them: present or not, their endpoint rows, and the drop. */
interface LegacyWebhookTables {
    /** `true` while the table `market_webhook_endpoint` exists. */
    suspend fun exists(): Boolean

    suspend fun endpoints(): List<LegacyWebhookEndpoint>

    /** Removes one imported endpoint row, so a start that stops half way never imports it twice. */
    suspend fun remove(id: Long)

    /** Drops `market_webhook_endpoint` and `market_webhook_delivery`. */
    suspend fun drop()
}

/** Core's `WebhookEndpointService.importEndpoint`, which encrypts with core's key (`webhook.key`). */
fun interface WebhookEndpointImporter {
    suspend fun import(
        name: String, url: String, events: List<String>, format: WebhookFormat, signing: WebhookSigning,
        plainSecret: String?, plainHeaders: Map<String, String>?, template: String?, enabled: Boolean, maxAttempts: Int
    ): Long
}

/**
 * Moves the store's webhook endpoints onto core's system (open front-end plan, doc 06 section 4.4 step 1). A `DatabaseMigration` handler only gets a
 * `SqlClient` and cannot reach the market's cipher, so this runs **at plugin start**, once, whenever the table `market_webhook_endpoint` exists:
 *
 * 1. every row is read, `secret` and `headers` are decrypted with the market's cipher, event names get the `market.` prefix (`"*"` becomes `"market.*"`,
 *    `test.ping` is dropped: core's panel has its own test button);
 * 2. [WebhookEndpointImporter] stores it (core encrypts with its key), then the legacy row is removed;
 * 3. when every row is in, both market tables (endpoints **and** deliveries) are dropped. A DDL statement commits by itself, so "the same transaction" is
 *    the order above: a start that stops half way finds only the rows not yet imported and finishes the job; nothing is imported twice.
 *
 * Delivery rows are not copied: the market only ever ran as prereleases and payments are live nowhere (decision 17). A second start finds no table and
 * does nothing. A row whose secret or headers cannot be read is imported **disabled** and, when it signed, without signing (core refuses HMAC without a
 * secret): the owner sees it in the panel and decides; nothing is ever sent unsigned that was configured to sign.
 */
class WebhookImport(
    private val tables: LegacyWebhookTables,
    private val cipher: SecretCipher,
    private val importer: WebhookEndpointImporter
) {
    /** What one run did. [dropped] is `true` when the legacy tables are gone afterwards. */
    class Result(val imported: Int, val unreadable: Int, val dropped: Boolean)

    suspend fun run(): Result {
        if (!tables.exists()) return Result(0, 0, dropped = false)

        var imported = 0
        var unreadable = 0

        for (row in tables.endpoints()) {
            val readable = importOne(row)

            imported++

            if (!readable) unreadable++

            tables.remove(row.id)
        }

        tables.drop()

        logger.info("The store's {} webhook endpoint(s) moved to the platform's webhooks ({} had to be imported disabled); the old tables are dropped", imported, unreadable)

        return Result(imported, unreadable, dropped = true)
    }

    /** Imports [row]; `false` when its secret, headers or events could not be read and it went in disabled. */
    private suspend fun importOne(row: LegacyWebhookEndpoint): Boolean {
        var readable = true
        val format = WebhookFormat.entries.firstOrNull { it.name == row.format } ?: WebhookFormat.JSON
        var signing = if (format == WebhookFormat.DISCORD) WebhookSigning.NONE else WebhookSigning.entries.firstOrNull { it.name == row.signing } ?: WebhookSigning.NONE

        val events = eventsOf(row.events) ?: run {
            readable = false
            emptyList()
        }

        var secret: String? = null

        if (signing == WebhookSigning.HMAC_SHA256) {
            secret = row.secret?.let { cipher.decrypt(it) }?.takeIf { it.isNotEmpty() }

            if (secret == null) {
                readable = false
                signing = WebhookSigning.NONE
            }
        }

        var headers: Map<String, String>? = null

        if (!row.headers.isNullOrEmpty()) {
            headers = row.headers.let { stored -> cipher.decrypt(stored) }?.let { headersOf(it) }

            if (headers == null) readable = false
        }

        importer.import(
            row.name, row.url, events, format, signing, secret, headers, row.template, row.enabled && readable, row.maxAttempts
        )

        return readable
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebhookImport::class.java)

        /** The market's names as core knows them: `order.paid` -> `market.order.paid`, `*` -> `market.*`; `test.ping` is gone. `null` = not a JSON list of strings. */
        fun eventsOf(json: String): List<String>? {
            val names = try {
                JsonArray(json).list.map { it as? String ?: return null }
            } catch (e: Exception) {
                return null
            }

            return names.filter { it != "test.ping" }.map { if (it == "*") "market.*" else "market.$it" }.distinct()
        }

        /** The plain JSON object of a decrypted headers column, or `null` when it is not one. */
        fun headersOf(json: String): Map<String, String>? {
            return try {
                val obj = JsonObject(json)
                val out = LinkedHashMap<String, String>()

                for (name in obj.fieldNames()) out[name] = obj.getValue(name)?.toString() ?: return null

                out
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** [LegacyWebhookTables] on the database: raw SQL with the table prefix, no DAO (the DAOs of these tables are gone). */
class SqlLegacyWebhookTables(private val client: suspend () -> SqlClient, private val prefix: () -> String) : LegacyWebhookTables {
    private fun endpointTable() = "`${prefix()}market_webhook_endpoint`"

    private fun deliveryTable() = "`${prefix()}market_webhook_delivery`"

    override suspend fun exists(): Boolean =
        client().preparedQuery("SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")
            .execute(Tuple.of("${prefix()}market_webhook_endpoint")).coAwait().iterator().hasNext()

    override suspend fun endpoints(): List<LegacyWebhookEndpoint> =
        client().query("SELECT * FROM ${endpointTable()} ORDER BY `id`").execute().coAwait().map { row ->
            LegacyWebhookEndpoint(
                id = row.getLong("id"), name = row.getString("name") ?: "", url = row.getString("url") ?: "", events = row.getString("events") ?: "[]",
                format = row.getString("format") ?: "JSON", signing = row.getString("signing") ?: "NONE", secret = row.getString("secret"),
                headers = row.getString("headers"), template = row.getString("template"), enabled = flag(row, "enabled"),
                maxAttempts = row.getInteger("maxAttempts") ?: 8
            )
        }

    override suspend fun remove(id: Long) {
        client().preparedQuery("DELETE FROM ${endpointTable()} WHERE `id` = ?").execute(Tuple.of(id)).coAwait()
    }

    override suspend fun drop() {
        client().query("DROP TABLE IF EXISTS ${deliveryTable()}, ${endpointTable()}").execute().coAwait()
    }

    private fun flag(row: Row, column: String): Boolean = when (val value = row.getValue(column)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }
}

/**
 * Runs [WebhookImport] for the plugin start and never throws: a failure is logged and the legacy tables stay, so the next start tries again
 * (rows that were imported are already gone from them).
 */
suspend fun runWebhookImport(import: WebhookImport) {
    try {
        import.run()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoggerFactory.getLogger(WebhookImport::class.java).error("The store's webhook endpoints could not be moved to the platform's webhooks, the old tables stay until the next start: {}", e.toString())
    }
}
