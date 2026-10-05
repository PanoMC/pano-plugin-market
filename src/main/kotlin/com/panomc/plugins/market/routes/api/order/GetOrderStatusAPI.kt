package com.panomc.plugins.market.routes.api.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.api.OrderRole
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.service.ClientIpResolver
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/orders/:publicId/status` (04 section 3, 06 section 10.4): `status, paymentStatus, fulfillmentStatus, shippingStatus, updatedAt`
 * for every role. For the owner it may run one provider status query (at most every 10 s per attempt). Limiter L7: 120 a minute per IP.
 */
@Endpoint
class GetOrderStatusAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/api/market/orders/:publicId/status", RouteType.GET))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val limiter = StatusLimiter()

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("publicId", stringSchema()))
            .queryParameter(optionalParam("token", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val ip = ClientIpResolver.resolve(context)

        limiter.check(ip.ip.takeIf { ip.trusted })

        val sqlClient = databaseManager.getSqlClient()
        val access = resolveOrder(plugin, context, getParameters(context).pathParameter("publicId").string, sqlClient)
        val body = paymentService(plugin).status(access.order, access.role == OrderRole.OWNER, sqlClient)

        noStore(context)

        return Successful(body.map)
    }
}
