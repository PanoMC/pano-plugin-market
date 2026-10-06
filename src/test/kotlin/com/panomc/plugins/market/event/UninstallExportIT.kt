package com.panomc.plugins.market.event

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The CSV export of the uninstall on a real MariaDB (MK-153; 00 section 8.7, 01 section 13, 17 section 11 `UninstallExportIT`): the files exist, with every row,
 * before the tables are dropped; secrets stay in the database; formulas in text cells are defused; a failing export stops the uninstall.
 */
class UninstallExportIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var dir: File

    @BeforeEach
    fun fresh() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        dir = Files.createTempDirectory("market-export").toFile()
    }

    @AfterEach
    fun clean() {
        dir.deleteRecursively()
    }

    /** The rows of this class are free-form (an order whose total no item explains): the export reads rows as they are, the invariants of the store are not its subject. */
    override suspend fun assertInvariants() {}

    private fun export(pageSize: Int = UninstallExport.PAGE) = UninstallExport(w.clock, { "pano_" }, { pool }, dir, pageSize = pageSize)

    private suspend fun seed(orders: Int) {
        for (i in 1..orders) {
            val id = Fixtures.insertRaw(
                pool, "market_order",
                mapOf(
                    "status" to "COMPLETED", "playerUsername" to if (i == 2) "=HYPERLINK(\"http://evil\")" else "player$i", "totalPrice" to i * 100, "email" to "p$i@example.com", "accessToken" to "secret-token-$i".padEnd(40, 'x'),
                    "publicId" to "EXPORT${i.toString().padStart(14, '0')}", "reservationState" to "COMMITTED", "paidAt" to 1, "paymentMethodId" to "manual", "giftMessage" to "hello, \"world\"\nline 2"
                )
            )

            Fixtures.insertRaw(
                pool, "market_payment",
                mapOf("orderId" to id, "providerId" to "fake", "status" to "SUCCEEDED", "reference" to "REF$i", "token" to "pay-token-$i".padEnd(40, 'y'), "amount" to i * 100, "currency" to "EUR", "providerData" to "ENC-SECRET", "startPayload" to "ENC-PAYLOAD")
            )
        }

        val account = w.fixtures.user("Exp")

        w.fixtures.credit(account, 1_234)
        Fixtures.insertRaw(pool, "market_invoice", mapOf("orderId" to 1, "type" to "INVOICE", "series" to "INV", "sequence" to 1, "number" to "INV-000001"))
    }

    private fun lines(name: String, from: File) = File(from, name).readText().split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun `the export writes one file per table with every row, in pages, and nothing of the secrets`(): Unit = runBlocking {
        seed(5)

        val result = export(pageSize = 2).export()

        assertTrue(result.dir.isDirectory)
        assertTrue(result.dir.name.startsWith("export-"), result.dir.name)
        assertEquals(setOf("orders.csv", "order_items.csv", "payments.csv", "refunds.csv", "refund_items.csv", "disputes.csv", "credit_accounts.csv", "credit_transactions.csv", "credit_entries.csv", "creator_earnings.csv", "creator_payouts.csv", "invoices.csv"), result.rows.keys)

        for ((file, rows) in result.rows) {
            assertTrue(File(result.dir, file).isFile, file)
            // orders.csv has a line break inside a quoted cell, so its rows are counted by the result and by the text below
            if (file != "orders.csv") assertEquals(rows + 1, lines(file, result.dir).size.toLong(), "$file: a header and every row")
        }

        assertEquals(5L, result.rows["orders.csv"])
        assertEquals(5L, result.rows["payments.csv"])
        assertEquals(1L, result.rows["invoices.csv"])
        assertEquals(1L, result.rows["credit_transactions.csv"])
        assertEquals(2L, result.rows["credit_entries.csv"])
        assertEquals(6L, result.rows["credit_accounts.csv"], "five system accounts and the user's")

        assertEquals(0, result.dir.listFiles()!!.count { it.name.endsWith(".part") }, "no half-written file is left")

        // the secrets stay in the database
        val orders = File(result.dir, "orders.csv").readText()
        val payments = File(result.dir, "payments.csv").readText()

        assertFalse(orders.contains("secret-token"))
        assertFalse(orders.contains("accessToken"))
        assertFalse(payments.contains("pay-token"))
        assertFalse(payments.contains("ENC-SECRET"))
        assertFalse(payments.contains("ENC-PAYLOAD"))
        assertTrue(orders.contains("p1@example.com"), "the order e-mail is part of the record")
        assertTrue(orders.startsWith("id,"), orders.take(40))
    }

    @Test
    fun `text cells are quoted and defused against spreadsheet formulas, numbers are not touched`(): Unit = runBlocking {
        seed(3)

        val text = File(export().export().dir, "orders.csv").readText()

        assertTrue(text.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""), "the formula starts with an apostrophe and its quotes are doubled")
        assertTrue(text.contains("\"hello, \"\"world\"\"\nline 2\""), "commas, quotes and line breaks are quoted")
        assertEquals("'=1", UninstallExport.cell("=1"))
        assertEquals("'-5", UninstallExport.cell("-5"))
        assertEquals("-500", UninstallExport.cell(-500L), "a negative number is a number")
        assertEquals("", UninstallExport.cell(null))
        assertEquals("1", UninstallExport.cell(true))
    }

    @Test
    fun `the files exist before the tables are dropped, and a failing export stops the uninstall`(): Unit = runBlocking {
        seed(2)

        var seenAtDrop: String? = null
        var dropped = 0

        val ok = exportThenDrop(export = { export().export() }, drop = {
            dropped++
            seenAtDrop = File(dir.listFiles()!!.single(), "orders.csv").takeIf { it.isFile }?.readText()
        })

        assertEquals(1, dropped)
        assertTrue(seenAtDrop!!.contains("p1@example.com") && seenAtDrop!!.contains("p2@example.com"), "the file was complete when the drop started")
        assertEquals(2L, ok.rows["orders.csv"])

        // an export that cannot read a table: the exception reaches the caller and the drop never runs
        val broken = UninstallExport(w.clock, { "pano_" }, { pool }, dir, tables = listOf(ExportTable("nothing.csv", "market_does_not_exist")))
        var droppedAfterFailure = 0

        assertThrows(Exception::class.java) { runBlocking { exportThenDrop(export = { broken.export() }, drop = { droppedAfterFailure++ }) } }
        assertEquals(0, droppedAfterFailure)
    }

    @Test
    fun `an export that finds a file missing does not let the drop run`(): Unit = runBlocking {
        var dropped = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking { exportThenDrop(export = { ExportResult(dir, mapOf("orders.csv" to 0L)) }, drop = { dropped++ }) }
        }

        assertEquals(0, dropped)
    }

    @Test
    fun `a second export never overwrites the first`(): Unit = runBlocking {
        seed(1)

        val first = export().export()
        val second = export().export()

        assertNotEquals(first.dir, second.dir)
        assertTrue(File(first.dir, "orders.csv").isFile)
        assertTrue(File(second.dir, "orders.csv").isFile)
    }

    @Test
    fun `an empty store exports files with a header only`(): Unit = runBlocking {
        val result = export().export()

        assertEquals(0L, result.rows["orders.csv"])
        assertEquals(1, lines("orders.csv", result.dir).size)
        assertTrue(lines("orders.csv", result.dir).single().startsWith("id,"))
    }
}
