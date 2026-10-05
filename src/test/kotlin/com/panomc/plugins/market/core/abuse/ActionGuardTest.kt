package com.panomc.plugins.market.core.abuse

import com.panomc.plugins.market.core.delivery.PermissionVia
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.ServerMode
import com.panomc.plugins.market.core.delivery.WebhookSpec
import com.panomc.plugins.market.db.model.DeliveryActionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `ActionGuard` (11 section 14.4, 08 section 2.2 `PERMISSION_ADMIN_REQUIRED`): the privilege rule of a product save, over a stubbed caller. */
class ActionGuardTest {
    private class Caller(
        val admin: Boolean = false,
        val groups: Boolean = false,
        val consoles: Set<Long> = emptySet(),
        val global: Boolean = false
    ) : ActionGuard.Caller {
        override suspend fun isAdmin() = admin

        override suspend fun canManagePermissionGroups() = groups

        override suspend fun canConsole(serverId: Long) = global || serverId in consoles

        override suspend fun canConsoleGlobally() = global
    }

    private fun command(id: String = "a1", vararg targets: Long, mode: ServerMode = ServerMode.FIXED, line: String = "give {username} diamond") =
        ProductAction(id = id, type = DeliveryActionType.COMMAND, serverMode = mode, targetServers = targets.toList(), commands = listOf(line))

    private fun permission(id: String = "a1", vararg nodes: String, via: PermissionVia = PermissionVia.PANO) =
        ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = nodes.toList(), via = via)

    private fun credit(id: String = "a1") = ProductAction(id = id, type = DeliveryActionType.CREDIT, credit = 500)

    private fun webhook(id: String = "a1") = ProductAction(id = id, type = DeliveryActionType.WEBHOOK, webhook = WebhookSpec("https://example.com/hook"))

    private fun check(
        stored: List<ProductAction>,
        submitted: List<ProductAction>,
        caller: ActionGuard.Caller?,
        choices: List<Long> = emptyList(),
        storedChoices: List<Long> = emptyList()
    ) = runBlocking { ActionGuard.violations(stored, submitted, caller, choices, storedChoices) }

    @Test
    fun `an unchanged command and an unchanged permission pass for a caller with no rights`() {
        val stored = listOf(command("a1", 1, 2), permission("a2", "group.vip"))

        assertTrue(check(stored, stored, Caller()).isEmpty())
        assertTrue(check(stored, stored.reversed(), Caller()).isEmpty())
    }

    @Test
    fun `a new or changed command needs the console of every fixed target server`() {
        val stored = listOf(command("a1", 1))

        // a new action
        assertEquals(listOf(2L), check(emptyList(), listOf(command("a1", 1, 2)), Caller(consoles = setOf(1))).map { it.serverId })
        assertTrue(check(emptyList(), listOf(command("a1", 1, 2)), Caller(consoles = setOf(1, 2))).isEmpty())

        // the same id with another command line is a changed action
        val changed = listOf(command("a1", 1, line = "op {username}"))

        assertEquals(ActionGuard.CONSOLE_REQUIRED, check(stored, changed, Caller()).single().code)
        assertTrue(check(stored, changed, Caller(consoles = setOf(1))).isEmpty())

        // an extra target server is a change too
        assertEquals(1, check(stored, listOf(command("a1", 1, 2)), Caller(consoles = setOf(1))).size)
    }

    @Test
    fun `a command on every connected server, or on no named server, needs the global console grant`() {
        val everywhere = command("a1", mode = ServerMode.ALL_CONNECTED)
        val noTargets = command("a2")

        // scoped grants for servers 1 and 2 are not the global grant
        assertEquals(listOf("a1", "a2"), check(emptyList(), listOf(everywhere, noTargets), Caller(consoles = setOf(1, 2))).map { it.actionId })
        assertTrue(check(emptyList(), listOf(everywhere, noTargets), Caller(global = true)).isEmpty())
    }

    @Test
    fun `a buyer choice command needs the console of every server the product offers`() {
        val action = command("a1", mode = ServerMode.BUYER_CHOICE)

        assertEquals(listOf(3L), check(emptyList(), listOf(action), Caller(consoles = setOf(1, 2)), choices = listOf(1, 2, 3)).map { it.serverId })
        assertTrue(check(emptyList(), listOf(action), Caller(consoles = setOf(1, 2, 3)), choices = listOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun `an unchanged buyer choice command still needs the console of a server the choice list gained in this save`() {
        val action = command("a1", mode = ServerMode.BUYER_CHOICE)

        // unchanged action, unchanged choices: passes
        assertTrue(check(listOf(action), listOf(action), Caller(), choices = listOf(1, 2), storedChoices = listOf(1, 2)).isEmpty())
        // server 3 was added to the choices: the command now runs there
        assertEquals(listOf(3L), check(listOf(action), listOf(action), Caller(consoles = setOf(1, 2)), choices = listOf(1, 2, 3), storedChoices = listOf(1, 2)).map { it.serverId })
        // removing a choice needs nothing
        assertTrue(check(listOf(action), listOf(action), Caller(), choices = listOf(1), storedChoices = listOf(1, 2)).isEmpty())
    }

    @Test
    fun `a changed permission action needs the right to manage permission groups`() {
        val stored = listOf(permission("a1", "group.vip"))

        assertEquals(ActionGuard.PERMISSION_ADMIN_REQUIRED, check(emptyList(), listOf(permission("a1", "group.vip")), Caller()).single().code)
        assertTrue(check(emptyList(), listOf(permission("a1", "group.vip")), Caller(groups = true)).isEmpty())
        // a new node on an existing id is a change
        assertEquals(1, check(stored, listOf(permission("a1", "group.vip", "group.mod")), Caller()).size)
        // a console right does not stand in for it
        assertEquals(1, check(emptyList(), listOf(permission("a1", "group.vip")), Caller(global = true)).size)
        // also for a permission written by the game server
        assertEquals(1, check(emptyList(), listOf(permission("a1", "essentials.fly", via = PermissionVia.SERVER)), Caller()).size)
    }

    @Test
    fun `a node of the Pano side needs the star itself`() {
        for (node in listOf("*", "**", "pano.manage.servers", "pano.*", "pano", "*.manage.servers", "PANO.manage", " pano.x ")) {
            val action = permission("a1", "group.vip", node)

            assertEquals(1, check(emptyList(), listOf(action), Caller(groups = true)).size, node)
            assertTrue(check(emptyList(), listOf(action), Caller(admin = true)).isEmpty(), node)
        }

        for (node in listOf("group.admin", "essentials.fly", "panorama.view", "vip.*")) {
            assertTrue(check(emptyList(), listOf(permission("a1", node)), Caller(groups = true)).isEmpty(), node)
        }
    }

    @Test
    fun `a caller with the star passes every change`() {
        val everything = listOf(command("a1", mode = ServerMode.ALL_CONNECTED), command("a2", 5), permission("a3", "*", "pano.x"), credit("a4"), webhook("a5"))

        assertTrue(check(emptyList(), everything, Caller(admin = true)).isEmpty())
    }

    @Test
    fun `credit and webhook actions need nothing extra`() {
        assertTrue(check(emptyList(), listOf(credit("a1"), webhook("a2")), Caller()).isEmpty())
    }

    @Test
    fun `removing an action needs no right and the clone of a product counts as new`() {
        val stored = listOf(command("a1", 1), permission("a2", "group.vip"))

        assertTrue(check(stored, emptyList(), Caller()).isEmpty())
        assertTrue(check(stored, listOf(stored[1]), Caller()).isEmpty())

        // a clone starts from nothing: every action is new
        assertEquals(listOf("a1", "a2"), check(emptyList(), stored, Caller()).map { it.actionId })
    }

    @Test
    fun `no caller is nobody`() {
        assertEquals(1, check(emptyList(), listOf(command("a1", 1)), null).size)
        assertEquals(1, check(emptyList(), listOf(permission("a1", "group.vip")), null).size)
        assertTrue(check(emptyList(), listOf(credit()), null).isEmpty())

    }
}
