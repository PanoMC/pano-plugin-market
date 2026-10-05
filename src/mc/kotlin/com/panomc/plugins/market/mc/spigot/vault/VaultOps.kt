package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer
import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** The server economy as the CONVERT mode sees it. The real implementation wraps Vault's `Economy` (see `VaultGlue`). Called on the server thread only. */
interface ServerEconomy {
    val name: String
    fun format(amount: Double): String
    fun has(player: PlayerRef, amount: Double): Boolean
    fun withdraw(player: PlayerRef, amount: Double): ServerResult
    fun deposit(player: PlayerRef, amount: Double): ServerResult
}

data class ServerResult(val ok: Boolean, val error: String? = null)

/** Runs a task on the server thread (Bukkit main thread / Folia global region): Vault economies are not thread safe. */
fun interface MainThread {
    fun run(task: Runnable)
}

/** What a PROVIDER-mode operation came to, as the Vault caller needs to know it. */
sealed class ProviderOutcome {
    data class Success(val balance: Double?) : ProviderOutcome()

    /** Pano answered and did not apply it. */
    data class Refused(val code: String, val balance: Double?) : ProviderOutcome()

    /** Pano did not take the request just now; nothing was applied. */
    data class Transient(val reason: String) : ProviderOutcome()

    /** Rejected while disconnected (owner decision): nothing was sent. */
    object NotConnected : ProviderOutcome()

    /** The request may or may not have been applied; the bridge resolves it in the background (journal, retry, `:undo`). */
    object Unknown : ProviderOutcome()

    /** The journal refused the entry (disk): nothing was sent, a money operation is never run without its record. */
    object JournalFailed : ProviderOutcome()
    object Stopped : ProviderOutcome()
}

/** What a CONVERT-mode conversion came to. */
sealed class ConvertOutcome {
    data class Done(val balance: Double?) : ConvertOutcome()

    /** Pano refused. [refunded] is only meaningful for money -> credits: `true` money given back, `false` giving it back is retried. */
    data class Refused(val code: String, val balance: Double?, val refunded: Boolean?) : ConvertOutcome()

    /** credits -> money: Pano did not take the request (not ready, rate limited, wrong version). Nothing changed. */
    data class NotApplied(val reason: String) : ConvertOutcome()

    /** credits -> money: no answer. If the credits were taken they are given back automatically; no money was added. */
    object Unknown : ConvertOutcome()

    /** money -> credits: the money is taken and the credits arrive when Pano answers (retried in the background). */
    object Pending : ConvertOutcome()

    /** credits -> money: the server economy refused the payout. [restored] = the credits are already back, `false` = being given back. */
    data class PayoutFailed(val error: String?, val restored: Boolean) : ConvertOutcome()

    object NoEconomy : ConvertOutcome()
    object NotConnected : ConvertOutcome()
    object InsufficientMoney : ConvertOutcome()
    data class MoneyRefused(val error: String?) : ConvertOutcome()
    object JournalFailed : ConvertOutcome()
}

/** What the bridge tells a player later, about a conversion that was still open when their command returned. */
enum class Notice { DEPOSIT_ARRIVED, CONVERSION_UNDONE, MONEY_REFUNDED, MONEY_REFUND_STUCK }

/**
 * The money paths of the Vault bridge (19 section 10), platform independent. Two rules hold everywhere:
 *
 * 1. **Ledger first, record first.** Every operation is written to the [VaultJournal] (durable) BEFORE its
 *    `MARKET_ECONOMY` request is sent, and a money operation whose record cannot be written is not run.
 * 2. **An unknown outcome is resolved, never guessed.** A request without a usable answer stays in the journal and is
 *    re-sent with the SAME `operationId` (Pano posts one ledger transaction per id, 07 section 3.1) until it is answered.
 *    What an answer means depends on who was told what: when the caller was told "failed" (a PROVIDER call that timed
 *    out, a conversion that could not pay out) and the ledger turns out to have applied it, the opposite operation is
 *    posted with `<id>:undo` (also retried until answered); a money -> credits deposit whose money is already taken is
 *    finished when applied and refunded to the server economy only when Pano explicitly refused it.
 *
 * 3. **A payment that cannot be undone is never guessed at.** The two payments into the server economy (the payout of
 *    credits -> money and the refund of a refused money -> credits) are not idempotent: a restart that cannot tell
 *    whether one happened must not repeat or compensate it. So the journal holds [OpPhase.PAYOUT_STARTED] /
 *    [OpPhase.REFUND_STARTED], written durably immediately before the economy is called (no record, no payment); an
 *    entry found in such a phase at start is reported at ERROR and closed as `INTERRUPTED`, never undone or retried.
 *    `LEDGER_PENDING` / `REFUND_PENDING` mean the payment provably never began and keep their automatic handling.
 *
 * All state changes go through [lock]; no network call, Vault call or disk write of the caller's thread happens under it
 * except the journal append itself.
 */
class VaultOps(
    private val client: EconomyClient,
    private val journal: VaultJournal,
    private val scheduler: McScheduler,
    private val clock: McClock,
    private val log: McLog,
    private val mainThread: MainThread,
    private val economy: () -> ServerEconomy?,
    private val notifier: (VaultEntry, Notice, String?) -> Unit = { _, _, _ -> },
    private val retryBaseMs: Long = 2_000,
    private val retryMaxMs: Long = 60_000
) {
    private val lock = Any()
    private val open = ConcurrentHashMap<String, VaultEntry>()
    private val timers = ConcurrentHashMap<String, McTimer>()
    private val refundFailures = ConcurrentHashMap<String, Int>()

    @Volatile
    private var stopped = false

    /** Loads the journal and starts resolving every entry that was still open (a crash, a restart). */
    fun start() {
        stopped = false
        for (e in journal.load()) {
            if (e.phase == OpPhase.PAYOUT_STARTED || e.phase == OpPhase.REFUND_STARTED) {
                interrupted(e)
                continue
            }
            open[e.id] = e
            log.warn("Vault: resuming ${e.kind} ${e.id} of ${e.username} (${e.phase}, ${e.credits} credits).")
            scheduleResolve(e.id, 1_000)
        }
    }

    /**
     * A payment into the server economy was under way when the server died: it may or may not have been made, and neither
     * repeating nor compensating it can be right in both cases. It is reported for the admin and closed.
     */
    private fun interrupted(e: VaultEntry) {
        val what = if (e.phase == OpPhase.PAYOUT_STARTED) "paying out" else "refunding"
        log.error("Vault: ${e.kind} ${e.id} of ${e.username} was interrupted while ${what} ${e.money} server money against ${e.credits} credits (${e.phase}). Whether the money arrived is UNKNOWN, so it is neither undone nor retried and the entry is closed as INTERRUPTED. Check the player's server balance and the credit ledger transaction with reference ${e.id}.")
        val closedEntry = e.copy(phase = OpPhase.CLOSED, closedReason = "INTERRUPTED", lastCode = e.phase.name, updatedAt = clock.now())
        if (!synchronized(lock) { journal.write(closedEntry) }) {
            log.warn("Vault: the INTERRUPTED record of ${e.id} could not be written; it is reported again at the next start.")
        }
    }

    fun stop() {
        stopped = true
        timers.values.forEach { it.cancel() }
        timers.clear()
        journal.close()
    }

    /** Entries whose outcome is still being resolved (for status output and tests). */
    fun openEntries(): List<VaultEntry> = ArrayList(open.values) // not toList(): that reads size, then first(), and the map can empty in between

    // ---- PROVIDER: one Vault call, at most [timeoutMs] on the calling thread --------------------------------------------

    /**
     * `withdrawPlayer` / `depositPlayer` of the credits economy. Blocks the calling thread for at most [timeoutMs]
     * (Vault's API is synchronous, 19 section 10); the journal write and the send happen on the bridge's own thread.
     */
    fun providerTransfer(withdraw: Boolean, player: PlayerRef, credits: Double, timeoutMs: Long): ProviderOutcome {
        if (stopped) return ProviderOutcome.Stopped
        if (!client.connected()) return ProviderOutcome.NotConnected
        val kind = if (withdraw) OpKind.PROVIDER_WITHDRAW else OpKind.PROVIDER_DEPOSIT
        val entry = newEntry(kind, player, credits, null)
        val gate = CompletableFuture<ProviderOutcome>()
        val state = AtomicInteger(WAITING)

        scheduler.execute {
            // The caller gave up before this ran: nothing was recorded, nothing is sent.
            if (state.get() != WAITING) return@execute
            if (!persistNew(entry, durableRequired = true)) {
                if (state.compareAndSet(WAITING, ANSWERED)) gate.complete(ProviderOutcome.JournalFailed)
                return@execute
            }
            client.send(kind.ledgerOp, player, credits, entry.id, reasonOf(kind)) { answer -> onProviderAnswer(entry, answer, state, gate) }
        }

        return try {
            gate.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            giveUp(state, gate, SETTLE_WAIT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            giveUp(state, gate, 0) // the interrupt flag makes any further wait throw at once: hand over right away
        } catch (e: ExecutionException) {
            log.error("Vault: a PROVIDER operation failed unexpectedly: ${e.cause?.message}", e.cause)
            state.compareAndSet(WAITING, ABANDONED)
            ProviderOutcome.Unknown
        }
    }

    /**
     * The caller's side of the hand-over. Who tells the Vault caller what is decided by two swaps on [state]:
     * - `WAITING -> ABANDONED` (here): nobody had answered; the caller reports FAILURE and the late answer compensates.
     * - `ANSWERED -> SETTLED` (the answer, after its record is durable): the caller learns the outcome from [gate].
     * An answer that wins the race for the gate is being recorded (an fsync that can stall); the caller gives it
     * [patientMs] and then swaps `ANSWERED -> ABANDONED`. Whoever loses a swap knows the other side owns the outcome: an
     * answer that finds the caller gone posts the compensation itself, so FAILURE is never reported for an applied
     * operation that is then left uncompensated.
     */
    private fun giveUp(state: AtomicInteger, gate: CompletableFuture<ProviderOutcome>, patientMs: Long): ProviderOutcome {
        if (state.compareAndSet(WAITING, ABANDONED)) return ProviderOutcome.Unknown
        if (patientMs > 0) {
            try {
                return gate.get(patientMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            } catch (_: ExecutionException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (state.compareAndSet(ANSWERED, ABANDONED)) return ProviderOutcome.Unknown
        return gate.join() // the answer settled in between: its outcome is about to be (or is) in the gate
    }

    private fun onProviderAnswer(entry: VaultEntry, answer: EconomyAnswer, state: AtomicInteger, gate: CompletableFuture<ProviderOutcome>) {
        val id = entry.id
        if (answer is EconomyAnswer.Unknown) {
            // No usable answer: whoever waits is told "unknown" (= FAILURE), the entry stays open and is re-sent.
            if (state.compareAndSet(WAITING, ABANDONED)) gate.complete(ProviderOutcome.Unknown)
            bumpAttempt(id, null)
            scheduleResolve(id, retryBaseMs)
            return
        }
        if (!state.compareAndSet(WAITING, ANSWERED)) {
            // The caller already got "failed": this is the late answer.
            onLedgerAnswer(id, answer, firstAttempt = true)
            return
        }
        when (answer) {
            is EconomyAnswer.Ok -> {
                if (closeDurably(id, "APPLIED")) {
                    if (state.compareAndSet(ANSWERED, SETTLED)) {
                        gate.complete(ProviderOutcome.Success(answer.balance))
                    } else {
                        // The caller ran out of patience while the record was being written and reported FAILURE: the
                        // operation is applied, so it is re-opened and compensated like any other late success.
                        log.warn("Vault: ${entry.kind} $id of ${entry.username} (${entry.credits} credits) was applied while its record was being written and the caller had already been told it failed; posting ${entry.undoId}.")
                        reopenForUndo(entry, "CALLER_GAVE_UP")
                    }
                } else {
                    // The record cannot be closed on disk: after a restart it would look unresolved and be undone although the
                    // caller was told "success". So the caller is told "failed" and the operation is undone now.
                    log.error("Vault: the journal could not record that $id succeeded; it is reported as failed and undone to stay consistent.")
                    gate.complete(ProviderOutcome.Unknown)
                    startUndo(id, "NOT_DURABLE", null)
                }
            }
            is EconomyAnswer.Refused -> {
                close(id, "REFUSED", answer.code)
                if (state.compareAndSet(ANSWERED, SETTLED)) gate.complete(ProviderOutcome.Refused(answer.code, answer.balance))
            }
            is EconomyAnswer.Transient -> {
                close(id, "NOT_APPLIED", answer.reason)
                if (state.compareAndSet(ANSWERED, SETTLED)) gate.complete(ProviderOutcome.Transient(answer.reason))
            }
            EconomyAnswer.Unknown -> Unit
        }
    }

    /** Re-opens an entry that was closed as applied although the caller was told it failed, and posts its compensation. */
    private fun reopenForUndo(entry: VaultEntry, code: String) {
        val next = entry.copy(phase = OpPhase.UNDO_PENDING, lastCode = code, updatedAt = clock.now())
        if (!persistNew(next, durableRequired = false)) {
            log.error("Vault: the journal could not record the compensation owed for ${entry.id} of ${entry.username} (${entry.credits} credits). It is posted from memory only; if the server stops before it is answered, audit this reference in the Pano admin panel.")
        }
        if (!client.connected()) return scheduleResolve(entry.id, retryBaseMs)
        sendUndo(entry.id, null)
    }

    // ---- CONVERT: credits -> server money -------------------------------------------------------------------------------

    /** `/credits convert`: the ledger is charged first (`WITHDRAW`), the server economy is paid after; a failed payout is undone. */
    fun convertToServer(player: PlayerRef, credits: Double, money: Double, done: (ConvertOutcome) -> Unit) {
        if (stopped) return done(ConvertOutcome.NotConnected)
        if (!client.connected()) return done(ConvertOutcome.NotConnected)
        val entry = newEntry(OpKind.CONVERT_TO_SERVER, player, credits, money)
        scheduler.execute {
            if (!persistNew(entry, durableRequired = true)) return@execute done(ConvertOutcome.JournalFailed)
            client.send(EconomyOp.WITHDRAW, player, credits, entry.id, reasonOf(entry.kind)) { a -> afterToServerWithdraw(entry.id, a, done) }
        }
    }

    private fun afterToServerWithdraw(id: String, answer: EconomyAnswer, done: (ConvertOutcome) -> Unit) {
        when (answer) {
            is EconomyAnswer.Ok -> hop(
                { payout(id, answer.balance, done) },
                // The server thread cannot be reached (plugin disabled): the credits are taken, give them back.
                { startUndo(id, "PAYOUT_FAILED") { restored -> done(ConvertOutcome.PayoutFailed("the server thread was not available", restored)) } }
            )
            is EconomyAnswer.Refused -> {
                close(id, "REFUSED", answer.code)
                done(ConvertOutcome.Refused(answer.code, answer.balance, null))
            }
            is EconomyAnswer.Transient -> {
                close(id, "NOT_APPLIED", answer.reason)
                done(ConvertOutcome.NotApplied(answer.reason))
            }
            EconomyAnswer.Unknown -> {
                bumpAttempt(id, null)
                scheduleResolve(id, retryBaseMs)
                done(ConvertOutcome.Unknown)
            }
        }
    }

    /** On the server thread: pay the player; when that fails the credits are given back (`:undo`). */
    private fun payout(id: String, balance: Double?, done: (ConvertOutcome) -> Unit) {
        val e = open[id] ?: return done(ConvertOutcome.Unknown)
        if (e.phase != OpPhase.LEDGER_PENDING) return done(ConvertOutcome.Unknown) // somebody else owns this entry now
        // No record, no payment: after a crash an entry in PAYOUT_STARTED is reported, never undone, because the money may be paid.
        if (!advanceDurably(id, OpPhase.PAYOUT_STARTED)) {
            log.error("Vault: the journal could not record that the payout of ${e.money} to ${e.username} is starting, so it was NOT paid; giving the ${e.credits} credits back (reference ${e.id}).")
            return startUndo(id, "PAYOUT_FAILED") { restored -> done(ConvertOutcome.PayoutFailed("the journal could not record the payout", restored)) }
        }
        val result = try {
            economy()?.deposit(ref(e), e.money ?: 0.0) ?: ServerResult(false, "no server economy")
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            ServerResult(false, t.message ?: t.javaClass.simpleName)
        }
        if (result.ok) {
            close(id, "APPLIED", null)
            return done(ConvertOutcome.Done(balance))
        }
        log.warn("Vault: the server economy refused the payout of ${e.money} to ${e.username} (${result.error}); giving the ${e.credits} credits back.")
        startUndo(id, "PAYOUT_FAILED") { restored -> done(ConvertOutcome.PayoutFailed(result.error, restored)) }
    }

    // ---- CONVERT: server money -> credits -------------------------------------------------------------------------------

    /**
     * `/credits deposit`: the server economy is charged first, then `DEPOSIT` is sent. Must be called on the server thread.
     * Pano's explicit refusal refunds the money; no answer or a transient refusal retries the same `operationId`.
     */
    fun convertToCredits(player: PlayerRef, money: Double, credits: Double, done: (ConvertOutcome) -> Unit) {
        val eco = economy() ?: return done(ConvertOutcome.NoEconomy)
        if (stopped || !client.connected()) return done(ConvertOutcome.NotConnected)
        val taken = try {
            if (!eco.has(player, money)) return done(ConvertOutcome.InsufficientMoney)
            eco.withdraw(player, money)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            ServerResult(false, t.message ?: t.javaClass.simpleName)
        }
        if (!taken.ok) return done(ConvertOutcome.MoneyRefused(taken.error))
        val entry = newEntry(OpKind.CONVERT_TO_CREDITS, player, credits, money)
        scheduler.execute {
            // The money is already taken: the conversion goes on even when the record cannot be made durable (then only
            // a crash in this window loses track of it), but the failure is logged loudly.
            if (!persistNew(entry, durableRequired = false)) {
                log.error("Vault: the journal could not record the deposit ${entry.id} of ${entry.username} (${entry.money} taken from the server economy, ${entry.credits} credits due). It continues in memory only.")
            }
            client.send(EconomyOp.DEPOSIT, player, credits, entry.id, reasonOf(entry.kind)) { a -> afterToCreditsDeposit(entry.id, a, done) }
        }
    }

    private fun afterToCreditsDeposit(id: String, answer: EconomyAnswer, done: (ConvertOutcome) -> Unit) {
        when (answer) {
            is EconomyAnswer.Ok -> {
                close(id, "APPLIED", null)
                done(ConvertOutcome.Done(answer.balance))
            }
            is EconomyAnswer.Refused -> {
                update(id) { it.copy(phase = OpPhase.REFUND_PENDING, lastCode = answer.code) }
                refund(id) { refunded -> done(ConvertOutcome.Refused(answer.code, answer.balance, refunded)) }
            }
            is EconomyAnswer.Transient, EconomyAnswer.Unknown -> {
                // Not applied by this attempt, or not known: the money stays taken, the same deposit is retried.
                bumpAttempt(id, (answer as? EconomyAnswer.Transient)?.reason)
                scheduleResolve(id, retryBaseMs)
                done(ConvertOutcome.Pending)
            }
        }
    }

    // ---- resolution of unknown outcomes ----------------------------------------------------------------------------------

    private fun scheduleResolve(id: String, delayMs: Long) {
        if (stopped) return
        val timer = scheduler.schedule(delayMs) { resolveStep(id) }
        timers.put(id, timer)?.cancel()
    }

    private fun nextDelay(e: VaultEntry): Long {
        val shift = e.attempts.coerceIn(0, 12)
        return minOf(retryMaxMs, retryBaseMs shl shift)
    }

    private fun resolveStep(id: String) {
        timers.remove(id)
        if (stopped) return
        val e = open[id] ?: return
        when (e.phase) {
            OpPhase.CLOSED, OpPhase.PAYOUT_STARTED, OpPhase.REFUND_STARTED -> Unit
            OpPhase.REFUND_PENDING -> refund(id, null)
            OpPhase.LEDGER_PENDING -> {
                if (!client.connected()) return scheduleResolve(id, nextDelay(e))
                bumpAttempt(id, null)
                client.send(e.kind.ledgerOp, ref(e), e.credits, e.id, reasonOf(e.kind)) { a -> onLedgerAnswer(id, a, firstAttempt = false) }
            }
            OpPhase.UNDO_PENDING -> {
                if (!client.connected()) return scheduleResolve(id, nextDelay(e))
                sendUndo(id, null)
            }
        }
    }

    /**
     * The answer to the ORIGINAL ledger request of an entry whose caller no longer waits (a late answer, or a retry).
     * [firstAttempt] = this is the answer of the very first send (nothing before it can have been applied).
     */
    private fun onLedgerAnswer(id: String, answer: EconomyAnswer, firstAttempt: Boolean) {
        val e = open[id] ?: return
        if (e.phase != OpPhase.LEDGER_PENDING) return
        when (answer) {
            is EconomyAnswer.Ok -> {
                if (e.kind.serverMoneyTaken) {
                    close(id, "APPLIED", null)
                    notifier(e, Notice.DEPOSIT_ARRIVED, null)
                } else {
                    // The caller was told "failed" but the ledger applied it: compensate.
                    log.warn("Vault: ${e.kind} ${e.id} of ${e.username} (${e.credits} credits) was applied after the caller was told it failed; posting ${e.undoId}.")
                    startUndo(id, "LATE_SUCCESS", null)
                }
            }
            is EconomyAnswer.Refused -> {
                if (e.kind.serverMoneyTaken) {
                    update(id) { it.copy(phase = OpPhase.REFUND_PENDING, lastCode = answer.code) }
                    refund(id, null)
                } else {
                    close(id, "REFUSED", answer.code)
                }
            }
            is EconomyAnswer.Transient -> {
                if (firstAttempt && !e.kind.serverMoneyTaken) {
                    close(id, "NOT_APPLIED", answer.reason)
                } else {
                    bumpAttempt(id, answer.reason)
                    scheduleResolve(id, nextDelay(open[id] ?: e))
                }
            }
            EconomyAnswer.Unknown -> {
                bumpAttempt(id, null)
                scheduleResolve(id, nextDelay(open[id] ?: e))
            }
        }
    }

    /** Moves an entry to UNDO_PENDING and posts the opposite operation (`<id>:undo`). [done] hears `true` once Pano confirmed it. */
    private fun startUndo(id: String, code: String, done: ((Boolean) -> Unit)?) {
        if (update(id) { it.copy(phase = OpPhase.UNDO_PENDING, lastCode = code) } == null) return run { done?.invoke(false) }
        if (!client.connected()) {
            scheduleResolve(id, retryBaseMs)
            return run { done?.invoke(false) }
        }
        sendUndo(id, done)
    }

    private fun sendUndo(id: String, done: ((Boolean) -> Unit)?) {
        val e = open[id] ?: return
        if (e.phase != OpPhase.UNDO_PENDING) return
        val op = if (e.kind.ledgerOp == EconomyOp.WITHDRAW) EconomyOp.DEPOSIT else EconomyOp.WITHDRAW
        client.send(op, ref(e), e.credits, e.undoId, reasonOf(e.kind) + " (undo)") { answer ->
            val cur = open[id]
            if (cur == null || cur.phase != OpPhase.UNDO_PENDING) return@send
            when (answer) {
                is EconomyAnswer.Ok -> {
                    close(id, "UNDONE", null)
                    if (e.kind == OpKind.CONVERT_TO_SERVER && done == null) notifier(cur, Notice.CONVERSION_UNDONE, null)
                    done?.invoke(true)
                }
                is EconomyAnswer.Refused -> {
                    close(id, "UNDO_REFUSED", answer.code)
                    log.error("Vault: the compensation ${e.undoId} for ${e.username} (${e.credits} credits) was refused by Pano (${answer.code}). The credit ledger and the server economy differ by this amount: audit it in the Pano admin panel.")
                    done?.invoke(false)
                }
                is EconomyAnswer.Transient, EconomyAnswer.Unknown -> {
                    bumpAttempt(id, (answer as? EconomyAnswer.Transient)?.reason)
                    scheduleResolve(id, nextDelay(open[id] ?: cur))
                    done?.invoke(false)
                }
            }
        }
    }

    /** Gives the player's server money back (money -> credits refused by Pano). On the server thread. [done] hears whether it worked. */
    private fun refund(id: String, done: ((Boolean) -> Unit)?) {
        hop(
            { refundOnServerThread(id, done) },
            {
                open[id]?.let { refundFailed(id, it, "the server thread was not available") }
                done?.invoke(false)
            }
        )
    }

    private fun refundOnServerThread(id: String, done: ((Boolean) -> Unit)?) {
        val e = open[id]
        if (e == null || e.phase != OpPhase.REFUND_PENDING) return run { done?.invoke(e == null) }
        // No record, no payment: after a crash an entry in REFUND_STARTED is reported, never refunded again, because the money may be back.
        val result = if (advanceDurably(id, OpPhase.REFUND_STARTED)) {
            try {
                economy()?.deposit(ref(e), e.money ?: 0.0) ?: ServerResult(false, "no server economy")
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                ServerResult(false, t.message ?: t.javaClass.simpleName)
            }
        } else {
            log.error("Vault: the journal could not record that the refund of ${e.money} to ${e.username} is starting, so it was NOT paid; it is retried (reference ${e.id}).")
            ServerResult(false, "the journal could not record the refund")
        }
        if (result.ok) {
            close(id, "REFUNDED", e.lastCode)
            if (done == null) notifier(e, Notice.MONEY_REFUNDED, e.lastCode) else done(true)
        } else {
            val first = refundFailed(id, e, result.error)
            if (done == null) {
                if (first) notifier(e, Notice.MONEY_REFUND_STUCK, e.lastCode)
            } else {
                done(false)
            }
        }
    }

    /** Counts a failed refund and retries later. Returns `true` for the first failure of this refund (the player is told once). */
    private fun refundFailed(id: String, e: VaultEntry, error: String?): Boolean {
        val count = refundFailures.merge(id, 1) { a, b -> a + b } ?: 1
        // Back to REFUND_PENDING (a failed payment provably paid nothing): a crash from here on retries it.
        val n = update(id) { it.copy(phase = OpPhase.REFUND_PENDING, attempts = it.attempts + 1) } ?: return false
        if (count == 1 || count % 10 == 0) {
            log.error("Vault: ${e.money} of ${e.username}'s server money could not be given back after Pano refused the deposit ${e.id} (${e.lastCode}): $error. Retrying; reference ${e.id}.")
        }
        scheduleResolve(id, nextDelay(n))
        return count == 1
    }

    private fun hop(task: () -> Unit, failed: () -> Unit) {
        try {
            mainThread.run(Runnable { task() })
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log.warn("Vault: a task could not be handed to the server thread: ${t.message}")
            failed()
        }
    }

    // ---- journal + bookkeeping --------------------------------------------------------------------------------------------

    private fun newEntry(kind: OpKind, player: PlayerRef, credits: Double, money: Double?): VaultEntry {
        val now = clock.now()
        return VaultEntry(UUID.randomUUID().toString(), kind, player.username, player.uuid, credits, money, OpPhase.LEDGER_PENDING, now, now)
    }

    private fun ref(e: VaultEntry) = PlayerRef(e.username, e.uuid)

    private fun reasonOf(kind: OpKind) = when (kind) {
        OpKind.PROVIDER_WITHDRAW -> "vault withdraw"
        OpKind.PROVIDER_DEPOSIT -> "vault deposit"
        OpKind.CONVERT_TO_SERVER -> "convert to server"
        OpKind.CONVERT_TO_CREDITS -> "convert to credits"
    }

    /** Records a new entry. With [durableRequired] a failed journal write means the entry does not exist (nothing may be sent). */
    private fun persistNew(e: VaultEntry, durableRequired: Boolean): Boolean {
        synchronized(lock) {
            val ok = journal.write(e)
            if (ok || !durableRequired) open[e.id] = e
            return ok
        }
    }

    private fun update(id: String, change: (VaultEntry) -> VaultEntry): VaultEntry? {
        synchronized(lock) {
            val cur = open[id] ?: return null
            val next = change(cur).copy(updatedAt = clock.now())
            journal.write(next)
            if (next.phase == OpPhase.CLOSED) open.remove(id) else open[id] = next
            return next
        }
    }

    /**
     * Moves an entry to [phase] and reports `true` only when the journal made that durable (an fsync). When it did not, the
     * entry keeps its previous phase in memory too, and the caller must not do whatever the phase announces.
     */
    private fun advanceDurably(id: String, phase: OpPhase): Boolean {
        synchronized(lock) {
            val cur = open[id] ?: return false
            val next = cur.copy(phase = phase, updatedAt = clock.now())
            if (!journal.write(next)) return false
            open[id] = next
            return true
        }
    }

    /** Closes an entry. A close that cannot be written leaves a stale open record on disk: that is logged, the operation itself is done. */
    private fun close(id: String, reason: String, code: String?) {
        val e = open[id]
        if (!closeDurably(id, reason, code) && e != null) {
            synchronized(lock) { open.remove(id) }
            log.error("Vault: ${e.kind} $id of ${e.username} is finished ($reason) but the journal could not record it. After a restart it is resolved from the last record that did reach the disk: an entry whose payment into the server economy had started is reported as INTERRUPTED and neither undone nor retried, any other is checked against Pano again (same ids, nothing is posted twice). Audit it by this reference.")
        }
        timers.remove(id)?.cancel()
        refundFailures.remove(id)
    }

    /** Writes the CLOSED record; `false` when it is not durable (the entry then stays open in memory and on disk). */
    private fun closeDurably(id: String, reason: String, code: String? = null): Boolean {
        synchronized(lock) {
            val cur = open[id] ?: return true
            val next = cur.copy(phase = OpPhase.CLOSED, closedReason = reason, lastCode = code ?: cur.lastCode, updatedAt = clock.now())
            if (!journal.write(next)) return false
            open.remove(id)
            return true
        }
    }

    private fun bumpAttempt(id: String, code: String?) {
        update(id) { it.copy(attempts = it.attempts + 1, lastCode = code ?: it.lastCode) }
    }

    private companion object {
        const val WAITING = 0
        const val ANSWERED = 1
        const val ABANDONED = 2
        const val SETTLED = 3

        /** How long a caller whose budget ran out still waits for an answer that already won the race and is being recorded. */
        const val SETTLE_WAIT_MS = 1_000L
    }
}
