package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.spi.shipping.AddressResolution
import com.panomc.plugins.market.spi.shipping.QuoteResult

/**
 * The live carrier rate cache and the circuit breaker of 10 section 5.4. In memory, one instance per plugin: it is a
 * latency and cost optimisation only, the price that counts is the one frozen on the order.
 *
 * - An entry is *fresh* for `quoteCacheSeconds` of the provider (0 = never reused for quoting): a quote inside that window makes no call.
 * - It is *honoured* for `max(quoteCacheSeconds, 1 800 s)` and never once every rate in it has expired: checkout reuses it
 *   without a call, so the buyer pays the price that was displayed.
 * - Failures are never cached (the breaker covers them). Saving a carrier's settings clears its entries ([invalidate]).
 * - LRU, [capacity] entries.
 */
class ShippingQuoteCache(private val clock: Clock, private val capacity: Int = DEFAULT_CAPACITY) {
    private class Entry(val providerId: String, val value: Any, val storedAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }

    @Synchronized
    fun size(): Int = entries.size

    // ---- rates

    @Synchronized
    fun put(key: String, providerId: String, result: QuoteResult) {
        entries[key] = Entry(providerId, result, clock.now())
    }

    /** The entry a quote may reuse: stored less than [quoteCacheSeconds] ago (never when that is 0). */
    @Synchronized
    fun fresh(key: String, quoteCacheSeconds: Int): QuoteResult? = lookup(key, quoteCacheSeconds.toLong() * 1000) as? QuoteResult

    /** The entry checkout may reuse: stored less than `max(quoteCacheSeconds, 1 800)` seconds ago and still holding an unexpired rate. */
    @Synchronized
    fun honoured(key: String, quoteCacheSeconds: Int): QuoteResult? {
        val result = lookup(key, maxOf(quoteCacheSeconds.toLong(), HONOUR_SECONDS) * 1000) as? QuoteResult ?: return null
        val now = clock.now()

        return result.takeIf { r -> r.rates.any { it.expiresAt == null || it.expiresAt!! > now } }
    }

    // ---- address resolution (10 section 3.4): `true` = the carrier said the address is valid, `false` = it said invalid

    @Synchronized
    fun putResolution(key: String, providerId: String, resolution: AddressResolution) {
        if (!resolution.supported) return

        entries[key] = Entry(providerId, resolution.valid, clock.now())
    }

    @Synchronized
    fun freshResolution(key: String, quoteCacheSeconds: Int): Boolean? = lookup(key, quoteCacheSeconds.toLong() * 1000) as? Boolean

    @Synchronized
    fun honouredResolution(key: String, quoteCacheSeconds: Int): Boolean? =
        lookup(key, maxOf(quoteCacheSeconds.toLong(), HONOUR_SECONDS) * 1000) as? Boolean

    private fun lookup(key: String, windowMs: Long): Any? {
        if (windowMs <= 0) return null

        val entry = entries[key] ?: return null

        return if (clock.now() - entry.storedAt < windowMs) entry.value else null
    }

    /** Drops every entry of [providerId] (its settings changed). */
    @Synchronized
    fun invalidate(providerId: String) {
        entries.values.removeIf { it.providerId == providerId }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 5_000

        /** The least time checkout honours a displayed carrier price (10 section 5.4). */
        const val HONOUR_SECONDS = 1_800L
    }
}

/**
 * Per provider circuit breaker (10 section 5.4): [THRESHOLD] consecutive failures within [WINDOW_MS] open it for
 * [OPEN_MS]; a success resets it. While open no live call is made (the rate is "none" at once).
 */
class QuoteBreaker(private val clock: Clock) {
    private class State {
        val failures = ArrayDeque<Long>()
        var openUntil = 0L
    }

    private val states = HashMap<String, State>()

    @Synchronized
    fun allow(providerId: String): Boolean {
        val state = states[providerId] ?: return true

        if (clock.now() < state.openUntil) return false

        if (state.openUntil != 0L) {
            // the open period is over: start counting again
            state.openUntil = 0L
            state.failures.clear()
        }

        return true
    }

    @Synchronized
    fun failure(providerId: String) {
        val now = clock.now()
        val state = states.getOrPut(providerId) { State() }

        while (state.failures.isNotEmpty() && now - state.failures.first() > WINDOW_MS) state.failures.removeFirst()

        state.failures.addLast(now)

        if (state.failures.size >= THRESHOLD) state.openUntil = now + OPEN_MS
    }

    @Synchronized
    fun success(providerId: String) {
        states.remove(providerId)
    }

    @Synchronized
    fun isOpen(providerId: String): Boolean = states[providerId]?.let { clock.now() < it.openUntil } ?: false

    companion object {
        const val THRESHOLD = 3
        const val WINDOW_MS = 60_000L
        const val OPEN_MS = 60_000L
    }
}
