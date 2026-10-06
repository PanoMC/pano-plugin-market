package com.panomc.plugins.market.routes.panel.dispute

import com.panomc.plugins.market.core.abuse.ActionGuard
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.ServerMode
import com.panomc.plugins.market.db.model.DeliveryActionType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `chargebackActions` on `POST /settings` (MK-112; 11 section 10): the action rules of 08 section 2.2 with the restrictions of a chargeback list, then [ActionGuard]. */
class ChargebackActionRulesTest {
    private val servers = setOf(7L)

    private fun caller(admin: Boolean = false, console: Set<Long> = emptySet(), global: Boolean = false) = object : ActionGuard.Caller {
        override suspend fun isAdmin() = admin

        override suspend fun canManagePermissionGroups() = false

        override suspend fun canConsole(serverId: Long) = serverId in console || global

        override suspend fun canConsoleGlobally() = global
    }

    private fun ban(id: String = "c1", server: Long = 7L) =
        ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf("ban {username} Chargeback"), targetServers = listOf(server))

    private fun json(vararg actions: ProductAction) = JsonArray(actions.map { it.toJson() }).encode()

    private fun check(text: String, stored: String? = "[]", caller: ActionGuard.Caller? = caller(admin = true), serverIds: Set<Long> = servers, hasServers: Boolean = true) =
        runBlocking { ChargebackActionRules.check(text, stored, caller, serverIds, hasServers) }

    private fun invalid(verdict: ChargebackActionRules.Verdict): String = (verdict as ChargebackActionRules.Verdict.Invalid).reason

    @Test
    fun `an empty list is always allowed, also for a caller with no rights`() {
        assertEquals(ChargebackActionRules.Verdict.Ok, check("[]", caller = ActionGuard.NOBODY))
        assertEquals(ChargebackActionRules.Verdict.Ok, check("  ", caller = null))
    }

    @Test
    fun `a ban command on a granted server is allowed for an admin and a webhook needs no privilege`() {
        assertEquals(ChargebackActionRules.Verdict.Ok, check(json(ban())))

        val webhook = JsonObject().put("id", "c2").put("type", "WEBHOOK").put("phase", "GRANT").put("value", JsonObject().put("url", "https://hooks.example.com/cb").put("format", "JSON").put("signing", "NONE"))

        assertEquals(ChargebackActionRules.Verdict.Ok, check(JsonArray().add(webhook).encode(), caller = ActionGuard.NOBODY))
    }

    @Test
    fun `a changed command needs the console permission of its server, an unchanged one needs nothing`() {
        val text = json(ban())

        assertEquals(ChargebackActionRules.Verdict.Forbidden, check(text, caller = ActionGuard.NOBODY))
        assertEquals(ChargebackActionRules.Verdict.Forbidden, check(text, caller = caller(console = setOf(8L))))
        assertEquals(ChargebackActionRules.Verdict.Ok, check(text, caller = caller(console = setOf(7L))))
        // the same action as stored: untouched, so saving the page does not need the privilege again
        assertEquals(ChargebackActionRules.Verdict.Ok, check(text, stored = text, caller = ActionGuard.NOBODY))
        // another command under the same id is a change
        assertEquals(
            ChargebackActionRules.Verdict.Forbidden, check(json(ban().copy(commands = listOf("kick {username}"))), stored = text, caller = ActionGuard.NOBODY)
        )
    }

    @Test
    fun `the list rules of a chargeback list are refused with their reason`() {
        assertEquals("INVALID", invalid(check("not json")))
        assertEquals("TOO_MANY", invalid(check(JsonArray((1..11).map { ban("c$it").toJson() }).encode())))
        assertTrue(invalid(check(json(ProductAction(id = "c1", type = DeliveryActionType.CREDIT, credit = 100)))).endsWith(":INVALID"))
        assertTrue(invalid(check(json(ban().copy(serverMode = ServerMode.BUYER_CHOICE)))).isNotEmpty())
        assertEquals("actions.0.perUnit:INVALID", invalid(check(json(ban().copy(perUnit = true)))))
        assertEquals("actions.0.phase:INVALID", invalid(check(JsonArray().add(ban().toJson().put("phase", "REVOKE")).encode())))
        assertTrue(invalid(check(json(ban(server = 9L)))).isNotEmpty(), "a server that is not granted")
        assertTrue(invalid(check(json(ban()), serverIds = emptySet(), hasServers = false)).isNotEmpty(), "no server at all")
    }

    @Test
    fun `an action without an id is accepted, the parser gives it a c-number`() {
        assertEquals(ChargebackActionRules.Verdict.Ok, check(JsonArray().add(ban().toJson().apply { remove("id") }).encode()))
        assertEquals(ChargebackActionRules.Verdict.Ok, check(json(ban("c3"))))
    }
}
