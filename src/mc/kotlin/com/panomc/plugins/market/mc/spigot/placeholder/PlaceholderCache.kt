package com.panomc.plugins.market.mc.spigot.placeholder

import com.panomc.plugins.market.mc.core.feature.EffectiveConfig
import com.panomc.plugins.market.mc.core.feature.Feature
import com.panomc.plugins.market.mc.core.feature.GameLink
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.core.wire.QueryArgs
import com.panomc.plugins.market.mc.core.wire.QueryType
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The data behind the PlaceholderAPI expansion (19 section 9, `mcPlaceholders`). PlaceholderAPI asks for values on the main
 * thread, often several times per tick per player, so:
 * - [resolve] only reads the snapshot (the last `MARKET_QUERY PLACEHOLDERS` answer, valid for 30 seconds) and answers an empty
 *   string while a value is unknown or the feature is off. It never talks to the network and never blocks;
 * - a stale snapshot makes [resolve] hand ONE refresh to [executor] (default: a single daemon thread) and return the old value
 *   meanwhile; the refresh sends the request through the callback based [GameLink], at most one in flight, at most one per
 *   [refreshMs] even when Pano does not answer;
 * - the players of the refresh are the ones PlaceholderAPI asked about in the last two minutes (online players only,
 *   at most 100, 19 section 7.2).
 */
class PlaceholderCache(
    private val config: EffectiveConfig,
    private val link: GameLink,
    private val componentVersion: String,
    private val log: McLog,
    private val clock: McClock = SystemMcClock,
    private val executor: (Runnable) -> Unit = DEFAULT_EXECUTOR,
    private val isOnline: (String) -> Boolean = { true },
    private val refreshMs: Long = REFRESH_MS
) {
    private class Snapshot(val data: MarketQueryData, val at: Long)

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var lastAttempt = NEVER
    private val refreshing = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val wanted = ConcurrentHashMap<String, Long>()

    /** The value of `%panomarket_<identifier>%`; [username] is the player PlaceholderAPI asks about, `null` for a global call. */
    fun resolve(identifier: String, username: String?): String {
        if (!config.enabled(Feature.PLACEHOLDERS)) return ""
        val name = identifier.lowercase()
        if (name !in IDENTIFIERS) return ""
        val now = clock.now()
        if (username != null && username.isNotEmpty()) wanted[username.lowercase()] = now
        requestRefreshIfStale(now)
        val data = snapshot?.data ?: return ""
        return when (name) {
            "credits" -> balance(data, username)?.let { plain(it) } ?: ""
            "credits_formatted" -> balance(data, username)?.let { formatted(it) } ?: ""
            "last_buyer" -> data.lastBuyer ?: ""
            "top_supporter" -> data.topSupporter ?: ""
            "goal_name" -> data.goalName ?: ""
            "goal_percent" -> data.goalPercent?.let { plain(it) } ?: ""
            "goal_progress" -> data.goalProgress?.let { plain(it) } ?: ""
            "goal_target" -> data.goalTarget?.let { plain(it) } ?: ""
            else -> ""
        }
    }

    private fun balance(data: MarketQueryData, username: String?): Double? {
        if (username == null) return null
        val players = data.players ?: return null
        return (players[username] ?: players.entries.firstOrNull { it.key.equals(username, true) }?.value)?.balance
    }

    private fun requestRefreshIfStale(now: Long) {
        val taken = snapshot
        val fresh = taken != null && now - taken.at < refreshMs
        if (fresh || (lastAttempt != NEVER && now - lastAttempt < refreshMs)) return
        if (!scheduled.compareAndSet(false, true)) return
        try {
            executor(Runnable {
                try {
                    refresh()
                } finally {
                    scheduled.set(false)
                }
            })
        } catch (t: Throwable) {
            scheduled.set(false)
            log.warn("A placeholder refresh could not be scheduled: ${t.message}")
        }
    }

    /** One refresh round trip; runs on the executor (never on the thread that asked for a value). Visible for tests. */
    internal fun refresh() {
        if (!config.enabled(Feature.PLACEHOLDERS)) return
        if (!refreshing.compareAndSet(false, true)) return
        val now = clock.now()
        lastAttempt = now
        try {
            if (!link.connected()) return refreshing.set(false)
            wanted.entries.removeIf { now - it.value > WANTED_MS }
            val names = wanted.keys.filter { isOnline(it) }.sorted().take(MAX_PLAYERS)
            val request = MarketQueryRequest(
                componentVersion, type = QueryType.PLACEHOLDERS, player = null, page = null, args = QueryArgs(usernames = names)
            )
            link.request(request, MarketQueryMessage::class.java) { answer ->
                try {
                    val data = answer?.takeIf { it.accepted }?.data
                    if (data != null) snapshot = Snapshot(data, clock.now())
                } finally {
                    refreshing.set(false)
                }
            }
        } catch (t: Throwable) {
            refreshing.set(false)
            log.warn("A placeholder refresh failed: ${t.message}")
        }
    }

    companion object {
        const val REFRESH_MS = 30_000L
        private const val NEVER = -1L
        private const val WANTED_MS = 2 * 60_000L
        private const val MAX_PLAYERS = 100

        val IDENTIFIERS = setOf(
            "credits", "credits_formatted", "last_buyer", "top_supporter", "goal_name", "goal_percent", "goal_progress", "goal_target"
        )

        private val DEFAULT_EXECUTOR: (Runnable) -> Unit = run {
            val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "PanoMarket-placeholders").also { it.isDaemon = true } }
            val submit: (Runnable) -> Unit = { pool.execute(it) }
            submit
        }

        private fun decimal(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP)

        /** `12.5`, `3`, `0.07` : two decimals at most, no trailing zeros, never an exponent. */
        fun plain(v: Double): String = decimal(v).stripTrailingZeros().toPlainString()

        /** `1,234.50`: grouped thousands, always two decimals (locale independent). */
        fun formatted(v: Double): String =
            DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ROOT)).format(decimal(v))
    }
}
