package com.panomc.plugins.market.routes.panel.goal

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketGoalDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.GoalService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool

internal fun goalService(plugin: MarketPlugin): GoalService {
    val context = plugin.beans
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }

    return GoalService(
        db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock),
        config = { currentConfig(plugin) },
        clock = SystemClock,
        goals = context.getBean(MarketGoalDao::class.java),
        products = context.getBean(MarketProductDao::class.java)
    )
}

/** The wire shape of a goal (04 section 5): `target` and `progress` of a `REVENUE` goal are money x100 like every amount. */
internal fun goalJson(view: GoalService.GoalView): JsonObject {
    val goal: MarketGoal = view.goal

    return JsonObject()
        .put("id", goal.id)
        .put("name", goal.name)
        .put("description", goal.description)
        .put("metric", goal.metric.name)
        .put("productIds", goal.productIds?.let { JsonArray(it) })
        .put("target", goal.target)
        .put("progress", goal.progress)
        .put("percent", view.percent)
        .put("currency", goal.currency)
        .put("period", goal.period.name)
        .put("periodStart", goal.periodStart)
        .put("startsAt", goal.startsAt)
        .put("endsAt", goal.endsAt)
        .put("status", goal.status)
        .put("showOnStore", goal.showOnStore)
        .put("completedAt", goal.completedAt)
        .put("position", goal.position)
        .put("createdAt", goal.createdAt)
        .put("updatedAt", goal.updatedAt)
}

internal val GOAL_FORM_FIELDS = listOf(
    "name", "description", "metric", "productIds", "target", "period", "startsAt", "endsAt", "status", "showOnStore", "position"
)
