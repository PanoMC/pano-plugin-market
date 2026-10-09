package com.panomc.plugins.market.support

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.webhook.WebhookDiscordRenderer
import com.panomc.platform.api.webhook.WebhookEventType
import com.panomc.platform.api.webhook.WebhookOutcomeListener
import com.panomc.platform.db.model.WebhookDelivery
import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.webhook.HostResolver
import com.panomc.platform.webhook.OutboundHttp
import com.panomc.platform.webhook.SiteInfo
import com.panomc.platform.webhook.WebhookDispatcher
import com.panomc.platform.webhook.WebhookPublisherImpl
import com.panomc.platform.webhook.WebhookRegistry
import com.panomc.platform.webhook.WebhookSender
import com.panomc.plugins.market.core.webhook.StoreInfo
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.WebhookService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import com.panomc.platform.util.SecretCipher as CoreCipher
import com.panomc.platform.webhook.WebhookService as CoreWebhookService

/** A resolver with a fixed table (the DNS answer is injected). A name that is not in the table fails like an unknown host. [lookups] counts the questions. */
class StubResolver(vararg entries: Pair<String, List<String>>) : HostResolver {
    private val table = ConcurrentHashMap<String, List<InetAddress>>()
    val lookups = AtomicInteger()

    init {
        for ((host, addresses) in entries) set(host, *addresses.toTypedArray())
    }

    fun set(host: String, vararg addresses: String) {
        table[host] = addresses.map { InetAddress.getByName(it) }
    }

    override suspend fun resolve(host: String): List<InetAddress> {
        lookups.incrementAndGet()
        return table[host] ?: throw java.net.UnknownHostException(host)
    }
}

object WebhookTestSupport {
    /** A deterministic cipher of the market for tests (provider secrets, the old webhook columns); the key is never used for anything else. */
    fun cipher(): SecretCipher = SecretCipher(ByteArray(SecretCipher.KEY_BYTES) { (it * 7 + 1).toByte() })

    /** A deterministic cipher of core's webhook system (`webhook.key`); a different key than [cipher] on purpose. */
    fun coreCipher(): CoreCipher = CoreCipher(ByteArray(CoreCipher.KEY_BYTES) { (it * 11 + 3).toByte() })

    /** The market's namespace as core derives it for `pano-plugin-market`. */
    const val SOURCE = "market"

    /** The full event name core stores for a market event (`order.paid` becomes `market.order.paid`). */
    fun full(event: String) = "$SOURCE.$event"

    /**
     * An endpoint's `events` list written the way the market names its events (`["order.paid"]`) as core stores it (`["market.order.paid"]`): `"*"` and
     * names that already carry a source stay.
     */
    fun subscribe(eventsJson: String): String =
        io.vertx.core.json.JsonArray(eventsJson).list.map { name ->
            val text = name as String

            if (text == "*" || text.startsWith("$SOURCE.") || text.startsWith("core.")) text else full(text)
        }.let { io.vertx.core.json.JsonArray(it).encode() }
}

/**
 * The webhook object graph of a tier-T2 test: **core's real webhook system** (publisher, delivery engine, registry, DAOs) on the test database and the
 * test's [FakeClock], plus the market's [WebhookService] on top of it. Rows land in `pano_webhook_delivery` exactly as in production, in the caller's
 * transaction; [tick] sends what is due to a real local receiver through a [StubResolver].
 */
class WebhookHarness(
    val w: TestWiring,
    val vertx: Vertx,
    val resolver: StubResolver = StubResolver(),
    var allowPrivate: Boolean = true,
    totalTimeoutMs: Long = 5_000L,
    discord: WebhookDiscordRenderer? = null,
    outcomes: WebhookOutcomeListener? = null,
    disableAfter: Int = com.panomc.platform.db.dao.WebhookEndpointDao.DEFAULT_DISABLE_AFTER,
    concurrency: Int = 5,
    uuidOf: suspend (Long?, String) -> String? = { _, _ -> null }
) {
    val cipher = WebhookTestSupport.coreCipher()
    val store = StoreInfo("Test Craft", "https://shop.example.com")
    val plugin: PanoPlugin = object : PanoPlugin() {}
    val registry = WebhookRegistry(null)
    val sender = WebhookSender(
        OutboundHttp.create(vertx, "test", resolver, totalTimeoutMs), { cipher }, { w.clock.now() }, "test", { allowPrivate }
    )

    /** Core's queue: claim, send, retry, redeliver. */
    val core = CoreWebhookService(
        pool = { w.pool }, endpoints = w.webhookEndpoints, deliveries = w.webhookDeliveries, registry = registry, cipher = { cipher }, sender = sender,
        site = { SiteInfo("Test Craft", "https://shop.example.com") }, activeSources = { setOf(WebhookTestSupport.SOURCE) },
        now = { w.clock.now() }, random = Random(5), disableAfter = disableAfter
    )

    /** A timer far in the future: the tests tick by hand. */
    val dispatcher = WebhookDispatcher(vertx, core, concurrency = concurrency, intervalMs = 3_600_000L, now = { w.clock.now() })

    /** What a market service gets: core's publisher bound to the namespace `market`. */
    val publisher = WebhookPublisherImpl(core, registry, dispatcher, namespaceOf = { WebhookTestSupport.SOURCE })

    /** The market's emitter on top of [publisher]. */
    val service = WebhookService({ publisher }, plugin, w.orders, w.orderItems, { store }, uuidOf)

    init {
        // the store's events as the plugin declares them at start
        publisher.register(
            plugin, WebhookEvents.SUBSCRIBABLE.map { WebhookEventType(it) } + WebhookEvents.ACTION_EVENTS.map { WebhookEventType(it, subscribable = false) },
            discord, outcomes
        )
    }

    /** Core's direct queue for the `WEBHOOK` action executor, on the executor's connection. */
    val actionQueue = com.panomc.plugins.market.service.ActionWebhookQueue { conn, direct ->
        publisher.enqueueDirect(plugin, direct, conn) ?: w.webhookDeliveries.getByEventId(direct.eventId, conn)!!.id
    }

    /** Inserts an enabled endpoint; [secret] and [headersJson] are plaintext and stored encrypted with core's key like the panel does. */
    suspend fun endpoint(
        url: String,
        signing: WebhookSigning = WebhookSigning.NONE,
        secret: String? = null,
        events: String = "[\"*\"]",
        maxAttempts: Int = 8,
        format: WebhookFormat = WebhookFormat.JSON,
        headersJson: String? = null,
        enabled: Boolean = true,
        name: String = "Endpoint ${w.ids.uuid()}"
    ): WebhookEndpoint = w.fixtures.webhookEndpoint(
        url, signing, secret?.let { cipher.encrypt(it) }, events, maxAttempts, format, headersJson?.let { cipher.encrypt(it) }, enabled, name
    )

    suspend fun emit(event: String = "order.paid", subject: String, orderId: Long? = null, data: JsonObject = JsonObject().put("k", subject)): Int =
        w.db.tx { conn -> service.emit(conn, event, subject, orderId, data) }

    /** Claims what is due and sends it, like the dispatcher's timer. */
    suspend fun tick(): Int = dispatcher.tick()

    /** Every row of core's delivery log, oldest first. */
    suspend fun rows(): List<WebhookDelivery> =
        MarketTestDb.sql(w.pool, "SELECT `id` FROM `pano_webhook_delivery` ORDER BY `id`").map { w.webhookDeliveries.getById(it.getLong("id"), w.pool)!! }

    suspend fun row(id: Long): WebhookDelivery = w.webhookDeliveries.getById(id, w.pool)!!

    suspend fun endpointNow(id: Long): WebhookEndpoint = w.webhookEndpoints.getById(id, w.pool)!!
}
