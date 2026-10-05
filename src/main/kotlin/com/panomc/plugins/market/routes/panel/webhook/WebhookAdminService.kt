package com.panomc.plugins.market.routes.panel.webhook

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.webhook.DiscordRenderer
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.core.webhook.WebhookHeaders
import com.panomc.plugins.market.db.dao.MarketWebhookDeliveryDao
import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
import com.panomc.plugins.market.db.model.MarketWebhookDelivery
import com.panomc.plugins.market.db.model.MarketWebhookEndpoint
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.db.model.WebhookFormat
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.error.InvalidWebhookUrl
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.OutboundHttp
import com.panomc.plugins.market.service.RedeliverResult
import com.panomc.plugins.market.service.WebhookService
import com.panomc.plugins.market.service.WebhookTestResult
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.security.SecureRandom
import java.util.Base64

/** The answer of a create: the new id and, when the service generated an HMAC secret, that secret (shown this once). */
class WebhookSaved(val id: Long, val secret: String?)

/** One page of a delivery log. */
class WebhookDeliveryPage(val deliveries: List<MarketWebhookDelivery>, val count: Long, val window: Paging.Window) {
    val totalPage: Long get() = Paging.totalPages(count, window.pageSize)
}

/**
 * The panel side of store webhooks (04 section 8 `webhooks*`, `webhook-deliveries*`; 08 section 15.2): endpoint CRUD with the field rules, the one-time
 * secret reveal, encrypted header values, the URL policy on save, the test ping, the delivery log and redelivery. All routes are `P:SET`.
 *
 * - **Secrets** are stored encrypted (`ENC`) and read back as [MASK]; a generated `whsec_...` secret leaves the service exactly once, in the answer of the call
 *   that created it. `format = DISCORD` never signs: `HMAC_SHA256` is normalised to `NONE` and no secret is kept (08 section 15.2).
 * - **Headers** are validated by [WebhookHeaders], stored as one encrypted JSON object, and read back with every value masked. On update a value equal to
 *   [MASK] keeps the stored value of that name.
 * - **URL**: [OutboundHttp.check] (syntax, scheme, user-info, port, resolution, address classes, `allowPrivateWebhookTargets`) -> 400 `INVALID_WEBHOOK_URL`
 *   with the reason.
 * - Disabling or deleting an endpoint ends its open rows as `DEAD` (`ENDPOINT_DISABLED` / `ENDPOINT_DELETED`); enabling resets `failureCount` and
 *   `disabledReason`.
 */
class WebhookAdminService(
    private val db: MarketDb,
    private val clock: Clock,
    private val endpoints: MarketWebhookEndpointDao,
    private val deliveries: MarketWebhookDeliveryDao,
    private val webhooks: WebhookService,
    private val cipher: SecretCipher,
    private val outbound: OutboundHttp,
    /** The effective `allowPrivateWebhookTargets` (false when hosted). */
    private val allowPrivate: () -> Boolean,
    private val prefix: () -> String,
    /** L8: 10 a minute per panel user; throws `TooManyRequests`. */
    private val rateLimit: (userId: Long) -> Unit = {},
    private val random: SecureRandom = SecureRandom()
) {
    private fun deliveryTable() = "`${prefix()}market_webhook_delivery`"

    private fun endpointTable() = "`${prefix()}market_webhook_endpoint`"

    // ----- read -------------------------------------------------------------------------------------------------------------

    /** `GET /webhooks`: the endpoints (secret and header values masked), the built-in Discord template and the subscribable event names. */
    suspend fun list(): JsonObject {
        val rows = db.tx { conn -> endpoints.getAll(conn) }

        return JsonObject()
            .put("webhooks", JsonArray(rows.map { view(it) }))
            .put("defaults", JsonObject().put("discordTemplate", DiscordRenderer.BUILT_IN_TEMPLATE))
            .put("eventNames", JsonArray(WebhookEvents.SUBSCRIBABLE))
    }

    /** One endpoint as the panel sees it: `secret` is [MASK] or `null`, header values are [MASK]. */
    fun view(e: MarketWebhookEndpoint): JsonObject {
        val headerNames = storedHeaders(e).keys

        return JsonObject()
            .put("id", e.id).put("name", e.name).put("url", e.url)
            .put("events", runCatching { JsonArray(e.events) }.getOrDefault(JsonArray()))
            .put("format", e.format.name).put("signing", e.signing.name)
            .put("secret", if (e.secret.isNullOrEmpty()) null else MASK)
            .put("headers", JsonObject().also { out -> headerNames.forEach { out.put(it, MASK) } })
            .put("template", e.template).put("enabled", e.enabled).put("maxAttempts", e.maxAttempts)
            .put("failureCount", e.failureCount).put("lastStatusCode", e.lastStatusCode).put("lastDeliveryAt", e.lastDeliveryAt)
            .put("disabledReason", e.disabledReason).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt)
    }

    private fun storedHeaders(e: MarketWebhookEndpoint): Map<String, String> {
        val encrypted = e.headers?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val json = cipher.decrypt(encrypted) ?: return emptyMap()

        return runCatching { JsonObject(json).map.mapValues { it.value?.toString().orEmpty() } }.getOrDefault(emptyMap())
    }

    // ----- create / update / delete -------------------------------------------------------------------------------------------

    suspend fun create(body: JsonObject): WebhookSaved {
        val parsed = parse(body, null)

        checkUrl(parsed.url, parsed.format)

        val now = clock.now()

        return db.tx { conn ->
            if (endpoints.getAll(conn).size >= MAX_ENDPOINTS) throw InvalidSettings(mapOf("name" to "LIMIT_REACHED"))

            val id = endpoints.add(
                MarketWebhookEndpoint(
                    name = parsed.name, url = parsed.url, events = parsed.events.encode(), format = parsed.format, signing = parsed.signing,
                    secret = parsed.secret?.let { cipher.encrypt(it) }, headers = parsed.headers?.let { cipher.encrypt(it.encode()) }, template = parsed.template,
                    enabled = parsed.enabled, maxAttempts = parsed.maxAttempts, createdAt = now, updatedAt = now
                ),
                conn
            )

            WebhookSaved(id, parsed.generatedSecret)
        }
    }

    suspend fun update(id: Long, body: JsonObject): WebhookSaved {
        val existing = db.tx { conn -> endpoints.getById(id, conn) } ?: throw NotFound()
        val parsed = parse(body, existing)

        if (parsed.url != existing.url || parsed.format != existing.format) checkUrl(parsed.url, parsed.format)

        val now = clock.now()

        return db.tx { conn ->
            val fresh = endpoints.getById(id, conn) ?: throw NotFound()
            val enabling = parsed.enabled && !fresh.enabled
            val disabling = !parsed.enabled && fresh.enabled

            val ok = endpoints.update(
                MarketWebhookEndpoint(
                    id = id, name = parsed.name, url = parsed.url, events = parsed.events.encode(), format = parsed.format, signing = parsed.signing,
                    secret = parsed.secret?.let { cipher.encrypt(it) }, headers = parsed.headers?.let { cipher.encrypt(it.encode()) }, template = parsed.template,
                    enabled = parsed.enabled, maxAttempts = parsed.maxAttempts,
                    disabledReason = when {
                        parsed.enabled -> null
                        disabling -> MANUAL_DISABLED_REASON
                        else -> fresh.disabledReason
                    }
                ),
                now, conn
            )

            if (!ok) throw NotFound()

            if (enabling) resetFailures(conn, id)

            if (disabling) webhooks.deadenOpenRows(conn, id, "ENDPOINT_DISABLED")

            WebhookSaved(id, parsed.generatedSecret)
        }
    }

    /** A hard delete; the rows still open become `DEAD (ENDPOINT_DELETED)` first (08 section 15.2). */
    suspend fun delete(id: Long) {
        db.tx { conn ->
            endpoints.getById(id, conn) ?: throw NotFound()
            webhooks.deadenOpenRows(conn, id, "ENDPOINT_DELETED")
            endpoints.delete(id, conn)
        }
    }

    private suspend fun resetFailures(conn: SqlConnection, id: Long) {
        conn.preparedQuery("UPDATE ${endpointTable()} SET `failureCount` = 0, `disabledReason` = NULL, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(clock.now(), id)).coAwait()
    }

    private suspend fun checkUrl(url: String, format: WebhookFormat) {
        val checked = outbound.check(url, allowPrivate(), discord = format == WebhookFormat.DISCORD)

        checked.refusal?.let { throw InvalidWebhookUrl(it.name) }
    }

    // ----- field rules (08 section 15.2) ---------------------------------------------------------------------------------------

    private class Parsed(
        val name: String, val url: String, val events: JsonArray, val format: WebhookFormat, val signing: WebhookSigning,
        val secret: String?, val generatedSecret: String?, val headers: JsonObject?, val template: String?, val enabled: Boolean, val maxAttempts: Int
    )

    private fun parse(body: JsonObject, existing: MarketWebhookEndpoint?): Parsed {
        val errors = LinkedHashMap<String, String>()

        fun has(key: String) = body.containsKey(key) && body.getValue(key) != null

        val name = (if (has("name")) body.getValue("name") else existing?.name)?.let { it as? String }?.trim().orEmpty()

        if (name.isEmpty()) errors["name"] = "REQUIRED" else if (name.length > MAX_NAME) errors["name"] = "TOO_LONG"

        val url = ((if (has("url")) body.getValue("url") else existing?.url) as? String)?.trim().orEmpty()

        if (url.isEmpty() || url.length > MAX_URL) throw InvalidWebhookUrl("MALFORMED")

        val events = parseEvents(if (has("events")) body.getValue("events") else existing?.events?.let { runCatching { JsonArray(it) }.getOrNull() }, errors)

        val format = parseEnum(WebhookFormat.entries, if (has("format")) body.getValue("format") else existing?.format?.name ?: "JSON", "format", errors, WebhookFormat.JSON)
        var signing = parseEnum(WebhookSigning.entries, if (has("signing")) body.getValue("signing") else existing?.signing?.name ?: "NONE", "signing", errors, WebhookSigning.NONE)

        // DISCORD never signs (08 section 15.2): HMAC is normalised to NONE
        if (format == WebhookFormat.DISCORD) signing = WebhookSigning.NONE

        var secret: String? = null
        var generated: String? = null

        if (signing == WebhookSigning.HMAC_SHA256) {
            val sent = (body.getValue("secret") as? String)?.takeIf { it.isNotBlank() && it != MASK }

            when {
                sent != null -> if (SECRET.matches(sent)) secret = sent else errors["secret"] = "INVALID"
                existing != null && !existing.secret.isNullOrEmpty() && existing.signing == WebhookSigning.HMAC_SHA256 ->
                    secret = cipher.decrypt(existing.secret!!) ?: run { generated = newSecret(); generated }

                else -> {
                    generated = newSecret()
                    secret = generated
                }
            }
        }

        val headers = parseHeaders(body, existing, errors)

        val rawTemplate = if (body.containsKey("template")) body.getValue("template") else existing?.template
        val template = (rawTemplate as? String)?.takeIf { it.isNotBlank() }

        if (rawTemplate != null && rawTemplate !is String) {
            errors["template"] = "INVALID_JSON"
        } else if (template != null) {
            if (format != WebhookFormat.DISCORD) errors["template"] = "NOT_ALLOWED" else DiscordRenderer.validate(template)?.let { errors["template"] = it }
        }

        val maxAttempts = when (val raw = if (has("maxAttempts")) body.getValue("maxAttempts") else existing?.maxAttempts ?: DEFAULT_ATTEMPTS) {
            is Number -> raw.toInt().takeIf { raw.toDouble() == raw.toInt().toDouble() && it in 1..MAX_ATTEMPTS } ?: run { errors["maxAttempts"] = "OUT_OF_RANGE"; DEFAULT_ATTEMPTS }
            else -> { errors["maxAttempts"] = "INVALID"; DEFAULT_ATTEMPTS }
        }

        val enabled = when (val raw = if (has("enabled")) body.getValue("enabled") else existing?.enabled ?: true) {
            is Boolean -> raw
            else -> { errors["enabled"] = "INVALID"; true }
        }

        if (errors.isNotEmpty()) throw InvalidSettings(errors)

        return Parsed(name, url, events, format, signing, secret, generated, headers, template, enabled, maxAttempts)
    }

    private fun parseEvents(raw: Any?, errors: MutableMap<String, String>): JsonArray {
        val array = raw as? JsonArray

        if (array == null || array.isEmpty) {
            errors["events"] = "REQUIRED"

            return JsonArray()
        }

        val names = LinkedHashSet<String>()

        for (value in array) {
            val name = value as? String

            if (name == null || (name != WebhookEvents.WILDCARD && !WebhookEvents.isSubscribable(name))) {
                errors["events"] = "INVALID"

                return JsonArray()
            }

            names += name
        }

        return JsonArray(names.toList())
    }

    private fun <E : Enum<E>> parseEnum(values: List<E>, raw: Any?, field: String, errors: MutableMap<String, String>, default: E): E {
        val name = raw as? String

        return values.firstOrNull { it.name == name } ?: run {
            errors[field] = "INVALID"

            default
        }
    }

    /** `null` = no headers at all; otherwise the plain `{name: value}` object to store encrypted. */
    private fun parseHeaders(body: JsonObject, existing: MarketWebhookEndpoint?, errors: MutableMap<String, String>): JsonObject? {
        val kept = existing?.let { storedHeaders(it) }.orEmpty()

        if (!body.containsKey("headers")) return kept.takeIf { it.isNotEmpty() }?.let { JsonObject(it.toMutableMap<String, Any?>()) }

        val raw = body.getValue("headers") ?: return null
        val obj = raw as? JsonObject ?: run {
            errors["headers"] = "INVALID"

            return null
        }
        val plain = LinkedHashMap<String, String>()

        for (key in obj.fieldNames()) {
            val value = obj.getValue(key) as? String

            if (value == null) {
                errors["headers.$key"] = "INVALID_VALUE"
            } else if (value == MASK) {
                // the masked read-back keeps the stored value of that header
                val old = kept[key]

                if (old == null) errors["headers.$key"] = "INVALID_VALUE" else plain[key] = old
            } else {
                plain[key] = value
            }
        }

        errors += WebhookHeaders.validate(plain)

        return if (plain.isEmpty()) null else JsonObject(plain.toMutableMap<String, Any?>())
    }

    private fun newSecret(): String {
        val raw = ByteArray(32)

        random.nextBytes(raw)

        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    // ----- test, log, redeliver --------------------------------------------------------------------------------------------------

    /** `POST /webhooks/:id/test`: L8 per user, then the synchronous `test.ping`. 404 for an unknown endpoint. */
    suspend fun test(id: Long, userId: Long): WebhookTestResult {
        rateLimit(userId)

        return webhooks.sendTestPing(id) ?: throw NotFound()
    }

    /** `GET /webhooks/:id/deliveries` ([endpointId] set) and `GET /webhook-deliveries`: newest first, filtered by [status]. */
    suspend fun deliveries(endpointId: Long?, status: WebhookDeliveryStatus?, window: Paging.Window): WebhookDeliveryPage = db.tx { conn ->
        if (endpointId != null) endpoints.getById(endpointId, conn) ?: throw NotFound()

        val where = ArrayList<String>()
        val args = ArrayList<Any?>()

        if (endpointId != null) {
            where += "`endpointId` = ?"
            args += endpointId
        }

        if (status != null) {
            where += "`status` = ?"
            args += status.name
        }

        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val count = conn.preparedQuery("SELECT COUNT(*) AS n FROM ${deliveryTable()}$clause").execute(Tuple.from(args)).coAwait().first().getLong("n")

        if (Paging.isBeyondLast(window.page, Paging.totalPages(count, window.pageSize))) throw com.panomc.platform.error.PageNotFound()

        val ids = conn.preparedQuery("SELECT `id` FROM ${deliveryTable()}$clause ORDER BY `id` DESC LIMIT ? OFFSET ?")
            .execute(Tuple.from(args + window.pageSize + window.offset)).coAwait().map { it.getLong("id") }

        WebhookDeliveryPage(ids.mapNotNull { deliveries.getById(it, conn) }, count, window)
    }

    /** The list shape of 04 section 8 (no body, no response). */
    fun listView(d: MarketWebhookDelivery): JsonObject = JsonObject()
        .put("id", d.id).put("endpointId", d.endpointId).put("deliveryId", d.deliveryId).put("event", d.event).put("orderId", d.orderId)
        .put("status", d.status.name).put("attempts", d.attempts).put("maxAttempts", d.maxAttempts).put("lastStatusCode", d.lastStatusCode)
        .put("lastError", d.lastError).put("durationMs", d.durationMs).put("createdAt", d.createdAt).put("deliveredAt", d.deliveredAt)

    /** `GET /webhook-deliveries/:id`: the list shape plus the stored `body`, `lastResponse`, `eventId` and `url`. The signing secret is never in it. */
    suspend fun delivery(id: Long): JsonObject {
        val d = db.tx { conn -> deliveries.getById(id, conn) } ?: throw NotFound()

        return listView(d).put("eventId", d.eventId).put("url", d.url).put("format", d.format.name).put("signing", d.signing.name)
            .put("body", d.body).put("lastResponse", d.lastResponse).put("nextAttemptAt", d.nextAttemptAt)
    }

    /** `POST /webhook-deliveries/:id/redeliver`: the same row back to `PENDING` (same event id); 404 unknown, 400 `BAD_REQUEST` while `SENDING`. */
    suspend fun redeliver(id: Long) {
        when (webhooks.redeliver(id)) {
            RedeliverResult.OK -> Unit
            RedeliverResult.NOT_FOUND -> throw NotFound()
            RedeliverResult.IN_FLIGHT -> throw com.panomc.platform.error.BadRequest()
        }
    }

    /** Validates the query values of the log routes. */
    fun statusOf(raw: String?): WebhookDeliveryStatus? =
        if (raw.isNullOrBlank()) null else WebhookDeliveryStatus.entries.firstOrNull { it.name == raw } ?: throw RequestValueException("status", "UNKNOWN_VALUE")

    companion object {
        /** What a stored secret and every header value read as. */
        const val MASK = "********"

        const val MAX_ENDPOINTS = 20
        const val MAX_NAME = 128
        const val MAX_URL = 1024
        const val MAX_ATTEMPTS = 20
        const val DEFAULT_ATTEMPTS = 8
        const val MANUAL_DISABLED_REASON = "MANUAL"

        /** 16..128 printable ASCII (08 section 15.2). */
        private val SECRET = Regex("^[\\u0020-\\u007e]{16,128}$")
    }
}
