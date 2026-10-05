package com.panomc.plugins.market.core.abuse

import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.ServerMode
import com.panomc.plugins.market.db.model.DeliveryActionType

/**
 * The privilege boundary inside the catalogue node (11 section 14.4, 08 section 2.2 row `PERMISSION_ADMIN_REQUIRED`).
 *
 * A product action runs console commands or writes Pano permission nodes, so saving one is more than catalogue editing.
 * An action is *changed* when no stored action has the same `id` and an equal definition (the actions are compared as the parsed
 * [ProductAction], which is the canonical form: the same data always parses to the same value). For every changed action:
 *
 * | type | extra requirement (a caller with `*` always passes) |
 * |---|---|
 * | `COMMAND` | `ManageServerConsolePermission` for **every** target server: `targetServers` of a `FIXED` action (an empty list means every granted server, so it needs the global grant), the product's `serverChoices` for `BUYER_CHOICE`, the global (unscoped) grant for `ALL_CONNECTED` |
 * | `PERMISSION` | `ManagePermissionGroupsPermission`; a node equal to `*` or starting with `pano.` (or with a `*` segment first, which matches it) needs `*` |
 * | `WEBHOOK`, `CREDIT` | none |
 *
 * Unchanged actions pass, and so does removing an action. Hardening beyond the table (recorded in the MK-104 evidence): an
 * *unchanged* `BUYER_CHOICE` command still counts as changed for the servers that `serverChoices` gained in this save, because
 * widening the choice list widens where the command runs.
 *
 * The decision is pure: the caller's rights come in through [Caller], so the rule is tested without the host. A `null` caller
 * is "nobody", which fails closed for every changed `COMMAND` / `PERMISSION` action. The refusal itself (403 `NO_PERMISSION`) is thrown by the caller of
 * [violations] (`ProductActionRules`): this package knows nothing of the platform.
 */
object ActionGuard {
    /** Code of [Violation] for a `PERMISSION` action the caller may not save (`fieldErrors["actions.<i>.value"]`, the panel's hint). */
    const val PERMISSION_ADMIN_REQUIRED = "PERMISSION_ADMIN_REQUIRED"

    /** Code of [Violation] for a `COMMAND` action aimed at a server whose console the caller may not use. */
    const val CONSOLE_REQUIRED = "CONSOLE_REQUIRED"

    /** What the caller may do; the production implementation reads the panel session (`RoutingCaller` in the product routes). */
    interface Caller {
        /** Holds `*` (the platform's `isAdmin` flag). */
        suspend fun isAdmin(): Boolean

        /** `ManagePermissionGroupsPermission`, globally. */
        suspend fun canManagePermissionGroups(): Boolean

        /** `ManageServerConsolePermission` for [serverId]: held globally or scoped to that server. */
        suspend fun canConsole(serverId: Long): Boolean

        /** `ManageServerConsolePermission` held globally (not scoped to one server). */
        suspend fun canConsoleGlobally(): Boolean
    }

    /** A caller with no rights at all. */
    val NOBODY: Caller = object : Caller {
        override suspend fun isAdmin() = false

        override suspend fun canManagePermissionGroups() = false

        override suspend fun canConsole(serverId: Long) = false

        override suspend fun canConsoleGlobally() = false
    }

    /** One refused action. [serverId] is the server whose console is missing for [CONSOLE_REQUIRED] (`null` = the global grant). */
    class Violation(val actionId: String, val code: String, val serverId: Long? = null)

    /** Every action of [submitted] that [caller] may not save, in submitted order (empty = allowed). */
    suspend fun violations(
        stored: List<ProductAction>,
        submitted: List<ProductAction>,
        caller: Caller?,
        serverChoices: List<Long> = emptyList(),
        storedServerChoices: List<Long> = emptyList()
    ): List<Violation> {
        val who = caller ?: NOBODY
        val byId = stored.associateBy { it.id }
        val out = ArrayList<Violation>()
        var admin: Boolean? = null

        suspend fun isAdmin(): Boolean = admin ?: who.isAdmin().also { admin = it }

        for (action in submitted) {
            val before = byId[action.id]
            val changed = before != action

            when (action.type) {
                DeliveryActionType.COMMAND -> {
                    val servers = consoleScope(action, serverChoices)
                    val needed = when {
                        changed -> servers
                        // an unchanged BUYER_CHOICE command only needs the servers that the choice list gained in this save
                        action.serverMode == ServerMode.BUYER_CHOICE -> (servers as? Scope.Servers)?.let { Scope.Servers(it.ids - storedServerChoices.toSet()) }
                        else -> null
                    }

                    if (needed != null && !isAdmin()) {
                        when (needed) {
                            Scope.Global -> if (!who.canConsoleGlobally()) out += Violation(action.id, CONSOLE_REQUIRED)
                            is Scope.Servers -> needed.ids.firstOrNull { !who.canConsole(it) }?.let { out += Violation(action.id, CONSOLE_REQUIRED, it) }
                        }
                    }
                }

                DeliveryActionType.PERMISSION -> if (changed && !isAdmin()) {
                    val star = action.nodes.any { isPanoNode(it) }

                    // a node on the Pano side needs `*` itself; every other node needs the right to manage permission groups
                    if (star || !who.canManagePermissionGroups()) out += Violation(action.id, PERMISSION_ADMIN_REQUIRED)
                }

                DeliveryActionType.WEBHOOK, DeliveryActionType.CREDIT -> Unit
            }
        }

        return out
    }

    /**
     * `true` for a node that reaches Pano's own permission checks: `*`, `pano.` and everything below it, and any pattern whose first
     * segment is a wildcard (`**`, `*.manage`), because the platform matcher lets such a pattern cover the `pano.` nodes.
     */
    fun isPanoNode(node: String): Boolean {
        val n = node.trim().lowercase()

        return n == "*" || n.startsWith("*") || n == "pano" || n.startsWith("pano.")
    }

    private sealed class Scope {
        data object Global : Scope()

        class Servers(val ids: Set<Long>) : Scope()
    }

    private fun consoleScope(action: ProductAction, serverChoices: List<Long>): Scope = when (action.serverMode) {
        ServerMode.ALL_CONNECTED -> Scope.Global
        // an empty list is "every server with permissionGranted" (08 section 4.2): that is the global grant
        ServerMode.FIXED -> if (action.targetServers.isEmpty()) Scope.Global else Scope.Servers(action.targetServers.toSet())
        ServerMode.BUYER_CHOICE -> Scope.Servers(serverChoices.toSet())
    }
}
