package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.routes.api.payment.InboundDispatcher
import com.panomc.plugins.market.routes.api.payment.InboundEventStore
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The retry of inbound payment traffic (02 section 7.3 step 7, 11 section 4.3 IN-4, 17 I18): every 60 s it runs steps 3 to 7 again on the **stored
 * raw request** of the rows that are `FAILED` and due (`nextAttemptAt`), or `RECEIVED` for more than 60 s (a crashed run), at most 10 runs per
 * row, backoff 1 min doubling up to 6 h. A row that used up its runs while still `RECEIVED` becomes `FAILED` without a schedule: only a human
 * replays it. `DEFERRED` rows (the provider was not there) are left to the panel's replay.
 *
 * Safe to run twice at the same time and to be killed mid-row: a run is claimed with one conditional update (`attempts + 1` and a lease of 60 s
 * in `nextAttemptAt`), so each row is handled by one runner, and applying events is idempotent anyway. The body never throws (a job must not stop
 * the scheduler: `LinkageError` included); `CancellationException` is rethrown.
 *
 * Registration in `MarketScheduler` is the job of MK-078: it calls [runOnce] on every scheduler tick (a tick that finds nothing is one query).
 */
class InboundEventRetryJob(
    private val dispatcher: InboundDispatcher,
    private val store: InboundEventStore,
    private val clock: Clock,
    private val batch: Int = BATCH
) {
    /** Rows run again by this call. */
    suspend fun runOnce(): Int {
        try {
            val now = clock.now()
            val staleBefore = now - InboundDispatcher.STALE_RECEIVED_MS

            store.exhaust(now, staleBefore, InboundDispatcher.MAX_ATTEMPTS)

            var handled = 0

            for (row in store.due(now, staleBefore, InboundDispatcher.MAX_ATTEMPTS, batch)) {
                try {
                    if (dispatcher.retry(row)) handled++
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // the claim keeps the row from being retried in a hot loop; its lease ends and the next tick takes it again
                    logger.error("Inbound event {} could not be run again: {}", row.id, t.toString())
                }
            }

            return handled
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("Inbound event retry tick failed: {}", t.toString())

            return 0
        }
    }

    companion object {
        const val BATCH = 25
        private val logger = LoggerFactory.getLogger(InboundEventRetryJob::class.java)
    }
}
