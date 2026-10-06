package com.panomc.plugins.market.notification

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.model.ThrottleScope
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.service.DisputeAlerts
import com.panomc.plugins.market.service.PanelAlerts
import com.panomc.plugins.market.service.RefundAlerts
import com.panomc.plugins.market.service.ThrottleService
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Who gets an alert: the holders of these nodes, the umbrella node and the administrators (11 section 14, 08 section 8.5). */
enum class AlertAudience(val nodes: Set<MarketNode>) {
    /** Money questions: reviews, refunds, disputes. */
    PAYMENTS(setOf(MarketNode.PAYMENTS)),

    /** Delivery trouble and the Minecraft component: fulfilment (`OM`) and settings (`SET`) holders. */
    FULFILMENT(setOf(MarketNode.ORDERS_MANAGE, MarketNode.SETTINGS))
}

/** Alert codes raised by market itself (the refund and dispute ones are the services' own). */
object AlertCodes {
    const val BANK_TRANSFER_NOTIFIED = "BANK_TRANSFER_NOTIFIED"
}

/** The names of the notification types (the host derives them from the class names, `MarketNotificationsTest` pins that). */
object AlertTypes {
    const val ORDER_REVIEW = "MARKET_ORDER_REVIEW"
    const val ORDER_ALERT = "MARKET_ORDER_ALERT"
    const val DELIVERY_WAITING = "MARKET_DELIVERY_WAITING"
}

/** One alert in host-free form: the notification [type], its [audience] and the fields of the notification class. */
class AlertMessage(val type: String, val audience: AlertAudience, val fields: Map<String, Any?>)

/**
 * Where an alert goes. The production sink ([HostPanelNotificationSink]) touches host classes; it is only created when the host has `NotificationTypeRegistry`,
 * every other code path of market sees this interface only.
 */
fun interface PanelNotificationSink {
    suspend fun send(message: AlertMessage)
}

/**
 * The panel alerts of market (MK-172, 15 section 2.6 / 8.1, 08 section 8.5): the one implementation behind the three alert ports of the services
 * ([PanelAlerts] review openings, [RefundAlerts], [DisputeAlerts]) and the delivery alerts of [DeliveryAlertSweep].
 *
 * - **Only with the registry**: [enabled] is `MarketRuntime.capabilities.notifications`; without it nothing is sent and no throttle row is written (the alert
 *   stays a log line and the banners of the panel pages).
 * - **At most once**: every alert has a subject; a subject is sent once per its window, remembered in memory and durably in `market_throttle` scope `DELIVERY_ALERT`
 *   (a lock row, so a restart does not repeat it and the housekeeping purge keeps it). Per server 24 h ([SERVER_WINDOW_MINUTES]); per order / row [ROW_WINDOW_MINUTES] (30 days).
 *   A send that fails gives the subject back, so the next occurrence tries again.
 * - **Never throws**: an alert is raised after the transaction that found it committed; a failing notification must not undo or fail that.
 */
class MarketAlerts(
    private val clock: Clock,
    private val enabled: () -> Boolean,
    private val sink: () -> PanelNotificationSink?,
    private val throttle: ThrottleService?
) : PanelAlerts, RefundAlerts, DisputeAlerts {
    private val memory = ConcurrentHashMap<String, Long>()

    // ---------------------------------------------------------------------------------------------------------- the ports of the services

    /** O3 / O9: an order waits for a human. Once per (order, reason). */
    override suspend fun reviewOpened(orderId: Long, reason: String?) {
        logger.warn("order {} waits for review ({})", orderId, reason)

        raise(
            "review:$orderId:${reason.orEmpty()}", ROW_WINDOW_MINUTES,
            AlertMessage(AlertTypes.ORDER_REVIEW, AlertAudience.PAYMENTS, mapOf("orderId" to orderId, "reason" to reason, "href" to orderHref(orderId)))
        )
    }

    /** A refund or dispute alert code on an order. Once per (order, code). */
    override suspend fun alert(orderId: Long, code: String, data: JsonObject) {
        logger.warn("order alert {} on order {}: {}", code, orderId, data.encode())

        raise(
            "alert:$orderId:$code", ROW_WINDOW_MINUTES,
            AlertMessage(AlertTypes.ORDER_ALERT, AlertAudience.PAYMENTS, mapOf("orderId" to orderId, "code" to code, "href" to orderHref(orderId)))
        )
    }

    // ---------------------------------------------------------------------------------------------------------- delivery alerts

    /** Deliveries wait for [serverName] (08 section 8.5): at most one per server per 24 hours. Returns whether a notification was sent. */
    suspend fun deliveryWaiting(serverId: Long, serverName: String, count: Long): Boolean = raise(
        "server:$serverId", SERVER_WINDOW_MINUTES,
        AlertMessage(
            AlertTypes.DELIVERY_WAITING, AlertAudience.FULFILMENT,
            mapOf("serverId" to serverId, "serverName" to serverName, "count" to count, "urgent" to false, "failed" to false, "kind" to "WAITING", "href" to DELIVERIES_HREF)
        )
    )

    /** A `REVOKE` / `EXPIRE` row that failed or waits too long: immediately, once per row, no per-server throttle. Returns whether a notification was sent. */
    suspend fun deliveryUrgent(orderId: Long, deliveryId: Long, serverId: Long, serverName: String?, failed: Boolean): Boolean = raise(
        "row:$deliveryId", ROW_WINDOW_MINUTES,
        AlertMessage(
            AlertTypes.DELIVERY_WAITING, AlertAudience.FULFILMENT,
            mapOf(
                "serverId" to serverId, "serverName" to serverName, "orderId" to orderId, "deliveryId" to deliveryId, "urgent" to true, "failed" to failed,
                "kind" to if (failed) "UNDO_FAILED" else "UNDO_WAITING",
                "href" to orderHref(orderId)
            )
        )
    )

    // ---------------------------------------------------------------------------------------------------------- core

    private suspend fun raise(subject: String, windowMinutes: Int, message: AlertMessage): Boolean {
        try {
            if (!enabled()) return false

            val target = sink() ?: return false
            val now = clock.now()
            val until = now + windowMinutes * 60_000L

            if ((memory[subject] ?: 0L) > now) return false

            if (throttle?.isLocked(ThrottleScope.DELIVERY_ALERT, subject) != null) {
                memory[subject] = until

                return false
            }

            // claim the subject before sending: a second raise (another job run, a replayed event) finds the lock and stays quiet
            memory[subject] = until
            throttle?.fail(ThrottleScope.DELIVERY_ALERT, subject, 1, windowMinutes, windowMinutes)

            try {
                target.send(message)
            } catch (e: CancellationException) {
                giveBack(subject)

                throw e
            } catch (t: Throwable) {
                giveBack(subject)

                throw t
            }

            return true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // LinkageError included: a host without the notification classes must not break the flow that raised the alert
            logger.error("panel alert {} ({}) could not be sent: {}", message.type, subject, t.toString())

            return false
        }
    }

    private suspend fun giveBack(subject: String) {
        memory.remove(subject)

        try {
            throttle?.reset(ThrottleScope.DELIVERY_ALERT, subject)
        } catch (t: Throwable) {
            logger.warn("alert subject {} could not be released: {}", subject, t.toString())
        }
    }

    companion object {
        const val SERVER_WINDOW_MINUTES = 24 * 60
        const val ROW_WINDOW_MINUTES = 30 * 24 * 60
        const val DELIVERIES_HREF = "/market/deliveries?status=WAITING_SERVER"

        fun orderHref(orderId: Long) = "/market/orders/detail/$orderId"

        private val logger = LoggerFactory.getLogger(MarketAlerts::class.java)
    }
}
