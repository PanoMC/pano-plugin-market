package com.panomc.plugins.market.routes.api.order

import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.base.MarketApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/orders/:publicId` (04 section 3, auth class `PUB` with `OrderAccess`): the `OrderView` for the caller's role (11 section 5.2).
 * The owner (the payer's session, or the `X-Order-Token` header / the `token` query of a mail link) sees everything including the stored payment
 * start and the data a retry needs; the recipient and everyone else a cut-down view. The answer is never cached and sends no referrer.
 */
@Endpoint
class GetOrderAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/orders/:publicId", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "One order, cut down to what the caller may see: the owner (session or order token), the gift recipient, or anyone else.",
        tag = "orders",
        response = objectSchema().requiredProperty("order", MarketSchemas.order),
        errors = listOf(NotFound::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("publicId", stringSchema()))
            .queryParameter(optionalParam("token", stringSchema()))
            .queryParameter(optionalParam("locale", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()
        val access = resolveOrder(plugin, context, getParameters(context).pathParameter("publicId").string, sqlClient)
        val view = paymentService(plugin).viewFor(access.order, access.role, payCaller(plugin, context), sqlClient)

        noStore(context)

        return Successful(mapOf("order" to view))
    }
}

/** 11 section 5.1: every `ORDER` response is `no-store` and sends no referrer. */
internal fun noStore(context: RoutingContext) {
    context.response().putHeader("Cache-Control", "no-store").putHeader("Referrer-Policy", "no-referrer")
}
