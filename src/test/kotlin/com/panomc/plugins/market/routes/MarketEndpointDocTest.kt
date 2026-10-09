package com.panomc.plugins.market.routes

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Api
import com.panomc.platform.model.Route
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.route.RouteEntry
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.OpenApiGenerator
import com.panomc.platform.schema.Stability
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.plugins.market.service.Quote
import com.panomc.plugins.market.service.QuoteLine
import com.panomc.plugins.market.service.QuoteMessage
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.cglib.proxy.Enhancer
import org.springframework.cglib.proxy.MethodInterceptor
import org.springframework.objenesis.ObjenesisStd
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.math.BigDecimal

/**
 * 04 section 5 for the market (slice E2): every public endpoint declares a `doc`, the panel stays undocumented, and the
 * documented shapes match what the market's own code writes.
 *
 * No test can start the router, so the market's route list is loaded by hand, the way the core's coverage test does it: every
 * `@Endpoint` class is built through its widest constructor with stand-in arguments (the constructors only store them).
 */
class MarketEndpointDocTest {
    private val pluginId = "pano-plugin-market"

    @Test
    fun `every market endpoint class can be loaded by hand`() {
        assertEquals(listOf<String>(), loaded.failures)
        assertTrue(loaded.entries.size > 100, "expected the whole market route list, got ${loaded.entries.size}")
    }

    @Test
    fun `every public market entry has a doc`() {
        val missing = loaded.entries
            .filter { it.stability == Stability.PUBLIC && it.doc == null }
            .map { "${it.method} ${it.path} (${it.routeClass.simpleName})" }
            .sorted()

        assertEquals(listOf<String>(), missing)
    }

    @Test
    fun `panel entries stay undocumented and internal`() {
        val panel = loaded.entries.filter { it.path.contains("/panel/") }

        assertTrue(panel.size > 50, "expected the panel routes, got ${panel.size}")
        assertEquals(listOf<String>(), panel.filter { it.doc != null }.map { it.routeClass.simpleName })
        assertEquals(listOf<String>(), panel.filter { it.stability != Stability.INTERNAL }.map { it.routeClass.simpleName })
    }

    @Test
    fun `every doc is usable and its errors resolve to a declared code`() {
        val problems = mutableListOf<String>()

        loaded.entries.forEach { entry ->
            val doc = entry.doc ?: return@forEach
            val name = entry.routeClass.simpleName

            doc.problem()?.let { problems.add("$name: $it") }

            if (doc.summary.isBlank()) problems.add("$name: empty summary")

            doc.errors.forEach { type ->
                val error = runCatching { type.java.getDeclaredConstructor().newInstance() }.getOrNull()

                if (error == null) {
                    problems.add("$name: ${type.simpleName} has no all-default constructor, so the document would drop it")
                } else if (error.getStatusCode() !in 400..599) {
                    problems.add("$name: ${type.simpleName} is not an error status")
                }
            }
        }

        assertEquals(listOf<String>(), problems)
    }

    @Test
    fun `the plugin document lists no operation as undocumented and names the shared shapes once`() {
        val document = JsonObject(OpenApiGenerator.generate(OpenApiGenerator.Scope.Plugin(pluginId), loaded.entries).encode())
        val operations = document.getJsonObject("paths").map.values
            .flatMap { (it as Map<*, *>).values }
            .map { JsonObject(it as Map<String, Any?>) }

        assertTrue(operations.size > 25, "expected the public operations, got ${operations.size}")
        assertEquals(0, operations.count { it.getBoolean("x-pano-undocumented", false) }, "operations without a doc")

        val schemas = document.getJsonObject("components").getJsonObject("schemas")

        listOf("MarketProductCard", "MarketProductDetail", "MarketQuote", "MarketOrder").forEach {
            assertTrue(schemas.containsKey(it), "components.schemas.$it")
        }

        assertEquals("/api/plugins/$pluginId", document.getJsonArray("servers").getJsonObject(0).getString("url"))
    }

    @Test
    fun `the documented operations carry the paths of the market`() {
        val paths = loaded.entries.filter { it.doc != null }.map { it.path }

        listOf(
            "/store", "/store/products", "/products/:slug", "/widgets", "/checkout/config", "/checkout/quote", "/checkout",
            "/orders/:publicId", "/orders/:publicId/status", "/me/cart", "/me/orders"
        ).forEach { declared ->
            assertTrue(paths.contains(ApiPaths.plugin(pluginId, declared)), "$declared is documented")
        }
    }

    // the shapes against what the market's own code writes

    @Test
    fun `a quote written by the real model matches its doc`() {
        val line = QuoteLine(
            lineKey = "p1", productId = 1, variantId = 0, name = "Rank", variantName = null, slug = "rank", imageFileName = null,
            quantity = 1, maxQuantity = 10, listUnitPrice = 1000, unitPrice = 1000, discountAmount = 0, upgradeAmount = 0,
            couponAmount = 0, vatPercent = 0, vatAmount = 0, lineTotal = 1000, creditUnitPrice = 0, fieldValues = emptyMap(),
            targetServerId = null, physical = false, kind = "PRODUCT", parentLineKey = null, billingMode = "ONE_TIME", errors = emptyList()
        )
        val quote = Quote(
            currency = "USD", baseCurrency = "USD", displayCurrency = null, lines = listOf(line), pricingMode = "TAX_INCLUSIVE",
            pricesIncludeVat = true, fxRate = BigDecimal.ONE, display = null, minimumOrderAmount = 0, subtotal = 1000, discountTotal = 0,
            couponDiscount = 0, creatorDiscount = 0, upgradeDiscount = 0, shippingTotal = 0, paymentFee = 0, vatTotal = 0, total = 1000,
            credits = null, gatewayAmount = 1000, coupon = null, creatorCode = null, requiresShipping = false, shippingOptions = emptyList(),
            shippingMethodId = null, paymentMethods = emptyList(), requiredBuyerFields = emptyList(),
            legal = null, messages = listOf(QuoteMessage("LOGIN_REQUIRED", "WARNING")), canCheckout = true
        )
        val body = wire(mapOf("quote" to quote.toJson()))

        assertEquals(listOf<String>(), docOf("QuoteAPI").check(body))
        assertEquals(listOf<String>(), docOf("CheckoutAPI").check(wire(mapOf("order" to orderJson(), "orderToken" to "t", "payment" to null))))
    }

    @Test
    fun `a quote with a wrong total type is rejected`() {
        val body = wire(mapOf("quote" to JsonObject().put("currency", "USD")))

        assertTrue(docOf("QuoteAPI").check(body).isNotEmpty())
    }

    @Test
    fun `the small bodies match their docs`() {
        assertEquals(
            listOf<String>(),
            docOf("GetOrderStatusAPI").check(
                wire(mapOf("status" to "PAID", "paymentStatus" to null, "fulfillmentStatus" to "PENDING", "shippingStatus" to "NONE", "updatedAt" to 1L))
            )
        )
        assertEquals(listOf<String>(), docOf("GetOrderAPI").check(wire(mapOf("order" to orderJson()))))
        assertEquals(listOf<String>(), docOf("CancelOrderAPI").check(JsonObject()))
        assertEquals(listOf<String>(), docOf("PayOrderAPI").check(wire(mapOf("payment" to JsonObject().put("kind", "REDIRECT")))))
        assertEquals(listOf<String>(), docOf("PayOrderAPI").check(wire(mapOf("payment" to null))))
        assertEquals(
            listOf<String>(),
            docOf("GetMySummaryAPI").check(
                wire(
                    mapOf(
                        "creditsEnabled" to true, "creditBalance" to 2.5, "creditName" to "Coins", "cartItemCount" to 3,
                        "activeSubscriptionCount" to 1, "subscriptionCount" to 2, "isCreator" to false
                    )
                )
            )
        )
    }

    @Test
    fun `a store page and a product page match their docs`() {
        val card = card()
        val settings = JsonObject()
            .put("storeName", "Shop").put("currency", "USD").put("currencySymbol", "$").put("currencies", JsonArray().add("USD"))
            .put("creditsEnabled", false).put("allowGuestCheckout", true).put("allowGiftPurchase", true).put("modules", JsonObject()).put("pageSize", 12)
        val page = JsonObject().put("number", 1).put("size", 12).put("totalItems", 1).put("totalPages", 1)
        val store = JsonObject()
            .put("items", JsonArray().add(card)).put("page", page).put("settings", settings).put("categories", JsonArray())
            .put("featured", JsonArray().add(card)).put("bestsellers", JsonArray()).put("comparisons", JsonArray()).put("comparisonProducts", JsonArray())

        assertEquals(listOf<String>(), docOf("GetStoreAPI").check(wire(store.map)))
        assertEquals(listOf<String>(), docOf("GetStoreProductsAPI").check(wire(mapOf("items" to JsonArray().add(card), "page" to page))))

        val detail = card().copy()
            .put("description", null as String?).put("categoryName", null as String?).put("variants", JsonArray()).put("fields", JsonArray())
            .put("bundleItems", JsonArray()).put("requiredProducts", JsonArray()).put("serverChoices", JsonArray())
            .put("purchasable", JsonObject().put("ok", true).put("reason", null as String?))

        assertEquals(listOf<String>(), docOf("GetStoreProductAPI").check(wire(mapOf("product" to detail))))
        assertTrue(docOf("GetStoreProductAPI").check(wire(mapOf("product" to card()))).isNotEmpty(), "a card is not a product page")
    }

    @Test
    fun `the shared shapes are named and the market keeps its own names`() {
        val named = listOf(MarketSchemas.productCard, MarketSchemas.productDetail, MarketSchemas.quote, MarketSchemas.order)
            .map { com.panomc.platform.schema.SchemaJson.of(it).getString("\$id").removeSuffix("#") }

        assertEquals(
            listOf("pano:MarketProductCard", "pano:MarketProductDetail", "pano:MarketQuote", "pano:MarketOrder"),
            named
        )
        assertNull(com.panomc.platform.schema.SchemaJson.of(MarketSchemas.page()).getString("\$id"))
    }

    // helpers

    private fun card(): JsonObject = JsonObject()
        .put("id", 1).put("slug", "rank").put("name", "Rank").put("shortDescription", null as String?).put("categoryId", null as Long?)
        .put("kind", "STANDARD").put("price", 10.0).put("compareAtPrice", null as Double?).put("creditPrice", 0.0).put("currency", "USD")
        .put("priceFrom", false).put("inStock", true).put("stock", null as Int?).put("featured", false).put("priority", 0)
        .put("icon", null as String?).put("imageFileName", null as String?).put("physical", false).put("billingMode", "ONE_TIME")
        .put("period", null as JsonObject?).put("hasVariants", false).put("tierRank", null as Int?).put("sale", null as JsonObject?)
        .put("needsOptions", false).put("owned", null as Boolean?)

    /** The owner view of an order that was just created, as `OrderService.ownerView` writes the keys that matter. */
    private fun orderJson(): JsonObject = JsonObject()
        .put("publicId", "abc").put("number", 7L).put("status", "PENDING").put("fulfillmentStatus", "PENDING").put("shippingStatus", "NONE")
        .put("limited", false).put("createdAt", 1L).put("paidAt", null as Long?).put("expiresAt", 2L).put("currency", "USD").put("testMode", false)
        .put("totals", JsonObject().put("total", 10.0)).put("items", JsonArray().add(JsonObject().put("name", "Rank").put("quantity", 1)))
        .put("recipientUsername", null as String?).put("isGift", false).put("payment", null as JsonObject?)

    private fun docOf(simpleName: String): EndpointDoc =
        loaded.entries.first { it.routeClass.simpleName == simpleName }.doc ?: error("$simpleName has no doc")

    /** A body as it leaves the server: encoded and parsed again, so an enum is its name and a long is a number. */
    private fun wire(body: Map<String, Any?>) = JsonObject(JsonObject(body).encode())

    class Loaded(val entries: List<RouteEntry>, val failures: List<String>)

    companion object {
        private const val PLUGIN_ID = "pano-plugin-market"

        private val objenesis = ObjenesisStd()

        /** The market route list, loaded once for the whole class. */
        val loaded: Loaded by lazy { load() }

        private fun load(): Loaded {
            val scanner = ClassPathScanningCandidateComponentProvider(false)

            scanner.addIncludeFilter(AnnotationTypeFilter(Endpoint::class.java))

            val classes = scanner.findCandidateComponents("com.panomc.plugins.market")
                .mapNotNull(BeanDefinition::getBeanClassName)
                .map { Class.forName(it) }
                .filter { Route::class.java.isAssignableFrom(it) }
                .sortedBy { it.name }

            val entries = mutableListOf<RouteEntry>()
            val failures = mutableListOf<String>()

            classes.forEach { type ->
                try {
                    val route = build(type) as Route
                    val api = route as? Api

                    route.paths.forEach { path ->
                        entries.add(
                            RouteEntry(
                                method = path.routeType.vertxHttpMethod?.name() ?: "ALL",
                                path = ApiPaths.resolve(path.url, route.mount, route.namespace, PLUGIN_ID),
                                declared = path.url,
                                pluginId = PLUGIN_ID,
                                routeClass = type,
                                mount = route.mount,
                                namespace = route.namespace,
                                doc = api?.doc,
                                declaredStability = api?.stability,
                                deprecation = api?.deprecation,
                                validation = null
                            )
                        )
                    }
                } catch (e: Throwable) {
                    failures.add("${type.name}: ${e.javaClass.simpleName} ${e.message}")
                }
            }

            return Loaded(entries, failures)
        }

        /** The class built through its widest constructor with stand-ins, which the constructors only store. */
        private fun build(type: Class<*>): Any {
            val constructor: Constructor<*> = type.declaredConstructors
                .filter { !it.isSynthetic }
                .maxByOrNull { it.parameterCount }
                ?: error("no constructor")

            constructor.isAccessible = true

            return constructor.newInstance(*constructor.parameterTypes.map { standIn(it) }.toTypedArray())
        }

        private fun standIn(type: Class<*>): Any? = when {
            type == Boolean::class.javaPrimitiveType -> false
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            type == Double::class.javaPrimitiveType -> 0.0
            type == String::class.java -> ""
            type == List::class.java -> emptyList<Any?>()
            type == Set::class.java -> emptySet<Any?>()
            type == Map::class.java -> emptyMap<Any?, Any?>()
            type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }

            Modifier.isAbstract(type.modifiers) -> objenesis.newInstance(subclassOf(type))

            else -> objenesis.newInstance(type)
        }

        /** An abstract class (a DAO) has no instance of its own: a generated subclass stands in; none of its methods is ever called. */
        private fun subclassOf(type: Class<*>): Class<*> = Enhancer().also {
            it.setSuperclass(type)
            it.setCallbackType(MethodInterceptor::class.java)
        }.createClass()
    }
}
