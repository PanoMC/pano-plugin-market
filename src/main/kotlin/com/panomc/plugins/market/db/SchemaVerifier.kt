package com.panomc.plugins.market.db

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient

/**
 * Compares the live database with what [MarketSchema] declares (01 section 14.1 rule 3): every table (as a base table,
 * a view of the same name does not count), every column with its `DATA_TYPE`, `CHARACTER_MAXIMUM_LENGTH` and
 * nullability (so a failed `MODIFY status VARCHAR(24)` is caught), every index with its columns and uniqueness (the
 * unique keys of 00 section 8.1 among them), and one `COUNT` per fixup predicate (non-zero = unfixed rows).
 *
 * Objects the schema does not declare (extra columns, extra indexes, extra tables) are never reported. Never throws
 * for a database error either: a failed query is a [Kind.CHECK_FAILED] finding, because "could not verify" must not
 * read as "verified".
 */
object SchemaVerifier {
    enum class Kind {
        /** No base table of that name (it may be a view). */
        MISSING_TABLE,

        MISSING_COLUMN,

        /** Column exists but its type, length or nullability differs. */
        COLUMN_MISMATCH,

        /** No index of that name. */
        MISSING_INDEX,

        /** Index exists with other columns or other uniqueness. */
        INDEX_MISMATCH,

        /** A verification query itself failed. */
        CHECK_FAILED
    }

    /**
     * One problem. [target] names the object exactly: `pano_market_coupon` (table),
     * `pano_market_coupon.usedCount` (column) or `pano_market_coupon#unique_code` (index).
     */
    data class Finding(val kind: Kind, val target: String, val detail: String) {
        override fun toString() = "$kind $target: $detail"
    }

    /** The verdict: [findings] plus the [unfixed] row counts per fixup id (only non-zero ones). */
    class Result(val findings: List<Finding>, val unfixed: Map<String, Long>) {
        val ok: Boolean get() = findings.isEmpty() && unfixed.isEmpty()

        fun missingTables(): List<String> = findings.filter { it.kind == Kind.MISSING_TABLE }.map { it.target }

        /** One human line per problem, for the log and `GET /health`. */
        fun describe(): List<String> = findings.map { it.toString() } + unfixed.map { "UNFIXED ${it.key}: ${it.value} row(s)" }
    }

    /** Verifies the declared tables and fixups against the current database of [client]. */
    suspend fun verify(client: SqlClient, prefix: String): Result =
        verify(client, prefix, MarketSchema.tables, MarketSchema.fixups())

    suspend fun verify(
        client: SqlClient,
        prefix: String,
        tables: List<MarketSchema.Table>,
        fixups: List<MarketSchema.Fixup>
    ): Result {
        val findings = ArrayList<Finding>()
        val unfixed = LinkedHashMap<String, Long>()

        try {
            val actual = readActual(client)
            for (table in tables) {
                val physical = table.physicalName(prefix)
                if (physical !in actual.baseTables) {
                    findings += Finding(Kind.MISSING_TABLE, physical, "no base table of that name")
                    continue
                }
                compareColumns(table, physical, actual, findings)
                compareKeys(table, physical, actual, findings)
            }
        } catch (e: Exception) {
            findings += Finding(Kind.CHECK_FAILED, "information_schema", e.message ?: e.javaClass.simpleName)
        }

        for (fixup in fixups) {
            if (fixup.pendingSql == null) continue
            try {
                if (!MarketSchema.tablesExist(client, prefix, fixup.requires)) continue
                val count = MarketSchema.pendingCount(client, fixup, prefix)
                if (count > 0) unfixed[fixup.id] = count
            } catch (e: Exception) {
                findings += Finding(Kind.CHECK_FAILED, "fixup:${fixup.id}", e.message ?: e.javaClass.simpleName)
            }
        }

        return Result(findings, unfixed)
    }

    private class ActualColumn(val dataType: String, val charLength: Long?, val nullable: Boolean) {
        override fun toString() = dataType + (charLength?.let { "($it)" } ?: "") + if (nullable) " NULL" else " NOT NULL"
    }

    private class ActualKey(val columns: List<String>, val unique: Boolean)

    private class Actual(
        val baseTables: Set<String>,
        val columns: Map<String, Map<String, ActualColumn>>,
        val keys: Map<String, Map<String, ActualKey>>
    )

    private suspend fun readActual(client: SqlClient): Actual {
        val tables = client.query(
            "SELECT TABLE_NAME AS name FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'"
        ).execute().coAwait().map { it.getString("name") }.toSet()

        val columns = HashMap<String, MutableMap<String, ActualColumn>>()
        client.query(
            "SELECT TABLE_NAME AS t, COLUMN_NAME AS c, DATA_TYPE AS d, CHARACTER_MAXIMUM_LENGTH AS l, IS_NULLABLE AS n " +
                "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
        ).execute().coAwait().forEach { row ->
            columns.getOrPut(row.getString("t")) { HashMap() }[row.getString("c")] = ActualColumn(
                row.getString("d").lowercase(),
                (row.getValue("l") as? Number)?.toLong(),
                row.getString("n") == "YES"
            )
        }

        val keyColumns = HashMap<String, HashMap<String, MutableList<Pair<Int, String>>>>()
        val keyUnique = HashMap<String, HashMap<String, Boolean>>()
        client.query(
            "SELECT TABLE_NAME AS t, INDEX_NAME AS i, SEQ_IN_INDEX AS s, COLUMN_NAME AS c, NON_UNIQUE AS nu " +
                "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()"
        ).execute().coAwait().forEach { row ->
            val t = row.getString("t")
            val i = row.getString("i")
            keyColumns.getOrPut(t) { HashMap() }.getOrPut(i) { ArrayList() }
                .add((row.getValue("s") as Number).toInt() to row.getString("c"))
            keyUnique.getOrPut(t) { HashMap() }[i] = (row.getValue("nu") as Number).toInt() == 0
        }
        val keys = keyColumns.mapValues { (t, byIndex) ->
            byIndex.mapValues { (i, cols) -> ActualKey(cols.sortedBy { it.first }.map { it.second }, keyUnique.getValue(t).getValue(i)) }
        }

        return Actual(tables, columns, keys)
    }

    private fun compareColumns(table: MarketSchema.Table, physical: String, actual: Actual, out: MutableList<Finding>) {
        val have = actual.columns[physical].orEmpty()
        for (column in table.columns) {
            val found = have[column.name]
            if (found == null) {
                out += Finding(Kind.MISSING_COLUMN, "$physical.${column.name}", "expected ${column.type}")
                continue
            }
            val expected = column.dataType + (column.charLength?.let { "($it)" } ?: "") +
                if (column.nullable) " NULL" else " NOT NULL"
            val sameLength = column.charLength == null || column.charLength == found.charLength
            if (found.dataType != column.dataType || !sameLength || found.nullable != column.nullable) {
                out += Finding(Kind.COLUMN_MISMATCH, "$physical.${column.name}", "expected $expected, found $found")
            }
        }
    }

    private fun compareKeys(table: MarketSchema.Table, physical: String, actual: Actual, out: MutableList<Finding>) {
        val have = actual.keys[physical].orEmpty()
        for (key in table.keys) {
            val found = have[key.name]
            if (found == null) {
                out += Finding(Kind.MISSING_INDEX, "$physical#${key.name}", "expected ${describe(key.columns, key.unique)}")
            } else if (found.columns != key.columns || found.unique != key.unique) {
                out += Finding(
                    Kind.INDEX_MISMATCH,
                    "$physical#${key.name}",
                    "expected ${describe(key.columns, key.unique)}, found ${describe(found.columns, found.unique)}"
                )
            }
        }
    }

    private fun describe(columns: List<String>, unique: Boolean) =
        (if (unique) "unique " else "") + columns.joinToString(",", "(", ")")
}
