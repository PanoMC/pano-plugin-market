package com.panomc.plugins.market.db.tx

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Work that may only happen once the data it announces is visible to others: a push to a game server ("there is something new for
 * you") sent before COMMIT would make the server ask too early and see nothing. [MarketDb.tx] puts one of these into the coroutine
 * context of its block and runs what was collected right after a successful COMMIT; an attempt that rolls back (or is re-run after a
 * deadlock) drops it. Outside a [MarketDb.tx] [run] does the work at once.
 */
class AfterCommit : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AfterCommit> {
        /** [action] after the COMMIT of the surrounding [MarketDb.tx], or now when there is none. It must be quick and must not throw. */
        suspend fun run(action: () -> Unit) {
            val pending = coroutineContext[Key]

            if (pending == null) safely(action) else pending.actions += action
        }

        internal fun safely(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                org.slf4j.LoggerFactory.getLogger(AfterCommit::class.java).warn("An after-commit action failed: ${e.message}")
            }
        }
    }

    internal val actions = CopyOnWriteArrayList<() -> Unit>()

    internal fun runAll() {
        actions.forEach { safely(it) }
        actions.clear()
    }
}
