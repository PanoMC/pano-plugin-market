package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.store.FileAppendSink
import com.panomc.plugins.market.mc.core.support.FlakySink
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.spigot.vault.OpKind
import com.panomc.plugins.market.mc.spigot.vault.OpPhase
import com.panomc.plugins.market.mc.spigot.vault.VaultEntry
import com.panomc.plugins.market.mc.spigot.vault.VaultJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** `vault/journal.log`: durable, last line wins, a torn tail is dropped, damage is moved aside loudly, compaction keeps open entries. */
class VaultJournalTest {
    @TempDir
    lateinit var dir: Path

    private val log = TestLog()

    private fun entry(id: String, phase: OpPhase = OpPhase.LEDGER_PENDING, kind: OpKind = OpKind.PROVIDER_WITHDRAW, attempts: Int = 0) =
        VaultEntry(id, kind, "Steve", "11111111-1111-1111-1111-111111111111", 12.5, null, phase, 1_000, 2_000, attempts)

    private fun journal(sinkFactory: (Path) -> com.panomc.plugins.market.mc.core.store.AppendSink = { FileAppendSink(it) }, compactAfter: Int = 200) =
        VaultJournal(dir.resolve("vault"), log, { 5_000 }, sinkFactory, compactAfter)

    private val file get() = dir.resolve("vault").resolve("journal.log")

    @Test
    fun `an entry is durable and comes back after a restart with every field`() {
        val j = journal()
        j.load()
        val e = VaultEntry("a-1", OpKind.CONVERT_TO_CREDITS, "Alex", null, 3.33, 9.99, OpPhase.REFUND_PENDING, 10, 20, 4, null, "NO_ACCOUNT")
        assertTrue(j.write(e))
        j.close()
        val back = journal().load()
        assertEquals(listOf(e), back)
    }

    @Test
    fun `the last line of an id wins and a closed entry is not loaded again`() {
        val j = journal()
        j.load()
        j.write(entry("a"))
        j.write(entry("a", OpPhase.UNDO_PENDING, attempts = 2))
        j.write(entry("b"))
        j.write(entry("b", OpPhase.CLOSED).copy(closedReason = "APPLIED"))
        j.close()
        val back = journal().load()
        assertEquals(1, back.size)
        assertEquals(OpPhase.UNDO_PENDING, back.single().phase)
        assertEquals(2, back.single().attempts)
    }

    @Test
    fun `a line torn by a crash at the very end is ignored and the entries before it survive`() {
        val j = journal()
        j.load()
        j.write(entry("a"))
        j.write(entry("b"))
        j.close()
        Files.write(file, Files.readAllBytes(file) + "{\"id\":\"c\",\"kind\":\"PRO".toByteArray())
        val back = journal().load()
        assertEquals(listOf("a", "b"), back.map { it.id })
        assertTrue(log.has("cut off"))
        // the file was rewritten clean: nothing odd is left for the next start
        assertEquals(2, Files.readAllLines(file).size)
    }

    @Test
    fun `a damaged line in the middle moves the file aside and starts a new journal, loudly`() {
        val j = journal()
        j.load()
        j.write(entry("a"))
        j.write(entry("b"))
        j.close()
        val lines = Files.readAllLines(file).toMutableList()
        lines.add(1, "this is not json")
        Files.write(file, (lines.joinToString("\n") + "\n").toByteArray())
        val fresh = journal()
        assertTrue(fresh.load().isEmpty())
        assertTrue(log.has("ERROR The Vault journal journal.log is damaged"))
        val aside = Files.list(dir.resolve("vault")).use { s -> s.map { it.fileName.toString() }.toList() }
        assertTrue(aside.any { it.startsWith("journal.corrupt-") }, "the damaged file is kept for the admin: $aside")
        // and it keeps working
        assertTrue(fresh.write(entry("z")))
        fresh.close()
        assertEquals(listOf("z"), journal().load().map { it.id })
    }

    @Test
    fun `a write that fails reports false, keeps nothing and leaves a journal that still parses`() {
        var sink: FlakySink? = null
        val j = journal({ p -> FlakySink(FileAppendSink(p)).also { sink = it } })
        j.load()
        assertTrue(j.write(entry("a")))
        sink!!.failNextAppend = true // half of the line reaches the disk, then the write throws
        assertFalse(j.write(entry("b")))
        assertNull(j.get("b"))
        assertTrue(log.has("could not be written"))
        assertTrue(j.write(entry("c")))
        j.close()
        assertEquals(listOf("a", "c"), journal().load().map { it.id })
    }

    @Test
    fun `a failing write of an update keeps the previous state of that entry`() {
        var sink: FlakySink? = null
        val j = journal({ p -> FlakySink(FileAppendSink(p)).also { sink = it } })
        j.load()
        j.write(entry("a"))
        sink!!.failAppends = true
        assertFalse(j.write(entry("a", OpPhase.UNDO_PENDING)))
        assertEquals(OpPhase.LEDGER_PENDING, j.get("a")!!.phase)
        j.close()
        assertEquals(OpPhase.LEDGER_PENDING, journal().load().single().phase)
    }

    @Test
    fun `compaction drops closed entries and keeps every open one`() {
        val j = journal(compactAfter = 5)
        j.load()
        j.write(entry("keep-1"))
        j.write(entry("keep-2", OpPhase.UNDO_PENDING))
        for (i in 1..12) {
            j.write(entry("done-$i"))
            j.write(entry("done-$i", OpPhase.CLOSED).copy(closedReason = "APPLIED"))
        }
        j.close()
        val lines = Files.readAllLines(file)
        assertTrue(lines.size < 20, "the file was compacted (${lines.size} lines)")
        assertEquals(setOf("keep-1", "keep-2"), journal().load().map { it.id }.toSet())
    }

    @Test
    fun `get and openEntries describe what is open right now`() {
        val j = journal()
        j.load()
        j.write(entry("a"))
        j.write(entry("b"))
        j.write(entry("a", OpPhase.CLOSED))
        assertNull(j.get("a"))
        assertNotNull(j.get("b"))
        assertEquals(listOf("b"), j.openEntries().map { it.id })
    }

    @Test
    fun `the undo id of an entry is its id with the undo suffix`() {
        assertEquals("abc:undo", entry("abc").undoId)
    }

    @Test
    fun `the directory is created on the first load and a missing file is an empty journal`() {
        val j = journal()
        assertTrue(j.load().isEmpty())
        assertTrue(Files.isDirectory(dir.resolve("vault")))
    }
}
