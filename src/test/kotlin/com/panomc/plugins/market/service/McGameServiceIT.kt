package com.panomc.plugins.market.service

import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.config.VaultDirection
import com.panomc.plugins.market.config.VaultMode
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.GoalMetric
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketServerState
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.event.server.GameActor
import com.panomc.plugins.market.event.server.GamePlayer
import com.panomc.plugins.market.event.server.GameQueryArgs
import com.panomc.plugins.market.event.server.GameTarget
import com.panomc.plugins.market.event.server.MarketAdminEventRequest
import com.panomc.plugins.market.event.server.MarketAdminEventResponse
import com.panomc.plugins.market.event.server.MarketConfigEventRequest
import com.panomc.plugins.market.event.server.MarketEconomyEventRequest
import com.panomc.plugins.market.event.server.MarketEconomyEventResponse
import com.panomc.plugins.market.event.server.MarketPurchaseEventRequest
import com.panomc.plugins.market.event.server.MarketPurchaseEventResponse
import com.panomc.plugins.market.event.server.MarketQueryEventRequest
import com.panomc.plugins.market.event.server.MarketQueryEventResponse
import com.panomc.plugins.market.event.server.MarketSyncEventRequest
import com.panomc.plugins.market.log.GrantedMarketCreditsIngameLog
import com.panomc.plugins.market.log.GrantedMarketProductIngameLog
import com.panomc.plugins.market.log.RevokedMarketCreditsIngameLog
import com.panomc.plugins.market.log.SetMarketCreditsIngameLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.support.FakeMcLink
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `McGameService` (19 section 7, MC-04) on a real MariaDB: the five game events besides `MARKET_SYNC`, driven the way the platform drives them (a decoded request, the
 * authenticated server, a response object) over the real checkout, credit ledger, store read model and order service of the earlier slices (MK-072, MK-091, MK-064,
 * MK-151). Tests of 19 section 13: MC-E4 (`MARKET_PURCHASE`: success debits and delivers, a replayed `operationId` returns the same order, out of stock / limit /
 * insufficient credits / blocked buyer / no account answer their codes, a required legal text without the confirmation is refused), MC-E5 (`MARKET_ADMIN`: the
 * double authorisation, ledger and activity log, the console), MC-E6 (`MARKET_ECONOMY`: `EXTERNAL_IN` / `EXTERNAL_OUT`, overdraw, mode `OFF`) and MC-E7
 * (`MARKET_CONFIG`: an override changes `configHash`), plus the queries, the per-server settings write and the component download. The invariants I1 to I22 and
 * the credit reconciler (D-O19) run after every test.
 */
class McGameServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var c: CreditHarness
    private lateinit var game: McGameService
    private val vertx: Vertx = Vertx.vertx()
    private val scope = CoroutineScope(vertx.dispatcher())

    private val version = "1.4.0"
    private val ready = AtomicBoolean(true)
    private val logs = CopyOnWriteArrayList<PluginActivityLog>()
    private val announced = CopyOnWriteArrayList<String>()
    private val granted = ConcurrentHashMap<Long, Set<MarketNode>>()
    private val everything: Set<MarketNode> = MarketNode.entries.toSet()

    /** The `mc*` panel defaults and the credit name a test changes (the checkout's own switches are `c.h.config`). */
    private data class Mc(
        val adminCommands: Boolean = true,
        val broadcast: Boolean = false,
        val template: String = MarketConfig.DEFAULT_BROADCAST_TEMPLATE,
        val disabledAdmin: List<String> = emptyList(),
        val vaultMode: VaultMode = VaultMode.OFF,
        val vaultRate: Double = 1.0,
        val vaultDirection: VaultDirection = VaultDirection.BOTH,
        val creditName: String = "Gems",
        val joinNotifications: Boolean = true,
        val storeEnabled: Boolean = true
    )

    @Volatile
    private var mc = Mc()

    @Volatile
    private var storeUrl: String? = "https://example.com"

    @Volatile
    private var texts: Map<String, Map<String, String>> = emptyMap()

    private val server7 = server(7, "Lobby")
    private val server8 = server(8, "Skyblock")

    private fun server(id: Long, name: String) = Server(
        id = id, name = name, motd = "", host = "127.0.0.1", port = 25565, playerCount = 0, maxPlayerCount = 0, type = ServerType.PAPER, version = "1.21",
        favicon = "", status = ServerStatus.ONLINE, startTime = 0, aesKey = ""
    )

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        ready.set(true)
        logs.clear()
        announced.clear()
        granted.clear()
        mc = Mc()
        storeUrl = "https://example.com"
        texts = emptyMap()
        w = TestWiring(pool)
        c = CreditHarness(w, vertx)
        game = newGame()
    }

    /** D-O19: the self-check of the ledger passes after every scenario. */
    override suspend fun assertInvariants() {
        super.assertInvariants()

        val result = c.reconciler().run(full = true)

        assertTrue(result.ok, "the credit reconciler found ${result.problems}")
    }

    private val fx get() = w.fixtures
    private val h get() = c.h

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = c.h.emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = false
    }

    /** The configuration the service sees: the checkout switches of the harness plus the in-game knobs of this test. */
    private fun cfg(): MarketConfig {
        val base = h.config
        val m = mc

        return MarketConfig(
            currency = com.panomc.plugins.market.util.CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = base.showVatInPrice, creditValue = 1.0, storeTimeZone = "UTC",
            storeEnabled = m.storeEnabled, allowGuestCheckout = base.allowGuestCheckout, creditsEnabled = base.creditsEnabled, creditTopUpEnabled = base.creditTopUpEnabled, creditName = m.creditName, storeName = "Shop",
            mcAdminCommands = m.adminCommands, mcBroadcast = m.broadcast, mcBroadcastTemplate = m.template, mcDisabledAdminCommands = m.disabledAdmin,
            mcVaultMode = m.vaultMode, mcVaultRate = m.vaultRate, mcVaultDirection = m.vaultDirection, mcJoinNotifications = m.joinNotifications
        )
    }

    private fun newGame(): McGameService {
        val store = StoreQueryService(
            { cfg() }, w.clock, w.categories, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.discounts, w.currencyRates, w.comparisons, w.orderItems, w.entitlements
        )
        val widgets = WidgetService({ cfg() }, w.clock, w.goals, { w.goals.prefix() })

        return McGameService(
            db = c.ph.db, clock = w.clock, config = { cfg() }, serverStates = w.serverStates, credits = c.credits, creditTxs = w.creditTxs, checkout = c.service, users = directory,
            permissions = McPermissions { userId, node -> node in granted[userId].orEmpty() }, orders = w.orders, orderItems = w.orderItems, products = w.products,
            categories = w.categories, store = store, widgets = widgets, read = { w.pool }, activity = { logs += it }, pluginId = "pano-plugin-market",
            marketVersion = { version }, storeUrl = { storeUrl }, texts = { locale -> texts[locale].orEmpty() }, textLocales = { listOf("en-US", "tr", "ru") },
            announce = { order, _ -> announced += order.publicId!! }, ready = { ready.get() }
        )
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun user(name: String, credit: Long = 0): TestUser {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        if (credit > 0) fx.credit(u, credit)

        return u
    }

    private fun op() = UUID.randomUUID().toString()

    private suspend fun balance(u: TestUser) = fx.creditBalance(u)

    private suspend fun system(key: CreditSystemKey): Long = w.creditAccounts.getBySystemKey(key, pool)!!.balance

    private suspend fun purchaseOf(player: String, productId: Long, quantity: Int = 1, operationId: String = op(), confirm: Long? = null, server: Server = server7) =
        game.purchase(MarketPurchaseEventRequest("MARKET_PURCHASE", version, 1, operationId, GamePlayer(player), productId, quantity, confirm), server)

    private suspend fun adminOf(
        op: String, actor: String?, target: String, operationId: String = op(), amount: Double? = null, productId: Long? = null, quantity: Int? = null, note: String? = null,
        server: Server = server7
    ) = game.admin(
        MarketAdminEventRequest("MARKET_ADMIN", version, 1, operationId, op, GameActor(console = actor == null, username = actor), GameTarget(target), amount, productId, quantity, note), server
    )

    private suspend fun economyOf(op: String, player: String, operationId: String = op(), amount: Double? = null, server: Server = server7) =
        game.economy(MarketEconomyEventRequest("MARKET_ECONOMY", version, 1, operationId, op, GamePlayer(player), amount, "vault"), server)

    private suspend fun queryOf(type: String, player: String? = null, page: Int? = null, args: GameQueryArgs? = null, server: Server = server7) =
        game.query(MarketQueryEventRequest("MARKET_QUERY", version, 1, type, player?.let { GamePlayer(it) }, page, args), server)

    private suspend fun orderRow(publicId: String): MarketOrder = w.orders.getByPublicId(publicId, pool)!!

    /** What a delivered purchase of [product] leaves (the harness runs no delivery): the entitlement that satisfies a `requiredProducts` of another product. */
    private suspend fun own(user: TestUser, product: com.panomc.plugins.market.db.model.MarketProduct, publicId: String) {
        val order = orderRow(publicId)
        val item = w.orderItems.getByOrderIds(listOf(order.id), pool).first()

        w.entitlements.add(
            com.panomc.plugins.market.db.model.MarketEntitlement(
                userId = user.id, playerUsername = user.username, ownerKey = "u:${user.id}", productId = product.id, orderId = order.id, orderItemId = item.id, startsAt = w.clock.now() - 1_000
            ),
            pool
        )
    }

    private suspend fun orderCount(): Long = sql("SELECT COUNT(*) AS n FROM `pano_market_order`").single().getLong("n")

    private suspend fun txKeys(): List<String> = sql("SELECT `idempotencyKey` FROM `pano_market_credit_tx` WHERE `idempotencyKey` LIKE 'mc:%' ORDER BY `id`").map { it.getString("idempotencyKey") }

    private fun assertFailed(code: String, r: MarketPurchaseEventResponse): MarketPurchaseEventResponse {
        assertTrue(r.accepted, "accepted: $r")
        assertEquals(false, r.ok, "ok: $r")
        assertEquals(code, r.code, "$r")

        return r
    }

    private fun assertFailed(code: String, r: MarketAdminEventResponse): MarketAdminEventResponse {
        assertTrue(r.accepted, "accepted: $r")
        assertEquals(false, r.ok, "ok: $r")
        assertEquals(code, r.code, "$r")

        return r
    }

    private fun assertFailed(code: String, r: MarketEconomyEventResponse): MarketEconomyEventResponse {
        assertTrue(r.accepted, "accepted: $r")
        assertEquals(false, r.ok, "ok: $r")
        assertEquals(code, r.code, "$r")

        return r
    }

    private suspend fun product(price: Long = 3_000, creditPrice: Long = 2_500, stock: Int? = null, slug: String? = null, columns: Map<String, Any?> = emptyMap(), name: String? = null, categoryId: Long? = null) =
        if (slug == null) fx.product(price = price, creditPrice = creditPrice, stock = stock, columns = columns, categoryId = categoryId, name = name ?: "Diamonds")
        else fx.product(slug = slug, price = price, creditPrice = creditPrice, stock = stock, columns = columns, categoryId = categoryId, name = name ?: slug)

    // ===================================================================================== MARKET_PURCHASE (MC-E4)

    @Test
    fun `a purchase is a full-credit INGAME order through the checkout, debits the credits and a replayed operationId returns the same order`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val diamonds = product(stock = 3)
        val operationId = op()

        val first = purchaseOf("Alex", diamonds.id, operationId = operationId)

        assertTrue(first.accepted)
        assertEquals(true, first.ok)
        assertEquals(25.0, first.creditTotal)
        assertEquals(75.0, first.balance)
        assertNull(first.code)

        val order = orderRow(first.orderPublicId!!)

        assertEquals(OrderSource.INGAME, order.source)
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals("credits", order.paymentMethodId)
        assertEquals(alex.id, order.userId)
        assertEquals(alex.id, order.recipientUserId)
        assertEquals("mc:7:$operationId", order.idempotencyKey)
        assertNull(order.clientIp, "an in-game purchase carries no address (19 section 7.3)")
        assertEquals(3_000, order.totalPrice)
        assertEquals(2_500, order.creditAmount, "the credit price of the product, not its money price")
        assertEquals(0, order.gatewayAmount)
        assertEquals(order.totalPrice, order.creditValue, "creditValue is the money value of the whole order")
        assertEquals(7_500, balance(alex))
        assertEquals(2, w.products.getById(diamonds.id, pool)!!.stock, "the stock was taken")
        assertEquals(listOf("order:${order.id}:hold", "order:${order.id}:capture"), c.ledger(order.id).map { it.idempotencyKey })
        assertEquals(2_500, system(CreditSystemKey.SPENT))

        val created = sql("SELECT `data` FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'CREATED'", order.id).single().getString("data")

        assertEquals("INGAME", JsonObject(created).getString("source"), "the timeline says where the order came from")

        // the announcement of a paid in-game purchase (best effort, once)
        assertEquals(listOf(first.orderPublicId), announced.toList())

        // a replay of the same operationId: the first order again, nothing charged twice, no second announcement
        val again = purchaseOf("Alex", diamonds.id, operationId = operationId)

        assertEquals(true, again.ok)
        assertEquals(first.orderPublicId, again.orderPublicId)
        assertEquals(25.0, again.creditTotal)
        assertEquals(75.0, again.balance)
        assertEquals(1, orderCount())
        assertEquals(7_500, balance(alex))
        assertEquals(2, w.products.getById(diamonds.id, pool)!!.stock)
        assertEquals(listOf(first.orderPublicId), announced.toList())

        // the same key for another request is a conflict, never a second order
        assertFailed("IDEMPOTENCY_CONFLICT", purchaseOf("Alex", product(slug = "other").id, operationId = operationId))
        assertEquals(1, orderCount())
    }

    @Test
    fun `a replay returns the first result even when the product was deleted in between`(): Unit = runBlocking {
        user("Alex", credit = 10_000)
        val p = product()
        val operationId = op()
        val first = purchaseOf("Alex", p.id, operationId = operationId)

        Fixtures.setColumns(pool, "market_product", p.id, mapOf("deletedAt" to w.clock.now()))

        assertFailed("PRODUCT_UNAVAILABLE", purchaseOf("Alex", p.id))

        val again = purchaseOf("Alex", p.id, operationId = operationId)

        assertEquals(true, again.ok)
        assertEquals(first.orderPublicId, again.orderPublicId)
    }

    @Test
    fun `a quantity buys that many units for that many credits`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val p = product(stock = 10)
        val r = purchaseOf("Alex", p.id, quantity = 3)

        assertEquals(true, r.ok)
        assertEquals(75.0, r.creditTotal)
        assertEquals(2_500, balance(alex))
        assertEquals(7, w.products.getById(p.id, pool)!!.stock)
    }

    @Test
    fun `out of stock answers OUT_OF_STOCK and takes nothing`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val p = product(stock = 0)

        assertFailed("OUT_OF_STOCK", purchaseOf("Alex", p.id))
        assertEquals(10_000, balance(alex))
        assertEquals(0, orderCount())
        assertEquals(emptyList<String>(), announced.toList())
    }

    @Test
    fun `the per-player limit and the cooldown answer their codes with their extras`(): Unit = runBlocking {
        val alex = user("Alex", credit = 100_000)
        val once = product(slug = "once", columns = mapOf("limitPerPlayer" to 1))
        val slow = product(slug = "slow", columns = mapOf("cooldownSeconds" to 3_600))

        assertEquals(true, purchaseOf("Alex", once.id).ok)

        val limit = assertFailed("PURCHASE_LIMIT_REACHED", purchaseOf("Alex", once.id))

        assertEquals(once.id, (limit.extras!!["productId"] as Number).toLong())
        assertEquals(1, (limit.extras!!["limit"] as Number).toInt())

        assertEquals(true, purchaseOf("Alex", slow.id).ok)
        w.clock.advance(600_000)

        val cooldown = assertFailed("COOLDOWN_ACTIVE", purchaseOf("Alex", slow.id))

        assertEquals(slow.id, (cooldown.extras!!["productId"] as Number).toLong())
        assertEquals(3_000, (cooldown.extras!!["retryAfter"] as Number).toLong())
        assertEquals(100_000 - 2 * 2_500, balance(alex))
        assertEquals(2, orderCount())
    }

    @Test
    fun `a missing product requirement answers REQUIREMENT_NOT_MET, the line code of the 409 PRODUCT_REQUIREMENT_NOT_MET`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val base = product(slug = "base")
        val upgrade = product(slug = "upgrade", columns = mapOf("requiredProducts" to "[${base.id}]"))

        assertFailed("REQUIREMENT_NOT_MET", purchaseOf("Alex", upgrade.id))

        val bought = purchaseOf("Alex", base.id)

        assertEquals(true, bought.ok)

        own(alex, base, bought.orderPublicId!!)

        assertEquals(true, purchaseOf("Alex", upgrade.id).ok)
    }

    @Test
    fun `not enough credits answers INSUFFICIENT_CREDITS with the balance and no order exists`(): Unit = runBlocking {
        val alex = user("Alex", credit = 1_000)
        val p = product()

        val r = assertFailed("INSUFFICIENT_CREDITS", purchaseOf("Alex", p.id))

        assertEquals(10.0, (r.extras!!["balance"] as Number).toDouble())
        assertEquals(1_000, balance(alex))
        assertEquals(0, orderCount())
    }

    @Test
    fun `a blocked buyer answers BUYER_BLOCKED`(): Unit = runBlocking {
        user("Alex", credit = 10_000)
        val p = product()

        h.blocked = { payer, _, _, _, _ -> payer == "Alex" }

        assertFailed("BUYER_BLOCKED", purchaseOf("Alex", p.id))
        assertEquals(0, orderCount())

        h.blocked = { _, _, _, _, _ -> false }

        assertEquals(true, purchaseOf("Alex", p.id).ok)
    }

    @Test
    fun `a player without a Pano account answers LOGIN_REQUIRED and nothing is created`(): Unit = runBlocking {
        val p = product()

        assertFailed("LOGIN_REQUIRED", purchaseOf("Nobody", p.id))
        assertEquals(0, orderCount())
    }

    @Test
    fun `a required legal text is refused without the confirmation, with the id of the active text`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val p = product()

        h.config = h.config.copy(legalTextRequired = true)

        val text = h.legal.publish("en-US", "Terms", "<p>one</p>", null)
        val refused = assertFailed("LEGAL_ACCEPTANCE_REQUIRED", purchaseOf("Alex", p.id))

        assertEquals(text.id, (refused.extras!!["legalTextId"] as Number).toLong())
        assertEquals(0, orderCount())

        // a confirmation of another text is as good as none
        assertFailed("LEGAL_ACCEPTANCE_REQUIRED", purchaseOf("Alex", p.id, confirm = text.id + 99))

        val ok = purchaseOf("Alex", p.id, confirm = text.id)

        assertEquals(true, ok.ok)
        assertEquals(text.id, orderRow(ok.orderPublicId!!).legalTextId, "the accepted text is stored with the order")
        assertEquals(7_500, balance(alex))
    }

    @Test
    fun `credits switched off, a product without a credit price, a physical product and a subscription are refused with their codes`(): Unit = runBlocking {
        user("Alex", credit = 10_000)
        val money = product(slug = "money", creditPrice = 0)
        val physical = product(slug = "box", columns = mapOf("physical" to true))
        val subscription = product(slug = "sub", columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))
        val ok = product(slug = "ok")

        assertFailed("NOT_PAYABLE_WITH_CREDITS", purchaseOf("Alex", money.id))
        assertFailed("PHYSICAL_NOT_SUPPORTED", purchaseOf("Alex", physical.id))
        assertFailed("RECURRING_NOT_SUPPORTED", purchaseOf("Alex", subscription.id))
        assertFailed("PRODUCT_UNAVAILABLE", purchaseOf("Alex", ok.id + 999))

        h.config = h.config.copy(creditsEnabled = false)

        assertFailed("CREDITS_DISABLED", purchaseOf("Alex", ok.id))
        assertEquals(0, orderCount())
    }

    @Test
    fun `a product that needs a variant is refused with the line code`(): Unit = runBlocking {
        user("Alex", credit = 10_000)
        val p = product(slug = "sized", columns = mapOf("hasVariants" to true))

        fx.variant(p, name = "L", price = 1_000)

        assertFailed("VARIANT_REQUIRED", purchaseOf("Alex", p.id))
    }

    @Test
    fun `the gates answer before anything is read, and a malformed request is BAD_REQUEST`(): Unit = runBlocking {
        user("Alex", credit = 10_000)
        val p = product()
        val request = MarketPurchaseEventRequest("MARKET_PURCHASE", version, 1, op(), GamePlayer("Alex"), p.id, 1, null)

        ready.set(false)

        assertEquals("MARKET_NOT_READY", game.purchase(request, server7).also { assertFalse(it.accepted) }.reason)

        ready.set(true)

        assertEquals("PROTOCOL_UNSUPPORTED", game.purchase(request.copy(protocol = 2), server7).also { assertFalse(it.accepted) }.reason)
        assertEquals("VERSION_MISMATCH", game.purchase(request.copy(componentVersion = "1.3.9"), server7).also { assertFalse(it.accepted) }.reason)

        assertFailed("BAD_REQUEST", game.purchase(request.copy(operationId = "x"), server7))
        assertFailed("BAD_REQUEST", game.purchase(request.copy(quantity = 0), server7))
        assertFailed("BAD_REQUEST", game.purchase(request.copy(quantity = 1_000), server7))
        assertFailed("BAD_REQUEST", game.purchase(request.copy(productId = 0), server7))
        assertFailed("BAD_REQUEST", game.purchase(request.copy(player = GamePlayer("  ")), server7))
        assertEquals(0, orderCount())
    }

    @Test
    fun `a purchase beyond the checkout rate limit of the buyer is refused with RATE_LIMITED`(): Unit = runBlocking {
        user("Alex", credit = 100_000)
        val p = product()

        h.config = h.config.copy(checkoutRateLimitPerMinute = 1)

        assertEquals(true, purchaseOf("Alex", p.id).ok)

        val limited = purchaseOf("Alex", p.id)

        assertFalse(limited.accepted)
        assertEquals("RATE_LIMITED", limited.reason)
        assertEquals(1, orderCount())
    }

    @Test
    fun `a failing announcement never fails the purchase`(): Unit = runBlocking {
        user("Alex", credit = 10_000)

        val failing = McGameService(
            db = c.ph.db, clock = w.clock, config = { cfg() }, serverStates = w.serverStates, credits = c.credits, creditTxs = w.creditTxs, checkout = c.service, users = directory,
            permissions = McPermissions { _, _ -> false }, orders = w.orders, orderItems = w.orderItems, products = w.products, categories = w.categories,
            store = StoreQueryService({ cfg() }, w.clock, w.categories, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.discounts, w.currencyRates, w.comparisons, w.orderItems, w.entitlements),
            widgets = WidgetService({ cfg() }, w.clock, w.goals, { w.goals.prefix() }), read = { w.pool }, activity = { }, pluginId = "pano-plugin-market", marketVersion = { version },
            storeUrl = { null }, announce = { _, _ -> throw IllegalStateException("the broadcast queue is gone") }, ready = { true }
        )
        val p = product()
        val r = failing.purchase(MarketPurchaseEventRequest("MARKET_PURCHASE", version, 1, op(), GamePlayer("Alex"), p.id, 1, null), server7)

        assertEquals(true, r.ok)
        assertEquals(OrderStatus.COMPLETED, orderRow(r.orderPublicId!!).status)
    }

    @Test
    fun `a closed store refuses a new purchase and the catalogue, a replay of an order placed while it was open still answers its first result`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val p = product(stock = 3)
        val placed = op()
        val first = purchaseOf("Alex", p.id, operationId = placed)

        assertEquals(true, first.ok)
        assertEquals(7_500, balance(alex))
        assertEquals(listOf(first.orderPublicId), announced.toList())

        mc = mc.copy(storeEnabled = false)

        // a new purchase: refused before anything is placed (04 section 1: 503 STORE_DISABLED on the web), nothing moves
        assertFailed("STORE_DISABLED", purchaseOf("Alex", p.id))
        assertFailed("STORE_DISABLED", purchaseOf("Alex", p.id, quantity = 2))

        assertEquals(1, orderCount())
        assertEquals(7_500, balance(alex), "no credits were held or taken")
        assertEquals(2, w.products.getById(p.id, pool)!!.stock, "no stock was reserved")
        assertEquals(1, announced.size)
        assertEquals(
            listOf("order:${orderRow(first.orderPublicId!!).id}:hold", "order:${orderRow(first.orderPublicId!!).id}:capture"),
            sql("SELECT `idempotencyKey` FROM `pano_market_credit_tx` WHERE `idempotencyKey` LIKE 'order:%' ORDER BY `id`").map { it.getString("idempotencyKey") },
            "the ledger holds the hold and the capture of the first order only"
        )

        // the switch comes before the account check, as on the web: a stranger is told the store is closed, not to register
        assertFailed("STORE_DISABLED", purchaseOf("Stranger", p.id))

        // a replay of the order placed while the store was open: the first result, no second order
        val replay = purchaseOf("Alex", p.id, operationId = placed)

        assertEquals(true, replay.ok)
        assertEquals(first.orderPublicId, replay.orderPublicId)
        assertEquals(75.0, replay.balance)
        assertEquals(1, orderCount())

        // a malformed request is still BAD_REQUEST (schema validation is part of the same step)
        assertFailed("BAD_REQUEST", purchaseOf("Alex", p.id, operationId = "x"))

        // the catalogue lists nothing a closed store could sell
        val catalog = queryOf("CATALOG", "Alex")

        assertFalse(catalog.accepted)
        assertEquals("STORE_DISABLED", catalog.reason)
        assertNull(catalog.data)

        // what is no store endpoint keeps answering: the player's own balance is read-only
        assertEquals(75.0, queryOf("BALANCE", "Alex").data!!.balance)

        // the store is opened again: the same request now buys, and the catalogue lists
        mc = mc.copy(storeEnabled = true)

        assertTrue(queryOf("CATALOG", "Alex").accepted)
        assertEquals(true, purchaseOf("Alex", p.id).ok)
        assertEquals(5_000, balance(alex))
        assertEquals(2, orderCount())
    }

    /** The first request of a purchase committed its order and hold, then the completion never finished (a crash between O1 and O2 of 07 section 5): the order stays PENDING. */
    private suspend fun leavePending(player: String, productId: Long, operationId: String): MarketOrder {
        c.deferStart = true

        try {
            val thrown = runCatching { purchaseOf(player, productId, operationId = operationId) }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException, "the unfinished purchase is no answer at all: $thrown")
        } finally {
            c.deferStart = false
        }

        return w.orders.getByBuyerAndIdempotencyKey("u:${w.users.idOf(player)!!}", "mc:7:$operationId", pool)!!
    }

    @Test
    fun `a purchase whose order is not paid yet is no answer, a replay stays unanswered while the order is PENDING and answers ok once it is completed`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val p = product(stock = 3)
        val id = op()
        val pending = leavePending("Alex", p.id, id)

        assertEquals(OrderStatus.PENDING, pending.status)
        assertEquals(OrderSource.INGAME, pending.source)
        assertEquals(7_500, balance(alex), "the credits are on hold")
        assertEquals(emptyList<String>(), announced.toList(), "nothing is announced for an order that is not paid")

        // the component timed out and asks again with the same operationId: the order is still PENDING with a CREATED attempt, so still no `ok`
        val again = runCatching { purchaseOf("Alex", p.id, operationId = id) }.exceptionOrNull()

        assertTrue(again is IllegalStateException, "a replay of an unfinished order must not be answered: $again")
        assertEquals(1, orderCount())
        assertEquals(7_500, balance(alex))

        // the re-drive of the reconcile job completes the order: the same replay now answers the first result
        val attempt = c.attempts(pending.id).single()

        c.payments.startAttempt(pending.id, attempt.id, emptyList(), pool)

        assertEquals(OrderStatus.COMPLETED, orderRow(pending.publicId!!).status)

        val done = purchaseOf("Alex", p.id, operationId = id)

        assertEquals(true, done.ok)
        assertEquals(pending.publicId, done.orderPublicId)
        assertEquals(25.0, done.creditTotal)
        assertEquals(75.0, done.balance)
        assertEquals(1, orderCount())
        assertEquals(7_500, balance(alex), "completing the order took no second amount")
    }

    @Test
    fun `a replay of an order that was cancelled or expired is a definite refusal, never ok`(): Unit = runBlocking {
        val alex = user("Alex", credit = 10_000)
        val p = product(stock = 5)
        val expiredId = op()
        val cancelledId = op()
        val expired = leavePending("Alex", p.id, expiredId)
        val cancelled = leavePending("Alex", p.id, cancelledId)

        assertEquals(5_000, balance(alex), "two holds")

        // the buyer cancels one, the expiry job takes the other
        assertEquals(OrderStatus.CANCELLED, c.payments.cancel(cancelled, pool))

        w.clock.advance(61 * 60_000L)

        assertTrue(c.expiry.runOnce() >= 1)
        assertEquals(OrderStatus.EXPIRED, orderRow(expired.publicId!!).status)
        assertEquals(10_000, balance(alex), "both holds are released")

        val a = assertFailed("ORDER_NOT_PAYABLE", purchaseOf("Alex", p.id, operationId = expiredId))
        val b = assertFailed("ORDER_NOT_PAYABLE", purchaseOf("Alex", p.id, operationId = cancelledId))

        assertNull(a.orderPublicId)
        assertNull(b.orderPublicId)
        assertNull(a.balance)
        assertEquals(2, orderCount())
        assertEquals(10_000, balance(alex))
        assertEquals(emptyList<String>(), announced.toList())

        // the player may buy again with a fresh operationId
        assertEquals(true, purchaseOf("Alex", p.id).ok)
        assertEquals(7_500, balance(alex))
    }

    // ===================================================================================== MARKET_ADMIN (MC-E5)

    @Test
    fun `the console may give credits without a Pano account, one ledger transaction under the mc key, no actor, no activity log`(): Unit = runBlocking {
        val steve = user("Steve", credit = 1_000)
        val operationId = op()

        val r = adminOf(McGameService.OP_GIVE, actor = null, target = "Steve", operationId = operationId, amount = 10.5)

        assertTrue(r.accepted)
        assertEquals(true, r.ok)
        assertEquals(20.5, r.balance)
        assertEquals(2_050, balance(steve))

        val tx = w.creditTxs.getByIdempotencyKey("mc:7:$operationId", pool)!!

        assertEquals(CreditTxType.GRANT, tx.type)
        assertEquals(1_050, tx.amount)
        assertEquals(steve.id, tx.userId)
        assertNull(tx.actorUserId, "the console has no user")
        assertEquals("in-game by console on Lobby", tx.note)
        assertTrue(logs.isEmpty(), "console operations are in the ledger note, not in the activity log (PluginActivityLog needs a user id)")

        // a replay: no second transaction, no second balance change
        val again = adminOf(McGameService.OP_GIVE, actor = null, target = "Steve", operationId = operationId, amount = 10.5)

        assertEquals(true, again.ok)
        assertEquals(20.5, again.balance)
        assertEquals(2_050, balance(steve))
        assertEquals(1, txKeys().size)

        // the same key with another amount is a conflict
        assertFailed("IDEMPOTENCY_CONFLICT", adminOf(McGameService.OP_GIVE, actor = null, target = "Steve", operationId = operationId, amount = 99.0))
        assertEquals(2_050, balance(steve))
    }

    @Test
    fun `a player actor needs the Pano permission, without it NO_PERMISSION and nothing is posted, with it a ledger transaction and an activity log`(): Unit = runBlocking {
        val admin = user("Admin")
        val steve = user("Steve", credit = 1_000)

        assertFailed("NO_PERMISSION", adminOf(McGameService.OP_GIVE, "Admin", "Steve", amount = 5.0))
        assertEquals(1_000, balance(steve))
        assertEquals(emptyList<String>(), txKeys())
        assertTrue(logs.isEmpty())

        // a node that is not PAY does not help
        granted[admin.id] = setOf(MarketNode.ORDERS_MANAGE, MarketNode.SETTINGS)

        assertFailed("NO_PERMISSION", adminOf(McGameService.OP_GIVE, "Admin", "Steve", amount = 5.0))
        assertEquals(1_000, balance(steve))

        granted[admin.id] = setOf(MarketNode.PAYMENTS)

        val operationId = op()
        val ok = adminOf(McGameService.OP_GIVE, "Admin", "Steve", operationId = operationId, amount = 5.0, note = "contest prize")

        assertEquals(true, ok.ok)
        assertEquals(15.0, ok.balance)

        val tx = w.creditTxs.getByIdempotencyKey("mc:7:$operationId", pool)!!

        assertEquals(admin.id, tx.actorUserId)
        assertEquals("contest prize", tx.note)

        val log = logs.single() as GrantedMarketCreditsIngameLog

        assertEquals(admin.id, log.userId)
        assertEquals("Admin", log.details.getString("username"))
        assertEquals("Steve", log.details.getString("targetUsername"))
        assertEquals(5.0, log.details.getDouble("amount"))
        assertEquals(7L, log.details.getLong("serverId"))

        // a replay writes no second log
        adminOf(McGameService.OP_GIVE, "Admin", "Steve", operationId = operationId, amount = 5.0, note = "contest prize")

        assertEquals(1, logs.size)
        assertEquals(1_500, balance(steve))
    }

    @Test
    fun `an actor without a Pano account is NO_PERMISSION, an unknown target is NO_ACCOUNT and the order of the answers never says whether the target exists`(): Unit = runBlocking {
        val admin = user("Admin")

        granted[admin.id] = setOf(MarketNode.PAYMENTS)

        assertFailed("NO_PERMISSION", adminOf(McGameService.OP_GIVE, "Ghost", "Nobody", amount = 5.0))
        assertFailed("NO_ACCOUNT", adminOf(McGameService.OP_GIVE, "Admin", "Nobody", amount = 5.0))
        assertFailed("NO_ACCOUNT", adminOf(McGameService.OP_GIVE, actor = null, target = "Nobody", amount = 5.0))

        val stranger = user("Stranger")

        assertNotNull(stranger)
        assertFailed("NO_PERMISSION", adminOf(McGameService.OP_GIVE, "Stranger", "Nobody", amount = 5.0))
        assertEquals(emptyList<String>(), txKeys())
    }

    @Test
    fun `take credits revokes what is asked, refuses what the balance cannot cover and logs the actor`(): Unit = runBlocking {
        val admin = user("Admin")
        val steve = user("Steve", credit = 1_000)

        granted[admin.id] = setOf(MarketNode.PAYMENTS)

        val r = adminOf(McGameService.OP_TAKE, "Admin", "Steve", amount = 4.0)

        assertEquals(true, r.ok)
        assertEquals(6.0, r.balance)
        assertEquals(600, balance(steve))
        assertTrue(logs.single() is RevokedMarketCreditsIngameLog)

        val short = assertFailed("INSUFFICIENT_CREDITS", adminOf(McGameService.OP_TAKE, "Admin", "Steve", amount = 50.0))

        assertEquals(6.0, short.balance)
        assertEquals(600, balance(steve), "a take that cannot be covered is refused, not shortened")
        assertEquals(1, txKeys().size)
        assertEquals(1, logs.size)
        assertEquals(400, system(CreditSystemKey.REVOKED))
    }

    @Test
    fun `set credits posts the difference as one grant or revoke, nothing when the balance is already right`(): Unit = runBlocking {
        val admin = user("Admin")
        val steve = user("Steve", credit = 1_000)

        granted[admin.id] = setOf(MarketNode.PAYMENTS)

        val up = op()

        assertEquals(50.0, adminOf(McGameService.OP_SET, "Admin", "Steve", operationId = up, amount = 50.0).balance)
        assertEquals(5_000, balance(steve))
        assertEquals(CreditTxType.GRANT, w.creditTxs.getByIdempotencyKey("mc:7:$up", pool)!!.type)
        assertEquals(4_000, w.creditTxs.getByIdempotencyKey("mc:7:$up", pool)!!.amount)

        val down = op()

        assertEquals(12.0, adminOf(McGameService.OP_SET, "Admin", "Steve", operationId = down, amount = 12.0).balance)
        assertEquals(1_200, balance(steve))
        assertEquals(CreditTxType.REVOKE, w.creditTxs.getByIdempotencyKey("mc:7:$down", pool)!!.type)
        assertEquals(3_800, w.creditTxs.getByIdempotencyKey("mc:7:$down", pool)!!.amount)

        // a replay of the first set does not move the balance again
        assertEquals(12.0, adminOf(McGameService.OP_SET, "Admin", "Steve", operationId = up, amount = 50.0).balance)
        assertEquals(1_200, balance(steve))

        val same = adminOf(McGameService.OP_SET, "Admin", "Steve", amount = 12.0)

        assertEquals(true, same.ok)
        assertEquals(12.0, same.balance)
        assertEquals(2, txKeys().size, "a set that finds the balance right writes no transaction")

        val zero = adminOf(McGameService.OP_SET, "Admin", "Steve", amount = 0.0)

        assertEquals(0.0, zero.balance)
        assertEquals(0, balance(steve))
        assertEquals(3, txKeys().size)
        assertTrue(logs.all { it is SetMarketCreditsIngameLog })
        assertEquals(4, logs.size, "every non-replayed operation of a player actor is logged, a set that changed nothing included")
    }

    @Test
    fun `credit amounts that are not a positive number with two decimals are INVALID_AMOUNT`(): Unit = runBlocking {
        user("Steve", credit = 1_000)

        for (bad in listOf(0.0, -1.0, 0.001, 2_000_000.0, Double.NaN)) assertFailed("INVALID_AMOUNT", adminOf(McGameService.OP_GIVE, null, "Steve", amount = bad))

        assertFailed("INVALID_AMOUNT", adminOf(McGameService.OP_GIVE, null, "Steve", amount = null))
        assertFailed("INVALID_AMOUNT", adminOf(McGameService.OP_SET, null, "Steve", amount = -3.0))
        assertEquals(emptyList<String>(), txKeys())
    }

    @Test
    fun `grant product is a manual order of price 0 and needs PAY and OM, the console is allowed and limits are enforced`(): Unit = runBlocking {
        val admin = user("Admin")
        val steve = user("Steve")
        val vip = product(slug = "vip", name = "VIP", columns = mapOf("limitPerPlayer" to 2), stock = 5)

        granted[admin.id] = setOf(MarketNode.PAYMENTS)

        assertFailed("NO_PERMISSION", adminOf(McGameService.OP_GRANT, "Admin", "Steve", productId = vip.id))

        granted[admin.id] = setOf(MarketNode.PAYMENTS, MarketNode.ORDERS_MANAGE)

        val operationId = op()
        val ok = adminOf(McGameService.OP_GRANT, "Admin", "Steve", operationId = operationId, productId = vip.id, quantity = 1, note = "winner")

        assertEquals(true, ok.ok)

        val order = orderRow(ok.orderPublicId!!)

        assertEquals(OrderSource.PANEL, order.source)
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(0, order.totalPrice)
        assertEquals(steve.id, order.userId)
        assertEquals(admin.id, order.createdBy)
        assertEquals("winner", order.note)
        assertEquals("mc:7:$operationId", order.idempotencyKey)
        assertEquals(4, w.products.getById(vip.id, pool)!!.stock)

        val log = logs.single() as GrantedMarketProductIngameLog

        assertEquals(vip.id, log.details.getLong("productId"))
        assertEquals(order.id, log.details.getLong("orderId"))

        // a replay is the same order and no second log
        val again = adminOf(McGameService.OP_GRANT, "Admin", "Steve", operationId = operationId, productId = vip.id, quantity = 1, note = "winner")

        assertEquals(ok.orderPublicId, again.orderPublicId)
        assertEquals(1, orderCount())
        assertEquals(1, logs.size)

        // the console: allowed, createdBy NULL, default note, no activity log
        val consoleOp = op()
        val console = adminOf(McGameService.OP_GRANT, null, "Steve", operationId = consoleOp, productId = vip.id)

        assertEquals(true, console.ok)
        assertNull(orderRow(console.orderPublicId!!).createdBy)
        assertEquals("in-game by console on Lobby", orderRow(console.orderPublicId!!).note)
        assertEquals(1, logs.size)

        // the per-player limit (2) is enforced, `force` is never used
        val limited = adminOf(McGameService.OP_GRANT, null, "Steve", productId = vip.id)

        assertFailed("PURCHASE_LIMIT_REACHED", limited)
        assertEquals(2, orderCount())

        assertFailed("PRODUCT_UNAVAILABLE", adminOf(McGameService.OP_GRANT, null, "Steve", productId = vip.id + 999))
        assertFailed("BAD_REQUEST", adminOf(McGameService.OP_GRANT, null, "Steve", productId = null))
        assertFailed("BAD_REQUEST", adminOf(McGameService.OP_GRANT, null, "Steve", productId = vip.id, quantity = 0))
        assertFailed("NO_ACCOUNT", adminOf(McGameService.OP_GRANT, null, "Nobody", productId = vip.id))
    }

    @Test
    fun `purchases of a player are listed for PAY, the latest ten paid non-test orders, newest first`(): Unit = runBlocking {
        val admin = user("Admin")
        user("Steve", credit = 100_000)
        val p = product(slug = "cape", name = "Cape")

        for (i in 1..12) assertEquals(true, purchaseOf("Steve", p.id).ok)

        val last = sql("SELECT `publicId` FROM `pano_market_order` ORDER BY `id` DESC LIMIT 1").single().getString("publicId")

        assertFailed("NO_PERMISSION", adminOf(McGameService.OP_PURCHASES, "Admin", "Steve"))

        granted[admin.id] = setOf(MarketNode.PAYMENTS)

        val r = adminOf(McGameService.OP_PURCHASES, "Admin", "Steve")

        assertEquals(true, r.ok)
        assertEquals(10, r.orders!!.size)
        assertEquals(last, r.orders!!.first().publicId)
        assertEquals(listOf("Cape"), r.orders!!.first().itemNames)
        assertEquals("COMPLETED", r.orders!!.first().status)
        assertEquals(30.0, r.orders!!.first().total)
        assertEquals("EUR", r.orders!!.first().currency)
        assertEquals(r.orders!!.map { it.publicId }, r.orders!!.map { it.publicId }.distinct())
        assertNotEquals(0, r.orders!!.first().createdAt)
        assertTrue(logs.isEmpty(), "reading purchases writes no log")
    }

    @Test
    fun `an operation that is switched off for the server, or the whole feature, is refused on the Pano side too`(): Unit = runBlocking {
        user("Steve", credit = 1_000)

        mc = mc.copy(disabledAdmin = listOf("give-credits"))

        assertFailed("ADMIN_COMMANDS_DISABLED", adminOf(McGameService.OP_GIVE, null, "Steve", amount = 1.0))
        assertEquals(true, adminOf(McGameService.OP_TAKE, null, "Steve", amount = 1.0).ok)

        mc = mc.copy(disabledAdmin = emptyList(), adminCommands = false)

        assertFailed("ADMIN_COMMANDS_DISABLED", adminOf(McGameService.OP_TAKE, null, "Steve", amount = 1.0))

        // the per-server override beats the panel default in both directions
        game.updateServerSettings(7, JsonObject().put("mcAdminCommands", true))

        assertEquals(true, adminOf(McGameService.OP_TAKE, null, "Steve", amount = 1.0).ok)
        assertFailed("ADMIN_COMMANDS_DISABLED", adminOf(McGameService.OP_TAKE, null, "Steve", amount = 1.0, server = server8))

        mc = mc.copy(adminCommands = true)
        game.updateServerSettings(7, JsonObject().put("mcDisabledAdminCommands", listOf("take-credits")))

        assertFailed("ADMIN_COMMANDS_DISABLED", adminOf(McGameService.OP_TAKE, null, "Steve", amount = 1.0))
        assertEquals(true, adminOf(McGameService.OP_TAKE, null, "Steve", amount = 1.0, server = server8).ok)
    }

    @Test
    fun `thirty admin operations a minute per server, then RATE_LIMITED until a token is back`(): Unit = runBlocking {
        user("Steve", credit = 1_000_000)

        repeat(30) { assertEquals(true, adminOf(McGameService.OP_GIVE, null, "Steve", amount = 1.0).ok, "operation ${it + 1}") }

        val limited = adminOf(McGameService.OP_GIVE, null, "Steve", amount = 1.0)

        assertFalse(limited.accepted)
        assertEquals("RATE_LIMITED", limited.reason)
        assertEquals(true, adminOf(McGameService.OP_GIVE, null, "Steve", amount = 1.0, server = server8).ok, "the bucket is per server")

        w.clock.advance(2_000)

        assertEquals(true, adminOf(McGameService.OP_GIVE, null, "Steve", amount = 1.0).ok)
        assertFalse(adminOf(McGameService.OP_GIVE, null, "Steve", amount = 1.0).accepted)
    }

    @Test
    fun `an unknown operation, a bad operationId and the gates are refused first`(): Unit = runBlocking {
        user("Steve", credit = 1_000)

        assertFailed("BAD_REQUEST", adminOf("DESTROY_EVERYTHING", null, "Steve"))
        assertFailed("BAD_REQUEST", adminOf(McGameService.OP_GIVE, null, "Steve", operationId = "short", amount = 1.0))

        ready.set(false)

        assertEquals("MARKET_NOT_READY", adminOf(McGameService.OP_GIVE, null, "Steve", amount = 1.0).reason)

        ready.set(true)

        val request = MarketAdminEventRequest("MARKET_ADMIN", "9.9.9", 1, op(), McGameService.OP_GIVE, GameActor(console = true), GameTarget("Steve"), 1.0, null, null, null)

        assertEquals("VERSION_MISMATCH", game.admin(request, server7).reason)
        assertEquals("PROTOCOL_UNSUPPORTED", game.admin(request.copy(componentVersion = version, protocol = 0), server7).reason)
        assertEquals(1_000, balance(user("Probe")) + 1_000)
        assertEquals(emptyList<String>(), txKeys())
    }

    // ===================================================================================== MARKET_ECONOMY (MC-E6)

    @Test
    fun `a bridge that is switched off refuses every operation with VAULT_DISABLED, a reason the component does not retry`(): Unit = runBlocking {
        val steve = user("Steve", credit = 5_000)

        for (op in listOf("DEPOSIT", "WITHDRAW", "BALANCE")) {
            val r = economyOf(op, "Steve", amount = 1.0)

            assertFalse(r.accepted, op)
            assertEquals("VAULT_DISABLED", r.reason, op)
            assertTrue(r.reason !in setOf("MARKET_NOT_READY", "RATE_LIMITED", "VERSION_MISMATCH", "PROTOCOL_UNSUPPORTED"), "not one of the transient reasons")
        }

        assertEquals(5_000, balance(steve))
        assertEquals(emptyList<String>(), txKeys())
    }

    @Test
    fun `deposit and withdraw post EXTERNAL_IN and EXTERNAL_OUT under the mc key, a replayed id answers from the first transaction`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        val steve = user("Steve", credit = 5_000)
        val deposit = op()

        val d = economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0)

        assertTrue(d.accepted)
        assertEquals(true, d.ok)
        assertEquals(55.0, d.balance)
        assertEquals(5_500, balance(steve))

        val tx = w.creditTxs.getByIdempotencyKey("mc:7:$deposit", pool)!!

        assertEquals(CreditTxType.EXTERNAL_IN, tx.type)
        assertEquals(500, tx.amount)
        assertEquals(steve.id, tx.userId)
        assertNull(tx.actorUserId)
        assertEquals("Lobby", tx.note, "the note is the server")
        assertEquals(-500, system(CreditSystemKey.EXTERNAL), "the counter-account of the server economy")

        // the bridge re-sends the same id after an unknown outcome: one transaction
        val again = economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0)

        assertEquals(true, again.ok)
        assertEquals(55.0, again.balance)
        assertEquals(5_500, balance(steve))
        assertEquals(1, txKeys().size)

        val withdraw = op()
        val wd = economyOf("WITHDRAW", "Steve", operationId = withdraw, amount = 20.0)

        assertEquals(true, wd.ok)
        assertEquals(35.0, wd.balance)
        assertEquals(CreditTxType.EXTERNAL_OUT, w.creditTxs.getByIdempotencyKey("mc:7:$withdraw", pool)!!.type)
        assertEquals(3_500, balance(steve))
        assertEquals(1_500, system(CreditSystemKey.EXTERNAL))

        // a replayed withdrawal is not taken twice
        assertEquals(35.0, economyOf("WITHDRAW", "Steve", operationId = withdraw, amount = 20.0).balance)
        assertEquals(3_500, balance(steve))
        assertEquals(2, txKeys().size)
    }

    @Test
    fun `a withdrawal beyond the balance is refused with INSUFFICIENT_CREDITS and the balance, nothing is posted`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.PROVIDER)

        val steve = user("Steve", credit = 3_000)
        val r = assertFailed("INSUFFICIENT_CREDITS", economyOf("WITHDRAW", "Steve", amount = 30.01))

        assertEquals(30.0, r.balance)
        assertEquals(3_000, balance(steve))
        assertEquals(emptyList<String>(), txKeys())
        assertEquals(0, system(CreditSystemKey.EXTERNAL))

        assertEquals(true, economyOf("WITHDRAW", "Steve", amount = 30.0).ok)
        assertEquals(0, balance(steve))
    }

    @Test
    fun `a compensation id with the undo suffix is an ordinary operation and a balance query is never recorded`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        val steve = user("Steve", credit = 4_000)
        val id = op()

        assertEquals(true, economyOf("WITHDRAW", "Steve", operationId = id, amount = 10.0).ok)
        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = "$id:undo", amount = 10.0).ok)
        assertEquals(4_000, balance(steve))
        assertEquals(listOf("mc:7:$id", "mc:7:$id:undo"), txKeys())

        val balance = economyOf("BALANCE", "Steve")

        assertEquals(true, balance.ok)
        assertEquals(40.0, balance.balance)
        assertEquals(2, txKeys().size, "BALANCE writes nothing")
        assertEquals(0, system(CreditSystemKey.EXTERNAL))
    }

    @Test
    fun `an operation the ledger applied is answered ok again after the bridge or the credits were switched off, a new operation stays refused`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        val steve = user("Steve", credit = 5_000)
        val deposit = op()
        val withdraw = op()

        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0).ok)
        assertEquals(true, economyOf("WITHDRAW", "Steve", operationId = withdraw, amount = 20.0).ok)
        assertEquals(3_500, balance(steve))
        assertEquals(2, txKeys().size)

        // the admin switches the bridge off while the component still settles an unknown outcome with the same id: that must read "applied", never "refused"
        mc = mc.copy(vaultMode = VaultMode.OFF)

        val d = economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0)
        val wd = economyOf("WITHDRAW", "Steve", operationId = withdraw, amount = 20.0)

        assertTrue(d.accepted, "$d")
        assertEquals(true, d.ok, "$d")
        assertEquals(35.0, d.balance, "the balance now")
        assertEquals(true, wd.ok, "$wd")
        assertEquals(35.0, wd.balance)
        assertEquals(3_500, balance(steve), "a replay moves nothing")
        assertEquals(2, txKeys().size, "one transaction per operation")

        // another request for the same id is a conflict, and a new operation is refused by the switch as before
        assertFailed("IDEMPOTENCY_CONFLICT", economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 6.0))
        assertFailed("IDEMPOTENCY_CONFLICT", economyOf("WITHDRAW", "Steve", operationId = deposit, amount = 5.0))
        assertFailed("IDEMPOTENCY_CONFLICT", economyOf("DEPOSIT", "Steve", operationId = withdraw, amount = 20.0))

        for (op in listOf("DEPOSIT", "WITHDRAW", "BALANCE")) {
            val fresh = economyOf(op, "Steve", amount = 1.0)

            assertFalse(fresh.accepted, op)
            assertEquals("VAULT_DISABLED", fresh.reason, op)
        }

        assertEquals("VAULT_DISABLED", economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0, server = server8).reason, "the id was applied on server 7, not on this one")
        assertEquals(3_500, balance(steve))
        assertEquals(2, txKeys().size)

        // the per-server override switches it off just the same, and credits switched off is the same story
        mc = mc.copy(vaultMode = VaultMode.CONVERT)
        game.updateServerSettings(7, JsonObject().put("mcVaultMode", "OFF"))

        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0).ok)
        assertEquals("VAULT_DISABLED", economyOf("DEPOSIT", "Steve", amount = 1.0).reason)

        game.updateServerSettings(7, null)
        h.config = h.config.copy(creditsEnabled = false)

        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0).ok)
        assertEquals(true, economyOf("WITHDRAW", "Steve", operationId = withdraw, amount = 20.0).ok)
        assertFailed("CREDITS_DISABLED", economyOf("DEPOSIT", "Steve", amount = 1.0))
        assertFailed("CREDITS_DISABLED", economyOf("BALANCE", "Steve"))
        assertFailed("IDEMPOTENCY_CONFLICT", economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 6.0))
        assertEquals(3_500, balance(steve))
        assertEquals(2, txKeys().size)

        // both switches back on: the ordinary replay and a new operation work again
        h.config = h.config.copy(creditsEnabled = true)

        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 5.0).ok)
        assertEquals(true, economyOf("DEPOSIT", "Steve", amount = 1.0).ok)
        assertEquals(3_600, balance(steve))
    }

    @Test
    fun `the undo of an applied operation passes a switched-off bridge or credits, any other operation id does not`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        val steve = user("Steve", credit = 4_000)
        val alex = user("Alex", credit = 4_000)
        val withdraw = op()
        val deposit = op()

        assertEquals(true, economyOf("WITHDRAW", "Steve", operationId = withdraw, amount = 10.0).ok)
        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 6.0).ok)
        assertEquals(3_600, balance(steve), "10 out, 6 in")

        mc = mc.copy(vaultMode = VaultMode.OFF)

        // an undo that is not the exact opposite of what the ledger holds under the original id is an ordinary new operation: refused
        for ((label, r) in listOf(
            "wrong amount" to economyOf("DEPOSIT", "Steve", operationId = "$withdraw:undo", amount = 9.0),
            "same direction as the original" to economyOf("WITHDRAW", "Steve", operationId = "$withdraw:undo", amount = 10.0),
            "another player" to economyOf("DEPOSIT", "Alex", operationId = "$withdraw:undo", amount = 10.0),
            "no original under that id" to economyOf("DEPOSIT", "Steve", operationId = "${op()}:undo", amount = 10.0),
            "a new id without the undo suffix" to economyOf("DEPOSIT", "Steve", operationId = op(), amount = 10.0)
        )) {
            assertFalse(r.accepted, "$label: $r")
            assertEquals("VAULT_DISABLED", r.reason, label)
        }

        assertEquals(3_600, balance(steve))
        assertEquals(4_000, balance(alex))
        assertEquals(2, txKeys().size, "nothing was posted by the refused ones")

        // the undo of the applied withdrawal: accepted although the bridge is off, one ledger transaction under its own key
        val undo = economyOf("DEPOSIT", "Steve", operationId = "$withdraw:undo", amount = 10.0)

        assertTrue(undo.accepted, "$undo")
        assertEquals(true, undo.ok, "$undo")
        assertEquals(46.0, undo.balance)
        assertEquals(4_600, balance(steve))
        assertEquals(CreditTxType.EXTERNAL_IN, w.creditTxs.getByIdempotencyKey("mc:7:$withdraw:undo", pool)!!.type)
        assertEquals(listOf("mc:7:$withdraw", "mc:7:$deposit", "mc:7:$withdraw:undo"), txKeys())

        // its own replay while the bridge is still off: the same answer, no second reversal
        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = "$withdraw:undo", amount = 10.0).ok)
        assertEquals(4_600, balance(steve))
        assertEquals(3, txKeys().size)

        // the undo of the applied deposit is a withdrawal
        val reversed = economyOf("WITHDRAW", "Steve", operationId = "$deposit:undo", amount = 6.0)

        assertEquals(true, reversed.ok, "$reversed")
        assertEquals(40.0, reversed.balance)
        assertEquals(4_000, balance(steve))
        assertEquals(CreditTxType.EXTERNAL_OUT, w.creditTxs.getByIdempotencyKey("mc:7:$deposit:undo", pool)!!.type)

        // credits switched off lets the undo through the same way, and still refuses a new operation
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        val third = op()

        assertEquals(true, economyOf("WITHDRAW", "Steve", operationId = third, amount = 4.0).ok)
        assertEquals(3_600, balance(steve))

        h.config = h.config.copy(creditsEnabled = false)

        assertFailed("CREDITS_DISABLED", economyOf("WITHDRAW", "Steve", operationId = op(), amount = 4.0))
        assertFailed("CREDITS_DISABLED", economyOf("DEPOSIT", "Steve", operationId = "${op()}:undo", amount = 4.0))
        assertEquals(3_600, balance(steve))

        val creditsOff = economyOf("DEPOSIT", "Steve", operationId = "$third:undo", amount = 4.0)

        assertEquals(true, creditsOff.ok, "$creditsOff")
        assertEquals(40.0, creditsOff.balance)
        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = "$third:undo", amount = 4.0).ok)
        assertEquals(4_000, balance(steve))
        assertEquals(6, txKeys().size)
    }

    @Test
    fun `an undo that the balance cannot cover is still INSUFFICIENT_CREDITS while the bridge is off`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        val steve = user("Steve", credit = 0)
        val deposit = op()

        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = deposit, amount = 8.0).ok)

        // the player spent the credits meanwhile
        assertEquals(true, economyOf("WITHDRAW", "Steve", amount = 8.0).ok)
        assertEquals(0, balance(steve))

        mc = mc.copy(vaultMode = VaultMode.OFF)

        val undo = assertFailed("INSUFFICIENT_CREDITS", economyOf("WITHDRAW", "Steve", operationId = "$deposit:undo", amount = 8.0))

        assertEquals(0.0, undo.balance)
        assertEquals(0, balance(steve))
        assertEquals(2, txKeys().size)
    }

    @Test
    fun `the same operation id for another type or amount is IDEMPOTENCY_CONFLICT, no account is NO_ACCOUNT, bad amounts are INVALID_AMOUNT, credits off is CREDITS_DISABLED`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        user("Steve", credit = 4_000)

        val id = op()

        assertEquals(true, economyOf("DEPOSIT", "Steve", operationId = id, amount = 2.0).ok)
        assertFailed("IDEMPOTENCY_CONFLICT", economyOf("WITHDRAW", "Steve", operationId = id, amount = 2.0))
        assertFailed("IDEMPOTENCY_CONFLICT", economyOf("DEPOSIT", "Steve", operationId = id, amount = 3.0))
        assertFailed("NO_ACCOUNT", economyOf("DEPOSIT", "Nobody", amount = 2.0))
        assertFailed("NO_ACCOUNT", economyOf("BALANCE", "Nobody"))
        assertFailed("INVALID_AMOUNT", economyOf("DEPOSIT", "Steve", amount = 0.0))
        assertFailed("INVALID_AMOUNT", economyOf("DEPOSIT", "Steve", amount = 1.234))
        assertFailed("INVALID_AMOUNT", economyOf("WITHDRAW", "Steve", amount = null))
        assertFailed("BAD_REQUEST", economyOf("TRANSFER", "Steve", amount = 1.0))
        assertFailed("BAD_REQUEST", economyOf("DEPOSIT", "Steve", operationId = "x", amount = 1.0))
        assertEquals(1, txKeys().size)

        h.config = h.config.copy(creditsEnabled = false)

        assertFailed("CREDITS_DISABLED", economyOf("DEPOSIT", "Steve", amount = 1.0))
    }

    @Test
    fun `the per-server override decides the bridge mode of that server only`(): Unit = runBlocking {
        user("Steve", credit = 4_000)

        game.updateServerSettings(7, JsonObject().put("mcVaultMode", "PROVIDER"))

        assertEquals(true, economyOf("DEPOSIT", "Steve", amount = 1.0, server = server7).ok)
        assertEquals("VAULT_DISABLED", economyOf("DEPOSIT", "Steve", amount = 1.0, server = server8).reason)

        game.updateServerSettings(7, null)

        assertEquals("VAULT_DISABLED", economyOf("DEPOSIT", "Steve", amount = 1.0, server = server7).reason)
    }

    @Test
    fun `the gates of a game event also apply to the economy`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        user("Steve", credit = 4_000)
        ready.set(false)

        assertEquals("MARKET_NOT_READY", economyOf("BALANCE", "Steve").reason)

        ready.set(true)

        val request = MarketEconomyEventRequest("MARKET_ECONOMY", "0.0.1", 1, op(), "BALANCE", GamePlayer("Steve"), null, null)

        assertEquals("VERSION_MISMATCH", game.economy(request, server7).reason)
        assertEquals("PROTOCOL_UNSUPPORTED", game.economy(request.copy(componentVersion = version, protocol = 7), server7).reason)
    }

    // ===================================================================================== MARKET_CONFIG (MC-E7)

    private suspend fun configOf(have: String? = null, server: Server = server7) = game.config(MarketConfigEventRequest("MARKET_CONFIG", version, 1, have), server)

    @Test
    fun `config answers the merged settings and the identity of the store, and an override changes the configHash`(): Unit = runBlocking {
        mc = mc.copy(broadcast = true, template = "&a{player} bought {product}", disabledAdmin = listOf("set-credits"), vaultMode = VaultMode.CONVERT, vaultRate = 2.5, vaultDirection = VaultDirection.TO_SERVER)

        val first = configOf()

        assertTrue(first.accepted)
        assertEquals(7L, first.serverId)
        assertEquals("https://example.com", first.storeUrl)
        assertEquals("Gems", first.creditName)
        assertEquals("EUR", first.currency)
        assertNotNull(first.configHash)
        assertEquals(64, first.configHash!!.length)

        val s = first.settings!!

        assertTrue(s.mcStoreCommand && s.mcCreditsCommand && s.mcStoreMenu && s.mcPlaceholders && s.mcLuckPerms && s.mcAdminCommands)
        assertTrue(s.mcBroadcast)
        assertEquals("&a{player} bought {product}", s.mcBroadcastTemplate)
        assertEquals(listOf("set-credits"), s.mcDisabledAdminCommands)
        assertEquals("CONVERT", s.mcVaultMode)
        assertEquals(2.5, s.mcVaultRate)
        assertEquals("TO_SERVER", s.mcVaultDirection)

        // the same answer again: the hash is stable, the component holding it gets no body
        assertEquals(first.configHash, configOf().configHash)

        val unchanged = configOf(have = first.configHash)

        assertTrue(unchanged.accepted)
        assertEquals(first.configHash, unchanged.configHash)
        assertNull(unchanged.settings, "an unchanged hash carries no settings (the component keeps what it holds)")
        assertTrue(unchanged.texts.isEmpty())

        // an override changes the hash and the settings, for that server only
        val eightBefore = configOf(server = server8).configHash

        assertNotEquals(first.configHash, eightBefore, "the server id is part of what is hashed")

        game.updateServerSettings(7, JsonObject().put("mcStoreMenu", false).put("mcVaultMode", "OFF").put("mcBroadcast", false))

        val overridden = configOf(have = first.configHash)

        assertNotEquals(first.configHash, overridden.configHash)
        assertFalse(overridden.settings!!.mcStoreMenu)
        assertEquals("OFF", overridden.settings!!.mcVaultMode)
        assertFalse(overridden.settings!!.mcBroadcast)
        assertTrue(overridden.settings!!.mcStoreCommand, "a key the override does not name keeps the panel default")
        assertEquals(eightBefore, configOf(server = server8).configHash, "server 8 has no override: its hash did not move")

        // clearing the override brings the first hash back
        game.updateServerSettings(7, null)

        assertEquals(first.configHash, configOf().configHash)
    }

    @Test
    fun `the hash also changes with the credit name, the store address and the texts, so a component re-pulls them`(): Unit = runBlocking {
        val base = configOf().configHash

        mc = mc.copy(creditName = "Coins")

        val named = configOf()

        assertNotEquals(base, named.configHash)
        assertEquals("Coins", named.creditName)

        storeUrl = "https://shop.example.org/"

        val moved = configOf()

        assertNotEquals(named.configHash, moved.configHash)
        assertEquals("https://shop.example.org", moved.storeUrl, "no trailing slash: the component appends /store/<slug> and /register")

        texts = mapOf("en-US" to mapOf("store.link" to "&6Shop: {url}"), "tr" to mapOf("store.link" to "&6Magaza: {url}"))

        val texted = configOf()

        assertNotEquals(moved.configHash, texted.configHash)
        assertEquals(setOf("en-US", "tr"), texted.texts.keys)
        assertEquals("&6Shop: {url}", texted.texts.getValue("en-US").getValue("store.link"))
        assertEquals(texted.configHash, game.configHash(7), "MARKET_SYNC carries the same hash")

        storeUrl = null

        assertNull(configOf().storeUrl)
    }

    @Test
    fun `an override value of the wrong type stored by hand never reaches the configuration`(): Unit = runBlocking {
        w.serverStates.upsertSync(MarketServerState(serverId = 7, createdAt = 1, updatedAt = 1), pool)
        w.serverStates.updateSettings(7, """{"mcStoreMenu":"yes","mcVaultRate":-4,"mcVaultMode":"EVIL","mcDisabledAdminCommands":["rm-rf"],"mcBroadcast":true,"unknown":1}""", 1, pool)

        val s = configOf().settings!!

        assertTrue(s.mcStoreMenu, "a string is not a boolean: the default stays")
        assertEquals(1.0, s.mcVaultRate)
        assertEquals("OFF", s.mcVaultMode)
        assertTrue(s.mcDisabledAdminCommands.isEmpty())
        assertTrue(s.mcBroadcast, "a valid key of the same object still applies")

        // not even valid JSON
        w.serverStates.updateSettings(7, "{not json", 2, pool)

        assertTrue(configOf().settings!!.mcStoreMenu)
    }

    @Test
    fun `the gates answer a refusal without a body`(): Unit = runBlocking {
        ready.set(false)

        val notReady = configOf()

        assertFalse(notReady.accepted)
        assertEquals("MARKET_NOT_READY", notReady.reason)
        assertNull(notReady.settings)
        assertNull(notReady.configHash)

        ready.set(true)

        assertEquals("VERSION_MISMATCH", game.config(MarketConfigEventRequest("MARKET_CONFIG", "0.9", 1, null), server7).reason)
        assertEquals("PROTOCOL_UNSUPPORTED", game.config(MarketConfigEventRequest("MARKET_CONFIG", version, 3, null), server7).reason)
    }

    @Test
    fun `the configHash is carried by MARKET_SYNC and moves with an override`(): Unit = runBlocking {
        val d = DeliveryWorld(w)
        val link = FakeMcLink().also { it.add(7) }

        d.roster.granted = listOf(7L)

        val sync = McSyncService(
            c.ph.db, d.locks, w.clock, { cfg() }, w.deliveries, w.serverStates, w.orders, w.orderItems, d.service, link, { version }, { true },
            configHash = { serverId -> game.configHash(serverId) }
        )
        val request = MarketSyncEventRequest(componentVersion = version, protocol = 1, capacity = 5)

        val before = sync.handle(request, server7)

        assertTrue(before.accepted)
        assertEquals(game.configHash(7), before.configHash)
        assertNotNull(before.configHash)

        game.updateServerSettings(7, JsonObject().put("mcPlaceholders", false))

        val after = sync.handle(request, server7)

        assertNotEquals(before.configHash, after.configHash, "the component notices the change within one sync and re-pulls MARKET_CONFIG")
        assertEquals(configOf().configHash, after.configHash)
        assertEquals(false, configOf().settings!!.mcPlaceholders)
    }

    // ===================================================================================== the per-server settings write

    @Test
    fun `a settings override is validated, stored, creates the row of a server that never synced and clears with null`(): Unit = runBlocking {
        assertNull(w.serverStates.getByServerId(7, pool))

        assertEquals(emptyMap<String, String>(), game.updateServerSettings(7, JsonObject().put("mcStoreMenu", false).put("mcVaultRate", 3)))

        val row = w.serverStates.getByServerId(7, pool)!!

        assertEquals(JsonObject().put("mcStoreMenu", false).put("mcVaultRate", 3), JsonObject(row.settings!!))
        assertNull(row.mcComponentVersion)
        assertNull(row.lastSeenAt)

        // invalid values and unknown keys: nothing is written, every error is named
        val errors = game.updateServerSettings(
            7,
            JsonObject().put("mcStoreMenu", "yes").put("mcVaultMode", "EVIL").put("mcVaultRate", 0).put("mcDisabledAdminCommands", listOf("nope")).put("mcBroadcastTemplate", "").put("whatever", 1)
        )

        assertEquals(
            mapOf("mcStoreMenu" to "INVALID", "mcVaultMode" to "INVALID", "mcVaultRate" to "INVALID", "mcDisabledAdminCommands" to "INVALID", "mcBroadcastTemplate" to "INVALID", "whatever" to "UNKNOWN"),
            errors
        )
        assertEquals(JsonObject().put("mcStoreMenu", false).put("mcVaultRate", 3), JsonObject(w.serverStates.getByServerId(7, pool)!!.settings!!), "a refused write leaves the override as it was")

        assertEquals(emptyMap<String, String>(), game.updateServerSettings(7, JsonObject()))
        assertNull(w.serverStates.getByServerId(7, pool)!!.settings, "an empty object clears the override")

        game.updateServerSettings(7, JsonObject().put("mcLuckPerms", false))
        game.updateServerSettings(7, null)

        assertNull(w.serverStates.getByServerId(7, pool)!!.settings)
    }

    @Test
    fun `writing the override never touches what the last sync recorded`(): Unit = runBlocking {
        w.serverStates.upsertSync(
            MarketServerState(
                serverId = 7, mcComponentVersion = version, capabilities = "market-delivery,vault", platform = "PAPER", protocol = 1, queuedCount = 3, lastSeenAt = 123_456,
                createdAt = 1, updatedAt = 1
            ),
            pool
        )

        assertEquals(emptyMap<String, String>(), game.updateServerSettings(7, JsonObject().put("mcPlaceholders", false)))

        val row = w.serverStates.getByServerId(7, pool)!!

        assertEquals(version, row.mcComponentVersion)
        assertEquals("market-delivery,vault", row.capabilities)
        assertEquals("PAPER", row.platform)
        assertEquals(3, row.queuedCount)
        assertEquals(123_456L, row.lastSeenAt)
        assertEquals(JsonObject().put("mcPlaceholders", false), JsonObject(row.settings!!))

        // a later sync write keeps the override too (upsertSync never writes `settings` of an existing row)
        w.serverStates.upsertSync(MarketServerState(serverId = 7, mcComponentVersion = "1.5.0", capabilities = "market-delivery", lastSeenAt = 999_999, createdAt = 2, updatedAt = 2), pool)

        assertEquals(JsonObject().put("mcPlaceholders", false), JsonObject(w.serverStates.getByServerId(7, pool)!!.settings!!))
    }

    @Test
    fun `the setting keys are the thirteen mc keys of the panel defaults and the validator accepts exactly their types`() {
        assertEquals(13, McSettingKeys.ALL.size)
        assertEquals(McSettingKeys.ALL.size, McSettingKeys.ALL.toSet().size)

        val defaults = MarketConfig::class.java.declaredFields.map { it.name }.filter { it.startsWith("mc") }.toSet()

        assertEquals(defaults, McSettingKeys.ALL.toSet(), "every `mc*` key of MarketConfig is an overridable key and the other way round")

        assertEquals(emptyMap<String, String>(), McSettingKeys.validate(JsonObject().put("mcStoreCommand", true).put("mcVaultRate", 1.5).put("mcDisabledAdminCommands", listOf("purchases", "give-credits"))))
        assertEquals(mapOf("mcStoreCommand" to "INVALID"), McSettingKeys.validate(JsonObject().put("mcStoreCommand", 1)))
        assertEquals(mapOf("mcVaultDirection" to "INVALID"), McSettingKeys.validate(JsonObject().put("mcVaultDirection", "SIDEWAYS")))
        assertEquals(mapOf("mcVaultRate" to "INVALID"), McSettingKeys.validate(JsonObject().put("mcVaultRate", 1e12)))
        assertEquals(mapOf("mcBroadcastTemplate" to "INVALID"), McSettingKeys.validate(JsonObject().put("mcBroadcastTemplate", "a\nb")))
        assertEquals(mapOf("mcBroadcastTemplate" to "INVALID"), McSettingKeys.validate(JsonObject().put("mcBroadcastTemplate", "x".repeat(300))))
        assertEquals(mapOf("mcStoreCommand" to "INVALID"), McSettingKeys.validate(JsonObject().putNull("mcStoreCommand")))
    }

    // ===================================================================================== MARKET_QUERY

    @Test
    fun `balance answers the spendable credits and the credit name, unregistered players have no account, credits off is a refusal`(): Unit = runBlocking {
        user("Steve", credit = 12_550)

        val r = queryOf("BALANCE", "Steve")

        assertTrue(r.accepted)
        assertEquals(true, r.data!!.registered)
        assertEquals(125.5, r.data!!.balance)
        assertEquals("Gems", r.data!!.creditName)

        val none = queryOf("BALANCE", "Nobody")

        assertTrue(none.accepted)
        assertEquals(false, none.data!!.registered)
        assertEquals(0.0, none.data!!.balance)
        assertEquals("Gems", none.data!!.creditName)

        mc = mc.copy(creditName = "  ")

        assertEquals("Credits", queryOf("BALANCE", "Steve").data!!.creditName, "a blank name falls back")

        assertEquals("BAD_REQUEST", queryOf("BALANCE", player = null).reason)

        h.config = h.config.copy(creditsEnabled = false)

        val off = queryOf("BALANCE", "Steve")

        assertFalse(off.accepted)
        assertEquals("CREDITS_DISABLED", off.reason)
    }

    @Test
    fun `purchases lists the own paid orders, never another player's and never a test order`(): Unit = runBlocking {
        user("Steve", credit = 100_000)
        user("Alex", credit = 100_000)

        val cape = product(slug = "cape", name = "Cape")
        val s = purchaseOf("Steve", cape.id)
        val a = purchaseOf("Alex", cape.id)

        assertEquals(true, s.ok)
        assertEquals(true, a.ok)

        // a purchase made while the store is in test mode is a test order
        h.config = h.config.copy(testMode = true)

        val test = purchaseOf("Steve", product(slug = "hat", name = "Hat").id)

        h.config = h.config.copy(testMode = false)

        assertEquals(true, test.ok)
        assertTrue(orderRow(test.orderPublicId!!).testMode)

        val steve = queryOf("PURCHASES", "Steve").data!!.orders!!

        assertEquals(listOf(s.orderPublicId), steve.map { it.publicId })
        assertEquals(listOf("Cape"), steve.single().itemNames)
        assertEquals(30.0, steve.single().total, "the money total of the order")
        assertEquals("COMPLETED", steve.single().status)

        assertEquals(emptyList<Any>(), queryOf("PURCHASES", "Nobody").data!!.orders)
        assertEquals("BAD_REQUEST", queryOf("PURCHASES", player = null).reason)
    }

    @Test
    fun `placeholders answer the last buyer, the top supporter, the goal and the balance of the asked players`(): Unit = runBlocking {
        user("Steve", credit = 100_000)
        user("Alex", credit = 100_000)
        user("Idle", credit = 777)

        val p = product(slug = "cape", name = "Cape")

        assertEquals(true, purchaseOf("Steve", p.id).ok)
        w.clock.advance(1_000)
        assertEquals(true, purchaseOf("Alex", p.id, quantity = 2).ok)

        val goal = w.goals.add(MarketGoal(name = "Server upgrade", metric = GoalMetric.REVENUE, target = 100_000, currency = "EUR", createdAt = 1, updatedAt = 1), pool)

        w.goals.addProgress(goal, 42_500, 1, pool)

        val r = queryOf("PLACEHOLDERS", args = GameQueryArgs(usernames = listOf("Steve", "Idle", "Nobody", "Idle")))

        assertTrue(r.accepted)

        val d = r.data!!

        assertEquals("Alex", d.lastBuyer)
        assertEquals("Alex", d.topSupporter)
        assertEquals("Server upgrade", d.goalName)
        assertEquals(42.0, d.goalPercent)
        assertEquals(425.0, d.goalProgress)
        assertEquals(1000.0, d.goalTarget)
        assertEquals(setOf("Steve", "Idle"), d.players!!.keys, "an unknown name is left out, a repeated one counted once")
        assertEquals(975.0, d.players!!.getValue("Steve").balance)
        assertEquals(7.77, d.players!!.getValue("Idle").balance)
    }

    @Test
    fun `placeholders cap the asked players at one hundred, leave out balances while credits are off and are empty without data`(): Unit = runBlocking {
        user("Steve", credit = 1_000)

        val empty = queryOf("PLACEHOLDERS").data!!

        assertNull(empty.lastBuyer)
        assertNull(empty.topSupporter)
        assertNull(empty.goalName)
        assertEquals(emptyMap<String, Any>(), empty.players)

        val many = (1..150).map { "Player$it" } + "Steve"

        assertEquals(emptyMap<String, Any>(), queryOf("PLACEHOLDERS", args = GameQueryArgs(usernames = many)).data!!.players, "the first hundred names are looked at, Steve is the 151st")
        assertEquals(setOf("Steve"), queryOf("PLACEHOLDERS", args = GameQueryArgs(usernames = listOf("Steve") + many)).data!!.players!!.keys)

        h.config = h.config.copy(creditsEnabled = false)

        assertEquals(emptyMap<String, Any>(), queryOf("PLACEHOLDERS", args = GameQueryArgs(usernames = listOf("Steve"))).data!!.players)
    }

    @Test
    fun `pending counts the unfinished grants of the player on this server and lists the gifts that wait for them`(): Unit = runBlocking {
        val steve = user("Steve")
        val d = DeliveryWorld(w)
        val grant = com.panomc.plugins.market.core.delivery.ProductAction(
            id = "c1", type = com.panomc.plugins.market.db.model.DeliveryActionType.COMMAND, commands = listOf("give {username} diamond 1")
        )

        d.roster.granted = listOf(7L, 8L)

        val placed = d.place(buyer = "Steve", user = steve, actions = listOf(grant), quantity = 2)

        d.pay(placed)

        val rows = d.rows(placed.order.id)

        assertTrue(rows.size >= 2)

        val pending = queryOf("PENDING", "Steve").data!!

        assertEquals(rows.count { it.serverId == 7L }, pending.deliveriesQueued)
        assertTrue(pending.gifts!!.isEmpty())
        assertEquals(rows.count { it.serverId == 8L }, queryOf("PENDING", "Steve", server = server8).data!!.deliveriesQueued)
        assertEquals(0, queryOf("PENDING", "Alex").data!!.deliveriesQueued)

        // a gift order: the payer's name is the sender, a hidden purchase stays anonymous
        val payer = user("Giver")
        val gift = d.place(buyer = "Giver", user = payer, actions = listOf(grant))

        d.pay(gift)
        Fixtures.setColumns(pool, "market_order", gift.order.id, mapOf("isGift" to true, "recipientUsername" to "Steve", "recipientUserId" to steve.id))
        sql("UPDATE `pano_market_delivery` SET `playerUsername` = 'Steve' WHERE `orderId` = ?", gift.order.id)

        val withGift = queryOf("PENDING", "Steve").data!!

        assertEquals(listOf("Giver"), withGift.gifts!!.map { it.from })
        assertEquals(listOf("VIP"), withGift.gifts!!.map { it.productName })
        assertEquals(listOf(gift.order.publicId), withGift.gifts!!.map { it.orderPublicId })

        Fixtures.setColumns(pool, "market_order", gift.order.id, mapOf("hideFromBroadcast" to true))

        assertNull(queryOf("PENDING", "Steve").data!!.gifts!!.single().from, "a buyer who hid the purchase stays anonymous to the recipient too")
        assertEquals("BAD_REQUEST", queryOf("PENDING", player = null).reason)
    }

    @Test
    fun `an unknown query type, the gates and twenty queries a second per server`(): Unit = runBlocking {
        user("Steve", credit = 1_000)

        assertEquals("UNSUPPORTED_TYPE", queryOf("WHATEVER", "Steve").reason)

        ready.set(false)

        assertEquals("MARKET_NOT_READY", queryOf("BALANCE", "Steve").reason)

        ready.set(true)

        val request = MarketQueryEventRequest("MARKET_QUERY", "0.1", 1, "BALANCE", GamePlayer("Steve"), null, null)

        assertEquals("VERSION_MISMATCH", game.query(request, server7).reason)
        assertEquals("PROTOCOL_UNSUPPORTED", game.query(request.copy(componentVersion = version, protocol = 5), server7).reason)

        // the gates and the unsupported type above took tokens or none: start from a fresh bucket on server 8
        var accepted = 0

        repeat(25) { if (queryOf("BALANCE", "Steve", server = server8).accepted) accepted++ }

        assertEquals(20, accepted, "a burst of 20, the rest is RATE_LIMITED")
        assertEquals("RATE_LIMITED", queryOf("BALANCE", "Steve", server = server8).reason)
        assertTrue(queryOf("BALANCE", "Steve", server = server7).accepted, "the bucket is per server")

        w.clock.advance(50)

        assertTrue(queryOf("BALANCE", "Steve", server = server8).accepted, "a token comes back every 50 ms")
        assertFalse(queryOf("BALANCE", "Steve", server = server8).accepted)
    }

    // ===================================================================================== MARKET_QUERY CATALOG

    @Test
    fun `catalog lists the root categories and one page of products with prices, stock, slug and the verdict for the player`(): Unit = runBlocking {
        user("Steve", credit = 100_000)

        val ranks = fx.category("Ranks")
        val items = fx.category("Items")

        fx.category("Sub", parentId = ranks.id)

        val vip = product(slug = "vip", name = "VIP", price = 1_000, creditPrice = 500, stock = 4, categoryId = ranks.id)
        val sword = product(slug = "sword", name = "Sword", price = 300, creditPrice = 0, categoryId = items.id)

        Fixtures.setColumns(pool, "market_product", vip.id, mapOf("icon" to "DIAMOND", "shortDescription" to "Shiny"))

        val r = queryOf("CATALOG", "Steve", page = 1)

        assertTrue(r.accepted)

        val d = r.data!!

        assertEquals(setOf("Ranks", "Items"), d.categories!!.map { it.name }.toSet(), "ACTIVE root categories only, the sub-category is inside its root")
        assertEquals(2, d.categories!!.size)
        assertEquals(1, d.page)
        assertEquals(1, d.totalPage)
        assertEquals(setOf("VIP", "Sword"), d.products!!.map { it.name }.toSet())

        val gameVip = d.products!!.single { it.name == "VIP" }

        assertEquals(vip.id, gameVip.id)
        assertEquals(5.0, gameVip.creditPrice)
        assertEquals(10.0, gameVip.price)
        assertEquals("EUR", gameVip.currency)
        assertEquals(4, gameVip.stockLeft)
        assertEquals("DIAMOND", gameVip.icon)
        assertEquals("Shiny", gameVip.shortDescription)
        assertEquals("vip", gameVip.slug)
        assertFalse(gameVip.needsWeb)
        assertTrue(gameVip.purchasable.ok)
        assertNull(gameVip.purchasable.reason)

        val gameSword = d.products!!.single { it.name == "Sword" }

        assertNull(gameSword.creditPrice, "no credit price: the GUI hands out the product link")
        assertTrue(gameSword.needsWeb)
        assertEquals(sword.id, gameSword.id)

        // the category filter
        val filtered = queryOf("CATALOG", "Steve", args = GameQueryArgs(categoryId = items.id)).data!!

        assertEquals(listOf("Sword"), filtered.products!!.map { it.name })
        assertEquals(setOf("Ranks", "Items"), filtered.categories!!.map { it.name }.toSet(), "the categories come with every page")
        assertEquals(emptyList<Any>(), queryOf("CATALOG", "Steve", args = GameQueryArgs(categoryId = 9_999)).data!!.products)
    }

    @Test
    fun `catalog marks what the chest GUI cannot sell as needing the web`(): Unit = runBlocking {
        user("Steve", credit = 100_000)

        val variants = product(slug = "sized", name = "Sized", columns = mapOf("hasVariants" to true)).also { fx.variant(it, name = "L", price = 500) }
        val physical = product(slug = "box", name = "Box", columns = mapOf("physical" to true))
        val subscription = product(slug = "sub", name = "Sub", columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))
        val pack = product(slug = "pack", name = "Pack", columns = mapOf("kind" to "CREDIT_PACK"))
        val choice = """[{"id":"a1","type":"COMMAND","phase":"GRANT","via":"SERVER","serverMode":"BUYER_CHOICE","commands":["give {player} diamond"]}]"""
        val here = product(slug = "here", name = "Here", columns = mapOf("actions" to choice, "serverChoices" to "[7]"))
        val elsewhere = product(slug = "there", name = "There", columns = mapOf("actions" to choice, "serverChoices" to "[8]"))
        val plain = product(slug = "plain", name = "Plain")

        h.config = h.config.copy(creditTopUpEnabled = true)

        val byName = queryOf("CATALOG", "Steve").data!!.products!!.associateBy { it.name }

        assertTrue(byName.getValue("Sized").needsWeb, "variants")
        assertTrue(byName.getValue("Box").needsWeb, "physical")
        assertTrue(byName.getValue("Sub").needsWeb, "subscription")
        assertTrue(byName.getValue("Pack").needsWeb, "credit packs are never payable with credits")
        assertTrue(byName.getValue("There").needsWeb, "a server choice this server is not part of")
        assertFalse(byName.getValue("Here").needsWeb, "this server is one of the choices: the purchase passes it as the target")
        assertFalse(byName.getValue("Plain").needsWeb)

        // server 8 sees the other one
        val on8 = queryOf("CATALOG", "Steve", server = server8).data!!.products!!.associateBy { it.name }

        assertTrue(on8.getValue("Here").needsWeb)
        assertFalse(on8.getValue("There").needsWeb)

        assertNotNull(variants)
        assertNotNull(physical)
        assertNotNull(subscription)
        assertNotNull(pack)
        assertNotNull(here)
        assertNotNull(elsewhere)
        assertNotNull(plain)
    }

    @Test
    fun `catalog evaluates stock, limit, cooldown and prerequisites for that player`(): Unit = runBlocking {
        user("Steve", credit = 100_000)
        user("Alex", credit = 100_000)

        val sold = product(slug = "sold", name = "Sold", stock = 0)
        val once = product(slug = "once", name = "Once", columns = mapOf("limitPerPlayer" to 1))
        val slow = product(slug = "slow", name = "Slow", columns = mapOf("cooldownSeconds" to 3_600))
        val base = product(slug = "base", name = "Base")
        val upgrade = product(slug = "upgrade", name = "Upgrade", columns = mapOf("requiredProducts" to "[${base.id}]"))
        val free = product(slug = "free", name = "Free")

        assertEquals(true, purchaseOf("Steve", once.id).ok)
        assertEquals(true, purchaseOf("Steve", slow.id).ok)

        val steve = queryOf("CATALOG", "Steve").data!!.products!!.associateBy { it.name }

        assertEquals("OUT_OF_STOCK", steve.getValue("Sold").purchasable.reason)
        assertFalse(steve.getValue("Sold").purchasable.ok)
        assertEquals("PURCHASE_LIMIT_REACHED", steve.getValue("Once").purchasable.reason)
        assertEquals("COOLDOWN_ACTIVE", steve.getValue("Slow").purchasable.reason)
        assertEquals("REQUIREMENT_NOT_MET", steve.getValue("Upgrade").purchasable.reason)
        assertTrue(steve.getValue("Free").purchasable.ok)
        assertTrue(steve.getValue("Base").purchasable.ok)

        // the same page for a player who has bought nothing
        val alex = queryOf("CATALOG", "Alex").data!!.products!!.associateBy { it.name }

        assertTrue(alex.getValue("Once").purchasable.ok)
        assertTrue(alex.getValue("Slow").purchasable.ok)

        // a player without an account cannot buy anything in game
        val stranger = queryOf("CATALOG", "Nobody").data!!.products!!

        assertTrue(stranger.isNotEmpty())
        assertTrue(stranger.all { !it.purchasable.ok && it.purchasable.reason == "LOGIN_REQUIRED" })

        // a blocked player: the reason is the block, whatever the product
        h.blocked = { payer, _, _, _, _ -> payer == "Alex" }

        assertTrue(queryOf("CATALOG", "Alex").data!!.products!!.filter { it.name != "Sold" }.all { it.purchasable.reason == "BUYER_BLOCKED" })

        assertNotNull(sold)
        assertNotNull(upgrade)
        assertNotNull(free)
    }

    @Test
    fun `catalog pages in forty-five products and a page beyond the last falls back to the first`(): Unit = runBlocking {
        user("Steve", credit = 100_000)

        for (i in 1..50) product(slug = "p$i", name = "Product ${i.toString().padStart(2, '0')}", columns = mapOf("priority" to 100 - i))

        val first = queryOf("CATALOG", "Steve", page = 1).data!!

        assertEquals(45, first.products!!.size)
        assertEquals(1, first.page)
        assertEquals(2, first.totalPage)
        assertEquals("Product 01", first.products!!.first().name)

        val second = queryOf("CATALOG", "Steve", page = 2).data!!

        assertEquals(5, second.products!!.size)
        assertEquals(2, second.page)
        assertEquals("Product 46", second.products!!.first().name)
        assertTrue(first.products!!.map { it.id }.intersect(second.products!!.map { it.id }.toSet()).isEmpty())

        val beyond = queryOf("CATALOG", "Steve", page = 9).data!!

        assertEquals(1, beyond.page)
        assertEquals(45, beyond.products!!.size)

        assertEquals(1, queryOf("CATALOG", "Steve", page = 0).data!!.page, "a page below 1 is page 1")
    }

    // ===================================================================================== races (money)

    @Test
    fun `eight concurrent purchases with one operationId place one order and take the credits once`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val alex = user("Racer$round", credit = 10_000)
            val p = product(slug = "race-p$round", stock = 5)
            val operationId = op()
            val answers = Race.run(8) { purchaseOf("Racer$round", p.id, operationId = operationId) }.map { it.getOrThrow() }

            assertTrue(answers.all { it.ok == true }, "round $round: $answers")
            assertEquals(1, answers.map { it.orderPublicId }.distinct().size, "round $round: every answer names the one order")
            assertEquals(7_500, balance(alex), "round $round: the credits were taken once")
            assertEquals(4, w.products.getById(p.id, pool)!!.stock, "round $round: one unit of stock")
            assertEquals(1, sql("SELECT COUNT(*) AS n FROM `pano_market_order` WHERE `userId` = ?", alex.id).single().getLong("n"))
        }
    }

    @Test
    fun `concurrent purchases of one buyer never spend more credits than the balance`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val alex = user("Spender$round", credit = 6_000)
            val p = product(slug = "race-s$round")
            val answers = Race.run(8) { purchaseOf("Spender$round", p.id) }.map { it.getOrThrow() }

            assertEquals(2, answers.count { it.ok == true }, "round $round: 6000 pays two purchases of 2500: $answers")
            assertTrue(answers.filter { it.ok != true }.all { it.code == "INSUFFICIENT_CREDITS" }, "round $round: $answers")
            assertEquals(1_000, balance(alex), "round $round")
        }
    }

    @Test
    fun `concurrent economy and admin requests with one operation id post one transaction`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.CONVERT)

        repeat(Race.rounds) { round ->
            val steve = user("Eco$round", credit = 1_000)
            val deposit = op()
            val give = op()
            val deposits = Race.run(8) { economyOf("DEPOSIT", "Eco$round", operationId = deposit, amount = 5.0) }.map { it.getOrThrow() }

            assertTrue(deposits.all { it.ok == true }, "round $round: $deposits")
            assertEquals(1_500, balance(steve), "round $round: one deposit")

            // the admin bucket is 30 an operation per minute and server: a fresh clock second per round keeps it out of the way
            w.clock.advance(60_000)

            val gives = Race.run(8) { adminOf(McGameService.OP_GIVE, null, "Eco$round", operationId = give, amount = 2.0) }.map { it.getOrThrow() }

            assertTrue(gives.all { it.ok == true }, "round $round: $gives")
            assertEquals(1_700, balance(steve), "round $round: one grant")
            assertEquals(listOf("mc:7:$deposit", "mc:7:$give"), sql("SELECT `idempotencyKey` FROM `pano_market_credit_tx` WHERE `userId` = ? AND `idempotencyKey` LIKE 'mc:%' ORDER BY `id`", steve.id).map { it.getString("idempotencyKey") })
        }
    }

    @Test
    fun `concurrent withdrawals never overdraw, the sum of the successes is what the balance paid`(): Unit = runBlocking {
        mc = mc.copy(vaultMode = VaultMode.PROVIDER)

        repeat(Race.rounds) { round ->
            val steve = user("Drain$round", credit = 3_500)
            val answers = Race.run(10) { economyOf("WITHDRAW", "Drain$round", amount = 10.0) }.map { it.getOrThrow() }

            assertEquals(3, answers.count { it.ok == true }, "round $round: 35 credits pay three withdrawals of 10: $answers")
            assertTrue(answers.filter { it.ok != true }.all { it.code == "INSUFFICIENT_CREDITS" }, "round $round: $answers")
            assertEquals(500, balance(steve), "round $round")
        }
    }

    // ===================================================================================== the component download

    private fun jar(bytes: Int): java.nio.file.Path {
        val file = Files.createTempFile("pano-plugin-market-test", ".jar")

        Files.write(file, ByteArray(bytes) { (it * 31 + 7).toByte() })
        file.toFile().deleteOnExit()

        return file
    }

    @Test
    fun `the download is the running jar under the name of the version, the Fabric jar only when it was bundled`() {
        val running = jar(4_096)
        val fabric = byteArrayOf(1, 2, 3, 4, 5)
        val d = McComponentDownload({ running }, { fabric }, { "1.4.0-alpha.7" })

        val plain = d.resolve(null) as McDownload.OnDisk

        assertEquals(running, plain.path)
        assertEquals("pano-plugin-market-1.4.0-alpha.7.jar", plain.fileName)
        assertEquals(plain.path, (d.resolve("  ") as McDownload.OnDisk).path)

        val fab = d.resolve("fabric") as McDownload.InMemory

        assertEquals(fabric.toList(), fab.bytes.toList())
        assertEquals("pano-plugin-market-fabric-1.4.0-alpha.7.jar", fab.fileName)
        assertEquals("pano-plugin-market-fabric-1.4.0-alpha.7.jar", (d.resolve(" FABRIC ") as McDownload.InMemory).fileName)

        assertNull(d.resolve("spigot"), "no other platform has its own jar")
        assertNull(McComponentDownload({ running }, { null }, { "1" }).resolve("fabric"), "a build without -Pfabric: 404")
        assertNull(McComponentDownload({ running }, { ByteArray(0) }, { "1" }).resolve("fabric"))
        assertNull(McComponentDownload({ null }, { fabric }, { "1" }).resolve(null), "the running jar cannot be found")
        assertNull(McComponentDownload({ running.resolveSibling("does-not-exist.jar") }, { fabric }, { "1" }).resolve(null))

        // the version never breaks out of the header
        assertEquals("pano-plugin-market-1.0_x_.jar", (McComponentDownload({ running }, { null }, { "1.0\"x\r" }).resolve(null) as McDownload.OnDisk).fileName)
        assertEquals("pano-plugin-market-unknown.jar", (McComponentDownload({ running }, { null }, { "" }).resolve(null) as McDownload.OnDisk).fileName)
    }

    @Test
    fun `the download streams the running jar byte for byte as an attachment, the Fabric jar from memory`(): Unit = runBlocking {
        val running = jar(300_000)
        val fabric = ByteArray(2_048) { (it % 251).toByte() }
        val downloads = McComponentDownload({ running }, { fabric }, { "2.0.0" })
        val server = vertx.createHttpServer()
            .requestHandler { request ->
                scope.launch {
                    val download = downloads.resolve(request.getParam("platform"))

                    if (download == null) request.response().setStatusCode(404).end() else downloads.send(request.response(), download)
                }
            }
            .listen(0, "127.0.0.1").coAwait()
        val client = WebClient.create(vertx)

        try {
            val jarResponse = client.get(server.actualPort(), "127.0.0.1", "/download").send().coAwait()

            assertEquals(200, jarResponse.statusCode())
            assertEquals(Files.readAllBytes(running).toList(), jarResponse.body().bytes.toList(), "the file Pano runs, byte for byte")
            assertEquals("attachment; filename=\"pano-plugin-market-2.0.0.jar\"", jarResponse.getHeader("Content-Disposition"))
            assertEquals("application/java-archive", jarResponse.getHeader("Content-Type"))
            assertEquals("nosniff", jarResponse.getHeader("X-Content-Type-Options"))

            val fabricResponse = client.get(server.actualPort(), "127.0.0.1", "/download?platform=fabric").send().coAwait()

            assertEquals(200, fabricResponse.statusCode())
            assertEquals(fabric.toList(), fabricResponse.body().bytes.toList())
            assertEquals("attachment; filename=\"pano-plugin-market-fabric-2.0.0.jar\"", fabricResponse.getHeader("Content-Disposition"))

            assertEquals(404, client.get(server.actualPort(), "127.0.0.1", "/download?platform=bedrock").send().coAwait().statusCode())
        } finally {
            client.close()
            server.close().coAwait()
        }
    }
}
