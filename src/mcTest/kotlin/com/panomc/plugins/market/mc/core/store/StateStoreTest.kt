package com.panomc.plugins.market.mc.core.store

import com.panomc.plugins.market.mc.core.support.FlakySink
import com.panomc.plugins.market.mc.core.support.TestClock
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.core.support.record
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** MC-U4 (journal / snapshot / purge) and MC-U5 (corrupt store) at the store level. */
class StateStoreTest {
    @TempDir
    lateinit var dir: Path

    private val clock = TestClock()
    private val log = TestLog()

    private fun open(options: StoreOptions = StoreOptions()) = StateStore.open(dir, clock, log, options)

    private val stateDir get() = dir.resolve("state")
    private val journal get() = stateDir.resolve("journal.log")
    private val snapshot get() = stateDir.resolve("snapshot.json")

    private fun result(code: String? = null, ok: Boolean = true) =
        RecordResult(code, null, clock.now(), listOf(CommandOutcome(0, ok, if (ok) null else "boom")))

    /** A mixed history: queued, running, done+acked, failed unacked, cancelled, queued-acked, un-acked again. */
    private fun history(store: StateStore) {
        store.commit(JournalOp.Received(record("k1", 1)))
        store.commit(JournalOp.Received(record("k2", 2, "Alex", commands = listOf("say a", "say b"))))
        store.commit(listOf(JournalOp.Received(record("k3", 3)), JournalOp.Started("k3", clock.now())))
        store.commit(JournalOp.Finished("k3", RecordState.DONE, result(), clock.now()))
        store.commit(JournalOp.Acked("k3", clock.now()))
        store.commit(JournalOp.Started("k2", clock.now()))
        store.commit(JournalOp.Finished("k2", RecordState.FAILED, result("COMMAND_ERROR", ok = false), clock.now()))
        store.commit(JournalOp.Received(record("k4", 4, "Bob")))
        store.commit(JournalOp.QueuedAcked("k4"))
        store.commit(JournalOp.Received(record("k5", 5, "Bob")))
        store.commit(JournalOp.QueuedAcked("k5"))
        store.commit(JournalOp.Unacked("k5"))
        store.commit(JournalOp.Finished("k5", RecordState.CANCELLED, RecordResult(message = "cancelled by Pano"), clock.now()))
    }

    @Test
    fun `journal replay equals the live state`() {
        val store = open()
        history(store)
        val live = store.dump()
        // No close: a crash leaves exactly these files.
        val replayed = open()
        assertEquals(live, replayed.dump())
        assertFalse(Files.exists(snapshot), "no snapshot was written, the journal alone carries the state")
    }

    @Test
    fun `snapshot plus journal replay equals the live state, also after a clean shutdown`() {
        val options = StoreOptions(maxJournalBytes = 700) // tiny: compaction triggers during the history
        val store = open(options)
        history(store)
        assertTrue(Files.exists(snapshot), "the journal grew past the limit and was compacted")
        store.commit(JournalOp.Received(record("k6", 6, "Carol")))
        val live = store.dump()
        assertEquals(live, open(options).dump(), "snapshot + journal tail")

        store.close() // clean shutdown compacts again
        assertEquals(0L, Files.size(journal), "the journal is empty after compaction")
        assertEquals(live, open(options).dump(), "snapshot only")
    }

    @Test
    fun `a crash between the snapshot and the journal truncation replays idempotently`() {
        val store = open()
        history(store)
        val live = store.dump()
        val oldJournal = Files.readAllBytes(journal)
        store.compact()
        // The crash happened after the snapshot rename and before the journal was replaced: the old journal is still there.
        Files.write(journal, oldJournal)
        assertEquals(live, open().dump())
        // and the store keeps working on top of it
        val again = open()
        again.commit(JournalOp.Received(record("k9", 9)))
        assertEquals(again.dump(), open().dump())
    }

    @Test
    fun `a partly written last line is cut off and later writes survive`() {
        val store = open()
        history(store)
        val live = store.dump()
        Files.write(journal, "{\"seq\":99,\"op\":\"star".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        val reopened = open()
        assertEquals(live, reopened.dump())
        assertNull(reopened.recovery, "a torn tail is a normal crash, not a lost store")
        assertTrue(log.has("partly written"))
        reopened.commit(JournalOp.Received(record("k7", 7)))
        val after = reopened.dump()
        assertEquals(after, open().dump(), "the cut line did not poison later entries")
    }

    @Test
    fun `a final line without newline that is valid is kept`() {
        val store = open()
        store.commit(JournalOp.Received(record("k1", 1)))
        val text = String(Files.readAllBytes(journal))
        Files.write(journal, text.trimEnd('\n').toByteArray())
        val reopened = open()
        assertNotNull(reopened.get("k1"))
        reopened.commit(JournalOp.Received(record("k2", 2)))
        assertEquals(setOf("k1", "k2"), open().all().map { it.key }.toSet())
    }

    @Test
    fun `an unreadable line in the middle quarantines the store`() {
        val store = open()
        history(store)
        val lines = String(Files.readAllBytes(journal)).split('\n').toMutableList()
        lines[3] = "{\"seq\":4,\"op\":"
        Files.write(journal, lines.joinToString("\n").toByteArray())
        val reopened = open()
        assertQuarantined(reopened)
        assertEquals(0, reopened.size)
    }

    @Test
    fun `a gap in the journal sequence quarantines the store`() {
        val store = open()
        history(store)
        val lines = String(Files.readAllBytes(journal)).split('\n').toMutableList()
        lines.removeAt(2)
        Files.write(journal, lines.joinToString("\n").toByteArray())
        assertQuarantined(open())
    }

    @Test
    fun `a corrupt snapshot quarantines the store`() {
        val store = open()
        history(store)
        store.compact()
        Files.write(snapshot, "{\"version\":1,\"seq\":".toByteArray())
        assertQuarantined(open())
    }

    @Test
    fun `an unsupported snapshot version quarantines the store`() {
        val store = open()
        history(store)
        store.compact()
        Files.write(snapshot, "{\"version\":99,\"seq\":3,\"records\":[]}".toByteArray())
        assertQuarantined(open())
    }

    @Test
    fun `a journal entry that does not fit the state quarantines the store`() {
        val store = open()
        history(store)
        // 'finished' for a key that was never received
        Files.write(
            journal,
            ("{\"seq\":${seqOfLast() + 1},\"op\":\"finished\",\"key\":\"ghost\",\"state\":\"DONE\",\"result\":{},\"at\":1}\n").toByteArray(),
            java.nio.file.StandardOpenOption.APPEND
        )
        assertQuarantined(open())
    }

    private fun seqOfLast(): Long {
        val last = String(Files.readAllBytes(journal)).trimEnd('\n').substringAfterLast('\n')
        return Regex("\"seq\":(\\d+)").find(last)!!.groupValues[1].toLong()
    }

    private fun assertQuarantined(store: StateStore) {
        val info = store.recovery
        assertNotNull(info, "recovery mode is on")
        assertNotNull(info!!.quarantine)
        assertTrue(Files.isDirectory(dir.resolve(info.quarantine!!)), "the lost store was moved to ${info.quarantine}")
        assertTrue(info.quarantine!!.startsWith("state.corrupt-"))
        assertTrue(Files.exists(dir.resolve("recovery.pending")))
        assertTrue(Files.isDirectory(stateDir), "a fresh store was started")
        assertTrue(log.has("unreadable"))
    }

    @Test
    fun `recovery mode survives a restart until confirmed`() {
        val store = open()
        history(store)
        store.compact()
        Files.write(snapshot, "garbage".toByteArray())
        val first = open()
        assertNotNull(first.recovery)
        // restart while still in recovery: the fresh, empty store must not look healthy
        first.close()
        val second = open()
        assertNotNull(second.recovery, "the marker keeps the recovery on")
        assertEquals(first.recovery!!.quarantine, second.recovery!!.quarantine)
        assertTrue(second.confirmRecovery())
        assertNull(second.recovery)
        assertFalse(Files.exists(dir.resolve("recovery.pending")))
        assertNull(open().recovery)
        assertFalse(second.confirmRecovery(), "nothing left to confirm")
    }

    @Test
    fun `salvage counts the keys of the lost store without an acknowledged result`() {
        val store = open()
        history(store) // k1 queued, k2 failed unacked, k3 acked, k4 queued, k5 cancelled unacked
        Files.write(journal, "{\"seq\":99,\"op\":\"x\"}\n{\"seq\":100,\"op\":\"x\"}\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        val reopened = open()
        assertEquals(4, reopened.recovery!!.salvagedUnackedKeys)
    }

    @Test
    fun `a finished state never changes`() {
        val store = open()
        store.commit(JournalOp.Received(record("k1", 1)))
        store.commit(listOf(JournalOp.Started("k1", clock.now()), JournalOp.Finished("k1", RecordState.DONE, result(), clock.now())))
        val before = store.dump()
        assertThrows(IllegalStateException::class.java) {
            store.commit(JournalOp.Finished("k1", RecordState.FAILED, result("X", false), clock.now()))
        }
        assertThrows(IllegalStateException::class.java) { store.commit(JournalOp.Started("k1", clock.now())) }
        assertThrows(IllegalStateException::class.java) { store.commit(JournalOp.Received(record("k1", 1))) }
        assertEquals(before, store.dump())
        assertEquals(before, open().dump(), "nothing of the refused ops reached the journal")
    }

    @Test
    fun `a batch that does not fit is refused as a whole`() {
        val store = open()
        store.commit(JournalOp.Received(record("k1", 1)))
        val before = store.dump()
        assertThrows(IllegalStateException::class.java) {
            store.commit(listOf(JournalOp.Received(record("k2", 2)), JournalOp.Started("nope", 1)))
        }
        assertEquals(before, store.dump())
        assertNull(store.get("k2"))
        assertEquals(before, open().dump())
    }

    @Test
    fun `retention purge removes acknowledged records only and keeps unacknowledged ones`() {
        val store = open()
        val t0 = clock.now()
        store.commit(JournalOp.Received(record("acked", 1)))
        store.commit(listOf(JournalOp.Started("acked", t0), JournalOp.Finished("acked", RecordState.DONE, result(), t0), JournalOp.Acked("acked", t0)))
        store.commit(JournalOp.Received(record("unacked", 2)))
        store.commit(listOf(JournalOp.Started("unacked", t0), JournalOp.Finished("unacked", RecordState.DONE, result(), t0)))
        store.commit(JournalOp.Received(record("queued", 3)))
        val day = 24L * 60 * 60 * 1000
        assertEquals(emptyList<String>(), store.purgeCandidates(t0 + 29 * day, 30 * day))
        assertEquals(listOf("acked"), store.purgeCandidates(t0 + 31 * day, 30 * day))
        assertEquals(listOf("acked"), store.purgeCandidates(t0 + 400 * day, 30 * day), "unacknowledged and queued records are never candidates")
        assertThrows(IllegalStateException::class.java) { store.commit(JournalOp.Purged(listOf("unacked"))) }
        store.commit(JournalOp.Purged(listOf("acked")))
        assertNull(store.get("acked"))
        assertEquals(store.dump(), open().dump())
        assertEquals(setOf("unacked", "queued"), open().all().map { it.key }.toSet())
    }

    @Test
    fun `a running record found at start is failed as interrupted and journaled`() {
        val store = open()
        store.commit(listOf(JournalOp.Received(record("k1", 1)), JournalOp.Started("k1", clock.now())))
        assertEquals(1, store.runningCount())
        val reopened = open() // crash: started, never finished
        val r = reopened.get("k1")!!
        assertEquals(RecordState.FAILED, r.state)
        assertEquals("INTERRUPTED", r.result!!.code)
        assertNull(r.ackedAt)
        assertEquals(0, reopened.runningCount())
        assertEquals(1, reopened.reportable().size, "the interrupted result is reported")
        assertEquals(reopened.dump(), open().dump(), "and it was written down: the next start sees the same")
    }

    @Test
    fun `a finished record drops its command text`() {
        val store = open()
        store.commit(listOf(JournalOp.Received(record("k1", 1, commands = listOf("op Steve"))), JournalOp.Started("k1", 1)))
        store.commit(JournalOp.Finished("k1", RecordState.DONE, result(), 2))
        val r = store.get("k1")!!
        assertTrue(r.commands.isEmpty())
        assertNull(r.permission)
        assertNull(r.issuer)
        assertEquals(2L, r.finishedAt)
    }

    // ---- write failures ----------------------------------------------------------------------------------------

    @Test
    fun `a failed write rolls memory back, leaves a readable journal and the store recovers`() {
        var flaky: FlakySink? = null
        val store = open(StoreOptions(sinkFactory = { FlakySink(FileAppendSink(it)).also { s -> flaky = s } }))
        store.commit(JournalOp.Received(record("k1", 1)))
        val before = store.dump()

        flaky!!.failNextAppend = true
        assertThrows(StoreWriteException::class.java) {
            store.commit(listOf(JournalOp.Received(record("k2", 2)), JournalOp.Started("k2", clock.now())))
        }
        assertFalse(store.healthy)
        assertNull(store.get("k2"), "memory was rolled back")
        assertEquals(before, store.dump())
        assertEquals(0, store.runningCount())

        assertTrue(store.probe(), "the disk accepts writes again")
        assertTrue(store.healthy)
        store.commit(JournalOp.Received(record("k3", 3)))
        val live = store.dump()
        val reopened = open()
        assertNull(reopened.recovery, "the half written batch was removed, so nothing is corrupt")
        assertEquals(live, reopened.dump())
    }

    @Test
    fun `when the partial write cannot be removed the store stays broken and a restart reads it`() {
        var flaky: FlakySink? = null
        val store = open(StoreOptions(sinkFactory = { FlakySink(FileAppendSink(it)).also { s -> flaky = s } }))
        store.commit(JournalOp.Received(record("k1", 1)))
        val before = store.dump()
        flaky!!.failNextAppend = true
        flaky!!.failTruncate = true
        assertThrows(StoreWriteException::class.java) { store.commit(JournalOp.Received(record("k2", 2))) }
        assertThrows(StoreWriteException::class.java) { store.commit(JournalOp.Received(record("k3", 3))) }
        assertFalse(store.probe())
        assertTrue(log.has("restart"))
        val reopened = open()
        assertNull(reopened.recovery, "the half line is the last line: a torn tail")
        assertEquals(before, reopened.dump())
    }
}
