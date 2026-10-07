package com.panomc.plugins.market.service

import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Error as PanoError
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketServerStateDao
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.IdempotencyConflict
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.error.InvalidCreditAmount
import com.panomc.plugins.market.event.server.GameCategory
import com.panomc.plugins.market.event.server.GameGift
import com.panomc.plugins.market.event.server.GameOrder
import com.panomc.plugins.market.event.server.GamePlayerBalance
import com.panomc.plugins.market.event.server.GameProduct
import com.panomc.plugins.market.event.server.GamePurchasable
import com.panomc.plugins.market.event.server.MarketAdminEventRequest
import com.panomc.plugins.market.event.server.MarketAdminEventResponse
import com.panomc.plugins.market.event.server.MarketConfigEventRequest
import com.panomc.plugins.market.event.server.MarketConfigEventResponse
import com.panomc.plugins.market.event.server.MarketEconomyEventRequest
import com.panomc.plugins.market.event.server.MarketEconomyEventResponse
import com.panomc.plugins.market.event.server.MarketPurchaseEventRequest
import com.panomc.plugins.market.event.server.MarketPurchaseEventResponse
import com.panomc.plugins.market.event.server.MarketQueryData
import com.panomc.plugins.market.event.server.MarketQueryEventRequest
import com.panomc.plugins.market.event.server.MarketQueryEventResponse
import com.panomc.plugins.market.event.server.McSettingsView
import com.panomc.plugins.market.log.GrantedMarketCreditsIngameLog
import com.panomc.plugins.market.log.GrantedMarketProductIngameLog
import com.panomc.plugins.market.log.RevokedMarketCreditsIngameLog
import com.panomc.plugins.market.log.SetMarketCreditsIngameLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.panel.credit.parseCreditAmount
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Does the Pano user [userId] hold one of the market permission nodes (04 section 9)? `true` for the umbrella node and for a platform administrator too (the same
 * decision as `MarketPermissions.require` makes for a panel request). The in-game admin commands ask it for the account linked to the sender (19 section 7.4,
 * second half of the double authorisation); a test stands in with a map.
 */
fun interface McPermissions {
    suspend fun holds(userId: Long, node: MarketNode): Boolean
}

/**
 * The keys of the per-server override of the `mc*` settings (`market_server_state.settings`, `PUT /servers/:id/settings`, 04 section 8) and what each may hold.
 * The panel defaults are the same keys of `MarketConfig` (00 section 12). [validate] is the write rule (400 `INVALID_SETTINGS`), [accept] the read rule: a
 * stored value of the wrong type is ignored, so a row written by hand can never put garbage into the configuration of a server.
 */
object McSettingKeys {
    val BOOLEANS: List<String> = listOf(
        "mcStoreCommand", "mcCreditsCommand", "mcJoinNotifications", "mcStoreMenu", "mcAdminCommands", "mcPlaceholders", "mcLuckPerms", "mcBroadcast"
    )

    const val TEMPLATE = "mcBroadcastTemplate"
    const val DISABLED_ADMIN = "mcDisabledAdminCommands"
    const val VAULT_MODE = "mcVaultMode"
    const val VAULT_RATE = "mcVaultRate"
    const val VAULT_DIRECTION = "mcVaultDirection"

    /** Every key, in the order of the panel defaults. */
    val ALL: List<String> = BOOLEANS + listOf(TEMPLATE, DISABLED_ADMIN, VAULT_MODE, VAULT_RATE, VAULT_DIRECTION)

    /** The sub-commands `mcDisabledAdminCommands` may name (00 section 12), one per `MARKET_ADMIN` op. */
    val ADMIN_COMMANDS: Set<String> = setOf("give-credits", "take-credits", "set-credits", "grant-product", "purchases")

    val VAULT_MODES: Set<String> = setOf("OFF", "PROVIDER", "CONVERT")
    val VAULT_DIRECTIONS: Set<String> = setOf("BOTH", "TO_SERVER", "TO_CREDITS")

    const val MAX_TEMPLATE = 256
    const val MAX_RATE = 1_000_000_000.0

    /** [value] normalised for [key], or `null` when it is not a valid value of that key. */
    fun accept(key: String, value: Any?): Any? = when (key) {
        in BOOLEANS -> value as? Boolean

        TEMPLATE -> (value as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_TEMPLATE && it.none { c -> c < ' ' || c == '\u007f' } }

        DISABLED_ADMIN -> (value as? List<*>)?.takeIf { list -> list.all { it is String && it in ADMIN_COMMANDS } }?.map { it as String }?.distinct()

        VAULT_MODE -> (value as? String)?.takeIf { it in VAULT_MODES }

        VAULT_RATE -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_RATE }

        VAULT_DIRECTION -> (value as? String)?.takeIf { it in VAULT_DIRECTIONS }

        else -> null
    }

    /** `fieldErrors` of a settings object for `PUT /servers/:id/settings`: an unknown key is `UNKNOWN`, a bad value `INVALID`. Empty = valid. */
    fun validate(settings: JsonObject): Map<String, String> {
        val errors = LinkedHashMap<String, String>()

        for (key in settings.fieldNames()) {
            val value = when (val raw = settings.getValue(key)) {
                is JsonArray -> raw.list
                else -> raw
            }

            if (key !in ALL) errors[key] = "UNKNOWN" else if (accept(key, value) == null) errors[key] = "INVALID"
        }

        return errors
    }
}

/** What a download of the Minecraft component is: a file on disk (the running jar) or bytes (the bundled Fabric jar), with the name the browser saves it under. */
sealed class McDownload(val fileName: String) {
    class OnDisk(val path: Path, fileName: String) : McDownload(fileName)

    class InMemory(val bytes: ByteArray, fileName: String) : McDownload(fileName)
}

/**
 * `GET /mc-component/download` (19 section 2.3): the **running market jar itself** (it is also the Spigot / Paper / Folia / BungeeCord / Velocity plugin, so the
 * version always matches what the gate of `MARKET_SYNC` requires), or with `platform=fabric` the bundled resource `mc/pano-plugin-market-fabric.jar`. [resolve]
 * answers `null` (404 `NOT_FOUND`) when the Fabric jar was not bundled (a build without `-Pfabric`) or the running jar cannot be found. Never a path from the
 * request: the file is [runningJar] or the one bundled resource.
 */
class McComponentDownload(
    private val runningJar: () -> Path?,
    private val fabricJar: () -> ByteArray?,
    private val version: () -> String
) {
    fun resolve(platform: String?): McDownload? {
        val safe = version().map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '+') it else '_' }.joinToString("").ifEmpty { "unknown" }

        return when (platform?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }) {
            null -> runningJar()?.takeIf { Files.isRegularFile(it) }?.let { McDownload.OnDisk(it, "pano-plugin-market-$safe.jar") }
            FABRIC -> fabricJar()?.takeIf { it.isNotEmpty() }?.let { McDownload.InMemory(it, "pano-plugin-market-fabric-$safe.jar") }
            else -> null
        }
    }

    /** Writes [download] to [response]: an attachment of the jar (a file on disk is sent by the server's own file transfer, never read into memory). */
    suspend fun send(response: io.vertx.core.http.HttpServerResponse, download: McDownload) {
        response
            .putHeader("Content-Type", "application/java-archive")
            .putHeader("Content-Disposition", "attachment; filename=\"${download.fileName}\"")
            .putHeader("X-Content-Type-Options", "nosniff")
            .putHeader("Cache-Control", "no-store")

        when (download) {
            is McDownload.OnDisk -> response.sendFile(download.path.toAbsolutePath().toString()).coAwait()
            is McDownload.InMemory -> response.end(io.vertx.core.buffer.Buffer.buffer(download.bytes)).coAwait()
        }
    }

    companion object {
        const val FABRIC = "fabric"

        /** The classpath resource the build bundles with `-Pfabric` (MC-08). */
        const val FABRIC_RESOURCE = "mc/pano-plugin-market-fabric.jar"
    }
}

/** A token bucket per server on the injected clock (19 section 7.2: 20 queries / s; section 7.4: 30 admin operations / min). */
internal class McBucket(private val capacity: Int, private val refillEveryMs: Long, private val clock: Clock) {
    private class State(var tokens: Double, var at: Long)

    private val states = ConcurrentHashMap<Long, State>()

    fun tryTake(serverId: Long): Boolean {
        val now = clock.now()
        val state = states.computeIfAbsent(serverId) { State(capacity.toDouble(), now) }

        synchronized(state) {
            val elapsed = (now - state.at).coerceAtLeast(0)

            state.tokens = minOf(capacity.toDouble(), state.tokens + elapsed.toDouble() / refillEveryMs)
            state.at = now

            if (state.tokens < 1.0) return false

            state.tokens -= 1.0

            return true
        }
    }
}

/**
 * The Pano side of the five game events besides `MARKET_SYNC` (19 section 7, MC-04): `MARKET_CONFIG`, `MARKET_QUERY`, `MARKET_PURCHASE`, `MARKET_ADMIN` and
 * `MARKET_ECONOMY`. The `event.server.*Event` classes are thin adapters; every business rule is the one the web already enforces:
 *
 * - [purchase] is a full checkout under the `INGAME` profile (05 section 12) through [CheckoutService.checkout]: `payWithCredits`, the key `mc:<serverId>:<operationId>`
 *   (a replay of the same `operationId` is the checkout's own idempotent replay: the first order again), every line rule, the block list, the legal acceptance. A
 *   refusal is `{ok: false, code}` with the code of 04 section 11 (`REQUIREMENT_NOT_MET` for the 409 `PRODUCT_REQUIREMENT_NOT_MET`);
 * - [admin] needs the linked account's market permission on top of the in-game node (`PAY` for the credit operations and `PURCHASES`, `PAY` and `OM` for
 *   `GRANT_PRODUCT`); the console is always allowed; `GRANT_PRODUCT` is a manual order of price 0 (limits enforced, never `force`);
 * - [economy] posts `EXTERNAL_IN` / `EXTERNAL_OUT` on the ledger (07 section 3.1) only while the effective `mcVaultMode` is `PROVIDER` or `CONVERT`;
 * - [query] is read-only; [config] merges the panel defaults with the per-server override and hashes the result ([configHash]).
 *
 * Nothing is trusted but the server identity (an authenticated installation): a request never spends or grants on its own authority. Every handler first applies
 * the gates of 04 section 3.1 (the store READY, protocol 1, the component version equal to the market's): a refusal is `accepted = false` with a `reason`, and nothing was done.
 * A database failure propagates: the component sees no answer, which it treats as "outcome unknown", never as a failure.
 */
class McGameService(
    private val db: MarketDb,
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val serverStates: MarketServerStateDao,
    private val credits: CreditService,
    private val creditTxs: MarketCreditTxDao,
    private val checkout: CheckoutService,
    private val users: UserDirectory,
    private val permissions: McPermissions,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val products: MarketProductDao,
    private val categories: MarketCategoryDao,
    private val store: StoreQueryService,
    private val widgets: WidgetService,
    /** The read connection (the pool): queries and look-ups outside a transaction. */
    private val read: suspend () -> SqlClient,
    /** Writes the activity log of an in-game admin action (the actor is a Pano user); never throws for the caller's sake, a failure is logged. */
    private val activity: suspend (PluginActivityLog) -> Unit,
    private val pluginId: String,
    private val marketVersion: () -> String,
    /** The site's base URL (`storeUrl` of `MARKET_CONFIG`; the component appends `/store/<slug>` and `/register`), `null` while the platform has none. */
    private val storeUrl: () -> String?,
    /** The locale a manual or in-game order of [username] is stored with (06 section 14.3); `null` = the checkout's default. */
    private val orderLocale: suspend (username: String) -> String? = { null },
    /** The strings of `MARKET_CONFIG.texts` for one locale (19 section 7.1); empty while the market locale files hold no in-game group. */
    private val texts: suspend (locale: String) -> Map<String, String> = { emptyMap() },
    /** The locales `texts` is rendered for, the site's default first, at most three. */
    private val textLocales: () -> List<String> = { listOf("en-US") },
    /** After an in-game purchase completed (best effort): the purchase announcement (`McSyncService.announce`). */
    private val announce: suspend (MarketOrder, List<MarketOrderItem>) -> Unit = { _, _ -> },
    private val ready: () -> Boolean = { MarketRuntime.isReady }
) {
    private val queryBucket = McBucket(QUERY_PER_SECOND, 1_000L / QUERY_PER_SECOND, clock)
    private val adminBucket = McBucket(ADMIN_PER_MINUTE, 60_000L / ADMIN_PER_MINUTE, clock)

    private fun table(name: String) = "`${orders.prefix()}$name`"

    /** The gates of 04 section 3.1 in their order; `null` = the request may proceed. */
    private fun gate(componentVersion: String, protocol: Int): String? = when {
        !ready() -> REASON_NOT_READY
        protocol != PROTOCOL -> REASON_PROTOCOL
        componentVersion != marketVersion() -> REASON_VERSION
        else -> null
    }

    // ===== MARKET_CONFIG (19 section 7.1) =================================================================================

    /** The merged settings, the texts and the identity values of [server], hashed; an unchanged hash answers without the body (the component keeps what it holds). */
    suspend fun config(request: MarketConfigEventRequest, server: Server): MarketConfigEventResponse {
        gate(request.componentVersion, request.protocol)?.let { return MarketConfigEventResponse(accepted = false, reason = it) }

        val view = configView(server.id)
        val hash = hashOf(view)

        if (request.have == hash) return MarketConfigEventResponse(accepted = true, configHash = hash)

        return MarketConfigEventResponse(
            accepted = true, configHash = hash, settings = view.settings, texts = view.texts, storeUrl = view.storeUrl, creditName = view.creditName,
            currency = view.currency, serverId = server.id
        )
    }

    /** The `configHash` the component of [serverId] should hold (`MARKET_SYNC` carries it so the component notices a change within one sync). */
    suspend fun configHash(serverId: Long): String = hashOf(configView(serverId))

    private class ConfigView(
        val settings: McSettingsView,
        val values: Map<String, Any?>,
        val texts: Map<String, Map<String, String>>,
        val storeUrl: String?,
        val creditName: String,
        val currency: String,
        val serverId: Long
    )

    private suspend fun configView(serverId: Long): ConfigView {
        val c = config()
        val values = effectiveSettings(serverId)
        val locales = textLocales().distinct().take(MAX_TEXT_LOCALES)
        val rendered = LinkedHashMap<String, Map<String, String>>()

        for (locale in locales) texts(locale).takeIf { it.isNotEmpty() }?.let { rendered[locale] = it }

        return ConfigView(
            settings = viewOf(values), values = values, texts = rendered, storeUrl = storeUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
            creditName = creditName(c), currency = c.currency, serverId = serverId
        )
    }

    /**
     * SHA-256 over the canonical JSON (sorted keys) of everything `MARKET_CONFIG` carries: the settings and the texts (19 section 7.1) and, deliberately, also the store
     * URL, the credit name, the currency and the server id (deviation: the component re-pulls only when the hash changes, so a changed credit name would otherwise
     * never reach it).
     */
    private fun hashOf(view: ConfigView): String {
        val canonical = canonical(
            mapOf(
                "settings" to view.values, "texts" to view.texts, "storeUrl" to view.storeUrl, "creditName" to view.creditName, "currency" to view.currency,
                "serverId" to view.serverId
            )
        )

        return MessageDigest.getInstance("SHA-256").digest(JsonObject(canonical as Map<String, Any?>).encode().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is Map<*, *> -> TreeMap<String, Any?>().also { out -> value.forEach { (k, v) -> out[k.toString()] = canonical(v) } }
        is Iterable<*> -> value.map { canonical(it) }
        else -> value
    }

    /** The panel defaults of the `mc*` keys, then the valid keys of the per-server override of [serverId] on top (19 section 9: override beats default). */
    suspend fun effectiveSettings(serverId: Long): Map<String, Any?> {
        val c = config()
        val values = linkedMapOf<String, Any?>(
            "mcStoreCommand" to c.mcStoreCommand, "mcCreditsCommand" to c.mcCreditsCommand, "mcJoinNotifications" to c.mcJoinNotifications,
            "mcStoreMenu" to c.mcStoreMenu, "mcAdminCommands" to c.mcAdminCommands, "mcPlaceholders" to c.mcPlaceholders, "mcLuckPerms" to c.mcLuckPerms,
            "mcBroadcast" to c.mcBroadcast, McSettingKeys.TEMPLATE to c.mcBroadcastTemplate, McSettingKeys.DISABLED_ADMIN to c.mcDisabledAdminCommands.distinct(),
            McSettingKeys.VAULT_MODE to c.mcVaultMode.name, McSettingKeys.VAULT_RATE to c.mcVaultRate, McSettingKeys.VAULT_DIRECTION to c.mcVaultDirection.name
        )
        val raw = serverStates.getByServerId(serverId, read())?.settings?.takeIf { it.isNotBlank() }
        val override = raw?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return values

        for (key in McSettingKeys.ALL) {
            if (!override.containsKey(key)) continue

            val value = when (val v = override.getValue(key)) {
                is JsonArray -> v.list
                else -> v
            }

            McSettingKeys.accept(key, value)?.let { values[key] = it }
        }

        return values
    }

    private fun viewOf(v: Map<String, Any?>): McSettingsView = McSettingsView(
        mcStoreCommand = v["mcStoreCommand"] as Boolean, mcCreditsCommand = v["mcCreditsCommand"] as Boolean, mcJoinNotifications = v["mcJoinNotifications"] as Boolean,
        mcStoreMenu = v["mcStoreMenu"] as Boolean, mcAdminCommands = v["mcAdminCommands"] as Boolean, mcPlaceholders = v["mcPlaceholders"] as Boolean,
        mcLuckPerms = v["mcLuckPerms"] as Boolean, mcBroadcast = v["mcBroadcast"] as Boolean, mcBroadcastTemplate = v[McSettingKeys.TEMPLATE] as String,
        mcDisabledAdminCommands = (v[McSettingKeys.DISABLED_ADMIN] as List<*>).map { it as String }, mcVaultMode = v[McSettingKeys.VAULT_MODE] as String,
        mcVaultRate = v[McSettingKeys.VAULT_RATE] as Double, mcVaultDirection = v[McSettingKeys.VAULT_DIRECTION] as String
    )

    /**
     * `PUT /servers/:id/settings`: stores [settings] (a subset of the `mc*` keys) as the override of [serverId], `null` or an empty object clears it. Answers the
     * `fieldErrors` of a bad object (nothing is written then), an empty map when it was stored. The row is created when the server has not synced yet; the sync
     * columns of an existing row are never touched.
     */
    suspend fun updateServerSettings(serverId: Long, settings: JsonObject?): Map<String, String> {
        val errors = settings?.let { McSettingKeys.validate(it) }.orEmpty()

        if (errors.isNotEmpty()) return errors

        val json = settings?.takeIf { !it.isEmpty }?.let { s ->
            JsonObject().also { out -> for (key in McSettingKeys.ALL) if (s.containsKey(key)) out.put(key, s.getValue(key)) }.encode()
        }
        val now = clock.now()

        db.tx { conn ->
            conn.preparedQuery(
                "INSERT INTO ${table("market_server_state")} (`serverId`, `settings`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE `settings` = VALUES(`settings`), `updatedAt` = VALUES(`updatedAt`)"
            ).execute(Tuple.of(serverId, json, now, now)).coAwait()
        }

        return emptyMap()
    }

    // ===== MARKET_QUERY (19 section 7.2) ==================================================================================

    suspend fun query(request: MarketQueryEventRequest, server: Server): MarketQueryEventResponse {
        gate(request.componentVersion, request.protocol)?.let { return MarketQueryEventResponse(accepted = false, reason = it) }

        if (!queryBucket.tryTake(server.id)) return MarketQueryEventResponse(accepted = false, reason = REASON_RATE_LIMITED)

        return when (request.type) {
            QUERY_BALANCE -> balance(request)
            QUERY_PURCHASES -> purchases(request)
            QUERY_CATALOG -> catalog(request, server)
            QUERY_PLACEHOLDERS -> placeholders(request)
            QUERY_PENDING -> pending(request, server)
            else -> MarketQueryEventResponse(accepted = false, reason = REASON_UNSUPPORTED_TYPE)
        }
    }

    private suspend fun userOf(username: String?, c: SqlClient): DirectoryUser? {
        val name = username?.trim().orEmpty()

        return if (name.isEmpty() || name.length > MAX_USERNAME) null else users.byUsername(name, c)
    }

    private fun answer(data: MarketQueryData) = MarketQueryEventResponse(accepted = true, data = data)

    private suspend fun balance(request: MarketQueryEventRequest): MarketQueryEventResponse {
        val c = config()

        if (request.player?.username.isNullOrBlank()) return MarketQueryEventResponse(accepted = false, reason = REASON_BAD_REQUEST)
        if (!c.creditsEnabled) return MarketQueryEventResponse(accepted = false, reason = REASON_CREDITS_DISABLED)

        val client = read()
        val user = userOf(request.player?.username, client)

        return answer(
            MarketQueryData(registered = user != null, balance = user?.let { MoneyUtil.toDecimal(credits.balance(it.id, client)) } ?: 0.0, creditName = creditName(c))
        )
    }

    private suspend fun purchases(request: MarketQueryEventRequest): MarketQueryEventResponse {
        if (request.player?.username.isNullOrBlank()) return MarketQueryEventResponse(accepted = false, reason = REASON_BAD_REQUEST)

        val client = read()
        val user = userOf(request.player?.username, client)

        return answer(MarketQueryData(orders = if (user == null) emptyList() else purchasesOf(user.id, client)))
    }

    /** The latest [PURCHASE_HISTORY] paid, non-test orders of [userId] as the buyer (`/store history`), newest first. */
    private suspend fun purchasesOf(userId: Long, c: SqlClient): List<GameOrder> {
        val rows = c.preparedQuery(
            "SELECT `id`, `publicId`, `status`, `totalPrice`, `currency`, `createdAt` FROM ${table("market_order")} " +
                "WHERE `userId` = ? AND `paidAt` IS NOT NULL AND `testMode` = 0 ORDER BY `id` DESC LIMIT $PURCHASE_HISTORY"
        ).execute(Tuple.of(userId)).coAwait().toList()

        if (rows.isEmpty()) return emptyList()

        val names = orderItems.getByOrderIds(rows.map { it.getLong("id") }, c)
            .filter { it.kind == com.panomc.plugins.market.db.model.OrderItemKind.PRODUCT || it.kind == com.panomc.plugins.market.db.model.OrderItemKind.BUNDLE }
            .groupBy({ it.orderId }) { it.productName }

        return rows.map { row ->
            GameOrder(
                publicId = row.getString("publicId").orEmpty(), status = row.getString("status"), total = MoneyUtil.toDecimal(row.getLong("totalPrice")),
                currency = row.getString("currency"), itemNames = names[row.getLong("id")].orEmpty().distinct(), createdAt = row.getLong("createdAt")
            )
        }
    }

    private suspend fun placeholders(request: MarketQueryEventRequest): MarketQueryEventResponse {
        val c = config()
        val client = read()
        val widgetData = widgets.widgets(setOf("recentBuyers", "topSupporters", "goals"), client)
        val goal = widgetData.getJsonArray("goals")?.takeIf { !it.isEmpty }?.getJsonObject(0)
        val players = LinkedHashMap<String, GamePlayerBalance>()

        if (c.creditsEnabled) {
            for (name in request.args?.usernames.orEmpty().asSequence().map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_USERNAME }.distinct().take(MAX_PLACEHOLDER_PLAYERS)) {
                val user = users.byUsername(name, client) ?: continue

                players[name] = GamePlayerBalance(MoneyUtil.toDecimal(credits.balance(user.id, client)))
            }
        }

        return answer(
            MarketQueryData(
                lastBuyer = widgetData.getJsonArray("recentBuyers")?.takeIf { !it.isEmpty }?.getJsonObject(0)?.getString("username"),
                topSupporter = widgetData.getJsonArray("topSupporters")?.takeIf { !it.isEmpty }?.getJsonObject(0)?.getString("username"),
                goalName = goal?.getString("name"), goalPercent = goal?.getNumber("percent")?.toDouble(), goalProgress = goal?.getNumber("progress")?.toDouble(),
                goalTarget = goal?.getNumber("target")?.toDouble(), players = players
            )
        )
    }

    /** `deliveriesQueued`: unfinished grants of the player on this server; `gifts`: paid gift orders to the player that still have such a grant (19 section 9, join notice). */
    private suspend fun pending(request: MarketQueryEventRequest, server: Server): MarketQueryEventResponse {
        val name = request.player?.username?.trim().orEmpty()

        if (name.isEmpty() || name.length > MAX_USERNAME) return MarketQueryEventResponse(accepted = false, reason = REASON_BAD_REQUEST)

        val client = read()
        val unfinished = "d.`transport` = 'MARKET_MC' AND d.`serverId` = ? AND LOWER(d.`playerUsername`) = LOWER(?) AND d.`phase` IN ('GRANT', 'RENEW') AND " +
            "d.`status` IN ('PENDING', 'WAITING_SERVER', 'SENT', 'QUEUED')"
        val queued = client.preparedQuery("SELECT COUNT(*) AS cnt FROM ${table("market_delivery")} d WHERE $unfinished")
            .execute(Tuple.of(server.id, name)).coAwait().first().getLong("cnt").toInt()
        val rows = client.preparedQuery(
            "SELECT o.`id`, o.`publicId`, o.`playerUsername`, o.`hideFromBroadcast` FROM ${table("market_order")} o WHERE o.`isGift` = 1 AND LOWER(o.`recipientUsername`) = LOWER(?) AND " +
                "o.`status` IN ('COMPLETED', 'PARTIALLY_REFUNDED') AND EXISTS (SELECT 1 FROM ${table("market_delivery")} d WHERE d.`orderId` = o.`id` AND $unfinished) " +
                "ORDER BY o.`id` DESC LIMIT $PENDING_GIFTS"
        ).execute(Tuple.of(name, server.id, name)).coAwait().toList()
        val items = if (rows.isEmpty()) emptyList() else orderItems.getByOrderIds(rows.map { it.getLong("id") }, client)
        val gifts = rows.map { row ->
            val product = items.firstOrNull {
                it.orderId == row.getLong("id") && (it.kind == com.panomc.plugins.market.db.model.OrderItemKind.PRODUCT || it.kind == com.panomc.plugins.market.db.model.OrderItemKind.BUNDLE)
            }

            // a buyer who hid the purchase from the broadcast stays anonymous to the recipient too
            GameGift(
                from = row.getString("playerUsername")?.takeIf { row.getInteger("hideFromBroadcast") == 0 && it.isNotBlank() }, productName = product?.productName.orEmpty(),
                orderPublicId = row.getString("publicId").orEmpty()
            )
        }

        return answer(MarketQueryData(deliveriesQueued = queued, gifts = gifts))
    }

    /**
     * `CATALOG`: the ACTIVE root categories (their sub-categories are inside them) and one page of the store's products, [CATALOG_PAGE_SIZE] a page, optionally
     * of the category `args.categoryId`. Prices come from the storefront read model, so what the menu shows is what a quote charges. `needsWeb` marks what the
     * chest GUI cannot sell (variants, required fields, a server choice this server is not part of, physical, subscription, credit packs, no credit price);
     * `purchasable` is the verdict of the line rules for that player (limits, cooldown, prerequisites, stock), evaluated by one quote over the page.
     */
    private suspend fun catalog(request: MarketQueryEventRequest, server: Server): MarketQueryEventResponse {
        // the store switch (04 section 1): a closed store lists nothing the menu could offer for purchase
        if (!config().storeEnabled) return MarketQueryEventResponse(accepted = false, reason = REASON_STORE_DISABLED)

        val client = read()
        val user = request.player?.username?.takeIf { it.isNotBlank() }?.let { userOf(it, client) }
        val categoryId = request.args?.categoryId
        val wanted = (request.page ?: 1).coerceAtLeast(1)
        val query = { page: Int -> ProductListQuery(category = categoryId, sort = ProductSort.PRIORITY, page = page, pageSize = CATALOG_PAGE_SIZE) }
        val viewer = StoreViewer(user?.id)
        var page = wanted
        val listing = try {
            store.products(query(page), viewer, client)
        } catch (e: PageNotFound) {
            page = 1

            store.products(query(1), viewer, client)
        } catch (e: NotFound) {
            // a category that is not visible (any more): an empty page, the menu shows "nothing here"
            return answer(MarketQueryData(categories = rootCategories(client), products = emptyList(), page = 1, totalPage = 1))
        }
        val cards = (listing.getJsonArray("products") ?: JsonArray()).map { it as JsonObject }
        val rows = if (cards.isEmpty()) emptyMap() else products.getByIds(cards.map { it.getLong("id") }, client).associateBy { it.id }
        val web = cards.associate { card -> card.getLong("id") to needsWeb(card, rows[card.getLong("id")], server.id) }
        val verdicts = purchasableOf(user, cards.filter { web[it.getLong("id")] == false && it.getBoolean("inStock") == true }.mapNotNull { rows[it.getLong("id")] }, server.id, client)
        val list = cards.map { card ->
            val id = card.getLong("id")
            val credit = card.getDouble("creditPrice")?.takeIf { it > 0.0 }
            val needs = web[id] == true || credit == null
            val verdict = when {
                user == null -> GamePurchasable(false, "LOGIN_REQUIRED")
                card.getBoolean("inStock") != true -> GamePurchasable(false, "OUT_OF_STOCK")
                else -> verdicts[id] ?: GamePurchasable(true, null)
            }

            GameProduct(
                id = id, name = card.getString("name").orEmpty(), shortDescription = card.getString("shortDescription"), creditPrice = credit, price = card.getDouble("price"),
                currency = card.getString("currency"), stockLeft = card.getInteger("stock"), icon = card.getString("icon")?.take(MAX_ICON), purchasable = verdict,
                needsWeb = needs, slug = card.getString("slug")
            )
        }

        return answer(
            MarketQueryData(categories = rootCategories(client), products = list, page = page, totalPage = listing.getLong("totalPage")?.toInt()?.coerceAtLeast(1) ?: 1)
        )
    }

    private suspend fun rootCategories(c: SqlClient): List<GameCategory> =
        categories.getAll(null, c).filter { it.status == com.panomc.plugins.market.util.MarketStatus.ACTIVE && it.parentId == null }.sortedBy { it.position }
            .map { GameCategory(id = it.id, name = it.name, icon = it.icon.take(MAX_ICON)) }

    private fun needsWeb(card: JsonObject, product: MarketProduct?, serverId: Long): Boolean {
        if (product == null) return true
        if (card.getBoolean("needsOptions") == true || card.getBoolean("physical") == true || product.physical || product.hasVariants) return true
        if (product.billingMode == BillingMode.SUBSCRIPTION || product.kind == ProductKind.CREDIT_PACK) return true

        // a product that makes the buyer choose a server: only when this server is one of the choices can the menu pick it
        return CheckoutService.buyerChoice(product.actions) && serverId !in CheckoutService.longArray(product.serverChoices)
    }

    /**
     * The verdict of the line rules for each of [candidates] (19 section 7.2 `purchasable`): the first error of a one-unit line is its `reason`. The products without a
     * prerequisite are priced in **one** quote over the page (a page is at most [CATALOG_PAGE_SIZE] lines, one catalogue load); a product with `requiredProducts` is
     * priced alone, because a prerequisite that sits in the same cart counts as owned (06 section 6) and the real purchase is a cart of one line. A block of the
     * player blocks all of them.
     */
    private suspend fun purchasableOf(user: DirectoryUser?, candidates: List<MarketProduct>, serverId: Long, c: SqlClient): Map<Long, GamePurchasable> {
        if (user == null || candidates.isEmpty()) return emptyMap()

        val verdicts = LinkedHashMap<Long, GamePurchasable>()
        val (dependent, independent) = candidates.partition { it.requiredProducts.isNotEmpty() }

        for (group in listOf(independent) + dependent.map { listOf(it) }) {
            if (group.isNotEmpty()) verdicts += quoteVerdicts(user, group, serverId, c)
        }

        return verdicts
    }

    private suspend fun quoteVerdicts(user: DirectoryUser, group: List<MarketProduct>, serverId: Long, c: SqlClient): Map<Long, GamePurchasable> =
        try {
            val lines = group.map { CartLine(it.id, 0, 1, emptyMap(), targetFor(it, serverId)) }
            val quote = checkout.quote(QuoteInput(items = lines, payWithCredits = true), QuoteCaller(user.id), c)
            val blocked = quote.messages.any { it.code == "BUYER_BLOCKED" }
            val byProduct = quote.lines.filter { it.parentLineKey == null }.associateBy { it.productId }

            group.associate { p ->
                val reason = if (blocked) "BUYER_BLOCKED" else byProduct[p.id]?.errors?.firstOrNull()

                p.id to GamePurchasable(reason == null, reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the verdict is advice: the purchase itself runs every rule again
            logger.warn("the purchasable verdict of the catalogue page could not be computed: {}", e.toString())

            emptyMap()
        }

    /** The line's `targetServerId`: this server when the product lists it among its server choices. */
    private fun targetFor(product: MarketProduct, serverId: Long): Long? = serverId.takeIf { it in CheckoutService.longArray(product.serverChoices) }

    // ===== MARKET_PURCHASE (19 section 7.3) ===============================================================================

    suspend fun purchase(request: MarketPurchaseEventRequest, server: Server): MarketPurchaseEventResponse {
        gate(request.componentVersion, request.protocol)?.let { return MarketPurchaseEventResponse(accepted = false, reason = it) }

        val username = request.player.username.trim()

        if (!OPERATION_ID.matches(request.operationId) || request.productId <= 0 || request.quantity !in CartLimits.MIN_QUANTITY..CartLimits.MAX_QUANTITY ||
            username.isEmpty() || username.length > MAX_USERNAME
        ) {
            return purchaseFailed(REASON_BAD_REQUEST)
        }

        val c = read()
        val user = users.byUsername(username, c)
        val key = "mc:${server.id}:${request.operationId}"
        val replay = user != null && orders.getByBuyerAndIdempotencyKey("u:${user.id}", key, c) != null

        // 06 section 4 step 1: the store switch comes before everything else a purchase is judged on, as on the web; a replay of an order that was placed while the
        // store was on still answers its first result (the checkout's own replay), only a new order is refused
        if (!replay && !config().storeEnabled) return purchaseFailed(REASON_STORE_DISABLED)

        if (user == null) return purchaseFailed("LOGIN_REQUIRED")

        // the pre-checks of what the chest GUI never sells; a replay skips them (the first result stands even when the product changed since)
        val product = products.getById(request.productId, c)

        if (!replay) {
            when {
                product == null || product.deletedAt != null -> return purchaseFailed("PRODUCT_UNAVAILABLE")
                product.physical -> return purchaseFailed("PHYSICAL_NOT_SUPPORTED")
                product.billingMode == BillingMode.SUBSCRIPTION -> return purchaseFailed("RECURRING_NOT_SUPPORTED")
                product.kind == ProductKind.CREDIT_PACK -> return purchaseFailed("NOT_PAYABLE_WITH_CREDITS")
            }
        }

        val checkoutRequest = CheckoutRequest(
            input = QuoteInput(
                items = listOf(CartLine(request.productId, 0, request.quantity, emptyMap(), product?.let { targetFor(it, server.id) })), payWithCredits = true,
                paymentMethodId = com.panomc.plugins.market.core.pricing.MethodInput.CREDITS
            ),
            expectedTotal = null, acceptLegal = request.confirmLegalTextId != null, legalTextId = request.confirmLegalTextId, hideFromBroadcast = false,
            idempotencyKey = key, bodyHash = hashOfParts(user.id, request.productId, request.quantity), orderLocale = orderLocale(user.username), source = OrderSource.INGAME
        )

        val result = try {
            checkout.checkout(checkoutRequest, QuoteCaller(user.id), c)
        } catch (e: PanoError) {
            return refusal(e)?.let { (code, extras) ->
                if (code == REASON_RATE_LIMITED) MarketPurchaseEventResponse(accepted = false, reason = REASON_RATE_LIMITED) else purchaseFailed(code, extras)
            } ?: throw e
        }

        val order = orders.getByPublicId(result.order.getString("publicId"), c) ?: throw IllegalStateException("order ${result.order.getString("publicId")} was just placed")

        // `ok` is told only for an order that is paid. A replay returns normally for an order that is still being completed (the first request is slow or died
        // with the hold still on) and for one that ended without payment: the first must stay "outcome unknown" (no answer, the component keeps the operationId and
        // asks again), the second is a definite refusal, never "bought".
        when (order.status) {
            OrderStatus.PENDING, OrderStatus.REVIEW -> throw IllegalStateException("order ${order.id} is ${order.status}, not paid yet")
            OrderStatus.FAILED, OrderStatus.CANCELLED, OrderStatus.EXPIRED -> return purchaseFailed(REASON_ORDER_NOT_PAYABLE)
            OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED, OrderStatus.CHARGEBACK -> Unit
        }

        if (!replay && order.status == OrderStatus.COMPLETED) {
            try {
                announce(order, orderItems.getByOrderIds(listOf(order.id), c))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("the purchase announcement of order {} failed: {}", order.id, e.toString())
            }
        }

        return MarketPurchaseEventResponse(
            accepted = true, ok = true, orderPublicId = order.publicId, creditTotal = MoneyUtil.toDecimal(order.creditAmount),
            balance = MoneyUtil.toDecimal(credits.balance(user.id, c))
        )
    }

    private fun purchaseFailed(code: String, extras: Map<String, Any?>? = null) =
        MarketPurchaseEventResponse(accepted = true, ok = false, code = code, extras = extras?.takeIf { it.isNotEmpty() })

    // ===== MARKET_ADMIN (19 section 7.4) ==================================================================================

    suspend fun admin(request: MarketAdminEventRequest, server: Server): MarketAdminEventResponse {
        gate(request.componentVersion, request.protocol)?.let { return MarketAdminEventResponse(accepted = false, reason = it) }

        if (!adminBucket.tryTake(server.id)) return MarketAdminEventResponse(accepted = false, reason = REASON_RATE_LIMITED)

        val op = request.op

        if (op !in ADMIN_OPS || !OPERATION_ID.matches(request.operationId)) return adminFailed(REASON_BAD_REQUEST)

        // the settings gate: the feature is on for this server and the sub-command is not switched off (the component checks it too)
        val settings = effectiveSettings(server.id)

        if (settings["mcAdminCommands"] != true || ADMIN_COMMAND_NAMES.getValue(op) in (settings[McSettingKeys.DISABLED_ADMIN] as List<*>)) return adminFailed("ADMIN_COMMANDS_DISABLED")

        val c = read()
        var actor: DirectoryUser? = null

        if (!request.actor.console) {
            // double authorisation, the Pano half: the sender's account must hold the market node of the operation (the component's claim "this is player X" is
            // trusted, that X may do it is not); answered before the target is looked at, so the answer never says whether the target has an account
            actor = userOf(request.actor.username, c) ?: return adminFailed("NO_PERMISSION")

            if (!allowed(actor.id, op)) return adminFailed("NO_PERMISSION")
        }

        val target = userOf(request.target.username, c) ?: return adminFailed("NO_ACCOUNT")
        val key = "mc:${server.id}:${request.operationId}"
        val who = actor?.username ?: "console"
        val note = (request.note?.let { clean(it) }?.takeIf { it.isNotEmpty() } ?: "in-game by $who on ${serverLabel(server)}").take(NOTE_MAX)

        return try {
            when (op) {
                OP_GIVE, OP_TAKE -> moveCredits(op, key, target, actor, note, request.amount, server)
                OP_SET -> setCredits(key, target, actor, note, request.amount, server)
                OP_GRANT -> grantProduct(request, key, target, actor, note, server, c)
                else -> adminPurchases(target, c)
            }
        } catch (e: IdempotencyConflict) {
            adminFailed("IDEMPOTENCY_CONFLICT")
        } catch (e: InvalidCreditAmount) {
            adminFailed("INVALID_AMOUNT")
        } catch (e: PanoError) {
            refusal(e)?.let { (code, extras) -> adminFailed(code, balance = (extras["balance"] as? Number)?.toDouble()) } ?: throw e
        }
    }

    /** The operation's market permission (19 section 7.4): `PAY` for the credit operations and `PURCHASES`, `PAY` and `OM` for `GRANT_PRODUCT` (the work breakdown's reading). */
    private suspend fun allowed(userId: Long, op: String): Boolean =
        permissions.holds(userId, MarketNode.PAYMENTS) && (op != OP_GRANT || permissions.holds(userId, MarketNode.ORDERS_MANAGE))

    private fun adminFailed(code: String, balance: Double? = null) = MarketAdminEventResponse(accepted = true, ok = false, code = code, balance = balance)

    private suspend fun moveCredits(op: String, key: String, target: DirectoryUser, actor: DirectoryUser?, note: String, amount: Double?, server: Server): MarketAdminEventResponse {
        val minor = parseCreditAmount(amount)
        val give = op == OP_GIVE
        val type = if (give) CreditTxType.GRANT else CreditTxType.REVOKE
        val result = db.tx { conn ->
            credits.lockAccounts(listOf(target.id), true, conn)

            val existing = creditTxs.getByIdempotencyKey(key, conn)

            if (existing != null) {
                if (existing.type != type || existing.userId != target.id || existing.amount + existing.shortfall != minor) throw IdempotencyConflict()
            } else if (!give) {
                // a take that cannot be covered is refused, not shortened: the admin sees the balance and asks again
                val balance = credits.balance(target.id, conn)

                if (balance < minor) throw InsufficientCredits(MoneyUtil.toDecimal(balance))
            }

            if (give) credits.grant(target.id, minor, key, actor?.id, note, conn) else credits.revoke(target.id, minor, key, actor?.id, note, conn)
        }

        if (!result.replayed && actor != null) {
            logActivity(
                if (give) GrantedMarketCreditsIngameLog(actor.id, actor.username, pluginId, target.username, minor, server.id)
                else RevokedMarketCreditsIngameLog(actor.id, actor.username, pluginId, target.username, minor, server.id)
            )
        }

        return MarketAdminEventResponse(accepted = true, ok = true, balance = MoneyUtil.toDecimal(result.userBalance))
    }

    /** `SET_CREDITS`: the difference as one `GRANT` or `REVOKE` under the key (a set that finds the balance already right writes nothing). */
    private suspend fun setCredits(key: String, target: DirectoryUser, actor: DirectoryUser?, note: String, amount: Double?, server: Server): MarketAdminEventResponse {
        val minor = if (amount != null && amount == 0.0) 0L else parseCreditAmount(amount)
        var replayed = false
        val balance = db.tx { conn ->
            credits.lockAccounts(listOf(target.id), true, conn)

            val existing = creditTxs.getByIdempotencyKey(key, conn)

            if (existing != null) {
                if ((existing.type != CreditTxType.GRANT && existing.type != CreditTxType.REVOKE) || existing.userId != target.id) throw IdempotencyConflict()

                replayed = true

                return@tx credits.balance(target.id, conn)
            }

            val current = credits.balance(target.id, conn)
            val diff = minor - current

            when {
                diff > 0 -> credits.grant(target.id, diff, key, actor?.id, note, conn).userBalance
                diff < 0 -> credits.revoke(target.id, -diff, key, actor?.id, note, conn).userBalance
                else -> current
            }
        }

        if (!replayed && actor != null) logActivity(SetMarketCreditsIngameLog(actor.id, actor.username, pluginId, target.username, minor, server.id))

        return MarketAdminEventResponse(accepted = true, ok = true, balance = MoneyUtil.toDecimal(balance))
    }

    /** `GRANT_PRODUCT`: a manual order (`source = PANEL`, `markPaid`, `runDeliveries`) of price 0 for the target; limits are enforced, `force` never. */
    private suspend fun grantProduct(
        request: MarketAdminEventRequest, key: String, target: DirectoryUser, actor: DirectoryUser?, note: String, server: Server, c: SqlClient
    ): MarketAdminEventResponse {
        val productId = request.productId?.takeIf { it > 0 } ?: return adminFailed(REASON_BAD_REQUEST)
        val quantity = request.quantity ?: 1

        if (quantity !in CartLimits.MIN_QUANTITY..CartLimits.MAX_QUANTITY) return adminFailed(REASON_BAD_REQUEST)

        val product = products.getById(productId, c)

        if (product == null || product.deletedAt != null) return adminFailed("PRODUCT_UNAVAILABLE")

        val manual = ManualOrderRequest(
            playerUsername = target.username, items = listOf(CartLine(productId, 0, quantity, emptyMap(), targetFor(product, server.id))), priceOverride = 0, markPaid = true,
            runDeliveries = true, sendMail = false, note = note, force = false, idempotencyKey = key, bodyHash = hashOfParts(target.id, productId, quantity),
            orderLocale = orderLocale(target.username)
        )
        val created = checkout.createManualOrder(manual, actor?.id, c)

        if (!created.replay && actor != null) logActivity(GrantedMarketProductIngameLog(actor.id, actor.username, pluginId, target.username, productId, created.id, server.id))

        return MarketAdminEventResponse(accepted = true, ok = true, orderPublicId = created.publicId)
    }

    private suspend fun adminPurchases(target: DirectoryUser, c: SqlClient) = MarketAdminEventResponse(accepted = true, ok = true, orders = purchasesOf(target.id, c))

    private suspend fun logActivity(log: PluginActivityLog) {
        try {
            activity(log)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the ledger or the order is committed already: a missing log line must not turn it into a failure
            logger.warn("the activity log of an in-game admin action could not be written: {}", e.toString())
        }
    }

    // ===== MARKET_ECONOMY (19 section 7.5) ================================================================================

    suspend fun economy(request: MarketEconomyEventRequest, server: Server): MarketEconomyEventResponse {
        gate(request.componentVersion, request.protocol)?.let { return MarketEconomyEventResponse(accepted = false, reason = it) }

        val op = request.op

        if (op !in ECONOMY_OPS || !ECONOMY_OPERATION_ID.matches(request.operationId)) return economyFailed(REASON_BAD_REQUEST)

        val c = read()
        val vaultOff = (effectiveSettings(server.id)[McSettingKeys.VAULT_MODE] as String) == "OFF"
        var compensation = false

        if ((vaultOff || !config().creditsEnabled) && op != ECONOMY_BALANCE) {
            // What the ledger already holds still gets its answer while a switch is off: the component settles an unknown outcome by re-sending the id and reads a
            // refusal as "not applied", which would refund (or leave without an undo) a movement the ledger did apply (value duplicated, or the two sides differing).
            when (val settled = settledWhileOff(request, op, server, c)) {
                is Settled.Answer -> return settled.response
                Settled.Compensation -> compensation = true
                null -> Unit
            }
        }

        if (!compensation) {
            // a bridge that is switched off for this server is an explicit refusal (not one of the transient reasons the component retries)
            if (vaultOff) return MarketEconomyEventResponse(accepted = false, reason = REASON_VAULT_DISABLED)
            if (!config().creditsEnabled) return economyFailed(REASON_CREDITS_DISABLED)
        }

        val user = userOf(request.player.username, c) ?: return economyFailed("NO_ACCOUNT")

        if (op == ECONOMY_BALANCE) return MarketEconomyEventResponse(accepted = true, ok = true, balance = MoneyUtil.toDecimal(credits.balance(user.id, c)))

        val minor = try {
            parseCreditAmount(request.amount)
        } catch (e: InvalidCreditAmount) {
            return economyFailed("INVALID_AMOUNT")
        }
        val key = "mc:${server.id}:${request.operationId}"
        val deposit = op == ECONOMY_DEPOSIT
        val type = if (deposit) CreditTxType.EXTERNAL_IN else CreditTxType.EXTERNAL_OUT
        val posting = Posting(
            type, key, user.id, minor, if (deposit) AccountRef.System(CreditSystemKey.EXTERNAL) else AccountRef.User(user.id),
            if (deposit) AccountRef.User(user.id) else AccountRef.System(CreditSystemKey.EXTERNAL), if (deposit) PostingPolicy.NONE else PostingPolicy.FAIL,
            note = serverLabel(server).take(NOTE_MAX)
        )

        return try {
            val result = db.tx { conn ->
                creditTxs.getByIdempotencyKey(key, conn)?.let { existing ->
                    if (existing.type != type || existing.userId != user.id || existing.amount != minor) throw IdempotencyConflict()
                }

                credits.post(posting, conn)
            }

            MarketEconomyEventResponse(accepted = true, ok = true, balance = MoneyUtil.toDecimal(result.userBalance))
        } catch (e: InsufficientCredits) {
            MarketEconomyEventResponse(accepted = true, ok = false, code = "INSUFFICIENT_CREDITS", balance = MoneyUtil.toDecimal(credits.balance(user.id, c)))
        } catch (e: IdempotencyConflict) {
            economyFailed("IDEMPOTENCY_CONFLICT")
        } catch (e: IllegalStateException) {
            // `CreditService.post`: the key belongs to another type or user
            if (e.message?.contains("idempotency key") == true) economyFailed("IDEMPOTENCY_CONFLICT") else throw e
        }
    }

    private fun economyFailed(code: String) = MarketEconomyEventResponse(accepted = true, ok = false, code = code)

    /** What [settledWhileOff] found: the answer to a replay, or a compensation that may pass the switches. */
    private sealed class Settled {
        class Answer(val response: MarketEconomyEventResponse) : Settled()

        object Compensation : Settled()
    }

    /**
     * Only called while the bridge or the credits are switched off, for a `DEPOSIT` / `WITHDRAW`. `null` = a new operation: the switch refuses it.
     * - the ledger holds a transaction under the key: the same type, user and amount is a replay and answers `ok` with the current balance; anything else under
     *   that key is `IDEMPOTENCY_CONFLICT` (07 section 3.1), never a refusal that reads as "not applied";
     * - an `<id>:undo` that is the exact opposite (type, user, amount) of the applied `mc:<serverId>:<id>` passes the switches: a compensation only reverses what the
     *   ledger already holds, and refusing it would leave the ledger and the server economy apart for good (19 section 10).
     */
    private suspend fun settledWhileOff(request: MarketEconomyEventRequest, op: String, server: Server, c: SqlClient): Settled? {
        val user = userOf(request.player.username, c) ?: return null
        val minor = try {
            parseCreditAmount(request.amount)
        } catch (e: InvalidCreditAmount) {
            return null
        }
        val type = if (op == ECONOMY_DEPOSIT) CreditTxType.EXTERNAL_IN else CreditTxType.EXTERNAL_OUT
        val key = "mc:${server.id}:${request.operationId}"

        creditTxs.getByIdempotencyKey(key, c)?.let { existing ->
            return Settled.Answer(
                if (existing.type == type && existing.userId == user.id && existing.amount == minor) {
                    MarketEconomyEventResponse(accepted = true, ok = true, balance = MoneyUtil.toDecimal(credits.balance(user.id, c)))
                } else {
                    economyFailed("IDEMPOTENCY_CONFLICT")
                }
            )
        }

        if (!request.operationId.endsWith(UNDO_SUFFIX)) return null

        val original = creditTxs.getByIdempotencyKey("mc:${server.id}:${request.operationId.removeSuffix(UNDO_SUFFIX)}", c) ?: return null
        val opposite = if (type == CreditTxType.EXTERNAL_IN) CreditTxType.EXTERNAL_OUT else CreditTxType.EXTERNAL_IN

        return if (original.type == opposite && original.userId == user.id && original.amount == minor) Settled.Compensation else null
    }

    // ===== shared =========================================================================================================

    /**
     * An error of the checkout as the component's `{code, extras}`: the code of 04 section 11 (the 409 `PRODUCT_REQUIREMENT_NOT_MET` is the line code
     * `REQUIREMENT_NOT_MET`; `INVALID_CART` is its first line code; the payment-method and shipping refusals become the codes the menu knows). `null` for a
     * server error (5xx): the outcome is unknown, so nothing definite is answered and the error propagates.
     */
    private fun refusal(e: PanoError): Pair<String, Map<String, Any?>>? {
        if (e.getStatusCode() >= 500) return null

        val body = JsonObject(e.encode())

        body.remove("result")

        val code = body.remove("error") as? String ?: return null
        val extras = body.map.toMutableMap()

        return when (code) {
            "PRODUCT_REQUIREMENT_NOT_MET" -> "REQUIREMENT_NOT_MET" to extras

            "INVALID_CART" -> {
                val lineErrors = (extras["lineErrors"] as? Map<*, *>)?.values?.firstNotNullOfOrNull { (it as? List<*>)?.firstOrNull() as? String } ?: "PRODUCT_UNAVAILABLE"

                lineErrors to emptyMap()
            }

            "EMPTY_CART" -> "PRODUCT_UNAVAILABLE" to emptyMap()

            "NOT_LOGGED_IN" -> "LOGIN_REQUIRED" to emptyMap()

            "PAYMENT_METHOD_UNAVAILABLE" -> when (val reason = extras["reason"] as? String) {
                "CREDITS_DISABLED", "NOT_PAYABLE_WITH_CREDITS" -> reason to emptyMap()
                "EXTERNAL_PRICING", "MIXED_CREDIT_NOT_SUPPORTED", "CREDITS_REQUIRED", null -> "NOT_PAYABLE_WITH_CREDITS" to emptyMap()
                else -> code to extras
            }

            "SHIPPING_ADDRESS_REQUIRED", "SHIPPING_UNAVAILABLE" -> "PHYSICAL_NOT_SUPPORTED" to emptyMap()

            "SUBSCRIPTION_MUST_BE_ALONE" -> "RECURRING_NOT_SUPPORTED" to emptyMap()

            "TOO_MANY_REQUESTS", "CODE_ATTEMPTS_LOCKED" -> REASON_RATE_LIMITED to emptyMap()

            else -> code to extras
        }
    }

    private fun hashOfParts(vararg parts: Any): String =
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun clean(text: String) = text.filter { it >= ' ' && it != '\u007f' && it != '§' }.trim()

    private fun serverLabel(server: Server): String = server.customName?.takeIf { it.isNotBlank() } ?: server.name

    private fun creditName(c: MarketConfig): String = c.creditName.trim().ifEmpty { DEFAULT_CREDIT_NAME }

    companion object {
        const val PROTOCOL = McSyncService.PROTOCOL

        const val REASON_NOT_READY = McSyncService.REASON_NOT_READY
        const val REASON_PROTOCOL = McSyncService.REASON_PROTOCOL
        const val REASON_VERSION = McSyncService.REASON_VERSION
        const val REASON_RATE_LIMITED = "RATE_LIMITED"
        const val REASON_BAD_REQUEST = "BAD_REQUEST"
        const val REASON_UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"
        const val REASON_CREDITS_DISABLED = "CREDITS_DISABLED"

        /** `storeEnabled = false` (04 section 1): a purchase answers it as a code, the catalogue as a refusal reason. */
        const val REASON_STORE_DISABLED = "STORE_DISABLED"

        /** A replay of a purchase whose order ended without being paid (`FAILED`, `CANCELLED`, `EXPIRED`). */
        const val REASON_ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE"

        /** `MARKET_ECONOMY` while the effective `mcVaultMode` is `OFF`: not one of the four reasons a component treats as transient. */
        const val REASON_VAULT_DISABLED = "VAULT_DISABLED"

        const val QUERY_BALANCE = "BALANCE"
        const val QUERY_PURCHASES = "PURCHASES"
        const val QUERY_CATALOG = "CATALOG"
        const val QUERY_PLACEHOLDERS = "PLACEHOLDERS"
        const val QUERY_PENDING = "PENDING"

        const val OP_GIVE = "GIVE_CREDITS"
        const val OP_TAKE = "TAKE_CREDITS"
        const val OP_SET = "SET_CREDITS"
        const val OP_GRANT = "GRANT_PRODUCT"
        const val OP_PURCHASES = "PURCHASES"

        val ADMIN_OPS: Set<String> = setOf(OP_GIVE, OP_TAKE, OP_SET, OP_GRANT, OP_PURCHASES)

        /** The sub-command names of `mcDisabledAdminCommands` (00 section 12) by operation. */
        val ADMIN_COMMAND_NAMES: Map<String, String> = mapOf(
            OP_GIVE to "give-credits", OP_TAKE to "take-credits", OP_SET to "set-credits", OP_GRANT to "grant-product", OP_PURCHASES to "purchases"
        )

        const val ECONOMY_DEPOSIT = "DEPOSIT"
        const val ECONOMY_WITHDRAW = "WITHDRAW"
        const val ECONOMY_BALANCE = "BALANCE"

        val ECONOMY_OPS: Set<String> = setOf(ECONOMY_DEPOSIT, ECONOMY_WITHDRAW, ECONOMY_BALANCE)

        /** The suffix of the compensation of an economy operation (`<id>:undo`, 19 section 10). */
        const val UNDO_SUFFIX = ":undo"

        /** 20 queries a second per server (19 section 7.2); 30 admin operations a minute (19 section 7.4). */
        const val QUERY_PER_SECOND = 20
        const val ADMIN_PER_MINUTE = 30

        const val CATALOG_PAGE_SIZE = 45
        const val PURCHASE_HISTORY = 10
        const val PENDING_GIFTS = 10
        const val MAX_PLACEHOLDER_PLAYERS = 100
        const val MAX_TEXT_LOCALES = 3
        const val MAX_USERNAME = 64
        const val MAX_ICON = 64
        const val NOTE_MAX = 255
        const val DEFAULT_CREDIT_NAME = "Credits"

        /** The `operationId` of a purchase or an admin operation (a UUID in practice): the order key `mc:<serverId>:<operationId>` must fit the 64 characters of the column. */
        val OPERATION_ID = Regex("[A-Za-z0-9_-]{8,40}")

        /** An economy `operationId` may carry the `:undo` suffix of a compensation (19 section 10); the ledger key column holds 128. */
        val ECONOMY_OPERATION_ID = Regex("[A-Za-z0-9_:-]{8,100}")

        private val logger = LoggerFactory.getLogger("Market:McGame")
    }
}
