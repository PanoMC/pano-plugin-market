package com.panomc.plugins.market.mc.spigot.vault

import com.panomc.plugins.market.mc.core.feature.Messages
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/** The answer of one credits-economy call, ready to become a Vault `EconomyResponse`. */
data class EconomyResult(val ok: Boolean, val amount: Double, val balance: Double, val error: String?)

/**
 * Pano credits as a server economy (PROVIDER mode, 19 section 10), without any Vault class (the adapter is
 * `ProviderEconomy`). Reads answer from a per-player cache and never touch the network on the calling thread:
 * - loaded at the authenticated join, refreshed after every operation and every [refreshMs] for the players who are
 *   present, a stale entry that is read is refreshed in the background;
 * - an unknown player answers "no account, balance 0" while the load runs (fail closed: `has` is false, nothing is
 *   ever approved from a guess; the ledger decides every real withdrawal anyway).
 * Writes go to the ledger through [VaultOps.providerTransfer] (at most [timeoutMs] on the calling thread); any
 * failure, refusal, disconnect or timeout is a FAILURE for the caller.
 */
class CreditEconomy(
    private val ops: VaultOps,
    private val client: EconomyClient,
    private val scheduler: McScheduler,
    private val clock: McClock,
    private val log: McLog,
    private val uuidOf: (String) -> String?,
    private val presentNames: () -> List<String>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val refreshMs: Long = DEFAULT_REFRESH_MS,
    private val evictAfterMs: Long = 10 * 60_000L,
    /** Pano pushes balance changes ([onBalanceChanged]): then the timer no longer reloads balances, it only drops old entries. */
    private val pushActive: () -> Boolean = { false },
    /** The error texts (Vault `errorMessage`, printed to players by other plugins); bundled [VaultTexts] by default. */
    private val texts: VaultMessages = VaultMessages(Messages({ null })),
    /** The locale of a player for those texts, `null` = the default chain of [Messages]. */
    private val localeOf: (String) -> String? = { null }
) {
    private class Entry(val balance: Double, val registered: Boolean, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var timer: McTimer? = null

    @Volatile
    private var running = false

    // ---- reads (cache only) ------------------------------------------------------------------------------------------------

    fun registered(name: String?): Boolean {
        val e = lookup(name) ?: return false
        return e.registered
    }

    fun balance(name: String?): Double = lookup(name)?.takeIf { it.registered }?.balance ?: 0.0

    fun has(name: String?, amount: Double): Boolean {
        if (amount.isNaN() || amount < 0.0) return false
        val e = lookup(name) ?: return false
        return e.registered && e.balance + EPS >= amount
    }

    /** The entry for [name]; a missing or stale one starts a background load (never a network call here). */
    private fun lookup(name: String?): Entry? {
        if (name.isNullOrBlank()) return null
        val e = cache[key(name)]
        if (e == null || clock.now() - e.at > refreshMs) load(name)
        return e
    }

    // ---- writes (the ledger decides) ---------------------------------------------------------------------------------------

    fun withdraw(name: String?, amount: Double): EconomyResult = transfer(true, name, amount)

    fun deposit(name: String?, amount: Double): EconomyResult = transfer(false, name, amount)

    private fun transfer(withdraw: Boolean, name: String?, amount: Double): EconomyResult {
        val known = balance(name)
        if (name.isNullOrBlank()) return fail(0.0, say(null, VaultMsg.ECONOMY_NO_NAME))
        if (amount.isNaN() || amount.isInfinite()) return fail(known, say(name, VaultMsg.ECONOMY_NOT_NUMBER))
        if (amount < 0.0) return fail(known, say(name, if (withdraw) VaultMsg.ECONOMY_NEGATIVE_WITHDRAW else VaultMsg.ECONOMY_NEGATIVE_DEPOSIT))
        // Credits have two decimals: rounding never favours the player (a withdrawal is rounded up, a deposit down).
        val credits = BigDecimal.valueOf(amount).setScale(2, if (withdraw) RoundingMode.UP else RoundingMode.DOWN).toDouble()
        if (credits == 0.0) return EconomyResult(true, 0.0, known, null)
        val player = PlayerRef(name, uuidOf(name))
        return when (val outcome = ops.providerTransfer(withdraw, player, credits, timeoutMs)) {
            is ProviderOutcome.Success -> {
                val balance = outcome.balance
                if (balance != null) put(name, balance, true)
                EconomyResult(true, credits, balance ?: known, null)
            }
            is ProviderOutcome.Refused -> {
                when (outcome.code) {
                    "NO_ACCOUNT" -> put(name, 0.0, false)
                    else -> outcome.balance?.let { put(name, it, true) }
                }
                fail(outcome.balance ?: known, refusalText(name, outcome.code))
            }
            is ProviderOutcome.Transient -> fail(known, say(name, VaultMsg.ECONOMY_BUSY, "reason" to outcome.reason))
            ProviderOutcome.NotConnected -> fail(known, say(name, VaultMsg.ECONOMY_NOT_CONNECTED))
            ProviderOutcome.Unknown -> {
                load(name) // the real balance is learned in the background, whichever way the operation ends
                fail(known, say(name, VaultMsg.ECONOMY_UNKNOWN))
            }
            ProviderOutcome.JournalFailed -> fail(known, say(name, VaultMsg.ECONOMY_JOURNAL_FAILED))
            ProviderOutcome.Stopped -> fail(known, say(name, VaultMsg.ECONOMY_STOPPING))
        }
    }

    private fun refusalText(name: String, code: String) = when (code) {
        "INSUFFICIENT_CREDITS" -> say(name, VaultMsg.ECONOMY_INSUFFICIENT)
        "NO_ACCOUNT" -> say(name, VaultMsg.ECONOMY_NO_ACCOUNT)
        "CREDITS_DISABLED" -> say(name, VaultMsg.CREDITS_OFF)
        else -> say(name, VaultMsg.ECONOMY_REFUSED, "code" to code)
    }

    /** The text of [key] in the locale of the player [name] (the default chain when unknown). */
    private fun say(name: String?, key: String, vararg args: Pair<String, Any?>): String {
        val locale = name?.let { runCatching { localeOf(it) }.getOrNull() }
        return texts.text(key, locale, *args)
    }

    private fun fail(balance: Double, message: String) = EconomyResult(false, 0.0, balance, message)

    // ---- cache upkeep ------------------------------------------------------------------------------------------------------

    /** The authenticated join: load the balance. */
    fun onPresent(name: String) = load(name)

    private fun key(name: String) = name.lowercase()

    private fun put(name: String, balance: Double, registered: Boolean) {
        cache[key(name)] = Entry(balance, registered, clock.now())
    }

    /** Loads one player's balance on the bridge's own thread. One load per player at a time. */
    private fun load(name: String) {
        val k = key(name)
        if (!running) return
        if (!loading.add(k)) return
        scheduler.execute {
            try {
                if (!client.connected()) {
                    loading.remove(k)
                    return@execute
                }
                client.balance(PlayerRef(name, uuidOf(name))) { answer ->
                    try {
                        when (answer) {
                            is EconomyAnswer.Ok -> answer.balance?.let { put(name, it, true) }
                            is EconomyAnswer.Refused -> if (answer.code == "NO_ACCOUNT") put(name, 0.0, false)
                            else -> Unit // keep what is cached; the next refresh asks again
                        }
                    } finally {
                        loading.remove(k)
                    }
                }
            } catch (t: Throwable) {
                loading.remove(k)
                if (t is VirtualMachineError) throw t
                log.warn("Vault: the balance of $name could not be requested: ${t.message}")
            }
        }
    }

    /** Starts the periodic refresh of the players who are present (and drops entries nobody has needed for a while). */
    fun start() {
        running = true
        scheduleRefresh()
    }

    fun stop() {
        running = false
        timer?.cancel()
        timer = null
        cache.clear()
        loading.clear()
    }

    private fun scheduleRefresh() {
        if (!running) return
        timer = scheduler.schedule(refreshMs) {
            try {
                refreshPresent()
            } finally {
                scheduleRefresh()
            }
        }
    }

    /** Pano says these players' balances changed: the ones this server holds are loaded again at once. */
    fun onBalanceChanged(names: List<String>) {
        if (!running) return
        val present = presentNames().map { key(it) }.toSet()
        names.filter { key(it) in present || cache.containsKey(key(it)) }.forEach { load(it) }
    }

    /** One refresh round (the timer calls it; public so a test can run a round without waiting). */
    fun refreshPresent() {
        val present = presentNames()
        if (!pushActive()) present.forEach { load(it) }
        val keep = present.map { key(it) }.toSet()
        val now = clock.now()
        cache.entries.removeIf { it.key !in keep && now - it.value.at > evictAfterMs }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_500L
        const val DEFAULT_REFRESH_MS = 30_000L
        private const val EPS = 1e-9
    }
}
