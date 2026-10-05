package com.panomc.plugins.market.routes.api.shipping

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.shipping.TrackingSource
import com.panomc.plugins.market.core.time.Backoff
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketShippingCarrierDao
import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.PaymentEventDirection
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.routes.api.payment.InboundCall
import com.panomc.plugins.market.routes.api.payment.InboundDispatcher
import com.panomc.plugins.market.routes.api.payment.InboundEventKey
import com.panomc.plugins.market.routes.api.payment.InboundEventStore
import com.panomc.plugins.market.routes.api.payment.InboundRouteSupport
import com.panomc.plugins.market.routes.api.payment.MarketInboundApi
import com.panomc.plugins.market.routes.api.payment.RawRewrite
import com.panomc.plugins.market.routes.api.payment.Settlement
import com.panomc.plugins.market.routes.api.payment.StoredRequestCodec
import com.panomc.plugins.market.routes.api.payment.inboundEventStore
import com.panomc.plugins.market.routes.panel.shipping.shippingService
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.ShippingService
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.shipping.ShippingInboundResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.random.Random

/**
 * What market does with an inbound shipping webhook (10 section 10.1, 03 section 6), the carrier twin of [InboundDispatcher]:
 * 1. the install token of the path is compared in constant time with `market_shipping_carrier.webhookToken`; an unknown provider id or a wrong token
 *    answers 404 with an empty body, **no** `market_payment_event` row and no provider code;
 * 2. the raw request is stored (`providerId = "shipping:<id>"`, `channel = WEBHOOK`, key `r:<uuid>`, headers and body verbatim, the install token masked
 *    in the url) before any provider code runs;
 * 3. `handleInbound` (25 s): an exception makes the row `FAILED` (retried) and the answer 503 (the carrier delivers again);
 * 4. `verified == false`: row `REJECTED`, nothing applied, the provider's reply is sent. An unsigned "trigger" webhook is verified by the provider
 *    re-fetching the shipment before it returns updates (03 section 9), never by market;
 * 5. a delivery key (`eventKey`) de-duplicates the request with the rules of 02 section 7.3 step 5; without one the updates are always applied (events are
 *    idempotent through `uq_shipment_event`, a request hash would add no safety, only loss);
 * 6. each update is resolved to one shipment of this provider (an unknown one is skipped and counted in the row's `error`) and goes through
 *    [ShippingService.applyUpdate] (source `WEBHOOK`): the order lock, the events, the state machine, the derived `shippingStatus`, mail and store webhook;
 * 7. `PROCESSED`, headers and body redacted in place, the provider's reply. A failure of market's own processing is `FAILED` and 503.
 *
 * `lastInboundAt` of the carrier is set only for verified requests. A registered provider receives every request whatever its carrier row says (enabled or not).
 */
class ShippingInboundDispatcher(
    private val store: InboundEventStore,
    private val carriers: MarketShippingCarrierDao,
    private val service: ShippingService,
    private val client: suspend () -> SqlClient,
    private val clock: Clock,
    private val ids: Ids,
    private val runtime: () -> MarketRuntime.State = { MarketRuntime.state },
    private val providerTimeoutMs: Long = InboundDispatcher.PROVIDER_TIMEOUT_MS,
    private val backoff: Backoff = InboundDispatcher.RETRY_BACKOFF,
    private val random: Random = Random.Default
) {
    private class Run(val id: Long, var eventKey: String, val createdAt: Long, val attempts: Int, val headers: String?, val body: String?, val requestHash: String?)

    // ============================================================================================ a request arrives

    suspend fun handle(call: InboundCall, installToken: String): HttpReply {
        val state = runtime()

        // 00 section 8.9: not started yet / stopped: nothing is touched, the carrier delivers again
        if (state == MarketRuntime.State.STOPPED || state == MarketRuntime.State.STARTING) return HttpReply.retryLater(503)

        val carrier = try {
            carriers.getByProviderId(call.providerId, client())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("the carrier row of {} could not be read: {}", call.providerId, t.javaClass.simpleName)

            return HttpReply.retryLater(503)
        }

        // 1. constant-time compare; nothing else runs and nothing is stored for a stranger
        if (carrier == null || !constantTimeEquals(carrier.webhookToken, installToken)) return notFound()

        val access = try {
            service.inboundAccess(call.providerId, client())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("shipping provider {} could not be resolved: {}", call.providerId, t.javaClass.simpleName)

            return HttpReply.retryLater(503)
        }

        val deferred = access == null || state != MarketRuntime.State.READY

        // 2. the raw request is on disk before any provider code runs
        val now = clock.now()
        val redactor = access?.redactor ?: com.panomc.plugins.market.core.abuse.Redactor()
        val headers = StoredRequestCodec.headersJson(call)
        val body = StoredRequestCodec.bodyText(call.body)
        val hash = InboundEventKey.requestHash(call.kind, call.channel, null, call.method, call.rawQuery, call.body)
        val key = InboundEventKey.received(ids.uuid())
        val path = call.path.replace(installToken, "{installToken}")
        val row = MarketPaymentEvent(
            providerId = PROVIDER_PREFIX + call.providerId, direction = PaymentEventDirection.IN, channel = InboundKind.WEBHOOK.name,
            subChannel = call.channel.takeIf { it != DEFAULT_CHANNEL }?.take(InboundDispatcher.SUB_CHANNEL_MAX), eventKey = key, requestHash = hash, method = call.method.take(8),
            url = redactor.redactUrl(path + (call.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")).take(InboundDispatcher.URL_MAX),
            headers = headers, body = body, remoteIp = call.remoteIp.take(InboundDispatcher.REMOTE_IP_MAX),
            status = if (deferred) PaymentEventStatus.DEFERRED else PaymentEventStatus.RECEIVED, attempts = if (deferred) 0 else 1, createdAt = now, updatedAt = now
        )

        val id = try {
            store.insert(row)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("an inbound shipping request of {} could not be stored: {}", call.providerId, t.javaClass.simpleName)

            return HttpReply.retryLater(503)
        }

        // a provider that is not there (or a degraded market) keeps the request for a replay
        if (access == null || deferred) return HttpReply.retryLater(503)

        return process(Run(id, key, now, 1, headers, body, hash), call, access)
    }

    // ===================================================================================== steps 3 to 7, shared with the retry

    private suspend fun process(run: Run, call: InboundCall, access: ShippingService.InboundAccess): HttpReply {
        val started = clock.now()

        // 3. the provider
        val result: ShippingInboundResult = try {
            withTimeout(providerTimeoutMs) { access.provider.handleInbound(access.context(), inboundRequest(call)) }
        } catch (e: TimeoutCancellationException) {
            return failed(run, call, access, "TIMEOUT: the provider did not answer in ${providerTimeoutMs / 1000} s", started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            return failed(run, call, access, "${e.code}: ${e.message}", started)
        } catch (t: Throwable) {
            return failed(run, call, access, "${t.javaClass.simpleName}: ${t.message.orEmpty()}", started)
        }

        // 4. not authentic: nothing is applied, the request is kept under its own key
        if (!result.verified) {
            settle(run, access, PaymentEventStatus.REJECTED, false, null, null, result.rejectReason, result.reply, started, InboundDispatcher.REJECTED_BODY_MAX)

            return sanitized(result.reply)
        }

        try {
            carriers.recordInbound(call.providerId, clock.now(), client())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("lastInboundAt of {} could not be written: {}", call.providerId, t.javaClass.simpleName)
        }

        // 5. the carrier's delivery key
        var inFlight = false
        val eventKey = result.eventKey

        if (eventKey != null) {
            val claim = try {
                claimKey(run, eventKey, access)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return failed(run, call, access, "${t.javaClass.simpleName}: ${t.message.orEmpty()}", started)
            }

            when (claim) {
                Claim.OWNED -> Unit

                Claim.PROCESSED -> {
                    settle(run, access, PaymentEventStatus.DUPLICATE, true, null, null, null, result.reply, started)

                    return sanitized(result.reply)
                }

                Claim.IN_FLIGHT -> inFlight = true
            }
        }

        // 6. the updates, each under its order lock; an unknown shipment is skipped, a failure of market's own processing ends the run
        var unknown = 0
        var orderId: Long? = null
        val types = LinkedHashSet<String>()

        try {
            for (update in result.updates) {
                val target = service.resolveTarget(call.providerId, update.target, client())

                if (target == null) {
                    unknown++

                    continue
                }

                service.applyUpdate(target.id, update, TrackingSource.WEBHOOK)

                if (orderId == null) orderId = target.orderId

                update.events.forEach { types += it.status.name }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val text = "${t.javaClass.simpleName}: ${t.message.orEmpty()}"

            if (inFlight) {
                // the first copy of this delivery still carries the fact; this row only records the failure
                settle(run, access, PaymentEventStatus.DUPLICATE, true, null, null, access.redactor.redact(text).take(InboundDispatcher.ERROR_MAX), null, started)

                return HttpReply.retryLater(503)
            }

            return failed(run, call, access, text, started)
        }

        // 7. settled: headers and body are redacted in place, the reply is the provider's
        val note = if (unknown > 0) "unknown shipment" + if (unknown > 1) " x$unknown" else "" else null

        settle(
            run, access, if (inFlight) PaymentEventStatus.DUPLICATE else PaymentEventStatus.PROCESSED, true, types.joinToString(",").takeIf { it.isNotEmpty() }, orderId, note,
            result.reply, started
        )

        return sanitized(result.reply)
    }

    private enum class Claim { OWNED, PROCESSED, IN_FLIGHT }

    /** Step 5: who holds the key of this delivery (`uq_event`), by the rules of 02 section 7.3 step 5. */
    private suspend fun claimKey(run: Run, providerKey: String, access: ShippingService.InboundAccess): Claim {
        val key = InboundEventKey.provider(providerKey)
        val providerId = PROVIDER_PREFIX + (access.provider.id)

        if (run.eventKey == key) return Claim.OWNED

        repeat(MAX_CLAIM_ROUNDS) {
            if (store.claimKey(run.id, key, clock.now())) {
                run.eventKey = key

                return Claim.OWNED
            }

            val holder = store.byKey(providerId, key) ?: return@repeat

            if (holder.id == run.id) return Claim.OWNED

            val now = clock.now()

            when {
                holder.status == PaymentEventStatus.PROCESSED -> {
                    store.bumpDuplicates(holder.id, now)

                    return Claim.PROCESSED
                }

                holder.status == PaymentEventStatus.RECEIVED && now - holder.createdAt < InboundDispatcher.STALE_RECEIVED_MS -> {
                    store.bumpDuplicates(holder.id, now)

                    return Claim.IN_FLIGHT
                }

                else -> {
                    // FAILED, DEFERRED, a crashed RECEIVED: this request takes the key over, so a redelivery after a failure is applied, never swallowed
                    val headers = StoredRequestCodec.settledHeaders(holder.headers, access.redactor)
                    val body = StoredRequestCodec.settledBody(holder.body, holder.headers, access.redactor, InboundDispatcher.SETTLED_BODY_MAX)

                    store.supersede(holder, headers, body, now)
                }
            }
        }

        throw IllegalStateException("the event key could not be claimed after $MAX_CLAIM_ROUNDS rounds")
    }

    /** A step 3 / 5 / 6 failure: the row is `FAILED` with its next retry, the carrier is told to deliver again. */
    private suspend fun failed(run: Run, call: InboundCall, access: ShippingService.InboundAccess, error: String, started: Long): HttpReply {
        val text = access.redactor.redact(error).take(InboundDispatcher.ERROR_MAX)
        val now = clock.now()
        val next = if (run.attempts >= InboundDispatcher.MAX_ATTEMPTS) null else now + backoff.delayMs(run.attempts, random)

        try {
            store.settle(
                run.id,
                Settlement(
                    PaymentEventStatus.FAILED, verified = null, eventTypes = null, paymentId = null, orderId = null, refundId = null, subscriptionId = null, responseStatus = 503,
                    error = text, durationMs = elapsed(started), processedAt = null, nextAttemptAt = next, now = now
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the row stays RECEIVED: the retry job finds it once it is a minute old
            logger.error("the failure of inbound shipping event {} could not be stored: {}", run.id, t.javaClass.simpleName)
        }

        logger.warn("inbound shipping event {} of {} failed (run {}): {}", run.id, call.providerId, run.attempts, text.take(160))

        return HttpReply.retryLater(503)
    }

    private suspend fun settle(
        run: Run, access: ShippingService.InboundAccess, status: PaymentEventStatus, verified: Boolean, eventTypes: String?, orderId: Long?, error: String?, reply: HttpReply?,
        started: Long, maxBody: Int = InboundDispatcher.SETTLED_BODY_MAX
    ) {
        val now = clock.now()

        try {
            store.settle(
                run.id,
                Settlement(
                    status, verified = verified, eventTypes = eventTypes, paymentId = null, orderId = orderId, refundId = null, subscriptionId = null, responseStatus = reply?.status,
                    error = error?.take(InboundDispatcher.ERROR_MAX), durationMs = elapsed(started), processedAt = now, nextAttemptAt = null, now = now,
                    raw = RawRewrite(StoredRequestCodec.settledHeaders(run.headers, access.redactor), StoredRequestCodec.settledBody(run.body, run.headers, access.redactor, maxBody))
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the updates are applied (idempotent) and the reply is still right; a row left RECEIVED is re-run by the retry job and ends the same way
            logger.error("the outcome of inbound shipping event {} could not be stored: {}", run.id, t.javaClass.simpleName)
        }
    }

    private fun elapsed(started: Long): Int = (clock.now() - started).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    // ================================================================================================ retry

    /** Claims one run of [row] (a stored `shipping:` request) and runs steps 3 to 7 again on it; `false` when somebody else claimed it first. */
    suspend fun retry(row: MarketPaymentEvent): Boolean {
        if (!row.providerId.startsWith(PROVIDER_PREFIX) || row.direction != PaymentEventDirection.IN) return false

        if (!store.claimRetry(row, clock.now(), InboundDispatcher.LEASE_MS)) return false

        rerun(store.get(row.id) ?: return false)

        return true
    }

    private suspend fun rerun(row: MarketPaymentEvent) {
        val providerId = row.providerId.removePrefix(PROVIDER_PREFIX)
        val access = service.inboundAccess(providerId, client())

        if (access == null) {
            // the provider is not there (any more): the request waits for it, a human replays it
            store.settle(row.id, Settlement(PaymentEventStatus.DEFERRED, null, null, null, null, null, null, 503, "the provider is not available", null, null, null, clock.now()))

            return
        }

        val stored = StoredRequestCodec.parse(row.headers, row.body)
        val call = InboundCall(
            InboundKind.WEBHOOK, providerId, row.subChannel ?: DEFAULT_CHANNEL, null, null, null, row.method ?: "POST", row.url.orEmpty(), stored.rawQuery, queryOf(stored.rawQuery),
            stored.headers, stored.headers["content-type"]?.firstOrNull(), stored.body, stored.form, row.remoteIp.orEmpty(), row.createdAt
        )

        process(Run(row.id, row.eventKey, row.createdAt, row.attempts, row.headers, row.body, row.requestHash), call, access)
    }

    // ===================================================================================================== the answers

    private fun inboundRequest(call: InboundCall): InboundRequest =
        InboundRequest(call.kind, call.channel, call.method, call.rawQuery, call.query, call.headers, call.contentType, call.body, call.remoteIp, call.receivedAt).also {
            it.formAttributes = call.formAttributes
        }

    private fun notFound(): HttpReply = HttpReply(404, null, ByteArray(0))

    /** The provider's reply, minus the headers that belong to the connection or would set state on the site's origin. */
    private fun sanitized(reply: HttpReply): HttpReply {
        val kept = reply.headers.filterKeys { it.lowercase() !in DROPPED_HEADERS }

        if (kept.size == reply.headers.size) return reply

        return HttpReply(reply.status, reply.contentType, reply.body).also { it.headers = kept }
    }

    private fun queryOf(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()

        val out = LinkedHashMap<String, MutableList<String>>()

        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue

            val eq = pair.indexOf('=')
            val name = runCatching { java.net.URLDecoder.decode(if (eq < 0) pair else pair.substring(0, eq), "UTF-8") }.getOrDefault(pair)
            val value = if (eq < 0) "" else runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault(pair.substring(eq + 1))

            out.getOrPut(name) { ArrayList() }.add(value)
        }

        return out
    }

    companion object {
        /** `market_payment_event.providerId` of a shipping request is `shipping:<id>` (03 section 6: one raw log for all provider traffic). */
        const val PROVIDER_PREFIX = "shipping:"

        private const val DEFAULT_CHANNEL = "default"
        private const val MAX_CLAIM_ROUNDS = 8
        private val DROPPED_HEADERS = setOf("set-cookie", "set-cookie2", "content-length", "transfer-encoding", "connection", "keep-alive", "host", "upgrade")
        private val logger = LoggerFactory.getLogger(ShippingInboundDispatcher::class.java)

        /** Constant-time comparison of two tokens (10 section 10.1): the time does not depend on where they differ. */
        fun constantTimeEquals(expected: String, given: String): Boolean =
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }
}

private object ShippingInboundHolder

@Volatile
private var cachedShippingDispatcher: Pair<MarketPlugin, ShippingInboundDispatcher>? = null

/** The pipeline of 10 section 10.1 on the plugin's beans; one per plugin instance. */
internal fun shippingInboundDispatcher(plugin: MarketPlugin): ShippingInboundDispatcher {
    cachedShippingDispatcher?.takeIf { it.first === plugin }?.let { return it.second }

    val context = plugin.beans
    val databaseManager = { context.getBean(com.panomc.platform.db.DatabaseManager::class.java) }
    val built = ShippingInboundDispatcher(
        inboundEventStore(plugin), context.getBean(MarketShippingCarrierDao::class.java), shippingService(plugin), { databaseManager().getSqlClient() }, SystemClock, SecureIds()
    )

    return synchronized(ShippingInboundHolder) { cachedShippingDispatcher?.takeIf { it.first === plugin }?.second ?: built.also { cachedShippingDispatcher = plugin to it } }
}

/**
 * `/api/market/shipping/:providerId/webhook/:installToken[/:channel]` (all methods): a carrier's status push (10 section 10.1, 03 section 6). Same route
 * properties as the payment inbound routes (02 section 7.1): no login, no CSRF, no validation schema, 1 MB, maintenance `ALWAYS`, allowed in demo,
 * the provider's literal reply. The install token (160 bits) is the carrier row's `webhookToken`; a wrong one is a 404 before any provider code runs.
 */
@Endpoint
class ShippingWebhookAPI(private val plugin: MarketPlugin) : MarketInboundApi() {
    override val paths = listOf(
        Path(WEBHOOK_PATH, RouteType.ROUTE),
        Path("$WEBHOOK_PATH/:channel", RouteType.ROUTE)
    )

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val token = context.pathParam("installToken")?.takeIf { InboundRouteSupport.TOKEN.matches(it) } ?: return InboundRouteSupport.notFound(context).let { null }
        val call = InboundRouteSupport.callOf(context, InboundKind.WEBHOOK) ?: return InboundRouteSupport.notFound(context).let { null }

        InboundRouteSupport.send(context, shippingInboundDispatcher(plugin).handle(call, token))

        return null
    }

    companion object {
        const val WEBHOOK_PATH = "/api/market/shipping/:providerId/webhook/:installToken"
    }
}
