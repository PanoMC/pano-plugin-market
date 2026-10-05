package com.panomc.plugins.market.mc.fabric

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.util.Tristate
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** An answer from a permission mod for one player and one node: `true` / `false` when it knows the node, `null` when it does not. */
fun interface NodeLookup {
    fun lookup(uuid: UUID, node: String): Boolean?
}

/**
 * Who may use the admin commands (`panomarket.admin.*`, 19 section 7.4). The permission mod decides when it has an opinion
 * on the node (a `false` is a deny, also for an operator), otherwise the vanilla default applies: an administrator
 * (operator, permission level 4). Without any permission mod that is "operators only", never "everybody".
 */
class FabricPermissions(private val lookup: NodeLookup?, private val isAdmin: (ServerPlayer) -> Boolean = ::vanillaAdmin) {
    fun check(player: ServerPlayer, uuid: UUID, node: String): Boolean = decide(uuid, node) { isAdmin(player) }

    /** The pure rule, visible for tests. */
    fun decide(uuid: UUID, node: String, admin: () -> Boolean): Boolean {
        val answer = try {
            lookup?.lookup(uuid, node)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            null
        }
        return answer ?: admin()
    }

    companion object {
        /**
         * Operator level 4. Minecraft 26 names are not obfuscated, so a release that moves the permission API again fails with
         * a `LinkageError`: that is "no", never a crash.
         */
        fun vanillaAdmin(player: ServerPlayer): Boolean = try {
            Commands.LEVEL_ADMINS.check(player.permissions())
        } catch (_: LinkageError) {
            false
        }
    }
}

/** LuckPerms (its Fabric mod provides the same API). Only ever instantiated after `FabricLoader.isModLoaded("luckperms")`. */
class LuckPermsNodes : NodeLookup {
    override fun lookup(uuid: UUID, node: String): Boolean? {
        val user = LuckPermsProvider.get().userManager.getUser(uuid) ?: return null
        val state = user.cachedData.permissionData.checkPermission(node)
        return if (state == Tristate.UNDEFINED) null else state.asBoolean()
    }
}
