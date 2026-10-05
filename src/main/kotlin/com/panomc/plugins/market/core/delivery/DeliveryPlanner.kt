package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryTransport
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** The order columns planning needs (`market_order`). Money is x100 in the order currency. */
class PlanOrder(
    val id: Long,
    val publicId: String = "",
    val totalPrice: Long = 0,
    val currency: String = "",
    val parties: TargetResolver.Parties,
    val giftMessage: String? = null,
    val source: OrderSource = OrderSource.STOREFRONT,
    /**
     * The player names of this order were typed by an admin (manual order, `PLAYER` block): the wider alphabet of
     * 00 section 8.4 applies. Always on for `source = PANEL`; a buyer's name never gets it.
     */
    val adminSuppliedNames: Boolean = false,
    val fulfillmentBy: FulfillmentBy = FulfillmentBy.MARKET,
    val testMode: Boolean = false
) {
    internal val usernameOrigin: UsernameOrigin
        get() = if (adminSuppliedNames || source == OrderSource.PANEL) UsernameOrigin.ADMIN else UsernameOrigin.BUYER
}

/** The entitlement of an item: [expiresAt] is the chain end after the change (`null` = permanent), 08 section 10.2. */
class PlanEntitlement(val id: Long, val expiresAt: Long? = null, val subscriptionId: Long? = null)

/**
 * 08 section 11.1 step 5: when the buyer still owns a covering entitlement after [endedEntitlementId] ended, an
 * `EXPIRE` / `REVOKE` plan does not undo permissions and runs no explicit end actions; it extends the permission nodes to
 * the new [chainEnd] (`null` = permanent). The `CREDIT` inverse of a `REVOKE` is still planned.
 */
class Coverage(val endedEntitlementId: Long, val chainEnd: Long?)

/**
 * One order line as planning reads it. [actions] are the snapshot actions read with [ActionParser.parseStored]
 * (what was sold, not what the catalogue says now). For a `BUNDLE_CHILD`, [fields] and [targetServerId] of the parent
 * line are used (08 section 5.2); [serverChoices] stay the child product's own.
 *
 * [priorRows] are the item's existing delivery rows, read after the end flow cancelled the unsent ones: an automatic
 * inverse (`REMOVE` of a permission, credit reversal) is planned only for an action that has a `SENDING`, `CONFIRMED`,
 * `SENT`, `QUEUED` or `FAILED (UNKNOWN_OUTCOME)` `GRANT` / `RENEW` row (08 section 11.3; `SENDING` is added to the
 * spec's list: an inline row claimed but not yet executed is in flight and may still take effect). [revokedUnits] are the units already
 * covered by non-cancelled `REVOKE` rows; a permission is removed only when the last unit is revoked (08 section 11.2).
 */
class PlanItem(
    val id: Long,
    val kind: OrderItemKind = OrderItemKind.PRODUCT,
    val parentItemId: Long? = null,
    val productId: Long = 0,
    val productName: String = "",
    val productSlug: String = "",
    val productSku: String? = null,
    val variantId: Long? = null,
    val variantName: String? = null,
    val variantSku: String? = null,
    val variantAttributes: Map<String, String> = emptyMap(),
    val fields: Map<String, FieldValue> = emptyMap(),
    val quantity: Int = 1,
    val lineTotal: Long = 0,
    val targetServerId: Long? = null,
    val serverChoices: List<Long> = emptyList(),
    val actions: ActionParser.Stored = ActionParser.Stored(emptyList(), emptyList()),
    val entitlement: PlanEntitlement? = null,
    val revokedUnits: Set<Int> = emptySet(),
    val priorRows: List<DeliveryRow> = emptyList()
)

/** Server facts at plan time ([lookup]: granted / existing / connected ids) and the names for `{server.name}`. */
class PlanServers(val lookup: TargetResolver.ServerLookup = TargetResolver.ServerLookup(), val names: Map<Long, String> = emptyMap())

class StoreInfo(val name: String = "", val url: String = "")

/**
 * What a `WEBHOOK` action's body is rendered from. [event] is `action.<phase>`. [context] is the variable context of the
 * row (webhook-only names can be added with [VariableContext.withExtras]).
 */
class WebhookBodyInput(
    val event: String,
    val phase: DeliveryPhase,
    val spec: WebhookSpec,
    val key: String,
    val actionId: String,
    val unitIndex: Int,
    val quantity: Int,
    val order: PlanOrder?,
    val item: PlanItem?,
    val player: TargetResolver.Player,
    val serverId: Long,
    val context: VariableContext,
    val now: Long,
    val store: StoreInfo
)

/**
 * [onlineWaitDays] = `deliveryOnlineWaitDays` (0 = never give up), [zone] = `storeTimeZone`. [webhookBody] renders the
 * body of `WEBHOOK` actions at plan time (08 section 5.3); the default is a self-contained stand-in that
 * `DiscordRenderer` (MK-106) is meant to replace for `format = DISCORD`.
 */
class PlanSettings(
    val onlineWaitDays: Int = 0,
    val zone: ZoneId = ZoneId.of("UTC"),
    val store: StoreInfo = StoreInfo(),
    val webhookBody: (WebhookBodyInput) -> String = DefaultWebhookBody::render
)

/**
 * [phase] is `GRANT` (O2 / O4, gift redeem, manual order, re-run), `RENEW` (extending repurchase, renewal order),
 * `EXPIRE` or `REVOKE`. [units] gives a unit range per item id (default `0 until quantity`); for a `BUNDLE` line without
 * ranges of its own, a child follows the parent's range scaled by its per-bundle quantity. [attemptGroup] is `0` for the
 * first plan and `max(existing) + 1` for a re-run. [coverage] per item id: see [Coverage].
 */
class PlanRequest(
    val order: PlanOrder,
    val items: List<PlanItem>,
    val phase: DeliveryPhase,
    val servers: PlanServers = PlanServers(),
    val settings: PlanSettings = PlanSettings(),
    val now: Long = 0,
    val attemptGroup: Int = 0,
    val units: Map<Long, IntRange> = emptyMap(),
    val coverage: Map<Long, Coverage> = emptyMap()
)

/** O11 with `chargebackActions` (08 section 12). [actions] come from `ActionParser.parseStored(..., Kind.CHARGEBACK)`. */
class ChargebackRequest(
    val order: PlanOrder,
    val disputeId: Long,
    val actions: ActionParser.Stored,
    val servers: PlanServers = PlanServers(),
    val settings: PlanSettings = PlanSettings(),
    val now: Long = 0,
    val attemptGroup: Int = 0
)

/** A creator payout with `method = ACTION` (08 section 12). [amount] is x100 in [currency]. */
class PayoutRequest(
    val payoutId: Long,
    val creator: String,
    val amount: Long,
    val currency: String,
    val actions: ActionParser.Stored,
    val servers: PlanServers = PlanServers(),
    val settings: PlanSettings = PlanSettings(),
    val now: Long = 0,
    val attemptGroup: Int = 0
)

/**
 * One `market_delivery` row to insert (08 section 5.2). The service inserts with `INSERT IGNORE` on `uq_idem`, so a
 * replayed transition creates nothing twice, and sets `fulfillmentStatus` afterwards. `attempts` is 0, `guaranteed` 1.
 * [payload] is JSON text (08 section 5.3), immutable after insert; a failed row carries `{"error": ...}`.
 */
data class PlannedDelivery(
    val sourceType: DeliverySourceType,
    val orderId: Long?,
    val orderItemId: Long?,
    val sourceId: Long?,
    val entitlementId: Long?,
    val subscriptionId: Long?,
    val phase: DeliveryPhase,
    val actionId: String,
    val actionType: DeliveryActionType,
    val unitIndex: Int,
    val attemptGroup: Int,
    val serverId: Long,
    val idempotencyKey: String,
    val status: DeliveryStatus,
    val requiresOnline: Boolean,
    val playerUsername: String,
    val playerUuid: String?,
    val payload: String,
    val transport: DeliveryTransport,
    val runAfter: Long,
    val nextAttemptAt: Long?,
    val waitUntil: Long?,
    val lastErrorCode: String? = null,
    val lastError: String? = null,
    val attempts: Int = 0,
    val guaranteed: Boolean = true
) {
    /** The row as the state machine and the fulfilment calculator see it. */
    fun toRow() = DeliveryRow(
        sourceType = sourceType, orderItemId = orderItemId, actionId = actionId, actionType = actionType, phase = phase,
        serverId = serverId, unitIndex = unitIndex, attemptGroup = attemptGroup, transport = transport, status = status,
        attempts = attempts, runAfter = runAfter, nextAttemptAt = nextAttemptAt, waitUntil = waitUntil, lastErrorCode = lastErrorCode
    )
}

/**
 * Delivery planner (08 section 5, 11, 12), pure: `(order, items, phase, servers, now)` -> rows. Nothing here reads a
 * database or a clock; the caller passes what it read and inserts what comes back, inside the business transaction.
 *
 * What it decides: which actions run in a phase (08 section 2.3: `RENEW` extends permissions and, without own `RENEW`
 * actions, repeats the grant; `EXPIRE` / `REVOKE` run their own actions plus the automatic inverse of permissions and,
 * for `REVOKE`, credits), one row per target server and unit, the rendered payload, `SCHEDULED` vs `PENDING`,
 * `waitUntil`, the idempotency key, and the failed rows for what cannot be delivered (render error, no target server, a
 * stored action that no longer converts). A render error never aborts the others.
 */
object DeliveryPlanner {
    private const val DAY_MS = 86_400_000L
    private const val MAX_ERROR = 512

    // ---- keys -------------------------------------------------------------------------------------------------------

    /** `<orderItemId>` for `ORDER_ITEM`, `cb:<disputeId>` for `CHARGEBACK_ACTION`, `cp:<payoutId>` for `CREATOR_PAYOUT` (00 section 8.1). */
    fun sourceKey(sourceType: DeliverySourceType, orderItemId: Long?, sourceId: Long?): String = when (sourceType) {
        DeliverySourceType.ORDER_ITEM -> requireNotNull(orderItemId) { "ORDER_ITEM needs an order item id" }.toString()
        DeliverySourceType.CHARGEBACK_ACTION -> "cb:${requireNotNull(sourceId) { "CHARGEBACK_ACTION needs a dispute id" }}"
        DeliverySourceType.CREATOR_PAYOUT -> "cp:${requireNotNull(sourceId) { "CREATOR_PAYOUT needs a payout id" }}"
    }

    /** `<source>:<actionId>:<serverId>:<unitIndex>:<phase>:<attemptGroup>`; also the de-duplication key of the game server. */
    fun key(source: String, actionId: String, serverId: Long, unitIndex: Int, phase: DeliveryPhase, attemptGroup: Int): String =
        "$source:$actionId:$serverId:$unitIndex:${phase.name}:$attemptGroup"

    /**
     * The context a permission node is written with (08 section 5.3): the server scope of the payload, plus
     * `"pano": false` for a raw node (not `group.*`), so a purchased game permission never counts for a Pano-side check.
     */
    fun nodeContext(node: String, scope: JsonObject): JsonObject {
        val context = scope.copy()

        if (!node.startsWith("group.")) context.put("pano", false)

        return context
    }

    // ---- plan -------------------------------------------------------------------------------------------------------

    fun plan(request: PlanRequest): List<PlannedDelivery> {
        val order = request.order

        // Fulfilment authority: a gateway that delivers itself gets entitlements and no rows at all.
        if (order.fulfillmentBy == FulfillmentBy.GATEWAY) return emptyList()

        val byId = request.items.associateBy { it.id }
        val player = TargetResolver.player(DeliverySourceType.ORDER_ITEM, order.parties)
        val out = ArrayList<PlannedDelivery>()

        for (item in orderItems(request.items)) {
            if (item.kind == OrderItemKind.CREDIT_TOPUP) continue

            val parent = if (item.kind == OrderItemKind.BUNDLE_CHILD) item.parentItemId?.let { byId[it] } else null
            val covered = coveredUnits(request, item, parent) ?: continue

            val common = Common(
                source = Source(
                    DeliverySourceType.ORDER_ITEM, order.id, item.id, null, item.entitlement?.id, item.entitlement?.subscriptionId,
                    player, sourceKey(DeliverySourceType.ORDER_ITEM, item.id, null), order.usernameOrigin, order.usernameOrigin
                ),
                servers = request.servers, settings = request.settings, now = request.now, attemptGroup = request.attemptGroup, order = order
            )

            val itemPlan = ItemPlan(common, item, parent, covered)

            if (request.phase == DeliveryPhase.GRANT || request.phase == DeliveryPhase.RENEW) {
                itemPlan.droppedRows(request.phase, out)
            }

            for (step in steps(item, request.phase, request.coverage[item.id])) itemPlan.rows(step, out)
        }

        return out
    }

    /** O11 `chargebackActions`: phase `GRANT`, no item, player = payer (or recipient of a guest order). */
    fun planChargebackActions(request: ChargebackRequest): List<PlannedDelivery> {
        val order = request.order
        val player = TargetResolver.player(DeliverySourceType.CHARGEBACK_ACTION, order.parties)

        val common = Common(
            source = Source(
                DeliverySourceType.CHARGEBACK_ACTION, order.id, null, request.disputeId, null, null,
                player, sourceKey(DeliverySourceType.CHARGEBACK_ACTION, null, request.disputeId), order.usernameOrigin, order.usernameOrigin
            ),
            servers = request.servers, settings = request.settings, now = request.now, attemptGroup = request.attemptGroup, order = order
        )

        return planStandalone(common, request.actions)
    }

    /** A creator payout with `method = ACTION`: phase `GRANT`, player = the creator, no order. */
    fun planPayoutActions(request: PayoutRequest): List<PlannedDelivery> {
        val player = TargetResolver.player(DeliverySourceType.CREATOR_PAYOUT, TargetResolver.Parties(""), request.creator)

        val common = Common(
            source = Source(
                DeliverySourceType.CREATOR_PAYOUT, null, null, request.payoutId, null, null,
                player, sourceKey(DeliverySourceType.CREATOR_PAYOUT, null, request.payoutId), UsernameOrigin.ADMIN, UsernameOrigin.ADMIN
            ),
            servers = request.servers, settings = request.settings, now = request.now, attemptGroup = request.attemptGroup, order = null,
            payout = PayoutValues(request.amount, request.currency)
        )

        return planStandalone(common, request.actions)
    }

    private fun planStandalone(common: Common, actions: ActionParser.Stored): List<PlannedDelivery> {
        val out = ArrayList<PlannedDelivery>()
        val itemPlan = ItemPlan(common, null, null, 0..0)

        itemPlan.droppedRows(DeliveryPhase.GRANT, out, actions.dropped, actions.actions.map { it.id }.toSet())

        for (action in actions.actions) itemPlan.rows(Step(action, DeliveryPhase.GRANT, op = if (action.type == DeliveryActionType.PERMISSION) "ADD" else null), out)

        return out
    }

    // ---- which actions run -------------------------------------------------------------------------------------------

    /**
     * One thing to plan. [op] is the permission operation (`ADD`, `EXTEND`, `REMOVE`). [inverse]: an automatic inverse or
     * coverage extension, planned only for an action that took effect (08 section 11.3); it runs without delay and, for a
     * server permission, on the servers that took the grant. [forcedUnit] / [expiresAt] / [explicitExpiry] belong to the
     * coverage extension.
     */
    private class Step(
        val action: ProductAction,
        val phase: DeliveryPhase,
        val op: String? = null,
        val inverse: Boolean = false,
        val forcedUnit: Int? = null,
        val explicitExpiry: Boolean = false,
        val expiresAt: Long? = null
    )

    private fun permissionOp(action: ProductAction, phase: DeliveryPhase): String = when (phase) {
        DeliveryPhase.GRANT -> "ADD"
        // `via=SERVER`: ADD on the Minecraft side is an upsert of the expiry (08 section 5.3).
        DeliveryPhase.RENEW -> if (action.via == PermissionVia.PANO) "EXTEND" else "ADD"
        DeliveryPhase.EXPIRE, DeliveryPhase.REVOKE -> "REMOVE"
    }

    private fun step(action: ProductAction, phase: DeliveryPhase, inverse: Boolean = false) =
        Step(action, phase, if (action.type == DeliveryActionType.PERMISSION) permissionOp(action, phase) else null, inverse)

    private fun steps(item: PlanItem, phase: DeliveryPhase, coverage: Coverage?): List<Step> {
        val actions = item.actions.actions
        val grant = actions.filter { it.phase == DeliveryPhase.GRANT }
        val renew = actions.filter { it.phase == DeliveryPhase.RENEW }
        val permissions = (grant + renew).filter { it.type == DeliveryActionType.PERMISSION }

        return when (phase) {
            DeliveryPhase.GRANT -> grant.map { step(it, DeliveryPhase.GRANT) }

            // A(RENEW) + the automatic extend of every PERMISSION of A(GRANT); with no RENEW action the grant simply repeats.
            DeliveryPhase.RENEW ->
                if (renew.isNotEmpty()) {
                    renew.map { step(it, DeliveryPhase.RENEW) } + grant.filter { it.type == DeliveryActionType.PERMISSION }.map { step(it, DeliveryPhase.RENEW) }
                } else {
                    grant.map { step(it, DeliveryPhase.RENEW) }
                }

            DeliveryPhase.EXPIRE, DeliveryPhase.REVOKE -> {
                val explicit = actions.filter { it.phase == phase }.map { step(it, phase) }
                val credits = if (phase == DeliveryPhase.REVOKE) (grant + renew).filter { it.type == DeliveryActionType.CREDIT } else emptyList()

                if (coverage == null) {
                    explicit + permissions.map { step(it, phase, inverse = true) } + credits.map { step(it, phase, inverse = true) }
                } else {
                    // The buyer is still covered: no undo, the node expiry is corrected instead. Credits are still reversed.
                    val extend = permissions.map {
                        Step(
                            it, DeliveryPhase.RENEW, permissionOp(it, DeliveryPhase.RENEW), inverse = true,
                            forcedUnit = Math.floorMod(coverage.endedEntitlementId, 1L shl 31).toInt(), explicitExpiry = true, expiresAt = coverage.chainEnd
                        )
                    }

                    extend + credits.map { step(it, phase, inverse = true) }
                }
            }
        }
    }

    /** Parent first, then its children by id (08 section 5.2). */
    private fun orderItems(items: List<PlanItem>): List<PlanItem> =
        items.sortedWith(compareBy<PlanItem>({ it.parentItemId ?: it.id }, { if (it.parentItemId == null) 0 else 1 }, { it.id }))

    /** The units this plan covers for [item]; `null` = nothing to plan. */
    private fun coveredUnits(request: PlanRequest, item: PlanItem, parent: PlanItem?): IntRange? {
        if (item.quantity <= 0) return null

        val given = request.units[item.id]
        val parentUnits = parent?.let { request.units[it.id] }

        val range = when {
            given != null -> given
            parent != null && parentUnits != null -> {
                // A BUNDLE line with range U maps to each child as U.first * n until (U.last + 1) * n (08 section 11.2).
                val n = if (parent.quantity > 0) item.quantity / parent.quantity else 1

                (parentUnits.first * n)..((parentUnits.last + 1) * n - 1)
            }
            else -> 0 until item.quantity
        }

        val clipped = maxOf(range.first, 0)..minOf(range.last, item.quantity - 1)

        return if (clipped.isEmpty()) null else clipped
    }

    // ---- rows -------------------------------------------------------------------------------------------------------

    private class Source(
        val type: DeliverySourceType,
        val orderId: Long?,
        val orderItemId: Long?,
        val sourceId: Long?,
        val entitlementId: Long?,
        val subscriptionId: Long?,
        val player: TargetResolver.Player,
        val keyPrefix: String,
        val recipientOrigin: UsernameOrigin,
        val buyerOrigin: UsernameOrigin
    )

    private class PayoutValues(val amount: Long, val currency: String)

    private class Common(
        val source: Source,
        val servers: PlanServers,
        val settings: PlanSettings,
        val now: Long,
        val attemptGroup: Int,
        val order: PlanOrder?,
        val payout: PayoutValues? = null
    )

    private sealed class Targets {
        class Rows(val serverIds: List<Long>, val scope: JsonObject? = null) : Targets()
        class Failed(val code: String) : Targets()
    }

    /**
     * 08 section 11.3 lists `CONFIRMED`, `SENT`, `QUEUED`; `SENDING` is added (review fix): an inline grant that was claimed
     * (D2) but has not run yet is the only in-flight state of an inline row and may still post its credits or add its
     * rank, so the end flow must plan the inverse for it. The inverse is held by the predecessor gate (11.4, which lists
     * `SENDING`) until the grant resolves, and D22 cancels it when the grant never executed.
     */
    private val EXECUTED = setOf(DeliveryStatus.CONFIRMED, DeliveryStatus.SENDING, DeliveryStatus.SENT, DeliveryStatus.QUEUED)

    /** Planning of one item (or, with a null item, of a standalone action list) in one phase. */
    private class ItemPlan(
        val c: Common,
        val item: PlanItem?,
        val parent: PlanItem?,
        val covered: IntRange
    ) {
        private val src get() = c.source
        private val itemTarget = TargetResolver.ItemTarget(parent?.targetServerId ?: item?.targetServerId, item?.serverChoices.orEmpty())

        /** One `FAILED` row per stored action that no longer converts, so an admin can see and retry it. */
        fun droppedRows(
            phase: DeliveryPhase,
            out: MutableList<PlannedDelivery>,
            dropped: List<ActionParser.Dropped> = item?.actions?.dropped.orEmpty(),
            usedIds: Set<String> = item?.actions?.actions?.map { it.id }?.toSet().orEmpty()
        ) {
            val used = HashSet(usedIds)

            for (d in dropped) {
                val given = d.id?.takeIf { ID.matches(it) }
                var id = given ?: "bad${d.index.coerceAtLeast(0)}"

                while (id in used) id += "x"

                used += id

                val action = ProductAction(id, DeliveryActionType.COMMAND, phase)

                out += failedRow(Step(action, phase), 0, covered.first, DeliveryError.RENDER_ERROR, "${d.path}: ${d.code}", transport = DeliveryTransport.INLINE)
            }
        }

        fun rows(step: Step, out: MutableList<PlannedDelivery>) {
            val action = step.action
            val first = step.forcedUnit ?: covered.first

            // An automatic inverse refers to what was really executed (08 section 11.3).
            val eligible = if (step.inverse) eligibleRows(action) else emptyList()

            if (step.inverse && eligible.isEmpty()) return

            // Permission nodes are removed only when the last unit of the line is revoked (08 section 11.2).
            if (step.inverse && step.phase == DeliveryPhase.REVOKE && action.type == DeliveryActionType.PERMISSION && !reachesEnd()) return

            val targets = targets(step, eligible)

            if (targets is Targets.Failed) {
                out += failedRow(step, 0, first, targets.code, targets.code)
                return
            }

            targets as Targets.Rows

            val perUnit = action.perUnit && (action.type == DeliveryActionType.COMMAND || action.type == DeliveryActionType.WEBHOOK)
            val units = if (perUnit) covered.toList() else listOf(first)

            for (serverId in targets.serverIds) {
                for (unit in units) {
                    val quantity = if (perUnit) 1 else covered.count()

                    out += buildRow(step, serverId, unit, quantity, targets.scope)
                }
            }
        }

        private fun reachesEnd(): Boolean {
            val line = item ?: return true
            val earlierRevoked = (0 until covered.first).all { it in line.revokedUnits }

            return covered.last + 1 >= line.quantity && earlierRevoked
        }

        private fun eligibleRows(action: ProductAction): List<DeliveryRow> {
            val line = item ?: return emptyList()

            return line.priorRows.filter {
                it.sourceType == DeliverySourceType.ORDER_ITEM && it.orderItemId == line.id && it.actionId == action.id &&
                    (it.phase == DeliveryPhase.GRANT || it.phase == DeliveryPhase.RENEW) &&
                    (it.status in EXECUTED || (it.status == DeliveryStatus.FAILED && it.lastErrorCode == DeliveryError.UNKNOWN_OUTCOME))
            }
        }

        // ---- targets

        private fun targets(step: Step, eligible: List<DeliveryRow>): Targets {
            val action = step.action

            return when {
                action.type == DeliveryActionType.PERMISSION && action.via == PermissionVia.PANO -> panoScope(step)

                !action.isServerAction -> Targets.Rows(listOf(0L))

                // The inverse of a server permission goes to the servers that took the grant, also when they are offline now.
                step.inverse -> Targets.Rows(eligible.map { it.serverId }.distinct().sorted())

                else -> {
                    val resolved = TargetResolver.servers(action, itemTarget, c.servers.lookup)

                    if (resolved.failed) Targets.Failed(resolved.errorCode!!) else Targets.Rows(resolved.serverIds)
                }
            }
        }

        /** `PERMISSION via=PANO` is one inline row; the server scope goes into the node context (08 section 5.3, 4.2). */
        private fun panoScope(step: Step): Targets {
            val action = step.action
            val global = Targets.Rows(listOf(0L), JsonObject())
            val undo = step.phase == DeliveryPhase.EXPIRE || step.phase == DeliveryPhase.REVOKE

            fun scoped(ids: List<Long>) = Targets.Rows(listOf(0L), JsonObject().put("server", JsonArray(ids)))

            return when (action.serverMode) {
                ServerMode.ALL_CONNECTED -> global

                ServerMode.FIXED ->
                    if (action.targetServers.isEmpty()) {
                        global
                    } else if (undo) {
                        // A removal works from the tuples recorded by the grant; the scope is only informative.
                        scoped(action.targetServers.distinct())
                    } else {
                        val resolved = TargetResolver.servers(action.copy(via = PermissionVia.SERVER), itemTarget, c.servers.lookup)

                        if (resolved.failed) Targets.Failed(resolved.errorCode!!) else scoped(resolved.serverIds)
                    }

                ServerMode.BUYER_CHOICE ->
                    if (undo) {
                        itemTarget.targetServerId?.takeIf { it != 0L }?.let { scoped(listOf(it)) } ?: global
                    } else {
                        val resolved = TargetResolver.servers(action.copy(via = PermissionVia.SERVER), itemTarget, c.servers.lookup)

                        if (resolved.failed) Targets.Failed(resolved.errorCode!!) else scoped(resolved.serverIds)
                    }
            }
        }

        // ---- one row

        private fun buildRow(step: Step, serverId: Long, unit: Int, quantity: Int, scope: JsonObject?): PlannedDelivery {
            val action = step.action
            val key = key(src.keyPrefix, action.id, serverId, unit, step.phase, c.attemptGroup)

            val payload: JsonObject = when (action.type) {
                DeliveryActionType.CREDIT -> {
                    val credits = try {
                        Math.multiplyExact(action.credit ?: 0L, quantity.toLong())
                    } catch (e: ArithmeticException) {
                        return failedRow(step, serverId, unit, DeliveryError.RENDER_ERROR, "credits: INVALID_VALUE")
                    }

                    JsonObject().put("credits", credits).put("reverse", step.phase == DeliveryPhase.REVOKE)
                }

                DeliveryActionType.PERMISSION -> permissionPayload(step, scope)

                DeliveryActionType.COMMAND -> {
                    val context = context(step.phase, serverId, quantity, unit)
                    val commands = JsonArray()

                    for (template in action.commands) {
                        val prepared = payoutSubstitution(template)

                        if (prepared.second != null) return failedRow(step, serverId, unit, prepared.second!!.code, prepared.second!!.message)

                        when (val rendered = CommandRenderer.render(prepared.first, context)) {
                            is CommandRenderer.Result.Ok -> commands.add(rendered.command)
                            is CommandRenderer.Result.Fail -> return failedRow(step, serverId, unit, rendered.code, rendered.message)
                        }
                    }

                    JsonObject().put("commands", commands)
                }

                DeliveryActionType.WEBHOOK -> {
                    val spec = action.webhook ?: return failedRow(step, serverId, unit, DeliveryError.RENDER_ERROR, "webhook: INVALID_VALUE")
                    val event = "action.${step.phase.name.lowercase()}"

                    var context = context(step.phase, serverId, quantity, unit)

                    c.payout?.let {
                        context = context.withExtras(mapOf("payout.amount" to VariableContext.plainDecimal(it.amount), "payout.currency" to it.currency))
                    }

                    val body = c.settings.webhookBody(
                        WebhookBodyInput(
                            event, step.phase, spec, key, action.id, unit, quantity, c.order, item, src.player, serverId,
                            context, c.now, c.settings.store
                        )
                    )

                    val webhook = JsonObject()
                        .put("url", spec.url).put("format", spec.format.name).put("signing", spec.signing.name)
                        .put("secret", spec.secret).put("event", event).put("body", body)

                    JsonObject().put("webhook", webhook)
                }
            }

            return finish(step, serverId, unit, payload, key)
        }

        private fun permissionPayload(step: Step, scope: JsonObject?): JsonObject {
            val action = step.action
            val op = step.op ?: permissionOp(action, step.phase)

            val expiresAt: Long? = when {
                op == "REMOVE" -> null
                step.explicitExpiry -> step.expiresAt
                else -> item?.entitlement?.expiresAt
            }

            val payload = JsonObject().put("via", action.via.name).put("op", op).put("nodes", JsonArray(action.nodes))

            if (action.via == PermissionVia.PANO) payload.put("context", scope ?: JsonObject())

            return payload.put("expiresAt", expiresAt)
        }

        /** The failed-row shape of 08 section 5.2: status `FAILED`, payload `{"error": ...}`, never retried automatically. */
        private fun failedRow(
            step: Step,
            serverId: Long,
            unit: Int,
            code: String,
            message: String?,
            transport: DeliveryTransport = transportOf(step.action)
        ): PlannedDelivery {
            val action = step.action

            return row(
                step, serverId, unit, key(src.keyPrefix, action.id, serverId, unit, step.phase, c.attemptGroup),
                DeliveryStatus.FAILED, JsonObject().put("error", message), code, message, transport
            )
        }

        private fun finish(step: Step, serverId: Long, unit: Int, payload: JsonObject, key: String): PlannedDelivery {
            // 08 section 4.1 / 12: the target of a chargeback action for a guest order whose payer is unproven waits for an admin.
            if (src.type == DeliverySourceType.CHARGEBACK_ACTION && src.player.needsConfirmation) {
                return row(step, serverId, unit, key, DeliveryStatus.CANCELLED, payload, DeliveryError.NEEDS_CONFIRMATION, null, transportOf(step.action))
            }

            return row(step, serverId, unit, key, null, payload, null, null, transportOf(step.action))
        }

        private fun row(
            step: Step,
            serverId: Long,
            unit: Int,
            key: String,
            forced: DeliveryStatus?,
            payload: JsonObject,
            code: String?,
            message: String?,
            transport: DeliveryTransport
        ): PlannedDelivery {
            val action = step.action
            val delayMs = if (step.inverse || forced != null) 0L else action.delaySeconds * 1000L
            val runAfter = c.now + delayMs
            val status = forced ?: if (runAfter > c.now) DeliveryStatus.SCHEDULED else DeliveryStatus.PENDING
            // 11 section 10 item 2: "requires online" is ignored for chargeback actions, a ban must not wait for the player.
            val requiresOnline = action.type == DeliveryActionType.COMMAND && action.requiresOnline && status != DeliveryStatus.FAILED &&
                src.type != DeliverySourceType.CHARGEBACK_ACTION
            val live = status == DeliveryStatus.SCHEDULED || status == DeliveryStatus.PENDING

            return PlannedDelivery(
                sourceType = src.type, orderId = src.orderId, orderItemId = src.orderItemId, sourceId = src.sourceId,
                entitlementId = src.entitlementId, subscriptionId = src.subscriptionId,
                phase = step.phase, actionId = action.id, actionType = action.type, unitIndex = unit, attemptGroup = c.attemptGroup,
                serverId = serverId, idempotencyKey = key, status = status, requiresOnline = requiresOnline,
                playerUsername = src.player.username, playerUuid = src.player.uuidHint,
                payload = payload.encode(), transport = transport,
                runAfter = runAfter,
                nextAttemptAt = if (live) runAfter else null,
                waitUntil = if (live && requiresOnline && c.settings.onlineWaitDays > 0) runAfter + c.settings.onlineWaitDays * DAY_MS else null,
                lastErrorCode = code, lastError = message?.take(MAX_ERROR)
            )
        }

        /** Server actions are delivered by the Minecraft component, everything else inline (08 section 2.1). */
        private fun transportOf(action: ProductAction) = if (action.isServerAction) DeliveryTransport.MARKET_MC else DeliveryTransport.INLINE

        // ---- variables

        private fun context(phase: DeliveryPhase, serverId: Long, quantity: Int, unit: Int): VariableContext {
            val order = c.order
            val line = item
            val owner = parent

            return VariableContext.build(
                VariableContext.Input(
                    recipientUsername = src.player.username,
                    buyerUsername = order?.parties?.payerUsername?.takeIf { it.isNotEmpty() } ?: src.player.username,
                    recipientOrigin = src.recipientOrigin,
                    buyerOrigin = src.buyerOrigin,
                    playerUuid = src.player.uuidHint,
                    orderId = order?.id ?: 0,
                    orderPublicId = order?.publicId.orEmpty(),
                    orderTotal = order?.totalPrice ?: 0,
                    orderCurrency = order?.currency.orEmpty(),
                    giftMessage = order?.giftMessage,
                    productId = line?.productId ?: 0,
                    productName = line?.productName.orEmpty(),
                    productSlug = line?.productSlug.orEmpty(),
                    productSku = line?.productSku,
                    bundleName = when (line?.kind) {
                        OrderItemKind.BUNDLE -> line.productName
                        OrderItemKind.BUNDLE_CHILD -> owner?.productName
                        else -> null
                    },
                    variantName = line?.variantName,
                    variantSku = line?.variantSku,
                    variantAttributes = line?.variantAttributes.orEmpty(),
                    // A bundle child uses the parent line's field values (08 section 5.2).
                    fields = (owner ?: line)?.fields.orEmpty(),
                    quantity = quantity,
                    unit = unit + 1,
                    serverId = serverId.takeIf { it != 0L },
                    serverName = c.servers.names[serverId],
                    lineTotal = line?.lineTotal ?: 0,
                    lineQuantity = line?.quantity ?: 1,
                    expiresAtMillis = line?.entitlement?.expiresAt,
                    phase = phase,
                    now = c.now,
                    zone = c.settings.zone
                )
            )
        }

        /**
         * `{payout.amount}` (decimal) and `{payout.currency}` (identifier) of 08 section 12 are not part of the shared
         * variable catalogue, so they are substituted here with the same validators before the renderer runs. Their
         * values cannot contain a brace, so the renderer's single pass is not affected.
         */
        private fun payoutSubstitution(template: String): Pair<String, CommandRenderer.Result.Fail?> {
            val payout = c.payout ?: return template to null

            val out = StringBuilder(template.length + 16)
            var last = 0

            for (match in VariableContext.TOKEN.findAll(template)) {
                val name = match.groupValues[1]

                if (name != "payout.amount" && name != "payout.currency") continue

                val raw = if (name == "payout.amount") VariableContext.plainDecimal(payout.amount) else payout.currency
                val default = match.groups[2]?.value?.takeIf { it.isNotBlank() }
                val value = raw.takeIf { it.isNotBlank() } ?: default ?: return template to CommandRenderer.Result.Fail(name, CommandRenderer.EMPTY_VARIABLE)

                if (raw.isNotBlank() && !(if (name == "payout.amount") DECIMAL.matches(raw) else IDENTIFIER.matches(raw))) {
                    return template to CommandRenderer.Result.Fail(name, CommandRenderer.INVALID_VALUE)
                }

                out.append(template, last, match.range.first).append(value)
                last = match.range.last + 1
            }

            out.append(template, last, template.length)

            return out.toString() to null
        }
    }

    private val ID = Regex("^[a-z0-9]{1,32}$")
    private val DECIMAL = Regex("[0-9]{1,16}(\\.[0-9]{1,2})?")
    private val IDENTIFIER = Regex("[A-Za-z0-9_.:\\-]{1,64}")
}

/**
 * The stand-in body of an action webhook (08 section 15.4 `action.<phase>`, 16.2). `JSON`: the envelope without `id`
 * (it is the event id, derived from the delivery row id, which does not exist at plan time: the executor adds it) and
 * with `createdAt`, `store`, `data`. `DISCORD`: the built-in embed with the mention filter forced off. Callers that
 * need the full renderer pass their own function in [PlanSettings.webhookBody].
 */
object DefaultWebhookBody {
    private const val COLOR_ACTION = 9807270

    private const val DISCORD_TEMPLATE =
        """{"username":"{store.name}","allowed_mentions":{"parse":[]},"embeds":[{"title":"{event.title}","description":"{event.description}",""" +
            """"color":$COLOR_ACTION,"timestamp":"{event.iso}","fields":[{"name":"Player","value":"{username}","inline":true},""" +
            """{"name":"Order","value":"#{order.id}","inline":true},{"name":"Product","value":"{product.name}"}],""" +
            """"footer":{"text":"{store.name} - #{order.id}"}}]}"""

    fun render(input: WebhookBodyInput): String =
        if (input.spec.format == WebhookFormat.DISCORD) discord(input) else json(input)

    private fun decimal(x100: Long): BigDecimal = BigDecimal.valueOf(x100, 2)

    private fun json(input: WebhookBodyInput): String {
        val order = input.order
        val item = input.item

        val delivery = JsonObject()
            .put("key", input.key).put("phase", input.phase.name).put("actionId", input.actionId)
            .put("unit", input.unitIndex).put("quantity", input.quantity)

        val data = JsonObject().put("delivery", delivery)

        if (order != null) {
            data.put(
                "order",
                JsonObject().put("id", order.id).put("publicId", order.publicId).put("currency", order.currency).put("total", decimal(order.totalPrice))
            )

            data.put(
                "buyer",
                JsonObject().put("username", order.parties.payerUsername).put("userId", order.parties.payerUserId).put("uuid", order.parties.payerMcUuid)
            )

            data.put("recipient", JsonObject().put("username", order.parties.recipientUsername).put("uuid", order.parties.recipientMcUuid))
        } else {
            data.put("recipient", JsonObject().put("username", input.player.username).put("uuid", input.player.uuidHint))
        }

        if (item != null) {
            data.put(
                "item",
                JsonObject().put("id", item.id).put("productId", item.productId).put("productName", item.productName).put("slug", item.productSlug)
                    .put("kind", item.kind.name).put("variantId", item.variantId).put("variantName", item.variantName).put("sku", item.productSku)
                    .put("quantity", item.quantity).put("lineTotal", decimal(item.lineTotal)).put("targetServerId", item.targetServerId)
            )

            val attributes = JsonObject()
            item.variantAttributes.forEach { (k, v) -> attributes.put(k, v) }
            data.put("variant", JsonObject().put("name", item.variantName).put("attributes", attributes))

            val fields = JsonObject()
            item.fields.forEach { (k, v) -> fields.put(k, v.value) }
            data.put("fields", fields)

            val entitlement = item.entitlement
            data.put("entitlement", entitlement?.let { JsonObject().put("id", it.id).put("expiresAt", it.expiresAt) })
        }

        return JsonObject()
            .put("event", input.event).put("createdAt", input.now).put("apiVersion", 1).put("testMode", order?.testMode ?: false)
            .put("store", JsonObject().put("name", input.store.name).put("url", input.store.url))
            .put("data", data)
            .encode()
    }

    private fun discord(input: WebhookBodyInput): String {
        val extras = mapOf(
            "store.name" to input.store.name,
            "store.url" to input.store.url,
            "event.name" to input.event,
            "event.title" to "Delivery action (${input.phase.name.lowercase()})",
            "event.description" to "${input.actionId} for ${input.player.username}",
            "event.iso" to Instant.ofEpochMilli(input.now).toString()
        )

        return TemplateRenderer.render(DISCORD_TEMPLATE, input.context.withExtras(extras))
    }
}
