package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.shipping.ShipmentStateMachine
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.service.ShippingService
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * The pull half of tracking (10 section 10.2, 03 section 4): every [MarketScheduler.SHIPMENT_TRACKING_MS] it asks the carriers about the shipments that
 * are due. Polling is mandatory for most carriers (DHL Express has no push, FedEx push is paid, most Turkish carriers have none).
 *
 * ```
 * select: status NOT IN (DELIVERED, RETURNED, CANCELLED, LOST) AND nextPollAt <= now  ORDER BY nextPollAt LIMIT 200
 * group by providerId; skip the groups whose provider is not registered / not enabled / has no trackingPull (nextPollAt untouched)
 * per group: chunks of capabilities.trackBatchSize, at most 50 shipments per provider per run
 *   claim each: UPDATE ... SET nextPollAt = now + 10 min WHERE id = ? AND nextPollAt = :seen   (0 rows: somebody else has it)
 *   provider.track(chunk), 30 s
 *   success: applyUpdate(..., POLL) per update that names a shipment of the chunk
 *   every shipment of the chunk: pollCount + 1, lastPolledAt = now, nextPollAt = TrackingSchedule.next(...) (none once terminal / stale)
 *   failure: the same bookkeeping, and the carrier row keeps the error
 * ```
 *
 * The 10 minute claim is also the retry of a run that died between the claim and the bookkeeping: the row is due again after it. Two runs at the same
 * time (two instances, a tick that overlaps) poll a shipment once. The body never throws: a job must not stop the scheduler; `CancellationException` is rethrown.
 */
class ShipmentTrackingJob(
    private val clock: Clock,
    private val shipments: MarketShipmentDao,
    private val service: ShippingService,
    private val client: suspend () -> SqlClient,
    private val trackTimeoutMs: Long = TRACK_TIMEOUT_MS,
    private val dueLimit: Int = DUE_LIMIT,
    private val perProvider: Int = PER_PROVIDER
) {
    /** The shipments this call polled (claimed and bookkept). */
    suspend fun runOnce(): Int {
        try {
            val sql = client()
            val now = clock.now()
            val due = shipments.getDueForPoll(POLLED_STATUSES, now, dueLimit, sql)

            if (due.isEmpty()) return 0

            var handled = 0

            for ((providerId, group) in due.groupBy { it.providerId }) {
                try {
                    handled += pollProvider(providerId, group.take(perProvider), sql)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // one provider (a broken plugin, LinkageError) never stops the others
                    logger.error("Tracking of provider {} failed: {}", providerId, t.toString())
                }
            }

            return handled
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("Shipment tracking tick failed: {}", t.toString())

            return 0
        }
    }

    private suspend fun pollProvider(providerId: String, group: List<MarketShipment>, sql: SqlClient): Int {
        val access = service.pollAccess(providerId, sql) ?: return 0
        var handled = 0

        for (chunk in group.chunked(access.trackBatchSize)) {
            val claimed = ArrayList<MarketShipment>(chunk.size)

            for (row in chunk) {
                val seen = row.nextPollAt ?: continue

                if (shipments.claimPoll(row.id, seen, clock.now() + CLAIM_MS, clock.now(), sql)) claimed += row
            }

            if (claimed.isEmpty()) continue

            // a shipment the carrier cannot be asked about is only re-planned
            val askable = claimed.filter { service.pollable(it, access) }
            var updates: List<TrackingUpdate> = emptyList()
            var failure: String? = null

            if (askable.isNotEmpty()) {
                try {
                    updates = withTimeout(trackTimeoutMs) { access.track(askable) }
                } catch (e: TimeoutCancellationException) {
                    failure = "TIMEOUT"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderException) {
                    failure = e.code.name
                } catch (e: Exception) {
                    failure = "INTERNAL"

                    logger.warn("Provider {} failed while tracking: {}", providerId, e.toString())
                }
            }

            for (row in askable) {
                if (failure != null) break

                try {
                    service.applyPolled(row, updates)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the number of the update stays stored in the events that did apply; the shipment is polled again on schedule
                    failure = "INTERNAL"

                    logger.warn("An update of shipment {} could not be applied: {}", row.id, e.toString())
                }
            }

            if (failure != null) service.recordCarrierError(providerId, failure, sql)

            for (row in claimed) {
                try {
                    service.finishPoll(row.id)

                    handled++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the claim lease (10 minutes) brings the row back
                    logger.error("The poll of shipment {} could not be recorded: {}", row.id, e.toString())
                }
            }
        }

        return handled
    }

    companion object {
        /** 10 section 10.2: `provider.track` deadline. */
        const val TRACK_TIMEOUT_MS = 30_000L

        const val DUE_LIMIT = 200
        const val PER_PROVIDER = 50

        /** The claim: a due shipment is not due again for 10 minutes. */
        const val CLAIM_MS = 10 * 60_000L

        private val TERMINAL_NAMES: Set<String> = ShipmentStateMachine.TERMINAL.map { it.name }.toSet()

        /** Everything that is not `TERMINAL` (`DELIVERED`, `RETURNED`, `CANCELLED`, `LOST`). */
        val POLLED_STATUSES: List<ShipmentStatus> = ShipmentStatus.values().filter { it.name !in TERMINAL_NAMES }

        private val logger = LoggerFactory.getLogger(ShipmentTrackingJob::class.java)
    }
}
