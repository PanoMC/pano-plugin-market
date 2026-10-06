package com.panomc.plugins.market.notification

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.service.ServerReadiness
import com.panomc.plugins.market.service.ServerView
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The delivery alerts of 08 section 8.5, one scheduler job every [EVERY_MS] (5 minutes):
 *
 * 1. **Waiting for a server**: every server that is not `READY` and holds deliveries in `PENDING`, `WAITING_SERVER` or `SENT` whose oldest row is older than
 *    [WAIT_MS] (10 minutes) raises `MARKET_DELIVERY_WAITING` with the count; [MarketAlerts] keeps it to one per server per 24 hours.
 * 2. **A waiting or failed undo is urgent**: a `REVOKE` / `EXPIRE` row that is `FAILED`, or that has waited longer than [WAIT_MS], raises the same type at once with
 *    the order id, once per row (the buyer may hold both the money and the rank).
 *
 * Nothing is read when [MarketAlerts] is disabled (no `NotificationTypeRegistry` in the host), so a host without X-5 pays nothing.
 */
class DeliveryAlertSweep(
    private val clock: Clock,
    private val alerts: MarketAlerts,
    private val enabled: () -> Boolean,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient,
    private val servers: suspend () -> List<ServerView>
) {
    private fun table(name: String) = "`${prefix()}$name`"

    /** The number of notifications sent by this run. */
    suspend fun runOnce(): Int {
        if (!enabled()) return 0

        var sent = 0

        sent += step("waiting") { waiting() }
        sent += step("urgent") { urgent() }

        return sent
    }

    private suspend fun step(name: String, body: suspend () -> Int): Int = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.error("delivery alert step {} failed: {}", name, t.toString())

        0
    }

    private suspend fun waiting(): Int {
        val sql = client()
        val cutoff = clock.now() - WAIT_MS
        var sent = 0

        for (server in servers()) {
            if (server.marketState == ServerReadiness.READY || server.waitingDeliveries <= 0) continue

            val oldest = sql.preparedQuery(
                "SELECT MIN(`createdAt`) AS oldest FROM ${table("market_delivery")} WHERE `serverId` = ? AND `status` IN ('PENDING', 'WAITING_SERVER', 'SENT')"
            ).execute(Tuple.of(server.id)).coAwait().first().getLong("oldest") ?: continue

            if (oldest > cutoff) continue

            if (alerts.deliveryWaiting(server.id, server.name, server.waitingDeliveries)) sent++
        }

        return sent
    }

    private suspend fun urgent(): Int {
        val sql = client()
        val cutoff = clock.now() - WAIT_MS
        val names = servers().associate { it.id to it.name }
        val rows = sql.preparedQuery(
            "SELECT `id`, `orderId`, `serverId`, `status` FROM ${table("market_delivery")} WHERE `phase` IN ('REVOKE', 'EXPIRE') AND `orderId` IS NOT NULL AND " +
                "(`status` = 'FAILED' OR (`status` IN ('PENDING', 'WAITING_SERVER', 'SENT') AND `createdAt` <= ?)) ORDER BY `id` DESC LIMIT $URGENT_BATCH"
        ).execute(Tuple.of(cutoff)).coAwait()
        var sent = 0

        for (row in rows) {
            val serverId = row.getLong("serverId") ?: 0L

            if (alerts.deliveryUrgent(row.getLong("orderId"), row.getLong("id"), serverId, names[serverId], row.getString("status") == "FAILED")) sent++
        }

        return sent
    }

    companion object {
        const val EVERY_MS = 5 * 60_000L
        const val WAIT_MS = 10 * 60_000L
        const val URGENT_BATCH = 100

        private val logger = LoggerFactory.getLogger(DeliveryAlertSweep::class.java)
    }
}
