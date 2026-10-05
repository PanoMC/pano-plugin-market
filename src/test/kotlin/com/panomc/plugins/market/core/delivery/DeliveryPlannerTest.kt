package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryTransport
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.ProductFieldType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 08 section 5 (planning), 11 (end flow, unit ranges, coverage), 12 (chargeback and payout actions), 20 cases 15 - 24, R2-7. */
class DeliveryPlannerTest {
    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private val servers = PlanServers(
        TargetResolver.ServerLookup(grantedIds = listOf(1L, 2L, 5L), existingIds = listOf(1L, 2L, 3L, 4L, 5L), connectedIds = listOf(5L, 2L)),
        mapOf(1L to "Survival", 2L to "Creative", 4L to "Lobby", 5L to "Proxy")
    )

    private fun order(
        parties: TargetResolver.Parties = TargetResolver.Parties("Steve"),
        source: OrderSource = OrderSource.STOREFRONT,
        by: FulfillmentBy = FulfillmentBy.MARKET,
        admin: Boolean = false
    ) = PlanOrder(
        id = 99, publicId = "ABCDEFGHJKMNPQRSTVWX", totalPrice = 2500, currency = "TRY", parties = parties,
        source = source, fulfillmentBy = by, adminSuppliedNames = admin
    )

    private fun command(
        id: String, vararg commands: String,
        phase: DeliveryPhase = DeliveryPhase.GRANT,
        mode: ServerMode = ServerMode.FIXED,
        targets: List<Long> = listOf(1L),
        perUnit: Boolean = false,
        online: Boolean = false,
        delay: Int = 0
    ) = ProductAction(
        id, DeliveryActionType.COMMAND, phase, delaySeconds = delay, serverMode = mode, targetServers = targets,
        requiresOnline = online, perUnit = perUnit, commands = commands.toList()
    )

    private fun credit(id: String, x100: Long, phase: DeliveryPhase = DeliveryPhase.GRANT, delay: Int = 0) =
        ProductAction(id, DeliveryActionType.CREDIT, phase, delaySeconds = delay, credit = x100)

    private fun permission(
        id: String, vararg nodes: String,
        via: PermissionVia = PermissionVia.PANO,
        phase: DeliveryPhase = DeliveryPhase.GRANT,
        mode: ServerMode = ServerMode.FIXED,
        targets: List<Long> = emptyList()
    ) = ProductAction(id, DeliveryActionType.PERMISSION, phase, serverMode = mode, targetServers = targets, via = via, nodes = nodes.toList())

    private fun item(
        id: Long = 812,
        vararg actions: ProductAction,
        quantity: Int = 1,
        kind: OrderItemKind = OrderItemKind.PRODUCT,
        parent: Long? = null,
        name: String = "Diamonds",
        fields: Map<String, FieldValue> = emptyMap(),
        target: Long? = null,
        choices: List<Long> = emptyList(),
        entitlement: PlanEntitlement? = null,
        prior: List<DeliveryRow> = emptyList(),
        revoked: Set<Int> = emptySet(),
        dropped: List<ActionParser.Dropped> = emptyList()
    ) = PlanItem(
        id = id, kind = kind, parentItemId = parent, productId = 40 + id, productName = name, productSlug = "diamonds", quantity = quantity,
        lineTotal = 1000L * quantity, fields = fields, targetServerId = target, serverChoices = choices,
        actions = ActionParser.Stored(actions.toList(), dropped), entitlement = entitlement, priorRows = prior, revokedUnits = revoked
    )

    private fun plan(
        vararg items: PlanItem,
        phase: DeliveryPhase = DeliveryPhase.GRANT,
        order: PlanOrder = order(),
        settings: PlanSettings = PlanSettings(),
        units: Map<Long, IntRange> = emptyMap(),
        coverage: Map<Long, Coverage> = emptyMap(),
        attemptGroup: Int = 0
    ) = DeliveryPlanner.plan(PlanRequest(order, items.toList(), phase, servers, settings, now, attemptGroup, units, coverage))

    private fun json(row: PlannedDelivery) = JsonObject(row.payload)

    private fun commandsOf(row: PlannedDelivery) = json(row).getJsonArray("commands").map { it as String }

    private fun executed(item: Long, action: String, server: Long = 0, phase: DeliveryPhase = DeliveryPhase.GRANT, status: DeliveryStatus = DeliveryStatus.CONFIRMED, code: String? = null) =
        DeliveryRow(orderItemId = item, actionId = action, phase = phase, serverId = server, status = status, lastErrorCode = code)

    // ---- keys (R2-7) ------------------------------------------------------------------------------------------------

    @Test
    fun `key formats of the three sources (R2-7)`() {
        assertEquals("812:a1:4:0:GRANT:0", DeliveryPlanner.key(DeliveryPlanner.sourceKey(DeliverySourceType.ORDER_ITEM, 812, null), "a1", 4, 0, DeliveryPhase.GRANT, 0))
        assertEquals("cb:12:c1:4:0:GRANT:0", DeliveryPlanner.key(DeliveryPlanner.sourceKey(DeliverySourceType.CHARGEBACK_ACTION, null, 12), "c1", 4, 0, DeliveryPhase.GRANT, 0))
        assertEquals("cp:7:p1:0:0:GRANT:0", DeliveryPlanner.key(DeliveryPlanner.sourceKey(DeliverySourceType.CREATOR_PAYOUT, null, 7), "p1", 0, 0, DeliveryPhase.GRANT, 0))
        assertEquals("812:a1:4:3:REVOKE:2", DeliveryPlanner.key("812", "a1", 4, 3, DeliveryPhase.REVOKE, 2))
    }

    @Test
    fun `the planner produces exactly those keys for an order item, a chargeback and a payout`() {
        val orderRow = plan(item(812, command("a1", "say hi", targets = listOf(4L)))).single()

        assertEquals("812:a1:4:0:GRANT:0", orderRow.idempotencyKey)

        val chargeback = DeliveryPlanner.planChargebackActions(
            ChargebackRequest(order(TargetResolver.Parties("Steve", payerUserId = 3, payerUsername = "Steve")), 12, ActionParser.Stored(listOf(command("c1", "ban {username}", targets = listOf(4L))), emptyList()), servers, now = now)
        ).single()

        assertEquals("cb:12:c1:4:0:GRANT:0", chargeback.idempotencyKey)

        val payout = DeliveryPlanner.planPayoutActions(PayoutRequest(7, "Creator", 1250, "TRY", ActionParser.Stored(listOf(credit("p1", 1250)), emptyList()), servers, now = now)).single()

        assertEquals("cp:7:p1:0:0:GRANT:0", payout.idempotencyKey)
    }

    @Test
    fun `source keys need their ids`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { DeliveryPlanner.sourceKey(DeliverySourceType.ORDER_ITEM, null, 3) }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { DeliveryPlanner.sourceKey(DeliverySourceType.CHARGEBACK_ACTION, 3, null) }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { DeliveryPlanner.sourceKey(DeliverySourceType.CREATOR_PAYOUT, 3, null) }
    }

    @Test
    fun `planning twice produces the same keys, so a replayed transition inserts nothing new (case 22)`() {
        val items = arrayOf(item(1, command("a1", "give {username} diamond {quantity}", targets = listOf(1L, 2L)), credit("a2", 500), permission("a3", "group.vip"), quantity = 3))

        val first = plan(*items)
        val second = plan(*items)

        assertEquals(first, second)
        assertEquals(first.size, first.map { it.idempotencyKey }.toSet().size, "keys are unique inside one plan")
        assertEquals(setOf("1:a1:1:0:GRANT:0", "1:a1:2:0:GRANT:0", "1:a2:0:0:GRANT:0", "1:a3:0:0:GRANT:0"), first.map { it.idempotencyKey }.toSet())
    }

    @Test
    fun `a re-run carries its attempt group in every key`() {
        val rows = plan(item(5, command("a1", "say hi"), credit("a2", 100)), attemptGroup = 2)

        assertEquals(listOf("5:a1:1:0:GRANT:2", "5:a2:0:0:GRANT:2"), rows.map { it.idempotencyKey })
        assertTrue(rows.all { it.attemptGroup == 2 })
    }

    // ---- GRANT: rows, units, servers (cases 15 - 24) ----------------------------------------------------------------

    @Test
    fun `one COMMAND, two fixed servers, quantity 3, not per unit gives 2 rows with quantity 3 (case 15)`() {
        val rows = plan(item(812, command("a1", "give {username} diamond {quantity}", targets = listOf(1L, 2L)), quantity = 3))

        assertEquals(2, rows.size)
        assertEquals(listOf(1L, 2L), rows.map { it.serverId })
        assertTrue(rows.all { commandsOf(it) == listOf("give Steve diamond 3") })
        assertTrue(rows.all { it.unitIndex == 0 && it.transport == DeliveryTransport.MARKET_MC && it.status == DeliveryStatus.PENDING })
        assertTrue(rows.all { it.sourceType == DeliverySourceType.ORDER_ITEM && it.orderId == 99L && it.orderItemId == 812L && it.sourceId == null })
        assertTrue(rows.all { it.playerUsername == "Steve" && it.guaranteed && it.attempts == 0 })
    }

    @Test
    fun `the same per unit gives 6 rows, unitIndex 0 to 2 and {unit} 1 to 3 (case 16)`() {
        val rows = plan(item(812, command("a1", "give {username} diamond {unit} of {quantity}", targets = listOf(1L, 2L), perUnit = true), quantity = 3))

        assertEquals(6, rows.size)
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L, 2L), rows.map { it.serverId })
        assertEquals(listOf(0, 1, 2, 0, 1, 2), rows.map { it.unitIndex })
        assertEquals(listOf("give Steve diamond 1 of 1", "give Steve diamond 2 of 1", "give Steve diamond 3 of 1"), rows.take(3).map { commandsOf(it).single() })
        assertEquals(6, rows.map { it.idempotencyKey }.toSet().size)
    }

    @Test
    fun `a delay makes the row SCHEDULED until runAfter, then D1 promotes it (case 17)`() {
        val row = plan(item(812, command("a1", "say hi", delay = 60), credit("a2", 100))).first { it.actionId == "a1" }

        assertEquals(DeliveryStatus.SCHEDULED, row.status)
        assertEquals(now + 60_000, row.runAfter)
        assertEquals(now + 60_000, row.nextAttemptAt)

        val machine = row.toRow()

        assertEquals(DeliveryTransition.NoOp, DeliveryStateMachine.decide(machine, DeliveryEvent.Promote, now + 59_999))
        assertEquals(DeliveryStatus.PENDING, (DeliveryStateMachine.decide(machine, DeliveryEvent.Promote, now + 60_000) as DeliveryTransition.Move).to)

        val immediate = plan(item(812, credit("a2", 100))).single()

        assertEquals(DeliveryStatus.PENDING, immediate.status)
        assertEquals(now, immediate.runAfter)
    }

    @Test
    fun `BUYER_CHOICE without a valid target is one FAILED NO_TARGET_SERVER row, the others still plan (case 18)`() {
        val choice = command("a1", "say hi", mode = ServerMode.BUYER_CHOICE, targets = emptyList())
        val ok = credit("a2", 100)

        for (bad in listOf<Long?>(null, 0L, 9L)) {
            val rows = plan(item(812, choice, ok, choices = listOf(1L, 2L), target = bad))
            val failed = rows.single { it.actionId == "a1" }

            assertEquals(DeliveryStatus.FAILED, failed.status, "$bad")
            assertEquals(DeliveryError.NO_TARGET_SERVER, failed.lastErrorCode)
            assertEquals(0L, failed.serverId)
            assertEquals("812:a1:0:0:GRANT:0", failed.idempotencyKey)
            assertEquals("NO_TARGET_SERVER", json(failed).getString("error"))
            assertNull(failed.nextAttemptAt)
            assertEquals(DeliveryStatus.PENDING, rows.single { it.actionId == "a2" }.status)
        }

        val good = plan(item(812, choice, choices = listOf(1L, 2L), target = 2L)).single()

        assertEquals(2L, good.serverId)
        assertEquals(DeliveryStatus.PENDING, good.status)
    }

    @Test
    fun `FIXED with no targets means every granted server, ALL_CONNECTED with none connected fails (case 19)`() {
        val everyServer = plan(item(1, command("a1", "say hi", targets = emptyList())))

        assertEquals(listOf(1L, 2L, 5L), everyServer.map { it.serverId })

        val connected = plan(item(1, command("a1", "say hi", mode = ServerMode.ALL_CONNECTED)))

        assertEquals(listOf(2L, 5L), connected.map { it.serverId })

        val none = DeliveryPlanner.plan(
            PlanRequest(order(), listOf(item(1, command("a1", "say hi", mode = ServerMode.ALL_CONNECTED))), DeliveryPhase.GRANT, PlanServers(TargetResolver.ServerLookup(grantedIds = listOf(1L))), PlanSettings(), now)
        ).single()

        assertEquals(DeliveryError.NO_TARGET_SERVER, none.lastErrorCode)
        assertEquals(DeliveryStatus.FAILED, none.status)
    }

    @Test
    fun `a render error in one action does not stop the other actions (case 20)`() {
        val rows = plan(
            item(
                812,
                command("a1", "give {username} {field.color} {quantity}"),
                command("a2", "give {username} diamond"),
                credit("a3", 100)
            )
        )

        assertEquals(3, rows.size)

        val failed = rows.single { it.actionId == "a1" }

        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertEquals(DeliveryError.RENDER_ERROR, failed.lastErrorCode)
        assertEquals("field.color: EMPTY_VARIABLE", failed.lastError)
        assertEquals("field.color: EMPTY_VARIABLE", json(failed).getString("error"))
        assertEquals(listOf(DeliveryStatus.PENDING, DeliveryStatus.PENDING), rows.filter { it.actionId != "a1" }.map { it.status })
    }

    @Test
    fun `a bundle plans the parent first, then its children with the parent's fields and the bundle name (case 21)`() {
        val fields = mapOf("nick" to FieldValue(ProductFieldType.USERNAME, "Alex"))

        val parent = item(1, command("a1", "say {bundle.name} for {field.nick} x{quantity}"), quantity = 2, kind = OrderItemKind.BUNDLE, name = "Starter Pack", fields = fields, target = 2L)

        val child = item(
            2, command("b1", "give {field.nick} diamond {quantity} from {bundle.name}", mode = ServerMode.BUYER_CHOICE, targets = emptyList()),
            quantity = 6, kind = OrderItemKind.BUNDLE_CHILD, parent = 1, name = "Diamonds", choices = listOf(1L, 2L)
        )

        for (input in listOf(arrayOf(parent, child), arrayOf(child, parent))) {
            val rows = plan(*input)

            assertEquals(listOf("1:a1:1:0:GRANT:0", "2:b1:2:0:GRANT:0"), rows.map { it.idempotencyKey })
            assertEquals("say Starter Pack for Alex x2", commandsOf(rows[0]).single())
            // child quantity = bundle quantity x per-bundle quantity; field values and server choice come from the parent line
            assertEquals("give Alex diamond 6 from Starter Pack", commandsOf(rows[1]).single())
        }
    }

    @Test
    fun `requiresOnline sets the wait deadline from deliveryOnlineWaitDays, 0 means never (case 23)`() {
        val action = command("a1", "give {username} diamond", online = true, delay = 30)

        val seven = plan(item(1, action), settings = PlanSettings(onlineWaitDays = 7)).single()

        assertTrue(seven.requiresOnline)
        assertEquals(now + 30_000 + 7 * day, seven.waitUntil)

        val never = plan(item(1, action), settings = PlanSettings(onlineWaitDays = 0)).single()

        assertTrue(never.requiresOnline)
        assertNull(never.waitUntil)

        val notRequired = plan(item(1, command("a1", "say hi")), settings = PlanSettings(onlineWaitDays = 7)).single()

        assertFalse(notRequired.requiresOnline)
        assertNull(notRequired.waitUntil)
    }

    @Test
    fun `a CREDIT_TOPUP line produces no delivery row (case 24)`() {
        assertEquals(emptyList<PlannedDelivery>(), plan(item(1, credit("a1", 100), kind = OrderItemKind.CREDIT_TOPUP)))
        assertEquals(1, plan(item(1, credit("a1", 100), kind = OrderItemKind.CREDIT_TOPUP), item(2, credit("a1", 100))).size)
    }

    @Test
    fun `an order fulfilled by its gateway gets no rows in any phase (R2-9)`() {
        val gateway = order(by = FulfillmentBy.GATEWAY)
        val prior = listOf(executed(1, "a1"))

        for (phase in DeliveryPhase.entries) {
            assertEquals(emptyList<PlannedDelivery>(), plan(item(1, command("a1", "say hi"), credit("a2", 100), prior = prior), phase = phase, order = gateway), phase.name)
        }
    }

    @Test
    fun `an item without actions or with units outside its quantity plans nothing`() {
        assertEquals(emptyList<PlannedDelivery>(), plan(item(1)))
        assertEquals(emptyList<PlannedDelivery>(), plan(item(1, command("a1", "say hi"), quantity = 5), units = mapOf(1L to 7..9)))
        assertEquals(emptyList<PlannedDelivery>(), plan(item(1, command("a1", "say hi"), quantity = 0)))
    }

    // ---- payloads (08 section 5.3) -----------------------------------------------------------------------------------

    @Test
    fun `CREDIT payload is the credits times the units and the direction`() {
        val grant = plan(item(1, credit("a1", 1500), quantity = 2, entitlement = null)).single()

        assertEquals(DeliveryActionType.CREDIT, grant.actionType)
        assertEquals(DeliveryTransport.INLINE, grant.transport)
        assertEquals(0L, grant.serverId)
        assertEquals("""{"credits":3000,"reverse":false}""", grant.payload)

        // perUnit is dropped for a credit: one row covering the whole line.
        assertEquals(1, plan(item(1, credit("a1", 100).copy(perUnit = true), quantity = 4)).size)

        val overflow = plan(item(1, credit("a1", Long.MAX_VALUE / 2), quantity = 3)).single()

        assertEquals(DeliveryStatus.FAILED, overflow.status)
        assertEquals(DeliveryError.RENDER_ERROR, overflow.lastErrorCode)
    }

    @Test
    fun `PERMISSION via PANO is one inline row, the server scope goes into the context, expiresAt is the chain end`() {
        val expires = now + 30 * day
        val entitlement = PlanEntitlement(55, expires, subscriptionId = 6)

        val fixed = plan(item(1, permission("a1", "group.vip", "essentials.fly", targets = listOf(1L, 2L)), entitlement = entitlement)).single()

        assertEquals(DeliveryTransport.INLINE, fixed.transport)
        assertEquals(0L, fixed.serverId)
        assertEquals(55L, fixed.entitlementId)
        assertEquals(6L, fixed.subscriptionId)
        assertEquals(
            """{"via":"PANO","op":"ADD","nodes":["group.vip","essentials.fly"],"context":{"server":[1,2]},"expiresAt":$expires}""",
            fixed.payload
        )

        val global = plan(item(1, permission("a1", "group.vip"))).single()

        assertEquals("""{"via":"PANO","op":"ADD","nodes":["group.vip"],"context":{},"expiresAt":null}""", global.payload)

        val everywhere = plan(item(1, permission("a1", "group.vip", mode = ServerMode.ALL_CONNECTED))).single()

        assertEquals(JsonObject(), json(everywhere).getJsonObject("context"))

        val chosen = plan(item(1, permission("a1", "group.vip", mode = ServerMode.BUYER_CHOICE), target = 2L, choices = listOf(1L, 2L))).single()

        assertEquals(JsonArray().add(2), json(chosen).getJsonObject("context").getJsonArray("server"))
    }

    @Test
    fun `a PANO permission scoped to servers that cannot be resolved fails instead of becoming global`() {
        val badChoice = plan(item(1, permission("a1", "group.vip", mode = ServerMode.BUYER_CHOICE), target = null, choices = listOf(1L))).single()

        assertEquals(DeliveryStatus.FAILED, badChoice.status)
        assertEquals(DeliveryError.NO_TARGET_SERVER, badChoice.lastErrorCode)

        val gone = plan(item(1, permission("a1", "group.vip", targets = listOf(98L, 99L)))).single()

        assertEquals(DeliveryError.NO_TARGET_SERVER, gone.lastErrorCode)

        val partly = plan(item(1, permission("a1", "group.vip", targets = listOf(1L, 99L)))).single()

        assertEquals(JsonArray().add(1), json(partly).getJsonObject("context").getJsonArray("server"))
    }

    @Test
    fun `a raw node never counts for a Pano-side check, group nodes carry no marker`() {
        val scope = JsonObject().put("server", JsonArray().add(1).add(2))

        assertEquals("""{"server":[1,2]}""", DeliveryPlanner.nodeContext("group.vip", scope).encode())
        assertEquals("""{"server":[1,2],"pano":false}""", DeliveryPlanner.nodeContext("essentials.fly", scope).encode())
        assertEquals("""{"pano":false}""", DeliveryPlanner.nodeContext("essentials.fly", JsonObject()).encode())
        assertEquals("""{"server":[1,2]}""", scope.encode(), "the payload scope itself is not modified")
    }

    @Test
    fun `PERMISSION via SERVER is one row per target server for the Minecraft component`() {
        val expires = now + day
        val rows = plan(item(1, permission("a1", "group.vip", via = PermissionVia.SERVER, targets = listOf(1L, 2L)), entitlement = PlanEntitlement(5, expires)))

        assertEquals(listOf(1L, 2L), rows.map { it.serverId })
        assertTrue(rows.all { it.transport == DeliveryTransport.MARKET_MC })
        assertEquals("""{"via":"SERVER","op":"ADD","nodes":["group.vip"],"expiresAt":$expires}""", rows[0].payload)
    }

    @Test
    fun `COMMAND rows render the context of their own server and unit`() {
        val rows = plan(item(1, command("a1", "say {server.id} {server.name} {phase} {unit}", targets = listOf(1L, 4L))))

        assertEquals(listOf("say 1 Survival grant 1", "say 4 Lobby grant 1"), rows.map { commandsOf(it).single() })
    }

    // ---- username alphabets ------------------------------------------------------------------------------------------

    @Test
    fun `a buyer's name uses the strict alphabet, an admin's manual order the wider one`() {
        val action = command("a1", "say {username}")
        val dotted = TargetResolver.Parties(".Steve_1")

        val storefront = plan(item(1, action), order = order(dotted)).single()

        assertEquals(DeliveryStatus.FAILED, storefront.status)
        assertEquals("username: INVALID_VALUE", storefront.lastError)

        val manual = plan(item(1, action), order = order(dotted, source = OrderSource.PANEL)).single()

        assertEquals("say .Steve_1", commandsOf(manual).single())

        val flagged = plan(item(1, action), order = order(dotted, admin = true)).single()

        assertEquals(DeliveryStatus.PENDING, flagged.status)

        // The wide alphabet never opens the door for a command injection.
        val hostile = plan(item(1, action), order = order(TargetResolver.Parties("Steve; op Steve"), source = OrderSource.PANEL)).single()

        assertEquals(DeliveryStatus.FAILED, hostile.status)
    }

    @Test
    fun `a bundle child without its parent line in the plan uses its own fields and target, children come ordered by id`() {
        val own = mapOf("nick" to FieldValue(ProductFieldType.USERNAME, "Own"))

        val orphan = item(
            5, command("b1", "give {field.nick} {bundle.name}x"), quantity = 3, kind = OrderItemKind.BUNDLE_CHILD, parent = 1, name = "Diamonds", fields = own
        )

        val rows = plan(orphan)

        // No parent: bundle.name is empty, which a command refuses instead of sending a half-built line.
        assertEquals("bundle.name: EMPTY_VARIABLE", rows.single().lastError)

        val first = item(7, command("c1", "say seven"), kind = OrderItemKind.BUNDLE_CHILD, parent = 1)
        val second = item(6, command("c1", "say six"), kind = OrderItemKind.BUNDLE_CHILD, parent = 1)
        val parent = item(1, command("p1", "say parent"), quantity = 1, kind = OrderItemKind.BUNDLE, name = "Pack")

        assertEquals(listOf("1:p1:1:0:GRANT:0", "6:c1:1:0:GRANT:0", "7:c1:1:0:GRANT:0"), plan(first, second, parent).map { it.idempotencyKey })
    }

    @Test
    fun `an explicit unit range for a bundle child wins over the one derived from its parent`() {
        val parent = item(1, command("k0", "say p", phase = DeliveryPhase.REVOKE), quantity = 2, kind = OrderItemKind.BUNDLE, name = "Pack")
        val child = item(2, command("k1", "say child {quantity}", phase = DeliveryPhase.REVOKE), quantity = 6, kind = OrderItemKind.BUNDLE_CHILD, parent = 1, name = "Diamonds")

        val rows = plan(parent, child, phase = DeliveryPhase.REVOKE, units = mapOf(1L to 0..0, 2L to 4..5))

        assertEquals(listOf("1:k0:1:0:REVOKE:0", "2:k1:1:4:REVOKE:0"), rows.map { it.idempotencyKey })
        assertEquals("say child 2", commandsOf(rows[1]).single())
    }

    @Test
    fun `a per unit WEBHOOK plans one row per unit, a WEBHOOK without its target fails`() {
        val perUnit = webhook(WebhookFormat.JSON).copy(perUnit = true)
        val rows = plan(item(812, perUnit, quantity = 3))

        assertEquals(listOf(0, 1, 2), rows.map { it.unitIndex })
        assertEquals(listOf("812:w1:0:0:GRANT:0", "812:w1:0:1:GRANT:0", "812:w1:0:2:GRANT:0"), rows.map { it.idempotencyKey })

        val missing = plan(item(812, ProductAction("w1", DeliveryActionType.WEBHOOK))).single()

        assertEquals(DeliveryStatus.FAILED, missing.status)
        assertEquals("webhook: INVALID_VALUE", missing.lastError)
    }

    @Test
    fun `an explicit REVOKE command whose fixed servers are all gone is one failed row`() {
        val rows = plan(item(1, command("k1", "say x", phase = DeliveryPhase.REVOKE, targets = listOf(98L, 99L))), phase = DeliveryPhase.REVOKE)

        assertEquals(DeliveryError.NO_TARGET_SERVER, rows.single().lastErrorCode)
        assertEquals("1:k1:0:0:REVOKE:0", rows.single().idempotencyKey)
    }

    @Test
    fun `every planned row of a mixed order has a unique key and a transport matching its action`() {
        val rows = plan(
            item(
                1, command("a1", "say {unit}", targets = listOf(1L, 2L, 5L), perUnit = true), credit("a2", 100), permission("a3", "x.y", via = PermissionVia.SERVER, targets = listOf(1L)),
                permission("a4", "group.vip"), webhook(WebhookFormat.JSON), quantity = 2
            )
        )

        assertEquals(rows.size, rows.map { it.idempotencyKey }.toSet().size)

        for (row in rows) {
            val server = row.actionType == DeliveryActionType.COMMAND || (row.actionType == DeliveryActionType.PERMISSION && row.serverId != 0L)

            assertEquals(if (server) DeliveryTransport.MARKET_MC else DeliveryTransport.INLINE, row.transport, row.idempotencyKey)
            assertTrue(row.idempotencyKey.length <= 191)
            assertTrue(row.payload.isNotEmpty() && JsonObject(row.payload).size() > 0)
        }
    }

    // ---- RENEW (08 section 2.3) ---------------------------------------------------------------------------------------

    @Test
    fun `RENEW with its own actions runs them plus the automatic extend of every GRANT permission`() {
        val expires = now + 60 * day

        val rows = plan(
            item(
                9,
                command("g1", "give {username} diamond"),
                permission("g2", "group.vip"),
                credit("g3", 100),
                command("r1", "say renewed {period.days}", phase = DeliveryPhase.RENEW),
                entitlement = PlanEntitlement(3, expires)
            ),
            phase = DeliveryPhase.RENEW
        )

        assertEquals(listOf("9:r1:1:0:RENEW:0", "9:g2:0:0:RENEW:0"), rows.map { it.idempotencyKey })
        assertEquals("say renewed 60", commandsOf(rows[0]).single())
        assertEquals("""{"via":"PANO","op":"EXTEND","nodes":["group.vip"],"context":{},"expiresAt":$expires}""", rows[1].payload)
    }

    @Test
    fun `RENEW without actions of its own repeats the grant, permissions are extended`() {
        val rows = plan(
            item(9, command("g1", "give {username} diamond"), permission("g2", "group.vip"), credit("g3", 100), permission("g4", "x.y", via = PermissionVia.SERVER, targets = listOf(1L))),
            phase = DeliveryPhase.RENEW
        )

        assertEquals(listOf("9:g1:1:0:RENEW:0", "9:g2:0:0:RENEW:0", "9:g3:0:0:RENEW:0", "9:g4:1:0:RENEW:0"), rows.map { it.idempotencyKey })
        assertTrue(rows.all { it.phase == DeliveryPhase.RENEW })
        assertEquals("EXTEND", json(rows[1]).getString("op"))
        // `ADD` on the Minecraft side is an upsert of the expiry (08 section 5.3).
        assertEquals("ADD", json(rows[3]).getString("op"))
    }

    // ---- EXPIRE ------------------------------------------------------------------------------------------------------

    @Test
    fun `EXPIRE runs its own actions and removes the permissions that were granted, credits are never taken back`() {
        val actions = arrayOf(
            permission("g2", "group.vip"), credit("g3", 100),
            command("e1", "say bye {username}", phase = DeliveryPhase.EXPIRE)
        )

        val granted = listOf(executed(9, "g2"), executed(9, "g3"))

        val rows = plan(item(9, *actions, prior = granted, entitlement = PlanEntitlement(3, now - 1)), phase = DeliveryPhase.EXPIRE)

        assertEquals(listOf("9:e1:1:0:EXPIRE:0", "9:g2:0:0:EXPIRE:0"), rows.map { it.idempotencyKey })
        assertEquals("""{"via":"PANO","op":"REMOVE","nodes":["group.vip"],"context":{},"expiresAt":null}""", rows[1].payload)
        assertTrue(rows.none { it.actionType == DeliveryActionType.CREDIT })

        // Nothing was ever granted: only the explicit action remains (D22 may still cancel it at the gate).
        val untouched = plan(item(9, *actions), phase = DeliveryPhase.EXPIRE)

        assertEquals(listOf("9:e1:1:0:EXPIRE:0"), untouched.map { it.idempotencyKey })
    }

    // ---- inverses refer to what took effect (08 section 11.3) --------------------------------------------------------

    @Test
    fun `an inverse is planned only for an action with a CONFIRMED, SENDING, SENT, QUEUED or UNKNOWN_OUTCOME row`() {
        val actions = arrayOf(permission("p1", "group.vip"), credit("c1", 100))

        fun inverses(vararg prior: DeliveryRow) = plan(item(9, *actions, prior = prior.toList()), phase = DeliveryPhase.REVOKE).map { it.actionId }

        assertEquals(listOf("p1", "c1"), inverses(executed(9, "p1"), executed(9, "c1")))
        assertEquals(listOf("p1"), inverses(executed(9, "p1", status = DeliveryStatus.SENT)))
        // A claimed inline row may still execute: a refund between the claim and the executor must get its undo (review fix).
        assertEquals(listOf("p1", "c1"), inverses(executed(9, "p1", status = DeliveryStatus.SENDING), executed(9, "c1", status = DeliveryStatus.SENDING)))
        assertEquals(listOf("c1"), inverses(executed(9, "c1", status = DeliveryStatus.QUEUED)))
        assertEquals(listOf("p1"), inverses(executed(9, "p1", status = DeliveryStatus.FAILED, code = DeliveryError.UNKNOWN_OUTCOME)))
        assertEquals(listOf("p1", "c1"), inverses(executed(9, "p1", phase = DeliveryPhase.RENEW), executed(9, "c1", phase = DeliveryPhase.RENEW)))

        for (status in listOf(DeliveryStatus.PENDING, DeliveryStatus.SCHEDULED, DeliveryStatus.WAITING_SERVER, DeliveryStatus.WAITING_PLAYER, DeliveryStatus.CANCELLED)) {
            assertEquals(emptyList<String>(), inverses(executed(9, "p1", status = status), executed(9, "c1", status = status)), "$status")
        }

        for (code in listOf(DeliveryError.COMMAND_ERROR, DeliveryError.RENDER_ERROR, DeliveryError.NO_ACCOUNT, null)) {
            assertEquals(emptyList<String>(), inverses(executed(9, "p1", status = DeliveryStatus.FAILED, code = code)), "$code")
        }

        // Rows of another item or another action do not count, nor does an undo row.
        assertEquals(emptyList<String>(), inverses(executed(10, "p1"), executed(9, "zz"), executed(9, "p1", phase = DeliveryPhase.REVOKE)))
    }

    @Test
    fun `a grant that is SENDING gets its credit reversal and permission removal, EXPIRE and coverage included`() {
        val actions = arrayOf(permission("p1", "group.vip"), credit("c1", 100))
        val sending = listOf(executed(9, "p1", status = DeliveryStatus.SENDING), executed(9, "c1", status = DeliveryStatus.SENDING))

        val revoke = plan(item(9, *actions, prior = sending), phase = DeliveryPhase.REVOKE)

        assertEquals(listOf("9:p1:0:0:REVOKE:0", "9:c1:0:0:REVOKE:0"), revoke.map { it.idempotencyKey })
        assertTrue(revoke.all { it.status == DeliveryStatus.PENDING && it.nextAttemptAt == now }, "held by the predecessor gate, not delayed")
        assertEquals("REMOVE", json(revoke[0]).getString("op"))

        // EXPIRE takes the permission back (credits never), the same as for a confirmed grant.
        val expire = plan(item(9, *actions, prior = sending, entitlement = PlanEntitlement(3, now - 1)), phase = DeliveryPhase.EXPIRE)

        assertEquals(listOf("9:p1:0:0:EXPIRE:0"), expire.map { it.idempotencyKey })

        // A grant on a server is never SENDING; a RENEW row that is SENDING counts like a GRANT row.
        val renew = plan(item(9, *actions, prior = listOf(executed(9, "c1", phase = DeliveryPhase.RENEW, status = DeliveryStatus.SENDING))), phase = DeliveryPhase.REVOKE)

        assertEquals(listOf("9:c1:0:0:REVOKE:0"), renew.map { it.idempotencyKey })
    }

    @Test
    fun `an automatic inverse runs without the delay of its grant action`() {
        val slow = permission("p1", "group.vip").copy(delaySeconds = 120)

        val grant = plan(item(9, slow)).single()
        val revoke = plan(item(9, slow, prior = listOf(executed(9, "p1"))), phase = DeliveryPhase.REVOKE).single()

        assertEquals(DeliveryStatus.SCHEDULED, grant.status)
        assertEquals(DeliveryStatus.PENDING, revoke.status)
        assertEquals(now, revoke.runAfter)

        val explicit = plan(item(9, command("k1", "say x", phase = DeliveryPhase.REVOKE, delay = 30)), phase = DeliveryPhase.REVOKE).single()

        assertEquals(DeliveryStatus.SCHEDULED, explicit.status, "an explicit REVOKE action keeps its own delay")
    }

    @Test
    fun `the inverse of a server permission goes to the servers that took the grant, also offline ones`() {
        val rows = plan(
            item(
                9, permission("p1", "group.vip", via = PermissionVia.SERVER, mode = ServerMode.ALL_CONNECTED),
                prior = listOf(
                    executed(9, "p1", server = 4), executed(9, "p1", server = 5, status = DeliveryStatus.SENT),
                    executed(9, "p1", server = 1, status = DeliveryStatus.FAILED, code = DeliveryError.COMMAND_ERROR)
                )
            ),
            phase = DeliveryPhase.EXPIRE
        )

        assertEquals(listOf(4L, 5L), rows.map { it.serverId })
        assertTrue(rows.all { it.transport == DeliveryTransport.MARKET_MC && json(it).getString("op") == "REMOVE" && (json(it).containsKey("expiresAt") && json(it).getValue("expiresAt") == null) })
    }

    // ---- REVOKE: unit ranges -----------------------------------------------------------------------------------------

    private val revokeActions = arrayOf(
        credit("c1", 1000), permission("p1", "group.vip"),
        command("k1", "say revoked {quantity}", phase = DeliveryPhase.REVOKE),
        command("k2", "kick {username} {unit}", phase = DeliveryPhase.REVOKE, perUnit = true)
    )

    private fun revokeItem(revoked: Set<Int> = emptySet()) = item(
        8, *revokeActions, quantity = 5,
        prior = listOf(executed(8, "c1"), executed(8, "p1")), revoked = revoked
    )

    @Test
    fun `a partial refund of 2 of 5 units revokes 2 units, keeps the permission and reverses 2 x the credit`() {
        val rows = plan(revokeItem(), phase = DeliveryPhase.REVOKE, units = mapOf(8L to 0..1))

        assertEquals(
            listOf("8:k1:1:0:REVOKE:0", "8:k2:1:0:REVOKE:0", "8:k2:1:1:REVOKE:0", "8:c1:0:0:REVOKE:0"),
            rows.map { it.idempotencyKey }
        )
        assertEquals("say revoked 2", commandsOf(rows[0]).single())
        assertEquals("kick Steve 1", commandsOf(rows[1]).single())
        assertEquals("kick Steve 2", commandsOf(rows[2]).single())
        assertEquals("""{"credits":2000,"reverse":true}""", rows[3].payload)
        assertTrue(rows.none { it.actionId == "p1" }, "the rank stays until the last unit is revoked")
    }

    @Test
    fun `the second refund of the remaining 3 units removes the permission, its keys differ from the first refund's`() {
        val first = plan(revokeItem(), phase = DeliveryPhase.REVOKE, units = mapOf(8L to 0..1))
        val second = plan(revokeItem(revoked = setOf(0, 1)), phase = DeliveryPhase.REVOKE, units = mapOf(8L to 2..4))

        assertEquals(
            listOf("8:k1:1:2:REVOKE:0", "8:k2:1:2:REVOKE:0", "8:k2:1:3:REVOKE:0", "8:k2:1:4:REVOKE:0", "8:p1:0:2:REVOKE:0", "8:c1:0:2:REVOKE:0"),
            second.map { it.idempotencyKey }
        )
        assertEquals("""{"credits":3000,"reverse":true}""", second.single { it.actionId == "c1" }.payload)
        assertEquals("REMOVE", json(second.single { it.actionId == "p1" }).getString("op"))
        assertEquals("say revoked 3", commandsOf(second.first()).single())

        assertTrue(first.map { it.idempotencyKey }.intersect(second.map { it.idempotencyKey }.toSet()).isEmpty())

        // Replaying the same refund re-creates the same keys (the insert ignores them).
        assertEquals(first.map { it.idempotencyKey }, plan(revokeItem(), phase = DeliveryPhase.REVOKE, units = mapOf(8L to 0..1)).map { it.idempotencyKey })
    }

    @Test
    fun `the permission stays while an earlier unit was never revoked`() {
        val rows = plan(revokeItem(revoked = setOf(0)), phase = DeliveryPhase.REVOKE, units = mapOf(8L to 2..4))

        assertTrue(rows.none { it.actionId == "p1" })
    }

    @Test
    fun `without a range the whole line is revoked in one step`() {
        val rows = plan(revokeItem(), phase = DeliveryPhase.REVOKE)

        assertEquals("""{"credits":5000,"reverse":true}""", rows.single { it.actionId == "c1" }.payload)
        assertEquals(listOf("8:p1:0:0:REVOKE:0"), rows.filter { it.actionId == "p1" }.map { it.idempotencyKey })
        assertEquals(5, rows.count { it.actionId == "k2" })
    }

    @Test
    fun `a bundle line revokes each child over the scaled range`() {
        val parent = item(1, command("k0", "say parent {quantity}", phase = DeliveryPhase.REVOKE), quantity = 2, kind = OrderItemKind.BUNDLE, name = "Pack")
        val child = item(2, command("k1", "say child {quantity}", phase = DeliveryPhase.REVOKE), quantity = 6, kind = OrderItemKind.BUNDLE_CHILD, parent = 1, name = "Diamonds")

        // Refund the second bundle: parent unit 1, child units 3..5 (n = 3 per bundle).
        val second = plan(parent, child, phase = DeliveryPhase.REVOKE, units = mapOf(1L to 1..1))

        assertEquals(listOf("1:k0:1:1:REVOKE:0", "2:k1:1:3:REVOKE:0"), second.map { it.idempotencyKey })
        assertEquals("say parent 1", commandsOf(second[0]).single())
        assertEquals("say child 3", commandsOf(second[1]).single())

        val both = plan(parent, child, phase = DeliveryPhase.REVOKE, units = mapOf(1L to 0..1))

        assertEquals("say child 6", commandsOf(both[1]).single())
        assertEquals("2:k1:1:0:REVOKE:0", both[1].idempotencyKey)
    }

    // ---- coverage (08 section 11.1 step 5) ---------------------------------------------------------------------------

    @Test
    fun `a covering entitlement turns the undo into an EXTEND of the nodes, credits are still reversed`() {
        val chainEnd = now + 10 * day
        val actions = arrayOf(permission("p1", "group.vip"), credit("c1", 500), command("k1", "ban {username}", phase = DeliveryPhase.REVOKE))
        val prior = listOf(executed(9, "p1"), executed(9, "c1"))

        val rows = plan(item(9, *actions, prior = prior), phase = DeliveryPhase.REVOKE, coverage = mapOf(9L to Coverage(77, chainEnd)))

        assertEquals(listOf("9:p1:0:77:RENEW:0", "9:c1:0:0:REVOKE:0"), rows.map { it.idempotencyKey })
        assertEquals("""{"via":"PANO","op":"EXTEND","nodes":["group.vip"],"context":{},"expiresAt":$chainEnd}""", rows[0].payload)
        assertEquals(DeliveryPhase.RENEW, rows[0].phase)
        assertEquals("""{"credits":500,"reverse":true}""", rows[1].payload)

        val expire = plan(item(9, *actions, prior = prior), phase = DeliveryPhase.EXPIRE, coverage = mapOf(9L to Coverage(77, chainEnd)))

        assertEquals(listOf("9:p1:0:77:RENEW:0"), expire.map { it.idempotencyKey })

        val permanent = plan(item(9, *actions, prior = prior), phase = DeliveryPhase.EXPIRE, coverage = mapOf(9L to Coverage(77, null)))

        assertTrue((json(permanent.single()).containsKey("expiresAt") && json(permanent.single()).getValue("expiresAt") == null))
    }

    @Test
    fun `the unit of a coverage extension is the entitlement id modulo 2 to the 31`() {
        val big = (1L shl 31) + 5
        val rows = plan(item(9, permission("p1", "group.vip"), prior = listOf(executed(9, "p1"))), phase = DeliveryPhase.EXPIRE, coverage = mapOf(9L to Coverage(big, null)))

        assertEquals("9:p1:0:5:RENEW:0", rows.single().idempotencyKey)
    }

    @Test
    fun `a coverage extension of a server permission is an upsert on the servers that took the grant`() {
        val rows = plan(
            item(9, permission("p1", "group.vip", via = PermissionVia.SERVER, targets = listOf(1L, 2L)), prior = listOf(executed(9, "p1", server = 2))),
            phase = DeliveryPhase.REVOKE, coverage = mapOf(9L to Coverage(3, now + day))
        )

        assertEquals(listOf(2L), rows.map { it.serverId })
        assertEquals("ADD", json(rows.single()).getString("op"))
        assertEquals(now + day, json(rows.single()).getLong("expiresAt"))
    }

    // ---- stored actions that no longer convert ------------------------------------------------------------------------

    @Test
    fun `a stored action that no longer converts becomes a visible FAILED row, the others still run`() {
        val stored = ActionParser.parseStored("""[{"id":"a1","type":"COMMAND","value":["say hi"],"serverMode":"FIXED","targetServers":[1]},{"id":"a2","type":"CREDIT","value":-5},"junk",{"type":"NOPE"}]""")

        assertEquals(1, stored.actions.size)
        assertEquals(3, stored.dropped.size)

        val rows = DeliveryPlanner.plan(
            PlanRequest(order(), listOf(PlanItem(id = 812, actions = stored)), DeliveryPhase.GRANT, servers, PlanSettings(), now)
        )

        assertEquals(4, rows.size)

        val failed = rows.filter { it.status == DeliveryStatus.FAILED }

        assertEquals(3, failed.size)
        assertTrue(failed.all { it.lastErrorCode == DeliveryError.RENDER_ERROR && it.transport == DeliveryTransport.INLINE })
        assertEquals(setOf("actions.1.value: INVALID_VALUE", "actions.2: INVALID", "actions.3.type: INVALID"), failed.map { it.lastError }.toSet())
        assertEquals(4, rows.map { it.idempotencyKey }.toSet().size, "one key each")
        assertTrue("812:a2:0:0:GRANT:0" in rows.map { it.idempotencyKey })
        assertEquals(DeliveryStatus.PENDING, rows.single { it.actionId == "a1" }.status)

        // A broken stored list as a whole (not an array) is one failed row too.
        val broken = DeliveryPlanner.plan(
            PlanRequest(order(), listOf(PlanItem(id = 1, actions = ActionParser.parseStored("not json"))), DeliveryPhase.GRANT, servers, PlanSettings(), now)
        ).single()

        assertEquals("actions: INVALID", broken.lastError)
    }

    @Test
    fun `broken stored actions are reported when something is granted, not again at the end of the term`() {
        val stored = ActionParser.parseStored("""[{"id":"a1","type":"CREDIT","value":0}]""")
        val line = PlanItem(id = 3, actions = stored)

        for (phase in listOf(DeliveryPhase.GRANT, DeliveryPhase.RENEW)) {
            assertEquals(1, DeliveryPlanner.plan(PlanRequest(order(), listOf(line), phase, servers, PlanSettings(), now)).size, phase.name)
        }

        for (phase in listOf(DeliveryPhase.EXPIRE, DeliveryPhase.REVOKE)) {
            assertEquals(0, DeliveryPlanner.plan(PlanRequest(order(), listOf(line), phase, servers, PlanSettings(), now)).size, phase.name)
        }
    }

    // ---- chargeback and payout actions (08 section 12) ----------------------------------------------------------------

    private fun chargeback(parties: TargetResolver.Parties, vararg actions: ProductAction) =
        DeliveryPlanner.planChargebackActions(ChargebackRequest(order(parties), 12, ActionParser.Stored(actions.toList(), emptyList()), servers, now = now))

    @Test
    fun `a chargeback action targets the payer of an order with an account`() {
        val rows = chargeback(
            TargetResolver.Parties("Gift", payerUsername = "Payer", payerUserId = 5),
            command("c1", "ban {username} {buyer.username}", targets = listOf(4L))
        )

        val row = rows.single()

        assertEquals(DeliverySourceType.CHARGEBACK_ACTION, row.sourceType)
        assertEquals(99L, row.orderId)
        assertNull(row.orderItemId)
        assertEquals(12L, row.sourceId)
        assertEquals("Payer", row.playerUsername)
        assertEquals("ban Payer Payer", commandsOf(row).single())
        assertEquals(DeliveryStatus.PENDING, row.status)
        assertEquals(DeliveryPhase.GRANT, row.phase)
    }

    @Test
    fun `a guest order whose payer and recipient differ never bans the typed payer name (R2-5)`() {
        val rows = chargeback(TargetResolver.Parties("Attacker", payerUsername = "Victim", payerUserId = null), command("c1", "ban {username}", targets = listOf(4L)))

        val row = rows.single()

        assertEquals(DeliveryStatus.CANCELLED, row.status)
        assertEquals(DeliveryError.NEEDS_CONFIRMATION, row.lastErrorCode)
        assertEquals("Attacker", row.playerUsername)
        assertEquals("ban Attacker", commandsOf(row).single(), "the payload is ready for the panel's confirmed re-run")
        assertNull(row.nextAttemptAt)

        // Same person (case-insensitive): runs unattended.
        val same = chargeback(TargetResolver.Parties("steve", payerUsername = "Steve", payerUserId = null), command("c1", "ban {username}", targets = listOf(4L))).single()

        assertEquals(DeliveryStatus.PENDING, same.status)
    }

    @Test
    fun `a chargeback action that cannot render is FAILED, not parked as NEEDS_CONFIRMATION`() {
        val row = chargeback(TargetResolver.Parties("Attacker", payerUsername = "Victim"), command("c1", "ban {username} {product.name}", targets = listOf(4L))).single()

        assertEquals(DeliveryStatus.FAILED, row.status)
        assertEquals(DeliveryError.RENDER_ERROR, row.lastErrorCode)
    }

    @Test
    fun `requires online is ignored for a chargeback action, a ban must not wait for the player (11 section 10)`() {
        val online = command("c1", "ban {username} Chargeback", targets = listOf(4L), online = true)
        val settings = PlanSettings(onlineWaitDays = 7)

        fun planned(parties: TargetResolver.Parties) =
            DeliveryPlanner.planChargebackActions(ChargebackRequest(order(parties), 12, ActionParser.Stored(listOf(online), emptyList()), servers, settings, now)).single()

        val pending = planned(TargetResolver.Parties("Steve", payerUsername = "Steve", payerUserId = 3))

        assertEquals(DeliveryStatus.PENDING, pending.status)
        assertFalse(pending.requiresOnline)
        assertNull(pending.waitUntil)
        assertEquals(now, pending.nextAttemptAt)

        // The row parked for the panel's confirmation carries no online wait either, so its re-run does not inherit one.
        val parked = planned(TargetResolver.Parties("Attacker", payerUsername = "Victim", payerUserId = null))

        assertEquals(DeliveryStatus.CANCELLED, parked.status)
        assertEquals(DeliveryError.NEEDS_CONFIRMATION, parked.lastErrorCode)
        assertFalse(parked.requiresOnline)
        assertNull(parked.waitUntil)

        // The same action on a product still waits for the player (the flag is only ignored for chargeback rows).
        val product = plan(item(1, online), settings = settings).single()

        assertTrue(product.requiresOnline)
        assertEquals(now + 7 * day, product.waitUntil)
    }

    @Test
    fun `a chargeback replay creates the same keys`() {
        val action = command("c1", "ban {username}", targets = listOf(4L, 1L))
        val parties = TargetResolver.Parties("Steve", payerUserId = 3, payerUsername = "Steve")

        assertEquals(chargeback(parties, action), chargeback(parties, action))
        assertEquals(listOf("cb:12:c1:4:0:GRANT:0", "cb:12:c1:1:0:GRANT:0"), chargeback(parties, action).map { it.idempotencyKey })
    }

    private fun payout(creator: String, amount: Long, currency: String, vararg actions: ProductAction) =
        DeliveryPlanner.planPayoutActions(PayoutRequest(7, creator, amount, currency, ActionParser.Stored(actions.toList(), emptyList()), servers, now = now))

    @Test
    fun `a payout action renders the creator and the payout amount and currency`() {
        val rows = payout("Creator", 1250, "TRY", command("p1", "eco give {username} {payout.amount} {payout.currency}", targets = listOf(4L)), credit("p2", 300))

        assertEquals(listOf("cp:7:p1:4:0:GRANT:0", "cp:7:p2:0:0:GRANT:0"), rows.map { it.idempotencyKey })
        assertEquals("eco give Creator 12.50 TRY", commandsOf(rows[0]).single())
        assertTrue(rows.all { it.sourceType == DeliverySourceType.CREATOR_PAYOUT && it.sourceId == 7L && it.orderId == null && it.orderItemId == null })
        assertTrue(rows.all { it.playerUsername == "Creator" && it.phase == DeliveryPhase.GRANT && it.status == DeliveryStatus.PENDING })
        assertEquals("""{"credits":300,"reverse":false}""", rows[1].payload)
    }

    @Test
    fun `payout variables follow the decimal and identifier validators, the creator may use the admin alphabet`() {
        val bad = payout("Creator", 1250, "T RY", command("p1", "pay {payout.currency}", targets = listOf(4L))).single()

        assertEquals(DeliveryStatus.FAILED, bad.status)
        assertEquals("payout.currency: INVALID_VALUE", bad.lastError)

        val fallback = payout("Creator", 1250, "", command("p1", "pay {payout.currency|TRY}", targets = listOf(4L))).single()

        assertEquals("pay TRY", commandsOf(fallback).single())

        val empty = payout("Creator", 1250, "", command("p1", "pay {payout.currency}", targets = listOf(4L))).single()

        assertEquals("payout.currency: EMPTY_VARIABLE", empty.lastError)

        val bedrock = payout(".Bedrock_1", 5, "USD", command("p1", "say {username} {payout.amount}", targets = listOf(4L))).single()

        assertEquals("say .Bedrock_1 0.05", commandsOf(bedrock).single())

        val hostile = payout("Creator; op x", 5, "USD", command("p1", "say {username}", targets = listOf(4L))).single()

        assertEquals(DeliveryStatus.FAILED, hostile.status)
    }

    @Test
    fun `a payout name that is not a player name reaches no command`() {
        val rows = payout("", 5, "USD", command("p1", "say {username}", targets = listOf(4L)))

        assertEquals(DeliveryStatus.FAILED, rows.single().status)
        assertFalse(TargetResolver.player(DeliverySourceType.CREATOR_PAYOUT, TargetResolver.Parties(""), "").valid)
    }

    // ---- webhooks ----------------------------------------------------------------------------------------------------

    private fun webhook(format: WebhookFormat, signing: WebhookSigning = WebhookSigning.NONE) =
        ProductAction("w1", DeliveryActionType.WEBHOOK, webhook = WebhookSpec("https://example.com/hook", format, signing, "ENC:secret"))

    @Test
    fun `a WEBHOOK action carries the target, the event name and a JSON body without an event id`() {
        val settings = PlanSettings(store = StoreInfo("My Store", "https://shop.example.com"))
        val row = plan(item(812, webhook(WebhookFormat.JSON, WebhookSigning.HMAC_SHA256), entitlement = PlanEntitlement(4, now + day)), settings = settings).single()

        assertEquals(DeliveryActionType.WEBHOOK, row.actionType)
        assertEquals(DeliveryTransport.INLINE, row.transport)

        val hook = json(row).getJsonObject("webhook")

        assertEquals("https://example.com/hook", hook.getString("url"))
        assertEquals("JSON", hook.getString("format"))
        assertEquals("HMAC_SHA256", hook.getString("signing"))
        assertEquals("ENC:secret", hook.getString("secret"))
        assertEquals("action.grant", hook.getString("event"))

        val body = JsonObject(hook.getString("body"))

        assertEquals("action.grant", body.getString("event"))
        assertEquals(now, body.getLong("createdAt"))
        assertEquals(1, body.getInteger("apiVersion"))
        assertFalse(body.containsKey("id"))
        assertEquals("My Store", body.getJsonObject("store").getString("name"))

        val data = body.getJsonObject("data")

        assertEquals("812:w1:0:0:GRANT:0", data.getJsonObject("delivery").getString("key"))
        assertEquals("Steve", data.getJsonObject("recipient").getString("username"))
        assertEquals(25.0, data.getJsonObject("order").getDouble("total"))
        assertEquals("Diamonds", data.getJsonObject("item").getString("productName"))
        assertEquals(4, data.getJsonObject("entitlement").getInteger("id"))
    }

    @Test
    fun `the Discord stand-in body is valid JSON for names with quotes and newlines and never pings anyone`() {
        val nasty = "Evil \"name\"\nline \\ two"
        val row = plan(item(812, webhook(WebhookFormat.DISCORD), name = nasty), order = order(TargetResolver.Parties("Steve"))).single()

        val body = JsonObject(json(row).getJsonObject("webhook").getString("body"))

        assertEquals(JsonArray(), body.getJsonObject("allowed_mentions").getJsonArray("parse"))
        assertEquals(nasty, body.getJsonArray("embeds").getJsonObject(0).getJsonArray("fields").getJsonObject(2).getString("value"))
        assertEquals("Steve", body.getJsonArray("embeds").getJsonObject(0).getJsonArray("fields").getJsonObject(0).getString("value"))
    }

    @Test
    fun `a custom body renderer replaces the stand-in and sees the row`() {
        var seen: WebhookBodyInput? = null

        val settings = PlanSettings(webhookBody = { input ->
            seen = input
            """{"custom":true}"""
        })

        val row = plan(item(812, webhook(WebhookFormat.JSON), quantity = 3), phase = DeliveryPhase.GRANT, settings = settings).single()

        assertEquals("""{"custom":true}""", json(row).getJsonObject("webhook").getString("body"))
        assertEquals("action.grant", seen!!.event)
        assertEquals(812L, seen!!.item!!.id)
        assertEquals(3, seen!!.quantity)
        assertEquals("812:w1:0:0:GRANT:0", seen!!.key)
    }

    @Test
    fun `a payout webhook can use the payout variables`() {
        val action = ProductAction("w1", DeliveryActionType.WEBHOOK, webhook = WebhookSpec("https://example.com/hook", WebhookFormat.DISCORD))
        val row = DeliveryPlanner.planPayoutActions(PayoutRequest(7, "Creator", 1250, "TRY", ActionParser.Stored(listOf(action), emptyList()), servers, now = now)).single()

        assertEquals("cp:7:w1:0:0:GRANT:0", row.idempotencyKey)
        assertNotNull(JsonObject(json(row).getJsonObject("webhook").getString("body")))
    }

    // ---- planner, machine and calculator agree -----------------------------------------------------------------------

    @Test
    fun `planned rows start in states the machine can move and the calculator reads`() {
        val rows = plan(item(1, command("a1", "say hi"), credit("a2", 100), permission("a3", "group.vip"), command("a4", "say later", delay = 30)))

        for (row in rows) {
            val machine = row.toRow()

            val event = when {
                row.status == DeliveryStatus.SCHEDULED -> DeliveryEvent.Promote
                row.transport == DeliveryTransport.INLINE -> DeliveryEvent.Claim
                else -> DeliveryEvent.Offer
            }

            val at = row.runAfter
            val result = DeliveryStateMachine.decide(machine, event, at)

            assertTrue(result is DeliveryTransition.Move, "${row.actionId}: $result")
        }

        assertEquals(FulfillmentStatus.PENDING, FulfillmentCalculator.status(rows.map { it.toRow() }))

        val confirmed = rows.map { it.toRow().copy(status = DeliveryStatus.CONFIRMED) }

        assertEquals(FulfillmentStatus.FULFILLED, FulfillmentCalculator.status(confirmed))

        // Revoke it all: the order reads PARTIAL until the undo rows are confirmed, then REVOKED.
        val undo = plan(
            item(1, command("a1", "say hi"), credit("a2", 100), permission("a3", "group.vip"), prior = confirmed.map { executed(1, it.actionId, it.serverId) }),
            phase = DeliveryPhase.REVOKE
        ).map { it.toRow() }

        assertEquals(listOf("a3", "a2"), undo.map { it.actionId }.sortedDescending())
        assertEquals(FulfillmentStatus.PARTIAL, FulfillmentCalculator.status(confirmed + undo, listOf(EntitlementStatus.REVOKED)))
        assertEquals(
            FulfillmentStatus.REVOKED,
            FulfillmentCalculator.status(confirmed + undo.map { it.copy(status = DeliveryStatus.CONFIRMED) }, listOf(EntitlementStatus.REVOKED))
        )
    }
}
