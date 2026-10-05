package com.panomc.plugins.market.support

import com.panomc.plugins.market.db.model.MarketWebhookDelivery
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.db.model.WebhookFormat
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.HostResolver
import com.panomc.plugins.market.service.OutboundHttp
import io.vertx.core.Vertx
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A resolver with a fixed table (17 section 4 S10: the DNS answer is injected). A name that is not in the table fails
 * like an unknown host. [lookups] counts the questions.
 */
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
    /** A deterministic cipher for tests; the key is never used for anything else. */
    fun cipher(): SecretCipher = SecretCipher(ByteArray(SecretCipher.KEY_BYTES) { (it * 7 + 1).toByte() })

    fun outbound(vertx: Vertx, resolver: HostResolver, totalTimeoutMs: Long = 5_000L, customize: (io.vertx.ext.web.client.WebClientOptions) -> Unit = {}): OutboundHttp =
        OutboundHttp.create(vertx, version = "test", resolver = resolver, totalTimeoutMs = totalTimeoutMs, customize = customize)

    /** A claimed-looking row (status SENDING, attempt 1) for the sender tests. */
    fun row(
        url: String,
        body: String = """{"id":"e1","event":"order.paid"}""",
        signing: WebhookSigning = WebhookSigning.NONE,
        secret: String? = null,
        format: WebhookFormat = WebhookFormat.JSON,
        attempts: Int = 1,
        id: Long = 11,
        eventId: String = "00000000-0000-4000-8000-000000000001",
        event: String = "order.paid"
    ) = MarketWebhookDelivery(
        id = id, endpointId = 1, eventId = eventId, event = event, url = url, format = format, signing = signing, secret = secret,
        body = body, status = WebhookDeliveryStatus.SENDING, attempts = attempts, maxAttempts = 8
    )
}

/**
 * The webhook object graph of a tier-T2 test: real DAOs and [MarketDb] from the [TestWiring], the wiring's [FakeClock] and
 * [SeqIds], a real [OutboundHttp] over a [StubResolver], and the seams of MK-105 (renderer, reporter) injectable.
 */
class WebhookHarness(
    val w: TestWiring,
    val vertx: Vertx,
    val resolver: StubResolver = StubResolver(),
    var allowPrivate: Boolean = true,
    totalTimeoutMs: Long = 5_000L,
    renderer: com.panomc.plugins.market.service.WebhookBodyRenderer = com.panomc.plugins.market.service.WebhookBodyRenderer.Unwired,
    reporter: com.panomc.plugins.market.service.WebhookDeliveryReporter? = null,
    disableAfter: Int = com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao.DEFAULT_DISABLE_AFTER,
    concurrency: Int = 5
) {
    val cipher = WebhookTestSupport.cipher()
    val store = com.panomc.plugins.market.core.webhook.StoreInfo("Test Craft", "https://shop.example.com")
    val sender = com.panomc.plugins.market.service.WebhookSender(
        WebhookTestSupport.outbound(vertx, resolver, totalTimeoutMs), cipher, w.clock, "test", { allowPrivate }
    )
    val service = com.panomc.plugins.market.service.WebhookService(
        w.db, w.clock, w.ids, w.webhookEndpoints, w.webhookDeliveries, w.orders, w.orderItems, sender, { store },
        renderer = renderer, reporter = reporter, disableAfter = disableAfter, random = kotlin.random.Random(5)
    )
    val job = com.panomc.plugins.market.job.WebhookJob(service, concurrency = concurrency)

    /** Inserts an enabled endpoint; [secret] and [headersJson] are plaintext and stored encrypted like the panel does. */
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
    ): com.panomc.plugins.market.db.model.MarketWebhookEndpoint {
        val now = w.clock.now()
        val id = w.webhookEndpoints.add(
            com.panomc.plugins.market.db.model.MarketWebhookEndpoint(
                name = name, url = url, events = events, format = format, signing = signing, secret = secret?.let { cipher.encrypt(it) },
                headers = headersJson?.let { cipher.encrypt(it) }, enabled = enabled, maxAttempts = maxAttempts, createdAt = now, updatedAt = now
            ),
            w.pool
        )
        return w.webhookEndpoints.getById(id, w.pool)!!
    }

    suspend fun emit(event: String = "order.paid", subject: String, orderId: Long? = null, data: io.vertx.core.json.JsonObject = io.vertx.core.json.JsonObject().put("k", subject)): Int =
        w.db.tx { conn -> service.emit(conn, event, subject, orderId, data) }

    suspend fun rows(): List<MarketWebhookDelivery> =
        MarketTestDb.sql(w.pool, "SELECT `id` FROM `pano_market_webhook_delivery` ORDER BY `id`").map { w.webhookDeliveries.getById(it.getLong("id"), w.pool)!! }

    suspend fun row(id: Long): MarketWebhookDelivery = w.webhookDeliveries.getById(id, w.pool)!!

    suspend fun endpointNow(id: Long) = w.webhookEndpoints.getById(id, w.pool)!!
}
