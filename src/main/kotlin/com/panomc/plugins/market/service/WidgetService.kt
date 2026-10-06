package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.config.TopSupportersPeriod
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketGoalDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.model.GoalMetric
import com.panomc.plugins.market.db.model.GoalPeriod
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * `GET /api/market/widgets` (04 section 3, 11 section 5): the four storefront modules as queries over `market_order` / `market_order_item` / `market_goal`,
 * each only when its module flag is on (`moduleRecentBuyers`, `moduleTopSupporters`, `moduleGoal`, `moduleStats`), plus `sidebars` (= `moduleSidebars`).
 *
 * What a visitor may learn (11 section 5): usernames only; a test order, an anonymised order (the `PII_ERASED` timeline event of the erasure) and the
 * orders of a blocked player (an active `PLAYER` or `USER` block) are left out of `recentBuyers` and `topSupporters`; `hideFromBroadcast` orders are left out
 * of `recentBuyers`; amounts (`recentBuyers[].amount`, `topSupporters[].total`) only when `moduleRecentBuyersShowAmount`. Only orders that count as sales
 * (`COMPLETED`, `PARTIALLY_REFUNDED`, `paidAt` set) are looked at.
 *
 * The answer is cached for [cacheMs] (30 s, 04 section 3) in memory, per set of requested modules and per module configuration, so a setting that changes
 * is visible at once while the store's traffic does not turn into queries. [now] is the injected clock.
 */
class WidgetService(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val goals: MarketGoalDao,
    private val prefix: () -> String,
    private val cacheMs: Long = CACHE_MS
) {
    private class Cached(val expiresAt: Long, val body: JsonObject)

    private val cache = ConcurrentHashMap<String, Cached>()

    private fun table(name: String) = "`${prefix()}$name`"

    companion object {
        const val CACHE_MS = 30_000L

        /** The keys `include` may name (04 section 3). */
        val SECTIONS: Set<String> = linkedSetOf("recentBuyers", "topSupporters", "goals", "stats")

        /** The host sidebars a plugin can address (00 section 12). */
        val SIDEBARS: Set<String> = setOf("home", "profile")

        private const val SALE = "o.`status` IN ('COMPLETED', 'PARTIALLY_REFUNDED') AND o.`testMode` = 0 AND o.`paidAt` IS NOT NULL"

        private fun clampCount(value: Int, max: Int) = value.coerceIn(1, max)
    }

    /** The widgets named by [include] (a subset of [SECTIONS]); a section whose module is off is absent. */
    suspend fun widgets(include: Set<String>, client: SqlClient): JsonObject {
        val cfg = config()
        val key = cacheKey(cfg, include)
        val now = clock.now()

        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.body.copy() }

        val body = JsonObject()

        if ("recentBuyers" in include && cfg.moduleRecentBuyers) body.put("recentBuyers", recentBuyers(cfg, now, client))
        if ("topSupporters" in include && cfg.moduleTopSupporters) body.put("topSupporters", topSupporters(cfg, now, client))
        if ("goals" in include && cfg.moduleGoal) body.put("goals", goalViews(now, client))
        if ("stats" in include && cfg.moduleStats) body.put("stats", stats(cfg, now, client))

        body.put("sidebars", JsonArray(cfg.moduleSidebars.filter { it in SIDEBARS }.distinct()))

        if (cache.size > 64) cache.entries.removeIf { it.value.expiresAt <= now }

        cache[key] = Cached(now + cacheMs, body.copy())

        return body
    }

    /** Forgets every cached answer (a test, or a settings change that should show at once). */
    fun invalidate() = cache.clear()

    private fun cacheKey(cfg: MarketConfig, include: Set<String>): String = listOf(
        include.filter { it in SECTIONS }.sorted().joinToString(","), cfg.moduleRecentBuyers, cfg.moduleRecentBuyersCount, cfg.moduleRecentBuyersShowAmount,
        cfg.moduleTopSupporters, cfg.moduleTopSupportersPeriod, cfg.moduleTopSupportersCount, cfg.moduleGoal, cfg.moduleStats, cfg.moduleSidebars.joinToString(","),
        cfg.storeTimeZone
    ).joinToString("|")

    /** Neither an anonymised order nor one of a blocked player (binds: now). */
    private fun hiddenClause() =
        "NOT EXISTS (SELECT 1 FROM ${table("market_order_event")} e WHERE e.`orderId` = o.`id` AND e.`type` = 'PII_ERASED') AND " +
            "NOT EXISTS (SELECT 1 FROM ${table("market_block")} b WHERE (b.`expiresAt` IS NULL OR b.`expiresAt` > ?) AND " +
            "((b.`type` = 'PLAYER' AND b.`value` = LOWER(o.`playerUsername`)) OR (b.`type` = 'USER' AND o.`userId` IS NOT NULL AND o.`userId` = CAST(b.`value` AS UNSIGNED))))"

    // ================================================================================================== recent buyers

    private suspend fun recentBuyers(cfg: MarketConfig, now: Long, client: SqlClient): JsonArray {
        val limit = clampCount(cfg.moduleRecentBuyersCount, 50)
        val rows = client.preparedQuery(
            "SELECT o.`id`, o.`playerUsername`, o.`totalPrice`, o.`currency`, o.`paidAt` FROM ${table("market_order")} o " +
                "WHERE $SALE AND o.`hideFromBroadcast` = 0 AND " +
                "EXISTS (SELECT 1 FROM ${table("market_order_item")} i WHERE i.`orderId` = o.`id` AND i.`kind` IN ('PRODUCT', 'BUNDLE')) AND ${hiddenClause()} " +
                "ORDER BY o.`paidAt` DESC, o.`id` DESC LIMIT ?"
        ).execute(Tuple.of(now, limit)).coAwait().toList()

        if (rows.isEmpty()) return JsonArray()

        val ids = rows.map { it.getLong("id") }
        val names = LinkedHashMap<Long, LinkedHashSet<String>>()

        client.preparedQuery(
            "SELECT `orderId`, `productName` FROM ${table("market_order_item")} WHERE `kind` IN ('PRODUCT', 'BUNDLE') AND `orderId` IN (${ids.joinToString(", ") { "?" }}) ORDER BY `id`"
        ).execute(Tuple.from(ids)).coAwait().forEach { names.getOrPut(it.getLong("orderId")) { LinkedHashSet() }.add(it.getString("productName")) }

        return JsonArray(rows.map { row ->
            val buyer = JsonObject()
                .put("username", row.getString("playerUsername"))
                .put("productNames", JsonArray(names[row.getLong("id")]?.toList() ?: emptyList<String>()))

            if (cfg.moduleRecentBuyersShowAmount) buyer.put("amount", MoneyUtil.toDecimal(row.getLong("totalPrice"))).put("currency", row.getString("currency"))

            buyer.put("createdAt", row.getLong("paidAt"))
        })
    }

    // ================================================================================================== top supporters

    private suspend fun topSupporters(cfg: MarketConfig, now: Long, client: SqlClient): JsonArray {
        val limit = clampCount(cfg.moduleTopSupportersCount, 50)
        val zone = StatsBuckets.zone(cfg.storeTimeZone)
        val args = ArrayList<Any?>()
        var since = ""

        if (cfg.moduleTopSupportersPeriod == TopSupportersPeriod.MONTH) {
            since = " AND o.`paidAt` >= ?"
            args += StatsBuckets.startOfDay(zone, StatsBuckets.today(zone, now).withDayOfMonth(1))
        }

        args += now
        args += limit

        // the total of a buyer is what the orders were worth in the store currency (`fxRate` = order currency per base unit), less what was refunded
        val rows = client.preparedQuery(
            "SELECT SUBSTRING_INDEX(GROUP_CONCAT(o.`playerUsername` ORDER BY o.`paidAt` DESC, o.`id` DESC SEPARATOR '\\n'), '\\n', 1) AS username, " +
                "SUM((o.`totalPrice` - o.`refundedTotal`) / IF(o.`fxRate` > 0, o.`fxRate`, 1)) AS total, MIN(o.`id`) AS firstId " +
                "FROM ${table("market_order")} o WHERE $SALE$since AND ${hiddenClause()} " +
                "GROUP BY COALESCE(NULLIF(o.`buyerKey`, ''), CONCAT('g:', LOWER(o.`playerUsername`))) HAVING total > 0 ORDER BY total DESC, firstId ASC LIMIT ?"
        ).execute(Tuple.from(args)).coAwait()

        var rank = 0

        return JsonArray(rows.map { row ->
            val supporter = JsonObject().put("username", row.getString("username"))

            if (cfg.moduleRecentBuyersShowAmount) supporter.put("total", Math.round(row.getDouble("total")) / 100.0)

            supporter.put("rank", ++rank)
        })
    }

    // ================================================================================================== goals

    private suspend fun goalViews(now: Long, client: SqlClient): JsonArray {
        val zone = StatsBuckets.zone(config().storeTimeZone)

        return JsonArray(
            goals.getActive(client).filter { it.showOnStore && GoalMath.inWindow(it, now) }.map { goal ->
                val money = goal.metric == GoalMetric.REVENUE
                // a periodic goal that has not been advanced since its period ended shows an empty bar (the next order starts the new period)
                val progress = if (GoalMath.stale(goal, zone, now)) 0L else goal.progress
                val percent = if (goal.target <= 0) 0 else BigDecimal(progress).multiply(BigDecimal(100)).divide(BigDecimal(goal.target), 0, RoundingMode.DOWN).min(BigDecimal(100)).toInt()

                JsonObject()
                    .put("id", goal.id).put("name", goal.name).put("description", goal.description).put("metric", goal.metric.name)
                    .put("target", if (money) MoneyUtil.toDecimal(goal.target) else goal.target)
                    .put("progress", if (money) MoneyUtil.toDecimal(progress) else progress)
                    .put("percent", percent).put("currency", goal.currency).put("endsAt", goal.endsAt)
            }
        )
    }

    // ================================================================================================== stats

    private suspend fun stats(cfg: MarketConfig, now: Long, client: SqlClient): JsonObject {
        val zone = StatsBuckets.zone(cfg.storeTimeZone)
        val todayStart = StatsBuckets.startOfDay(zone, StatsBuckets.today(zone, now))
        val orders = client.preparedQuery(
            "SELECT COUNT(*) AS total, COALESCE(SUM(o.`paidAt` >= ?), 0) AS today, " +
                "COUNT(DISTINCT COALESCE(NULLIF(o.`buyerKey`, ''), CONCAT('g:', LOWER(o.`playerUsername`)))) AS customers FROM ${table("market_order")} o WHERE $SALE"
        ).execute(Tuple.of(todayStart)).coAwait().first()
        val products = client.preparedQuery("SELECT COUNT(*) AS cnt FROM ${table("market_product")} WHERE `status` = 'ACTIVE' AND `deletedAt` IS NULL")
            .execute().coAwait().first().getLong("cnt")

        return JsonObject()
            .put("ordersToday", orders.getLong("today")).put("ordersTotal", orders.getLong("total"))
            .put("customersTotal", orders.getLong("customers")).put("productsTotal", products)
    }
}

/**
 * The arithmetic of the community goals (01 section 12), pure so it is tested without a database. What a paid order adds to a goal, what a refund takes
 * back, and when a goal counts an order at all.
 *
 * Rules: a goal counts the order of a moment `at` when it is `ACTIVE`, `startsAt <= at`, `at < endsAt` and, for a `WEEKLY` / `MONTHLY` goal, `at` is not before
 * the current period's `periodStart`. Only lines of kind `PRODUCT` / `BUNDLE` are products (not bundle children, not credit top-ups); `productIds = null` means
 * every product. `ORDERS` adds 1 per order (with a product filter: when a line matches); `PRODUCT_SALES` adds the quantity of the matching lines; `REVENUE`
 * adds the money the gateway received (`gatewayAmount`, in the base currency: divided by `fxRate`), for a product filter the matching share of the goods
 * (lines' `lineTotal`). A refund takes back the same amounts: `REVENUE` the refund's gateway part (same share), `PRODUCT_SALES` the refunded quantities of
 * the matching lines, `ORDERS` one when the order has become fully refunded with this refund.
 */
object GoalMath {
    private val PRODUCT_KINDS = setOf(OrderItemKind.PRODUCT, OrderItemKind.BUNDLE)

    fun productIds(goal: MarketGoal): Set<Long>? =
        goal.productIds?.let { raw -> runCatching { JsonArray(raw).map { (it as Number).toLong() }.toSet() }.getOrNull() }?.takeIf { it.isNotEmpty() }

    /** Whether [at] lies inside `[startsAt, endsAt)` of the goal. */
    fun inWindow(goal: MarketGoal, at: Long): Boolean = (goal.startsAt == null || goal.startsAt <= at) && (goal.endsAt == null || at < goal.endsAt)

    /** The first instant of the period of [period] that holds [at] in [zone]; `null` for a one-time goal. */
    fun periodStart(period: GoalPeriod, zone: ZoneId, at: Long): Long? {
        val day = StatsBuckets.today(zone, at)

        return when (period) {
            GoalPeriod.ONE_TIME -> null
            GoalPeriod.WEEKLY -> StatsBuckets.startOfDay(zone, StatsBuckets.weekStart(day))
            GoalPeriod.MONTHLY -> StatsBuckets.startOfDay(zone, day.withDayOfMonth(1))
        }
    }

    /** A periodic goal whose stored period began before the current one: its progress belongs to a period that is over. */
    fun stale(goal: MarketGoal, zone: ZoneId, now: Long): Boolean {
        val current = periodStart(goal.period, zone, now) ?: return false

        return (goal.periodStart ?: 0L) < current
    }

    /** Does the goal count an order paid at [at] (before looking at what it holds)? */
    fun counts(goal: MarketGoal, zone: ZoneId, at: Long, now: Long): Boolean {
        if (goal.status != "ACTIVE" || !inWindow(goal, at)) return false

        val current = periodStart(goal.period, zone, now) ?: return true

        // a periodic goal counts what was paid since it began and since its current period began
        return at >= current && at >= (goal.periodStart ?: 0L)
    }

    private fun products(items: List<MarketOrderItem>) = items.filter { it.kind in PRODUCT_KINDS }

    private fun matching(goal: MarketGoal, items: List<MarketOrderItem>): List<MarketOrderItem> {
        val ids = productIds(goal)

        return products(items).filter { ids == null || (it.productId != null && it.productId in ids) }
    }

    /** The base-currency amount (x100) of [orderCurrencyAmount]: `fxRate` is order-currency units per base unit. */
    fun toBase(orderCurrencyAmount: Long, fxRate: BigDecimal): Long {
        val rate = if (fxRate.signum() > 0) fxRate else BigDecimal.ONE

        return BigDecimal(orderCurrencyAmount).divide(rate, 0, RoundingMode.HALF_UP).toLong()
    }

    /** The share of [amount] (order currency) that the matching lines hold of the goods; the whole [amount] without a product filter. */
    private fun share(goal: MarketGoal, items: List<MarketOrderItem>, amount: Long): Long {
        if (productIds(goal) == null) return amount

        val goods = products(items).sumOf { it.lineTotal }

        if (goods <= 0) return 0

        val mine = matching(goal, items).sumOf { it.lineTotal }

        return BigDecimal(amount).multiply(BigDecimal(mine)).divide(BigDecimal(goods), 0, RoundingMode.HALF_UP).toLong()
    }

    /** What a paid [order] adds to [goal]. */
    fun paidDelta(goal: MarketGoal, order: MarketOrder, items: List<MarketOrderItem>): Long = when (goal.metric) {
        GoalMetric.ORDERS -> if (matching(goal, items).isNotEmpty() || (productIds(goal) == null)) 1L else 0L
        GoalMetric.PRODUCT_SALES -> matching(goal, items).sumOf { it.quantity.toLong() }
        GoalMetric.REVENUE -> toBase(share(goal, items, order.gatewayAmount), order.fxRate)
    }

    /**
     * What a refund takes back from [goal]: a negative number or 0. [quantities] = refunded quantity per order item (the refund's line deltas),
     * [becameRefunded] = the order reached `REFUNDED` with this refund.
     */
    fun refundDelta(goal: MarketGoal, order: MarketOrder, items: List<MarketOrderItem>, refund: MarketRefund, quantities: Map<Long, Int>, becameRefunded: Boolean): Long = when (goal.metric) {
        GoalMetric.ORDERS -> if (becameRefunded && (productIds(goal) == null || matching(goal, items).isNotEmpty())) -1L else 0L
        GoalMetric.PRODUCT_SALES -> -matching(goal, items).sumOf { (quantities[it.id] ?: 0).toLong() }
        GoalMetric.REVENUE -> -toBase(share(goal, items, refund.gatewayAmount), order.fxRate)
    }
}

/**
 * The writer of the goal progress (21 section 3.4 step 10, 06 section 11 `AdvanceGoalProgress`): `market_goal.progress` is only ever changed by the atomic
 * `progress = progress + delta` of the DAO (never read-modify-write), a periodic goal whose period ended is first reset to the new period, `completedAt` is
 * set once when the target is reached. Goals are a storefront nicety: a failure here is logged and never blocks a payment or a refund. Test orders count for
 * nothing.
 */
class GoalProgress(
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val goals: MarketGoalDao,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao
) {
    private val logger = LoggerFactory.getLogger(GoalProgress::class.java)

    /** `AdvanceGoalProgress` of O2 / O4: adds the order paid now to every goal that counts it. */
    suspend fun onOrderPaid(conn: SqlClient, orderId: Long) = guarded("order $orderId paid") {
        // the row after the StampPaid of the same transition: `testMode`, `gatewayAmount` and `paidAt` are the paid ones
        val order = orders.getById(orderId, conn) ?: return@guarded

        if (order.testMode) return@guarded

        val now = clock.now()
        val items = orderItems.getByOrderIds(listOf(order.id), conn)

        apply(conn, now, order.paidAt ?: now) { goal -> GoalMath.paidDelta(goal, order, items) }
    }

    /** Step 10 of O10: takes the refund back from the goals that counted the order. */
    suspend fun onRefundSucceeded(conn: SqlClient, before: MarketOrder, after: MarketOrder, refund: MarketRefund, quantities: Map<Long, Int>) = guarded("refund ${refund.id}") {
        if (after.testMode) return@guarded

        val now = clock.now()
        val items = orderItems.getByOrderIds(listOf(after.id), conn)
        val became = after.status == com.panomc.plugins.market.util.OrderStatus.REFUNDED && before.status != com.panomc.plugins.market.util.OrderStatus.REFUNDED

        apply(conn, now, after.paidAt ?: after.createdAt) { goal -> GoalMath.refundDelta(goal, after, items, refund, quantities, became) }
    }

    private suspend fun apply(conn: SqlClient, now: Long, at: Long, delta: (MarketGoal) -> Long) {
        val zone = StatsBuckets.zone(config().storeTimeZone)

        for (goal in goals.getActive(conn)) {
            var current = goal

            // a new week / month: the old progress belongs to a finished period. The roll is conditional on the stored periodStart still being older, so of
            // several orders that all saw the finished period only the first resets; the others (their snapshot is stale) change nothing and just add
            GoalMath.periodStart(goal.period, zone, now)?.let { start ->
                if (GoalMath.stale(goal, zone, now)) {
                    goals.rollPeriod(goal.id, start, now, conn)

                    current = rolledTo(goal, start)
                }
            }

            if (!GoalMath.counts(current, zone, at, now)) continue

            val change = delta(current)

            if (change == 0L) continue

            goals.addProgress(current.id, change, now, conn)

            if (change > 0) goals.getById(current.id, conn)?.let { if (it.progress >= it.target) goals.markCompleted(it.id, now, conn) }
        }
    }

    /** The goal as it is once rolled into the period that began at [start] (the stored row is not re-read: its snapshot may predate the roll of a concurrent order). */
    private fun rolledTo(goal: MarketGoal, start: Long) = MarketGoal(
        goal.id, goal.name, goal.description, goal.metric, goal.productIds, goal.target, goal.progress, goal.currency, goal.period, start, goal.startsAt, goal.endsAt,
        goal.status, goal.showOnStore, goal.completedAt, goal.position, goal.createdAt, goal.updatedAt
    )

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("goal progress of {} failed and was skipped: {}", what, e.toString())
        }
    }
}

/** `AdvanceGoalProgress` of the order transitions goes to [GoalProgress]; every other effect to [next]. */
class GoalEffects(
    private val progress: () -> GoalProgress,
    private val next: ForeignEffects = ForeignEffects.PENDING_SLICES
) : ForeignEffects {
    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
        if (effect !is OrderEffect.AdvanceGoalProgress) {
            next.apply(conn, locked, effect)

            return
        }

        progress().onOrderPaid(conn, locked.order.id)
    }
}
