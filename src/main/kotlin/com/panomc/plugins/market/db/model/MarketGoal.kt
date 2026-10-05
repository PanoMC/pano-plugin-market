package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_goal.metric` (01 section 12). */
enum class GoalMetric { REVENUE, ORDERS, PRODUCT_SALES }

/** `market_goal.period`. */
enum class GoalPeriod { ONE_TIME, WEEKLY, MONTHLY }

/**
 * `market_goal` (01 section 12): a store goal. [target] is minor units for `REVENUE`, a count otherwise;
 * [progress] is changed only by the atomic `+delta` of the DAO. [productIds] is JSON (`null` = every product).
 */
open class MarketGoal(
    val id: Long = -1,
    val name: String = "",
    val description: String? = null,
    val metric: GoalMetric = GoalMetric.REVENUE,
    val productIds: String? = null,
    val target: Long = 0,
    val progress: Long = 0,
    val currency: String? = null,
    val period: GoalPeriod = GoalPeriod.ONE_TIME,
    val periodStart: Long? = null,
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    val status: String = "ACTIVE",
    val showOnStore: Boolean = true,
    val completedAt: Long? = null,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
