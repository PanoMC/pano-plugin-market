package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.ExportedMarketOrdersLog
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.abuseWiring
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.OrderExportColumns
import com.panomc.plugins.market.util.CsvWriter
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `GET /api/panel/market/orders/export` (`P:OV`, 04 section 7, 13 section 5.2, 11 sections 6.5 and 11): the filters of `GET /orders` plus `columns` (csv of the keys of 04
 * section 7; absent = every column the caller may select) and `delimiter` (`,` | `;` | `tab`). A UTF-8 CSV with a byte order mark, one row per order item, at most 50 000
 * rows (a longer result is cut and announced by the header `X-Market-Export-Truncated: true`), every text cell quoted and formula-safe. `email` and `country` are
 * selectable only with `OM` or `PAY` (403 `NO_PERMISSION`). 6 a minute per panel user (L11, 429). Activity log `EXPORTED_MARKET_ORDERS`.
 */
@Endpoint
class PanelExportOrdersAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    // order = 0 so /orders/export is registered before the /orders/:id path parameter route.
    override val order = 0

    override val paths = listOf(Path("/orders/export", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in ORDER_FILTER_QUERY + listOf("columns", "delimiter")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result? {
        val userId = actingUserId(plugin, context)

        abuseWiring(plugin).rateLimits.export(userId)

        val parameters = getParameters(context)

        fun query(name: String) = parameters.queryParameter(name)?.string

        val pii = FieldGating.piiTier(context)
        val filter = parseOrderFilter(
            query("status"), query("paymentMethodId"), query("fulfillmentStatus"), query("shippingStatus"), query("from"), query("to"), query("testMode"), query("source"), query("search")
        )
        val columns = OrderExportColumns.parse(query("columns"), pii)
        val delimiter = CsvWriter.Delimiter.parse(query("delimiter"))
        val response = context.response()
        val sqlClient = databaseManager.getSqlClient()

        val result = try {
            orderQueryService(plugin).export(
                filter, columns, delimiter, pii, sqlClient,
                begin = { truncated ->
                    response.setChunked(true)
                        .putHeader("Content-Type", "text/csv; charset=utf-8")
                        .putHeader("Content-Disposition", "attachment; filename=\"market-orders-${LocalDate.now(ZoneOffset.UTC)}.csv\"")
                        .putHeader("X-Content-Type-Options", "nosniff")
                        .putHeader("Cache-Control", "no-store")

                    if (truncated) response.putHeader("X-Market-Export-Truncated", "true")
                },
                sink = { piece -> response.write(piece).coAwait() }
            )
        } catch (e: Throwable) {
            // the status line is gone once the first piece went out: the only honest answer left is a broken connection, never a JSON body inside a CSV
            if (response.headWritten()) {
                runCatching { response.reset() }

                return null
            }

            throw e
        }

        response.end().coAwait()

        logOrderDecision(plugin, context) { id, username ->
            ExportedMarketOrdersLog(id, username, plugin.pluginId, result.rows, filter.describe(), columns.any { it in FieldGating.PII_EXPORT_COLUMNS })
        }

        return null
    }
}
