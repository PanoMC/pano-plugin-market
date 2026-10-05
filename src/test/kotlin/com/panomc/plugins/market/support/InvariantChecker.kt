package com.panomc.plugins.market.support

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool

/** A violated global invariant (17 sections 7 and 14): [id] is the invariant (`I5`), [rows] the offending rows. */
class InvariantViolation(val id: String, val rows: List<String>) :
    AssertionError("invariant $id violated by ${rows.size} row(s): ${rows.take(10)}")

/**
 * Skeleton of the global invariants of 17 section 7 (MK-012). The real checker (MK-032) adds I1 to I22 with their
 * self-test; this one fixes the shape the database test bases already call through `LateBound`:
 * `assertAll(pool)` and the legacy-aware `assertAll(pool, legacy)`.
 *
 * Every check is one query that must return zero rows; the table placeholders are written without the prefix
 * (`{market_product}`). A check whose tables do not exist yet in the database is skipped and reported through
 * [lastRun]; it is the schema slices that make the tables exist, and `MarketSchemaIT` asserts them. So far only
 * I5 (stock never negative) is implemented, in its two forms (product, variant).
 */
object InvariantChecker {
    class Check(val id: String, val tables: List<String>, val sql: String)

    val checks: List<Check> = listOf(
        Check("I5", listOf("market_product"), "SELECT `id` FROM `{market_product}` WHERE `stock` < 0"),
        Check("I5", listOf("market_product_variant"), "SELECT `id` FROM `{market_product_variant}` WHERE `stock` < 0")
    )

    /** What the most recent `assertAll` did: the ids of the checks that ran and the ones skipped for a missing table. */
    data class Run(val ran: List<String>, val skipped: List<String>)

    @Volatile
    var lastRun: Run = Run(emptyList(), emptyList())
        private set

    suspend fun assertAll(pool: Pool) = assertAll(pool, false)

    /** [legacy] = the database may still carry rows of a version-2 install (the legacy-aware forms of I6, I11, I17, MK-032). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun assertAll(pool: Pool, legacy: Boolean) {
        val prefix = MarketTestDb.TABLE_PREFIX
        val existing = pool.query("SELECT `table_name` AS t FROM information_schema.tables WHERE table_schema = DATABASE()")
            .execute().coAwait().map { it.getString("t").lowercase() }.toSet()
        val ran = ArrayList<String>()
        val skipped = ArrayList<String>()
        val violations = ArrayList<InvariantViolation>()
        for (check in checks) {
            val names = check.tables.associateWith { "$prefix$it" }
            if (names.values.any { it.lowercase() !in existing }) {
                skipped += check.id
                continue
            }
            var statement = check.sql
            names.forEach { (short, full) -> statement = statement.replace("{$short}", full) }
            val rows = pool.query(statement).execute().coAwait().map { row ->
                (0 until row.size()).joinToString(",") { i -> row.getValue(i)?.toString() ?: "null" }
            }
            ran += check.id
            if (rows.isNotEmpty()) violations += InvariantViolation(check.id, rows)
        }
        lastRun = Run(ran, skipped)
        if (violations.isNotEmpty()) {
            throw violations.first().also { first -> violations.drop(1).forEach { first.addSuppressed(it) } }
        }
    }
}
