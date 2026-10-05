package com.panomc.plugins.market.job

import com.panomc.plugins.market.service.WebhookService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The tick of the outbound webhook queue (08 section 15.5, 17): claim up to 20 due rows, send at most
 * [concurrency] (5) at a time, the rows of one endpoint one after the other in id order, store every outcome. Safe to run
 * twice and to be killed mid-row: the claim expires after 60 s and the row is retried (receivers de-duplicate on
 * `X-Pano-Event-Id`).
 *
 * One tick at a time: a call that arrives while another is running returns 0 at once. The body never throws (a job must
 * not stop the scheduler); `CancellationException` is rethrown.
 *
 * Registration in `MarketScheduler` is the job of MK-078: it calls [tick] on every scheduler tick.
 */
class WebhookJob(
    private val service: WebhookService,
    private val concurrency: Int = 5,
    private val batch: Int = WebhookService.CLAIM_BATCH
) {
    private val running = AtomicBoolean(false)

    init {
        require(concurrency >= 1) { "concurrency must be at least 1" }
    }

    /** Rows claimed and handled by this call (0 when nothing was due or a tick was already running). */
    suspend fun tick(): Int {
        if (!running.compareAndSet(false, true)) return 0

        try {
            val claimed = service.claimDue(batch)
            if (claimed.isEmpty()) return 0

            val semaphore = Semaphore(concurrency)
            // Rows of one endpoint form one sequential chain; rows without an endpoint (product actions) are independent.
            val chains = claimed.groupBy { if (it.endpointId != 0L) "e${it.endpointId}" else "r${it.id}" }.values

            coroutineScope {
                chains.map { chain ->
                    async(Dispatchers.Default) {
                        semaphore.withPermit {
                            for (row in chain.sortedBy { it.id }) {
                                try {
                                    service.process(row)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    // The row stays SENDING; its claim expires and the next tick retries it.
                                    logger.error("Webhook delivery {} could not be processed: {}", row.id, t.toString())
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            return claimed.size
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("Webhook job tick failed: {}", t.toString())
            return 0
        } finally {
            running.set(false)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(WebhookJob::class.java)
    }
}
