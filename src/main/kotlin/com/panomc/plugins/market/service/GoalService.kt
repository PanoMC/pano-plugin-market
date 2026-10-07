package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketGoalDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.model.GoalMetric
import com.panomc.plugins.market.db.model.GoalPeriod
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.tx.MarketDb
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Store goals of the panel (04 section 5, 01 section 12): CRUD with validation. A goal is a target for revenue (money, x100
 * in the store currency), the number of orders or the number of product sales, optionally limited to a set of products and
 * to a window; `progress` is maintained elsewhere by the atomic `+delta` of the DAO and is never written here, except that a
 * change of what is measured (metric, product set, currency, period) starts a new period (`progress = 0`), because the old
 * count would describe something else.
 */
class GoalService(
    private val db: MarketDb,
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val goals: MarketGoalDao,
    private val products: MarketProductDao
) {
    /** A goal with its progress as a whole percent, capped at 100. */
    class GoalView(val goal: MarketGoal, val percent: Int)

    suspend fun list(): List<GoalView> = db.tx { conn -> goals.getAll(conn).map(::view) }

    suspend fun get(id: Long): GoalView = db.tx { conn -> view(goals.getById(id, conn) ?: throw NotFound()) }

    /** Keys of [data]: `name*`, `description`, `metric*`, `productIds`, `target*`, `period`, `startsAt`, `endsAt`, `status`, `showOnStore`, `position`. */
    suspend fun create(data: JsonObject): MarketGoal = db.tx { conn ->
        val now = clock.now()
        val errors = linkedMapOf<String, String>()
        val parsed = GoalRules.parse(data, null, config().currency, errors)

        if (parsed.name.isBlank()) errors["name"] = "REQUIRED"
        if (!data.containsKey("metric")) errors["metric"] = "REQUIRED"
        if (!data.containsKey("target")) errors["target"] = "REQUIRED"

        checkProducts(parsed.productIds, errors, conn)

        if (errors.isNotEmpty()) throw fieldErrors(errors)

        val position = if (data.containsKey("position")) parsed.position else goals.getAll(conn).maxOfOrNull { it.position + 1 } ?: 0
        val goal = MarketGoal(
            name = parsed.name, description = parsed.description, metric = parsed.metric, productIds = parsed.productIds?.encode(),
            target = parsed.target, currency = parsed.currency, period = parsed.period,
            periodStart = if (parsed.period == GoalPeriod.ONE_TIME) null else parsed.startsAt ?: now, startsAt = parsed.startsAt,
            endsAt = parsed.endsAt, status = parsed.status, showOnStore = parsed.showOnStore, position = position,
            createdAt = now, updatedAt = now
        )

        val id = goals.add(goal, conn)

        goals.getById(id, conn)!!
    }

    /** A partial update: keys that were not sent keep their stored value. */
    suspend fun update(id: Long, data: JsonObject): MarketGoal = db.tx { conn ->
        val now = clock.now()
        val base = goals.getById(id, conn) ?: throw NotFound()
        val errors = linkedMapOf<String, String>()
        val parsed = GoalRules.parse(data, base, config().currency, errors)

        if (data.containsKey("name") && parsed.name.isBlank()) errors["name"] = "REQUIRED"

        val moneyKindChanged = (base.metric == GoalMetric.REVENUE) != (parsed.metric == GoalMetric.REVENUE)
        if (moneyKindChanged && !data.containsKey("target")) errors["target"] = "REQUIRED"

        if (data.containsKey("productIds")) checkProducts(parsed.productIds, errors, conn)

        if (errors.isNotEmpty()) throw fieldErrors(errors)

        val encodedProducts = parsed.productIds?.encode()

        goals.update(
            MarketGoal(
                id = id, name = parsed.name, description = parsed.description, metric = parsed.metric, productIds = encodedProducts,
                target = parsed.target, currency = parsed.currency, period = parsed.period, startsAt = parsed.startsAt,
                endsAt = parsed.endsAt, status = parsed.status, showOnStore = parsed.showOnStore, position = parsed.position, updatedAt = now
            ),
            conn
        )

        val measuresSomethingElse = base.metric != parsed.metric || base.productIds != encodedProducts ||
            base.currency != parsed.currency || base.period != parsed.period

        if (measuresSomethingElse) goals.resetPeriod(id, parsed.startsAt ?: now, now, conn)

        goals.getById(id, conn)!!
    }

    suspend fun delete(id: Long): String = db.tx { conn ->
        val goal = goals.getById(id, conn) ?: throw NotFound()

        goals.delete(id, conn)

        goal.name
    }

    private suspend fun checkProducts(ids: JsonArray?, errors: MutableMap<String, String>, conn: io.vertx.sqlclient.SqlClient) {
        if (ids == null || errors.containsKey("productIds")) return

        val wanted = ids.map { (it as Number).toLong() }
        val live = products.getByIds(wanted, conn).filter { it.deletedAt == null }.map { it.id }.toSet()

        wanted.forEachIndexed { index, id -> if (id !in live) errors["productIds.$index"] = "NOT_FOUND" }
    }

    private fun view(goal: MarketGoal): GoalView {
        val percent = if (goal.target <= 0) 0 else BigDecimal(goal.progress).multiply(BigDecimal(100))
            .divide(BigDecimal(goal.target), 0, RoundingMode.DOWN).min(BigDecimal(100)).toInt()

        return GoalView(goal, percent)
    }

    private fun fieldErrors(errors: Map<String, String>) = BadRequest(extras = mapOf("fieldErrors" to errors))
}

/** Validation of a goal request (01 section 12): pure, no database. */
object GoalRules {
    const val MAX_PRODUCTS = 200
    const val MAX_COUNT_TARGET = 1_000_000_000L

    /** Revenue target cap in minor units (x100): 100 000 000 000.00. */
    const val MAX_REVENUE_TARGET = 10_000_000_000_000L

    val STATUSES = setOf("ACTIVE", "INACTIVE")

    class Parsed(
        val name: String,
        val description: String?,
        val metric: GoalMetric,
        /** Positive product ids, `null` = every product. */
        val productIds: JsonArray?,
        val target: Long,
        val currency: String?,
        val period: GoalPeriod,
        val startsAt: Long?,
        val endsAt: Long?,
        val status: String,
        val showOnStore: Boolean,
        val position: Int
    )

    /** Lays the keys of [data] over [base]; a bad value goes to [errors] under its key and the stored value stays. */
    fun parse(data: JsonObject, base: MarketGoal?, baseCurrency: String, errors: MutableMap<String, String>): Parsed {
        fun has(key: String) = data.containsKey(key)

        var name = base?.name ?: ""
        if (has("name")) {
            val v = data.getValue("name")

            if (v is String) {
                if (v.trim().length <= 255) name = v.trim() else errors["name"] = "TOO_LONG"
            } else {
                errors["name"] = "INVALID"
            }
        }

        var description = base?.description
        if (has("description")) {
            val v = data.getValue("description")

            if (v == null) description = null
            else if (v is String && v.length <= 512) description = v.ifBlank { null }
            else errors["description"] = "INVALID"
        }

        var metric = base?.metric ?: GoalMetric.REVENUE
        if (has("metric")) {
            val v = (data.getValue("metric") as? String)?.let { s -> GoalMetric.entries.firstOrNull { it.name == s } }

            if (v != null) metric = v else errors["metric"] = "INVALID"
        }

        var productIds = base?.productIds?.let { runCatching { JsonArray(it) }.getOrNull() }
        if (has("productIds")) {
            val v = data.getValue("productIds")

            if (v == null) {
                productIds = null
            } else if (v is JsonArray) {
                val ids = v.map { (it as? Number)?.toLong() }
                val bad = ids.any { it == null || it < 1 } || ids.size != ids.toSet().size

                if (v.size() > MAX_PRODUCTS) errors["productIds"] = "TOO_MANY"
                else if (bad) errors["productIds"] = "INVALID"
                else productIds = if (v.isEmpty) null else JsonArray(ids)
            } else {
                errors["productIds"] = "INVALID"
            }
        }

        var target = base?.target ?: 0L
        if (has("target")) {
            val t = parseTarget(data.getValue("target"), metric)

            if (t != null) target = t else errors["target"] = "INVALID"
        }

        var period = base?.period ?: GoalPeriod.ONE_TIME
        if (has("period")) {
            val v = (data.getValue("period") as? String)?.let { s -> GoalPeriod.entries.firstOrNull { it.name == s } }

            if (v != null) period = v else errors["period"] = "INVALID"
        }

        var startsAt = base?.startsAt
        if (has("startsAt")) {
            val v = data.getValue("startsAt")

            if (v == null) startsAt = null
            else if (v is Number && v.toLong() >= 0) startsAt = v.toLong()
            else errors["startsAt"] = "INVALID"
        }

        var endsAt = base?.endsAt
        if (has("endsAt")) {
            val v = data.getValue("endsAt")

            if (v == null) endsAt = null
            else if (v is Number && v.toLong() >= 0) endsAt = v.toLong()
            else errors["endsAt"] = "INVALID"
        }

        if (!errors.containsKey("startsAt") && !errors.containsKey("endsAt") && startsAt != null && endsAt != null && endsAt <= startsAt) {
            errors["endsAt"] = "BEFORE_START"
        }

        var status = base?.status ?: "ACTIVE"
        if (has("status")) {
            val v = data.getValue("status")

            if (v is String && v in STATUSES) status = v else errors["status"] = "INVALID"
        }

        var showOnStore = base?.showOnStore ?: true
        if (has("showOnStore")) {
            val v = CategoryRules.asBoolean(data.getValue("showOnStore"))

            if (v != null) showOnStore = v else errors["showOnStore"] = "INVALID"
        }

        var position = base?.position ?: 0
        if (has("position")) {
            val v = CategoryRules.asLong(data.getValue("position"))

            if (v != null && v in 0..1_000_000) position = v.toInt() else errors["position"] = "INVALID"
        }

        val currency = if (metric == GoalMetric.REVENUE) baseCurrency else null

        return Parsed(name, description, metric, productIds, target, currency, period, startsAt, endsAt, status, showOnStore, position)
    }

    /** `REVENUE`: a positive decimal in major units (at most 2 places), stored x100; otherwise a positive whole number. */
    fun parseTarget(raw: Any?, metric: GoalMetric): Long? {
        val decimal = when (raw) {
            is Int -> BigDecimal(raw)
            is Long -> BigDecimal(raw)
            is Double -> if (raw.isFinite()) BigDecimal.valueOf(raw) else return null
            is String -> raw.trim().toBigDecimalOrNull() ?: return null
            else -> return null
        }

        if (decimal.signum() <= 0) return null

        return if (metric == GoalMetric.REVENUE) {
            if (decimal.stripTrailingZeros().scale() > 2) return null

            val minor = decimal.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).toBigInteger()

            if (minor.bitLength() > 62) null else minor.toLong().takeIf { it <= MAX_REVENUE_TARGET }
        } else {
            if (decimal.stripTrailingZeros().scale() > 0) return null

            decimal.toBigInteger().let { if (it.bitLength() > 62) null else it.toLong().takeIf { n -> n <= MAX_COUNT_TARGET } }
        }
    }
}
