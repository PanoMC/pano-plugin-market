package com.panomc.plugins.market.routes.panel.shipment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.spi.shipping.LabelFormat
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

// `GET /shipments/:id/label` (MK-145; 10 section 9.8, 04 section 7). The decision which file to serve lives in `ShippingService.shipmentLabel`.

/** The most documents a shipment keeps (`ShippingService` stores at most 20): a larger `index` is a 400. */
internal const val MAX_LABEL_INDEX = 20

/** `index` query: absent = 0, else an integer from 0 to [MAX_LABEL_INDEX]. */
internal fun parseLabelIndex(raw: String?): Int {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
    val index = value.toIntOrNull() ?: throw RequestValueException("index", "INVALID")

    if (index < 0 || index > MAX_LABEL_INDEX) throw RequestValueException("index", "INVALID")

    return index
}

/** `generic` query: absent = false, `true` / `1` / `false` / `0`. */
internal fun parseLabelGeneric(raw: String?): Boolean = when (raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
    null, "false", "0" -> false
    "true", "1" -> true
    else -> throw RequestValueException("generic", "INVALID")
}

/** 10 section 9.8: `PDF application/pdf`, `PNG`, `GIF`, `SVG`, `HTML` their own types, `ZPL` / `EPL` an opaque stream. */
internal fun labelContentType(format: LabelFormat): String = when (format) {
    LabelFormat.PDF -> "application/pdf"
    LabelFormat.PNG -> "image/png"
    LabelFormat.GIF -> "image/gif"
    LabelFormat.SVG -> "image/svg+xml"
    LabelFormat.HTML -> "text/html"
    LabelFormat.ZPL, LabelFormat.EPL -> "application/octet-stream"
}

internal fun labelExtension(format: LabelFormat): String = when (format) {
    LabelFormat.PDF -> "pdf"
    LabelFormat.PNG -> "png"
    LabelFormat.GIF -> "gif"
    LabelFormat.SVG -> "svg"
    LabelFormat.HTML -> "html"
    LabelFormat.ZPL -> "zpl"
    LabelFormat.EPL -> "epl"
}

/** `label-<orderId>-<shipmentId>-<index>.<ext>`. */
internal fun labelFileName(orderId: Long, shipmentId: Long, index: Int, format: LabelFormat): String = "label-$orderId-$shipmentId-$index.${labelExtension(format)}"

/**
 * Always an attachment (carrier supplied HTML / SVG must never render on the panel origin), never sniffed, never cached, and sandboxed should a
 * browser open it anyway.
 */
internal fun labelHeaders(context: RoutingContext, format: LabelFormat, fileName: String) {
    context.response()
        .putHeader("Content-Type", labelContentType(format))
        .putHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
        .putHeader("X-Content-Type-Options", "nosniff")
        .putHeader("Cache-Control", "no-store")
        .putHeader("Content-Security-Policy", "sandbox")
}

/** `GET /api/panel/market/shipments/:id/label` (`P:OM`): q `index?` (0), `generic?`; the stored carrier label, or market's generic PDF label. */
@Endpoint
class PanelGetShipmentLabelAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/shipments/:id/label", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("index", stringSchema()))
            .queryParameter(optionalParam("generic", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result? {
        val id = idOf(context)
        val index = parseLabelIndex(context.request().getParam("index"))
        val generic = parseLabelGeneric(context.request().getParam("generic"))
        val label = service.shipmentLabel(id, index, generic, sql())

        labelHeaders(context, label.format, labelFileName(label.orderId, label.shipmentId, label.index, label.format))
        context.response().end(Buffer.buffer(label.bytes))

        return null
    }
}
