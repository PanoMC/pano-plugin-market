package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.feature.GameLink
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.McSender
import com.panomc.plugins.market.mc.core.feature.RuntimeControl
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.sync.EngineStatus
import com.panomc.plugins.market.mc.core.sync.RecoveryPreview
import com.panomc.plugins.market.mc.core.sync.RuntimeStatus
import com.panomc.plugins.market.mc.core.wire.MarketConfigMessage
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketRequest
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** The files the jar ships (`src/mc/resources`), read from the source tree exactly as the class loader would serve them. */
object ShippedResources {
    val root: File = File(System.getProperty("market.mcResources"))

    val reader: (String) -> String? = { path -> File(root, path).takeIf { it.isFile }?.readText(Charsets.UTF_8) }
}

class CollectingLog : McLog {
    val lines = CopyOnWriteArrayList<String>()
    override fun info(message: String) {
        lines.add("INFO $message")
    }

    override fun warn(message: String) {
        lines.add("WARN $message")
    }

    override fun error(message: String, error: Throwable?) {
        lines.add("ERROR $message")
    }

    fun has(fragment: String) = lines.any { it.contains(fragment) }
}

class FixedClock(var now: Long = 1_790_000_000_000L) : McClock {
    override fun now(): Long = now
}

/** A Pano that answers from [handler] (`null` = no answer: a timeout). Answers synchronously unless [defer] is set. */
class FakeGameLink : GameLink {
    @Volatile
    var up = true

    /** Every request except `MARKET_CONFIG` (those are in [configRequests]). */
    val requests = CopyOnWriteArrayList<MarketRequest>()
    val configRequests = CopyOnWriteArrayList<com.panomc.plugins.market.mc.core.wire.MarketConfigRequest>()
    var handler: (MarketRequest) -> PlatformMessageResponse? = { null }
    var defer = false
    private val deferred = CopyOnWriteArrayList<() -> Unit>()

    override fun connected() = up

    @Suppress("UNCHECKED_CAST")
    override fun <R : PlatformMessageResponse> request(request: MarketRequest, responseType: Class<R>, callback: (R?) -> Unit) {
        if (request is com.panomc.plugins.market.mc.core.wire.MarketConfigRequest) configRequests.add(request) else requests.add(request)
        val run = { callback(handler(request)?.let { responseType.cast(it) }) }
        if (defer) deferred.add(run) else run()
    }

    fun releaseDeferred() {
        val all = deferred.toList()
        deferred.clear()
        all.forEach { it() }
    }

    fun <T : MarketRequest> of(type: Class<T>): List<T> = requests.filterIsInstance(type)
}

class FakeSender(
    override val name: String = "Steve",
    override val isConsole: Boolean = false,
    override val uuid: String? = if (isConsole) null else "11111111-1111-1111-1111-111111111111",
    override val locale: String? = null,
    private val nodes: Set<String> = emptySet()
) : McSender {
    val lines = CopyOnWriteArrayList<String>()
    val links = CopyOnWriteArrayList<String?>()

    override fun hasPermission(node: String) = isConsole || node in nodes

    override fun send(text: String, openUrl: String?) {
        lines.add(text)
        links.add(openUrl)
    }

    fun plain() = lines.map { it.replace(Regex("§."), "") }
    fun said(fragment: String) = plain().any { it.contains(fragment) }
}

class FakeHost : FeatureHost {
    val sent = CopyOnWriteArrayList<Triple<String, String, String?>>()
    val broadcasts = CopyOnWriteArrayList<String>()
    val locales = HashMap<String, String>()

    override fun sendTo(username: String, text: String, openUrl: String?) {
        sent.add(Triple(username, text, openUrl))
    }

    override fun broadcast(text: String) {
        broadcasts.add(text)
    }

    override fun localeOf(username: String): String? = locales[username.lowercase()]

    fun to(username: String) = sent.filter { it.first.equals(username, true) }.map { it.second.replace(Regex("§."), "") }
}

class FakeControl(var status: RuntimeStatus = RuntimeStatus(true, false, true, EngineStatus())) : RuntimeControl {
    var preview: RecoveryPreview? = null
    var confirmResult = true
    var confirmed = 0
    var syncs = 0

    override fun status() = status
    override fun recoveryPreview(): CompletableFuture<RecoveryPreview?> = CompletableFuture.completedFuture(preview)
    override fun confirmRecovery(): CompletableFuture<Boolean> {
        confirmed++
        return CompletableFuture.completedFuture(confirmResult)
    }

    override fun syncSoon() {
        syncs++
    }
}

fun panoConfig(
    hash: String = "h1",
    settings: MarketMcSettings = MarketMcSettings(),
    texts: Map<String, Map<String, String>> = emptyMap(),
    storeUrl: String? = "https://shop.example.com",
    creditName: String? = "Credits",
    productUrlTemplate: String? = null,
    registerUrl: String? = null
) = MarketConfigMessage(true, null, hash, settings, texts, storeUrl, creditName, "USD", 7L, productUrlTemplate, registerUrl)

/** One [MarketFeatures] over the shipped resources in [dir] (config.yml is written there, like on a first start). */
class FeatureRig(
    val dir: Path,
    configText: String? = null,
    val link: FakeGameLink = FakeGameLink(),
    val host: FakeHost = FakeHost(),
    val clock: FixedClock = FixedClock(),
    val log: CollectingLog = CollectingLog(),
    val uuids: MutableMap<String, String> = HashMap(),
    /** The port the features really use; defaults to [host] (the recording fake). */
    featureHost: FeatureHost? = null
) {
    val features: MarketFeatures

    init {
        if (configText != null) {
            java.nio.file.Files.createDirectories(dir)
            java.nio.file.Files.write(dir.resolve("config.yml"), configText.toByteArray(Charsets.UTF_8))
        }
        features = MarketFeatures.create(dir, ShippedResources.reader, featureHost ?: host, link, { uuids[it.lowercase()] }, "1.4.0", log, clock)
    }

    val control = FakeControl().also { features.attach(it) }

    /** Pano answers `MARKET_CONFIG` with [config] and the engine tells the features the hash changed. */
    fun loadConfig(config: MarketConfigMessage = panoConfig()) {
        val before = link.handler
        link.handler = { r -> if (r is com.panomc.plugins.market.mc.core.wire.MarketConfigRequest) config else before(r) }
        features.callbacks.onConfigHashChanged(config.configHash ?: "h")
    }
}
