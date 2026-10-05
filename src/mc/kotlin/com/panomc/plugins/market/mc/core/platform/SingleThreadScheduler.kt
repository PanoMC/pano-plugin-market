package com.panomc.plugins.market.mc.core.platform

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The production [McScheduler]: one daemon thread. A task that throws is logged and does not stop the thread; a task
 * submitted after [shutdown] is dropped (the component is stopping).
 */
class SingleThreadScheduler(threadName: String, private val log: McLog) : McScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, threadName).also { it.isDaemon = true }
    }

    init {
        executor.setRemoveOnCancelPolicy(true)
        executor.executeExistingDelayedTasksAfterShutdownPolicy = false
        executor.continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    override fun execute(task: () -> Unit) {
        try {
            executor.execute(guarded(task))
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): McTimer {
        return try {
            val future: ScheduledFuture<*> = executor.schedule(guarded(task), delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            object : McTimer {
                override fun cancel() {
                    future.cancel(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            object : McTimer {
                override fun cancel() {}
            }
        }
    }

    /** Stops accepting tasks, lets the running and already queued immediate tasks finish, waits at most [awaitMs]. */
    fun shutdown(awaitMs: Long): Boolean {
        executor.shutdown()
        return try {
            executor.awaitTermination(awaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun guarded(task: () -> Unit) = Runnable {
        try {
            task()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            log.error("A Market engine task failed: ${t.message}", t)
        }
    }
}
