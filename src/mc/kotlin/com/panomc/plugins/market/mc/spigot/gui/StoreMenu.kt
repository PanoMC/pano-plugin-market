package com.panomc.plugins.market.mc.spigot.gui

import com.panomc.plugins.market.mc.core.feature.ChatFormat
import com.panomc.plugins.market.mc.core.feature.EffectiveConfig
import com.panomc.plugins.market.mc.core.feature.Feature
import com.panomc.plugins.market.mc.core.feature.GameLink
import com.panomc.plugins.market.mc.core.feature.MarketCommands
import com.panomc.plugins.market.mc.core.feature.Messages
import com.panomc.plugins.market.mc.core.feature.Msg
import com.panomc.plugins.market.mc.core.feature.RuntimeControl
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.core.wire.MarketPurchaseMessage
import com.panomc.plugins.market.mc.core.wire.MarketPurchaseRequest
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.mc.core.wire.QueryArgs
import com.panomc.plugins.market.mc.core.wire.QueryCategory
import com.panomc.plugins.market.mc.core.wire.QueryProduct
import com.panomc.plugins.market.mc.core.wire.QueryType
import com.panomc.plugins.market.mc.core.wire.RefusalReason
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// The chest-GUI store flow (19 section 9, "store menu"): categories -> products -> confirm -> MARKET_PURCHASE.
// This file has no Bukkit types: the model decides what each screen shows and what a click does, [MenuView] draws it.
// That keeps the money path (operation ids, double click, close, unknown outcomes) testable without a server.

/** A material name as the player's server knows it (`DIAMOND`); the view falls back to a plain item when it is unknown. */
typealias MaterialName = String

sealed class MenuAction {
    /** `null` = all products. */
    data class OpenCategory(val categoryId: Long?) : MenuAction()
    data class Product(val productId: Long) : MenuAction()
    data class WebLink(val url: String, val productName: String) : MenuAction()
    object NextPage : MenuAction()
    object PreviousPage : MenuAction()
    object Back : MenuAction()
    object Confirm : MenuAction()
    object Cancel : MenuAction()
    object Close : MenuAction()
}

data class MenuItem(
    val slot: Int,
    val material: MaterialName,
    val title: String,
    val lore: List<String> = emptyList(),
    val action: MenuAction? = null
)

data class MenuScreen(val rows: Int, val items: List<MenuItem>) {
    val size: Int get() = rows * 9
}

/** What the model needs from the platform: draw, close, chat. Every method may be called from any thread. */
interface MenuView {
    /** Opens the menu for [username] or replaces the contents of the open one. */
    fun show(username: String, screen: MenuScreen)

    /** Closes the menu of [username] if one is open (the model has already forgotten the flow). */
    fun close(username: String)

    fun message(username: String, text: String, openUrl: String? = null)
}

class MenuPlayer(val name: String, val uuid: String?, val locale: String?)

/**
 * One chest-GUI flow per player. The rules (19 section 9):
 * - one open flow per player: opening again replaces the old flow (its late answers are dropped);
 * - closing the menu cancels the flow: a late catalog answer draws nothing and no purchase can follow;
 * - a click while an answer is awaited does nothing (no double navigation);
 * - a purchase is sent once per `operationId`: while one is in flight for the player (even after closing the menu) every
 *   other purchase attempt is refused, and after an answer that left the outcome unknown (no reply) the same product keeps
 *   the same `operationId` for ten minutes, so a retry gets Pano's first result (`uq_buyer_idem`) and never a second order;
 * - items that need the web (no credit price, variants, a physical product ...) hand out the link instead of a buy button.
 */
open class StoreMenu(
    private val config: EffectiveConfig,
    private val messages: Messages,
    private val link: GameLink,
    private val view: MenuView,
    private val control: () -> RuntimeControl?,
    private val uuidOf: (String) -> String?,
    private val componentVersion: String,
    private val clock: McClock = SystemMcClock,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
    private val openCooldownMs: Long = 1_000
) {
    private enum class Screen { LOADING, CATEGORIES, PRODUCTS, CONFIRM }

    private class Session(val player: MenuPlayer, val key: String) {
        var screen = Screen.LOADING

        /** Bumped by every request that draws something; an answer for an older value is dropped. */
        var generation = 0L
        var awaiting = false
        var categories: List<QueryCategory> = emptyList()
        var categoryId: Long? = null
        var categoryName: String? = null
        var serverPage = 1
        var totalPage = 1
        var products: List<QueryProduct> = emptyList()
        var local = 0
        var actions: Map<Int, MenuAction> = emptyMap()
        var product: QueryProduct? = null
        var balance: Double? = null
        var legalTextId: Long? = null
    }

    private class Unknown(val operationId: String, val at: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val buying = ConcurrentHashMap.newKeySet<String>()
    private val unknown = ConcurrentHashMap<String, Unknown>()
    private val lastOpen = ConcurrentHashMap<String, Long>()

    private val rows: Int get() = config.local.menuRows.coerceIn(2, 6)
    private val pageSize: Int get() = (rows - 1) * 9
    private val creditName: String get() = config.remote?.creditName ?: ""

    fun hasFlow(username: String): Boolean = sessions.containsKey(username.lowercase())

    // ---- entry points ---------------------------------------------------------------------------------------------

    /** `/store menu`. */
    fun open(player: MenuPlayer) {
        val key = player.name.lowercase()
        if (!config.enabled(Feature.STORE_MENU)) return say(player, Msg.COMMAND_DISABLED)
        val now = clock.now()
        val previousOpen = lastOpen[key]
        if (previousOpen != null && now - previousOpen < openCooldownMs) return say(player, Msg.COMMAND_COOLDOWN)
        lastOpen[key] = now
        if (lastOpen.size > 1024) lastOpen.entries.removeIf { now - it.value > 60_000 }
        if (!link.connected()) return say(player, Msg.ERROR_NOT_CONNECTED)
        val session = Session(player, key)
        val old = sessions.put(key, session)
        if (old != null) synchronized(old) { old.generation++ }
        synchronized(session) {
            session.screen = Screen.LOADING
            draw(session, loadingScreen(player))
        }
        requestCatalog(session, categoryId = null, page = 1, chooseCategories = true)
    }

    /** A click on [slot] of the menu of [username] (the view only forwards clicks inside the menu inventory). */
    open fun click(username: String, slot: Int) {
        val session = sessions[username.lowercase()] ?: return
        val action: MenuAction
        synchronized(session) {
            if (sessions[session.key] !== session) return
            action = session.actions[slot] ?: return
            if (session.awaiting && action != MenuAction.Close) return
        }
        when (action) {
            is MenuAction.OpenCategory -> {
                synchronized(session) {
                    session.categoryName = session.categories.firstOrNull { it.id == action.categoryId }?.name
                }
                requestCatalog(session, action.categoryId, 1, chooseCategories = false)
            }
            is MenuAction.Product -> openConfirm(session, action.productId)
            is MenuAction.WebLink -> sayTo(session.player, Msg.MENU_WEB_LINK, action.url, "product" to action.productName, "url" to action.url)
            MenuAction.NextPage -> turn(session, +1)
            MenuAction.PreviousPage -> turn(session, -1)
            MenuAction.Back -> back(session)
            MenuAction.Confirm -> confirm(session)
            MenuAction.Cancel -> synchronized(session) { if (sessions[session.key] === session) showProducts(session) }
            MenuAction.Close -> end(session, closeView = true)
        }
    }

    /** The player closed the inventory (or left): the flow is cancelled. Safe to call for a flow that is already gone. */
    open fun closed(username: String) {
        val session = sessions[username.lowercase()] ?: return
        end(session, closeView = false)
    }

    /** The plugin is shutting down: every menu closes, nothing more is sent. */
    fun shutdown() {
        sessions.values.toList().forEach { end(it, closeView = true) }
    }

    // ---- catalog --------------------------------------------------------------------------------------------------

    private fun requestCatalog(session: Session, categoryId: Long?, page: Int, chooseCategories: Boolean, localAfter: Int? = null) {
        val generation: Long
        synchronized(session) {
            if (sessions[session.key] !== session) return
            generation = ++session.generation
            session.awaiting = true
            if (!chooseCategories) draw(session, loadingScreen(session.player))
        }
        if (!link.connected()) {
            sayTo(session.player, Msg.ERROR_NOT_CONNECTED)
            return end(session, closeView = true)
        }
        val request = MarketQueryRequest(
            componentVersion, type = QueryType.CATALOG, player = PlayerRef(session.player.name, session.player.uuid ?: uuidOf(session.player.name)),
            page = page, args = categoryId?.let { QueryArgs(categoryId = it) }
        )
        link.request(request, MarketQueryMessage::class.java) { answer ->
            synchronized(session) {
                if (sessions[session.key] !== session || session.generation != generation) return@request
                session.awaiting = false
                if (answer == null) return@request failFlow(session, Msg.ERROR_TIMEOUT)
                if (!answer.accepted) return@request failFlow(session, refusalKey(answer.reason), "reason" to (answer.reason ?: "UNKNOWN"))
                val data = answer.data ?: return@request failFlow(session, Msg.ERROR_REFUSED, "reason" to "NO_DATA")
                session.products = data.products ?: emptyList()
                session.serverPage = data.page ?: page
                session.totalPage = (data.totalPage ?: 1).coerceAtLeast(1)
                session.categoryId = categoryId
                if (chooseCategories) {
                    session.categories = data.categories ?: emptyList()
                    session.categoryName = null
                }
                session.local = if (localAfter == LAST) lastLocalPage(session) else localAfter ?: 0
                if (chooseCategories && session.categories.isNotEmpty()) showCategories(session) else showProducts(session)
            }
        }
    }

    private fun failFlow(session: Session, key: String, vararg args: Pair<String, Any?>) {
        sayTo(session.player, key, null, *args)
        end(session, closeView = true)
    }

    private fun refusalKey(reason: String?): String = when (reason) {
        RefusalReason.RATE_LIMITED -> Msg.ERROR_RATE_LIMITED
        RefusalReason.MARKET_NOT_READY -> Msg.ERROR_NOT_READY
        RefusalReason.VERSION_MISMATCH, RefusalReason.PROTOCOL_UNSUPPORTED -> Msg.ERROR_VERSION
        else -> Msg.ERROR_REFUSED
    }

    // ---- screens --------------------------------------------------------------------------------------------------

    private fun t(p: MenuPlayer, key: String, vararg args: Pair<String, Any?>) = messages.text(key, p.locale, *args)

    private fun base() = (rows - 1) * 9

    private fun loadingScreen(p: MenuPlayer): MenuScreen =
        MenuScreen(rows, listOf(MenuItem(0, "PAPER", t(p, Msg.MENU_LOADING)), closeItem(p, base())))

    private fun closeItem(p: MenuPlayer, base: Int) = MenuItem(base + 5, "BARRIER", t(p, Msg.MENU_NAV_CLOSE), action = MenuAction.Close)

    /** Caller holds the session lock. */
    private fun showCategories(session: Session) {
        val p = session.player
        session.screen = Screen.CATEGORIES
        session.product = null
        val items = ArrayList<MenuItem>()
        items.add(MenuItem(0, "CHEST", t(p, Msg.MENU_ALL), action = MenuAction.OpenCategory(null)))
        session.categories.take(pageSize - 1).forEachIndexed { i, c ->
            items.add(MenuItem(i + 1, material(c.icon, "BOOK"), "§f" + clean(c.name), action = MenuAction.OpenCategory(c.id)))
        }
        items.add(closeItem(p, base()))
        draw(session, MenuScreen(rows, items))
    }

    /** Caller holds the session lock. */
    private fun showProducts(session: Session) {
        val p = session.player
        session.screen = Screen.PRODUCTS
        session.product = null
        session.legalTextId = null
        val items = ArrayList<MenuItem>()
        if (session.products.isEmpty()) items.add(MenuItem(4, "PAPER", t(p, Msg.MENU_EMPTY)))
        pageSlice(session).forEachIndexed { i, product -> items.add(productItem(session, i, product)) }
        val b = base()
        if (hasPrevious(session)) items.add(MenuItem(b, "ARROW", t(p, Msg.MENU_NAV_PREV), action = MenuAction.PreviousPage))
        if (hasNext(session)) items.add(MenuItem(b + 8, "ARROW", t(p, Msg.MENU_NAV_NEXT), action = MenuAction.NextPage))
        if (session.categories.isNotEmpty()) items.add(MenuItem(b + 3, "ARROW", t(p, Msg.MENU_NAV_BACK), action = MenuAction.Back))
        items.add(MenuItem(b + 4, "PAPER", t(p, Msg.MENU_NAV_PAGE, "page" to pageLabel(session)), session.categoryName?.let { listOf("§f" + clean(it)) } ?: emptyList()))
        items.add(closeItem(p, b))
        draw(session, MenuScreen(rows, items))
    }

    private fun pageSlice(session: Session): List<QueryProduct> = session.products.drop(session.local * pageSize).take(pageSize)

    private fun subPages(session: Session) = ((session.products.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    private fun lastLocalPage(session: Session) = subPages(session) - 1
    private fun hasPrevious(session: Session) = session.local > 0 || session.serverPage > 1
    private fun hasNext(session: Session) = session.local + 1 < subPages(session) || session.serverPage < session.totalPage

    private fun pageLabel(session: Session): String {
        val sub = subPages(session)
        return if (sub > 1) "${session.serverPage}/${session.totalPage} (${session.local + 1}/$sub)" else "${session.serverPage}/${session.totalPage}"
    }

    private fun productItem(session: Session, index: Int, product: QueryProduct): MenuItem {
        val p = session.player
        val lore = ArrayList<String>()
        product.shortDescription?.takeIf { it.isNotBlank() }?.let { lore.add("§7" + clean(it).take(120)) }
        val webOnly = product.needsWeb || product.creditPrice == null
        if (product.creditPrice != null) lore.add(t(p, Msg.MENU_PRODUCT_PRICE, "price" to MarketCommands.number(product.creditPrice), "credit" to creditName))
        if (product.price != null && product.currency != null) {
            lore.add(t(p, Msg.MENU_PRODUCT_MONEY, "price" to MarketCommands.number(product.price), "currency" to product.currency))
        }
        product.stockLeft?.let { lore.add(t(p, Msg.MENU_PRODUCT_STOCK, "count" to it)) }
        val action: MenuAction?
        if (webOnly) {
            lore.add(t(p, Msg.MENU_PRODUCT_WEB))
            action = MenuAction.WebLink(productUrl(product), product.name)
        } else if (!product.purchasable.ok) {
            lore.add(t(p, Msg.MENU_PRODUCT_UNAVAILABLE, "reason" to ChatFormat.strip(codeMessage(p, product.purchasable.reason))))
            action = null
        } else {
            lore.add(t(p, Msg.MENU_PRODUCT_BUY))
            action = MenuAction.Product(product.id)
        }
        return MenuItem(index, material(product.icon, "PAPER"), "§f" + clean(product.name), lore, action)
    }

    /** `storeUrl/store/<slug>`, the store itself when Pano sent no slug (19 section 7.3). */
    private fun productUrl(product: QueryProduct): String {
        val base = config.remote?.storeUrl?.trimEnd('/') ?: return ""
        val slug = product.slug?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' } }
        return if (slug != null) "$base/store/$slug" else "$base/store"
    }

    private fun clean(s: String) = ChatFormat.plainValue(s)

    private fun material(icon: String?, fallback: String): MaterialName {
        val name = icon?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')
        return if (name != null && name.matches(MATERIAL)) name else fallback
    }

    // ---- paging ---------------------------------------------------------------------------------------------------

    private fun turn(session: Session, direction: Int) {
        val fetchPage: Int
        val localAfter: Int
        synchronized(session) {
            if (sessions[session.key] !== session || session.screen != Screen.PRODUCTS) return
            if (direction > 0) {
                if (session.local + 1 < subPages(session)) {
                    session.local++
                    return showProducts(session)
                }
                if (session.serverPage >= session.totalPage) return
                fetchPage = session.serverPage + 1
                localAfter = 0
            } else {
                if (session.local > 0) {
                    session.local--
                    return showProducts(session)
                }
                if (session.serverPage <= 1) return
                fetchPage = session.serverPage - 1
                localAfter = LAST
            }
        }
        requestCatalog(session, session.categoryId, fetchPage, chooseCategories = false, localAfter = localAfter)
    }

    private fun back(session: Session) {
        synchronized(session) {
            if (sessions[session.key] !== session) return
            when (session.screen) {
                Screen.PRODUCTS -> {
                    if (session.categories.isEmpty()) return
                    session.generation++
                    showCategories(session)
                }
                Screen.CONFIRM -> showProducts(session)
                else -> Unit
            }
        }
    }

    // ---- confirm and purchase -------------------------------------------------------------------------------------

    private fun openConfirm(session: Session, productId: Long) {
        val generation: Long
        val product: QueryProduct
        synchronized(session) {
            if (sessions[session.key] !== session || session.screen != Screen.PRODUCTS) return
            product = session.products.firstOrNull { it.id == productId } ?: return
            if (!product.purchasable.ok || product.needsWeb || product.creditPrice == null) return
            generation = ++session.generation
            session.awaiting = true
            session.product = product
            session.legalTextId = null
            draw(session, loadingScreen(session.player))
        }
        val request = MarketQueryRequest(
            componentVersion, type = QueryType.BALANCE, player = PlayerRef(session.player.name, session.player.uuid ?: uuidOf(session.player.name)), page = null, args = null
        )
        link.request(request, MarketQueryMessage::class.java) { answer ->
            synchronized(session) {
                if (sessions[session.key] !== session || session.generation != generation) return@request
                session.awaiting = false
                if (answer == null) return@request failFlow(session, Msg.ERROR_TIMEOUT)
                if (!answer.accepted) return@request failFlow(session, refusalKey(answer.reason), "reason" to (answer.reason ?: "UNKNOWN"))
                val data = answer.data
                if (data == null || data.registered == false) {
                    val url = registerUrl()
                    sayTo(session.player, Msg.MENU_REGISTER, url, "url" to (url ?: ""))
                    return@request end(session, closeView = true)
                }
                session.balance = data.balance ?: 0.0
                showConfirm(session, product)
            }
        }
    }

    private fun registerUrl(): String? = config.remote?.storeUrl?.trimEnd('/')?.let { "$it/register" }

    /** Caller holds the session lock. */
    private fun showConfirm(session: Session, product: QueryProduct, processing: Boolean = false) {
        val p = session.player
        session.screen = Screen.CONFIRM
        val price = product.creditPrice ?: 0.0
        val balance = session.balance ?: 0.0
        val enough = balance + EPSILON >= price
        val lore = ArrayList<String>()
        lore.add(t(p, Msg.MENU_PRODUCT_PRICE, "price" to MarketCommands.number(price), "credit" to creditName))
        lore.add(t(p, Msg.MENU_CONFIRM_BALANCE, "balance" to MarketCommands.number(balance), "credit" to creditName))
        if (enough) lore.add(t(p, Msg.MENU_CONFIRM_AFTER, "after" to MarketCommands.number(balance - price), "credit" to creditName))
        else lore.add(t(p, Msg.MENU_CONFIRM_SHORT, "credit" to creditName))
        if (session.legalTextId != null) lore.add(t(p, Msg.MENU_CONFIRM_LEGAL))
        val items = ArrayList<MenuItem>()
        items.add(MenuItem(4, material(product.icon, "PAPER"), t(p, Msg.MENU_CONFIRM_TITLE, "product" to product.name), lore))
        if (processing) {
            items.add(MenuItem(1, "CLOCK", t(p, Msg.MENU_CONFIRM_PROCESSING)))
        } else {
            items.add(MenuItem(1, "EMERALD_BLOCK", t(p, Msg.MENU_CONFIRM_YES), action = if (enough) MenuAction.Confirm else null))
            items.add(MenuItem(7, "REDSTONE_BLOCK", t(p, Msg.MENU_CONFIRM_NO), action = MenuAction.Cancel))
        }
        items.add(closeItem(p, base()))
        draw(session, MenuScreen(rows, items))
    }

    private fun confirm(session: Session) {
        val product: QueryProduct
        val operationId: String
        val legal: Long?
        val unknownKey: String
        synchronized(session) {
            if (sessions[session.key] !== session || session.screen != Screen.CONFIRM) return
            product = session.product ?: return
            val price = product.creditPrice ?: return
            if ((session.balance ?: 0.0) + EPSILON < price) return
            if (!buying.add(session.key)) {
                // A second click, or a purchase of an earlier (closed) menu, is still being answered.
                sayTo(session.player, Msg.MENU_BUSY)
                return
            }
            unknownKey = "${session.key}|${product.id}"
            val now = clock.now()
            val previous = unknown[unknownKey]?.takeIf { now - it.at < UNKNOWN_TTL_MS }
            operationId = previous?.operationId ?: newOperationId()
            legal = session.legalTextId
            session.awaiting = true
            session.generation++
            showConfirm(session, product, processing = true)
        }
        val request = MarketPurchaseRequest(
            componentVersion, operationId = operationId, player = PlayerRef(session.player.name, session.player.uuid ?: uuidOf(session.player.name)),
            productId = product.id, quantity = 1, confirmLegalTextId = legal
        )
        try {
            link.request(request, MarketPurchaseMessage::class.java) { answer ->
                try {
                    purchased(session, product, operationId, unknownKey, answer)
                } finally {
                    buying.remove(session.key)
                }
            }
        } catch (e: Throwable) {
            buying.remove(session.key)
            throw e
        }
    }

    private fun purchased(session: Session, product: QueryProduct, operationId: String, unknownKey: String, answer: MarketPurchaseMessage?) {
        val p = session.player
        // The outcome is told to the player whether or not the menu is still open; only the drawing depends on it.
        if (answer == null) {
            unknown[unknownKey] = Unknown(operationId, clock.now())
            if (unknown.size > 256) clock.now().let { now -> unknown.entries.removeIf { now - it.value.at > UNKNOWN_TTL_MS } }
            sayTo(p, Msg.MENU_UNKNOWN_OUTCOME)
            return backToProducts(session)
        }
        if (!answer.accepted) {
            sayTo(p, refusalKey(answer.reason), null, "reason" to (answer.reason ?: "UNKNOWN"))
            return backToProducts(session)
        }
        unknown.remove(unknownKey)
        if (answer.ok == true) {
            val total = answer.creditTotal ?: product.creditPrice ?: 0.0
            sayTo(
                p, Msg.MENU_BOUGHT, null,
                "product" to product.name, "price" to MarketCommands.number(total), "credit" to creditName,
                "balance" to MarketCommands.number(answer.balance ?: 0.0)
            )
            // Pano created the deliveries in this call: fetch them now instead of at the next interval (19 section 7.3).
            control()?.syncSoon()
            return backToProducts(session)
        }
        val code = answer.code
        if (code == LEGAL_REQUIRED) {
            val raw = answer.extras?.get("legalTextId")
            val legalId = (raw as? Number)?.toLong() ?: (raw as? String)?.toLongOrNull()
            if (legalId != null) {
                synchronized(session) {
                    if (sessions[session.key] === session) {
                        session.awaiting = false
                        session.legalTextId = legalId
                        session.generation++
                        showConfirm(session, product)
                    }
                }
                return sayTo(p, Msg.MENU_LEGAL_NEEDED, null, "url" to (config.remote?.storeUrl ?: ""))
            }
        }
        if (code == LOGIN_REQUIRED) {
            val url = registerUrl()
            sayTo(p, Msg.MENU_REGISTER, url, "url" to (url ?: ""))
            return end(session, closeView = true)
        }
        view.message(p.name, codeMessage(p, code))
        backToProducts(session)
    }

    private fun backToProducts(session: Session) {
        val page: Int
        val category: Long?
        synchronized(session) {
            if (sessions[session.key] !== session) return
            session.awaiting = false
            page = session.serverPage
            category = session.categoryId
        }
        // Fresh stock and limits after a purchase attempt.
        requestCatalog(session, category, page, chooseCategories = false)
    }

    /** The localized text of a purchase refusal code (04 section 11). */
    fun codeMessage(p: MenuPlayer, code: String?): String = when (code) {
        "BUYER_BLOCKED" -> t(p, Msg.MENU_ERR_BLOCKED)
        "OUT_OF_STOCK" -> t(p, Msg.MENU_ERR_STOCK)
        "PURCHASE_LIMIT_REACHED" -> t(p, Msg.MENU_ERR_LIMIT)
        "COOLDOWN_ACTIVE" -> t(p, Msg.MENU_ERR_COOLDOWN)
        "REQUIREMENT_NOT_MET" -> t(p, Msg.MENU_ERR_REQUIREMENT)
        "NOT_PAYABLE_WITH_CREDITS" -> t(p, Msg.MENU_ERR_NOT_PAYABLE)
        "INSUFFICIENT_CREDITS" -> t(p, Msg.MENU_ERR_INSUFFICIENT)
        "PRODUCT_UNAVAILABLE" -> t(p, Msg.MENU_ERR_UNAVAILABLE)
        "CREDITS_DISABLED" -> t(p, Msg.MENU_ERR_CREDITS_OFF)
        else -> t(p, Msg.MENU_ERR_OTHER, "code" to (code ?: "UNKNOWN"))
    }

    // ---- plumbing -------------------------------------------------------------------------------------------------

    /** Caller holds the session lock. */
    private fun draw(session: Session, screen: MenuScreen) {
        session.actions = screen.items.mapNotNull { i -> i.action?.let { i.slot to it } }.toMap()
        view.show(session.player.name, screen)
    }

    private fun end(session: Session, closeView: Boolean) {
        val removed = synchronized(session) {
            session.generation++
            session.awaiting = false
            sessions.remove(session.key, session)
        }
        if (removed && closeView) view.close(session.player.name)
    }

    private fun say(p: MenuPlayer, key: String, vararg args: Pair<String, Any?>) = view.message(p.name, t(p, key, *args))

    private fun sayTo(p: MenuPlayer, key: String, url: String? = null, vararg args: Pair<String, Any?>) =
        view.message(p.name, t(p, key, *args), url?.takeIf { it.isNotEmpty() })

    companion object {
        private const val LAST = Int.MAX_VALUE
        private const val EPSILON = 1e-9
        private const val UNKNOWN_TTL_MS = 10 * 60 * 1000L
        private const val LEGAL_REQUIRED = "LEGAL_ACCEPTANCE_REQUIRED"
        private const val LOGIN_REQUIRED = "LOGIN_REQUIRED"
        private val MATERIAL = Regex("[A-Z][A-Z0-9_]{0,47}")
    }
}
