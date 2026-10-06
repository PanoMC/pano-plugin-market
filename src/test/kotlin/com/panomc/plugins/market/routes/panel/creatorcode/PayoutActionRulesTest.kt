package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.plugins.market.core.abuse.ActionGuard
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.ServerMode
import com.panomc.plugins.market.db.model.DeliveryActionType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `actions[]` of `POST /creator-codes/:id/payouts` (MK-114; 08 section 2.2 last paragraph, 11 section 14.4): the action rules with the restrictions of a payout list, then [ActionGuard]. */
class PayoutActionRulesTest {
    private val servers = setOf(7L)

    private fun caller(admin: Boolean = false, console: Set<Long> = emptySet()) = object : ActionGuard.Caller {
        override suspend fun isAdmin() = admin

        override suspend fun canManagePermissionGroups() = false

        override suspend fun canConsole(serverId: Long) = serverId in console

        override suspend fun canConsoleGlobally() = false
    }

    private fun pay(id: String = "p1", server: Long = 7L) =
        ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf("pay {username} {payout.amount}"), targetServers = listOf(server))

    private fun array(vararg actions: ProductAction) = JsonArray(actions.map { it.toJson() })

    private fun check(submitted: Any?, caller: ActionGuard.Caller? = caller(admin = true), serverIds: Set<Long> = servers, hasServers: Boolean = true) =
        runBlocking { PayoutActionRules.check(submitted, caller, serverIds, hasServers) }

    private fun invalid(verdict: PayoutActionRules.Verdict): String = (verdict as PayoutActionRules.Verdict.Invalid).reason

    @Test
    fun `a pay command on a granted server is allowed for an admin and the canonical text parses back to the same action`() {
        val ok = check(array(pay())) as PayoutActionRules.Verdict.Ok
        val back = ActionParser.parseStored(ok.canonical, ActionParser.Kind.PAYOUT)

        assertEquals(listOf(pay()), back.actions)
        assertTrue(back.dropped.isEmpty())
    }

    @Test
    fun `an id that is left out is generated with the payout prefix`() {
        val bare = JsonObject().put("type", "COMMAND").put("value", JsonArray().add("pay {username}")).put("serverMode", "FIXED").put("targetServers", JsonArray().add(7))
        val ok = check(JsonArray().add(bare)) as PayoutActionRules.Verdict.Ok

        assertEquals("p1", ActionParser.parseStored(ok.canonical, ActionParser.Kind.PAYOUT).actions.single().id)
    }

    @Test
    fun `the list must be a non-empty array of at most ten actions`() {
        assertEquals("REQUIRED", invalid(check(null)))
        assertEquals("REQUIRED", invalid(check(JsonArray())))
        assertEquals("INVALID", invalid(check("[]")))
        assertEquals("INVALID", invalid(check(JsonObject())))
        assertEquals("actions.0:INVALID", invalid(check(JsonArray().add("text"))))
        assertEquals("TOO_MANY", invalid(check(JsonArray((1..11).map { pay("p$it").toJson() }))))
        assertTrue(check(JsonArray((1..10).map { pay("p$it").toJson() })) is PayoutActionRules.Verdict.Ok)
    }

    @Test
    fun `a payout list refuses a phase other than GRANT, perUnit, BUYER_CHOICE and every type but COMMAND and WEBHOOK`() {
        assertEquals("actions.0.phase:INVALID", invalid(check(JsonArray().add(pay().toJson().put("phase", "REVOKE")))))
        assertEquals("actions.0.perUnit:INVALID", invalid(check(JsonArray().add(pay().toJson().put("perUnit", true)))))
        assertTrue(invalid(check(JsonArray().add(pay().toJson().put("serverMode", ServerMode.BUYER_CHOICE.name)))).isNotEmpty())

        val credit = ProductAction(id = "p1", type = DeliveryActionType.CREDIT, credit = 500)
        val permission = ProductAction(id = "p1", type = DeliveryActionType.PERMISSION, nodes = listOf("group.vip"))

        assertEquals("actions.0.type:INVALID", invalid(check(array(credit))))
        assertEquals("actions.0.type:INVALID", invalid(check(array(permission))))
    }

    @Test
    fun `a command to a server that is not granted is refused by the parser`() {
        assertTrue(invalid(check(array(pay(server = 9L)))).isNotEmpty())
        assertTrue(invalid(check(array(pay()), hasServers = false, serverIds = emptySet())).isNotEmpty())
    }

    @Test
    fun `a webhook must be unsigned and carry no secret`() {
        fun webhook(signing: String, secret: String? = null) = JsonObject().put("id", "p1").put("type", "WEBHOOK").put("phase", "GRANT")
            .put("value", JsonObject().put("url", "https://hooks.example.com/pay").put("format", "JSON").put("signing", signing).also { if (secret != null) it.put("secret", secret) })

        assertTrue(check(JsonArray().add(webhook("NONE")), caller = ActionGuard.NOBODY) is PayoutActionRules.Verdict.Ok, "a webhook needs no privilege")
        assertEquals("actions.0.value.signing:INVALID", invalid(check(JsonArray().add(webhook("HMAC_SHA256")))))
        assertTrue(invalid(check(JsonArray().add(webhook("NONE", "s3cret")))).isNotEmpty())
    }

    @Test
    fun `every action of a new payout is a changed one, a command needs the console permission of its server`() {
        val list = array(pay())

        assertEquals(PayoutActionRules.Verdict.Forbidden, check(list, caller = ActionGuard.NOBODY))
        assertEquals(PayoutActionRules.Verdict.Forbidden, check(list, caller = null))
        assertEquals(PayoutActionRules.Verdict.Forbidden, check(list, caller = caller(console = setOf(8L))))
        assertTrue(check(list, caller = caller(console = setOf(7L))) is PayoutActionRules.Verdict.Ok)
        assertTrue(check(list, caller = caller(admin = true)) is PayoutActionRules.Verdict.Ok)
    }
}
