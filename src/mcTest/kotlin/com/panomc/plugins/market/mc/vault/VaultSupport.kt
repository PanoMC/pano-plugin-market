package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.feature.GameLink
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer
import com.panomc.plugins.market.mc.core.store.AppendSink
import com.panomc.plugins.market.mc.core.support.TestClock
import com.panomc.plugins.market.mc.core.wire.EconomyOp
import com.panomc.plugins.market.mc.core.wire.MarketEconomyMessage
import com.panomc.plugins.market.mc.core.wire.MarketEconomyRequest
import com.panomc.plugins.market.mc.core.wire.MarketRequest
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.spigot.vault.MainThread
import com.panomc.plugins.market.mc.spigot.vault.ServerEconomy
import com.panomc.plugins.market.mc.spigot.vault.ServerResult
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import java.math.BigDecimal
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList

/** Everything that happened, in order, across the fake Pano and the fake server economy (ledger-first assertions read it). */
class Events {
    private val list = CopyOnWriteArrayList<String>()
    fun add(e: String) {
        list.add(e)
    }

    fun all(): List<String> = list.toList()
    fun clear() = list.clear()
}

/**
 * Pano's side of `MARKET_ECONOMY`, as 19 section 7.5 / 07 section 3.1 describe it: one ledger transaction per
 * `operationId` (a repeat is answered from the first one and posts nothing), `NO_ACCOUNT` for a player without an account,
 * `INSUFFICIENT_CREDITS` for a withdrawal above the balance.
 */
class FakeLedger(private val events: Events? = null) {
    private val balances = HashMap<String, BigDecimal>()
    val transactions = LinkedHashMap<String, Triple<String, String, BigDecimal>>() // operationId -> (op, user, amount)
    private val answers = HashMap<String, MarketEconomyMessage>()

    @Volatile
    var creditsDisabled = false

    @Synchronized
    fun account(user: String, balance: String) {
        balances[user.lowercase()] = BigDecimal(balance)
    }

    @Synchronized
    fun balance(user: String): BigDecimal? = balances[user.lowercase()]

    @Synchronized
    fun apply(r: MarketEconomyRequest): MarketEconomyMessage {
        answers[r.operationId]?.let { first ->
            events?.add("ledger ${r.op} ${r.operationId} (repeat)")
            return first.copy(balance = balances[r.player.username.lowercase()]?.toDouble() ?: first.balance)
        }
        events?.add("ledger ${r.op} ${r.operationId}")
        val user = r.player.username.lowercase()
        val bal = balances[user]
        val reply: MarketEconomyMessage = when {
            creditsDisabled -> MarketEconomyMessage(true, null, false, "CREDITS_DISABLED", null)
            bal == null -> MarketEconomyMessage(true, null, false, "NO_ACCOUNT", null)
            r.op == EconomyOp.BALANCE -> MarketEconomyMessage(true, null, true, null, bal.toDouble())
            else -> {
                val amount = BigDecimal.valueOf(r.amount ?: 0.0)
                if (r.op == EconomyOp.WITHDRAW && bal < amount) {
                    MarketEconomyMessage(true, null, false, "INSUFFICIENT_CREDITS", bal.toDouble())
                } else {
                    val next = if (r.op == EconomyOp.WITHDRAW) bal - amount else bal + amount
                    balances[user] = next
                    transactions[r.operationId] = Triple(r.op, user, amount)
                    MarketEconomyMessage(true, null, true, null, next.toDouble())
                }
            }
        }
        if (r.op != EconomyOp.BALANCE) answers[r.operationId] = reply
        return reply
    }
}

/** What the fake link does with one request. */
sealed class Delivery {
    /** Pano applies it and answers. */
    object Normal : Delivery()

    /** Pano applies it, the answer is lost (the GameLink gives up: callback `null`). */
    object AppliedNoAnswer : Delivery()

    /** Pano never gets it (callback `null`). */
    object Lost : Delivery()

    /** Pano applies it now; the answer is held back until [EconomyLink.releaseHeld]. */
    object HeldApplied : Delivery()

    /** Pano answers `accepted = false` with this reason. */
    data class NotAccepted(val reason: String) : Delivery()
}

/** The Pano connection of the Vault tests: records every `MARKET_ECONOMY` request, applies it to a [FakeLedger] as scripted. */
class EconomyLink(val ledger: FakeLedger, private val events: Events? = null) : GameLink {
    @Volatile
    var up = true

    val requests = CopyOnWriteArrayList<MarketEconomyRequest>()
    val threads = CopyOnWriteArrayList<String>()

    /** Decides per request what happens; the default is a normal, answered request. */
    @Volatile
    var script: (MarketEconomyRequest, Int) -> Delivery = { _, _ -> Delivery.Normal }

    private class Held(val answer: MarketEconomyMessage, val callback: (MarketEconomyMessage?) -> Unit)

    private val held = CopyOnWriteArrayList<Held>()

    override fun connected() = up

    @Suppress("UNCHECKED_CAST")
    override fun <R : PlatformMessageResponse> request(request: MarketRequest, responseType: Class<R>, callback: (R?) -> Unit) {
        val r = request as MarketEconomyRequest
        val attempt = requests.count { it.operationId == r.operationId }
        requests.add(r)
        threads.add(Thread.currentThread().name)
        val cb = callback as (MarketEconomyMessage?) -> Unit
        when (val d = script(r, attempt)) {
            Delivery.Normal -> cb(ledger.apply(r))
            Delivery.AppliedNoAnswer -> {
                ledger.apply(r)
                cb(null)
            }
            Delivery.Lost -> {
                events?.add("lost ${r.op} ${r.operationId}")
                cb(null)
            }
            Delivery.HeldApplied -> {
                val answer = ledger.apply(r)
                synchronized(held) { held.add(Held(answer, cb)) }
            }
            is Delivery.NotAccepted -> cb(MarketEconomyMessage(false, d.reason))
        }
    }

    fun heldCount() = held.size

    /** Delivers every held answer now (the late answer of a request whose caller already gave up). */
    fun releaseHeld() {
        val all = synchronized(held) { held.toList().also { held.clear() } }
        all.forEach { it.callback(it.answer) }
    }

    fun of(op: String) = requests.filter { it.op == op }
    fun ids() = requests.map { it.operationId }.distinct()
}

/** The server economy: balances per player (money), a shared [Events] log, and switches to make it refuse. */
class FakeServerEconomy(private val events: Events? = null, override val name: String = "FakeEco") : ServerEconomy {
    private val money = HashMap<String, BigDecimal>()
    val calls = CopyOnWriteArrayList<String>()

    @Volatile
    var refuseDeposit = false

    @Volatile
    var refuseWithdraw = false

    /** Number of deposits that still fail before one works (a refund that needs a few tries). */
    @Volatile
    var failDeposits = 0

    @Volatile
    var throwOnDeposit = false

    @Synchronized
    fun set(player: String, amount: String) {
        money[player.lowercase()] = BigDecimal(amount)
    }

    @Synchronized
    fun balance(player: String): BigDecimal = money[player.lowercase()] ?: BigDecimal.ZERO

    override fun format(amount: Double): String = "$" + BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    @Synchronized
    override fun has(player: PlayerRef, amount: Double): Boolean = balance(player.username) >= BigDecimal.valueOf(amount)

    @Synchronized
    override fun withdraw(player: PlayerRef, amount: Double): ServerResult {
        calls.add("withdraw ${player.username} $amount")
        events?.add("economy withdraw ${player.username} $amount")
        if (refuseWithdraw) return ServerResult(false, "refused")
        val b = balance(player.username)
        val a = BigDecimal.valueOf(amount)
        if (b < a) return ServerResult(false, "insufficient")
        money[player.username.lowercase()] = b - a
        return ServerResult(true)
    }

    @Synchronized
    override fun deposit(player: PlayerRef, amount: Double): ServerResult {
        calls.add("deposit ${player.username} $amount")
        events?.add("economy deposit ${player.username} $amount")
        if (throwOnDeposit) throw IllegalStateException("economy exploded")
        if (refuseDeposit) return ServerResult(false, "refused")
        if (failDeposits > 0) {
            failDeposits--
            return ServerResult(false, "temporarily unavailable")
        }
        money[player.username.lowercase()] = balance(player.username) + BigDecimal.valueOf(amount)
        return ServerResult(true)
    }
}

/** Runs everything inline on the calling thread; timers are virtual ([advance] moves the shared [TestClock]). */
class InlineScheduler(private val clock: TestClock) : McScheduler {
    private class Task(val due: Long, val order: Long, val body: () -> Unit) {
        @Volatile
        var cancelled = false
    }

    private var counter = 0L
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.due }, { it.order }))

    override fun execute(task: () -> Unit) = task()

    @Synchronized
    override fun schedule(delayMs: Long, task: () -> Unit): McTimer {
        val t = Task(clock.now() + delayMs.coerceAtLeast(0), counter++, task)
        queue.add(t)
        return object : McTimer {
            override fun cancel() {
                t.cancelled = true
            }
        }
    }

    @Synchronized
    private fun nextDue(limit: Long): Task? {
        while (true) {
            val head = queue.peek() ?: return null
            if (head.cancelled) {
                queue.poll()
                continue
            }
            if (head.due > limit) return null
            return queue.poll()
        }
    }

    /** Moves the clock forward by [ms], running every timer that falls due on the way (timers they set run too if due). */
    fun advance(ms: Long) {
        val target = clock.now() + ms
        var guard = 0
        while (true) {
            val t = nextDue(target) ?: break
            if (t.due > clock.now()) clock.current = t.due
            t.body()
            check(++guard < 100_000) { "scheduler does not settle" }
        }
        clock.current = target
    }

    @Synchronized
    fun pending(): Int = queue.count { !it.cancelled }
}

/** Tasks for the server thread wait until the test runs them (or run at once with [inline]). */
class ManualMainThread(var inline: Boolean = false) : MainThread {
    private val tasks = CopyOnWriteArrayList<Runnable>()

    @Volatile
    var refuse: Throwable? = null

    override fun run(task: Runnable) {
        refuse?.let { throw it }
        if (inline) task.run() else tasks.add(task)
    }

    fun pending() = tasks.size

    fun runAll() {
        while (tasks.isNotEmpty()) {
            val t = tasks.removeAt(0)
            t.run()
        }
    }
}

/** An [AppendSink] over memory that can refuse writes (a full disk). */
class MemorySink : AppendSink {
    val bytes = java.io.ByteArrayOutputStream()

    @Volatile
    var failing = false

    /** Appends that still work before the disk fills up (-1 = no limit). */
    @Volatile
    var okAppends = -1

    override fun size() = bytes.size().toLong()
    override fun append(bytes: ByteArray) {
        if (failing || okAppends == 0) throw java.io.IOException("disk full")
        if (okAppends > 0) okAppends--
        this.bytes.write(bytes)
    }

    override fun truncate(size: Long) = Unit
    override fun close() = Unit
}

/** `100`, not `1E+2`: BigDecimal values compared as plain text. */
fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()

fun player(name: String = "Steve", uuid: String? = "11111111-1111-1111-1111-111111111111") = PlayerRef(name, uuid)

/** The fakes of the Vault tests wired together: ledger, link, server economy, inline scheduler, virtual clock and the ops over a journal in [dir]. */
class VaultRig(dir: java.nio.file.Path, mainInline: Boolean = true) {
    val log = com.panomc.plugins.market.mc.core.support.TestLog()
    val clock = TestClock()
    val events = Events()
    val ledger = FakeLedger(events)
    val link = EconomyLink(ledger, events)
    val scheduler = InlineScheduler(clock)
    val eco = FakeServerEconomy(events)
    val mainThread = ManualMainThread(mainInline)
    val notices = CopyOnWriteArrayList<Triple<com.panomc.plugins.market.mc.spigot.vault.VaultEntry, com.panomc.plugins.market.mc.spigot.vault.Notice, String?>>()

    @Volatile
    var hasEconomy = true
    val journal = com.panomc.plugins.market.mc.spigot.vault.VaultJournal(dir.resolve("vault"), log, clock::now)
    val client = com.panomc.plugins.market.mc.spigot.vault.EconomyClient(link, "1.4.0", log)
    val ops = com.panomc.plugins.market.mc.spigot.vault.VaultOps(
        client, journal, scheduler, clock, log, mainThread,
        economy = { if (hasEconomy) eco else null },
        notifier = { e, n, c -> notices.add(Triple(e, n, c)) },
        retryBaseMs = 1_000, retryMaxMs = 8_000
    ).also { it.start() }

    fun settle() {
        var rounds = 0
        while (ops.openEntries().isNotEmpty() && rounds++ < 60) scheduler.advance(10_000)
    }
}
