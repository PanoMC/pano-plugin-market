package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.Route
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.RouteAuth
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.ClientIpResolver
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * The `GET /health` body (04 section 8) without `result`. Sections whose subsystem has not landed yet (jobs, queues,
 * providers, servers, credit check, locks) are present with their empty / zero value, so the shape is stable and later
 * slices only fill it. Pure: the route passes in what it read from the runtime and the router.
 */
fun marketHealthBody(
    health: MarketRuntime.Health,
    ipTrust: String,
    routes: List<Map<String, Any?>>
): Map<String, Any?> {
    val unfixed = health.unfixed.filterValues { it > 0 }.keys.toList().sorted()

    return linkedMapOf(
        "runtimeState" to health.state.name,
        "schema" to mapOf(
            "ok" to (health.problems.isEmpty() && unfixed.isEmpty()),
            "missing" to health.problems,
            "unfixed" to unfixed
        ),
        "bootstrapErrors" to health.bootstrapErrors,
        "jobs" to emptyList<Any>(),
        "queues" to mapOf(
            "deliveriesPending" to 0,
            "deliveriesFailed" to 0,
            "mailsPending" to 0,
            "webhooksPending" to 0,
            "deferredEvents" to 0,
            "failedEvents" to 0
        ),
        "providers" to emptyList<Any>(),
        "servers" to emptyList<Any>(),
        "credits" to mapOf("ok" to true, "checkedAt" to null, "problems" to emptyList<String>()),
        "mail" to health.capabilities.mailStatus,
        "mailEnabled" to health.capabilities.mail,
        "ipTrust" to ipTrust,
        "lockedSubjects" to 0,
        "rejectedEventsLastHour" to 0,
        "routes" to routes
    )
}

/** `GET /api/panel/market/health` (04 section 8): node `SET`. Answers while the market is not READY. */
@Endpoint
class PanelGetHealthAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/health", RouteType.GET))

    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val exemptFromRuntimeGate = true

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("recheck", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        Successful(marketHealthBody(MarketRuntime.health(), ClientIpResolver.ipTrust(), registeredRoutes()))

    /** Every route the plugin registered, with its auth class, so the permission-matrix test can prove none is missing. */
    private fun registeredRoutes(): List<Map<String, Any?>> = try {
        plugin.pluginBeanContext.getBeansWithAnnotation(Endpoint::class.java).values
            .filterIsInstance<Route>()
            .flatMap { route ->
                route.paths.map { path ->
                    mapOf("method" to path.routeType.name, "path" to path.url, "auth" to RouteAuth.describe(route))
                }
            }
            .sortedWith(compareBy({ it["path"] as String }, { it["method"] as String }))
    } catch (e: Exception) {
        emptyList()
    }
}
