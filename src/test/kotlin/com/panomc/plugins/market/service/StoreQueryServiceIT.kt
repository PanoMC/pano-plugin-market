package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.platform.error.PageNotFound
import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.db.model.MarketComparison
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductPrice
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * `StoreQueryService` on a real MariaDB (MK-064): what `GET /store`, `GET /store/products` and `GET /products/:slug` serve.
 * Cards carry no description, paging beyond the last page is `PAGE_NOT_FOUND`, a product in an inactive category or an
 * ARCHIVED one is 404, the page description is sanitised on read, DISPLAY mode converts with the stored rate, MULTI falls
 * back with CONVERT or HIDE, and the response keys of the old `GET /store` are still all there (golden key set).
 *
 * The rows are written raw (entitlements, `soldCount`, comparisons), so the order / counter invariants are not checked here.
 */
class StoreQueryServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val fx by lazy { Fixtures(w) }

    @Volatile
    private var config = MarketConfig()

    @Volatile
    private var servers: List<ServerChoice> = emptyList()

    private val service by lazy {
        StoreQueryService(
            { config }, w.clock, w.categories, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.discounts,
            w.currencyRates, w.comparisons, w.orderItems, w.entitlements,
            serverChoices = { ids, _ -> servers.filter { it.id in ids } }
        )
    }

    override suspend fun assertInvariants() {}

    override suspend fun resetState() {
        super.resetState()
        config = MarketConfig()
        servers = emptyList()
    }

    private suspend fun store(currency: String? = null, viewer: StoreViewer = StoreViewer.GUEST) = service.store(currency, viewer, pool)

    private suspend fun list(query: ProductListQuery = ProductListQuery(), viewer: StoreViewer = StoreViewer.GUEST) = service.products(query, viewer, pool)

    private suspend fun page(slug: String, currency: String? = null, viewer: StoreViewer = StoreViewer.GUEST) = service.product(slug, currency, viewer, pool)

    private fun names(array: JsonArray) = array.map { (it as JsonObject).getString("slug") }

    private suspend fun raw(product: MarketProduct, vararg values: Pair<String, Any?>) = Fixtures.setColumns(pool, "market_product", product.id, mapOf(*values))

    private suspend fun rate(currency: String, rate: String, mode: CurrencyRateMode = CurrencyRateMode.MANUAL) {
        w.currencyRates.upsert(MarketCurrencyRate(currency = currency, rate = BigDecimal(rate), mode = mode, fetchedAt = w.clock.now()), pool)
    }

    private suspend fun entitlement(userId: Long, product: MarketProduct, categoryId: Long? = null, rank: Int? = null, pricePaid: Long = 0): Long =
        w.entitlements.add(
            MarketEntitlement(
                userId = userId, playerUsername = "u$userId", ownerKey = "u:$userId", productId = product.id, orderId = 1, orderItemId = 1,
                tierCategoryId = categoryId, tierRank = rank, pricePaid = pricePaid, startsAt = w.clock.now() - 1000
            ),
            pool
        )

    // ------------------------------------------------------------------------------------------------ shape

    @Test
    fun `cards carry no description and exactly the ProductCard keys`(): Unit = runBlocking {
        val product = fx.product(slug = "vip", price = 5000)
        raw(product, "description" to "<p>long text</p>", "shortDescription" to "short")

        val card = store().getJsonArray("items").getJsonObject(0)
        val listed = list().getJsonArray("items").getJsonObject(0)

        for (c in listOf(card, listed)) {
            assertFalse(c.containsKey("description"), "a card never carries the description")
            assertEquals("short", c.getString("shortDescription"))
            assertEquals(
                setOf(
                    "id", "slug", "name", "shortDescription", "categoryId", "kind", "price", "compareAtPrice", "creditPrice", "currency", "priceFrom",
                    "inStock", "stock", "featured", "priority", "icon", "imageFileName", "physical", "billingMode", "period", "hasVariants", "tierRank",
                    "sale", "needsOptions", "owned"
                ),
                c.fieldNames()
            )
            assertEquals(50.0, c.getDouble("price"))
            assertEquals("TRY", c.getString("currency"))
            assertNull(c.getValue("owned"), "owned is only for a logged-in caller")
        }
    }

    @Test
    fun `GET store keeps every key of the old response and adds the new ones`(): Unit = runBlocking {
        fx.product(slug = "a")
        val body = store()

        // the keys of GET /store on the core page shape (04 section 4): the first page as items + page, the rest as extra keys
        for (key in listOf("settings", "categories", "items", "page", "bestsellers", "comparisons")) assertTrue(body.containsKey(key), "top-level $key")
        for (key in listOf(
            "storeName", "storeDescription", "currency", "currencySymbol", "creditsEnabled", "creditName", "removeCents", "showBestsellers",
            "showFeaturedProducts", "showComparisons"
        )) assertTrue(body.getJsonObject("settings").containsKey(key), "settings.$key")
        // ... and the additions of 04 section 3
        assertEquals(
            setOf("items", "page", "settings", "categories", "bestsellers", "comparisons", "featured", "comparisonProducts"),
            body.fieldNames()
        )
        val settings = body.getJsonObject("settings")
        for (key in listOf(
            "currencyMode", "currencies", "displayCurrency", "pricesIncludeVat", "allowGuestCheckout", "allowGiftPurchase", "testMode", "onlyAcceptCredits",
            "creditTopUpEnabled", "modules", "pageSize"
        )) assertTrue(settings.containsKey(key), "settings.$key")
        assertEquals(setOf("recentBuyers", "topSupporters", "goal", "saleBadges", "saleCountdown", "stats"), settings.getJsonObject("modules").fieldNames())
        assertEquals("TRY", settings.getString("currency"))
        assertEquals("₺", settings.getString("currencySymbol"))
        assertEquals("SINGLE", settings.getString("currencyMode"))
        assertEquals(listOf("TRY"), settings.getJsonArray("currencies").list)
        assertEquals(24, settings.getInteger("pageSize"))
    }

    @Test
    fun `the category tree keeps its keys, adds tiered and counts the visible products`(): Unit = runBlocking {
        val root = fx.category("Ranks")
        val child = fx.category("Vip", parentId = root.id)
        fx.product(slug = "in-child", categoryId = child.id)
        fx.product(slug = "in-root", categoryId = root.id)
        fx.product(slug = "loose")

        val tree = store().getJsonArray("categories")

        assertEquals(1, tree.size())
        val node = tree.getJsonObject(0)
        assertEquals(setOf("id", "name", "description", "icon", "color", "parentId", "position", "imageFileName", "tiered", "productsCount", "children"), node.fieldNames())
        assertEquals(1L, node.getLong("productsCount"))
        assertEquals(1L, node.getJsonArray("children").getJsonObject(0).getLong("productsCount"))
    }

    // ------------------------------------------------------------------------------------------------ paging

    @Test
    fun `paging beyond the last page is PAGE_NOT_FOUND and page one of an empty store is fine`(): Unit = runBlocking {
        val empty = list()
        assertEquals(0, empty.getJsonArray("items").size())
        assertEquals(0L, empty.getJsonObject("page").getLong("totalPages"))
        assertThrows(PageNotFound::class.java) { runBlocking { list(ProductListQuery(page = 2)) } }

        repeat(5) { fx.product(slug = "p$it", name = "P$it") }

        val last = list(ProductListQuery(page = 3, pageSize = 2))
        assertEquals(1, last.getJsonArray("items").size())
        assertEquals(5L, last.getJsonObject("page").getLong("totalItems"))
        assertEquals(3L, last.getJsonObject("page").getLong("totalPages"))
        assertThrows(PageNotFound::class.java) { runBlocking { list(ProductListQuery(page = 4, pageSize = 2)) } }
    }

    @Test
    fun `the store serves the first page of storePageSize cards but counts them all`(): Unit = runBlocking {
        config = MarketConfig(storePageSize = 2)
        repeat(5) { fx.product(slug = "p$it", name = "P$it") }

        val body = store()

        assertEquals(2, body.getJsonArray("items").size())
        assertEquals(5L, body.getJsonObject("page").getLong("totalItems"))
        assertEquals(3L, body.getJsonObject("page").getLong("totalPages"))
    }

    // ------------------------------------------------------------------------------------------------ visibility

    @Test
    fun `an inactive category hides its products, its page and its subtree`(): Unit = runBlocking {
        val parent = fx.category("Parent")
        val child = fx.category("Child", parentId = parent.id)
        fx.product(slug = "in-parent", categoryId = parent.id)
        fx.product(slug = "in-child", categoryId = child.id)
        fx.product(slug = "free")
        assertEquals(listOf("free", "in-child", "in-parent"), names(list().getJsonArray("items")).sorted())

        Fixtures.setColumns(pool, "market_category", parent.id, mapOf("status" to "INACTIVE"))

        assertEquals(listOf("free"), names(list().getJsonArray("items")))
        assertEquals(listOf("free"), names(store().getJsonArray("items")))
        assertEquals(0, store().getJsonArray("categories").size())
        assertThrows(NotFound::class.java) { runBlocking { page("in-parent") } }
        assertThrows(NotFound::class.java) { runBlocking { page("in-child") } }
        assertNotNull(page("free"))
        assertThrows(NotFound::class.java) { runBlocking { list(ProductListQuery(category = parent.id)) } }
    }

    @Test
    fun `an ARCHIVED INACTIVE or soft deleted product is a 404 and not listed`(): Unit = runBlocking {
        fx.product(slug = "live")
        val archived = fx.product(slug = "archived")
        val inactive = fx.product(slug = "inactive")
        val deleted = fx.product(slug = "deleted")
        raw(archived, "status" to "ARCHIVED")
        raw(inactive, "status" to "INACTIVE")
        raw(deleted, "deletedAt" to w.clock.now())

        assertEquals(listOf("live"), names(list().getJsonArray("items")))
        for (slug in listOf("archived", "inactive", "deleted", "nothing-here")) assertThrows(NotFound::class.java, { runBlocking { page(slug) } }, slug)
        assertEquals("live", page("live").getString("slug"))
    }

    @Test
    fun `a temporary product is shown only inside its window`(): Unit = runBlocking {
        val now = w.clock.now()
        val open = fx.product(slug = "open")
        val early = fx.product(slug = "early")
        val late = fx.product(slug = "late")
        raw(open, "durationType" to "TEMPORARY", "durationStart" to now - 1000, "durationExpiry" to now + 1000)
        raw(early, "durationType" to "TEMPORARY", "durationStart" to now + 1000)
        raw(late, "durationType" to "TEMPORARY", "durationExpiry" to now - 1000)

        assertEquals(listOf("open"), names(list().getJsonArray("items")))
        assertThrows(NotFound::class.java) { runBlocking { page("early") } }
        assertThrows(NotFound::class.java) { runBlocking { page("late") } }
    }

    // ------------------------------------------------------------------------------------------------ description

    @Test
    fun `the page description is sanitised on read and the card has none`(): Unit = runBlocking {
        val product = fx.product(slug = "x")
        raw(product, "description" to "<p onclick=\"steal()\">hello</p><script>alert(1)</script><a href=\"javascript:alert(2)\">go</a><b>bold</b>")

        val detail = page("x")
        val description = detail.getString("description")

        assertFalse(description.contains("<script", ignoreCase = true), description)
        assertFalse(description.contains("onclick", ignoreCase = true), description)
        assertFalse(description.contains("javascript:", ignoreCase = true), description)
        assertTrue(description.contains("hello") && description.contains("<b>bold</b>"), description)
    }

    // ------------------------------------------------------------------------------------------------ currencies

    @Test
    fun `SINGLE mode ignores a foreign currency`(): Unit = runBlocking {
        rate("USD", "0.025")
        config = MarketConfig(currencyMode = CurrencyMode.SINGLE, additionalCurrencies = listOf("USD"))
        fx.product(slug = "x", price = 10000)

        val card = list(ProductListQuery(currency = "USD")).getJsonArray("items").getJsonObject(0)

        assertEquals("TRY", card.getString("currency"))
        assertEquals(100.0, card.getDouble("price"))
    }

    @Test
    fun `DISPLAY mode converts every figure with the stored rate and charges the base currency`(): Unit = runBlocking {
        rate("USD", "0.025")
        config = MarketConfig(currencyMode = CurrencyMode.DISPLAY, additionalCurrencies = listOf("USD"))
        val product = fx.product(slug = "x", price = 10000, creditPrice = 4000)
        raw(product, "compareAtPrice" to 20000L)

        val base = list().getJsonArray("items").getJsonObject(0)
        assertEquals("TRY", base.getString("currency"))
        assertEquals(100.0, base.getDouble("price"))
        assertEquals(200.0, base.getDouble("compareAtPrice"))

        val shown = list(ProductListQuery(currency = "USD")).getJsonArray("items").getJsonObject(0)
        assertEquals("USD", shown.getString("currency"))
        assertEquals(2.5, shown.getDouble("price"))
        assertEquals(5.0, shown.getDouble("compareAtPrice"), "the stored was-price is converted like the price")
        assertEquals(40.0, shown.getDouble("creditPrice"), "credit prices are an independent price, never converted")

        val settings = store("USD").getJsonObject("settings")
        assertEquals("USD", settings.getString("displayCurrency"))
        assertEquals("TRY", settings.getString("currency"))
        assertEquals(listOf("TRY", "USD"), settings.getJsonArray("currencies").list)
        assertEquals("$", settings.getJsonObject("currencySymbols").getString("USD"))

        assertEquals(2.5, page("x", "USD").getDouble("price"))
    }

    @Test
    fun `a currency without a stored rate is not offered and falls back to the base currency`(): Unit = runBlocking {
        config = MarketConfig(currencyMode = CurrencyMode.DISPLAY, additionalCurrencies = listOf("USD"))
        fx.product(slug = "x", price = 10000)

        val card = list(ProductListQuery(currency = "USD")).getJsonArray("items").getJsonObject(0)

        assertEquals("TRY", card.getString("currency"))
        assertEquals(100.0, card.getDouble("price"))
        assertNull(store("USD").getJsonObject("settings").getValue("displayCurrency"))
        assertEquals(listOf("TRY"), store().getJsonObject("settings").getJsonArray("currencies").list)
    }

    @Test
    fun `MULTI mode uses an explicit price and converts the rest with CONVERT`(): Unit = runBlocking {
        rate("USD", "0.025")
        config = MarketConfig(currencyMode = CurrencyMode.MULTI, additionalCurrencies = listOf("USD"), multiCurrencyFallback = MultiCurrencyFallback.CONVERT)
        val explicit = fx.product(slug = "explicit", price = 10000)
        fx.product(slug = "converted", price = 10000)
        w.prices.upsert(MarketProductPrice(productId = explicit.id, variantId = 0, currency = "USD", price = 300), pool)

        val cards = list(ProductListQuery(currency = "USD", sort = ProductSort.PRICE_DESC)).getJsonArray("items")

        assertEquals(listOf("explicit", "converted"), names(cards))
        assertEquals(3.0, cards.getJsonObject(0).getDouble("price"))
        assertEquals(2.5, cards.getJsonObject(1).getDouble("price"))
        assertEquals("USD", cards.getJsonObject(1).getString("currency"))
        assertEquals(listOf("TRY", "USD"), store().getJsonObject("settings").getJsonArray("currencies").list)
    }

    @Test
    fun `MULTI mode with HIDE leaves out a product without a price in the currency`(): Unit = runBlocking {
        rate("USD", "0.025")
        config = MarketConfig(currencyMode = CurrencyMode.MULTI, additionalCurrencies = listOf("USD"), multiCurrencyFallback = MultiCurrencyFallback.HIDE)
        val explicit = fx.product(slug = "explicit", price = 10000)
        fx.product(slug = "bare", price = 10000)
        w.prices.upsert(MarketProductPrice(productId = explicit.id, variantId = 0, currency = "USD", price = 300), pool)

        assertEquals(listOf("explicit"), names(list(ProductListQuery(currency = "USD")).getJsonArray("items")))
        assertEquals(listOf("explicit"), names(store("USD").getJsonArray("items")))
        assertEquals(3.0, page("explicit", "USD").getDouble("price"))
        assertThrows(NotFound::class.java) { runBlocking { page("bare", "USD") } }
        // in the base currency both are listed
        assertEquals(listOf("bare", "explicit"), names(list().getJsonArray("items")).sorted())
    }

    // ------------------------------------------------------------------------------------------------ sale

    @Test
    fun `an automatic discount with a badge puts the list price in compareAtPrice and a sale block on the card`(): Unit = runBlocking {
        fx.product(slug = "x", price = 10000)
        val d = fx.discount(value = 2000)
        Fixtures.setColumns(pool, "market_discount", d.id, mapOf("expiryDate" to w.clock.now() + 3_600_000))

        val card = list().getJsonArray("items").getJsonObject(0)

        assertEquals(80.0, card.getDouble("price"))
        assertEquals(100.0, card.getDouble("compareAtPrice"))
        val sale = card.getJsonObject("sale")
        assertEquals(20.0, sale.getDouble("percent"))
        assertNull(sale.getValue("amountOff"))
        assertEquals(w.clock.now() + 3_600_000, sale.getLong("endsAt"))
    }

    @Test
    fun `a fixed discount reports amountOff and one without showBadge is priced but not announced`(): Unit = runBlocking {
        fx.product(slug = "x", price = 10000)
        val d = fx.discount(value = 1500, unit = DiscountUnit.FIXED)

        val fixed = list().getJsonArray("items").getJsonObject(0)
        assertEquals(85.0, fixed.getDouble("price"))
        assertEquals(15.0, fixed.getJsonObject("sale").getDouble("amountOff"))
        assertNull(fixed.getJsonObject("sale").getValue("percent"))

        Fixtures.setColumns(pool, "market_discount", d.id, mapOf("showBadge" to false))
        val quiet = list().getJsonArray("items").getJsonObject(0)
        assertEquals(85.0, quiet.getDouble("price"), "the sale still applies")
        assertNull(quiet.getValue("sale"), "no badge without showBadge")
    }

    @Test
    fun `an expired deleted inactive or cart dependent discount is not advertised`(): Unit = runBlocking {
        fx.product(slug = "x", price = 10000)
        val expired = fx.discount(value = 5000)
        val deleted = fx.discount(value = 5000)
        val inactive = fx.discount(value = 5000)
        val cart = fx.discount(value = 5000)
        Fixtures.setColumns(pool, "market_discount", expired.id, mapOf("expiryDate" to w.clock.now() - 1))
        Fixtures.setColumns(pool, "market_discount", deleted.id, mapOf("deletedAt" to w.clock.now()))
        Fixtures.setColumns(pool, "market_discount", inactive.id, mapOf("status" to "INACTIVE"))
        Fixtures.setColumns(pool, "market_discount", cart.id, mapOf("minPaymentAmount" to 50000))

        val card = list().getJsonArray("items").getJsonObject(0)

        assertEquals(100.0, card.getDouble("price"))
        assertNull(card.getValue("compareAtPrice"))
        assertNull(card.getValue("sale"))
    }

    // ------------------------------------------------------------------------------------------------ stock, variants, options

    @Test
    fun `stock shows only at ten or below and zero is out of stock`(): Unit = runBlocking {
        fx.product(slug = "few", name = "a", stock = 5)
        fx.product(slug = "many", name = "b", stock = 50)
        fx.product(slug = "none", name = "c", stock = 0)
        fx.product(slug = "unlimited", name = "d", stock = null)

        val cards = list(ProductListQuery(sort = ProductSort.NEWEST)).getJsonArray("items").map { it as JsonObject }.associateBy { it.getString("slug") }

        assertEquals(5, cards.getValue("few").getInteger("stock"))
        assertTrue(cards.getValue("few").getBoolean("inStock"))
        assertNull(cards.getValue("many").getValue("stock"))
        assertTrue(cards.getValue("many").getBoolean("inStock"))
        assertFalse(cards.getValue("none").getBoolean("inStock"))
        assertTrue(cards.getValue("unlimited").getBoolean("inStock"))

        val purchasable = page("none").getJsonObject("purchasable")
        assertFalse(purchasable.getBoolean("ok"))
        assertEquals("OUT_OF_STOCK", purchasable.getString("reason"))
        assertTrue(page("few").getJsonObject("purchasable").getBoolean("ok"))
    }

    @Test
    fun `variants give priceFrom a lowest price and per variant details`(): Unit = runBlocking {
        val product = fx.product(slug = "x", price = 10000)
        fx.variant(product, name = "Small", price = 5000, stock = 3, position = 0, optionValues = "{\"size\":\"s\"}")
        fx.variant(product, name = "Large", price = 9000, stock = 0, position = 1, optionValues = "{\"size\":\"l\"}")
        raw(product, "variantOptions" to "[{\"key\":\"size\",\"label\":\"Size\",\"values\":[{\"key\":\"s\",\"label\":\"S\"},{\"key\":\"l\",\"label\":\"L\"}]}]")

        val card = list().getJsonArray("items").getJsonObject(0)
        assertEquals(50.0, card.getDouble("price"))
        assertTrue(card.getBoolean("priceFrom"))
        assertTrue(card.getBoolean("hasVariants"))
        assertTrue(card.getBoolean("needsOptions"))
        assertTrue(card.getBoolean("inStock"), "one variant is in stock")
        assertNull(card.getValue("stock"), "a variant product shows stock per variant")

        val detail = page("x")
        val variants = detail.getJsonArray("variants").map { it as JsonObject }
        assertEquals(listOf("Small", "Large"), variants.map { it.getString("name") })
        assertEquals(listOf(50.0, 90.0), variants.map { it.getDouble("price") })
        assertEquals(listOf(true, false), variants.map { it.getBoolean("inStock") })
        assertEquals("s", variants[0].getJsonObject("optionValues").getString("size"))
        assertEquals("size", detail.getJsonArray("variantOptions").getJsonObject(0).getString("key"))
    }

    @Test
    fun `a product whose variants are all gone is not listed`(): Unit = runBlocking {
        val product = fx.product(slug = "x")
        raw(product, "hasVariants" to true)
        fx.product(slug = "y")

        assertEquals(listOf("y"), names(list().getJsonArray("items")))
        assertThrows(NotFound::class.java) { runBlocking { page("x") } }
    }

    @Test
    fun `needsOptions for a required field a subscription and more than one server`(): Unit = runBlocking {
        val plain = fx.product(slug = "plain")
        val withField = fx.product(slug = "field")
        val subscription = fx.product(slug = "sub")
        val twoServers = fx.product(slug = "servers")
        val oneServer = fx.product(slug = "one")
        fx.field(withField, key = "nick", required = true)
        fx.field(plain, key = "note", required = false)
        raw(subscription, "billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1)
        raw(twoServers, "serverChoices" to "[1,2]")
        raw(oneServer, "serverChoices" to "[1]")

        val cards = list().getJsonArray("items").map { it as JsonObject }.associateBy { it.getString("slug") }

        assertFalse(cards.getValue("plain").getBoolean("needsOptions"))
        assertTrue(cards.getValue("field").getBoolean("needsOptions"))
        assertTrue(cards.getValue("sub").getBoolean("needsOptions"))
        assertTrue(cards.getValue("servers").getBoolean("needsOptions"))
        assertFalse(cards.getValue("one").getBoolean("needsOptions"))
        assertEquals(mapOf("unit" to "MONTH", "count" to 1), cards.getValue("sub").getJsonObject("period").map)
        assertNull(cards.getValue("plain").getValue("period"))
    }

    // ------------------------------------------------------------------------------------------------ filters and sorting

    @Test
    fun `filters search category featured kind and sorts`(): Unit = runBlocking {
        config = MarketConfig(creditTopUpEnabled = true) // a credit pack is listed only while the top-up is on (07 section 8)
        val cat = fx.category("Cat")
        val sub = fx.category("Sub", parentId = cat.id)
        val a = fx.product(slug = "alpha", name = "Alpha sword", price = 3000, categoryId = cat.id)
        val b = fx.product(slug = "beta", name = "Beta shield", price = 1000, categoryId = sub.id)
        val c = fx.product(slug = "gamma", name = "Gamma", price = 2000)
        raw(a, "featured" to true, "priority" to 5, "soldCount" to 1)
        raw(b, "priority" to 9, "shortDescription" to "A mighty SWORD too", "soldCount" to 7)
        raw(c, "kind" to "CREDIT_PACK", "creditAmount" to 1000L, "soldCount" to 3)

        fun slugs(q: ProductListQuery) = runBlocking { names(list(q).getJsonArray("items")) }

        assertEquals(listOf("beta", "alpha", "gamma"), slugs(ProductListQuery()), "priority desc, then name")
        assertEquals(listOf("beta", "gamma", "alpha"), slugs(ProductListQuery(sort = ProductSort.PRICE_ASC)))
        assertEquals(listOf("alpha", "gamma", "beta"), slugs(ProductListQuery(sort = ProductSort.PRICE_DESC)))
        assertEquals(listOf("beta", "gamma", "alpha"), slugs(ProductListQuery(sort = ProductSort.BESTSELLING)))
        assertEquals(listOf("gamma", "beta", "alpha"), slugs(ProductListQuery(sort = ProductSort.NEWEST)))
        assertEquals(listOf("beta", "alpha"), slugs(ProductListQuery(category = cat.id)), "a category includes its subcategories")
        assertEquals(listOf("beta"), slugs(ProductListQuery(category = sub.id)))
        assertEquals(listOf("alpha"), slugs(ProductListQuery(featured = true)))
        assertEquals(listOf("beta", "gamma"), slugs(ProductListQuery(featured = false)))
        assertEquals(listOf("gamma"), slugs(ProductListQuery(kind = ProductKind.CREDIT_PACK)))
        assertEquals(listOf("beta", "alpha"), slugs(ProductListQuery(search = "sword")), "name or short description, any case")
        assertEquals(emptyList<String>(), slugs(ProductListQuery(search = "zzz")))
        assertThrows(NotFound::class.java) { runBlocking { list(ProductListQuery(category = 9999)) } }
    }

    @Test
    fun `store serves featured bestsellers and the cards of the comparisons`(): Unit = runBlocking {
        val a = fx.product(slug = "a", name = "A")
        val b = fx.product(slug = "b", name = "B")
        val hidden = fx.product(slug = "hidden", name = "H")
        raw(a, "featured" to true)
        raw(hidden, "status" to "INACTIVE")
        w.comparisons.add(MarketComparison(name = "Ranks", productIds = "[${a.id},null,${hidden.id},${b.id}]", features = "[]", cellValues = "{}"), pool)

        val body = store()

        assertEquals(listOf("a"), names(body.getJsonArray("featured")))
        assertEquals(listOf("a", "b"), names(body.getJsonArray("comparisonProducts")), "only visible products, once each")
        assertEquals(1, body.getJsonArray("comparisons").size())
        assertEquals("[${a.id},null,${hidden.id},${b.id}]", body.getJsonArray("comparisons").getJsonObject(0).getJsonArray("productIds").encode())
        assertEquals(0, body.getJsonArray("bestsellers").size())

        config = MarketConfig(showFeaturedProducts = false, showComparisons = false, showBestsellers = false)
        val off = store()
        assertEquals(0, off.getJsonArray("featured").size())
        assertEquals(0, off.getJsonArray("comparisons").size())
        assertEquals(0, off.getJsonArray("comparisonProducts").size())
    }

    // ------------------------------------------------------------------------------------------------ viewer

    @Test
    fun `owned is false or true for a logged in caller and null for a guest`(): Unit = runBlocking {
        val user = fx.user()
        val bought = fx.product(slug = "bought")
        fx.product(slug = "other")
        entitlement(user.id, bought)

        val cards = list(viewer = StoreViewer(user.id)).getJsonArray("items").map { it as JsonObject }.associateBy { it.getString("slug") }

        assertEquals(true, cards.getValue("bought").getBoolean("owned"))
        assertEquals(false, cards.getValue("other").getBoolean("owned"))
        assertNull(list().getJsonArray("items").getJsonObject(0).getValue("owned"))
    }

    @Test
    fun `a revoked or expired entitlement does not count as owned`(): Unit = runBlocking {
        val user = fx.user()
        val product = fx.product(slug = "x")
        val ended = entitlement(user.id, product)
        w.entitlements.end(ended, com.panomc.plugins.market.db.model.EntitlementStatus.REVOKED, "ADMIN", w.clock.now(), pool)
        val expired = entitlement(user.id, product)
        Fixtures.setColumns(pool, "market_entitlement", expired, mapOf("expiresAt" to w.clock.now() - 1))

        assertEquals(false, list(viewer = StoreViewer(user.id)).getJsonArray("items").getJsonObject(0).getBoolean("owned"))
    }

    @Test
    fun `the owner of a lower tier sees the upgrade price and the upgrade block`(): Unit = runBlocking {
        val user = fx.user()
        val ladder = fx.category("Ranks", tiered = true)
        val low = fx.product(slug = "low", price = 1000, categoryId = ladder.id)
        val high = fx.product(slug = "high", price = 3000, categoryId = ladder.id)
        raw(low, "tierRank" to 1)
        raw(high, "tierRank" to 2)
        entitlement(user.id, low, categoryId = ladder.id, rank = 1, pricePaid = 1000)

        val guest = page("high")
        assertEquals(30.0, guest.getDouble("price"))
        assertNull(guest.getValue("upgrade"))

        val owner = page("high", viewer = StoreViewer(user.id))
        val upgrade = owner.getJsonObject("upgrade")
        assertEquals(30.0, owner.getDouble("price"), "price is the unit price after the automatic discount; the upgrade deduction is its own figure")
        assertEquals(low.id, upgrade.getLong("fromProductId"))
        assertEquals("low", upgrade.getString("fromName"))
        assertEquals("DIFFERENCE", upgrade.getString("mode"))
        assertEquals(10.0, upgrade.getDouble("deduction"))
        assertEquals(2, owner.getInteger("tierRank"))
    }

    @Test
    fun `required products need to be owned and a guest sees the login hint where an account is needed`(): Unit = runBlocking {
        config = MarketConfig(creditTopUpEnabled = true) // a credit pack is purchasable only while the top-up is on (07 section 8)
        val user = fx.user()
        val base = fx.product(slug = "base", name = "Base")
        val addon = fx.product(slug = "addon", name = "Addon")
        raw(addon, "requiredProducts" to "[${base.id}]")
        val credits = fx.product(slug = "credits", name = "Credits")
        raw(credits, "kind" to "CREDIT_PACK", "creditAmount" to 500L)

        val without = page("addon", viewer = StoreViewer(user.id))
        assertEquals("REQUIREMENT_NOT_MET", without.getJsonObject("purchasable").getString("reason"))
        assertEquals(base.id, without.getJsonArray("requiredProducts").getJsonObject(0).getLong("id"))
        assertEquals(false, without.getJsonArray("requiredProducts").getJsonObject(0).getBoolean("owned"))

        entitlement(user.id, base)
        val with = page("addon", viewer = StoreViewer(user.id))
        assertTrue(with.getJsonObject("purchasable").getBoolean("ok"))
        assertEquals(true, with.getJsonArray("requiredProducts").getJsonObject(0).getBoolean("owned"))

        assertEquals("LOGIN_REQUIRED", page("credits").getJsonObject("purchasable").getString("reason"))
        config = MarketConfig(allowGuestCheckout = false)
        assertEquals("LOGIN_REQUIRED", page("base").getJsonObject("purchasable").getString("reason"))
        assertTrue(page("base", viewer = StoreViewer(user.id)).getJsonObject("purchasable").getBoolean("ok"))
    }

    // ------------------------------------------------------------------------------------------------ product page

    @Test
    fun `the product page carries ProductDetail fields, fields, bundle items and server choices`(): Unit = runBlocking {
        val cat = fx.category("Kits")
        val sword = fx.product(slug = "sword", name = "Sword", price = 1000)
        val apple = fx.product(slug = "apple", name = "Apple", price = 500)
        val bundle = fx.bundle(sword to 1, apple to 3, slug = "kit", price = 1200)
        raw(
            bundle, "categoryId" to cat.id, "metaTitle" to "Kit!", "metaDescription" to "A kit", "limitPerPlayer" to 2, "maxQuantityPerOrder" to 3,
            "cooldownSeconds" to 60L, "allowGift" to false, "serverChoices" to "[7,8]", "vatPercent" to 800L, "weightGrams" to 250, "description" to "<p>d</p>"
        )
        fx.field(bundle, key = "nick", required = true)
        servers = listOf(ServerChoice(7, "Survival", "SPIGOT"), ServerChoice(99, "Elsewhere", "SPIGOT"))

        val detail = page("kit")

        assertEquals("Kits", detail.getString("categoryName"))
        assertEquals("BUNDLE", detail.getString("kind"))
        assertEquals("Kit!", detail.getString("metaTitle"))
        assertEquals("<p>d</p>", detail.getString("description"))
        assertEquals(2, detail.getInteger("limitPerPlayer"))
        assertEquals(3, detail.getInteger("maxQuantityPerOrder"))
        assertEquals(60, detail.getLong("cooldownSeconds"))
        assertFalse(detail.getBoolean("allowGift"))
        assertEquals(8.0, detail.getDouble("vatPercent"))
        assertEquals(250, detail.getInteger("weightGrams"))
        assertTrue(detail.getBoolean("pricesIncludeVat"))
        assertEquals(listOf(7L), detail.getJsonArray("serverChoices").map { (it as JsonObject).getLong("id") })
        assertEquals("Survival", detail.getJsonArray("serverChoices").getJsonObject(0).getString("name"))
        assertEquals(listOf("Sword", "Apple"), detail.getJsonArray("bundleItems").map { (it as JsonObject).getString("name") })
        assertEquals(listOf(1, 3), detail.getJsonArray("bundleItems").map { (it as JsonObject).getInteger("quantity") })
        val field = detail.getJsonArray("fields").getJsonObject(0)
        assertEquals("nick", field.getString("fieldKey"))
        assertTrue(field.getBoolean("required"))
        assertFalse(detail.getBoolean("hasVariants"))
        // the pre-v2 keys survive
        for (key in listOf("id", "slug", "name", "description", "categoryId", "categoryName", "price", "creditPrice", "stock", "requiredProducts", "requireOnlyOne",
            "featured", "durationType", "durationStart", "durationExpiry", "priority", "icon", "imageFileName", "createdAt", "updatedAt")) {
            assertTrue(detail.containsKey(key), "product.$key")
        }
        assertFalse(detail.containsKey("actions"))
        assertFalse(detail.containsKey("requiredPermission"))
    }

    @Test
    fun `a bundle is out of stock when a child is`(): Unit = runBlocking {
        val sword = fx.product(slug = "sword", stock = 1)
        val apple = fx.product(slug = "apple", stock = 10)
        fx.bundle(sword to 2, apple to 1, slug = "kit")
        fx.bundle(apple to 1, slug = "snack")

        val cards = list().getJsonArray("items").map { it as JsonObject }.associateBy { it.getString("slug") }

        assertFalse(cards.getValue("kit").getBoolean("inStock"), "the sword has 1 unit, the bundle needs 2")
        assertTrue(cards.getValue("snack").getBoolean("inStock"))
    }

    @Test
    fun `a product the pricing code refuses is left out and does not break the store`(): Unit = runBlocking {
        fx.product(slug = "fine", price = 1000)
        fx.product(slug = "absurd", price = 2_000_000_000_000L)

        assertEquals(listOf("fine"), names(list().getJsonArray("items")))
        assertEquals(1L, store().getJsonObject("page").getLong("totalItems"))
        assertThrows(NotFound::class.java) { runBlocking { page("absurd") } }
    }

    @Test
    fun `the settings block follows the configuration`(): Unit = runBlocking {
        config = MarketConfig(
            storeName = "Shop", currency = "EUR", showVatInPrice = false, allowGuestCheckout = false, allowGiftPurchase = false, testMode = true,
            creditsEnabled = true, onlyAcceptCredits = true, creditTopUpEnabled = true, moduleStats = true, moduleGoal = false, storePageSize = 12
        )
        val settings = store().getJsonObject("settings")

        assertEquals("Shop", settings.getString("storeName"))
        assertEquals("EUR", settings.getString("currency"))
        assertEquals("€", settings.getString("currencySymbol"))
        assertFalse(settings.getBoolean("pricesIncludeVat"))
        assertFalse(settings.getBoolean("allowGuestCheckout"))
        assertFalse(settings.getBoolean("allowGiftPurchase"))
        assertTrue(settings.getBoolean("testMode"))
        assertTrue(settings.getBoolean("onlyAcceptCredits"))
        assertTrue(settings.getBoolean("creditTopUpEnabled"))
        assertTrue(settings.getJsonObject("modules").getBoolean("stats"))
        assertFalse(settings.getJsonObject("modules").getBoolean("goal"))
        assertEquals(12, settings.getInteger("pageSize"))
    }

    @Test
    fun `marketPricingConfig maps the settings to x100 amounts and basis points`() {
        val c = MarketConfig(
            currency = "USD", vatPercent = 18.5, minimumOrderAmount = 12.34, creditValue = 0.25, cashbackPercent = 2.5,
            additionalCurrencies = listOf(" try ", "EUR", "eur"), removeCents = true
        )
        val p = marketPricingConfig(c, mapOf("EUR" to BigDecimal("0.9")))

        assertEquals("USD", p.baseCurrency)
        assertEquals(1850L, p.vatBp)
        assertEquals(1234L, p.minimumOrderAmount)
        assertEquals(25L, p.creditValue)
        assertEquals(250L, p.cashbackBp)
        assertEquals(listOf("TRY", "EUR"), p.additionalCurrencies)
        assertTrue(p.removeCents)
        assertEquals(BigDecimal("0.9"), p.rates["EUR"])
    }
}
