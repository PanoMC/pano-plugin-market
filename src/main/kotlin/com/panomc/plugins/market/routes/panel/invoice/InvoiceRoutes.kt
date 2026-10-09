package com.panomc.plugins.market.routes.panel.invoice

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

// The invoice routes of the panel (MK-144; 04 sections 7 and 8, 12 section 9.2). Thin shells over `InvoiceEndpoints`.

/** The headers of a PDF that holds billing data (12 section 9.1): a download, never sniffed, cached nowhere, no referrer. */
internal fun pdfHeaders(context: RoutingContext, fileName: String, inline: Boolean = false) {
    context.response()
        .putHeader("Content-Type", "application/pdf")
        .putHeader("Content-Disposition", (if (inline) "inline" else "attachment") + "; filename=\"$fileName\"")
        .putHeader("X-Content-Type-Options", "nosniff")
        .putHeader("Cache-Control", "no-store")
        .putHeader("Referrer-Policy", "no-referrer")
}

/** `type` query: `INVOICE` (default) or `CREDIT_NOTE`; anything else is a 400. */
internal fun parseInvoiceType(raw: String?): InvoiceType {
    val value = raw?.trim().orEmpty()

    if (value.isEmpty()) return InvoiceType.INVOICE

    return InvoiceType.entries.firstOrNull { it.name == value } ?: throw RequestValueException("type", "INVALID")
}

/** `refundId` query: absent, or an integer id. */
internal fun parseRefundId(raw: String?): Long? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { parseId(it, "refundId") }

private val LOCALE = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,2}$")

/** `locale` query of the preview: absent, or a locale code. */
internal fun parsePreviewLocale(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    if (!LOCALE.matches(value)) throw RequestValueException("locale", "INVALID")

    return value
}

/** The body of `PUT /settings/invoice-sequence`: `series*` and an integral `nextNumber*`; a value that moves backwards is the service's 400. */
internal fun parseSequenceBody(body: JsonObject): Pair<String, Long> {
    val series = (body.getValue("series") as? String)?.trim().orEmpty()

    if (series.isEmpty()) throw RequestValueException("series", "REQUIRED")

    val next = when (val raw = body.getValue("nextNumber")) {
        is Int -> raw.toLong()
        is Long -> raw
        is Double -> if (raw == Math.floor(raw) && Math.abs(raw) < 9.0E15) raw.toLong() else null
        else -> null
    } ?: throw RequestValueException("nextNumber", "MUST_BE_AN_INTEGER")

    return series to next
}

abstract class InvoiceRoute(protected val plugin: MarketPlugin, override val nodes: Set<MarketNode>) : MarketPanelApi() {
    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    internal val endpoints: InvoiceEndpoints get() = invoiceWiring(plugin).endpoints

    protected suspend fun sql() = databaseManager.getSqlClient()

    protected fun idOf(context: RoutingContext): Long = parseId(context.pathParam("id"), "id")

    protected suspend fun log(context: RoutingContext, build: (userId: Long, username: String) -> com.panomc.platform.db.model.PluginActivityLog) {
        val client = sql()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, client)!!

        databaseManager.panelActivityLogDao.add(build(userId, username), client)
    }

    protected fun noBody(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()
}

/** `GET /api/panel/market/orders/:id/invoice` (`P:OM` or `P:PAY`: the document holds billing data): the PDF; 404; 500 `INVOICE_RENDER_FAILED`. */
@Endpoint
class PanelGetOrderInvoiceAPI(plugin: MarketPlugin) : InvoiceRoute(plugin, setOf(MarketNode.ORDERS_MANAGE, MarketNode.PAYMENTS)) {
    override val paths = listOf(Path("/orders/:id/invoice", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("type", stringSchema()))
            .queryParameter(optionalParam("refundId", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result? {
        val type = parseInvoiceType(context.request().getParam("type"))
        val refundId = parseRefundId(context.request().getParam("refundId"))
        val invoice = endpoints.panelFile(idOf(context), type, refundId, sql())

        pdfHeaders(context, "${invoice.number}.pdf")
        context.response().sendFile(invoice.file.absolutePath)

        return null
    }
}

/** `POST /api/panel/market/orders/:id/invoice/regenerate` (`P:OM`): `{documents: [{type, number}]}`; 409 `INVOICE_NOT_ISSUABLE {reason}`. */
@Endpoint
class PanelRegenerateOrderInvoiceAPI(plugin: MarketPlugin) : InvoiceRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/orders/:id/invoice/regenerate", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val orderId = idOf(context)
        val documents = endpoints.regenerate(orderId, sql())

        log(context) { u, n -> RegeneratedMarketInvoiceLog(u, n, plugin.pluginId, orderId, documents.map { it.number }) }

        return Successful(mapOf("documents" to JsonArray(documents.map { JsonObject().put("type", it.type.name).put("number", it.number) })))
    }
}

/** `GET /api/panel/market/settings/invoice/preview` (`P:SET`): the sample PDF under the current seller settings; q `locale?`, `type?`. */
@Endpoint
class PanelGetInvoicePreviewAPI(plugin: MarketPlugin) : InvoiceRoute(plugin, setOf(MarketNode.SETTINGS)) {
    override val paths = listOf(Path("/settings/invoice/preview", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("locale", stringSchema()))
            .queryParameter(optionalParam("type", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result? {
        val locale = parsePreviewLocale(context.request().getParam("locale"))
        val type = parseInvoiceType(context.request().getParam("type"))
        val bytes = endpoints.preview(locale, type)

        pdfHeaders(context, "invoice-preview.pdf", inline = true)
        context.response().end(io.vertx.core.buffer.Buffer.buffer(bytes))

        return null
    }
}

/** `PUT /api/panel/market/settings/invoice-sequence` (`P:SET`): `series*`, `nextNumber*` (only upwards); `{}`; 400 `INVALID_INVOICE_SEQUENCE {minimum}`. */
@Endpoint
class PanelUpdateInvoiceSequenceAPI(plugin: MarketPlugin) : InvoiceRoute(plugin, setOf(MarketNode.SETTINGS)) {
    override val paths = listOf(Path("/settings/invoice-sequence", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema().requiredProperty("series", stringSchema()).requiredProperty("nextNumber", numberSchema()).allowAdditionalProperties(false)
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val (series, nextNumber) = parseSequenceBody(context.body().asJsonObject() ?: JsonObject())

        endpoints.setSequence(series, nextNumber)

        log(context) { u, n -> UpdatedMarketInvoiceSequenceLog(u, n, plugin.pluginId, series, nextNumber) }

        return Successful()
    }
}
