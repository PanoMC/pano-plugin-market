package com.panomc.plugins.market.routes.api.payment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [AttemptLocks.withOrNull]: a bounded wait that leaves nothing behind when it gives up (WIRE-1 review fix). */
class AttemptLocksTest {
    @Test
    fun `withOrNull gives up after the wait, never runs the block and leaves only the holder in the registry`(): Unit = runBlocking {
        val locks = AttemptLocks()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { locks.with(7) { held.complete(Unit); release.await() } }

        held.await()

        var ran = false

        assertNull(locks.withOrNull(7, 100) { ran = true; "ran" })
        assertEquals(false, ran)
        assertEquals(1, locks.inUse())

        release.complete(Unit)
        holder.await()

        assertEquals(0, locks.inUse())
        assertEquals("ran", locks.withOrNull(7, 100) { "ran" })
        assertEquals(0, locks.inUse())
    }

    @Test
    fun `withOrNull acquires when the lock is released inside the wait and is reentrant`(): Unit = runBlocking {
        val locks = AttemptLocks()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { locks.with(3) { held.complete(Unit); release.await() } }

        held.await()

        val waiter = async { locks.withOrNull(3, 5_000) { locks.withOrNull(3, 1) { locks.with(3) { "inner" } } } }

        kotlinx.coroutines.delay(100)
        release.complete(Unit)
        holder.await()

        assertEquals("inner", waiter.await())
        assertEquals(0, locks.inUse())
    }
}
