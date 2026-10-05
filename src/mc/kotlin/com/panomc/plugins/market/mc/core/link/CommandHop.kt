package com.panomc.plugins.market.mc.core.link

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** The hop did not give an answer in time. [started] tells whether the body had begun (then it may still complete). */
class CommandHopTimeout(message: String, val started: Boolean) : RuntimeException(message)

/**
 * Runs a body on a platform thread (the server main thread, the Folia global region, a proxy scheduler) from the
 * engine thread and returns its result (19 section 6.3: dispatch on the platform's command thread, return once it ran).
 *
 * The one guarantee that matters for a delivery: when the wait times out BEFORE the body started, the body is
 * cancelled and will never run, so a failure reported to Pano for that command really means "not executed". When it
 * already started, the failure message says that it may still complete.
 */
object CommandHop {
    private const val PENDING = 0
    private const val RUNNING = 1
    private const val CANCELLED = 2

    /** [submit] hands the runnable to the platform scheduler; it may throw when the scheduler refuses (plugin disabled). */
    fun <T> call(timeoutMs: Long, submit: (Runnable) -> Unit, body: () -> T): T {
        val state = AtomicInteger(PENDING)
        val future = CompletableFuture<T>()
        val task = Runnable {
            if (!state.compareAndSet(PENDING, RUNNING)) return@Runnable
            try {
                future.complete(body())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        submit(task)
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            if (state.compareAndSet(PENDING, CANCELLED)) {
                throw CommandHopTimeout("the server thread did not pick the command up within ${timeoutMs / 1000} s; it was not executed", false)
            }
            // Running: give a slow command one more window before giving up.
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                throw CommandHopTimeout("the command started but did not return within ${2 * timeoutMs / 1000} s; it may still complete", true)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (state.compareAndSet(PENDING, CANCELLED)) {
                throw CommandHopTimeout("interrupted before the command started; it was not executed", false)
            }
            throw CommandHopTimeout("interrupted while the command ran; it may still complete", true)
        }
    }
}
