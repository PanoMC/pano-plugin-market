package com.panomc.plugins.market.mc.core.feature

import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.core.wire.AdminActor
import com.panomc.plugins.market.mc.core.wire.AdminOp
import com.panomc.plugins.market.mc.core.wire.AdminTarget
import com.panomc.plugins.market.mc.core.wire.MarketAdminMessage
import com.panomc.plugins.market.mc.core.wire.MarketAdminRequest
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.MarketWire
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.core.wire.QueryOrder
import com.panomc.plugins.market.mc.core.wire.QueryType
import com.panomc.plugins.market.mc.core.wire.RefusalReason
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The text commands on every platform (19 section 9): `/store` (+ `history`), `/credits`, `/panomarket` (admin). The
 * platform adapters only translate their sender and arguments into [McSender] and call [execute]; nothing here knows
 * Bukkit, BungeeCord or Velocity, and nothing blocks: every Pano call is callback based.
 *
 * Authorisation of the admin commands is two halves (19 section 7.4): the in-game node and the feature switches are
 * checked HERE, the linked Pano account's market permission is checked by Pano (`NO_PERMISSION`). The console always
 * passes the first half. `status` and `recover` are operator diagnostics that never touch Pano state: they work when
 * `admin-commands` is off (a lost store must stay recoverable) and `recover` is console only.
 */
class MarketCommands(
    private val config: EffectiveConfig,
    private val messages: Messages,
    private val link: GameLink,
    private val control: () -> RuntimeControl?,
    private val componentVersion: String,
    private val clock: McClock = SystemMcClock,
    private val cooldownMs: Long = 1_000
) {
    private class Sub(val feature: Feature, val run: (McSender, List<String>) -> Unit)

    private val subs = ConcurrentHashMap<String, Sub>()
    private val lastUse = ConcurrentHashMap<String, Long>()

    /**
     * Adds a sub-command of `/store` or `/credits` (the chest menu of MC-06, the Vault `convert` / `deposit` of MC-07).
     * It only runs while [feature] is effectively on.
     */
    fun registerSub(command: String, sub: String, feature: Feature, run: (McSender, List<String>) -> Unit) {
        subs["${command.lowercase()}/${sub.lowercase()}"] = Sub(feature, run)
    }

    fun execute(command: String, sender: McSender, args: List<String>) {
        try {
            when (command.lowercase()) {
                "store" -> store(sender, args)
                "credits" -> credits(sender, args)
                "panomarket" -> panoMarket(sender, args)
            }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            sender.send(t(sender, Msg.ERROR_REFUSED, "reason" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    fun complete(command: String, sender: McSender, args: List<String>): List<String> {
        if (args.size != 1) {
            if (command == "panomarket" && args.size == 2 && args[0].equals("credits", true) && canAdmin(sender, "credits.give")) return filter(listOf("give", "take", "set"), args[1])
            if (command == "panomarket" && args.size == 2 && args[0].equals("recover", true) && sender.isConsole) return filter(listOf("confirm"), args[1])
            return emptyList()
        }
        val options = when (command.lowercase()) {
            "store" -> listOf("history") + subNames("store")
            "credits" -> subNames("credits")
            "panomarket" -> panoMarketOptions(sender)
            else -> emptyList()
        }
        return filter(options, args[0])
    }

    private fun filter(options: List<String>, prefix: String) = options.filter { it.startsWith(prefix, true) }.sorted()

    private fun subNames(command: String) = subs.entries.filter { it.key.startsWith("$command/") && config.enabled(it.value.feature) }.map { it.key.substringAfter('/') }

    // ---- /store ---------------------------------------------------------------------------------------------------

    private fun store(sender: McSender, args: List<String>) {
        if (!config.enabled(Feature.STORE_COMMAND)) return sender.send(t(sender, Msg.COMMAND_DISABLED))
        if (args.isEmpty()) {
            val url = config.remote?.storeUrl ?: return sender.send(t(sender, Msg.STORE_NO_LINK))
            return sender.send(t(sender, Msg.STORE_LINK, "url" to url), url)
        }
        val sub = args[0].lowercase()
        if (sub == "history") return history(sender)
        val registered = subs["store/$sub"]
        if (registered != null) {
            if (!config.enabled(registered.feature)) return sender.send(t(sender, Msg.COMMAND_DISABLED))
            return registered.run(sender, args.drop(1))
        }
        if (sub == "menu") return sender.send(t(sender, Msg.STORE_MENU_UNAVAILABLE))
        sender.send(t(sender, Msg.STORE_USAGE))
    }

    private fun history(sender: McSender) {
        if (sender.isConsole) return sender.send(t(sender, Msg.COMMAND_PLAYER_ONLY))
        if (!cooldown(sender)) return
        val request = MarketQueryRequest(componentVersion, type = QueryType.PURCHASES, player = PlayerRef(sender.name, sender.uuid), page = null, args = null)
        ask(sender, request, MarketQueryMessage::class.java, { it.accepted to it.reason }) { m ->
            val orders = m.data?.orders ?: emptyList()
            if (orders.isEmpty()) sender.send(t(sender, Msg.HISTORY_EMPTY)) else sendOrders(sender, t(sender, Msg.HISTORY_HEADER), orders)
        }
    }

    private fun sendOrders(sender: McSender, header: String, orders: List<QueryOrder>) {
        sender.send(header)
        orders.take(10).forEach { o ->
            sender.send(
                t(
                    sender, Msg.HISTORY_LINE,
                    "id" to o.publicId, "items" to o.itemNames.joinToString(", "), "total" to number(o.total),
                    "currency" to o.currency, "status" to o.status, "date" to date(o.createdAt)
                )
            )
        }
    }

    // ---- /credits -------------------------------------------------------------------------------------------------

    private fun credits(sender: McSender, args: List<String>) {
        if (!config.enabled(Feature.CREDITS_COMMAND)) return sender.send(t(sender, Msg.COMMAND_DISABLED))
        if (args.isNotEmpty()) {
            val registered = subs["credits/${args[0].lowercase()}"]
            if (registered == null) return sender.send(t(sender, Msg.CREDITS_USAGE))
            if (!config.enabled(registered.feature)) return sender.send(t(sender, Msg.COMMAND_DISABLED))
            return registered.run(sender, args.drop(1))
        }
        if (sender.isConsole) return sender.send(t(sender, Msg.COMMAND_PLAYER_ONLY))
        if (!cooldown(sender)) return
        val request = MarketQueryRequest(componentVersion, type = QueryType.BALANCE, player = PlayerRef(sender.name, sender.uuid), page = null, args = null)
        ask(sender, request, MarketQueryMessage::class.java, { it.accepted to it.reason }) { m ->
            val d = m.data
            if (d == null || d.registered == false) {
                val url = config.remote?.links?.registerUrl()
                sender.send(t(sender, Msg.CREDITS_UNREGISTERED, "url" to (url ?: "")), url)
            } else {
                sender.send(t(sender, Msg.CREDITS_BALANCE, "balance" to number(d.balance ?: 0.0), "credit" to (d.creditName ?: config.remote?.creditName ?: "")))
            }
        }
    }

    // ---- /panomarket ----------------------------------------------------------------------------------------------

    private fun panoMarketOptions(sender: McSender): List<String> {
        val out = ArrayList<String>()
        if (canAdmin(sender, "credits.give") || canAdmin(sender, "credits.take") || canAdmin(sender, "credits.set")) out.add("credits")
        if (canAdmin(sender, "grant")) out.add("grant")
        if (canAdmin(sender, "purchases")) out.add("purchases")
        if (sender.isConsole || sender.hasPermission(NODE_STATUS)) out.add("status")
        if (sender.isConsole) out.add("recover")
        return out
    }

    private fun canAdmin(sender: McSender, node: String) = sender.isConsole || sender.hasPermission("$NODE_PREFIX$node")

    private fun panoMarket(sender: McSender, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        when (sub) {
            "status" -> status(sender)
            "recover" -> recover(sender, args.getOrNull(1))
            "credits" -> adminCredits(sender, args.drop(1))
            "grant" -> adminGrant(sender, args.drop(1))
            "purchases" -> adminPurchases(sender, args.drop(1))
            else -> help(sender)
        }
    }

    private fun help(sender: McSender) {
        val options = panoMarketOptions(sender)
        if (options.isEmpty()) return sender.send(t(sender, Msg.COMMAND_NO_PERMISSION))
        sender.send(t(sender, Msg.ADMIN_HELP_HEADER))
        if ("credits" in options) sender.send(t(sender, Msg.ADMIN_HELP_CREDITS))
        if ("grant" in options) sender.send(t(sender, Msg.ADMIN_HELP_GRANT))
        if ("purchases" in options) sender.send(t(sender, Msg.ADMIN_HELP_PURCHASES))
        if ("status" in options) sender.send(t(sender, Msg.ADMIN_HELP_STATUS))
        if ("recover" in options) sender.send(t(sender, Msg.ADMIN_HELP_RECOVER))
    }

    /** `false` (and the reason is told) when the sender may not run an admin sub-command. */
    private fun adminAllowed(sender: McSender, node: String, disabledName: String): Boolean {
        if (!canAdmin(sender, node)) {
            sender.send(t(sender, Msg.COMMAND_NO_PERMISSION))
            return false
        }
        if (config.remote == null) {
            // The panel settings are unknown (start-up, Pano unreachable, MARKET_CONFIG refused): money ops stay closed.
            sender.send(t(sender, Msg.ADMIN_SETTINGS_NOT_LOADED))
            return false
        }
        if (!config.enabled(Feature.ADMIN_COMMANDS) || config.adminCommandDisabled(disabledName)) {
            sender.send(t(sender, Msg.COMMAND_DISABLED))
            return false
        }
        return true
    }

    private fun actor(sender: McSender) = if (sender.isConsole) AdminActor(true, null, null) else AdminActor(false, sender.name, sender.uuid)

    private fun adminCredits(sender: McSender, args: List<String>) {
        val verb = args.firstOrNull()?.lowercase()
        val (op, node, disabled) = when (verb) {
            "give" -> Triple(AdminOp.GIVE_CREDITS, "credits.give", "give-credits")
            "take" -> Triple(AdminOp.TAKE_CREDITS, "credits.take", "take-credits")
            "set" -> Triple(AdminOp.SET_CREDITS, "credits.set", "set-credits")
            else -> return help(sender)
        }
        if (!adminAllowed(sender, node, disabled)) return
        val player = args.getOrNull(1)?.takeIf { PLAYER.matches(it) } ?: return sender.send(t(sender, Msg.ADMIN_BAD_PLAYER))
        val amount = parseAmount(args.getOrNull(2), allowZero = op == AdminOp.SET_CREDITS) ?: return sender.send(t(sender, Msg.ADMIN_BAD_AMOUNT))
        val note = args.drop(3).joinToString(" ").let { ChatFormat.plainValue(it).trim() }.take(MAX_NOTE).ifEmpty { null }
        val request = MarketAdminRequest(componentVersion, MarketWire.PROTOCOL, UUID.randomUUID().toString(), op, actor(sender), AdminTarget(player), amount.toDouble(), null, null, note)
        askAdmin(sender, request, player) { m ->
            val key = when (op) {
                AdminOp.GIVE_CREDITS -> Msg.ADMIN_GAVE
                AdminOp.TAKE_CREDITS -> Msg.ADMIN_TOOK
                else -> Msg.ADMIN_SET
            }
            sender.send(t(sender, key, "player" to player, "amount" to number(amount.toDouble()), "balance" to number(m.balance ?: 0.0)))
        }
    }

    private fun adminGrant(sender: McSender, args: List<String>) {
        if (!adminAllowed(sender, "grant", "grant-product")) return
        val player = args.getOrNull(0)?.takeIf { PLAYER.matches(it) } ?: return sender.send(t(sender, Msg.ADMIN_BAD_PLAYER))
        val product = args.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return sender.send(t(sender, Msg.ADMIN_BAD_PRODUCT))
        val quantity = if (args.size > 2) (args[2].toIntOrNull()?.takeIf { it in 1..MAX_QUANTITY } ?: return sender.send(t(sender, Msg.ADMIN_BAD_QUANTITY))) else 1
        val request = MarketAdminRequest(
            componentVersion, MarketWire.PROTOCOL, UUID.randomUUID().toString(), AdminOp.GRANT_PRODUCT, actor(sender), AdminTarget(player), null, product, quantity, null
        )
        askAdmin(sender, request, player) { m ->
            sender.send(t(sender, Msg.ADMIN_GRANT_DONE, "player" to player, "product" to product, "quantity" to quantity, "order" to (m.orderPublicId ?: "")))
            // The delivery of the manual order reaches this server with the next MARKET_SYNC.
            control()?.syncSoon()
        }
    }

    private fun adminPurchases(sender: McSender, args: List<String>) {
        if (!adminAllowed(sender, "purchases", "purchases")) return
        val player = args.getOrNull(0)?.takeIf { PLAYER.matches(it) } ?: return sender.send(t(sender, Msg.ADMIN_BAD_PLAYER))
        if (!cooldown(sender)) return
        val request = MarketAdminRequest(
            componentVersion, MarketWire.PROTOCOL, UUID.randomUUID().toString(), AdminOp.PURCHASES, actor(sender), AdminTarget(player), null, null, null, null
        )
        askAdmin(sender, request, player) { m ->
            val orders = m.orders ?: emptyList()
            if (orders.isEmpty()) sender.send(t(sender, Msg.ADMIN_PURCHASES_EMPTY, "player" to player))
            else sendOrders(sender, t(sender, Msg.ADMIN_PURCHASES_HEADER, "player" to player), orders)
        }
    }

    /**
     * One `MARKET_ADMIN` round trip. A reply that never came (timeout) is an UNKNOWN outcome, told as such: the credits or
     * the order may have been applied, so the admin is asked to check before repeating the command.
     */
    private fun askAdmin(sender: McSender, request: MarketAdminRequest, player: String, onOk: (MarketAdminMessage) -> Unit) {
        ask(sender, request, MarketAdminMessage::class.java, { it.accepted to it.reason }, onNoAnswer = { sender.send(t(sender, Msg.ADMIN_UNKNOWN_OUTCOME)) }) { m ->
            if (m.ok == true) return@ask onOk(m)
            val key = when (m.code) {
                "NO_PERMISSION" -> Msg.ADMIN_NO_PERMISSION
                "NO_ACCOUNT" -> Msg.ADMIN_NO_ACCOUNT
                "INSUFFICIENT_CREDITS" -> Msg.ADMIN_INSUFFICIENT
                "CREDITS_DISABLED" -> Msg.ADMIN_CREDITS_OFF
                else -> Msg.ADMIN_FAILED
            }
            sender.send(t(sender, key, "player" to player, "code" to (m.code ?: "UNKNOWN")))
        }
    }

    private fun status(sender: McSender) {
        if (!sender.isConsole && !sender.hasPermission(NODE_STATUS)) return sender.send(t(sender, Msg.COMMAND_NO_PERMISSION))
        val s = control()?.status()
        sender.send(t(sender, Msg.STATUS_HEADER))
        sender.send(t(sender, Msg.STATUS_COMPONENT, "version" to componentVersion))
        if (s == null) return sender.send(t(sender, Msg.STATUS_STORE_FAILED))
        sender.send(t(sender, if (s.connected) Msg.STATUS_CONNECTION_UP else Msg.STATUS_CONNECTION_DOWN))
        val e = s.engine
        sender.send(
            when (e.accepted) {
                null -> t(sender, Msg.STATUS_VERSION_UNKNOWN)
                true -> t(sender, Msg.STATUS_VERSION_OK, "market" to (e.marketVersion ?: "?"))
                false -> t(sender, Msg.STATUS_VERSION_REFUSED, "reason" to (e.rejectionReason ?: "?"), "market" to (e.marketVersion ?: "?"), "version" to componentVersion)
            }
        )
        sender.send(t(sender, Msg.STATUS_QUEUE, "queued" to e.queued, "running" to e.running, "unacked" to e.unacked))
        sender.send(t(sender, if (s.failed) Msg.STATUS_STORE_FAILED else if (e.storeHealthy) Msg.STATUS_STORE_OK else Msg.STATUS_STORE_BROKEN))
        if (e.recoveryMode) sender.send(t(sender, Msg.STATUS_RECOVERY))
        val last = e.lastSyncAt
        sender.send(if (last == null) t(sender, Msg.STATUS_SYNC_NEVER) else t(sender, Msg.STATUS_SYNC_AGO, "seconds" to ((clock.now() - last) / 1000).coerceAtLeast(0)))
        val remote = config.remote
        config.local.error?.let { sender.send(t(sender, Msg.STATUS_CONFIG_ERROR, "error" to it)) }
        sender.send(if (remote == null) t(sender, Msg.STATUS_CONFIG_WAITING) else t(sender, Msg.STATUS_CONFIG_LOADED, "hash" to remote.configHash.take(8)))
    }

    private fun recover(sender: McSender, arg: String?) {
        if (!sender.isConsole) return sender.send(t(sender, Msg.RECOVER_CONSOLE_ONLY))
        val c = control() ?: return sender.send(t(sender, Msg.RECOVER_NOT_NEEDED))
        if (arg != null && !arg.equals("confirm", true)) return help(sender)
        if (arg == null) {
            c.recoveryPreview().whenComplete { preview, error ->
                if (error != null || preview == null) return@whenComplete sender.send(t(sender, Msg.RECOVER_NOT_NEEDED))
                sender.send(t(sender, Msg.RECOVER_PREVIEW, "count" to preview.salvagedUnackedKeys))
                sender.send(t(sender, Msg.RECOVER_CONFIRM_HINT))
            }
        } else {
            c.confirmRecovery().whenComplete { was, error ->
                sender.send(t(sender, if (error == null && was == true) Msg.RECOVER_DONE else Msg.RECOVER_NOT_NEEDED))
            }
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------------------------

    /** One round trip to Pano with the common failure messages; [onNoAnswer] replaces the plain timeout text. */
    private fun <R : PlatformMessageResponse> ask(
        sender: McSender,
        request: com.panomc.plugins.market.mc.core.wire.MarketRequest,
        type: Class<R>,
        accepted: (R) -> Pair<Boolean, String?>,
        onNoAnswer: () -> Unit = { sender.send(t(sender, Msg.ERROR_TIMEOUT)) },
        onOk: (R) -> Unit
    ) {
        if (!link.connected()) return sender.send(t(sender, Msg.ERROR_NOT_CONNECTED))
        link.request(request, type) { answer ->
            try {
                if (answer == null) return@request onNoAnswer()
                val (ok, reason) = accepted(answer)
                if (!ok) return@request sender.send(refusal(sender, reason))
                onOk(answer)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                sender.send(t(sender, Msg.ERROR_REFUSED, "reason" to (e.message ?: e.javaClass.simpleName)))
            }
        }
    }

    private fun refusal(sender: McSender, reason: String?): String = when (reason) {
        RefusalReason.RATE_LIMITED -> t(sender, Msg.ERROR_RATE_LIMITED)
        RefusalReason.MARKET_NOT_READY -> t(sender, Msg.ERROR_NOT_READY)
        RefusalReason.VERSION_MISMATCH, RefusalReason.PROTOCOL_UNSUPPORTED -> t(sender, Msg.ERROR_VERSION)
        else -> t(sender, Msg.ERROR_REFUSED, "reason" to (reason ?: "UNKNOWN"))
    }

    /** One query command per sender and [cooldownMs]; the console is never throttled. */
    private fun cooldown(sender: McSender): Boolean {
        if (sender.isConsole) return true
        val now = clock.now()
        val key = sender.name.lowercase()
        val previous = lastUse[key]
        if (previous != null && now - previous < cooldownMs) {
            sender.send(t(sender, Msg.COMMAND_COOLDOWN))
            return false
        }
        lastUse[key] = now
        if (lastUse.size > 1024) lastUse.entries.removeIf { now - it.value > 60_000 }
        return true
    }

    private fun t(sender: McSender, key: String, vararg args: Pair<String, Any?>) = messages.text(key, sender.locale, *args)

    private fun parseAmount(raw: String?, allowZero: Boolean): BigDecimal? {
        if (raw == null || !AMOUNT.matches(raw)) return null
        val v = BigDecimal(raw)
        return if (v.signum() > 0 || (allowZero && v.signum() == 0)) v else null
    }

    companion object {
        const val NODE_PREFIX = "panomarket.admin."
        const val NODE_STATUS = "panomarket.admin.status"

        /** All nodes a permission plugin may grant, for the platforms that declare them (Spigot `addPermission`). */
        val NODES = listOf(
            "credits.give", "credits.take", "credits.set", "grant", "purchases", "status"
        ).map { "$NODE_PREFIX$it" }

        private val PLAYER = Regex("[A-Za-z0-9_.]{1,32}")
        private val AMOUNT = Regex("\\d{1,12}(\\.\\d{1,2})?")
        private const val MAX_NOTE = 200
        private const val MAX_QUANTITY = 1000
        private val DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

        fun number(v: Double): String = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        fun date(epochMs: Long): String = if (epochMs <= 0) "-" else DATE.format(Instant.ofEpochMilli(epochMs))
    }
}
