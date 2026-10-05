package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Applies a `PERMISSION` delivery (19 section 6.3). Kept behind this interface so an adapter holds no LuckPerms type:
 * the class below is only loaded once LuckPerms is known to be installed.
 */
interface PermissionApplier {
    /**
     * [presentUuid] is the uuid the platform itself knows (the connected, authenticated player); [uuidHint] is only
     * Pano's `user.mcUuid`, which differs between proxy, online and offline backends (19 section 3) and is used last.
     */
    fun apply(username: String, presentUuid: String?, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome
}

/**
 * LuckPerms API 5.x, from the engine thread (never the main thread; every LuckPerms future is awaited with a bound).
 *
 * - user: the connected player's own uuid when there is one, else `lookupUniqueId(name)` (offline users are loaded by
 *   name), else Pano's uuid hint when it parses, else the platform's never-joined id (the same one Pano's own
 *   permission sync stores for a player that was never seen), then `loadUser(uuid, name)`;
 * - `ADD`: upsert per node in the global context. No node: add. A permanent node already there: nothing to do.
 *   A temporary one: replaced when the new expiry is later (permanent counts as the latest), kept otherwise.
 *   A negated node (value false) of the same key is removed first, LuckPerms refuses an add over it. A refused add
 *   (or remove, a missing node excepted) is a failed outcome, never DONE;
 * - `REMOVE`: every global-context node with that key goes (a missing node is fine: the undo is idempotent);
 * - `group.<name>` is an inheritance node, `Node.builder(key)` picks the type from the key;
 * - saved with `saveUser`, only when something changed. Any exception is a failed outcome with its message.
 */
class LuckPermsExecutor(
    private val api: () -> LuckPerms = { LuckPermsProvider.get() },
    private val offlineUuid: (String) -> UUID,
    private val clock: McClock = SystemMcClock,
    private val timeoutMs: Long = 15_000,
    private val nodeFactory: (String, Instant?) -> Node = ::buildNode
) : PermissionApplier {

    override fun apply(username: String, presentUuid: String?, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome {
        return try {
            if (op != "ADD" && op != "REMOVE") return PermissionOutcome(false, "unknown permission operation $op")
            val keys = nodes.map { it.trim() }
            if (keys.isEmpty() || keys.any { it.isEmpty() }) return PermissionOutcome(false, "no permission node to $op")
            if (op == "ADD" && expiresAt != null && expiresAt <= clock.now()) {
                return PermissionOutcome(false, "the permission expiry has already passed")
            }
            val lp = api()
            val manager = lp.userManager
            val uuid = resolveUuid(lp, username, presentUuid, uuidHint)
            val user = await(manager.loadUser(uuid, username), "loading the LuckPerms user")
                ?: throw IllegalStateException("LuckPerms returned no user for $username")
            var changed = false
            for (key in keys) {
                changed = (if (op == "ADD") upsert(user, key, expiresAt) else remove(user, key)) || changed
            }
            if (changed) await(manager.saveUser(user), "saving the LuckPerms user")
            PermissionOutcome(true)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            PermissionOutcome(false, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun resolveUuid(lp: LuckPerms, username: String, presentUuid: String?, hint: String?): UUID {
        parse(presentUuid)?.let { return it }
        val looked = await(lp.userManager.lookupUniqueId(username), "looking up the LuckPerms user")
        return looked ?: parse(hint) ?: offlineUuid(username)
    }

    private fun parse(value: String?): UUID? = try {
        value?.let { UUID.fromString(it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun globalNodes(user: User, key: String): List<Node> =
        user.data().toCollection().filter { it.key == key && it.contexts.isEmpty }

    private fun upsert(user: User, key: String, expiresAt: Long?): Boolean {
        val all = globalNodes(user, key)
        val existing = all.filter { it.value }
        val newExpiry = expiresAt?.let { Instant.ofEpochMilli(it) }
        if (existing.any { !it.hasExpiry() }) return false
        if (newExpiry != null) {
            val latest = existing.mapNotNull { it.expiry }.maxOrNull()
            if (latest != null && !latest.isBefore(newExpiry)) return false
        }
        // Every global node of this key goes first, the negated ones too: LuckPerms refuses an add over any node with
        // the same key and context (FAIL_ALREADY_HAS), whatever its value or expiry.
        val removed = ArrayList<Node>()
        for (n in all) {
            val r = user.data().remove(n)
            if (r.wasSuccessful()) removed.add(n) else if (r != DataMutateResult.FAIL_LACKS) throw IllegalStateException("LuckPerms refused to remove $key: $r")
        }
        val r = user.data().add(nodeFactory(key, newExpiry))
        if (!r.wasSuccessful()) {
            removed.forEach { user.data().add(it) } // best effort: leave the in-memory user as it was
            throw IllegalStateException("LuckPerms refused to add $key: $r")
        }
        return true
    }

    private fun remove(user: User, key: String): Boolean {
        val existing = globalNodes(user, key).filter { it.value }
        var changed = false
        for (n in existing) {
            val r = user.data().remove(n)
            if (r.wasSuccessful()) changed = true
            else if (r != DataMutateResult.FAIL_LACKS) throw IllegalStateException("LuckPerms refused to remove $key: $r")
        }
        return changed
    }

    private fun <T> await(future: CompletableFuture<T>, what: String): T? = try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: java.util.concurrent.TimeoutException) {
        throw IllegalStateException("LuckPerms did not answer while $what (${timeoutMs / 1000} s)")
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    }

    companion object {
        private fun buildNode(key: String, expiry: Instant?): Node {
            val b = Node.builder(key)
            b.value(true)
            if (expiry != null) b.expiry(expiry)
            return b.build()
        }
    }
}

/** Creates the executor only when LuckPerms is installed (its classes are not loaded before). */
object LuckPermsLoader {
    fun create(offlineUuid: (String) -> UUID, clock: McClock = SystemMcClock): PermissionApplier =
        LuckPermsExecutor(offlineUuid = offlineUuid, clock = clock)
}
