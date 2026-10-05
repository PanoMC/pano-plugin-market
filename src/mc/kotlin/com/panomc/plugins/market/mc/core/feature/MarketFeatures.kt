package com.panomc.plugins.market.mc.core.feature

import com.panomc.plugins.market.mc.core.platform.DeliverySettings
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.core.store.DeliveryRecord
import com.panomc.plugins.market.mc.core.store.RecordState
import com.panomc.plugins.market.mc.core.sync.EngineCallbacks
import com.panomc.plugins.market.mc.core.wire.MarketConfigMessage
import com.panomc.plugins.market.mc.core.wire.MarketConfigRequest
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.core.wire.QueryType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The optional in-game features of one installation (19 section 9), platform independent: the effective config (local
 * file + `MARKET_CONFIG`), the commands, purchase broadcasts and join notifications. A platform main builds one with
 * [create], hands [settings] and [callbacks] to `MarketComponent`, calls [attach] with the component's runtime, forwards
 * every authenticated join to [onPlayerPresent] and registers [commands] with its own command API.
 */
class MarketFeatures(
    val config: EffectiveConfig,
    val messages: Messages,
    private val host: FeatureHost,
    private val link: GameLink,
    private val uuidOf: (String) -> String?,
    private val componentVersion: String,
    private val log: McLog,
    private val clock: McClock = SystemMcClock
) {
    @Volatile
    private var control: RuntimeControl? = null

    private val pulling = AtomicBoolean(false)
    private val announcedGifts = ConcurrentHashMap<String, Long>()

    val commands = MarketCommands(config, messages, link, { control }, componentVersion, clock)

    /**
     * Why the platform main must NOT start the component, or `null` when it may. A file that could not be read gives
     * the same "stay off" as `enabled: false`, so nothing is synced and no delivery is answered DISABLED_LOCALLY.
     */
    fun offReason(): String? {
        val local = config.local
        if (local.enabled) return null
        return local.error?.let { "The Market component stays OFF (not started, deliveries stay queued on Pano) because $it" }
            ?: "The Market component is switched off in config.yml (enabled: false)."
    }

    /** The live switches the delivery engine reads. */
    val settings: DeliverySettings get() = config.deliverySettings

    fun attach(runtime: RuntimeControl) {
        control = runtime
    }

    /** The syncing runtime, once [attach]ed: MC-06 calls `syncSoon()` after an in-game purchase. */
    fun runtime(): RuntimeControl? = control

    /** What the delivery engine tells the features (all on the engine thread: quick, hand the real work to the host). */
    val callbacks: EngineCallbacks = object : EngineCallbacks {
        override fun cachedConfigHash(): String? = config.remote?.configHash

        override fun onConfigHashChanged(current: String) = pullConfig()

        override fun showBroadcast(text: String) {
            val line = ChatFormat.fromPano(text)
            if (line.isBlank()) return
            host.broadcast(line)
        }

        override fun onQueueDrained(username: String, records: List<DeliveryRecord>) = announceDrained(username, records)
    }

    // ---- MARKET_CONFIG --------------------------------------------------------------------------------------------

    private fun pullConfig() {
        if (!pulling.compareAndSet(false, true)) return
        try {
            link.request(MarketConfigRequest(componentVersion, have = config.remote?.configHash), MarketConfigMessage::class.java) { m ->
                try {
                    applyConfig(m)
                } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                    log.error("Applying MARKET_CONFIG failed: ${e.message}", e)
                } finally {
                    pulling.set(false)
                }
            }
        } catch (e: Throwable) {
            pulling.set(false)
            log.warn("MARKET_CONFIG could not be requested: ${e.message}")
        }
    }

    /** Visible for tests. */
    internal fun applyConfig(m: MarketConfigMessage?) {
        if (m == null) return log.warn("MARKET_CONFIG got no answer; the Market component keeps the settings it has.")
        if (!m.accepted) return log.warn("MARKET_CONFIG was refused (${m.reason ?: "no reason"}); the Market component keeps the settings it has.")
        val hash = m.configHash?.takeIf { it.isNotBlank() } ?: return log.warn("MARKET_CONFIG carried no configHash; ignored.")
        val previous = config.remote
        val settings: MarketMcSettings = m.settings ?: previous?.settings ?: return log.warn("MARKET_CONFIG carried no settings and none are cached; ignored.")
        // An answer without settings means "unchanged": everything else it carries refines the cached values.
        val unchanged = m.settings == null
        fun <T> pick(fresh: T?, old: T?): T? = if (unchanged) fresh ?: old else fresh
        val texts = if (unchanged && m.texts.isEmpty()) previous?.texts ?: emptyMap() else limitTexts(m.texts)
        config.update(
            RemoteConfig(
                configHash = hash,
                settings = settings,
                texts = texts,
                storeUrl = pick(m.storeUrl, previous?.storeUrl)?.takeIf { isWebUrl(it) },
                creditName = pick(m.creditName, previous?.creditName)?.let { ChatFormat.plainValue(it).take(32) },
                currency = pick(m.currency, previous?.currency),
                serverId = pick(m.serverId, previous?.serverId)
            )
        )
    }

    private fun limitTexts(texts: Map<String, Map<String, String>>): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        for ((locale, map) in texts.entries.take(Messages.MAX_PANO_LOCALES)) {
            out[locale] = map.entries.take(MAX_TEXTS).associate { it.key.take(80) to it.value.take(MAX_TEXT_LENGTH) }
        }
        return out
    }

    // ---- join notifications ---------------------------------------------------------------------------------------

    /** From the engine after a joining player's queue ran: how many waiting deliveries were delivered, and the gifts among them. */
    private fun announceDrained(username: String, records: List<DeliveryRecord>) {
        if (!config.enabled(Feature.JOIN_NOTIFICATIONS)) return
        val done = records.filter { it.state == RecordState.DONE }
        if (done.isEmpty()) return
        val locale = host.localeOf(username)
        host.sendTo(username, messages.text(Msg.JOIN_DELIVERED, locale, "count" to done.size))
        for (r in done) {
            val d = r.display ?: continue
            if (!d.gift) continue
            if (d.orderPublicId != null && !claimGift(username, d.orderPublicId)) continue
            host.sendTo(username, messages.text(Msg.JOIN_GIFT, locale, "from" to (d.from ?: "?"), "product" to (d.productName ?: "?")))
        }
    }

    /** An authenticated join (19 section 6.2 "pending-delivery / gift notices"): `MARKET_QUERY PENDING`. */
    fun onPlayerPresent(username: String) {
        if (!config.enabled(Feature.JOIN_NOTIFICATIONS) || !link.connected()) return
        val request = MarketQueryRequest(componentVersion, type = QueryType.PENDING, player = PlayerRef(username, uuidOf(username)), page = null, args = null)
        try {
            link.request(request, MarketQueryMessage::class.java) { m ->
                try {
                    announcePending(username, m)
                } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                    log.warn("The join notice of $username failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            log.warn("The join notice query of $username could not be sent: ${e.message}")
        }
    }

    private fun announcePending(username: String, m: MarketQueryMessage?) {
        val data = m?.takeIf { it.accepted }?.data ?: return
        if (!config.enabled(Feature.JOIN_NOTIFICATIONS)) return
        val locale = host.localeOf(username)
        val queued = data.deliveriesQueued ?: 0
        if (queued > 0) host.sendTo(username, messages.text(Msg.JOIN_PENDING, locale, "count" to queued))
        for (g in data.gifts ?: emptyList()) {
            if (g.orderPublicId.isNotEmpty() && !claimGift(username, g.orderPublicId)) continue
            host.sendTo(username, messages.text(Msg.JOIN_GIFT_PENDING, locale, "from" to (g.from ?: "?"), "product" to g.productName))
        }
    }

    /** `true` for the first claim of a gift notice for this player: the local and the Pano side never both announce it. */
    private fun claimGift(username: String, orderPublicId: String): Boolean {
        val now = clock.now()
        if (announcedGifts.size > 512) announcedGifts.entries.removeIf { now - it.value > GIFT_WINDOW_MS }
        return announcedGifts.putIfAbsent("${username.lowercase()}|$orderPublicId", now) == null
    }

    companion object {
        private const val MAX_TEXTS = 300
        private const val MAX_TEXT_LENGTH = 512
        private const val GIFT_WINDOW_MS = 10 * 60 * 1000L

        private fun isWebUrl(url: String) = (url.startsWith("https://") || url.startsWith("http://")) && url.none { it.code < 0x21 || it.code == 0x7f }

        /** Where the bundled files live inside the jar. */
        const val CONFIG_RESOURCE = "mc/config.yml"
        fun langResource(locale: String) = "mc/lang/$locale.yml"

        /**
         * Reads (and creates, when missing) `<dataDir>/config.yml`, builds the message layers and the features. [resource]
         * reads a file of the jar by path (`mc/config.yml`). Never throws: a config that cannot be read fails closed.
         */
        fun create(
            dataDir: Path,
            resource: (String) -> String?,
            host: FeatureHost,
            link: GameLink,
            uuidOf: (String) -> String?,
            componentVersion: String,
            log: McLog,
            clock: McClock = SystemMcClock
        ): MarketFeatures {
            val local = loadLocalConfig(dataDir, resource, log)
            local.warnings.forEach { log.warn("config.yml: $it") }
            val effective = EffectiveConfig(local)
            val messages = Messages(
                bundled = { locale -> readTexts(resource(langResource(locale)), langResource(locale), log) },
                overrides = { locale -> readOverride(dataDir.resolve("lang").resolve("$locale.yml"), log) },
                panoTexts = { effective.remote?.texts ?: emptyMap() },
                configuredLocales = { local.locales }
            )
            return MarketFeatures(effective, messages, host, link, uuidOf, componentVersion, log, clock)
        }

        internal fun loadLocalConfig(dataDir: Path, resource: (String) -> String?, log: McLog): LocalConfig {
            val file = dataDir.resolve("config.yml")
            try {
                if (!Files.exists(file)) {
                    val bundled = resource(CONFIG_RESOURCE)
                    if (bundled != null) {
                        Files.createDirectories(dataDir)
                        Files.write(file, bundled.toByteArray(Charsets.UTF_8))
                    }
                }
                val text = if (Files.exists(file)) String(Files.readAllBytes(file), Charsets.UTF_8) else resource(CONFIG_RESOURCE) ?: ""
                val parsed = LocalConfig.parse(text)
                parsed.error?.let { log.error("$it. The Market component stays OFF (not started, nothing synced, paid deliveries stay queued on Pano) until config.yml is fixed.") }
                return parsed
            } catch (e: Exception) {
                val message = "config.yml could not be read: ${e.message}"
                log.error("$message. The Market component stays OFF (not started, nothing synced, paid deliveries stay queued on Pano) until it can be read.")
                return LocalConfig.failedClosed(message)
            }
        }

        private fun readTexts(text: String?, name: String, log: McLog): Map<String, String>? {
            if (text == null) return null
            return try {
                flatTexts(MiniYaml.parse(text))
            } catch (e: YamlException) {
                log.error("$name is not valid: ${e.message}")
                null
            }
        }

        private fun readOverride(file: Path, log: McLog): Map<String, String>? {
            return try {
                if (!Files.isRegularFile(file)) return null
                readTexts(String(Files.readAllBytes(file), Charsets.UTF_8), "lang/${file.fileName}", log)
            } catch (e: Exception) {
                log.error("lang/${file.fileName} could not be read: ${e.message}")
                null
            }
        }
    }
}
