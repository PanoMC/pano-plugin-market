package com.panomc.plugins.market.mc.link

import net.luckperms.api.LuckPerms
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.data.NodeMap
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.node.Node
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("UNCHECKED_CAST")
fun <T> proxyOf(type: Class<T>, handler: (Method, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, m, args ->
        val a = args ?: emptyArray()
        when {
            m.name == "toString" && a.isEmpty() -> "proxy(${type.simpleName})"
            m.name == "hashCode" && a.isEmpty() -> System.identityHashCode(self)
            m.name == "equals" && a.size == 1 -> self === a[0]
            else -> handler(m, a)
        }
    } as T

/** `ImmutableContextSet.empty()` needs a registered LuckPerms provider, so the context set is faked too: only `isEmpty` is read. */
fun contexts(global: Boolean): ImmutableContextSet = proxyOf(ImmutableContextSet::class.java) { m, _ ->
    when (m.name) {
        "isEmpty" -> global
        else -> throw UnsupportedOperationException("ImmutableContextSet.${m.name}")
    }
}

/** A LuckPerms node as the executor reads it: key, value, expiry, context (global = empty). */
class FakeNode(val key: String, val expiry: Instant? = null, val value: Boolean = true, val global: Boolean = true) {
    val node: Node = proxyOf(Node::class.java) { m, _ ->
        when (m.name) {
            "getKey" -> key
            "getValue" -> value
            "hasExpiry" -> expiry != null
            "getExpiry" -> expiry
            "getContexts" -> contexts(global)
            else -> throw UnsupportedOperationException("Node.${m.name}")
        }
    }
}

/** Just enough of the LuckPerms 5 API: user manager, one user, its node map. Records everything the executor does. */
class FakeLuckPerms {
    val nodes = CopyOnWriteArrayList<Node>()
    val nodeInfo = HashMap<Node, FakeNode>()
    val loads = CopyOnWriteArrayList<Pair<UUID, String?>>()
    val lookups = CopyOnWriteArrayList<String>()
    var saves = 0
    var lookupResult: UUID? = null
    var loadFuture: (() -> CompletableFuture<User>)? = null
    var saveFuture: (() -> CompletableFuture<Void>)? = null

    fun seed(key: String, expiry: Instant? = null, global: Boolean = true): Node {
        val f = FakeNode(key, expiry, true, global)
        nodeInfo[f.node] = f
        nodes.add(f.node)
        return f.node
    }

    /** What is stored now: key to expiry, global context only. */
    fun stored(): List<Pair<String, Instant?>> = nodes.filter { nodeInfo[it]!!.global }.map { nodeInfo[it]!!.key to nodeInfo[it]!!.expiry }

    fun factory(): (String, Instant?) -> Node = { key, expiry ->
        val f = FakeNode(key, expiry)
        nodeInfo[f.node] = f
        f.node
    }

    private val nodeMap: NodeMap = proxyOf(NodeMap::class.java) { m, a ->
        when (m.name) {
            "toCollection" -> nodes.toList()
            "add" -> { nodes.add(a[0] as Node); DataMutateResult.SUCCESS }
            "remove" -> { nodes.remove(a[0] as Node); DataMutateResult.SUCCESS }
            else -> throw UnsupportedOperationException("NodeMap.${m.name}")
        }
    }

    private val user: User = proxyOf(User::class.java) { m, _ ->
        when (m.name) {
            "data" -> nodeMap
            else -> throw UnsupportedOperationException("User.${m.name}")
        }
    }

    private val users: UserManager = proxyOf(UserManager::class.java) { m, a ->
        when (m.name) {
            "loadUser" -> {
                loads.add(a[0] as UUID to (if (a.size > 1) a[1] as String? else null))
                loadFuture?.invoke() ?: CompletableFuture.completedFuture(user)
            }
            "lookupUniqueId" -> {
                lookups.add(a[0] as String)
                CompletableFuture.completedFuture(lookupResult)
            }
            "saveUser" -> {
                saves++
                saveFuture?.invoke() ?: CompletableFuture.completedFuture<Void?>(null)
            }
            else -> throw UnsupportedOperationException("UserManager.${m.name}")
        }
    }

    val api: LuckPerms = proxyOf(LuckPerms::class.java) { m, _ ->
        when (m.name) {
            "getUserManager" -> users
            else -> throw UnsupportedOperationException("LuckPerms.${m.name}")
        }
    }
}
