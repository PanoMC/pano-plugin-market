package com.panomc.plugins.market.mc.core.support

import com.panomc.plugins.market.mc.core.platform.DeliverySettings
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.platform.SyncTransport
import com.panomc.plugins.market.mc.core.store.AppendSink
import com.panomc.plugins.market.mc.core.store.DeliveryRecord
import com.panomc.plugins.market.mc.core.sync.EngineCallbacks
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import java.io.IOException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

class TestClock(var current: Long = 1_790_000_000_000L) : McClock {
    override fun now(): Long = current
    fun advance(ms: Long) {
        current += ms
    }
}

class TestLog : McLog {
    val lines = ArrayList<String>()
    override fun info(message: String) {
        lines.add("INFO $message")
    }

    override fun warn(message: String) {
        lines.add("WARN $message")
    }

    override fun error(message: String, error: Throwable?) {
        lines.add("ERROR $message")
    }

    fun count(fragment: String): Int = lines.count { it.contains(fragment) }
    fun has(fragment: String): Boolean = count(fragment) > 0
}

/** Virtual time: tasks run only when the test says so, in due-time then submission order. */
class ManualScheduler(private val clock: TestClock) : McScheduler {
    private class Task(val due: Long, val order: Long, val body: () -> Unit) {
        @Volatile
        var cancelled = false
    }

    private var counter = 0L
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.due }, { it.order }))

    override fun execute(task: () -> Unit) {
        queue.add(Task(clock.now(), counter++, task))
    }

    override fun schedule(delayMs: Long, task: () -> Unit): McTimer {
        val t = Task(clock.now() + delayMs.coerceAtLeast(0), counter++, task)
        queue.add(t)
        return object : McTimer {
            override fun cancel() {
                t.cancelled = true
            }
        }
    }

    /** Runs everything that is due at the current time (including tasks those tasks submit). */
    fun runUntilIdle() {
        var guard = 0
        while (true) {
            val next = queue.peek() ?: return
            if (next.due > clock.now()) return
            queue.poll()
            if (!next.cancelled) next.body()
            check(++guard < 100_000) { "scheduler does not settle" }
        }
    }

    /** Moves the clock forward, running every task that falls due on the way. */
    fun advance(ms: Long) {
        val target = clock.now() + ms
        while (true) {
            runUntilIdle()
            val next = queue.peek() ?: break
            if (next.due > target) break
            clock.current = next.due
        }
        clock.current = target
        runUntilIdle()
    }

    fun pending(): Int = queue.count { !it.cancelled }
}

data class PermissionCall(val username: String, val uuidHint: String?, val op: String, val nodes: List<String>, val expiresAt: Long?)

class FakeMcPlatform : McPlatform {
    override var platformName: String = McPlatformName.PAPER
    var luckPerms = true
    var vault = false
    var placeholders = false

    /** lower-case username -> uuid of the players that are present (authenticated). */
    val presentPlayers = ConcurrentHashMap<String, String>()

    val console = CopyOnWriteArrayList<String>()
    val permissionCalls = CopyOnWriteArrayList<PermissionCall>()

    var dispatcher: (String) -> DispatchResult = { DispatchResult.OK }
    var permissionHandler: (PermissionCall) -> PermissionOutcome = { PermissionOutcome(true) }
    var offline: (String) -> String = { name -> UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString() }

    override fun luckPermsAvailable() = luckPerms
    override fun vaultAvailable() = vault
    override fun placeholderApiAvailable() = placeholders

    /** When set, `isPresent` throws it (an authentication plugin that is not ready, a broken adapter). */
    var presenceError: Throwable? = null

    override fun isPresent(username: String): Boolean {
        presenceError?.let { throw it }
        return presentPlayers.containsKey(username.lowercase())
    }
    override fun playerUuid(username: String): String? = presentPlayers[username.lowercase()]
    override fun offlineUuid(username: String): String = offline(username)

    override fun dispatchConsole(command: String): DispatchResult {
        console.add(command)
        return dispatcher(command)
    }

    override fun applyPermission(username: String, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome {
        val call = PermissionCall(username, uuidHint, op, nodes, expiresAt)
        permissionCalls.add(call)
        return permissionHandler(call)
    }

    fun join(name: String, uuid: String = UUID.nameUUIDFromBytes(name.toByteArray()).toString()): String {
        presentPlayers[name.lowercase()] = uuid
        return uuid
    }

    fun leave(name: String) {
        presentPlayers.remove(name.lowercase())
    }
}

class TestSettings(
    override var deliveriesEnabled: Boolean = true,
    override var luckPermsEnabled: Boolean = true,
    override var broadcastEnabled: Boolean = true
) : DeliverySettings

class RecordingCallbacks : EngineCallbacks {
    var localChanges = 0

    /** Called on every local change (a test wires the sync loop's `wake` here). */
    var hook: (() -> Unit)? = null
    val configHashChanges = ArrayList<String>()
    var cachedHash: String? = null
    val broadcasts = ArrayList<String>()
    val drains = ArrayList<Pair<String, List<DeliveryRecord>>>()

    override fun onLocalChange() {
        localChanges++
        hook?.invoke()
    }

    override fun onConfigHashChanged(current: String) {
        configHashChanges.add(current)
    }

    override fun cachedConfigHash(): String? = cachedHash

    override fun showBroadcast(text: String) {
        broadcasts.add(text)
    }

    override fun onQueueDrained(username: String, records: List<DeliveryRecord>) {
        drains.add(username to records)
    }
}

/** Records every request; the test answers (or drops) each one by hand. */
class FakeTransport : SyncTransport {
    val requests = CopyOnWriteArrayList<MarketSyncRequest>()
    private val callbacks = CopyOnWriteArrayList<(MarketSyncMessage?) -> Unit>()
    var failOnSend: Throwable? = null

    override fun send(request: MarketSyncRequest, callback: (MarketSyncMessage?) -> Unit) {
        failOnSend?.let { throw it }
        callbacks.add(callback)
        requests.add(request)
    }

    val sent: Int get() = requests.size

    /** Answers request number [index] (0 based); `null` is a timeout. */
    fun respond(index: Int, response: MarketSyncMessage?) = callbacks[index](response)

    fun respondLast(response: MarketSyncMessage?) = respond(requests.size - 1, response)
}

/** A journal sink that can fail on demand: half of the batch reaches the file, then the write throws. */
class FlakySink(private val delegate: AppendSink) : AppendSink {
    var failNextAppend = false

    /** Every append fails while this is set. */
    var failAppends = false
    var failTruncate = false
    override fun size() = delegate.size()
    override fun append(bytes: ByteArray) {
        if (failNextAppend || failAppends) {
            failNextAppend = false
            delegate.append(bytes.copyOf(bytes.size / 2))
            throw IOException("disk full")
        }
        delegate.append(bytes)
    }

    override fun truncate(size: Long) {
        if (failTruncate) throw IOException("cannot truncate")
        delegate.truncate(size)
    }

    override fun close() = delegate.close()
}
