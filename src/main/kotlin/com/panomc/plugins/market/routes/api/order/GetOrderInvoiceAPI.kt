package com.panomc.plugins.market.routes.api.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.RateLimiter
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.panel.invoice.invoiceWiring
import com.panomc.plugins.market.routes.panel.invoice.parseInvoiceType
import com.panomc.plugins.market.routes.panel.invoice.parseRefundId
import com.panomc.plugins.market.routes.panel.invoice.pdfHeaders
import com.panomc.plugins.market.service.ClientIpResolver
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** The buyer's invoice download: 30 a minute per IP (12 section 9.1). */
internal class InvoiceDownloadLimiter {
    private val limiter = RateLimiter(30, 2_000L)

    fun check(clientIp: String?) {
        val bucket = IpRange.bucketKey(clientIp) ?: return

        if (!limiter.tryAcquire("ip:$bucket")) throw TooManyRequests(2)
    }
}

/**
 * `GET /api/market/orders/:publicId/invoice` (04 section 3, auth `ORDER`, owner only; 12 section 9.1): the invoice (q `type`, default `INVOICE`) or
 * a credit note (`type=CREDIT_NOTE`, `refundId` when the order has more than one) as a PDF attachment. The caller who is not the owner (a gift
 * recipient included), an unknown order and a missing document all answer 404 `NOT_FOUND`. The file is the one the row names; no client value
 * reaches the file system.
 */
@Endpoint
class GetOrderInvoiceAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/api/market/orders/:publicId/invoice", RouteType.GET))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val limiter = InvoiceDownloadLimiter()

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("publicId", stringSchema()))
            .queryParameter(optionalParam("token", stringSchema()))
            .queryParameter(optionalParam("type", stringSchema()))
            .queryParameter(optionalParam("refundId", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val ip = ClientIpResolver.resolve(context)

        limiter.check(ip.ip.takeIf { ip.trusted })

        val sqlClient = databaseManager.getSqlClient()
        val access = resolveOrder(plugin, context, getParameters(context).pathParameter("publicId").string, sqlClient)
        val type = parseInvoiceType(context.request().getParam("type"))
        val refundId = parseRefundId(context.request().getParam("refundId"))
        val invoice = invoiceWiring(plugin).endpoints.buyerFile(access, type, refundId, sqlClient)

        pdfHeaders(context, "${invoice.number}.pdf")
        context.response().sendFile(invoice.file.absolutePath)

        return null
    }
}
