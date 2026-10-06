package com.panomc.plugins.market.routes.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.job.MailWiring
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseOptionalEnum
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.routes.base.rejectUnknownKeys
import com.panomc.plugins.market.routes.panel.order.actingUserId
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

// The mail routes of the panel (MK-146; 04 sections 7 and 8, 12 sections 4.5 and 10). Thin shells over `MailAdmin`.

/** The body of `POST /orders/:id/mails/resend`: `kind*`, `recipient?`, `refId?` (an integral id: the shipment, refund, ...). The kind and recipient rules are the service's. */
internal class ResendMailRequest(val kind: String?, val recipient: String?, val refId: Long?)

internal fun parseResendMailRequest(body: JsonObject): ResendMailRequest {
    rejectUnknownKeys(body, setOf("kind", "recipient", "refId"))

    fun text(key: String): String? = when (val v = body.getValue(key)) {
        null -> null
        is String -> v
        else -> throw RequestValueException(key, "MUST_BE_A_STRING")
    }

    val kind = text("kind")?.takeIf { it.isNotBlank() } ?: throw RequestValueException("kind", "REQUIRED")

    return ResendMailRequest(kind, text("recipient"), body.getValue("refId")?.let { parseBodyId(it, "refId") })
}

/** The body of `POST /settings/mail/test`: `kind?`, `recipient?`; both text. */
internal class TestMailRequest(val kind: String?, val recipient: String?)

internal fun parseTestMailRequest(body: JsonObject): TestMailRequest {
    rejectUnknownKeys(body, setOf("kind", "recipient"))

    fun text(key: String): String? = when (val v = body.getValue(key)) {
        null -> null
        is String -> v
        else -> throw RequestValueException(key, "MUST_BE_A_STRING")
    }

    return TestMailRequest(text("kind"), text("recipient"))
}

/** The query of `GET /mails`: `status?`, `kind?`, `orderId?`, `page?`, `pageSize?`; a value outside the contract is a 400. */
internal class MailListQuery(val status: MailStatus?, val kind: MailKind?, val orderId: Long?, val window: Paging.Window)

internal fun parseMailListQuery(status: String?, kind: String?, orderId: String?, page: String?, pageSize: String?): MailListQuery {
    fun number(raw: String?, name: String): Long? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_A_NUMBER") }

    return MailListQuery(
        status = parseOptionalEnum(MailStatus.entries.toTypedArray(), status?.trim()?.takeIf { it.isNotEmpty() }, "status"),
        kind = parseOptionalEnum(MailKind.entries.toTypedArray(), kind?.trim()?.takeIf { it.isNotEmpty() }, "kind"),
        orderId = orderId?.trim()?.takeIf { it.isNotEmpty() }?.let { parseId(it, "orderId") },
        window = parsePagingRequest(number(page, "page"), number(pageSize, "pageSize"))
    )
}

/** `POST /api/panel/market/orders/:id/mails/resend` (`P:OM`): `{}`; 400 `INVALID_MAIL_KIND`, `MAIL_RECIPIENT_REQUIRED`; 409 `MAIL_DISABLED`, `MAIL_NOT_APPLICABLE`. Log `RESENT_MARKET_ORDER_MAIL`. */
@Endpoint
class PanelResendOrderMailAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/mails/resend", RouteType.POST))

    override val nodes = setOf(MarketNode.ORDERS_MANAGE)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val orderId = parseId(context.pathParam("id"), "id")
        val request = parseResendMailRequest(getParameters(context).body().jsonObject)

        MailWiring.admin(plugin).resend(orderId, request.kind, request.recipient, request.refId, databaseManager.getSqlClient())

        logOrderDecision(plugin, context) { id, name -> ResentMarketOrderMailLog(id, name, plugin.pluginId, orderId, request.kind!!.trim()) }

        return Successful()
    }
}

/** `GET /api/panel/market/mails` (`P:OV`): `mails[]`, `mailCount`, `totalPage`; the recipient is masked without `OM` / `PAY`. */
@Endpoint
class PanelGetMailsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/mails", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("status", "kind", "orderId", "page", "pageSize")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = context.request()
        val query = parseMailListQuery(request.getParam("status"), request.getParam("kind"), request.getParam("orderId"), request.getParam("page"), request.getParam("pageSize"))
        val page = MailWiring.admin(plugin).list(
            query.status, query.kind, query.orderId, query.window, has(context, MarketNode.ORDERS_MANAGE, MarketNode.PAYMENTS), databaseManager.getSqlClient()
        )
        val totalPages = Paging.totalPages(page.total, query.window.pageSize)

        if (Paging.isBeyondLast(query.window.page, totalPages)) throw PageNotFound()

        return Successful(mapOf("mails" to page.rows, "mailCount" to page.total, "totalPage" to totalPages))
    }
}

/** `POST /api/panel/market/mails/:id/retry` (`P:OM`): `{}`; 409 `INVALID_STATE`, `MAIL_DISABLED`; 502 `MAIL_SEND_FAILED`. Log `RETRIED_MARKET_MAIL`. */
@Endpoint
class PanelRetryMailAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/mails/:id/retry", RouteType.POST))

    override val nodes = setOf(MarketNode.ORDERS_MANAGE)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"), "id")
        val row = MailWiring.admin(plugin).retry(id, databaseManager.getSqlClient())

        logOrderDecision(plugin, context) { userId, name -> RetriedMarketMailLog(userId, name, plugin.pluginId, id, row.kind.name) }

        return Successful()
    }
}

/** `POST /api/panel/market/settings/mail/test` (`P:SET`): `kind?`, `recipient?` (default: the admin's e-mail); `{}`; 409 `MAIL_DISABLED`; 502 `MAIL_SEND_FAILED`. Log `SENT_MARKET_TEST_MAIL`. */
@Endpoint
class PanelSendTestMailAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/settings/mail/test", RouteType.POST))

    override val nodes = setOf(MarketNode.SETTINGS)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = parseTestMailRequest(runCatching { getParameters(context).body()?.jsonObject }.getOrNull() ?: JsonObject())
        val userId = actingUserId(plugin, context)
        val email = PlatformUserDirectory { databaseManager }.emailOf(userId, databaseManager.getSqlClient())

        MailWiring.admin(plugin).sendTest(request.kind, request.recipient, email)

        logOrderDecision(plugin, context) { id, name -> SentMarketTestMailLog(id, name, plugin.pluginId) }

        return Successful()
    }
}
