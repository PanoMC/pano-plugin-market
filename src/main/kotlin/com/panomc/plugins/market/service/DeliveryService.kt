package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.Coverage
import com.panomc.plugins.market.core.delivery.DeliveryEffect
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.DeliveryEvent
import com.panomc.plugins.market.core.delivery.DeliveryPlanner
import com.panomc.plugins.market.core.delivery.DeliveryRow
import com.panomc.plugins.market.core.delivery.DeliveryRules
import com.panomc.plugins.market.core.delivery.DeliveryStateMachine
import com.panomc.plugins.market.core.delivery.DeliveryTransition
import com.panomc.plugins.market.core.delivery.FieldValue
import com.panomc.plugins.market.core.delivery.FulfillmentCalculator
import com.panomc.plugins.market.core.delivery.PlanEntitlement
import com.panomc.plugins.market.core.delivery.PlanItem
import com.panomc.plugins.market.core.delivery.PlanOrder
import com.panomc.plugins.market.core.delivery.PlanRequest
import com.panomc.plugins.market.core.delivery.PlanServers
import com.panomc.plugins.market.core.delivery.PlanSettings
import com.panomc.plugins.market.core.delivery.PlannedDelivery
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.StoreInfo
import com.panomc.plugins.market.core.delivery.TargetResolver
import com.panomc.plugins.market.core.delivery.WebhookBodyInput
import com.panomc.plugins.market.core.delivery.DefaultWebhookBody
import com.panomc.plugins.market.core.delivery.WebhookFormat as PlanWebhookFormat
import com.panomc.plugins.market.core.webhook.DiscordLabelSource
import com.panomc.plugins.market.core.webhook.DiscordLabels
import com.panomc.plugins.market.core.webhook.DiscordRenderer
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.db.dao.MarketWebhookDeliveryDao
import com.panomc.plugins.market.db.model.MarketWebhookDelivery
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.db.model.WebhookFormat
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.core.subscription.PeriodCalculator
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryTransport
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.service.platform.PlayerAccounts
import com.panomc.plugins.market.service.platform.ServerRoster
import com.panomc.plugins.market.service.platform.UserDirectory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlin.random.Random

/** An inline executor that cannot do its work. [retryable] failures go back to `PENDING` with a backoff (D4) until the attempts are used up (D5). */
class InlineFailure(val code: String, message: String?, val retryable: Boolean = false) : RuntimeException(message)

/**
 * Delivery engine, database side (08 sections 5 to 7, 11, 13): plans rows inside the caller's transaction, applies the transitions of
 * [DeliveryStateMachine] with conditional updates, runs the inline executors `CREDIT` (through the ledger key `delivery:<id>`) and
 * `PERMISSION via=PANO` ([PermissionGrantService]), re-asserts permission grants, and keeps `market_order.fulfillmentStatus`.
 *
 * **Planning** ([planGrant], [planEnd], [planRevoke], [insertPlanned]) runs on the connection of the business transaction, after the order row is
 * locked (08 section 5.1): nothing here opens a transaction, the rows commit or roll back with the order. `INSERT IGNORE` on `uq_idem` makes a
 * replayed transition create nothing twice. An order with `fulfillmentBy = GATEWAY` gets no rows at all.
 *
 * **Execution** ([claimDue], [execute] and the job steps [promote], [recoverStaleClaims], [reassertDue], [classify]) opens its own transactions,
 * each in the lock order of 00 section 8.3: credit accounts, then the order row, then the delivery row (re-checked: `SENDING` and the claim token).
 * Two workers can select the same row; the conditional claim lets exactly one run it.
 *
 * The `WEBHOOK` executor (08 section 7.3) writes the outbox row of the action and runs only when an outbox is wired. Not here (other slices): the server rows (`MARKET_SYNC`: D7 - D11, D20, D14:
 * MK-103), the panel operations re-run / retry / cancel (MK-104), chargeback and payout rows (MK-112, MK-114).
 */
class DeliveryService(
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val ids: Ids,
    private val config: () -> MarketConfig,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val orderEvents: MarketOrderEventDao,
    private val deliveries: MarketDeliveryDao,
    private val entitlements: MarketEntitlementDao,
    private val creditAccounts: MarketCreditAccountDao,
    private val products: MarketProductDao,
    private val productFields: MarketProductFieldDao,
    private val roster: ServerRoster,
    private val users: UserDirectory,
    private val accounts: PlayerAccounts,
    private val credits: CreditService,
    private val permissions: PermissionGrantService,
    private val random: Random = Random.Default,
    /** The outbox of store webhooks: with it the inline `WEBHOOK` executor exists (08 section 7.3, MK-106); without it `WEBHOOK` rows are never claimed. */
    private val webhookDeliveries: MarketWebhookDeliveryDao? = null,
    /** The localised texts of `format = DISCORD` action bodies (08 section 16.2); without it the stand-in body of the planner is used. */
    private val discordLabels: DiscordLabelSource? = null
) {
    private fun table(name: String) = "`${deliveries.prefix()}$name`"

    private val deliveryTable get() = table("market_delivery")

    /** The action types this instance executes inline: `WEBHOOK` only when the outbox is wired. */
    private val inlineTypes: Set<DeliveryActionType> =
        if (webhookDeliveries != null) INLINE_TYPES else INLINE_TYPES - DeliveryActionType.WEBHOOK

    private fun rules(): DeliveryRules = config().let { DeliveryRules(it.deliveryMaxAttempts, it.deliveryAckTimeoutSeconds) }

    // ===== planning ======================================================================================================

    /**
     * `GRANT` rows of a paid order (O2 / O4, 08 section 5.1), `RENEW` for a line that extends a timed chain ([EntitlementService.isExtension]) or
     * belongs to a renewal order. The entitlements of the order must exist already ([EntitlementService.onPaid]). Answers the ids of the rows
     * written (a replayed call answers none).
     */
    suspend fun planGrant(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>): List<Long> {
        if (order.fulfillmentBy == FulfillmentBy.GATEWAY) return emptyList()

        val ents = items.associate { it.id to entitlements.getByOrderItemId(it.id, conn).firstOrNull() }
        val env = environment(conn, order, items, ents)
        val renewal = order.source == OrderSource.RENEWAL
        val renew = items.filter { renewal || ents[it.id]?.let(EntitlementService::isExtension) == true }.map { it.id }.toSet()
        val now = clock.now()
        val planned = ArrayList<PlannedDelivery>()

        for ((phase, ofPhase) in listOf(DeliveryPhase.GRANT to items.filter { it.id !in renew }, DeliveryPhase.RENEW to items.filter { it.id in renew })) {
            if (ofPhase.isEmpty()) continue

            // The planner walks every item it is given (a bundle child needs its parent). Every item gets a range of its own: the whole line in its own
            // phase, an empty one in the other. An item without an entry would follow its parent's range (a bundle child, 08 section 11.2), and a child
            // of the other phase than its parent would then inherit the parent's switched-off range and be planned in neither phase.
            val inPhase = ofPhase.mapTo(HashSet()) { it.id }
            val units = items.associate { it.id to if (it.id in inPhase) (0 until it.quantity) else EMPTY_RANGE }

            planned += DeliveryPlanner.plan(PlanRequest(env.order, env.items.values.toList(), phase, env.servers, env.settings, now, 0, units))
        }

        return insertPlanned(conn, planned)
    }

    /**
     * The end flow of an entitlement that expired ([DeliveryPhase.EXPIRE]) or of a revoke ([planRevoke]), 08 section 11.1 steps 1 to 3: GRANT / RENEW
     * rows that were not sent yet are cancelled (D16), rows in flight get a cancel request (D17), then the `EXPIRE` rows are planned; the predecessor
     * gate (11.4) holds them until the in-flight rows resolved. The entitlement itself (step 4, coverage of step 5) is [EntitlementService]'s (MK-107).
     * The caller holds the order lock. [reason] is `ENTITLEMENT_ENDED` (expiry) or `ORDER_REVOKED`. Without [units] every item given is ended whole; a caller
     * that ends one line of a bundle names the range of each line (an empty range switches the bundle line itself off, MK-107).
     */
    suspend fun planEnd(
        conn: SqlConnection,
        order: MarketOrder,
        items: List<MarketOrderItem>,
        reason: String = DeliveryError.ENTITLEMENT_ENDED,
        coverage: Map<Long, Coverage> = emptyMap(),
        units: Map<Long, IntRange> = emptyMap()
    ): EndPlan = endFlow(conn, order, items, DeliveryPhase.EXPIRE, reason, units, emptyMap(), coverage, 0)

    /**
     * `REVOKE` of the units [units] of items (refund, chargeback, manual revoke; 08 section 11.2). [revokedBefore] are the units that non-cancelled
     * `REVOKE` rows covered already (a permission is removed only when the last unit is revoked). [attemptGroup] is `0` unless this is a re-run.
     * The caller computes both maps (refunded quantity before this refund; the rows alone cannot tell the range of a row that is not `perUnit`).
     *
     * The ranges follow 08 section 11.2: a line without a range of its own (a bundle child) takes its bundle line's range scaled by its quantity per bundle.
     * Unsent rows covering units of the range are cancelled (D16), rows in flight get a cancel request (D17). A whole-line row that D16 cancelled while the
     * buyer keeps some of its units is planned again for those units (new attempt group, same schedule), so a partial refund never takes away what is left.
     */
    suspend fun planRevoke(
        conn: SqlConnection,
        order: MarketOrder,
        items: List<MarketOrderItem>,
        units: Map<Long, IntRange>,
        revokedBefore: Map<Long, Set<Int>> = emptyMap(),
        reason: String = DeliveryError.ORDER_REVOKED,
        coverage: Map<Long, Coverage> = emptyMap(),
        attemptGroup: Int = 0
    ): EndPlan = endFlow(conn, order, items, DeliveryPhase.REVOKE, reason, units, revokedBefore, coverage, attemptGroup)

    /**
     * What an end flow did: rows cancelled (D16), cancels requested (D17), the undo rows planned ([inserted]) and the rows planned again for the units
     * of a partly revoked line that the buyer still owns ([replanned]).
     */
    class EndPlan(val cancelled: Int, val cancelRequested: Int, val inserted: List<Long>, val replanned: List<Long> = emptyList())

    private suspend fun endFlow(
        conn: SqlConnection,
        order: MarketOrder,
        items: List<MarketOrderItem>,
        phase: DeliveryPhase,
        reason: String,
        units: Map<Long, IntRange>,
        revokedBefore: Map<Long, Set<Int>>,
        coverage: Map<Long, Coverage>,
        attemptGroup: Int
    ): EndPlan {
        if (order.fulfillmentBy == FulfillmentBy.GATEWAY) return EndPlan(0, 0, emptyList())

        val ents = items.associate { it.id to entitlements.getByOrderItemId(it.id, conn).firstOrNull() }
        val env = environment(conn, order, items, ents)
        var cancelled = 0
        var requested = 0
        val replans = ArrayList<PlannedDelivery>()

        // steps 1 and 2: unsent rows covering units of U are cancelled, rows in flight get a cancel request
        for (item in items) {
            if (item.kind == OrderItemKind.CREDIT_TOPUP) continue

            val covered = coveredUnits(items, item, units) ?: continue
            val actions = env.items[item.id]?.actions?.actions.orEmpty().associateBy { it.id }
            val rows = deliveries.getByOrderItemId(item.id, conn)
            val gone = revokedBefore[item.id].orEmpty()
            val kept = ArrayList<Pair<MarketDelivery, List<IntRange>>>()

            for (row in rows) {
                if (row.phase != DeliveryPhase.GRANT && row.phase != DeliveryPhase.RENEW) continue

                val action = actions[row.actionId]

                if (row.unitIndex >= item.quantity) continue

                // the units a whole-line row stands for (a per unit row: its own); read from the rows as they were before this flow
                val standsFor = if (perUnitRow(row, action)) listOf(row.unitIndex) else wholeLineUnits(row, rows, item.quantity, gone)

                if (standsFor.none { it in covered }) continue

                val applied = apply(conn, row.id, DeliveryEvent.Cancel(reason))

                if (!applied.moved) continue

                if (applied.row?.status == DeliveryStatus.CANCELLED) {
                    cancelled++

                    // a whole-line row stood for units the buyer keeps: they are planned again below
                    val left = standsFor.filter { it !in covered }

                    if (!perUnitRow(row, action) && left.isNotEmpty()) kept += row to contiguous(left)
                } else {
                    requested++
                }
            }

            if (kept.isNotEmpty()) replans += replanKept(env, item, kept, rows, clock.now())
        }

        // step 3: the undo rows, with what really happened to the grants (read after steps 1 and 2)
        val planItems = items.map { item ->
            val base = env.items.getValue(item.id)
            val prior = deliveries.getByOrderItemId(item.id, conn).map { it.toRow() }

            PlanItem(
                id = base.id, kind = base.kind, parentItemId = base.parentItemId, productId = base.productId, productName = base.productName,
                productSlug = base.productSlug, productSku = base.productSku, variantId = base.variantId, variantName = base.variantName,
                variantSku = base.variantSku, variantAttributes = base.variantAttributes, fields = base.fields, quantity = base.quantity,
                lineTotal = base.lineTotal, targetServerId = base.targetServerId, serverChoices = base.serverChoices, actions = base.actions,
                entitlement = base.entitlement, revokedUnits = revokedBefore[item.id].orEmpty(), priorRows = prior
            )
        }
        val now = clock.now()
        val request = PlanRequest(env.order, planItems, phase, env.servers, env.settings, now, attemptGroup, units, coverage)
        val inserted = insertPlanned(conn, DeliveryPlanner.plan(request))
        val replanned = insertPlanned(conn, replans)

        refreshFulfillment(conn, order.id)

        return EndPlan(cancelled, requested, inserted, replanned)
    }

    /**
     * The unit range an end flow covers for [item], exactly as `DeliveryPlanner.plan` reads it for step 3 (08 section 11.2): the range given for the
     * line; for a `BUNDLE_CHILD` without a range of its own, the range of its bundle line scaled by the child's quantity per bundle
     * (`U.first x n until (U.last + 1) x n`); else the whole line. Clipped to the line, `null` when nothing is left.
     */
    private fun coveredUnits(items: List<MarketOrderItem>, item: MarketOrderItem, units: Map<Long, IntRange>): IntRange? {
        if (item.quantity <= 0) return null

        val parent = if (item.kind == OrderItemKind.BUNDLE_CHILD) item.parentItemId?.let { id -> items.firstOrNull { it.id == id } } else null
        val given = units[item.id]
        val parentUnits = parent?.let { units[it.id] }
        val range = when {
            given != null -> given
            parent != null && parentUnits != null -> {
                val n = if (parent.quantity > 0) item.quantity / parent.quantity else 1

                (parentUnits.first * n)..((parentUnits.last + 1) * n - 1)
            }
            else -> 0 until item.quantity
        }
        val clipped = maxOf(range.first, 0)..minOf(range.last, item.quantity - 1)

        return if (clipped.isEmpty()) null else clipped
    }

    /** One row per unit exists only for a `perUnit` action of type `COMMAND` / `WEBHOOK` (the planner's rule); every other row stands for the whole line. */
    private fun perUnitRow(row: MarketDelivery, action: ProductAction?): Boolean =
        action?.perUnit == true && (row.actionType == DeliveryActionType.COMMAND || row.actionType == DeliveryActionType.WEBHOOK)

    /**
     * The units a whole-line row (an action that is not `perUnit`) stands for: from its `unitIndex` up to the `unitIndex` of the next live row of the same
     * item, action, server and phase, or the end of the line, without the units [revoked] by earlier `REVOKE` rows. A row planned for the whole line has
     * `unitIndex` 0 and stands for every unit; a row planned again by [replanKept] stands for the kept range it was planned for. [rows] are the rows of the
     * item as they were before the end flow touched them.
     */
    private fun wholeLineUnits(row: MarketDelivery, rows: List<MarketDelivery>, quantity: Int, revoked: Set<Int>): List<Int> {
        val end = rows.filter {
            it.id != row.id && it.phase == row.phase && it.actionId == row.actionId && it.serverId == row.serverId && it.status != DeliveryStatus.CANCELLED &&
                it.unitIndex > row.unitIndex && it.unitIndex < quantity
        }.minOfOrNull { it.unitIndex } ?: quantity

        return (row.unitIndex until end).filter { it !in revoked }
    }

    private fun contiguous(units: List<Int>): List<IntRange> {
        val out = ArrayList<IntRange>()

        for (u in units.sorted()) {
            val last = out.lastOrNull()

            if (last != null && last.last + 1 == u) out[out.size - 1] = last.first..u else out += u..u
        }

        return out
    }

    /**
     * The units of a partly revoked line that the buyer still owns, for the whole-line rows that an end flow cancelled (D16): the same action in the same
     * phase on the same server for each kept range, as attempt group `max + 1` of the item (new keys), with the schedule of the row that was cancelled (an
     * action `delay` is not started again). Rows that were only asked to cancel (D17) are not replaced: they may still take effect, and the undo of the
     * revoked units takes the rest back.
     */
    private fun replanKept(
        env: Environment,
        item: MarketOrderItem,
        cancelled: List<Pair<MarketDelivery, List<IntRange>>>,
        rows: List<MarketDelivery>,
        now: Long
    ): List<PlannedDelivery> {
        val base = env.items.getValue(item.id)
        val parent = if (base.kind == OrderItemKind.BUNDLE_CHILD) base.parentItemId?.let { env.items[it] } else null
        val request = listOfNotNull(parent, base)
        val group = rows.maxOf { it.attemptGroup } + 1
        val out = ArrayList<PlannedDelivery>()

        for ((was, ranges) in cancelled) {
            for (range in ranges) {
                // the parent is only there to be read (targets, fields); it is switched off with an empty range
                val limits = HashMap<Long, IntRange>()

                parent?.let { limits[it.id] = EMPTY_RANGE }
                limits[item.id] = range

                for (p in DeliveryPlanner.plan(PlanRequest(env.order, request, was.phase, env.servers, env.settings, now, group, limits))) {
                    if (p.actionId != was.actionId || (p.status != DeliveryStatus.FAILED && p.serverId != was.serverId)) continue

                    // a row that is born FAILED (render error, no target server any more) stays as the planner made it: it is visible and retryable
                    out += if (p.status == DeliveryStatus.FAILED) p else retimed(p, was, now)
                }
            }
        }

        return out
    }

    private fun retimed(p: PlannedDelivery, was: MarketDelivery, now: Long): PlannedDelivery =
        p.copy(
            runAfter = was.runAfter, status = if (was.runAfter > now) DeliveryStatus.SCHEDULED else DeliveryStatus.PENDING, nextAttemptAt = was.runAfter,
            waitUntil = was.waitUntil
        )

    /**
     * The rows the planner makes for [phase] from the snapshots of [items] as attempt group [attemptGroup], over the whole line of every item and
     * with the item's existing rows as the planner's `priorRows`: the panel re-run of 08 section 14.1 (MK-104). Nothing is written: the caller
     * drops what its rules skip and hands the rest to [insertPlanned]. The caller holds the order lock.
     */
    suspend fun planAgain(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, phase: DeliveryPhase, attemptGroup: Int): List<PlannedDelivery> {
        if (order.fulfillmentBy == FulfillmentBy.GATEWAY) return emptyList()

        val ents = items.associate { it.id to entitlements.getByOrderItemId(it.id, conn).firstOrNull() }
        val env = environment(conn, order, items, ents)
        val planItems = items.map { item ->
            val base = env.items.getValue(item.id)

            PlanItem(
                id = base.id, kind = base.kind, parentItemId = base.parentItemId, productId = base.productId, productName = base.productName,
                productSlug = base.productSlug, productSku = base.productSku, variantId = base.variantId, variantName = base.variantName,
                variantSku = base.variantSku, variantAttributes = base.variantAttributes, fields = base.fields, quantity = base.quantity,
                lineTotal = base.lineTotal, targetServerId = base.targetServerId, serverChoices = base.serverChoices, actions = base.actions,
                entitlement = base.entitlement, priorRows = deliveries.getByOrderItemId(item.id, conn).map { it.toRow() }
            )
        }

        return DeliveryPlanner.plan(PlanRequest(env.order, planItems, phase, env.servers, env.settings, clock.now(), attemptGroup))
    }

    /**
     * `planChargebackActions` (08 section 12, MK-112): the rows of `MarketConfig.chargebackActions` for the dispute [disputeId] of [order] (phase `GRANT`,
     * `sourceType = CHARGEBACK_ACTION`, key prefix `cb:<disputeId>`). Nothing is written; the caller hands the rows to [insertPlanned] under the order lock.
     * The player is the payer of an account order; for a guest order whose payer and recipient differ the planner makes the rows `CANCELLED` /
     * `NEEDS_CONFIRMATION`. With [confirmed] (the admin's "run chargeback actions", attempt group `+ 1`) the recipient of a guest order is the target, so the
     * rows are live. `emptyList()` when no action is configured.
     */
    suspend fun planChargebackActions(conn: SqlConnection, order: MarketOrder, disputeId: Long, attemptGroup: Int = 0, confirmed: Boolean = false): List<PlannedDelivery> {
        val c = config()
        val stored = ActionParser.parseStored(c.chargebackActions, ActionParser.Kind.CHARGEBACK)

        if (stored.actions.isEmpty() && stored.dropped.isEmpty()) return emptyList()

        val servers = roster.snapshot(conn)
        val recipient = order.recipientUsername.ifBlank { order.playerUsername }
        val parties = if (confirmed) TargetResolver.Parties(recipientUsername = recipient, payerUsername = recipient, payerUserId = null)
        else TargetResolver.Parties(recipientUsername = recipient, payerUsername = order.playerUsername.ifBlank { recipient }, payerUserId = order.userId)
        val planOrder = PlanOrder(
            id = order.id, publicId = order.publicId.orEmpty(), totalPrice = order.totalPrice, currency = order.currency, parties = parties, giftMessage = order.giftMessage,
            source = order.source, fulfillmentBy = order.fulfillmentBy, testMode = order.testMode
        )
        val settings = PlanSettings(
            onlineWaitDays = c.deliveryOnlineWaitDays, zone = PeriodCalculator.zoneOf(c.storeTimeZone), store = StoreInfo(c.storeName, ""),
            webhookBody = webhookBodyRendererFor(stored.actions)
        )

        return DeliveryPlanner.planChargebackActions(
            com.panomc.plugins.market.core.delivery.ChargebackRequest(planOrder, disputeId, stored, PlanServers(servers.lookup(), servers.names), settings, clock.now(), attemptGroup)
        )
    }

    /**
     * Inserts [planned] rows with `INSERT IGNORE` semantics (the unique key `uq_idem`), writes one `DELIVERY_FAILED` timeline row for every row that
     * is born `FAILED`, and recomputes the fulfilment of the orders touched. Answers the ids of the rows that were new.
     */
    suspend fun insertPlanned(conn: SqlConnection, planned: List<PlannedDelivery>): List<Long> {
        val now = clock.now()
        val inserted = ArrayList<Long>()
        val touched = LinkedHashSet<Long>()

        for (p in planned) {
            val id = deliveries.add(
                MarketDelivery(
                    sourceType = p.sourceType, orderId = p.orderId, orderItemId = p.orderItemId, sourceId = p.sourceId, entitlementId = p.entitlementId,
                    subscriptionId = p.subscriptionId, phase = p.phase, actionId = p.actionId, actionType = p.actionType, unitIndex = p.unitIndex,
                    attemptGroup = p.attemptGroup, serverId = p.serverId, idempotencyKey = p.idempotencyKey, status = p.status,
                    requiresOnline = p.requiresOnline, playerUsername = p.playerUsername, playerUuid = p.playerUuid, payload = p.payload,
                    transport = p.transport, guaranteed = p.guaranteed, attempts = p.attempts, runAfter = p.runAfter, nextAttemptAt = p.nextAttemptAt,
                    waitUntil = p.waitUntil, lastErrorCode = p.lastErrorCode, lastError = p.lastError, createdAt = now, updatedAt = now
                ),
                conn
            ) ?: continue

            inserted += id
            p.orderId?.let { touched += it }

            if (p.status == DeliveryStatus.FAILED && p.orderId != null) recordFailed(conn, p.orderId, id, p.lastErrorCode ?: DeliveryError.REJECTED, now)
        }

        for (orderId in touched) refreshFulfillment(conn, orderId)

        return inserted
    }

    /** `market_order.fulfillmentStatus` from the rows and entitlements of the order (08 section 13). The caller holds the order lock. */
    suspend fun refreshFulfillment(conn: SqlClient, orderId: Long) {
        val order = orders.getById(orderId, conn) ?: return
        val rows = deliveries.getByOrderId(orderId, conn).map { it.toRow() }
        val statuses = conn.preparedQuery("SELECT `status` FROM ${table("market_entitlement")} WHERE `orderId` = ?").execute(Tuple.of(orderId)).coAwait()
            .map { EntitlementStatus.valueOf(it.getString("status")) }
        val value = FulfillmentCalculator.calculate(rows, statuses, order.fulfillmentBy).status

        if (value == order.fulfillmentStatus) return

        conn.preparedQuery("UPDATE ${table("market_order")} SET `fulfillmentStatus` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ?")
            .execute(Tuple.of(value.name, clock.now(), orderId)).coAwait()
    }

    private suspend fun recordFailed(conn: SqlClient, orderId: Long, deliveryId: Long, code: String, now: Long) {
        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = OrderEventType.DELIVERY_FAILED, actorType = OrderActorType.SYSTEM,
                data = JsonObject().put("deliveryId", deliveryId).put("code", code).encode(), createdAt = now, updatedAt = now
            ),
            conn
        )
    }

    // ----- plan inputs -------------------------------------------------------------------------------------------------

    private class Environment(val order: PlanOrder, val items: Map<Long, PlanItem>, val servers: PlanServers, val settings: PlanSettings)

    private suspend fun environment(conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>, ents: Map<Long, MarketEntitlement?>): Environment {
        val c = config()
        val servers = roster.snapshot(conn)
        val productIds = items.mapNotNull { it.productId }.distinct()
        val fieldDefs = if (productIds.isEmpty()) emptyMap() else productFields.getByProductIds(productIds, conn).groupBy { it.productId }
        val choices = HashMap<Long, List<Long>>()

        for (id in productIds) {
            choices[id] = products.getById(id, conn)?.serverChoices?.let { raw -> runCatching { JsonArray(raw).mapNotNull { (it as? Number)?.toLong() } }.getOrNull() }.orEmpty()
        }

        val recipient = order.recipientUsername.ifBlank { order.playerUsername }
        val planOrder = PlanOrder(
            id = order.id, publicId = order.publicId.orEmpty(), totalPrice = order.totalPrice, currency = order.currency,
            parties = TargetResolver.Parties(recipientUsername = recipient, payerUsername = order.playerUsername.ifBlank { recipient }, payerUserId = order.userId),
            giftMessage = order.giftMessage, source = order.source, fulfillmentBy = order.fulfillmentBy, testMode = order.testMode
        )
        val planItems = LinkedHashMap<Long, PlanItem>()

        for (item in items) {
            val snap = item.snapshot?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() } ?: JsonObject()
            val ent = ents[item.id]

            planItems[item.id] = PlanItem(
                id = item.id, kind = item.kind, parentItemId = item.parentItemId, productId = item.productId ?: 0, productName = item.productName,
                productSlug = snap.getString("slug").orEmpty(), productSku = item.sku, variantId = item.variantId, variantName = item.variantName,
                variantAttributes = snap.getJsonObject("variantAttributes")?.map?.mapValues { it.value?.toString().orEmpty() }.orEmpty(),
                fields = fieldValuesOf(item, fieldDefs[item.productId].orEmpty()), quantity = item.quantity, lineTotal = item.lineTotal,
                targetServerId = item.targetServerId, serverChoices = choices[item.productId].orEmpty(),
                actions = ActionParser.parseStored(snap.getJsonArray("actions")?.encode()),
                entitlement = ent?.let { PlanEntitlement(it.id, it.expiresAt, it.subscriptionId) }
            )
        }

        return Environment(
            planOrder, planItems, PlanServers(servers.lookup(), servers.names),
            PlanSettings(
                onlineWaitDays = c.deliveryOnlineWaitDays, zone = PeriodCalculator.zoneOf(c.storeTimeZone), store = StoreInfo(c.storeName, ""),
                webhookBody = webhookBodyRenderer(planItems.values)
            )
        )
    }

    /**
     * The body renderer of `WEBHOOK` actions at plan time: `JSON` keeps the planner's body, `DISCORD` goes through [DiscordRenderer] with the localised labels
     * of the four `action.*` events (read before planning because the planner is not suspending). Without a label source the stand-in body is kept.
     */
    private suspend fun webhookBodyRenderer(items: Collection<PlanItem>): (WebhookBodyInput) -> String = webhookBodyRendererFor(items.flatMap { it.actions.actions })

    private suspend fun webhookBodyRendererFor(actions: List<ProductAction>): (WebhookBodyInput) -> String {
        val source = discordLabels ?: return DefaultWebhookBody::render
        val hasDiscord = actions.any { it.type == DeliveryActionType.WEBHOOK && it.webhook?.format == PlanWebhookFormat.DISCORD }

        if (!hasDiscord) return DefaultWebhookBody::render

        val labels = HashMap<String, DiscordLabels>()

        for (event in WebhookEvents.ACTION_EVENTS) labels[event] = source.labels(event)

        return { input ->
            if (input.spec.format != PlanWebhookFormat.DISCORD) {
                DefaultWebhookBody.render(input)
            } else {
                val envelope = JsonObject(
                    DefaultWebhookBody.render(
                        WebhookBodyInput(
                            input.event, input.phase, input.spec.copy(format = PlanWebhookFormat.JSON), input.key, input.actionId, input.unitIndex, input.quantity,
                            input.order, input.item, input.player, input.serverId, input.context, input.now, input.store
                        )
                    )
                )

                DiscordRenderer.render(null, DiscordRenderer.vars(input.event, envelope, labels[input.event] ?: DiscordLabels.DEFAULT))
            }
        }
    }

    /** `market_order_item.fieldValues` (key -> scalar) typed by the product's field definitions; a value whose field is gone is dropped (its variable then renders empty). */
    private fun fieldValuesOf(item: MarketOrderItem, defs: List<com.panomc.plugins.market.db.model.MarketProductField>): Map<String, FieldValue> {
        val raw = item.fieldValues?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return emptyMap()
        val out = LinkedHashMap<String, FieldValue>()

        for ((key, value) in raw.map) {
            val def = defs.firstOrNull { it.fieldKey == key } ?: continue
            val text = when (value) {
                null -> ""
                is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
                else -> value.toString()
            }

            out[key] = FieldValue(def.type, text, def.pattern, def.usableInCommands)
        }

        return out
    }

    // ===== state transitions =============================================================================================

    /** What [apply] did: the machine's [transition], whether the row [moved], and the row as it is now (`null` when the row does not exist). */
    class Applied(val transition: DeliveryTransition, val moved: Boolean, val row: MarketDelivery?)

    /**
     * Decides [event] for the row [deliveryId] and applies the result with `UPDATE ... WHERE id = ? AND status = :from` (and the claim token when
     * [claimToken] is given, the guard of an executor's commit). Zero rows means the row moved under us: it is read and decided once more, then given up
     * silently. Effects that touch the order (`RecomputeFulfillment`, `RecordDeliveryFailed`) need the order lock: the caller holds it (08 section 6).
     */
    suspend fun apply(conn: SqlClient, deliveryId: Long, event: DeliveryEvent, claimToken: String? = null): Applied {
        var last: Applied? = null

        repeat(2) {
            val row = deliveries.getById(deliveryId, conn) ?: return Applied(DeliveryTransition.NoOp, false, null)

            if (claimToken != null && row.claimToken != claimToken) return Applied(DeliveryTransition.NoOp, false, row)

            val now = clock.now()
            val transition = DeliveryStateMachine.decide(row.toRow(), event, now, rules(), random)

            if (transition !is DeliveryTransition.Move) return Applied(transition, false, row)

            if (write(conn, row, transition, claimToken, now)) return Applied(transition, true, deliveries.getById(deliveryId, conn))

            last = Applied(transition, false, row)
        }

        return last!!
    }

    private suspend fun write(conn: SqlClient, row: MarketDelivery, move: DeliveryTransition.Move, claimToken: String?, now: Long): Boolean {
        val sets = ArrayList<String>()
        val values = ArrayList<Any?>()

        fun set(column: String, value: Any?) {
            sets += "`$column` = ?"
            values += value
        }

        set("status", move.to.name)

        var failed: String? = null
        var recompute = false

        for (effect in move.effects) {
            when (effect) {
                is DeliveryEffect.Claim -> {
                    set("claimToken", ids.uuid())
                    set("claimedUntil", effect.until)
                }

                DeliveryEffect.ClearClaim -> {
                    set("claimToken", null)
                    set("claimedUntil", null)
                }

                DeliveryEffect.IncrementAttempts -> sets += "`attempts` = `attempts` + 1"
                DeliveryEffect.ResetAttempts -> set("attempts", 0)

                is DeliveryEffect.StampSent -> {
                    sets += "`sentAt` = COALESCE(`sentAt`, ?)"
                    values += effect.at
                }

                is DeliveryEffect.StampConfirmed -> set("confirmedAt", effect.at)
                is DeliveryEffect.RecordResult -> set("result", effect.result)
                is DeliveryEffect.SetNextAttemptAt -> set("nextAttemptAt", effect.at)

                is DeliveryEffect.SetError -> {
                    set("lastErrorCode", effect.code)
                    set("lastError", effect.message?.take(DeliveryStateMachine.MAX_ERROR_LENGTH))
                }

                DeliveryEffect.ClearError -> {
                    set("lastErrorCode", null)
                    set("lastError", null)
                }

                is DeliveryEffect.RequestCancel -> set("cancelRequestedAt", effect.at)
                DeliveryEffect.RecomputeFulfillment -> recompute = true
                is DeliveryEffect.RecordDeliveryFailed -> failed = effect.code
            }
        }

        set("updatedAt", now)

        var where = "`id` = ? AND `status` = ?"
        val args = ArrayList<Any?>(values)

        args += row.id
        args += row.status.name

        if (claimToken != null) {
            where += " AND `claimToken` = ?"
            args += claimToken
        }

        val changed = conn.preparedQuery("UPDATE $deliveryTable SET ${sets.joinToString(", ")} WHERE $where").execute(Tuple.from(args)).coAwait().rowCount()

        if (changed == 0) return false

        val orderId = row.orderId

        if (orderId != null) {
            failed?.let { recordFailed(conn, orderId, row.id, it, now) }

            if (recompute || failed != null) refreshFulfillment(conn, orderId)
        }

        return true
    }

    private fun MarketDelivery.toRow() = DeliveryRow(
        sourceType = sourceType, orderItemId = orderItemId, actionId = actionId, actionType = actionType, phase = phase, serverId = serverId,
        unitIndex = unitIndex, attemptGroup = attemptGroup, transport = transport ?: if (serverId != 0L) DeliveryTransport.MARKET_MC else DeliveryTransport.INLINE,
        status = status, attempts = attempts, runAfter = runAfter, nextAttemptAt = nextAttemptAt, cancelRequestedAt = cancelRequestedAt, waitUntil = waitUntil,
        claimedUntil = claimedUntil, sentAt = sentAt, lastErrorCode = lastErrorCode
    )

    /** [apply] under the order lock, for transitions that recompute the fulfilment (08 section 6, last paragraph). */
    private suspend fun applyLocked(deliveryId: Long, event: DeliveryEvent, claimToken: String? = null): Applied =
        db.txRestartingOnOrderChange { conn ->
            val orderId = deliveries.getById(deliveryId, conn)?.orderId

            withOrder(conn, orderId) { apply(conn, deliveryId, event, claimToken) }
        }

    private suspend fun <T> withOrder(conn: SqlConnection, orderId: Long?, block: suspend () -> T): T =
        if (orderId == null) block() else locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) { block() }

    private suspend fun lockRow(conn: SqlConnection, id: Long): MarketDelivery? {
        conn.preparedQuery("SELECT `id` FROM $deliveryTable WHERE `id` = ? FOR UPDATE").execute(Tuple.of(id)).coAwait()

        return deliveries.getById(id, conn)
    }

    // ===== the job steps =================================================================================================

    /** D1: `SCHEDULED` rows whose `runAfter` has come become `PENDING`. */
    suspend fun promote(limit: Int = PROMOTE_BATCH): Int {
        val now = clock.now()
        val due = db.tx { conn ->
            conn.preparedQuery("SELECT `id` FROM $deliveryTable WHERE `status` = 'SCHEDULED' AND `runAfter` <= ? ORDER BY `id` LIMIT ?")
                .execute(Tuple.of(now, limit)).coAwait().map { it.getLong("id") }
        }
        var moved = 0

        for (id in due) if (db.tx { conn -> apply(conn, id, DeliveryEvent.Promote) }.moved) moved++

        return moved
    }

    /**
     * D2: claims up to [limit] due inline rows whose executor exists ([INLINE_TYPES]) and whose predecessor gate is open (08 section 11.4). Several
     * workers may select the same rows; the conditional update gives each row to exactly one of them.
     *
     * An undo row (`EXPIRE` / `REVOKE`) whose gate is open is checked before it is claimed: when nothing it undoes ever took effect it is cancelled
     * (D22, `NOTHING_TO_REVOKE`) instead of run. Without this a credit reversal planned while its grant was still `SENDING` would take the buyer's own
     * credits back for a grant that then failed.
     */
    suspend fun claimDue(limit: Int = INLINE_BATCH): List<MarketDelivery> {
        val now = clock.now()
        val types = inlineTypes.joinToString(",") { "'${it.name}'" }
        val candidates = db.tx { conn ->
            conn.preparedQuery(
                "SELECT d.`id`, (d.`phase` IN ('EXPIRE','REVOKE') AND d.`orderItemId` IS NOT NULL) AS isUndo FROM $deliveryTable d WHERE d.`status` = 'PENDING' " +
                    "AND d.`transport` = 'INLINE' AND d.`actionType` IN ($types) AND d.`nextAttemptAt` IS NOT NULL AND d.`nextAttemptAt` <= ? AND ${gateOpen("d")} " +
                    "ORDER BY d.`id` LIMIT ?"
            ).execute(Tuple.of(now, limit)).coAwait().map { it.getLong("id") to (it.getValue("isUndo") as Number).toInt() }
        }
        val claimed = ArrayList<MarketDelivery>()

        for ((id, undo) in candidates) {
            if (undo != 0 && cancelIfNothingDelivered(id)) continue

            val applied = db.tx { conn -> apply(conn, id, DeliveryEvent.Claim) }

            if (applied.moved) applied.row?.let { claimed += it }
        }

        return claimed
    }

    /** The predecessor gate of 08 section 11.4 for the delivery table alias [d]: true when the row is no undo, or nothing it undoes is in flight. */
    private fun gateOpen(d: String): String =
        "($d.`phase` IN ('GRANT','RENEW') OR $d.`orderItemId` IS NULL OR NOT EXISTS (SELECT 1 FROM $deliveryTable g WHERE g.`orderItemId` = $d.`orderItemId` " +
            "AND g.`serverId` = $d.`serverId` AND g.`phase` IN ('GRANT','RENEW') AND g.`status` IN ('SENDING','SENT','QUEUED')))"

    /** D6: a `SENDING` row whose claim ran out (the worker died) goes back to `PENDING`; every inline executor is idempotent. */
    suspend fun recoverStaleClaims(limit: Int = INLINE_BATCH): Int {
        val now = clock.now()
        val stale = db.tx { conn ->
            conn.preparedQuery("SELECT `id` FROM $deliveryTable WHERE `status` = 'SENDING' AND `claimedUntil` IS NOT NULL AND `claimedUntil` < ? ORDER BY `id` LIMIT ?")
                .execute(Tuple.of(now, limit)).coAwait().map { it.getLong("id") }
        }
        var moved = 0

        for (id in stale) if (applyLocked(id, DeliveryEvent.ClaimExpired).moved) moved++

        return moved
    }

    /**
     * D22: an `EXPIRE` / `REVOKE` row whose gate is open and whose predecessors never took effect is cancelled (`NOTHING_TO_REVOKE`): a refund before the
     * player ever got the goods neither delivers nor takes back (08 section 11.4). Server rows in `WAITING_SERVER` are included.
     */
    suspend fun classify(limit: Int = CLASSIFY_BATCH): Int {
        val gated = db.tx { conn ->
            conn.preparedQuery(
                "SELECT d.`id` FROM $deliveryTable d WHERE d.`phase` IN ('EXPIRE','REVOKE') AND d.`orderItemId` IS NOT NULL AND " +
                    "(d.`status` = 'PENDING' OR (d.`status` = 'WAITING_SERVER' AND d.`transport` = 'MARKET_MC')) AND ${gateOpen("d")} ORDER BY d.`id` LIMIT ?"
            ).execute(Tuple.of(limit)).coAwait().map { it.getLong("id") }
        }
        var moved = 0

        for (id in gated) if (cancelIfNothingDelivered(id)) moved++

        return moved
    }

    /**
     * D22 for one row under the order lock: cancelled (`NOTHING_TO_REVOKE`) when its gate is open and nothing it undoes ever took effect. `true` when the row moved.
     * `internal` because `McSyncService` checks every undo row it is about to offer with it, one row at a time (08 section 11.4).
     */
    internal suspend fun cancelIfNothingDelivered(id: Long): Boolean {
        val applied = db.txRestartingOnOrderChange { conn ->
            val row = deliveries.getById(id, conn) ?: return@txRestartingOnOrderChange null

            withOrder(conn, row.orderId) {
                val fresh = deliveries.getById(id, conn) ?: return@withOrder null

                apply(conn, id, DeliveryEvent.GateOpened(nothingDelivered(conn, fresh)))
            }
        }

        return applied?.moved == true
    }

    /** 08 section 11.4: no GRANT / RENEW row the undo refers to is `CONFIRMED` or `FAILED (UNKNOWN_OUTCOME)`. */
    private suspend fun nothingDelivered(conn: SqlClient, undo: MarketDelivery): Boolean {
        val itemId = undo.orderItemId ?: return false
        val snapshot = orderItems.getById(itemId, conn)?.snapshot?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() }
        val action = ActionParser.parseStored(snapshot?.getJsonArray("actions")?.encode()).actions.firstOrNull { it.id == undo.actionId }
        // an explicit EXPIRE / REVOKE action undoes the item as a whole; an automatic inverse refers to the one action it inverts
        val explicit = action != null && (action.phase == DeliveryPhase.EXPIRE || action.phase == DeliveryPhase.REVOKE)
        val sql = "SELECT COUNT(*) AS n FROM $deliveryTable WHERE `orderItemId` = ? AND `phase` IN ('GRANT','RENEW') " +
            "AND (`status` = 'CONFIRMED' OR (`status` = 'FAILED' AND `lastErrorCode` = 'UNKNOWN_OUTCOME'))" + if (explicit) "" else " AND `actionId` = ?"
        val args = if (explicit) Tuple.of(itemId) else Tuple.of(itemId, undo.actionId)

        return conn.preparedQuery(sql).execute(args).coAwait().first().getLong("n") == 0L
    }

    // ===== inline execution ==================================================================================================

    /**
     * Runs one claimed inline row (08 section 7): its executor in one transaction under the lock order (credit accounts, order, delivery row), then D3, or
     * D4 / D5 when it failed. Answers the status the row ended in, or `null` when the claim was lost (another worker, or the row moved).
     */
    suspend fun execute(claimed: MarketDelivery): DeliveryStatus? {
        val token = claimed.claimToken ?: return null

        try {
            val result = when (claimed.actionType) {
                DeliveryActionType.CREDIT -> db.txRestartingOnOrderChange { conn -> runCredit(conn, claimed, token) }
                DeliveryActionType.PERMISSION -> permissions.serialized(claimed.playerUsername) { runPermission(claimed, token) }
                DeliveryActionType.WEBHOOK -> db.txRestartingOnOrderChange { conn -> runWebhook(conn, claimed, token) }
                else -> throw IllegalStateException("no inline executor for ${claimed.actionType}: the claim query must not select it")
            }

            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: InlineFailure) {
            return fail(claimed, token, DeliveryEvent.InlineFailed(e.code, e.message, e.retryable))
        } catch (e: Exception) {
            logger.warn("inline delivery {} failed: {}", claimed.id, e.toString())

            return fail(claimed, token, DeliveryEvent.InlineFailed(DeliveryError.DB_ERROR, e.message?.take(300) ?: e.javaClass.simpleName, retryable = true))
        }
    }

    private suspend fun fail(claimed: MarketDelivery, token: String, event: DeliveryEvent.InlineFailed): DeliveryStatus? {
        val applied = applyLocked(claimed.id, event, token)

        return if (applied.moved) applied.row?.status else null
    }

    /** The row under its lock, or `null` when this worker no longer owns it (status `SENDING` and the claim token). */
    private suspend fun owned(conn: SqlConnection, claimed: MarketDelivery, token: String): MarketDelivery? =
        lockRow(conn, claimed.id)?.takeIf { it.status == DeliveryStatus.SENDING && it.claimToken == token }

    private suspend fun succeed(conn: SqlConnection, row: MarketDelivery, token: String, result: JsonObject): DeliveryStatus? {
        val applied = apply(conn, row.id, DeliveryEvent.InlineSucceeded(result.encode()), token)

        return if (applied.moved) applied.row?.status else null
    }

    // ----- CREDIT (08 section 7.1) ------------------------------------------------------------------------------------

    private suspend fun runCredit(conn: SqlConnection, claimed: MarketDelivery, token: String): DeliveryStatus? {
        val payload = JsonObject(claimed.payload)
        val amount = payload.getLong("credits", 0L)
        val reverse = payload.getBoolean("reverse", false)

        if (amount <= 0) throw InlineFailure(DeliveryError.INVALID_PAYLOAD, "a credit delivery needs a positive amount")

        val order = claimed.orderId?.let { orders.getById(it, conn) }
        // 08 section 4.1: the recipient's account, else the user of that name; no user => NO_ACCOUNT, a balance is never parked on a shell account
        val userId = order?.recipientUserId?.takeIf { it > 0 } ?: users.byUsername(claimed.playerUsername, conn)?.id?.takeIf { it > 0 }
            ?: throw InlineFailure(DeliveryError.NO_ACCOUNT, "${claimed.playerUsername} has no Pano account")

        // level 4: the accounts this posting moves, ascending, before the order row
        creditAccounts.insertUserAccountIgnore(userId, conn)

        val userAccount = creditAccounts.getByUserId(userId, conn) ?: throw IllegalStateException("the credit account of user $userId was not created")
        val systemAccount = creditAccounts.getBySystemKey(if (reverse) CreditSystemKey.REVOKED else CreditSystemKey.ISSUANCE, conn)
            ?: throw IllegalStateException("system credit account is missing")

        locks.creditAccounts(conn, listOf(userAccount.id, systemAccount.id))

        return withOrder(conn, claimed.orderId) {
            val row = owned(conn, claimed, token) ?: return@withOrder null
            val key = "delivery:${row.id}"
            val posted = if (reverse) {
                credits.post(
                    Posting(
                        CreditTxType.ACTION_REVERSAL, key, userId, amount, AccountRef.User(userId), AccountRef.System(CreditSystemKey.REVOKED), PostingPolicy.TAKE_AVAILABLE,
                        orderId = row.orderId, deliveryId = row.id
                    ),
                    conn
                )
            } else {
                credits.post(
                    Posting(CreditTxType.ACTION, key, userId, amount, AccountRef.System(CreditSystemKey.ISSUANCE), AccountRef.User(userId), orderId = row.orderId, deliveryId = row.id),
                    conn
                )
            }
            val result = JsonObject().put("creditTxId", posted.tx.id).put("userId", userId)

            if (reverse) result.put("shortfall", posted.tx.shortfall)

            succeed(conn, row, token, result)
        }
    }

    // ----- WEBHOOK (08 section 7.3) -----------------------------------------------------------------------------------

    /**
     * One transaction: lock the order, insert the outbox row of the action (`endpointId = 0`, `deliveryId = <row id>`, `eventId = nameUUIDFromBytes("action:<id>")`,
     * `INSERT IGNORE` on `uq_eventId`: a retried claim finds its row again), then the delivery becomes `SENT` with `result = {"webhookDeliveryId": n}` (D3).
     * `WebhookJob` sends the row and reports `SUCCEEDED` (D12, `CONFIRMED`) or `DEAD` (D21) back through [reportWebhook]. `DISCORD` never signs (08 section 15.2).
     */
    private suspend fun runWebhook(conn: SqlConnection, claimed: MarketDelivery, token: String): DeliveryStatus? {
        val outbox = webhookDeliveries ?: throw InlineFailure(DeliveryError.INVALID_PAYLOAD, "no webhook outbox is wired", retryable = false)
        val hook = runCatching { JsonObject(claimed.payload).getJsonObject("webhook") }.getOrNull()
            ?: throw InlineFailure(DeliveryError.INVALID_PAYLOAD, "a webhook delivery needs a webhook payload")
        val url = hook.getString("url")?.takeIf { it.isNotBlank() } ?: throw InlineFailure(DeliveryError.INVALID_PAYLOAD, "the webhook has no url")
        val format = runCatching { WebhookFormat.valueOf(hook.getString("format") ?: "JSON") }.getOrDefault(WebhookFormat.JSON)
        val signing = if (format == WebhookFormat.DISCORD) WebhookSigning.NONE else runCatching { WebhookSigning.valueOf(hook.getString("signing") ?: "NONE") }.getOrDefault(WebhookSigning.NONE)

        return withOrder(conn, claimed.orderId) {
            val row = owned(conn, claimed, token) ?: return@withOrder null
            val eventId = WebhookEvents.actionEventId(row.id)
            val now = clock.now()
            val event = hook.getString("event") ?: "action.${row.phase.name.lowercase()}"
            val body = withEventId(hook.getString("body").orEmpty(), format, eventId)
            val inserted = outbox.add(
                MarketWebhookDelivery(
                    endpointId = 0, deliveryId = row.id, eventId = eventId, event = event, orderId = row.orderId, url = url, format = format, signing = signing,
                    secret = if (signing == WebhookSigning.HMAC_SHA256) hook.getString("secret") else null, body = body,
                    status = WebhookDeliveryStatus.PENDING, attempts = 0, maxAttempts = WEBHOOK_ACTION_ATTEMPTS, nextAttemptAt = now, createdAt = now, updatedAt = now
                ),
                conn
            ) ?: outbox.getByEventId(eventId, conn)?.id ?: throw IllegalStateException("the webhook row of delivery ${row.id} vanished")

            succeed(conn, row, token, JsonObject().put("webhookDeliveryId", inserted))
        }
    }

    /** The planner renders the body before the delivery has an id: a `JSON` body gets the event id as `id` now, a `DISCORD` body has none. */
    private fun withEventId(body: String, format: WebhookFormat, eventId: String): String {
        if (format != WebhookFormat.JSON) return body

        val parsed = runCatching { JsonObject(body) }.getOrNull() ?: return body

        return JsonObject().put("id", eventId).also { out -> parsed.forEach { (k, v) -> if (k != "id") out.put(k, v) } }.encode()
    }

    // ----- PERMISSION via=PANO (08 section 7.2) --------------------------------------------------------------------------

    private suspend fun runPermission(claimed: MarketDelivery, token: String): DeliveryStatus? {
        var changed = false
        val status = db.txRestartingOnOrderChange { conn ->
            changed = false

            val payload = JsonObject(claimed.payload)

            if (payload.getString("via") != "PANO") throw InlineFailure(DeliveryError.INVALID_PAYLOAD, "an inline permission row must be via=PANO")

            val op = payload.getString("op")
            val nodes = payload.getJsonArray("nodes")?.map { it.toString() }.orEmpty()
            val scope = payload.getJsonObject("context") ?: JsonObject()
            val expiresAt = payload.getLong("expiresAt")

            if (nodes.isEmpty()) throw InlineFailure(DeliveryError.INVALID_PAYLOAD, "a permission delivery names no node")

            withOrder(conn, claimed.orderId) {
                val row = owned(conn, claimed, token) ?: return@withOrder null

                if (op == "REMOVE") {
                    val removal = remove(conn, row, nodes, scope)

                    changed = removal.second

                    succeed(conn, row, token, removal.first)
                } else {
                    val userId = resolvePermissionUser(conn, row)
                    val tuples = nodes.map { PermissionGrantService.Tuple(it, DeliveryPlanner.nodeContext(it, scope)) }
                    val granted = permissions.grant(userId, tuples, expiresAt, extend = op == "EXTEND", heldElsewhere = { heldElsewhere(conn, row, it, lowerTo = expiresAt) }, sqlClient = conn)

                    changed = granted.changed

                    succeed(conn, row, token, resultOf(userId, granted.nodes, 0))
                }
            }
        }

        // after the commit, never inside the transaction
        if (changed) permissions.publish()

        return status
    }

    private fun resultOf(userId: Long, nodes: List<PermissionGrantService.Recorded>, verify: Int) =
        JsonObject().put("userId", userId).put("nodes", JsonArray(nodes.map { it.toJson() })).put("verify", verify)

    /** The user a rank is written for: the order's recipient account, else the name (created the way a join of that player would create it). */
    private suspend fun resolvePermissionUser(conn: SqlConnection, row: MarketDelivery): Long {
        if (!TargetResolver.Player(row.playerUsername, null).valid) throw InlineFailure(DeliveryError.INVALID_PLAYER, "${row.playerUsername} is not a valid player name")

        row.orderId?.let { orders.getById(it, conn) }?.recipientUserId?.takeIf { it > 0 }?.let { return it }

        users.byUsername(row.playerUsername, conn)?.let { return it.id }

        return accounts.findOrCreate(row.playerUsername, conn) ?: throw InlineFailure(DeliveryError.INVALID_PLAYER, "${row.playerUsername} cannot be created as a user")
    }

    /** REMOVE: the tuples recorded by the confirmed GRANT / RENEW rows of the same item and action. Answers the result JSON and whether a row was deleted. */
    private suspend fun remove(conn: SqlConnection, row: MarketDelivery, nodes: List<String>, scope: JsonObject): Pair<JsonObject, Boolean> {
        val itemId = row.orderItemId ?: return JsonObject().put("removed", JsonArray()).put("kept", JsonArray()) to false
        val granted = deliveries.getByOrderItemId(itemId, conn).filter {
            it.actionId == row.actionId && it.actionType == DeliveryActionType.PERMISSION && it.status == DeliveryStatus.CONFIRMED &&
                (it.phase == DeliveryPhase.GRANT || it.phase == DeliveryPhase.RENEW) && it.result != null
        }
        val userId = granted.firstNotNullOfOrNull { runCatching { JsonObject(it.result!!).getLong("userId") }.getOrNull() }

        // the nodes of the undo are the ones the grants recorded (the tuple, with the id the row had); a node of the payload that was never recorded is nothing to remove
        val recorded = LinkedHashMap<String, PermissionGrantService.Recorded>()

        for (g in granted) {
            val array = runCatching { JsonObject(g.result!!).getJsonArray("nodes") }.getOrNull() ?: continue

            for (any in array) {
                val entry = PermissionGrantService.Recorded.of(any as? JsonObject ?: continue)

                if (entry.node in nodes) recorded[Canon.of(entry.node, entry.context)] = entry
            }
        }

        if (userId == null || recorded.isEmpty()) return JsonObject().put("userId", userId).put("removed", JsonArray()).put("kept", JsonArray()) to false

        val removal = permissions.remove(userId, recorded.values.toList(), { heldElsewhere(conn, row, it) }, conn)
        val result = JsonObject().put("userId", userId).put("removed", JsonArray(removal.removed.map { it.toJson() })).put("kept", JsonArray(removal.kept.map { it.toJson() }))

        return result to removal.changed
    }

    /**
     * Does another confirmed, still-active market grant of the same player hold [tuple]? (08 section 7.2: buying two ranks that share a node and
     * refunding one must not remove the shared node.) Rows of the same order item never count: they are the grant being undone.
     *
     * [lowerTo] is set when an `EXTEND` would lower the node's expiry to that instant (the coverage correction of 08 section 11.1 step 5): a grant whose
     * entitlement ends at or before it does not need the later expiry, so only entitlements that run past [lowerTo] (or never end) hold the node. Without it
     * the other links of the same timed chain would always protect the later expiry and the correction could never lower anything (MK-107).
     */
    private suspend fun heldElsewhere(conn: SqlClient, row: MarketDelivery, tuple: PermissionGrantService.Tuple, lowerTo: Long? = null): Boolean {
        val now = maxOf(clock.now(), lowerTo ?: Long.MIN_VALUE)
        val payloads = conn.preparedQuery(
            "SELECT d.`payload` AS p FROM $deliveryTable d JOIN ${table("market_entitlement")} e ON e.`id` = d.`entitlementId` " +
                "WHERE d.`status` = 'CONFIRMED' AND d.`actionType` = 'PERMISSION' AND d.`transport` = 'INLINE' AND d.`phase` IN ('GRANT','RENEW') " +
                "AND d.`playerUsername` = ? AND (d.`orderItemId` IS NULL OR d.`orderItemId` <> ?) AND e.`status` = 'ACTIVE' AND (e.`expiresAt` IS NULL OR e.`expiresAt` > ?)"
        ).execute(Tuple.of(row.playerUsername, row.orderItemId ?: 0L, now)).coAwait().map { it.getString("p") }

        return payloads.any { raw ->
            val payload = runCatching { JsonObject(raw) }.getOrNull() ?: return@any false

            payload.getString("via") == "PANO" && payload.getJsonArray("nodes").orEmpty().any { node ->
                node.toString() == tuple.node && Canon.of(tuple.node, DeliveryPlanner.nodeContext(tuple.node, payload.getJsonObject("context") ?: JsonObject())) == tuple.identity
            }
        }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()

    // ===== action webhooks (08 sections 7.3 and 15.5) =======================================================================

    /**
     * D12 / D21 for the delivery [deliveryId] of a product `WEBHOOK` action: its outbox row ended `SUCCEEDED` ([succeeded], also after a redeliver of a
     * dead one: a positive result always wins) or `DEAD`. Runs on [conn], the connection of the outbox row's own status change, under the order lock.
     * The executor that inserts the outbox row and sets the delivery `SENT` is MK-106's; this is the report back that [DeliveryWebhookReporter] hands to
     * `WebhookService`.
     */
    suspend fun reportWebhook(conn: SqlConnection, deliveryId: Long, succeeded: Boolean): Applied {
        val orderId = deliveries.getById(deliveryId, conn)?.orderId

        return withOrder(conn, orderId) { apply(conn, deliveryId, if (succeeded) DeliveryEvent.WebhookSucceeded else DeliveryEvent.WebhookDead) }
    }

    // ===== re-assertion (08 section 7.2) ====================================================================================

    /**
     * `CONFIRMED` permission rows whose check is due: the nodes of the result are verified and missing ones added again (the lost-grant race), only
     * while the entitlement is `ACTIVE` (an item without an entitlement: while no `REVOKE` row of it is confirmed). The next check follows at
     * 5 min, 15 min, then none; after the third pass a missing node is taken as a deliberate removal.
     */
    suspend fun reassertDue(limit: Int = INLINE_BATCH): Int {
        val now = clock.now()
        val due = db.tx { conn ->
            conn.preparedQuery(
                "SELECT `id` FROM $deliveryTable WHERE `status` = 'CONFIRMED' AND `transport` = 'INLINE' AND `actionType` = 'PERMISSION' AND `phase` IN ('GRANT','RENEW') " +
                    "AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ? ORDER BY `nextAttemptAt`, `id` LIMIT ?"
            ).execute(Tuple.of(now, limit)).coAwait().map { it.getLong("id") }
        }
        var handled = 0

        for (id in due) {
            val first = db.tx { conn -> deliveries.getById(id, conn) } ?: continue

            var changed = false
            val done = permissions.serialized(first.playerUsername) {
                db.tx { conn ->
                    val row = lockRow(conn, id)

                    if (row == null || row.status != DeliveryStatus.CONFIRMED || row.nextAttemptAt == null || row.nextAttemptAt > clock.now()) return@tx false

                    if (!stillAsserted(conn, row)) {
                        setNextCheck(conn, row, null, row.result)

                        return@tx true
                    }

                    val result = runCatching { JsonObject(row.result ?: "{}") }.getOrDefault(JsonObject())
                    val userId = result.getLong("userId") ?: return@tx false
                    val pass = result.getInteger("verify", 0) + 1
                    val recorded = result.getJsonArray("nodes").orEmpty().mapNotNull { (it as? JsonObject)?.let(PermissionGrantService.Recorded::of) }
                    val verified = permissions.verify(userId, recorded, conn)
                    val next = when (pass) {
                        1 -> clock.now() + REASSERT_SECOND_MS
                        2 -> clock.now() + REASSERT_THIRD_MS
                        else -> null
                    }

                    changed = verified.readded > 0
                    setNextCheck(conn, row, next, resultOf(userId, verified.nodes, pass).encode())

                    true
                }
            }

            if (changed) permissions.publish()

            if (done) handled++
        }

        return handled
    }

    private suspend fun stillAsserted(conn: SqlClient, row: MarketDelivery): Boolean {
        val entitlementId = row.entitlementId

        if (entitlementId != null) return entitlements.getById(entitlementId, conn)?.status == EntitlementStatus.ACTIVE

        val itemId = row.orderItemId ?: return true

        return conn.preparedQuery("SELECT COUNT(*) AS n FROM $deliveryTable WHERE `orderItemId` = ? AND `phase` = 'REVOKE' AND `status` = 'CONFIRMED'")
            .execute(Tuple.of(itemId)).coAwait().first().getLong("n") == 0L
    }

    private suspend fun setNextCheck(conn: SqlClient, row: MarketDelivery, next: Long?, result: String?) {
        conn.preparedQuery("UPDATE $deliveryTable SET `nextAttemptAt` = ?, `result` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'CONFIRMED'")
            .execute(Tuple.of(next, result, clock.now(), row.id)).coAwait()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeliveryService::class.java)

        /** The action types that have an inline executor; `DeliveryJob` claims nothing else (`WEBHOOK` only on a service with an outbox). */
        val INLINE_TYPES: Set<DeliveryActionType> = setOf(DeliveryActionType.CREDIT, DeliveryActionType.PERMISSION, DeliveryActionType.WEBHOOK)

        /** An action webhook is retried like a store webhook: 8 attempts (08 section 7.3). */
        const val WEBHOOK_ACTION_ATTEMPTS = 8

        const val INLINE_BATCH = 50
        const val PROMOTE_BATCH = 200
        const val CLASSIFY_BATCH = 100

        /** The second and third pass of the permission re-assertion (the first is `REASSERT_MS` after the confirmation, in the state machine). */
        const val REASSERT_SECOND_MS = 5 * 60_000L
        const val REASSERT_THIRD_MS = 15 * 60_000L

        private val EMPTY_RANGE = 1..0
    }
}

/**
 * The effects of O2 / O4 that belong to the delivery engine (06 section 11), for [OrderService]: `GrantEntitlements` creates the entitlements
 * ([EntitlementService.onPaid]) and `QueueGrantDeliveries` plans the `GRANT` / `RENEW` rows ([DeliveryService.planGrant]), both inside the order
 * transition; every other effect goes to [next]. The order is read again (the `LockedOrder` was read before `StampPaid`).
 */
class DeliveryEffects(
    private val entitlementService: EntitlementService,
    private val deliveryService: DeliveryService,
    private val orders: MarketOrderDao,
    private val next: ForeignEffects = ForeignEffects.PENDING_SLICES
) : ForeignEffects {
    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: com.panomc.plugins.market.core.order.OrderEffect) {
        when (effect) {
            is com.panomc.plugins.market.core.order.OrderEffect.GrantEntitlements ->
                entitlementService.onPaid(conn, current(conn, locked), locked.items)

            is com.panomc.plugins.market.core.order.OrderEffect.QueueGrantDeliveries ->
                deliveryService.planGrant(conn, current(conn, locked), locked.items)

            else -> next.apply(conn, locked, effect)
        }
    }

    private suspend fun current(conn: SqlConnection, locked: LockedOrder): MarketOrder =
        orders.getById(locked.order.id, conn) ?: error("order ${locked.order.id} vanished inside its transaction")
}

/**
 * The report back of an action webhook (08 section 15.5): `WebhookService` calls it when the outbox row of a product `WEBHOOK` action ends `SUCCEEDED`
 * or `DEAD`, and the delivery row follows (D12 `CONFIRMED`, D21 `FAILED (WEBHOOK_DEAD)`; a later redelivery that succeeds confirms it again).
 */
class DeliveryWebhookReporter(private val service: DeliveryService) : WebhookDeliveryReporter {
    override suspend fun report(conn: SqlConnection, row: com.panomc.plugins.market.db.model.MarketWebhookDelivery, decision: com.panomc.plugins.market.core.webhook.Decision) {
        when (decision.status) {
            com.panomc.plugins.market.db.model.WebhookDeliveryStatus.SUCCEEDED -> service.reportWebhook(conn, row.deliveryId, true)
            com.panomc.plugins.market.db.model.WebhookDeliveryStatus.DEAD -> service.reportWebhook(conn, row.deliveryId, false)
            else -> Unit
        }
    }
}
