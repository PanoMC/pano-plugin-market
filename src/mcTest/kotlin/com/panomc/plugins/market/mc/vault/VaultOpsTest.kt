package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.store.AppendSink
import com.panomc.plugins.market.mc.core.store.FileAppendSink
import com.panomc.plugins.market.mc.core.support.TestClock
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.spigot.vault.ConvertOutcome
import com.panomc.plugins.market.mc.spigot.vault.EconomyClient
import com.panomc.plugins.market.mc.spigot.vault.Notice
import com.panomc.plugins.market.mc.spigot.vault.OpKind
import com.panomc.plugins.market.mc.spigot.vault.OpPhase
import com.panomc.plugins.market.mc.spigot.vault.ProviderOutcome
import com.panomc.plugins.market.mc.spigot.vault.VaultEntry
import com.panomc.plugins.market.mc.spigot.vault.VaultJournal
import com.panomc.plugins.market.mc.spigot.vault.VaultOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The money paths of the Vault bridge against a fake Pano ledger (one transaction per operationId, like Pano) and a fake
 * server economy: MC-U10 (PROVIDER: disconnected, timeout, late success -> `:undo`) and both CONVERT directions with
 * ledger-first ordering, compensation, refund only on an explicit refusal, and resolution of unknown outcomes.
 */
class VaultOpsTest {
    @TempDir
    lateinit var dir: Path

    private val log = TestLog()
    private val clock = TestClock()
    private val events = Events()
    private val ledger = FakeLedger(events)
    private val link = EconomyLink(ledger, events)
    private val scheduler = InlineScheduler(clock)
    private val eco = FakeServerEconomy(events)
    private val mainThread = ManualMainThread(inline = true)
    private val notices = CopyOnWriteArrayList<Triple<VaultEntry, Notice, String?>>()
    private var hasEconomy = true
    private var sinkFactory: (Path) -> AppendSink = { FileAppendSink(it) }

    private val journal: VaultJournal by lazy { VaultJournal(dir.resolve("vault"), log, clock::now, { p -> sinkFactory(p) }) }

    private val ops: VaultOps by lazy { newOps(journal) }

    private fun newOps(j: VaultJournal) = VaultOps(
        EconomyClient(link, "1.4.0", log), j, scheduler, clock, log, mainThread,
        economy = { if (hasEconomy) eco else null },
        notifier = { e, n, c -> notices.add(Triple(e, n, c)) },
        retryBaseMs = 1_000, retryMaxMs = 8_000
    )

    private val steve = player("Steve")

    private fun start() = ops.start()

    private fun reloadedJournal(): List<VaultEntry> = VaultJournal(dir.resolve("vault"), TestLog(), clock::now).load()

    /** Lets every retry timer run until nothing is left open (or the safety limit is hit). */
    private fun settle(o: VaultOps = ops) {
        var rounds = 0
        while (o.openEntries().isNotEmpty() && rounds++ < 60) scheduler.advance(10_000)
    }

    private fun credits(user: String = "Steve") = ledger.balance(user)!!.stripTrailingZeros().toPlainString()

    // ---- PROVIDER (MC-U10) ---------------------------------------------------------------------------------------------

    @Test
    fun `MC-U10 PROVIDER disconnected - FAILURE, nothing sent, nothing recorded`() {
        ledger.account("Steve", "100")
        start()
        link.up = false
        assertEquals(ProviderOutcome.NotConnected, ops.providerTransfer(true, steve, 10.0, 500))
        assertTrue(link.requests.isEmpty())
        assertEquals("100", credits())
        assertTrue(reloadedJournal().isEmpty())
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `PROVIDER success - ledger charged once, the request carries the operation, the journal entry is closed`() {
        ledger.account("Steve", "100")
        start()
        val out = ops.providerTransfer(true, steve, 12.5, 500)
        assertEquals(ProviderOutcome.Success(87.5), out)
        assertEquals("87.5", credits())
        val r = link.requests.single()
        assertEquals(EconomyOp.WITHDRAW, r.op)
        assertEquals(12.5, r.amount)
        assertEquals("Steve", r.player.username)
        assertEquals("11111111-1111-1111-1111-111111111111", r.player.uuid)
        assertEquals("vault withdraw", r.reason)
        assertTrue(Regex("[0-9a-f-]{36}").matches(r.operationId))
        assertTrue(ops.openEntries().isEmpty())
        assertTrue(reloadedJournal().isEmpty())
    }

    @Test
    fun `PROVIDER deposit success`() {
        ledger.account("Steve", "1")
        start()
        assertEquals(ProviderOutcome.Success(6.0), ops.providerTransfer(false, steve, 5.0, 500))
        assertEquals(EconomyOp.DEPOSIT, link.requests.single().op)
    }

    @Test
    fun `PROVIDER refusals are final - INSUFFICIENT_CREDITS and NO_ACCOUNT apply nothing and close the entry`() {
        ledger.account("Steve", "5")
        start()
        val low = ops.providerTransfer(true, steve, 10.0, 500)
        assertEquals(ProviderOutcome.Refused("INSUFFICIENT_CREDITS", 5.0), low)
        assertEquals(ProviderOutcome.Refused("NO_ACCOUNT", null), ops.providerTransfer(true, player("Nobody"), 1.0, 500))
        assertEquals("5", credits())
        assertTrue(ops.openEntries().isEmpty())
        assertEquals(0, ledger.transactions.size)
    }

    @Test
    fun `PROVIDER a transient refusal on the first attempt applied nothing and is not retried`() {
        ledger.account("Steve", "5")
        link.script = { _, _ -> Delivery.NotAccepted("MARKET_NOT_READY") }
        start()
        assertEquals(ProviderOutcome.Transient("MARKET_NOT_READY"), ops.providerTransfer(true, steve, 1.0, 500))
        assertTrue(ops.openEntries().isEmpty())
        scheduler.advance(120_000)
        assertEquals(1, link.requests.size, "no retry of something that was refused before it was applied")
    }

    @Test
    fun `MC-U10 PROVIDER timeout - FAILURE within the budget, the entry stays in the journal`() {
        ledger.account("Steve", "100")
        link.script = { _, _ -> Delivery.HeldApplied }
        start()
        val began = System.nanoTime()
        val out = ops.providerTransfer(true, steve, 10.0, 120)
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertEquals(ProviderOutcome.Unknown, out)
        assertTrue(tookMs in 100..1_500, "the caller waited about the budget, not longer ($tookMs ms)")
        val open = ops.openEntries().single()
        assertEquals(OpPhase.LEDGER_PENDING, open.phase)
        assertEquals(OpKind.PROVIDER_WITHDRAW, open.kind)
        // the record is on disk: a restart would see it
        assertEquals(listOf(open.id), reloadedJournal().map { it.id })
    }

    @Test
    fun `MC-U10 PROVIDER late success after the timeout - the compensation is posted with the undo id and the ledger ends where it began`() {
        ledger.account("Steve", "100")
        link.script = { _, _ -> Delivery.HeldApplied }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(true, steve, 10.0, 80))
        assertEquals("90", credits(), "the ledger did apply it")
        val id = ops.openEntries().single().id
        link.script = { _, _ -> Delivery.Normal }

        link.releaseHeld() // the late answer: ok = true

        val undo = link.requests.last()
        assertEquals(EconomyOp.DEPOSIT, undo.op, "a withdrawal is compensated by a deposit")
        assertEquals("$id:undo", undo.operationId)
        assertEquals(10.0, undo.amount)
        assertEquals("100", credits())
        assertTrue(ops.openEntries().isEmpty())
        assertTrue(reloadedJournal().isEmpty())
        assertTrue(log.has("applied after the caller was told it failed"))
    }

    @Test
    fun `PROVIDER late success of a deposit is compensated by a withdrawal`() {
        ledger.account("Steve", "100")
        link.script = { _, _ -> Delivery.HeldApplied }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(false, steve, 7.0, 80))
        assertEquals("107", credits())
        val id = ops.openEntries().single().id
        link.script = { _, _ -> Delivery.Normal }
        link.releaseHeld()
        val undo = link.requests.last()
        assertEquals(EconomyOp.WITHDRAW, undo.op)
        assertEquals("$id:undo", undo.operationId)
        assertEquals("100", credits())
    }

    @Test
    fun `PROVIDER late refusal needs no compensation`() {
        ledger.account("Steve", "5")
        link.script = { _, _ -> Delivery.HeldApplied }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(true, steve, 10.0, 80))
        link.releaseHeld() // INSUFFICIENT_CREDITS, late
        assertEquals(1, link.requests.size, "nothing was applied, nothing to compensate")
        assertTrue(ops.openEntries().isEmpty())
        assertEquals("5", credits())
    }

    @Test
    fun `PROVIDER answer lost - the same operation id is re-sent until answered, then the late success is undone`() {
        ledger.account("Steve", "100")
        // first attempt: Pano applies it, the answer is lost; the retry is answered normally (idempotent: no second transaction)
        link.script = { _, attempt -> if (attempt == 0) Delivery.AppliedNoAnswer else Delivery.Normal }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(true, steve, 10.0, 500))
        val id = ops.openEntries().single().id
        settle()
        assertEquals(setOf(id, "$id:undo"), link.ids().toSet())
        assertEquals(2, link.requests.count { it.operationId == id }, "the original was sent again with the SAME id")
        assertEquals(1, ledger.transactions.keys.count { it == id }, "Pano posted one transaction for it")
        assertEquals("100", credits())
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `PROVIDER request lost on the way - the retry is the first time Pano sees it, and it is reverted`() {
        ledger.account("Steve", "100")
        link.script = { _, attempt -> if (attempt == 0) Delivery.Lost else Delivery.Normal }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(true, steve, 10.0, 500))
        assertEquals("100", credits(), "never applied so far")
        settle()
        assertEquals(2, ledger.transactions.size, "the delayed application and its compensation")
        assertEquals("100", credits(), "net zero: the caller was told it failed")
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `PROVIDER the retry backs off and waits while disconnected`() {
        ledger.account("Steve", "100")
        link.script = { _, attempt -> if (attempt == 0) Delivery.Lost else Delivery.Normal }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(true, steve, 10.0, 500))
        link.up = false
        scheduler.advance(120_000)
        assertEquals(1, link.requests.size, "no send while disconnected")
        assertEquals(1, ops.openEntries().size)
        link.up = true
        settle()
        assertTrue(ops.openEntries().isEmpty())
        assertEquals("100", credits())
    }

    @Test
    fun `PROVIDER unresolved entries are resumed after a restart`() {
        ledger.account("Steve", "100")
        link.script = { _, _ -> Delivery.HeldApplied }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(true, steve, 10.0, 80))
        val id = ops.openEntries().single().id
        assertEquals("90", credits())
        // the server goes down (its timers die with it) and a new component starts over the same journal directory
        ops.stop()
        link.script = { _, _ -> Delivery.Normal }
        val second = newOps(VaultJournal(dir.resolve("vault"), log, clock::now))
        second.start()
        assertEquals(listOf(id), second.openEntries().map { it.id })
        settle(second)
        assertEquals("100", credits(), "the original was re-sent (answered from the first transaction), then undone")
        assertTrue(link.requests.any { it.operationId == "$id:undo" })
        assertTrue(log.has("resuming PROVIDER_WITHDRAW $id"))
    }

    @Test
    fun `PROVIDER a refused compensation is closed with an error naming the amount for the admin`() {
        ledger.account("Steve", "100")
        link.script = { _, _ -> Delivery.HeldApplied }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(false, steve, 7.0, 80))
        // the player spends the credits before the undo can take them back
        ledger.account("Steve", "1")
        link.script = { _, _ -> Delivery.Normal }
        link.releaseHeld()
        assertTrue(ops.openEntries().isEmpty())
        assertTrue(log.has("compensation") && log.has("INSUFFICIENT_CREDITS"))
    }

    @Test
    fun `PROVIDER the compensation itself is retried with the undo id until answered`() {
        ledger.account("Steve", "100")
        link.script = { r, _ -> if (r.operationId.endsWith(":undo")) Delivery.Lost else Delivery.HeldApplied }
        start()
        assertEquals(ProviderOutcome.Unknown, ops.providerTransfer(true, steve, 10.0, 80))
        val id = ops.openEntries().single().id
        link.releaseHeld() // late success, the undo is lost
        assertEquals(OpPhase.UNDO_PENDING, ops.openEntries().single().phase)
        link.script = { _, attempt -> if (attempt < 2) Delivery.Lost else Delivery.Normal }
        settle()
        val undoSends = link.requests.filter { it.operationId == "$id:undo" }
        assertTrue(undoSends.size >= 3, "retried until answered (${undoSends.size} sends)")
        assertEquals("100", credits())
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `PROVIDER a journal that cannot be written runs nothing`() {
        ledger.account("Steve", "100")
        val sink = MemorySink().also { it.failing = true }
        sinkFactory = { sink }
        start()
        assertEquals(ProviderOutcome.JournalFailed, ops.providerTransfer(true, steve, 10.0, 500))
        assertTrue(link.requests.isEmpty())
        assertEquals("100", credits())
    }

    @Test
    fun `PROVIDER a success that cannot be recorded as closed is reported as failed and undone, so a restart can never undo a success`() {
        ledger.account("Steve", "100")
        // the intent is written, then the disk fills up before the close can be written
        val sink = MemorySink().also { it.okAppends = 1 }
        sinkFactory = { sink }
        start()
        val out = ops.providerTransfer(true, steve, 10.0, 500)
        assertEquals(ProviderOutcome.Unknown, out, "the caller is told it failed because the success could not be recorded")
        assertEquals("100", credits(), "and the ledger was put back")
        assertTrue(link.requests.any { it.operationId.endsWith(":undo") })
        assertTrue(log.has("could not record that"))
    }

    @Test
    fun `a journal write after stop does not reopen the file`() {
        ledger.account("Steve", "100")
        start()
        ops.stop()
        val file = dir.resolve("vault").resolve("journal.log")
        val before = java.nio.file.Files.size(file)
        assertFalse(journal.write(VaultEntry("late", OpKind.PROVIDER_WITHDRAW, "Steve", null, 1.0, null, OpPhase.LEDGER_PENDING, 1, 1)))
        assertEquals(before, java.nio.file.Files.size(file))
    }

    @Test
    fun `PROVIDER stop makes every later call fail without sending`() {
        ledger.account("Steve", "100")
        start()
        ops.stop()
        assertEquals(ProviderOutcome.Stopped, ops.providerTransfer(true, steve, 10.0, 500))
        assertTrue(link.requests.isEmpty())
    }

    @Test
    fun `PROVIDER an answer that arrives at the same moment as the timeout is never both success and compensated`() {
        // many rounds with a tiny budget: whichever side wins, the ledger must agree with what the caller was told
        ledger.account("Steve", "1000000")
        var expected = BigDecimal("1000000")
        val delayed = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        link.script = { _, _ -> Delivery.HeldApplied }
        start()
        try {
            repeat(40) { i ->
                val amount = BigDecimal(i + 1)
                val release = delayed.schedule({ link.releaseHeld() }, 15, java.util.concurrent.TimeUnit.MILLISECONDS)
                val out = ops.providerTransfer(true, steve, amount.toDouble(), 15)
                release.get()
                if (out is ProviderOutcome.Success) expected -= amount
            }
            link.script = { _, _ -> Delivery.Normal }
            link.releaseHeld()
            settle()
        } finally {
            delayed.shutdownNow()
        }
        assertEquals(expected.stripTrailingZeros(), ledger.balance("Steve")!!.stripTrailingZeros())
        assertTrue(ops.openEntries().isEmpty())
    }

    // ---- CONVERT: credits -> server money ---------------------------------------------------------------------------------

    @Test
    fun `convert to server - the ledger is charged first, the server economy is paid after`() {
        ledger.account("Steve", "50")
        eco.set("Steve", "0")
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.Done(40.0), out)
        assertEquals(listOf("ledger WITHDRAW ${link.requests.single().operationId}", "economy deposit Steve 25.0"), events.all())
        assertEquals("40", credits())
        assertEquals("25", eco.balance("Steve").plain())
        assertEquals("convert to server", link.requests.single().reason)
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `convert to server - a payout the economy refuses is compensated with the undo id`() {
        ledger.account("Steve", "50")
        eco.refuseDeposit = true
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.PayoutFailed("refused", true), out)
        val id = link.requests.first().operationId
        assertEquals(listOf("ledger WITHDRAW $id", "economy deposit Steve 25.0", "ledger DEPOSIT $id:undo"), events.all())
        assertEquals("50", credits())
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `convert to server - an economy that throws is a failed payout, never an exception into the caller`() {
        ledger.account("Steve", "50")
        eco.throwOnDeposit = true
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertTrue(out is ConvertOutcome.PayoutFailed && (out as ConvertOutcome.PayoutFailed).restored)
        assertEquals("50", credits())
    }

    @Test
    fun `convert to server - no server economy at payout time gives the credits back`() {
        ledger.account("Steve", "50")
        hasEconomy = false
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.PayoutFailed("no server economy", true), out)
        assertEquals("50", credits())
    }

    @Test
    fun `convert to server - the undo of a failed payout is retried when the first try gets no answer and the player is told when it lands`() {
        ledger.account("Steve", "50")
        eco.refuseDeposit = true
        link.script = { r, attempt -> if (r.operationId.endsWith(":undo") && attempt == 0) Delivery.AppliedNoAnswer else Delivery.Normal }
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.PayoutFailed("refused", false), out)
        settle()
        assertEquals("50", credits())
        assertEquals(2, link.requests.count { it.operationId.endsWith(":undo") }, "the undo was sent again with the same undo id")
        assertEquals(1, ledger.transactions.keys.count { it.endsWith(":undo") }, "and Pano posted it once")
        assertEquals(listOf(Notice.CONVERSION_UNDONE), notices.map { it.second })
    }

    @Test
    fun `convert to server - refusals apply nothing and never touch the economy`() {
        ledger.account("Steve", "5")
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.Refused("INSUFFICIENT_CREDITS", 5.0, null), out)
        ops.convertToServer(player("Nobody"), 1.0, 2.0) { out = it }
        assertEquals(ConvertOutcome.Refused("NO_ACCOUNT", null, null), out)
        ledger.creditsDisabled = true
        ops.convertToServer(steve, 1.0, 2.0) { out = it }
        assertEquals(ConvertOutcome.Refused("CREDITS_DISABLED", null, null), out)
        assertTrue(eco.calls.isEmpty())
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `convert to server - a transient refusal changes nothing and is reported as not applied`() {
        ledger.account("Steve", "50")
        link.script = { _, _ -> Delivery.NotAccepted("RATE_LIMITED") }
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.NotApplied("RATE_LIMITED"), out)
        assertTrue(eco.calls.isEmpty())
        assertTrue(ops.openEntries().isEmpty())
        scheduler.advance(120_000)
        assertEquals(1, link.requests.size)
    }

    @Test
    fun `convert to server - no answer - nothing is paid, the same id is resolved, a late success is undone and the player is told`() {
        ledger.account("Steve", "50")
        link.script = { _, attempt -> if (attempt == 0) Delivery.Lost else Delivery.Normal }
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.Unknown, out)
        assertTrue(eco.calls.isEmpty())
        settle()
        assertTrue(eco.calls.isEmpty(), "no money was ever paid for a conversion the player was told was unknown")
        assertEquals("50", credits())
        assertEquals(listOf(Notice.CONVERSION_UNDONE), notices.map { it.second })
        assertEquals(1, link.requests.map { it.operationId }.filter { !it.endsWith(":undo") }.distinct().size)
    }

    @Test
    fun `convert to server - answer lost after Pano applied it - the retry sees the first transaction and the credits come back`() {
        ledger.account("Steve", "50")
        link.script = { _, attempt -> if (attempt == 0) Delivery.AppliedNoAnswer else Delivery.Normal }
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.Unknown, out)
        assertEquals("40", credits())
        settle()
        assertEquals("50", credits())
        assertTrue(eco.calls.isEmpty())
    }

    @Test
    fun `convert to server - disconnected or stopped answers at once without a journal entry`() {
        ledger.account("Steve", "50")
        start()
        link.up = false
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.NotConnected, out)
        assertTrue(reloadedJournal().isEmpty())
    }

    @Test
    fun `convert to server - a journal that cannot be written runs nothing`() {
        ledger.account("Steve", "50")
        sinkFactory = { MemorySink().also { it.failing = true } }
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(ConvertOutcome.JournalFailed, out)
        assertTrue(link.requests.isEmpty())
        assertTrue(eco.calls.isEmpty())
    }

    @Test
    fun `convert to server - the payout waits for the server thread and runs there`() {
        ledger.account("Steve", "50")
        mainThread.inline = false
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertEquals(null, out)
        assertTrue(eco.calls.isEmpty(), "the Vault economy is never called off the server thread")
        assertEquals(1, mainThread.pending())
        mainThread.runAll()
        assertEquals(ConvertOutcome.Done(40.0), out)
    }

    @Test
    fun `convert to server - a server thread that cannot be reached gives the credits back`() {
        ledger.account("Steve", "50")
        mainThread.refuse = IllegalStateException("plugin disabled")
        start()
        var out: ConvertOutcome? = null
        ops.convertToServer(steve, 10.0, 25.0) { out = it }
        assertTrue(out is ConvertOutcome.PayoutFailed)
        assertEquals("50", credits())
    }

    // ---- CONVERT: server money -> credits ---------------------------------------------------------------------------------

    @Test
    fun `convert to credits - the server economy is charged first, then the ledger is credited`() {
        ledger.account("Steve", "5")
        eco.set("Steve", "100")
        start()
        var out: ConvertOutcome? = null
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Done(20.0), out)
        val id = link.requests.single().operationId
        assertEquals(listOf("economy withdraw Steve 30.0", "ledger DEPOSIT $id"), events.all())
        assertEquals("20", credits())
        assertEquals("70", eco.balance("Steve").plain())
        assertEquals("convert to credits", link.requests.single().reason)
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `convert to credits - not enough money, a refusing economy, no economy and no connection move nothing`() {
        ledger.account("Steve", "5")
        eco.set("Steve", "10")
        start()
        var out: ConvertOutcome? = null
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.InsufficientMoney, out)
        eco.set("Steve", "100")
        eco.refuseWithdraw = true
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.MoneyRefused("refused"), out)
        eco.refuseWithdraw = false
        hasEconomy = false
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.NoEconomy, out)
        hasEconomy = true
        link.up = false
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.NotConnected, out)
        assertEquals("100", eco.balance("Steve").plain(), "no money was taken in any of these cases")
        assertTrue(link.requests.isEmpty())
    }

    @Test
    fun `convert to credits - an explicit refusal (NO_ACCOUNT) gives the money back`() {
        eco.set("Nobody", "100")
        start()
        var out: ConvertOutcome? = null
        ops.convertToCredits(player("Nobody"), 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Refused("NO_ACCOUNT", null, true), out)
        assertEquals("100", eco.balance("Nobody").plain())
        assertEquals(listOf("economy withdraw Nobody 30.0", "ledger DEPOSIT ${link.requests.single().operationId}", "economy deposit Nobody 30.0"), events.all())
        assertTrue(ops.openEntries().isEmpty())
    }

    @Test
    fun `convert to credits - a refund that fails is retried until it works and the player is told once it is stuck`() {
        eco.set("Nobody", "100")
        start()
        eco.failDeposits = 3
        var out: ConvertOutcome? = null
        ops.convertToCredits(player("Nobody"), 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Refused("NO_ACCOUNT", null, false), out)
        assertEquals("70", eco.balance("Nobody").plain())
        settle()
        assertEquals("100", eco.balance("Nobody").plain())
        assertEquals(listOf(Notice.MONEY_REFUNDED), notices.map { it.second }, "the stuck notice is only for the late path; the command already told the player")
        assertTrue(log.has("could not be given back"))
    }

    @Test
    fun `convert to credits - a late refusal refunds and the stuck refund is announced once`() {
        eco.set("Nobody", "100")
        link.script = { _, attempt -> if (attempt == 0) Delivery.Lost else Delivery.Normal }
        start()
        eco.failDeposits = 2
        var out: ConvertOutcome? = null
        ops.convertToCredits(player("Nobody"), 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Pending, out)
        settle()
        assertEquals("100", eco.balance("Nobody").plain())
        assertEquals(listOf(Notice.MONEY_REFUND_STUCK, Notice.MONEY_REFUNDED), notices.map { it.second })
    }

    @Test
    fun `convert to credits - transient refusal and no answer never refund, the same deposit is retried`() {
        ledger.account("Steve", "5")
        eco.set("Steve", "100")
        link.script = { _, attempt -> when (attempt) { 0 -> Delivery.NotAccepted("MARKET_NOT_READY"); 1 -> Delivery.Lost; else -> Delivery.Normal } }
        start()
        var out: ConvertOutcome? = null
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Pending, out)
        assertEquals("70", eco.balance("Steve").plain())
        settle()
        assertEquals(1, link.ids().size, "every attempt carried the same operation id")
        assertEquals(3, link.requests.size)
        assertEquals("20", credits())
        assertEquals("70", eco.balance("Steve").plain(), "no refund: the deposit was applied")
        assertEquals(1, eco.calls.count { it.startsWith("withdraw") })
        assertEquals(listOf(Notice.DEPOSIT_ARRIVED), notices.map { it.second })
        assertFalse(eco.calls.any { it.startsWith("deposit") })
    }

    @Test
    fun `convert to credits - the answer was lost after Pano applied it - the retry finishes it, nothing is refunded`() {
        ledger.account("Steve", "5")
        eco.set("Steve", "100")
        link.script = { _, attempt -> if (attempt == 0) Delivery.AppliedNoAnswer else Delivery.Normal }
        start()
        var out: ConvertOutcome? = null
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Pending, out)
        assertEquals("20", credits())
        settle()
        assertEquals("20", credits(), "one transaction only")
        assertFalse(eco.calls.any { it.startsWith("deposit") })
    }

    @Test
    fun `convert to credits - a pending deposit survives a restart and is finished`() {
        ledger.account("Steve", "5")
        eco.set("Steve", "100")
        link.script = { _, _ -> Delivery.Lost }
        start()
        var out: ConvertOutcome? = null
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Pending, out)
        val persisted = reloadedJournal().single()
        assertEquals(OpKind.CONVERT_TO_CREDITS, persisted.kind)
        assertEquals(30.0, persisted.money)
        assertEquals(15.0, persisted.credits)
        ops.stop()
        link.script = { _, _ -> Delivery.Normal }
        val second = newOps(VaultJournal(dir.resolve("vault"), log, clock::now))
        second.start()
        settle(second)
        assertEquals("20", credits())
        assertEquals(listOf(Notice.DEPOSIT_ARRIVED), notices.map { it.second })
    }

    @Test
    fun `convert to credits - a journal that cannot be written does not stop a conversion whose money is already taken`() {
        ledger.account("Steve", "5")
        eco.set("Steve", "100")
        sinkFactory = { MemorySink().also { it.failing = true } }
        start()
        var out: ConvertOutcome? = null
        ops.convertToCredits(steve, 30.0, 15.0) { out = it }
        assertEquals(ConvertOutcome.Done(20.0), out)
        assertTrue(log.has("could not record the deposit"))
    }

    @Test
    fun `the same operation id is used for every send of one conversion and a compensation is its id plus undo`() {
        ledger.account("Steve", "50")
        eco.refuseDeposit = true
        link.script = { r, attempt -> if (attempt == 0 && !r.operationId.endsWith(":undo")) Delivery.AppliedNoAnswer else Delivery.Normal }
        start()
        ops.convertToServer(steve, 10.0, 25.0) { }
        settle()
        val base = link.requests.map { it.operationId }.filter { !it.endsWith(":undo") }.distinct()
        assertEquals(1, base.size)
        assertTrue(link.requests.any { it.operationId == base.single() + ":undo" })
        assertNotNull(link.requests.first().operationId)
    }

    // ---- conservation under random failures ----------------------------------------------------------------------------------

    @Test
    fun `PROVIDER under random failures - the ledger always equals the sum of what the callers were told succeeded`() {
        val rnd = java.util.Random(20261005)
        ledger.account("Steve", "1000000")
        var expected = BigDecimal("1000000")
        link.script = { r, attempt ->
            if (r.operationId.endsWith(":undo")) {
                when (rnd.nextInt(4)) { 0 -> Delivery.AppliedNoAnswer; 1 -> Delivery.Lost; else -> Delivery.Normal }
            } else if (attempt > 0) {
                when (rnd.nextInt(4)) { 0 -> Delivery.AppliedNoAnswer; 1 -> Delivery.Lost; else -> Delivery.Normal }
            } else {
                when (rnd.nextInt(6)) {
                    0 -> Delivery.AppliedNoAnswer
                    1 -> Delivery.Lost
                    2 -> Delivery.HeldApplied
                    3 -> Delivery.NotAccepted("RATE_LIMITED")
                    else -> Delivery.Normal
                }
            }
        }
        start()
        repeat(150) { i ->
            val withdraw = rnd.nextBoolean()
            val amount = BigDecimal(rnd.nextInt(50) + 1)
            val out = ops.providerTransfer(withdraw, steve, amount.toDouble(), 5)
            if (out is ProviderOutcome.Success) expected = if (withdraw) expected - amount else expected + amount
            if (i % 7 == 0) link.releaseHeld()
            if (i % 11 == 0) scheduler.advance(3_000)
        }
        link.releaseHeld()
        link.script = { _, _ -> Delivery.Normal }
        settle()
        assertTrue(ops.openEntries().isEmpty(), "everything resolved: ${ops.openEntries()}")
        assertEquals(expected.stripTrailingZeros(), ledger.balance("Steve")!!.stripTrailingZeros())
    }

    @Test
    fun `CONVERT under random failures - credits times the rate plus server money never changes`() {
        val rnd = java.util.Random(7)
        val rate = BigDecimal(2)
        ledger.account("Steve", "5000")
        eco.set("Steve", "10000")
        val total = { ledger.balance("Steve")!! * rate + eco.balance("Steve") }
        val start = total()
        link.script = { r, attempt ->
            if (attempt > 0 || r.operationId.endsWith(":undo")) {
                when (rnd.nextInt(4)) { 0 -> Delivery.AppliedNoAnswer; 1 -> Delivery.Lost; else -> Delivery.Normal }
            } else {
                when (rnd.nextInt(6)) { 0 -> Delivery.AppliedNoAnswer; 1 -> Delivery.Lost; 2 -> Delivery.NotAccepted("MARKET_NOT_READY"); else -> Delivery.Normal }
            }
        }
        start()
        repeat(120) { i ->
            eco.refuseDeposit = rnd.nextInt(5) == 0
            val c = rnd.nextInt(20) + 1
            if (rnd.nextBoolean()) ops.convertToServer(steve, c.toDouble(), (c * 2).toDouble()) { }
            else ops.convertToCredits(steve, (c * 2).toDouble(), c.toDouble()) { }
            if (i % 9 == 0) scheduler.advance(2_500)
        }
        eco.refuseDeposit = false
        link.script = { _, _ -> Delivery.Normal }
        settle()
        assertTrue(ops.openEntries().isEmpty(), "everything resolved: ${ops.openEntries()}")
        assertEquals(start.stripTrailingZeros(), total().stripTrailingZeros(), "value was neither created nor destroyed")
    }
}
