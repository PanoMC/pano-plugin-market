package com.panomc.plugins.market.routes.api.store

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketGoalDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.GoalProgress
import com.panomc.plugins.market.service.WidgetService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `?include=` of `GET /api/market/widgets` (04 section 3): a csv of `recentBuyers`, `topSupporters`, `goals`, `stats`. Absent or blank = all four; a name
 * outside the list is a 400 (never ignored), repeated names count once.
 */
fun parseWidgetInclude(raw: String?): Set<String> {
    val names = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    if (names.isEmpty()) return WidgetService.SECTIONS

    for (name in names) if (name !in WidgetService.SECTIONS) throw RequestValueException("include", "INVALID")

    return names.toSet()
}

private object WidgetWiringHolder

@Volatile
private var cachedWidgets: Pair<MarketPlugin, WidgetService>? = null

/** The widget service of the plugin (its 30 s cache lives in the instance, so every request shares it). */
internal fun widgetService(plugin: MarketPlugin): WidgetService {
    cachedWidgets?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(WidgetWiringHolder) {
        cachedWidgets?.takeIf { it.first === plugin }?.second ?: run {
            val context = plugin.beans
            val goals = context.getBean(MarketGoalDao::class.java)

            WidgetService({ currentConfig(plugin) }, SystemClock, goals, { goals.prefix() }).also { cachedWidgets = plugin to it }
        }
    }
}

/** The goal progress writer (`AdvanceGoalProgress` of the order transitions, the decrement of a refund) on the plugin's beans; stateless. */
internal fun goalProgress(plugin: MarketPlugin): GoalProgress {
    val context = plugin.beans

    return GoalProgress(
        SystemClock, { currentConfig(plugin) }, context.getBean(MarketGoalDao::class.java), context.getBean(MarketOrderDao::class.java), context.getBean(MarketOrderItemDao::class.java)
    )
}

/**
 * `GET /api/market/widgets` (04 section 3, auth class `PUB`): the storefront modules, each only when its module flag is on, plus `sidebars`. Anonymous and
 * the same for everybody (usernames only, 11 section 5), so it is cached in memory for 30 s and for a shared cache for the same time.
 */
@Endpoint
class GetWidgetsAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/api/market/widgets", RouteType.GET))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("include", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val include = parseWidgetInclude(context.request().getParam("include"))
        val body = widgetService(plugin).widgets(include, databaseManager.getSqlClient())

        context.response().putHeader("Cache-Control", "public, max-age=30")

        return Successful(body.map)
    }
}
