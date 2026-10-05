package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.store.FileAppendSink
import com.panomc.plugins.market.mc.core.support.FlakySink
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.spigot.vault.JournalFs
import com.panomc.plugins.market.mc.spigot.vault.OpKind
import com.panomc.plugins.market.mc.spigot.vault.OpPhase
import com.panomc.plugins.market.mc.spigot.vault.VaultEntry
import com.panomc.plugins.market.mc.spigot.vault.RealJournalFs
import com.panomc.plugins.market.mc.spigot.vault.VaultJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
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
    fun `a damaged line in the middle is skipped loudly, every valid entry survives and a copy of the damaged file is kept`() {
        val j = journal()
        j.load()
        j.write(entry("a"))
        j.write(entry("b"))
        j.close()
        val lines = Files.readAllLines(file).toMutableList()
        lines.add(1, "this is not json")
        Files.write(file, (lines.joinToString("\n") + "\n").toByteArray())
        val damaged = Files.readAllBytes(file)
        val fresh = journal()
        assertEquals(listOf("a", "b"), fresh.load().map { it.id }, "one unreadable line must not make the journal forget the money operations around it")
        assertTrue(log.has("ERROR The Vault journal journal.log has 1 unreadable line"), log.lines.toString())
        assertTrue(log.has("line 2"))
        val aside = Files.list(dir.resolve("vault")).use { s -> s.map { it.fileName.toString() }.toList() }
        val copy = aside.single { it.startsWith("journal.corrupt-") }
        assertTrue(damaged.contentEquals(Files.readAllBytes(dir.resolve("vault").resolve(copy))), "the copy is the damaged file as it was, for the admin")
        assertEquals(2, Files.readAllLines(file).size, "the live file was rewritten without the bad line")
        // and it keeps working
        assertTrue(fresh.write(entry("z")))
        fresh.close()
        assertEquals(listOf("a", "b", "z"), journal().load().map { it.id })
    }

    @Test
    fun `a file of nothing but unreadable lines loads empty, keeps a copy and keeps working`() {
        Files.createDirectories(file.parent)
        Files.write(file, "garbage one\ngarbage two\n".toByteArray())
        val j = journal()
        assertTrue(j.load().isEmpty())
        assertTrue(log.has("ERROR The Vault journal journal.log has 2 unreadable line"))
        assertTrue(Files.list(dir.resolve("vault")).use { s -> s.anyMatch { it.fileName.toString().startsWith("journal.corrupt-") } })
        assertTrue(j.write(entry("z")))
        j.close()
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

    /** Makes every compaction fail (the temp file cannot be created) the way a full disk would. */
    private fun blockCompaction(): Path {
        val tmp = dir.resolve("vault").resolve("journal.log.tmp")
        Files.createDirectories(tmp.resolve("occupied"))
        return tmp
    }

    private fun unblockCompaction(tmp: Path) {
        Files.delete(tmp.resolve("occupied"))
        Files.delete(tmp)
    }

    @Test
    fun `a write that fails halfway is cut off again, so the next write is not glued onto the fragment even when compaction fails too`() {
        var sink: FlakySink? = null
        val j = journal({ p -> FlakySink(FileAppendSink(p)).also { sink = it } })
        j.load()
        assertTrue(j.write(entry("a")))
        assertTrue(j.write(entry("b")))
        val tmp = blockCompaction() // a disk that is full for everything but the next few bytes
        sink!!.failNextAppend = true // half of the line reaches the disk, then the write throws
        assertFalse(j.write(entry("c")))
        assertTrue(j.write(entry("d")), "the journal is usable again")
        j.close()
        assertEquals(listOf("a", "b", "d"), journal().load().map { it.id }, "the earlier entries and the new one all load")
        assertFalse(log.has("unreadable line"), "no glued line was ever written: ${log.lines}")
        unblockCompaction(tmp)
    }

    @Test
    fun `when even cutting the fragment off fails and compaction fails, nothing is appended after it until the file can be repaired`() {
        var sink: FlakySink? = null
        val j = journal({ p -> FlakySink(FileAppendSink(p)).also { sink = it } })
        j.load()
        assertTrue(j.write(entry("a")))
        val tmp = blockCompaction()
        sink!!.failTruncate = true
        sink!!.failNextAppend = true
        assertFalse(j.write(entry("b")))
        assertFalse(j.write(entry("c")), "not written behind a torn fragment, and it says so")
        assertNull(j.get("c"))
        unblockCompaction(tmp)
        sink?.failTruncate = false
        assertTrue(j.write(entry("d")), "the file is repaired first, then the entry is appended")
        j.close()
        assertEquals(listOf("a", "d"), journal().load().map { it.id })
        assertFalse(log.has("unreadable line"), log.lines.toString())
    }

    @Test
    fun `a failing compaction after a write that succeeded does not turn that write into a failure`() {
        val j = journal(compactAfter = 1)
        j.load()
        assertTrue(j.write(entry("a")))
        val tmp = blockCompaction()
        assertTrue(j.write(entry("a", OpPhase.CLOSED).copy(closedReason = "APPLIED")), "the CLOSED record is on disk, so it is a success")
        assertTrue(j.write(entry("b")))
        j.close()
        assertEquals(listOf("b"), journal().load().map { it.id })
        unblockCompaction(tmp)
    }

    @Test
    fun `a compaction forces the new file to disk before the rename and the directory after it`() {
        val fs = RecordingFs()
        val j = VaultJournal(dir.resolve("vault"), log, { 5_000 }, { FileAppendSink(it) }, 200, fs)
        j.load()
        j.write(entry("keep"))
        j.write(entry("done"))
        j.write(entry("done", OpPhase.CLOSED).copy(closedReason = "APPLIED"))
        j.close()
        fs.events.clear()

        val reload = VaultJournal(dir.resolve("vault"), log, { 5_000 }, { FileAppendSink(it) }, 200, fs)
        assertEquals(listOf("keep"), reload.load().map { it.id })

        val e = fs.events
        val force = e.indexOfFirst { it.startsWith("force") }
        val close = e.indexOf("close")
        val move = e.indexOfFirst { it.startsWith("move") }
        val sync = e.indexOfFirst { it.startsWith("syncDirectory") }
        assertTrue(force >= 0 && close > force && move > close && sync > move, "write, force(true), close, rename, then the directory: $e")
        assertEquals("force true", e[force], "data and metadata")
        assertEquals("move journal.log.tmp -> journal.log", e[move])
        assertEquals(listOf("keep"), journal().load().map { it.id })
        assertFalse(Files.exists(dir.resolve("vault").resolve("journal.log.tmp")))
    }

    @Test
    fun `a start with nothing to drop does not rewrite the journal at all`() {
        val j = journal()
        j.load()
        j.write(entry("a"))
        j.write(entry("b", OpPhase.UNDO_PENDING))
        j.close()
        val before = Files.readAllBytes(file)
        val fs = RecordingFs()
        val reload = VaultJournal(dir.resolve("vault"), log, { 5_000 }, { FileAppendSink(it) }, 200, fs)
        assertEquals(listOf("a", "b"), reload.load().map { it.id })
        assertTrue(fs.events.isEmpty(), "no temp file, no rename: ${fs.events}")
        assertTrue(before.contentEquals(Files.readAllBytes(file)))
        // appending after such a start works as before
        assertTrue(reload.write(entry("c")))
        reload.close()
        assertEquals(listOf("a", "b", "c"), journal().load().map { it.id })
    }

    @Test
    fun `a start rewrites the journal when it holds closed or superseded lines`() {
        val j = journal()
        j.load()
        j.write(entry("a"))
        j.write(entry("a", OpPhase.UNDO_PENDING))
        j.close()
        val fs = RecordingFs()
        VaultJournal(dir.resolve("vault"), log, { 5_000 }, { FileAppendSink(it) }, 200, fs).also {
            assertEquals(1, it.load().size)
        }
        assertTrue(fs.events.any { it.startsWith("move") }, "the superseded line is dropped: ${fs.events}")
        assertEquals(1, Files.readAllLines(file).size)
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

/** A [FileChannel] that records what is done to it (in order) into [events] and passes everything to the real one. */
private class RecordingChannel(private val real: FileChannel, private val events: MutableList<String>) : FileChannel() {
    override fun read(dst: ByteBuffer): Int = real.read(dst)
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.read(dsts, offset, length)
    override fun write(src: ByteBuffer): Int = real.write(src).also { events.add("write $it") }
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = real.write(srcs, offset, length)
    override fun position(): Long = real.position()
    override fun position(newPosition: Long): FileChannel = apply { real.position(newPosition) }
    override fun size(): Long = real.size()
    override fun truncate(size: Long): FileChannel = apply { real.truncate(size) }
    override fun force(metaData: Boolean) {
        events.add("force $metaData")
        real.force(metaData)
    }

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = real.transferTo(position, count, target)
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = real.transferFrom(src, position, count)
    override fun read(dst: ByteBuffer, position: Long): Int = real.read(dst, position)
    override fun write(src: ByteBuffer, position: Long): Int = real.write(src, position)
    override fun map(mode: FileChannel.MapMode, position: Long, size: Long): MappedByteBuffer = real.map(mode, position, size)
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = real.lock(position, size, shared)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = real.tryLock(position, size, shared)
    override fun implCloseChannel() {
        events.add("close")
        real.close()
    }
}

/** The compaction steps of a journal, recorded in order over the real file system. */
private class RecordingFs(val events: MutableList<String> = ArrayList()) : JournalFs {
    override fun create(path: Path): FileChannel = RecordingChannel(RealJournalFs.create(path), events).also { events.add("create ${path.fileName}") }

    override fun move(from: Path, to: Path) {
        events.add("move ${from.fileName} -> ${to.fileName}")
        RealJournalFs.move(from, to)
    }

    override fun syncDirectory(dir: Path) {
        events.add("syncDirectory ${dir.fileName}")
        RealJournalFs.syncDirectory(dir)
    }
}
