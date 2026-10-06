package com.panomc.plugins.market.event

import com.panomc.plugins.market.core.time.Clock
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One CSV file of the export: the [file] it becomes, the [table] it is read from, the columns that never leave the database ([exclude]). */
class ExportTable(val file: String, val table: String, val exclude: Set<String> = emptySet())

/** What was written: the [dir] and the number of rows per file name. */
class ExportResult(val dir: File, val rows: Map<String, Long>)

/**
 * The CSV export that runs before the tables are dropped (00 section 8.7, 01 section 13): orders (with their items), payments, refunds (with their items),
 * disputes, the credit ledger (accounts, transactions, entries), the creator earnings and payouts, and the invoices, one file each in
 * `<dataDir>/export-<yyyyMMdd-HHmmss>/`. Rows are read in pages by id, so the export of a large store does not hold a table in memory.
 *
 * Secrets stay in the database: access tokens, payment tokens, encrypted gateway payloads and request hashes are left out ([TABLES]). A text value that
 * starts with `=`, `+`, `-`, `@` or a control character is prefixed with an apostrophe (spreadsheet formula injection); numbers are written as they are.
 * Every file is written under a temporary name and moved into place when complete, so a file that exists is a whole file; the export fails as a whole
 * (the exception reaches the caller) when any file cannot be written.
 */
class UninstallExport(
    private val clock: Clock,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient,
    private val dataDir: File,
    private val tables: List<ExportTable> = TABLES,
    private val pageSize: Int = PAGE
) {
    suspend fun export(): ExportResult {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(clock.now()))
        var dir = File(dataDir, "export-$stamp")
        var n = 1

        // a second export in the same second (or an interrupted one) never overwrites an earlier one
        while (dir.exists()) dir = File(dataDir, "export-$stamp-${++n}")

        withContext(Dispatchers.IO) { Files.createDirectories(dir.toPath()) }

        val counts = LinkedHashMap<String, Long>()

        for (spec in tables) counts[spec.file] = writeTable(dir, spec)

        return ExportResult(dir, counts)
    }

    private suspend fun writeTable(dir: File, spec: ExportTable): Long {
        val target = File(dir, spec.file)
        val temp = File(dir, spec.file + ".part")
        var rows = 0L
        var lastId = 0L
        var header: List<String>? = null

        withContext(Dispatchers.IO) { temp.bufferedWriter(StandardCharsets.UTF_8) }.use { out ->
            while (true) {
                val page = client().preparedQuery("SELECT * FROM `${prefix()}${spec.table}` WHERE `id` > ? ORDER BY `id` LIMIT $pageSize").execute(Tuple.of(lastId)).coAwait()
                val names = page.columnsNames().filter { it !in spec.exclude }

                if (header == null) {
                    header = names

                    withContext(Dispatchers.IO) { out.write(names.joinToString(",") { cell(it) } + "\r\n") }
                }

                if (page.size() == 0) break

                val chunk = StringBuilder()

                for (row in page) {
                    chunk.append(names.joinToString(",") { cell(row.getValue(it)) }).append("\r\n")
                    lastId = row.getLong("id")
                    rows++
                }

                withContext(Dispatchers.IO) { out.write(chunk.toString()) }

                if (page.size() < pageSize) break
            }
        }

        withContext(Dispatchers.IO) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }

        return rows
    }

    companion object {
        const val PAGE = 1000

        val TABLES: List<ExportTable> = listOf(
            ExportTable("orders.csv", "market_order", setOf("accessToken", "idempotencyHash")),
            ExportTable("order_items.csv", "market_order_item"),
            ExportTable("payments.csv", "market_payment", setOf("token", "startPayload", "providerData")),
            ExportTable("refunds.csv", "market_refund", setOf("idempotencyHash")),
            ExportTable("refund_items.csv", "market_refund_item"),
            ExportTable("disputes.csv", "market_dispute"),
            ExportTable("credit_accounts.csv", "market_credit_account"),
            ExportTable("credit_transactions.csv", "market_credit_tx"),
            ExportTable("credit_entries.csv", "market_credit_entry"),
            ExportTable("creator_earnings.csv", "market_creator_earning"),
            ExportTable("creator_payouts.csv", "market_creator_payout", setOf("idempotencyHash")),
            ExportTable("invoices.csv", "market_invoice")
        )

        /** One CSV cell: `null` is empty, a number or boolean is itself, text is quoted when needed and defused against formulas. */
        internal fun cell(value: Any?): String = when (value) {
            null -> ""
            is Boolean -> if (value) "1" else "0"
            is Number -> value.toString()
            else -> {
                val text = value.toString()
                val defused = if (text.isNotEmpty() && (text[0] in "=+-@" || text[0] == '\t' || text[0] == '\r')) "'$text" else text

                if (defused.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + defused.replace("\"", "\"\"") + "\"" else defused
            }
        }
    }
}

/**
 * The order of an uninstall (00 section 8.7): the export first, then the tables are dropped. A failing export aborts the uninstall (the exception
 * propagates, [drop] never runs, the tables stay): the financial records are never dropped without a copy. [drop] runs only when the export directory
 * holds a file for every table.
 */
internal suspend fun exportThenDrop(export: suspend () -> ExportResult, drop: suspend () -> Unit): ExportResult {
    val result = export()
    val missing = result.rows.keys.filter { !File(result.dir, it).isFile }

    check(missing.isEmpty()) { "the export is incomplete, the tables are not dropped: missing ${missing.joinToString()}" }

    drop()

    return result
}
