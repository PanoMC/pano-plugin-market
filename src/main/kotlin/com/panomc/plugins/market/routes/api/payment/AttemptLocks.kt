package com.panomc.plugins.market.routes.api.payment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The in-process serialisation of the calls for one attempt (02 section 10 guarantee 3): `handleInbound` of a `NOTIFY` / `RETURN`, a status query,
 * a cancel, a refund. [PaymentContext.withAttemptLock][com.panomc.plugins.market.spi.payment.PaymentContext.withAttemptLock] is this lock too, and it is
 * reentrant for the coroutine that already holds it: market holds it around `handleInbound` and a provider that takes it again inside the handler
 * must not wait for itself.
 *
 * An entry exists only while a call holds or awaits it, so the map never grows with the number of attempts.
 */
class AttemptLocks {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    /** The attempts the current coroutine already holds (what makes [with] reentrant). */
    private class Held(val ids: Set<Long>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private val entries = HashMap<Long, Entry>()

    /** How many attempt locks are held or awaited right now (a hook for the tests). */
    fun inUse(): Int = synchronized(entries) { entries.size }

    /** Runs [block] holding the lock of [attemptId]; runs it at once when this coroutine holds the lock already. */
    suspend fun <T> with(attemptId: Long, block: suspend () -> T): T {
        val held = coroutineContext[Held]?.ids ?: emptySet()

        if (attemptId in held) return block()

        val entry = synchronized(entries) { entries.getOrPut(attemptId) { Entry() }.also { it.users++ } }

        try {
            return entry.mutex.withLock { withContext(Held(held + attemptId)) { block() } }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(attemptId) }
        }
    }
}
