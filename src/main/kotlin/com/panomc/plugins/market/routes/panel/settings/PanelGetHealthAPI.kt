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
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.ClientIpResolver
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * The `GET /health` body (04 section 8) without `result`. Pure: the route passes in what it read from the runtime, the router and
 * [MarketHealthReader] (jobs, queues, providers, servers, the credit check, locks); [extras] defaults to the empty / zero value of every part, so the shape
 * is the same while a part cannot be read.
 */
fun marketHealthBody(
    health: MarketRuntime.Health,
    ipTrust: String,
    routes: List<Map<String, Any?>>,
    extras: HealthExtras = HealthExtras.EMPTY
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
        "jobs" to extras.jobs,
        "queues" to extras.queues,
        "providers" to extras.providers,
        "servers" to extras.servers,
        "credits" to extras.credits,
        "mail" to health.capabilities.mailStatus,
        "mailEnabled" to health.capabilities.mail,
        "ipTrust" to ipTrust,
        "lockedSubjects" to extras.lockedSubjects,
        "rejectedEventsLastHour" to extras.rejectedEventsLastHour,
        "routes" to routes
    )
}

/** The reader of the live sections on the plugin's beans. */
internal fun healthReader(plugin: MarketPlugin): MarketHealthReader {
    val context = plugin.beans
    val housekeeping = com.panomc.plugins.market.job.housekeepingJob(plugin)
    val prefix = { context.getBean(com.panomc.plugins.market.db.dao.MarketThrottleDao::class.java).prefix() }

    return MarketHealthReader(
        clock = com.panomc.plugins.market.core.time.SystemClock,
        prefix = prefix,
        client = { context.getBean(com.panomc.platform.db.DatabaseManager::class.java).getSqlClient() },
        jobStats = { plugin.jobStats() },
        providerListings = {
            val lookup = com.panomc.plugins.market.routes.panel.settings.payment.providerLookup(plugin)

            lookup.listing(com.panomc.plugins.market.provider.ProviderKind.PAYMENT) + lookup.listing(com.panomc.plugins.market.provider.ProviderKind.SHIPPING)
        },
        serverViews = { com.panomc.plugins.market.routes.panel.server.mcSyncService(plugin).servers() },
        lastCredits = { housekeeping.reconciler?.last },
        recheckCredits = { housekeeping.reconciler?.run(full = true) }
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

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val extras = try {
            healthReader(plugin).read(context.request().getParam("recheck")?.trim())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // the page answers while the store is in trouble: the beans may be gone, the parts then report their empty values
            HealthExtras.EMPTY
        }

        return Successful(marketHealthBody(MarketRuntime.health(), ClientIpResolver.ipTrust(), registeredRoutes(), extras))
    }

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
