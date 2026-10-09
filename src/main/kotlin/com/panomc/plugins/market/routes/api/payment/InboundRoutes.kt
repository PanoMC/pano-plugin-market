package com.panomc.plugins.market.routes.api.payment

import com.panomc.platform.error.NotFound
import com.panomc.platform.schema.EndpointDoc
import com.panomc.plugins.market.util.MarketLinks
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.BrowserAccess
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.service.ClientIpResolver
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Base of the inbound gateway routes (02 section 7.1): all methods, no login, no CSRF, no validation schema, 1 MB body limit (an over-limit body is
 * answered `413 PAYLOAD_TOO_LARGE` by the platform, nothing stored), maintenance and demo mode never block a gateway, and the store switch does not
 * either (a payment that was started must still be recorded). The runtime gate of 00 section 8.9 is the dispatcher's: not `READY` answers 503 without
 * touching the database for a webhook / notification and 303 for a return, `DEGRADED` keeps the request as `DEFERRED`.
 */
abstract class MarketInboundApi : MarketApi() {
    override val requiresStoreEnabled: Boolean = false

    override val maintenanceAccess: MaintenanceAccess = MaintenanceAccess.ALWAYS

    /** A gateway's server call and the buyer coming back from a 3-D Secure page carry no CSRF token; the signature or the attempt token is the proof. */
    override val csrfExempt: Boolean = true

    override fun isAllowedInDemo(method: HttpMethod): Boolean = true

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    /** Raw bytes for JSON and form bodies, multipart attributes without any upload to disk, 1 MB (02 section 7.1). */
    override fun bodyHandler(): Handler<RoutingContext>? = BodyHandler.create(false).setBodyLimit(BODY_LIMIT_BYTES)

    /** A gateway and the buyer coming back from it post from another origin (doc 05 section 4): the Origin gate lets these routes through. */
    override val browserAccess: BrowserAccess = BrowserAccess.ANY_ORIGIN

    override suspend fun marketChecks(context: RoutingContext) = Unit

    companion object {
        const val BODY_LIMIT_BYTES = 1_048_576L
    }
}

/** What the inbound routes read from a Vert.x request (the pure part of a route, shared by all of them). */
internal object InboundRouteSupport {
    val PROVIDER_ID = Regex("^[a-z0-9-]{2,32}$")
    val CHANNEL = Regex("^[a-z0-9-]{1,32}$")
    val TOKEN = Regex("^[0-9a-f]{40}$")

    /** The two return routes, written once: [PaymentReturnAPI] registers them and the route glue tests mount them. */
    const val RETURN_STEP_PATH = "/payments/:providerId/return/:attemptToken/step/:name"
    const val RETURN_OUTCOME_PATH = "/payments/:providerId/return/:attemptToken/:outcome"

    private val OUTCOMES = mapOf("success" to ReturnOutcome.SUCCESS, "cancel" to ReturnOutcome.CANCEL, "pending" to ReturnOutcome.PENDING, "result" to ReturnOutcome.RESULT)

    fun outcomeOf(name: String?): ReturnOutcome? = OUTCOMES[name]

    /**
     * The call of a request on either return route: the `step/:name` route is the `STEP` outcome, the other one reads `:outcome`. `null` (404, nothing
     * touched) for an outcome market does not know or any path parameter that is not of the documented shape.
     */
    fun returnCallOf(context: RoutingContext, remoteIp: (RoutingContext) -> String = { ClientIpResolver.resolve(it).ip.orEmpty() }): InboundCall? {
        val outcome = if (context.pathParam("name") != null) ReturnOutcome.STEP else outcomeOf(context.pathParam("outcome")) ?: return null

        return callOf(context, InboundKind.RETURN, outcome, remoteIp)
    }

    /**
     * The call of [context], or `null` when a path parameter is not of the documented shape (404 without touching anything). [remoteIp] is the
     * trusted-proxy-resolved address (11 section 2, never the raw `X-Forwarded-For`); a test passes its own.
     */
    fun callOf(
        context: RoutingContext, kind: InboundKind, outcome: ReturnOutcome? = null, remoteIp: (RoutingContext) -> String = { ClientIpResolver.resolve(it).ip.orEmpty() }
    ): InboundCall? {
        if ((kind == InboundKind.RETURN) != (outcome != null)) return null

        val providerId = context.pathParam("providerId")?.takeIf { PROVIDER_ID.matches(it) } ?: return null
        val token = if (kind == InboundKind.WEBHOOK) null else context.pathParam("attemptToken")?.takeIf { TOKEN.matches(it) } ?: return null
        val channel = if (kind == InboundKind.RETURN) "default" else context.pathParam("channel")?.let { if (CHANNEL.matches(it)) it else return null } ?: "default"
        val step = if (outcome == ReturnOutcome.STEP) (context.pathParam("name")?.takeIf { CHANNEL.matches(it) } ?: return null) else null
        val request = context.request()
        val headers = LinkedHashMap<String, MutableList<String>>()

        for (name in request.headers().names()) headers.getOrPut(name.lowercase()) { ArrayList() }.addAll(request.headers().getAll(name))

        val query = LinkedHashMap<String, List<String>>()

        for (name in context.queryParams().names()) query[name] = context.queryParams().getAll(name)

        val contentType = request.getHeader("content-type")
        val multipart = contentType?.lowercase()?.startsWith("multipart/form-data") == true
        val form = if (multipart) request.formAttributes().names().associateWith { request.formAttributes().getAll(it) } else null

        return InboundCall(
            kind, providerId, channel, token, outcome, step, request.method().name(), request.path(), request.query(), query, headers, contentType,
            context.body().buffer()?.bytes ?: ByteArray(0), form, remoteIp(context), SystemClock.now()
        )
    }

    /** The provider's reply, byte for byte; a header with a line break in it is never written. */
    fun send(context: RoutingContext, reply: HttpReply) {
        val response = context.response()

        if (response.ended() || response.headWritten()) return

        response.statusCode = reply.status
        reply.contentType?.let { response.putHeader("Content-Type", it) }

        for ((name, value) in reply.headers) {
            if (name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) continue

            response.putHeader(name, value)
        }

        if (!response.headers().contains("Cache-Control")) response.putHeader("Cache-Control", "no-store")

        if (reply.body.isEmpty()) response.end() else response.end(Buffer.buffer(reply.body))
    }

    fun notFound(context: RoutingContext) = send(context, HttpReply(404, null, ByteArray(0)))
}

/** `/api/market/payments/:providerId/webhook[/:channel]` (all methods): the gateway's own server-to-server notification; the provider finds the attempt. */
@Endpoint
class PaymentWebhookAPI(private val plugin: MarketPlugin) : MarketInboundApi() {
    override val paths = listOf(
        Path("/payments/:providerId/webhook", RouteType.ROUTE),
        Path("/payments/:providerId/webhook/:channel", RouteType.ROUTE)
    )

    override val doc = EndpointDoc(
        summary = "Where a payment gateway posts its events; the provider reads and answers it.",
        tag = "payments",
        binary = true
    )

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val call = InboundRouteSupport.callOf(context, InboundKind.WEBHOOK) ?: return InboundRouteSupport.notFound(context).let { null }

        InboundRouteSupport.send(context, inboundDispatcher(plugin).handle(call))

        return null
    }
}

/** `/api/market/payments/:providerId/notify/:attemptToken[/:channel]` (all methods): a per-attempt notification of a gateway that signs nothing. */
@Endpoint
class PaymentNotifyAPI(private val plugin: MarketPlugin) : MarketInboundApi() {
    override val paths = listOf(
        Path("/payments/:providerId/notify/:attemptToken", RouteType.ROUTE),
        Path("/payments/:providerId/notify/:attemptToken/:channel", RouteType.ROUTE)
    )

    override val doc = EndpointDoc(
        summary = "Where a payment gateway notifies one payment attempt; the provider reads and answers it.",
        tag = "payments",
        binary = true
    )

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val call = InboundRouteSupport.callOf(context, InboundKind.NOTIFY) ?: return InboundRouteSupport.notFound(context).let { null }

        InboundRouteSupport.send(context, inboundDispatcher(plugin).handle(call))

        return null
    }
}

/**
 * `/api/market/payments/:providerId/return/:attemptToken/:outcome` (`success`, `cancel`, `pending`, `result`) and `.../return/:attemptToken/step/:name`
 * (all methods): the buyer's browser coming back from the gateway. It only ever ends in a redirect to the order page.
 */
@Endpoint
class PaymentReturnAPI(private val plugin: MarketPlugin) : MarketInboundApi() {
    override val paths = listOf(
        Path(InboundRouteSupport.RETURN_STEP_PATH, RouteType.ROUTE),
        Path(InboundRouteSupport.RETURN_OUTCOME_PATH, RouteType.ROUTE)
    )

    override val doc = EndpointDoc(
        summary = "Where a gateway sends the buyer back; it redirects to the order page.",
        tag = "payments",
        binary = true
    )

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val call = InboundRouteSupport.returnCallOf(context) ?: return InboundRouteSupport.notFound(context).let { null }

        InboundRouteSupport.send(context, inboundDispatcher(plugin).handle(call))

        return null
    }
}

/**
 * `GET /api/market/payments/attempts/:attemptToken/page` (02 section 6): serves the `Html` / `FormPost` start result of an open attempt, always
 * `no-store` / `nosniff` / `no-referrer`, a gateway document only inside a CSP `sandbox` without `allow-same-origin` (opaque origin).
 */
@Endpoint
class GetPaymentAttemptPageAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/payments/attempts/:attemptToken/page", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The hosted page of a payment attempt, for providers that show instructions.",
        tag = "payments",
        binary = true,
        errors = listOf(NotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("attemptToken", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val token = getParameters(context).pathParameter("attemptToken").string

        if (!InboundRouteSupport.TOKEN.matches(token)) return InboundRouteSupport.notFound(context).let { null }

        val response = context.response()

        when (val page = attemptPageService(plugin).page(token)) {
            is AttemptPageResult.NotFound -> InboundRouteSupport.notFound(context)

            is AttemptPageResult.ToOrderPage -> {
                val base = com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring(plugin).site().baseUrl.trimEnd('/')
                val store = MarketLinks.platform.store() ?: base.ifEmpty { "/" }

                InboundRouteSupport.send(
                    context,
                    HttpReply.redirect(if (page.publicId.isNullOrEmpty()) store else MarketLinks.platform.order(page.publicId) ?: store).also {
                        it.headers = it.headers + ("Referrer-Policy" to "no-referrer")
                    }
                )
            }

            is AttemptPageResult.Page -> {
                response.statusCode = 200

                for ((name, value) in page.headers) response.putHeader(name, value)

                response.end(page.body)
            }
        }

        return null
    }
}
