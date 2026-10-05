package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.service.DeliveryService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The delivery worker (08 section 17): on every scheduler tick it promotes `SCHEDULED` rows (D1), claims and executes the due inline rows (D2, D3 - D5),
 * returns stale claims to `PENDING` (D6), re-asserts permission grants, and, every [CLASSIFY_EVERY_MS] (first run [CLASSIFY_FIRST_MS] after the job
 * was created), applies D22 to undo rows whose predecessors never took effect.
 *
 * Restart-safe: all state is in the rows, so a lost tick changes only latency. Safe to run twice at once: the claim is a conditional update, so two
 * workers that select the same row execute it once (the one that loses sees `null` from [DeliveryService.execute] and moves on). Not here:
 * the server rows (`MARKET_SYNC`, D7 - D11, D14, D20; MK-103) and the `WEBHOOK` executor (MK-106 adds its type to [DeliveryService.INLINE_TYPES]).
 *
 * One step failing never hides the others: every step runs, the first failure is rethrown at the end for the scheduler to log and count.
 */
class DeliveryJob(
    private val service: DeliveryService,
    private val clock: Clock,
    private val batch: Int = DeliveryService.INLINE_BATCH
) {
    private val createdAt = clock.now()

    @Volatile
    private var lastClassifyAt: Long? = null

    /** Rows handled by this call (promoted, executed, recovered, re-asserted, classified). */
    suspend fun runOnce(): Int {
        var handled = 0
        var failure: Throwable? = null

        suspend fun step(name: String, body: suspend () -> Int) {
            try {
                handled += body()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.error("delivery step {} failed: {}", name, t.toString())

                if (failure == null) failure = t
            }
        }

        step("promote") { service.promote() }
        step("inline") { inline() }
        step("recover") { service.recoverStaleClaims(batch) }
        step("reassert") { service.reassertDue(batch) }

        if (classifyDue()) step("classify") { service.classify() }

        failure?.let { throw it }

        return handled
    }

    /** D2 + executor for every claimed row, one after the other (08 section 7). */
    private suspend fun inline(): Int {
        var executed = 0

        for (row in service.claimDue(batch)) {
            try {
                if (service.execute(row) != null) executed++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // the row stays SENDING; its claim runs out and D6 returns it to PENDING
                logger.error("inline delivery {} could not be executed: {}", row.id, t.toString())
            }
        }

        return executed
    }

    private fun classifyDue(): Boolean {
        val now = clock.now()
        val last = lastClassifyAt

        if (last == null && now - createdAt < CLASSIFY_FIRST_MS) return false

        if (last != null && now - last < CLASSIFY_EVERY_MS) return false

        lastClassifyAt = now

        return true
    }

    companion object {
        const val CLASSIFY_EVERY_MS = 30_000L
        const val CLASSIFY_FIRST_MS = 60_000L

        private val logger = LoggerFactory.getLogger(DeliveryJob::class.java)
    }
}
