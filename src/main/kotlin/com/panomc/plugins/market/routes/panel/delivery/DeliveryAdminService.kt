package com.panomc.plugins.market.routes.panel.delivery

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.DeliveryEvent
import com.panomc.plugins.market.core.delivery.DeliveryPlanner
import com.panomc.plugins.market.core.delivery.DeliveryTransition
import com.panomc.plugins.market.core.delivery.FulfillmentCalculator
import com.panomc.plugins.market.core.delivery.PlannedDelivery
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryTransport
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.DeliveryNotCancellable
import com.panomc.plugins.market.error.DeliveryNotRetryable
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.service.DeliveryService
import com.panomc.plugins.market.service.platform.ServerRoster
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

/**
 * The panel's delivery operations (08 section 14, 04 section 7; node `OM`): re-run, retry, cancel, revoke and the `GET /deliveries` list.
 * Every state change goes through [DeliveryService.apply] (the state machine decides, the conditional update writes) or
 * [DeliveryService.insertPlanned] (`INSERT IGNORE` on the idempotency key), inside one transaction that holds the order lock (00 section 8.3).
 *
 * Re-run is the only operation that can grant twice, so the caller states whether the actor may ([mayRepeatTakenEffect], `PAY` in addition to `OM`);
 * the whole request is refused with 403 before anything is written when a selected logical delivery already took effect and the actor may not.
 */
class DeliveryAdminService(
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val deliveryService: DeliveryService,
    private val orders: MarketOrderDao,
    private val orderEvents: MarketOrderEventDao,
    private val deliveries: MarketDeliveryDao,
    private val entitlements: MarketEntitlementDao,
    private val roster: ServerRoster
) {
    private fun table(name: String) = "`${deliveries.prefix()}$name`"

    /** What a re-run selects: exactly one of the three (08 section 14.1). */
    sealed class Selector {
        class Deliveries(val ids: List<Long>) : Selector()

        class Items(val ids: List<Long>) : Selector()

        data object All : Selector()
    }

    /** [creditAmount] is in credits x100; [duplicateGrant] is `true` when a row that already took effect was run again. */
    class RerunResult(val created: Int, val skipped: Int, val duplicateGrant: Boolean, val creditAmount: Long)

    // ===== re-run (08 section 14.1) ======================================================================================

    suspend fun rerun(orderId: Long, selector: Selector, phase: DeliveryPhase, mayRepeatTakenEffect: Boolean, actorUserId: Long): RerunResult =
        db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, orderId, OrderLockScope.COMMIT) { locked ->
                val order = locked.order
                val all = deliveries.getByOrderId(orderId, conn)
                val now = clock.now()
                val effective = effectiveRows(all)

                // what to run again: copies of chosen rows, or what the planner makes from the snapshots
                val candidates: List<PlannedDelivery>
                val grantItems = LinkedHashSet<Long>()

                when (selector) {
                    is Selector.Deliveries -> {
                        val byId = all.associateBy { it.id }
                        val chosen = selector.ids.distinct().map { id -> byId[id] ?: throw NotFound() }

                        if (chosen.any { it.phase.isGrantLike }) requireRerunnableOrder(order.status)

                        candidates = chosen.map { copyOf(it, nextGroup(all, it), now) }
                        chosen.filter { it.phase == DeliveryPhase.GRANT }.mapNotNullTo(grantItems) { it.orderItemId }
                    }

                    else -> {
                        val items = when (selector) {
                            is Selector.Items -> selector.ids.distinct().map { id -> locked.items.firstOrNull { it.id == id } ?: throw NotFound() }
                            else -> locked.items
                        }

                        if (phase.isGrantLike) requireRerunnableOrder(order.status)

                        // a bundle child needs its line to be planned; the planner reads the lines it is given
                        val wanted = items.map { it.id }.toSet()
                        val parents = items.mapNotNull { it.parentItemId }.toSet()
                        val lines = locked.items.filter { it.id in wanted || it.id in parents }
                        val group = (all.filter { it.orderItemId in wanted }.maxOfOrNull { it.attemptGroup } ?: -1) + 1

                        candidates = deliveryService.planAgain(conn, order, lines, phase, group).filter { it.orderItemId in wanted }

                        if (phase == DeliveryPhase.GRANT) grantItems += wanted
                    }
                }

                // a logical delivery whose effective row is still open is skipped; one that took effect needs PAY
                var skipped = 0
                var repeats = false
                var credits = 0L
                val runnable = ArrayList<PlannedDelivery>()

                for (c in candidates) {
                    val current = effective[logicalKey(c.sourceType.name, c.orderItemId, c.sourceId, c.actionId, c.serverId, c.unitIndex, c.phase)]

                    when {
                        current != null && FulfillmentCalculator.isOpen(current.status) -> skipped++

                        else -> {
                            if (current != null && tookEffect(current)) {
                                repeats = true

                                if (c.actionType == DeliveryActionType.CREDIT) credits += creditsOf(c.payload)
                            }

                            runnable += c
                        }
                    }
                }

                if (repeats && !mayRepeatTakenEffect) throw NoPermission()

                // an item whose entitlement was revoked comes back for a GRANT re-run, unless it ran out meanwhile (then the item is skipped)
                val revived = HashSet<Long>()
                val dropped = HashSet<Long>()

                for (itemId in grantItems) {
                    val ents = entitlements.getByOrderItemId(itemId, conn).filter { it.status == EntitlementStatus.REVOKED }

                    for (e in ents) {
                        if (e.expiresAt == null || e.expiresAt > now) revived += e.id else dropped += itemId
                    }
                }

                val toInsert = runnable.filter { c -> !(c.phase == DeliveryPhase.GRANT && c.orderItemId in dropped) }

                skipped += runnable.size - toInsert.size

                for (id in revived) reactivate(conn, id, now)

                val inserted = deliveryService.insertPlanned(conn, toInsert).size

                skipped += toInsert.size - inserted

                if (revived.isNotEmpty()) deliveryService.refreshFulfillment(conn, orderId)

                orderEvents.add(
                    MarketOrderEvent(
                        orderId = orderId, type = OrderEventType.DELIVERY_RERUN, actorType = OrderActorType.ADMIN, actorUserId = actorUserId,
                        data = JsonObject().put("created", inserted).put("skipped", skipped).put("duplicateGrant", repeats).put("phase", phase.name)
                            .put("credits", credits).encode(),
                        createdAt = now, updatedAt = now
                    ),
                    conn
                )

                RerunResult(inserted, skipped, repeats, credits)
            }
        }

    private fun requireRerunnableOrder(status: OrderStatus) {
        if (status != OrderStatus.COMPLETED && status != OrderStatus.PARTIALLY_REFUNDED) throw InvalidOrderTransition(reason = "ORDER_STATUS")
    }

    private val DeliveryPhase.isGrantLike: Boolean get() = this == DeliveryPhase.GRANT || this == DeliveryPhase.RENEW

    /** A new row for the same logical delivery as [row]: every planning column and the stored payload, `attemptGroup` one higher, fresh counters. */
    private fun copyOf(row: MarketDelivery, group: Int, now: Long): PlannedDelivery {
        val source = DeliveryPlanner.sourceKey(row.sourceType, row.orderItemId, row.sourceId)

        return PlannedDelivery(
            sourceType = row.sourceType, orderId = row.orderId, orderItemId = row.orderItemId, sourceId = row.sourceId, entitlementId = row.entitlementId,
            subscriptionId = row.subscriptionId, phase = row.phase, actionId = row.actionId, actionType = row.actionType, unitIndex = row.unitIndex,
            attemptGroup = group, serverId = row.serverId, idempotencyKey = DeliveryPlanner.key(source, row.actionId, row.serverId, row.unitIndex, row.phase, group),
            status = DeliveryStatus.PENDING, requiresOnline = row.requiresOnline, playerUsername = row.playerUsername, playerUuid = row.playerUuid,
            payload = row.payload, transport = row.transport ?: if (row.serverId != 0L) DeliveryTransport.MARKET_MC else DeliveryTransport.INLINE,
            runAfter = now, nextAttemptAt = now, waitUntil = row.waitUntil?.let { if (row.requiresOnline) now + maxOf(0L, it - row.runAfter) else null },
            attempts = 0, guaranteed = row.guaranteed
        )
    }

    /** `max(attemptGroup)` of the rows of the same logical delivery as [row] (every phase-and-key prefix) plus one. */
    private fun nextGroup(all: List<MarketDelivery>, row: MarketDelivery): Int {
        val key = logicalKey(row.sourceType.name, row.orderItemId, row.sourceId, row.actionId, row.serverId, row.unitIndex, row.phase)

        return all.filter { logicalKey(it.sourceType.name, it.orderItemId, it.sourceId, it.actionId, it.serverId, it.unitIndex, it.phase) == key }.maxOf { it.attemptGroup } + 1
    }

    /** The effective row of every logical delivery: the one with the highest attempt group (then the highest id). */
    private fun effectiveRows(all: List<MarketDelivery>): Map<List<Any?>, MarketDelivery> {
        val best = HashMap<List<Any?>, MarketDelivery>()

        for (row in all) {
            val key = logicalKey(row.sourceType.name, row.orderItemId, row.sourceId, row.actionId, row.serverId, row.unitIndex, row.phase)
            val seen = best[key]

            if (seen == null || row.attemptGroup > seen.attemptGroup || (row.attemptGroup == seen.attemptGroup && row.id > seen.id)) best[key] = row
        }

        return best
    }

    private fun logicalKey(source: String, itemId: Long?, sourceId: Long?, actionId: String, serverId: Long, unit: Int, phase: DeliveryPhase): List<Any?> =
        listOf(source, itemId, sourceId, actionId, serverId, unit, phase)

    /** `CONFIRMED`, or `FAILED` with `UNKNOWN_OUTCOME` (it may have run): running it again grants again (08 section 14.1, privilege). */
    private fun tookEffect(row: MarketDelivery): Boolean =
        row.status == DeliveryStatus.CONFIRMED || (row.status == DeliveryStatus.FAILED && row.lastErrorCode == DeliveryError.UNKNOWN_OUTCOME)

    private fun creditsOf(payload: String): Long = runCatching { JsonObject(payload).getLong("credits") }.getOrNull() ?: 0L

    private suspend fun reactivate(conn: SqlConnection, entitlementId: Long, now: Long) {
        conn.preparedQuery("UPDATE ${table("market_entitlement")} SET `status` = 'ACTIVE', `endReason` = NULL, `endedAt` = NULL, `updatedAt` = ? WHERE `id` = ? AND `status` = 'REVOKED'")
            .execute(Tuple.of(now, entitlementId)).coAwait()
    }

    // ===== retry and cancel (08 sections 14.2, 14.3) =====================================================================

    /** D18 / D19 on the same row and key. 409 `DELIVERY_NOT_RETRYABLE` when the machine refuses; 404 for an unknown row. */
    suspend fun retry(deliveryId: Long): MarketDelivery = decide(deliveryId, DeliveryEvent.Retry, grantGuard = true) { DeliveryNotRetryable() }

    /** D16 / D17. 409 `DELIVERY_NOT_CANCELLABLE` from `SENDING` and from the terminal states; the row of D17 stays as it is until the server answers. */
    suspend fun cancel(deliveryId: Long): MarketDelivery = decide(deliveryId, DeliveryEvent.Cancel(DeliveryError.CANCELLED_BY_ADMIN), grantGuard = false) { DeliveryNotCancellable() }

    private suspend fun decide(deliveryId: Long, event: DeliveryEvent, grantGuard: Boolean, refusal: () -> Throwable): MarketDelivery =
        db.txRestartingOnOrderChange { conn ->
            // the unlocked read only names the order to lock; the decision is made on the row as it is under the lock
            val first = deliveries.getById(deliveryId, conn) ?: throw NotFound()

            suspend fun run(): MarketDelivery {
                val current = deliveries.getById(deliveryId, conn) ?: throw NotFound()

                // a grant that was never delivered must not run after the order stopped being a paid one (a refund planned the undo already)
                if (grantGuard && current.phase.isGrantLike && current.orderId != null) {
                    val status = orders.getById(current.orderId, conn)?.status

                    if (status != OrderStatus.COMPLETED && status != OrderStatus.PARTIALLY_REFUNDED) throw refusal()
                }

                val applied = deliveryService.apply(conn, deliveryId, event)

                return when {
                    applied.transition is DeliveryTransition.Rejected -> throw refusal()
                    applied.transition is DeliveryTransition.Move && !applied.moved -> throw refusal()
                    else -> applied.row ?: throw NotFound()
                }
            }

            if (first.orderId == null) run() else locks.forOrder(conn, first.orderId, OrderLockScope.PAYMENT) { run() }
        }

    // ===== revoke (08 section 14.4) ======================================================================================

    /**
     * `POST /orders/:id/revoke`: the end flow of 08 section 11.1 for [itemIds] (default every line) and every not-yet-revoked unit; no money moves.
     * Answers the number of `REVOKE` rows planned. An item whose entitlement is `UPGRADED`, or a timed item whose owner has another live entitlement
     * of the same product (the chain pull-forward and coverage rules, MK-107), is refused with 409 `INVALID_STATE` instead of being revoked halfway.
     */
    suspend fun revoke(orderId: Long, itemIds: List<Long>?, actorUserId: Long): Int =
        db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, orderId, OrderLockScope.COMMIT) { locked ->
                val selected = when (itemIds) {
                    null -> locked.items
                    else -> itemIds.distinct().map { id -> locked.items.firstOrNull { it.id == id } ?: throw NotFound() }
                }.filter { it.kind != OrderItemKind.CREDIT_TOPUP }
                val chosen = selected.map { it.id }.toMutableSet()

                // a bundle line takes its children with it
                locked.items.filter { it.parentItemId != null && it.parentItemId in chosen }.forEach { chosen += it.id }

                val parentIds = locked.items.filter { it.id in chosen }.mapNotNull { it.parentItemId }.toSet()
                val lines = locked.items.filter { it.id in chosen || it.id in parentIds }
                val rows = deliveries.getByOrderId(orderId, conn)
                val units = HashMap<Long, IntRange>()
                val before = HashMap<Long, Set<Int>>()
                var group = 0

                for (item in lines) {
                    units[item.id] = EMPTY

                    if (item.id !in chosen) continue

                    val gone = revokedUnits(item, rows.filter { it.orderItemId == item.id })
                    val left = (0 until item.quantity).filter { it !in gone }

                    before[item.id] = gone

                    if (left.isNotEmpty()) units[item.id] = left.first()..left.last()

                    rows.filter { it.orderItemId == item.id && it.phase == DeliveryPhase.REVOKE }.maxOfOrNull { it.attemptGroup }?.let { group = maxOf(group, it + 1) }
                }

                val targets = lines.filter { it.id in chosen && !units.getValue(it.id).isEmpty() }

                if (targets.isEmpty()) return@forOrder 0

                guardEntitlements(conn, targets)

                val now = clock.now()
                val plan = deliveryService.planRevoke(conn, locked.order, lines, units, before, DeliveryError.ORDER_REVOKED, emptyMap(), group)

                for (item in targets) {
                    for (e in entitlements.getByOrderItemId(item.id, conn)) {
                        if (e.status == EntitlementStatus.ACTIVE) entitlements.end(e.id, EntitlementStatus.REVOKED, "ADMIN", now, conn)
                    }
                }

                deliveryService.refreshFulfillment(conn, orderId)

                orderEvents.add(
                    MarketOrderEvent(
                        orderId = orderId, type = OrderEventType.DELIVERY_REVOKED, actorType = OrderActorType.ADMIN, actorUserId = actorUserId,
                        data = JsonObject().put("created", plan.inserted.size).put("cancelled", plan.cancelled).put("cancelRequested", plan.cancelRequested)
                            .put("orderItemIds", JsonArray(targets.map { it.id })).encode(),
                        createdAt = now, updatedAt = now
                    ),
                    conn
                )

                plan.inserted.size
            }
        }

    /** The units that non-cancelled `REVOKE` rows of the item already stand for: a per-unit row its own unit, any other row its unit up to the end of the line. */
    private fun revokedUnits(item: MarketOrderItem, rows: List<MarketDelivery>): Set<Int> {
        val actions = ActionParser.parseStored(runCatching { JsonObject(item.snapshot ?: "{}").getJsonArray("actions")?.encode() }.getOrNull()).actions.associateBy { it.id }
        val out = HashSet<Int>()

        for (row in rows) {
            if (row.phase != DeliveryPhase.REVOKE || row.status == DeliveryStatus.CANCELLED || row.unitIndex >= item.quantity) continue

            val perUnit = actions[row.actionId]?.perUnit == true && (row.actionType == DeliveryActionType.COMMAND || row.actionType == DeliveryActionType.WEBHOOK)

            if (perUnit) out += row.unitIndex else for (u in row.unitIndex until item.quantity) out += u
        }

        return out
    }

    private suspend fun guardEntitlements(conn: SqlClient, targets: List<MarketOrderItem>) {
        for (item in targets) {
            val timed = runCatching { JsonObject(item.snapshot ?: "{}").getString("billingMode") }.getOrNull().let { it == "TIMED" || it == "SUBSCRIPTION" }

            for (e in entitlements.getByOrderItemId(item.id, conn)) {
                if (e.status == EntitlementStatus.UPGRADED) throw InvalidState("UPGRADED")

                if (timed && e.status == EntitlementStatus.ACTIVE && othersLive(conn, e)) throw InvalidState("ENTITLEMENT_CHAIN")
            }
        }
    }

    private suspend fun othersLive(conn: SqlClient, e: MarketEntitlement): Boolean =
        entitlements.getByOwnerAndProduct(e.ownerKey, e.productId, conn).any { it.id != e.id && it.variantId == e.variantId && it.status == EntitlementStatus.ACTIVE }

    // ===== list (08 section 14.5, 04 section 7) ==========================================================================

    class Filter(
        val status: DeliveryStatus? = null,
        val phase: DeliveryPhase? = null,
        val serverId: Long? = null,
        val actionType: DeliveryActionType? = null,
        val search: String? = null
    )

    class Page(val rows: List<JsonObject>, val total: Long)

    suspend fun list(filter: Filter, window: Paging.Window): Page {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()

        filter.status?.let { where += "d.`status` = ?"; args += it.name }
        filter.phase?.let { where += "d.`phase` = ?"; args += it.name }
        filter.serverId?.let { where += "d.`serverId` = ?"; args += it }
        filter.actionType?.let { where += "d.`actionType` = ?"; args += it.name }

        filter.search?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
            val like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val orderId = term.toLongOrNull()

            where += "(d.`playerUsername` LIKE ? OR d.`idempotencyKey` LIKE ?" + (if (orderId != null) " OR d.`orderId` = ?" else "") + ")"
            args += like
            args += like
            if (orderId != null) args += orderId
        }

        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        return db.tx { pool ->
        val total = pool.preparedQuery("SELECT COUNT(*) AS c FROM ${table("market_delivery")} d $clause").execute(Tuple.from(args)).coAwait().first().getLong("c")
        val names = roster.snapshot(pool).names
        val pageArgs = ArrayList<Any?>(args).also { it += window.pageSize; it += window.offset }
        val rows = pool.preparedQuery(
            "SELECT d.*, i.`productName` AS itemProductName FROM ${table("market_delivery")} d LEFT JOIN ${table("market_order_item")} i ON i.`id` = d.`orderItemId` " +
                "$clause ORDER BY d.`id` DESC LIMIT ? OFFSET ?"
        ).execute(Tuple.from(pageArgs)).coAwait().map { r ->
            val serverId = r.getLong("serverId")

            JsonObject()
                .put("id", r.getLong("id")).put("orderId", r.getLong("orderId")).put("orderItemId", r.getLong("orderItemId"))
                .put("productName", r.getString("itemProductName")).put("playerUsername", r.getString("playerUsername"))
                .put("phase", r.getString("phase")).put("actionId", r.getString("actionId")).put("actionType", r.getString("actionType"))
                .put("transport", r.getString("transport")).put("idempotencyKey", r.getString("idempotencyKey"))
                .put("serverId", serverId).put("serverName", names[serverId]).put("status", r.getString("status")).put("attempts", r.getInteger("attempts"))
                .put("requiresOnline", r.getBoolean("requiresOnline")).put("waitUntil", r.getLong("waitUntil"))
                .put("cancelRequested", r.getLong("cancelRequestedAt") != null).put("lastErrorCode", r.getString("lastErrorCode"))
                .put("lastError", r.getString("lastError")).put("runAfter", r.getLong("runAfter")).put("sentAt", r.getLong("sentAt"))
                .put("confirmedAt", r.getLong("confirmedAt")).put("payload", payloadView(r.getString("payload"))).put("result", jsonOrText(r.getString("result")))
        }

        Page(rows, total)
        }
    }

    companion object {
        private val EMPTY = 1..0

        /** The delivery payload for the panel: the webhook secret (stored encrypted) is never part of it (08 section 14.5). */
        fun payloadView(payload: String?): Any? {
            val json = (jsonOrText(payload) as? JsonObject) ?: return jsonOrText(payload)
            val copy = json.copy()
            val webhook = copy.getValue("webhook")

            if (webhook is JsonObject) copy.put("webhook", webhook.copy().also { it.remove("secret") })

            return copy
        }

        private fun jsonOrText(text: String?): Any? {
            if (text == null) return null

            return runCatching { JsonObject(text) }.getOrNull() ?: runCatching { JsonArray(text) }.getOrNull() ?: text
        }
    }
}

