package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.catalog.ImageChange
import com.panomc.plugins.market.core.catalog.ProductInput
import com.panomc.plugins.market.core.catalog.ProductRequestParser
import com.panomc.plugins.market.core.catalog.StockMode
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketShippingRate
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.error.InvalidProduct
import com.panomc.plugins.market.error.ReservedSlug
import com.panomc.plugins.market.error.SlugAlreadyExists
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
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
import com.panomc.plugins.market.support.ErrorBodies

/**
 * `CatalogService` on a real MariaDB (MK-050): product save with its set parts, partial update, stock honoured on
 * create only, soft / hard delete by reference, reserved and duplicate slugs, the atomic stock endpoint (raced), the
 * rule matrix reaching the database layer, and fieldErrors with dotted paths.
 */
class CatalogServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val service by lazy {
        CatalogService(w.db, { w.config }, w.clock, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.providerMeta, w.categories, w.comparisons)
    }
    private var counter = 0

    /** Reference rows are inserted raw (an order item without its order), which the global invariants would flag. */
    override suspend fun assertInvariants() {}

    private fun input(vararg pairs: Pair<String, Any?>): ProductInput = ProductRequestParser.parse(JsonObject(mapOf(*pairs)))

    private fun named(vararg pairs: Pair<String, Any?>): ProductInput {
        val n = ++counter
        return input("name" to "Product $n", "price" to "10", *pairs)
    }

    private suspend fun create(vararg pairs: Pair<String, Any?>): CatalogService.SaveResult = service.create(named(*pairs))

    private suspend fun fieldErrors(block: suspend () -> Unit): Map<String, String> {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue(e is InvalidProduct, "expected INVALID_PRODUCT, got $e")
        val body = JsonObject((e as InvalidProduct).encode(emptyMap()))
        assertEquals("INVALID_PRODUCT", body.getJsonObject("error").getString("code"))
        return ErrorBodies.details(e).getJsonObject("fieldErrors").map.mapValues { it.value as String }
    }

    private suspend fun productCount(): Long = count("market_product")

    // ----- create ---------------------------------------------------------------------------------------------------

    @Test
    fun `create stores every column and get returns the product with its set parts`(): Unit = runBlocking {
        val saved = service.create(
            input(
                "name" to "Diamond Kit", "slug" to "Diamond Kit!", "description" to "<p>Hello</p>", "shortDescription" to "short",
                "price" to "19.99", "creditPrice" to "5", "compareAtPrice" to "29.99", "stock" to 12, "vatPercent" to "8",
                "physical" to true, "sku" to "DK-1", "weightGrams" to 250, "lengthMm" to 100, "widthMm" to 50, "heightMm" to 20,
                "hsCode" to "6109.10", "originCountry" to "tr", "limitPerPlayer" to 3, "maxQuantityPerOrder" to 4, "cooldownSeconds" to 60,
                "allowGift" to false, "metaTitle" to "mt", "metaDescription" to "md", "featured" to true, "priority" to 5, "icon" to "fa-gem",
                "requireOnlyOne" to true, "requiredPermission" to "group.vip", "actions" to """[{"type":"CREDIT","value":2.5}]"""
            )
        )

        assertEquals("diamond-kit", saved.slug)
        assertEquals(emptyList<String>(), saved.warnings.filter { it != "NO_SHIPPING_METHOD" })
        assertEquals(listOf("NO_SHIPPING_METHOD"), saved.warnings)

        val p = service.get(saved.id).product
        assertEquals("Diamond Kit", p.name)
        assertEquals("<p>Hello</p>", p.description)
        assertEquals("short", p.shortDescription)
        assertEquals(1999L, p.price)
        assertEquals(500L, p.creditPrice)
        assertEquals(2999L, p.compareAtPrice)
        assertEquals(12, p.stock)
        assertEquals(800L, p.vatPercent)
        assertTrue(p.physical)
        assertEquals("DK-1", p.sku)
        assertEquals(250, p.weightGrams)
        assertEquals(100, p.lengthMm)
        assertEquals(50, p.widthMm)
        assertEquals(20, p.heightMm)
        assertEquals("610910", p.hsCode)
        assertEquals("TR", p.originCountry)
        assertEquals(3, p.limitPerPlayer)
        assertEquals(4, p.maxQuantityPerOrder)
        assertEquals(60L, p.cooldownSeconds)
        assertFalse(p.allowGift)
        assertEquals("mt", p.metaTitle)
        assertEquals("md", p.metaDescription)
        assertTrue(p.featured)
        assertEquals(5, p.priority)
        assertEquals("fa-gem", p.icon)
        assertTrue(p.requireOnlyOne)
        assertEquals("group.vip", p.requiredPermission)
        assertEquals(ProductKind.STANDARD, p.kind)
        assertEquals(BillingMode.ONE_TIME, p.billingMode)
        assertEquals(0, p.soldCount)
        assertNull(p.deletedAt)
        assertTrue(p.actions!!.contains("\"id\":\"a1\""))
    }

    @Test
    fun `create with every set part writes them and get reads them back`(): Unit = runBlocking {
        val child = w.fixtures.product()
        val axes = """[{"key":"size","label":"Size","values":[{"key":"s","label":"S"},{"key":"l","label":"L"}]}]"""

        val saved = service.create(
            named(
                "variantOptions" to axes,
                "variants" to """[
                    {"name":"S","sku":"V-S","optionValues":{"size":"s"},"attributes":{"color":"red"},"price":"9","stock":4,"prices":[{"currency":"USD","price":"10"}]},
                    {"name":"L","optionValues":{"size":"l"},"stock":null,"status":"INACTIVE"}
                ]""",
                "fields" to """[{"fieldKey":"nick","label":"Nick","required":true,"pattern":"[a-z]{3,8}","minLength":3,"maxLength":8},
                                {"fieldKey":"kind","label":"Kind","type":"SELECT","options":[{"value":"a","label":"A"}]}]""",
                "prices" to """[{"currency":"TRY","price":"300","compareAtPrice":"400"}]""",
                "providerMeta" to """{"stripe":{"packageId":"123"}}""",
                "actions" to "[]"
            )
        )

        val view = service.get(saved.id)

        assertTrue(view.product.hasVariants)
        assertEquals(listOf("S", "L"), view.variants.map { it.name })
        assertEquals(4, view.variants[0].stock)
        assertNull(view.variants[1].stock)
        assertEquals(MarketStatus.INACTIVE, view.variants[1].status)
        assertEquals(900L, view.variants[0].price)
        assertEquals("""{"size":"s"}""", view.variants[0].optionValues)
        assertEquals("""{"color":"red"}""", view.variants[0].attributes)
        assertEquals(listOf(0, 1), view.variants.map { it.position })

        assertEquals(listOf("nick", "kind"), view.fields.map { it.fieldKey })
        assertTrue(view.fields[0].required)
        assertEquals("[a-z]{3,8}", view.fields[0].pattern)
        assertEquals("""[{"value":"a","label":"A"}]""", view.fields[1].options)

        assertEquals(setOf(0L to "TRY", view.variants[0].id to "USD"), view.prices.map { it.variantId to it.currency }.toSet())
        assertEquals(40000L, view.prices.single { it.currency == "TRY" }.compareAtPrice)
        assertEquals(mapOf("stripe" to """{"packageId":"123"}"""), view.providerMeta)

        // a bundle with a real child
        val bundle = service.create(named("kind" to "BUNDLE", "bundleItems" to """[{"productId":${child.id},"quantity":3}]"""))
        val bundleView = service.get(bundle.id)
        assertEquals(listOf(child.id to 3), bundleView.bundleItems.map { it.productId to it.quantity })
    }

    @Test
    fun `stock is honoured on create only and an update never writes it, not even from a stale form`(): Unit = runBlocking {
        val saved = create("stock" to 5)
        assertEquals(5, service.get(saved.id).product.stock)

        // somebody sells units meanwhile
        w.products.setStock(saved.id, 2, pool)

        service.update(saved.id, input("name" to "Renamed", "stock" to 5))
        val after = service.get(saved.id).product

        assertEquals("Renamed", after.name)
        assertEquals(2, after.stock)

        service.update(saved.id, input("stock" to ""))
        assertEquals(2, service.get(saved.id).product.stock)
    }

    @Test
    fun `an unlimited stock on create is stored as null`(): Unit = runBlocking {
        assertNull(service.get(create().id).product.stock)
        assertNull(service.get(create("stock" to "").id).product.stock)
    }

    // ----- partial update ---------------------------------------------------------------------------------------------

    @Test
    fun `update is partial - omitted scalars and omitted set parts stay`(): Unit = runBlocking {
        val saved = create(
            "price" to "10", "creditPrice" to "3", "featured" to true, "priority" to 4, "physical" to true, "weightGrams" to 100,
            "variants" to """[{"name":"A","stock":3},{"name":"B"}]""",
            "fields" to """[{"fieldKey":"nick","label":"Nick"}]""",
            "prices" to """[{"currency":"USD","price":"11"}]""",
            "providerMeta" to """{"stripe":{"x":1}}"""
        )
        val before = service.get(saved.id)

        service.update(saved.id, input("name" to "New name"))

        val after = service.get(saved.id)
        assertEquals("New name", after.product.name)
        assertEquals(saved.slug, after.product.slug)
        assertEquals(1000L, after.product.price)
        assertEquals(300L, after.product.creditPrice)
        assertTrue(after.product.featured)
        assertEquals(4, after.product.priority)
        assertTrue(after.product.physical)
        assertEquals(100, after.product.weightGrams)
        assertEquals(before.variants.map { it.id }, after.variants.map { it.id })
        assertEquals(before.fields.map { it.id }, after.fields.map { it.id })
        assertEquals(before.prices.map { it.id }, after.prices.map { it.id })
        assertEquals(before.providerMeta, after.providerMeta)
        assertTrue(after.product.updatedAt >= before.product.updatedAt)
        assertEquals(before.product.createdAt, after.product.createdAt)
    }

    @Test
    fun `update can clear a nullable column with a blank value`(): Unit = runBlocking {
        val saved = create("shortDescription" to "x", "compareAtPrice" to "20", "sku" to "A1", "vatPercent" to "5")

        service.update(saved.id, input("shortDescription" to "", "compareAtPrice" to "", "sku" to null, "vatPercent" to ""))

        val p = service.get(saved.id).product
        assertNull(p.shortDescription)
        assertNull(p.compareAtPrice)
        assertNull(p.sku)
        assertNull(p.vatPercent)
    }

    @Test
    fun `an update of a missing or deleted product is NOT_FOUND`(): Unit = runBlocking {
        assertThrows(NotFound::class.java) { runBlocking { service.update(9999, input("name" to "x")) } }

        val saved = create()
        service.delete(saved.id)
        assertThrows(NotFound::class.java) { runBlocking { service.update(saved.id, input("name" to "x")) } }
        assertThrows(NotFound::class.java) { runBlocking { service.get(saved.id) } }
        assertThrows(NotFound::class.java) { runBlocking { service.delete(saved.id) } }
    }

    // ----- slugs -------------------------------------------------------------------------------------------------------

    @Test
    fun `reserved slugs checkout, order and cart are refused on create and update and nothing is stored`(): Unit = runBlocking {
        val before = productCount()

        listOf("checkout", "order", "cart", "Checkout", " CART ").forEach { slug ->
            assertThrows(ReservedSlug::class.java, { runBlocking { service.create(input("name" to "x", "slug" to slug)) } }, slug)
        }
        assertThrows(ReservedSlug::class.java) { runBlocking { service.create(input("name" to "Cart")) } }
        assertEquals(before, productCount())

        val saved = create()
        assertThrows(ReservedSlug::class.java) { runBlocking { service.update(saved.id, input("slug" to "order")) } }
        assertEquals(saved.slug, service.get(saved.id).product.slug)

        // near misses are fine
        assertEquals("checkout-pass", service.create(input("name" to "x", "slug" to "checkout-pass")).slug)
    }

    @Test
    fun `a taken slug is refused and an update keeps its own slug`(): Unit = runBlocking {
        val a = create("slug" to "same-slug")
        assertThrows(SlugAlreadyExists::class.java) { runBlocking { create("slug" to "same-slug") } }
        assertThrows(SlugAlreadyExists::class.java) { runBlocking { create("slug" to "Same Slug") } }

        val b = create()
        assertThrows(SlugAlreadyExists::class.java) { runBlocking { service.update(b.id, input("slug" to "same-slug")) } }

        // saving a product with its own slug is not a conflict
        service.update(a.id, input("slug" to "same-slug", "name" to "Renamed"))
        assertEquals("same-slug", service.get(a.id).product.slug)
    }

    @Test
    fun `a rename keeps the slug unless one is asked for, a blank slug regenerates it from the name`(): Unit = runBlocking {
        val saved = service.create(input("name" to "Old Name", "price" to "1"))
        assertEquals("old-name", saved.slug)

        service.update(saved.id, input("name" to "New Name"))
        assertEquals("old-name", service.get(saved.id).product.slug)

        service.update(saved.id, input("slug" to ""))
        assertEquals("new-name", service.get(saved.id).product.slug)

        service.update(saved.id, input("slug" to "custom"))
        assertEquals("custom", service.get(saved.id).product.slug)
    }

    @Test
    fun `eight concurrent creates of one slug leave exactly one product and no stray rows`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val slug = "race-slug-$round"
            val results = Race.run(8) {
                service.create(
                    input(
                        "name" to "Racer $it", "slug" to slug, "price" to "1",
                        "variants" to """[{"name":"v","stock":1}]""", "fields" to """[{"fieldKey":"f","label":"F"}]"""
                    )
                )
            }

            assertEquals(1, results.count { it.isSuccess }, "round $round")
            results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is SlugAlreadyExists, it.exceptionOrNull().toString()) }

            val winner = w.products.getBySlug(slug, pool)!!
            assertEquals(1L, count("market_product", "`slug` = ?", slug))
            assertEquals(1L, count("market_product_variant", "`productId` = ?", winner.id))
            assertEquals(1L, count("market_product_field", "`productId` = ?", winner.id))
        }
        assertEquals(0L, count("market_product_variant", "`productId` NOT IN (SELECT `id` FROM `pano_market_product`)"))
        assertEquals(0L, count("market_product_field", "`productId` NOT IN (SELECT `id` FROM `pano_market_product`)"))
    }

    // ----- validation reaches the service -----------------------------------------------------------------------------

    @Test
    fun `an invalid product answers INVALID_PRODUCT with dotted fieldErrors and stores nothing`(): Unit = runBlocking {
        val before = productCount()

        val errors = fieldErrors {
            service.create(
                input(
                    "name" to "", "price" to "-1", "kind" to "CREDIT_PACK",
                    "fields" to """[{"fieldKey":"BAD","label":"x"}]""",
                    "prices" to """[{"currency":"EUR","price":"1"}]"""
                )
            )
        }

        assertEquals("REQUIRED", errors["name"])
        assertEquals("OUT_OF_RANGE", errors["price"])
        assertEquals("REQUIRED", errors["creditAmount"])
        assertEquals("INVALID", errors["fields.0.fieldKey"])
        assertEquals("BASE_CURRENCY", errors["prices.0.currency"])
        assertEquals(before, productCount())
        assertEquals(0L, count("market_product_field"))
    }

    @Test
    fun `the category must exist and a tiered category needs a tier rank and forces quantity one`(): Unit = runBlocking {
        assertEquals("NOT_FOUND", fieldErrors { create("categoryId" to 98765) }["categoryId"])

        val tiered = w.fixtures.category(tiered = true)
        assertEquals("REQUIRED", fieldErrors { create("categoryId" to tiered.id) }["tierRank"])

        val saved = create("categoryId" to tiered.id, "tierRank" to 2, "maxQuantityPerOrder" to 5)
        val p = service.get(saved.id).product
        assertEquals(2, p.tierRank)
        assertEquals(1, p.maxQuantityPerOrder)

        val plain = w.fixtures.category()
        assertEquals(5, service.get(create("categoryId" to plain.id, "maxQuantityPerOrder" to 5).id).product.maxQuantityPerOrder)
        // -1 and blank detach
        assertNull(service.get(create("categoryId" to -1).id).product.categoryId)
    }

    @Test
    fun `required products must exist, be live and not be the product itself`(): Unit = runBlocking {
        val a = create()
        assertEquals("NOT_FOUND", fieldErrors { create("requiredProducts" to "[${a.id},99999]") }["requiredProducts"])
        assertEquals("SELF", fieldErrors { service.update(a.id, input("requiredProducts" to "[${a.id}]")) }["requiredProducts"])

        val b = create("requiredProducts" to "[${a.id}]")
        assertEquals(listOf(a.id), service.get(b.id).product.requiredProducts)

        service.delete(a.id)
        assertEquals("NOT_FOUND", fieldErrors { create("requiredProducts" to "[${a.id}]") }["requiredProducts"])
    }

    @Test
    fun `the price rows in a foreign currency follow that currency's exponent`(): Unit = runBlocking {
        assertEquals("NOT_WHOLE_UNITS", fieldErrors { create("prices" to """[{"currency":"JPY","price":"1500.5"}]""") }["prices.0.price"])

        val saved = create("prices" to """[{"currency":"JPY","price":"1500"},{"currency":"USD","price":"15.55"}]""")
        val prices = service.get(saved.id).prices.associate { it.currency to it.price }

        assertEquals(150000L, prices["JPY"])
        assertEquals(1555L, prices["USD"])
    }

    @Test
    fun `NO_SHIPPING_METHOD is reported for a physical product until a sellable method exists`(): Unit = runBlocking {
        assertEquals(listOf("NO_SHIPPING_METHOD"), create("physical" to true, "weightGrams" to 10).warnings)
        assertEquals(emptyList<String>(), create().warnings)

        val zone = w.fixtures.shippingZone()
        val method = w.fixtures.shippingMethod()
        // a method without a rate row is still not sellable
        assertEquals(listOf("NO_SHIPPING_METHOD"), create("physical" to true, "weightGrams" to 10).warnings)

        w.shippingRates.add(MarketShippingRate(methodId = method.id, zoneId = zone.id), pool)
        assertEquals(emptyList<String>(), create("physical" to true, "weightGrams" to 10).warnings)

        // an inactive method stops counting
        Fixtures.setColumns(pool, "market_shipping_method", method.id, mapOf("status" to "INACTIVE"))
        assertEquals(listOf("NO_SHIPPING_METHOD"), create("physical" to true, "weightGrams" to 10).warnings)
    }

    // ----- variants ---------------------------------------------------------------------------------------------------

    @Test
    fun `variants are matched by id - rename, add, remove, order and the stock of an existing row stays`(): Unit = runBlocking {
        val saved = create("variants" to """[{"name":"A","stock":5},{"name":"B","stock":6},{"name":"C","stock":7}]""")
        val rows = service.get(saved.id).variants
        val (a, b, c) = rows

        val json = JsonArray()
            .add(JsonObject().put("id", c.id).put("name", "C renamed").put("stock", 999))
            .add(JsonObject().put("id", a.id).put("name", "A").put("price", "12"))
            .add(JsonObject().put("name", "D new").put("stock", 3))
            .encode()
        service.update(saved.id, input("variants" to json))

        val after = service.get(saved.id).variants
        assertEquals(listOf("C renamed", "A", "D new"), after.map { it.name })
        assertEquals(listOf(c.id, a.id), after.take(2).map { it.id })
        assertEquals(listOf(0, 1, 2), after.map { it.position })
        assertEquals(7, after[0].stock)
        assertEquals(5, after[1].stock)
        assertEquals(1200L, after[1].price)
        assertEquals(3, after[2].stock)
        assertNull(w.variants.getById(b.id, pool), "an unreferenced variant is removed for good")
    }

    @Test
    fun `a variant id of another product is refused`(): Unit = runBlocking {
        val one = create("variants" to """[{"name":"A"}]""")
        val two = create("variants" to """[{"name":"B"}]""")
        val foreign = service.get(two.id).variants.single().id

        val errors = fieldErrors { service.update(one.id, input("variants" to """[{"id":$foreign,"name":"stolen"}]""")) }

        assertEquals("NOT_FOUND", errors["variants.0.id"])
        assertEquals("B", w.variants.getById(foreign, pool)!!.name)
    }

    @Test
    fun `a removed variant that is referenced is soft deleted and leaves the carts, an unreferenced one is deleted with its prices`(): Unit = runBlocking {
        val saved = create(
            "variants" to """[{"name":"keep"},{"name":"ordered","prices":[{"currency":"USD","price":"3"}]},{"name":"carted"},{"name":"free","prices":[{"currency":"USD","price":"2"}]}]"""
        )
        val (keep, ordered, carted, free) = service.get(saved.id).variants
        Fixtures.insertRaw(pool, "market_order_item", mapOf("productId" to saved.id, "variantId" to ordered.id))
        Fixtures.insertRaw(pool, "market_cart_item", mapOf("productId" to saved.id, "variantId" to carted.id, "cartId" to 1, "lineKey" to "k1"))

        service.update(saved.id, input("variants" to """[{"id":${keep.id},"name":"keep"}]"""))

        assertEquals(listOf(keep.id), service.get(saved.id).variants.map { it.id })
        assertNotNull(w.variants.getById(ordered.id, pool)!!.deletedAt, "referenced by an order item")
        assertNotNull(w.variants.getById(carted.id, pool)!!.deletedAt, "referenced by a cart line")
        assertNull(w.variants.getById(free.id, pool), "unreferenced")
        assertEquals(0L, count("market_product_price", "`variantId` = ?", free.id))
        assertEquals(0L, count("market_cart_item", "`variantId` = ?", carted.id), "gone from the carts")
        assertEquals(1L, count("market_order_item", "`variantId` = ?", ordered.id), "history untouched")
    }

    @Test
    fun `hasVariants cannot be switched off while variants remain, an empty list removes them`(): Unit = runBlocking {
        val saved = create("variants" to """[{"name":"A"}]""")

        assertEquals("NOT_ALLOWED", fieldErrors { service.update(saved.id, input("hasVariants" to false)) }["variants"])
        assertEquals("REQUIRED", fieldErrors { service.update(saved.id, input("variants" to "[]", "hasVariants" to true)) }["variants"])

        service.update(saved.id, input("variants" to "[]"))
        val view = service.get(saved.id)
        assertFalse(view.product.hasVariants)
        assertEquals(0, view.variants.size)
    }

    @Test
    fun `variant images - a replaced or removed file is reported as an orphan, a kept one is not`(): Unit = runBlocking {
        val saved = service.create(
            ProductRequestParser.parse(
                JsonObject().put("name", "Img").put("price", "1").put("variants", """[{"name":"A"},{"name":"B"}]"""),
                ImageChange.Set("main.png"), mapOf(0 to "a.png", 1 to "b.png")
            )
        )
        assertEquals(emptyList<String>(), saved.orphanedFiles)
        val (a, b) = service.get(saved.id).variants
        assertEquals("a.png", a.imageFileName)
        assertEquals("main.png", service.get(saved.id).product.imageFileName)

        val replaced = service.update(
            saved.id,
            ProductRequestParser.parse(
                JsonObject().put("variants", """[{"id":${a.id},"name":"A"},{"id":${b.id},"name":"B","removeImage":true}]"""),
                ImageChange.Set("main2.png"), mapOf(0 to "a2.png")
            )
        )

        assertEquals(setOf("main.png", "a.png", "b.png"), replaced.orphanedFiles.toSet())
        val after = service.get(saved.id)
        assertEquals("a2.png", after.variants[0].imageFileName)
        assertNull(after.variants[1].imageFileName)
        assertEquals("main2.png", after.product.imageFileName)

        // no file part and no flag: kept
        val kept = service.update(saved.id, input("variants" to """[{"id":${a.id},"name":"A"},{"id":${b.id},"name":"B"}]"""))
        assertEquals(emptyList<String>(), kept.orphanedFiles)
        assertEquals("a2.png", service.get(saved.id).variants[0].imageFileName)

        val removed = service.update(saved.id, input("removeImage" to "true"))
        assertEquals(listOf("main2.png"), removed.orphanedFiles)
        assertNull(service.get(saved.id).product.imageFileName)
    }

    // ----- fields -----------------------------------------------------------------------------------------------------

    @Test
    fun `fields replace by id, may swap their keys inside one save and a missing one is deleted`(): Unit = runBlocking {
        val saved = create("fields" to """[{"fieldKey":"a","label":"A"},{"fieldKey":"b","label":"B"},{"fieldKey":"c","label":"C"}]""")
        val (a, b, c) = service.get(saved.id).fields

        service.update(
            saved.id,
            input(
                "fields" to """[{"id":${a.id},"fieldKey":"b","label":"A now b"},{"id":${b.id},"fieldKey":"a","label":"B now a","type":"NUMBER"},{"fieldKey":"d","label":"D"}]"""
            )
        )

        val after = service.get(saved.id).fields
        assertEquals(listOf("b", "a", "d"), after.map { it.fieldKey })
        assertEquals(listOf(a.id, b.id), after.take(2).map { it.id })
        assertEquals("A now b", after[0].label)
        assertEquals("NUMBER", after[1].type.name)
        assertNull(w.fields.getById(c.id, pool))
        assertEquals(listOf(0, 1, 2), after.map { it.position })
    }

    @Test
    fun `a field id of another product is refused`(): Unit = runBlocking {
        val one = create("fields" to """[{"fieldKey":"a","label":"A"}]""")
        val two = create("fields" to """[{"fieldKey":"a","label":"A"}]""")
        val foreign = service.get(two.id).fields.single().id

        assertEquals("NOT_FOUND", fieldErrors { service.update(one.id, input("fields" to """[{"id":$foreign,"fieldKey":"a","label":"x"}]""")) }["fields.0.id"])
    }

    // ----- prices -----------------------------------------------------------------------------------------------------

    @Test
    fun `top level prices replace every stored row, a variant's own prices replace only its rows`(): Unit = runBlocking {
        val saved = create(
            "variants" to """[{"name":"A"},{"name":"B"}]""",
            "prices" to """[{"currency":"USD","price":"10"},{"currency":"TRY","price":"300"}]"""
        )
        val (a, b) = service.get(saved.id).variants

        fun keys() = runBlocking { service.get(saved.id).prices.map { it.variantId to it.currency }.toSet() }

        // variant A gets its own rows through the variant, product level and B untouched
        service.update(
            saved.id,
            input("variants" to """[{"id":${a.id},"name":"A","prices":[{"currency":"USD","price":"5"},{"currency":"GBP","price":"6"}]},{"id":${b.id},"name":"B"}]""")
        )
        assertEquals(setOf(0L to "USD", 0L to "TRY", a.id to "USD", a.id to "GBP"), keys())

        // replacing A's rows drops the ones not listed
        service.update(saved.id, input("variants" to """[{"id":${a.id},"name":"A","prices":[{"currency":"GBP","price":"7"}]},{"id":${b.id},"name":"B"}]"""))
        assertEquals(setOf(0L to "USD", 0L to "TRY", a.id to "GBP"), keys())
        assertEquals(700L, service.get(saved.id).prices.single { it.variantId == a.id }.price)

        // top level: everything not listed goes
        service.update(saved.id, input("prices" to """[{"currency":"USD","price":"11"},{"variantId":${b.id},"currency":"USD","price":"12"}]"""))
        assertEquals(setOf(0L to "USD", b.id to "USD"), keys())

        // an empty top level list clears all
        service.update(saved.id, input("prices" to "[]"))
        assertEquals(emptySet<Pair<Long, String>>(), keys())
    }

    @Test
    fun `a price row of an unknown variant is refused and a re-saved currency updates in place`(): Unit = runBlocking {
        val saved = create("prices" to """[{"currency":"USD","price":"10"}]""")
        val id = service.get(saved.id).prices.single().id

        assertEquals("NOT_FOUND", fieldErrors { service.update(saved.id, input("prices" to """[{"variantId":99999,"currency":"USD","price":"1"}]""")) }["prices.0.variantId"])

        service.update(saved.id, input("prices" to """[{"currency":"USD","price":"20","compareAtPrice":"30"}]"""))
        val row = service.get(saved.id).prices.single()
        assertEquals(id, row.id)
        assertEquals(2000L, row.price)
        assertEquals(3000L, row.compareAtPrice)
    }

    // ----- bundle rows ------------------------------------------------------------------------------------------------

    @Test
    fun `bundle rows are matched by child and variant, children must exist and belong together`(): Unit = runBlocking {
        val x = w.fixtures.product()
        val y = w.fixtures.product()
        val z = w.fixtures.product()
        val xv = w.fixtures.variant(x)
        val saved = create("kind" to "BUNDLE", "bundleItems" to """[{"productId":${x.id},"quantity":1},{"productId":${y.id},"quantity":2}]""")
        val first = service.get(saved.id).bundleItems

        service.update(saved.id, input("bundleItems" to """[{"productId":${y.id},"quantity":5},{"productId":${z.id},"quantity":1},{"productId":${x.id},"variantId":${xv.id},"quantity":1}]"""))

        val after = service.get(saved.id).bundleItems
        assertEquals(setOf(Triple(y.id, 0L, 5), Triple(z.id, 0L, 1), Triple(x.id, xv.id, 1)), after.map { Triple(it.productId, it.variantId, it.quantity) }.toSet())
        assertEquals(first.single { it.productId == y.id }.id, after.single { it.productId == y.id }.id)
        assertEquals(listOf(y.id, z.id, x.id), after.map { it.productId })

        assertEquals("NOT_FOUND", fieldErrors { service.update(saved.id, input("bundleItems" to """[{"productId":99999,"quantity":1}]""")) }["bundleItems.0.productId"])
        assertEquals("SELF", fieldErrors { service.update(saved.id, input("bundleItems" to """[{"productId":${saved.id},"quantity":1}]""")) }["bundleItems.0.productId"])
        assertEquals("NOT_FOUND", fieldErrors { service.update(saved.id, input("bundleItems" to """[{"productId":${y.id},"variantId":${xv.id},"quantity":1}]""")) }["bundleItems.0.variantId"])
        assertEquals("REQUIRED", fieldErrors { service.update(saved.id, input("bundleItems" to "[]")) }["bundleItems"])

        service.delete(z.id)
        assertEquals("NOT_FOUND", fieldErrors { service.update(saved.id, input("bundleItems" to """[{"productId":${z.id},"quantity":1}]""")) }["bundleItems.0.productId"])
    }

    // ----- provider meta ----------------------------------------------------------------------------------------------

    @Test
    fun `provider meta is replaced as a set`(): Unit = runBlocking {
        val saved = create("providerMeta" to """{"stripe":{"a":1},"paypal":{"b":2}}""")
        assertEquals(setOf("stripe", "paypal"), service.get(saved.id).providerMeta.keys)

        service.update(saved.id, input("providerMeta" to """{"stripe":{"a":9}}"""))
        assertEquals(mapOf("stripe" to """{"a":9}"""), service.get(saved.id).providerMeta)

        service.update(saved.id, input("name" to "no meta part"))
        assertEquals(1, service.get(saved.id).providerMeta.size)

        service.update(saved.id, input("providerMeta" to "{}"))
        assertEquals(emptyMap<String, String>(), service.get(saved.id).providerMeta)
    }

    // ----- delete -----------------------------------------------------------------------------------------------------

    @Test
    fun `a product that nothing references is deleted with every set part and its files are reported`(): Unit = runBlocking {
        val child = w.fixtures.product()
        val saved = service.create(
            ProductRequestParser.parse(
                JsonObject().put("name", "Gone").put("price", "1").put("kind", "BUNDLE")
                    .put("bundleItems", """[{"productId":${child.id},"quantity":1}]""")
                    .put("fields", """[{"fieldKey":"a","label":"A"}]""")
                    .put("prices", """[{"currency":"USD","price":"1"}]""")
                    .put("providerMeta", """{"stripe":{}}"""),
                ImageChange.Set("p.png")
            )
        )
        val variantOwner = service.create(
            ProductRequestParser.parse(JsonObject().put("name", "V").put("price", "1").put("variants", """[{"name":"A"}]"""), ImageChange.Set("vo.png"), mapOf(0 to "v0.png"))
        )

        val result = service.delete(saved.id)
        assertFalse(result.soft)
        assertEquals(listOf("p.png"), result.orphanedFiles)
        assertNull(w.products.getById(saved.id, pool))
        listOf("market_bundle_item" to "bundleProductId", "market_product_field" to "productId", "market_product_price" to "productId", "market_product_provider_meta" to "productId")
            .forEach { (table, column) -> assertEquals(0L, count(table, "`$column` = ?", saved.id), table) }

        val second = service.delete(variantOwner.id)
        assertEquals(setOf("vo.png", "v0.png"), second.orphanedFiles.toSet())
        assertEquals(0L, count("market_product_variant", "`productId` = ?", variantOwner.id))
    }

    @Test
    fun `a referenced product is soft deleted - archived, hidden, off the carts, slug free again, history intact`(): Unit = runBlocking {
        data class Ref(val table: String, val column: String, val extra: Map<String, Any?> = emptyMap())

        val references = listOf(
            Ref("market_order_item", "productId"),
            Ref("market_entitlement", "productId"),
            Ref("market_subscription", "productId"),
            Ref("market_cart_item", "productId", mapOf("cartId" to 1, "lineKey" to "lk")),
            Ref("market_bundle_item", "productId", mapOf("bundleProductId" to 777))
        )

        references.forEachIndexed { index, ref ->
            val saved = create("slug" to "referenced-$index", "variants" to """[{"name":"A"}]""", "fields" to """[{"fieldKey":"a","label":"A"}]""")
            Fixtures.insertRaw(pool, ref.table, mapOf(ref.column to saved.id) + ref.extra)
            Fixtures.insertRaw(pool, "market_cart_item", mapOf("productId" to saved.id, "cartId" to 2, "lineKey" to "other-$index"))

            val result = service.delete(saved.id)
            assertTrue(result.soft, ref.table)
            assertEquals(emptyList<String>(), result.orphanedFiles, "files of a soft deleted product stay (snapshots)")

            val row = w.products.getById(saved.id, pool)!!
            assertNotNull(row.deletedAt, ref.table)
            assertEquals(MarketStatus.ARCHIVED, row.status, ref.table)
            assertEquals("referenced-$index--d${saved.id}", row.slug, ref.table)
            // history stays, except the cart line which leaves the carts on purpose (asserted below)
            if (ref.table != "market_cart_item") assertEquals(1L, count(ref.table, "`${ref.column}` = ?", saved.id), "history kept: ${ref.table}")
            assertEquals(1L, count("market_product_variant", "`productId` = ?", saved.id), "set parts stay for the snapshots")
            assertEquals(0L, count("market_cart_item", "`productId` = ?", saved.id), "off the carts: ${ref.table}")

            assertThrows(NotFound::class.java) { runBlocking { service.get(saved.id) } }
            assertFalse(service.list(1, 100, null, null, null, null).products.any { it.id == saved.id })
            assertFalse(w.products.getAllSimple(pool).any { it.id == saved.id })
            assertFalse(w.products.getVisibleProducts(pool).any { it.id == saved.id })

            // the slug is free: a new product may take it
            assertEquals("referenced-$index", create("slug" to "referenced-$index").slug)
        }
    }

    @Test
    fun `a product that is a bundle child counts as referenced`(): Unit = runBlocking {
        val child = w.fixtures.product()
        w.fixtures.bundle(child to 1)

        assertTrue(service.delete(child.id).soft)
    }

    // ----- stock ------------------------------------------------------------------------------------------------------

    @Test
    fun `SET writes the value or unlimited and ADJUST adds a signed delta`(): Unit = runBlocking {
        val id = create("stock" to 5).id

        assertEquals(9, service.changeStock(id, null, StockMode.SET, 9).stock)
        assertEquals(6, service.changeStock(id, null, StockMode.ADJUST, -3).stock)
        assertEquals(10, service.changeStock(id, null, StockMode.ADJUST, 4).stock)
        assertEquals(0, service.changeStock(id, null, StockMode.SET, 0).stock)
        assertNull(service.changeStock(id, null, StockMode.SET, null).stock)
        assertNull(w.products.getById(id, pool)!!.stock)
        assertEquals(3, service.changeStock(id, null, StockMode.SET, 3).stock)
    }

    @Test
    fun `ADJUST below zero is refused and leaves the stock alone`(): Unit = runBlocking {
        val id = create("stock" to 2).id

        assertEquals("STOCK_OUT_OF_RANGE", fieldErrors { service.changeStock(id, null, StockMode.ADJUST, -3) }["value"])
        assertEquals(2, w.products.getById(id, pool)!!.stock)

        assertEquals(0, service.changeStock(id, null, StockMode.ADJUST, -2).stock)
        assertEquals("STOCK_OUT_OF_RANGE", fieldErrors { service.changeStock(id, null, StockMode.ADJUST, -1) }["value"])
        assertEquals(0, w.products.getById(id, pool)!!.stock)
    }

    @Test
    fun `ADJUST on an unlimited stock and above the cap are refused`(): Unit = runBlocking {
        val unlimited = create().id
        assertEquals("STOCK_UNLIMITED", fieldErrors { service.changeStock(unlimited, null, StockMode.ADJUST, 5) }["value"])
        assertNull(w.products.getById(unlimited, pool)!!.stock)

        val full = create("stock" to 1_000_000_000).id
        assertEquals("STOCK_OUT_OF_RANGE", fieldErrors { service.changeStock(full, null, StockMode.ADJUST, 1) }["value"])
        assertEquals(1_000_000_000, w.products.getById(full, pool)!!.stock)
    }

    @Test
    fun `stock values outside the contract are refused`(): Unit = runBlocking {
        val id = create("stock" to 1).id

        assertEquals("OUT_OF_RANGE", fieldErrors { service.changeStock(id, null, StockMode.SET, -1) }["value"])
        assertEquals("OUT_OF_RANGE", fieldErrors { service.changeStock(id, null, StockMode.SET, 1_000_000_001) }["value"])
        assertEquals("REQUIRED", fieldErrors { service.changeStock(id, null, StockMode.ADJUST, null) }["value"])
        assertEquals("OUT_OF_RANGE", fieldErrors { service.changeStock(id, null, StockMode.ADJUST, 0) }["value"])
        assertEquals(1, w.products.getById(id, pool)!!.stock)
        assertThrows(NotFound::class.java) { runBlocking { service.changeStock(99999, null, StockMode.SET, 1) } }
    }

    @Test
    fun `a product with variants is addressed through its variant`(): Unit = runBlocking {
        val id = create("variants" to """[{"name":"A","stock":5},{"name":"B","stock":1}]""").id
        val (a, b) = service.get(id).variants
        val other = service.get(create("variants" to """[{"name":"Z"}]""").id).variants.single()

        assertEquals("REQUIRED", fieldErrors { service.changeStock(id, null, StockMode.SET, 1) }["variantId"])
        assertEquals("NOT_FOUND", fieldErrors { service.changeStock(id, 99999, StockMode.SET, 1) }["variantId"])
        assertEquals("NOT_FOUND", fieldErrors { service.changeStock(id, other.id, StockMode.SET, 1) }["variantId"])

        assertEquals(8, service.changeStock(id, a.id, StockMode.ADJUST, 3).stock)
        assertEquals(8, w.variants.getById(a.id, pool)!!.stock)
        assertEquals(1, w.variants.getById(b.id, pool)!!.stock)
        assertNull(service.changeStock(id, b.id, StockMode.SET, null).stock)
        assertEquals("STOCK_UNLIMITED", fieldErrors { service.changeStock(id, b.id, StockMode.ADJUST, 1) }["value"])
        assertEquals("STOCK_OUT_OF_RANGE", fieldErrors { service.changeStock(id, a.id, StockMode.ADJUST, -9) }["value"])

        val plain = create().id
        assertEquals("NOT_APPLICABLE", fieldErrors { service.changeStock(plain, a.id, StockMode.SET, 1) }["variantId"])
    }

    @Test
    fun `a soft deleted variant cannot be addressed`(): Unit = runBlocking {
        val id = create("variants" to """[{"name":"A"},{"name":"B"}]""").id
        val (a, b) = service.get(id).variants
        Fixtures.insertRaw(pool, "market_order_item", mapOf("productId" to id, "variantId" to b.id))
        service.update(id, input("variants" to """[{"id":${a.id},"name":"A"}]"""))

        assertEquals("NOT_FOUND", fieldErrors { service.changeStock(id, b.id, StockMode.SET, 1) }["variantId"])
    }

    @Test
    fun `thirty concurrent decrements of ten units succeed exactly ten times and never go below zero`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val id = create("stock" to 10).id

            val results = Race.run(30) { runCatching { service.changeStock(id, null, StockMode.ADJUST, -1) } }

            val wins = results.count { it.getOrThrow().isSuccess }
            assertEquals(10, wins, "round $round")
            results.map { it.getOrThrow() }.filter { it.isFailure }.forEach {
                assertTrue(it.exceptionOrNull() is InvalidProduct, it.exceptionOrNull().toString())
            }
            assertEquals(0, w.products.getById(id, pool)!!.stock)
        }
    }

    @Test
    fun `concurrent adjustments of a variant add up exactly`(): Unit = runBlocking {
        val id = create("variants" to """[{"name":"A","stock":100}]""").id
        val variant = service.get(id).variants.single()

        val results = Race.run(20) { i -> runCatching { service.changeStock(id, variant.id, StockMode.ADJUST, if (i % 2 == 0) 5 else -3) } }

        assertTrue(results.all { it.getOrThrow().isSuccess })
        assertEquals(100 + 10 * 5 - 10 * 3, w.variants.getById(variant.id, pool)!!.stock)
    }

    @Test
    fun `an adjustment racing an update never loses the update's other columns or the counter`(): Unit = runBlocking {
        val id = create("stock" to 50).id

        val results = Race.run(20) { i ->
            runCatching {
                if (i % 2 == 0) service.changeStock(id, null, StockMode.ADJUST, -1) else service.update(id, input("name" to "Updated $i", "stock" to 999))
            }
        }

        assertTrue(results.all { it.getOrThrow().isSuccess }, results.toString())
        val p = w.products.getById(id, pool)!!
        assertEquals(40, p.stock)
        assertTrue(p.name.startsWith("Updated "))
    }

    // ----- description sanitising (11 section 6.1) ---------------------------------------------------------------------

    @Test
    fun `a description is stored sanitised on create and on update, and an over long raw text is refused`(): Unit = runBlocking {
        val dirty = "<p onclick=\"x()\">Hi</p><script>alert(1)</script><img src=x onerror=alert(1)>"
        val saved = create("description" to dirty)

        fun assertClean(text: String?) {
            assertNotNull(text)
            assertFalse(text!!.contains("<script", ignoreCase = true), text)
            assertFalse(text.contains("onerror", ignoreCase = true), text)
            assertFalse(text.contains("onclick", ignoreCase = true), text)
            assertTrue(text.contains("<p>Hi</p>"), text)
        }

        assertClean(w.products.getById(saved.id, pool)!!.description)
        assertClean(service.get(saved.id).product.description)

        service.update(saved.id, input("description" to "<b>ok</b>"))
        assertEquals("<b>ok</b>", service.get(saved.id).product.description)

        service.update(saved.id, input("description" to dirty))
        assertClean(w.products.getById(saved.id, pool)!!.description)

        val tooLong = "<script>" + "x".repeat(100_000) + "</script>"
        assertEquals("TOO_LONG", fieldErrors { service.update(saved.id, input("description" to tooLong)) }["description"])
        assertEquals("TOO_LONG", fieldErrors { create("description" to tooLong) }["description"])
        assertClean(w.products.getById(saved.id, pool)!!.description)
    }

    // ----- concurrent save and delete ----------------------------------------------------------------------------------

    @Test
    fun `an update racing a delete of a referenced product leaves a cleanly archived row, never a live slug on a deleted one`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val slug = "race-ref-$round"
            val saved = create("slug" to slug, "variants" to """[{"name":"A"}]""")
            Fixtures.insertRaw(pool, "market_order_item", mapOf("productId" to saved.id))

            val results = Race.run(10) { i ->
                if (i == 4) service.delete(saved.id) else service.update(saved.id, input("name" to "Racer $i", "slug" to slug, "status" to "ACTIVE"))
            }

            results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is NotFound, "round $round: ${it.exceptionOrNull()}") }
            assertTrue(results[4].isSuccess, "round $round: ${results[4].exceptionOrNull()}")

            val row = w.products.getById(saved.id, pool)!!
            assertNotNull(row.deletedAt, "round $round")
            assertEquals(MarketStatus.ARCHIVED, row.status, "round $round")
            assertEquals("$slug--d${saved.id}", row.slug, "round $round")
            assertNull(w.products.getBySlug(slug, pool), "round $round: the live slug must be free")
            assertEquals(slug, create("slug" to slug).slug)
        }
    }

    @Test
    fun `an update racing a delete of an unreferenced product leaves no row and no orphaned set part`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val saved = create(
                "variants" to """[{"name":"A"}]""", "fields" to """[{"fieldKey":"f","label":"F"}]""",
                "prices" to """[{"currency":"USD","price":"1"}]""", "providerMeta" to """{"stripe":{}}"""
            )

            val results = Race.run(10) { i ->
                if (i == 4) {
                    service.delete(saved.id)
                } else {
                    service.update(
                        saved.id,
                        input(
                            "name" to "Racer $i", "variants" to """[{"name":"B$i"}]""", "fields" to """[{"fieldKey":"g","label":"G"}]""",
                            "prices" to """[{"currency":"USD","price":"2"}]""", "providerMeta" to """{"stripe":{"a":1}}"""
                        )
                    )
                }
            }

            results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is NotFound, "round $round: ${it.exceptionOrNull()}") }
            assertTrue(results[4].isSuccess, "round $round: ${results[4].exceptionOrNull()}")

            assertNull(w.products.getById(saved.id, pool), "round $round")
            listOf("market_product_variant", "market_product_field", "market_product_price", "market_product_provider_meta")
                .forEach { assertEquals(0L, count(it, "`productId` = ?", saved.id), "round $round: $it") }
        }
    }

    @Test
    fun `an update of a product whose row was deleted underneath is NOT_FOUND and writes nothing`(): Unit = runBlocking {
        val saved = create()
        service.delete(saved.id)

        assertThrows(NotFound::class.java) { runBlocking { service.update(saved.id, input("name" to "Late")) } }
        assertEquals(0L, count("market_product_variant", "`productId` = ?", saved.id))
    }

    @Test
    fun `two concurrent partial updates of different columns both survive`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val saved = create("name" to "Initial $round", "slug" to "partial-race-$round", "priority" to 0, "price" to "5")

            val results = Race.run(20) { i ->
                runCatching {
                    when (i % 3) {
                        0 -> service.update(saved.id, input("name" to "Renamed $i"))
                        1 -> service.update(saved.id, input("priority" to i + 1))
                        else -> service.update(saved.id, input("price" to "${10 + i}"))
                    }
                }
            }

            assertTrue(results.all { it.getOrThrow().isSuccess }, "round $round: $results")
            val p = w.products.getById(saved.id, pool)!!
            assertTrue(p.name.startsWith("Renamed "), "round $round: the rename was reverted: ${p.name}")
            assertTrue(p.priority > 0, "round $round: the priority change was reverted")
            assertTrue(p.price != 500L, "round $round: the price change was reverted")
        }
    }

    // ----- list -------------------------------------------------------------------------------------------------------

    @Test
    fun `the list filters by search, status, kind and category, pages by pageSize and skips deleted rows`(): Unit = runBlocking {
        val cat = w.fixtures.category()
        val child = w.fixtures.product()
        val a = create("name" to "Alpha rank", "categoryId" to cat.id)
        val b = create("name" to "Beta rank", "status" to "INACTIVE")
        val c = service.create(named("kind" to "BUNDLE", "name" to "Gamma bundle", "bundleItems" to """[{"productId":${child.id},"quantity":1}]"""))
        val gone = create("name" to "Delta rank")
        service.delete(gone.id)

        fun ids(search: String? = null, status: String? = null, kind: ProductKind? = null, category: Long? = null, page: Long = 1, size: Int = 50) =
            runBlocking { service.list(page, size, search, status, kind, category).products.map { it.id }.toSet() }

        assertEquals(setOf(a.id, b.id), ids(search = "rank"))
        assertEquals(setOf(b.id), ids(search = "rank", status = "INACTIVE"))
        assertEquals(setOf(c.id), ids(kind = ProductKind.BUNDLE))
        assertEquals(setOf(a.id), ids(category = cat.id))
        assertEquals(2, service.list(1, 2, null, null, null, null).products.size)
        assertEquals(service.list(1, 2, null, null, null, null).count, 4L, "child + three listed, the deleted one excluded")
        assertEquals(1, service.list(2, 1, "rank", null, null, null).products.size)
        assertEquals(0, service.list(3, 1, "rank", null, null, null).products.size)
        assertEquals("Alpha rank", service.list(1, 50, "Alpha", null, null, null).products.single().name)
        assertEquals(cat.name, service.list(1, 50, "Alpha", null, null, null).products.single().categoryName)
    }

    @Test
    fun `the old paged dao signature still lists ten rows and never a deleted one`(): Unit = runBlocking {
        repeat(12) { create() }
        val gone = create()
        service.delete(gone.id)

        assertEquals(10, w.products.getAllPaged(1, null, null, pool).size)
        assertEquals(12L, w.products.count(null, null, pool))
    }

    // ----- counters stay out of the generic update -----------------------------------------------------------------

    @Test
    fun `an update never touches soldCount or deletedAt`(): Unit = runBlocking {
        val id = create().id
        Fixtures.setColumns(pool, "market_product", id, mapOf("soldCount" to 7))

        service.update(id, input("name" to "x", "price" to "3"))

        assertEquals(7, service.get(id).product.soldCount)
        assertNull(service.get(id).product.deletedAt)
    }

    @Test
    fun `an empty update changes nothing but is accepted`(): Unit = runBlocking {
        val id = create("price" to "4").id
        val before = service.get(id).product

        service.update(id, input())

        val after = service.get(id).product
        assertEquals(before.price, after.price)
        assertEquals(before.slug, after.slug)
        assertEquals(before.name, after.name)
    }

    @Test
    fun `soft delete marks only the targeted product`(): Unit = runBlocking {
        val a = create()
        val b = create()
        Fixtures.insertRaw(pool, "market_order_item", mapOf("productId" to a.id))

        assertTrue(service.delete(a.id).soft)

        assertNull(w.products.getById(b.id, pool)!!.deletedAt)
    }

    // ----- bundle rules (MK-051) ------------------------------------------------------------------------------------

    @Test
    fun `a bundle cannot contain a bundle`(): Unit = runBlocking {
        val inner = w.fixtures.bundle(w.fixtures.product() to 1)

        val errors = fieldErrors { create("kind" to "BUNDLE", "bundleItems" to """[{"productId":${inner.id},"quantity":1}]""") }

        assertEquals("NESTED_BUNDLE", errors["bundleItems.0.productId"])
        assertEquals(1L, count("market_bundle_item"))
    }

    @Test
    fun `a bundle cannot contain a physical child, a subscription or a credit pack`(): Unit = runBlocking {
        val physical = w.fixtures.product(columns = mapOf("physical" to true))
        val subscription = w.fixtures.product(columns = mapOf("billingMode" to "SUBSCRIPTION"))
        val pack = w.fixtures.product(columns = mapOf("kind" to "CREDIT_PACK"))

        val errors = fieldErrors {
            create(
                "kind" to "BUNDLE",
                "bundleItems" to """[{"productId":${physical.id},"quantity":1},{"productId":${subscription.id},"quantity":1},{"productId":${pack.id},"quantity":1}]"""
            )
        }

        assertEquals("PHYSICAL_CHILD", errors["bundleItems.0.productId"])
        assertEquals("SUBSCRIPTION_CHILD", errors["bundleItems.1.productId"])
        assertEquals("INVALID_CHILD", errors["bundleItems.2.productId"])
    }

    @Test
    fun `a bundle with a standard child is saved and a bundle stays non physical`(): Unit = runBlocking {
        val child = w.fixtures.product()

        val saved = create("kind" to "BUNDLE", "physical" to true, "bundleItems" to """[{"productId":${child.id},"quantity":2}]""")

        assertFalse(service.get(saved.id).product.physical)
        assertEquals(listOf(child.id to 2), service.get(saved.id).bundleItems.map { it.productId to it.quantity })
    }

    @Test
    fun `a product that is a bundle child cannot become physical, a subscription or a bundle`(): Unit = runBlocking {
        val child = w.fixtures.product()
        create("kind" to "BUNDLE", "bundleItems" to """[{"productId":${child.id},"quantity":1}]""")

        assertEquals("IN_BUNDLE", fieldErrors { service.update(child.id, input("physical" to true, "weightGrams" to 100)) }["physical"])
        assertEquals("NESTED_BUNDLE", fieldErrors { service.update(child.id, input("kind" to "BUNDLE", "bundleItems" to """[{"productId":${w.fixtures.product().id},"quantity":1}]""")) }["kind"])

        service.update(child.id, input("price" to "5"))
        assertEquals(500L, service.get(child.id).product.price)
    }

    @Test
    fun `a deleted bundle no longer pins its child`(): Unit = runBlocking {
        val child = w.fixtures.product()
        val bundle = create("kind" to "BUNDLE", "bundleItems" to """[{"productId":${child.id},"quantity":1}]""")
        Fixtures.insertRaw(pool, "market_order_item", mapOf("productId" to bundle.id))
        assertTrue(service.delete(bundle.id).soft)

        service.update(child.id, input("physical" to true, "weightGrams" to 100))

        assertTrue(service.get(child.id).product.physical)
    }

    // ----- clone (MK-051) -------------------------------------------------------------------------------------------

    @Test
    fun `clone copies variants fields prices bundle rows and provider meta with new ids`(): Unit = runBlocking {
        val child = w.fixtures.product()
        val axes = """[{"key":"size","label":"Size","values":[{"key":"s","label":"S"},{"key":"l","label":"L"}]}]"""
        val saved = service.create(
            named(
                "stock" to 9, "sku" to "ORIG", "featured" to true, "compareAtPrice" to "20", "limitPerPlayer" to 2,
                "variantOptions" to axes,
                "variants" to """[{"name":"S","sku":"V-S","optionValues":{"size":"s"},"price":"9","stock":4,"prices":[{"currency":"USD","price":"10"}]},
                                  {"name":"L","optionValues":{"size":"l"},"stock":null}]""",
                "fields" to """[{"fieldKey":"nick","label":"Nick","required":true}]""",
                "prices" to """[{"currency":"TRY","price":"300"}]""",
                "providerMeta" to """{"stripe":{"packageId":"123"}}""",
                "actions" to """[{"type":"CREDIT","value":2.5},{"type":"CREDIT","value":1}]"""
            )
        )
        val bundle = create("kind" to "BUNDLE", "bundleItems" to """[{"productId":${child.id},"quantity":3}]""")

        val copy = service.clone(saved.id, " (Copy)")
        val original = service.get(saved.id)
        val cloned = service.get(copy.id)

        assertTrue(copy.id != saved.id)
        assertEquals("${original.product.name} (Copy)", cloned.product.name)
        assertEquals("${original.product.slug}-copy", cloned.product.slug)
        assertEquals(MarketStatus.INACTIVE, cloned.product.status)
        assertEquals(original.product.price, cloned.product.price)
        assertEquals(9, cloned.product.stock)
        assertEquals("ORIG", cloned.product.sku)
        assertTrue(cloned.product.featured)
        assertEquals(2, cloned.product.limitPerPlayer)
        assertEquals(0, cloned.product.soldCount)
        assertTrue(cloned.product.hasVariants)
        assertEquals(original.product.variantOptions, cloned.product.variantOptions)

        assertEquals(2, cloned.variants.size)
        assertTrue(cloned.variants.none { v -> original.variants.any { it.id == v.id } })
        assertEquals(listOf("S", "L"), cloned.variants.map { it.name })
        assertEquals(listOf(4, null), cloned.variants.map { it.stock })
        assertEquals(listOf(900L, null), cloned.variants.map { it.price })

        assertEquals(original.fields.map { it.fieldKey to it.required }, cloned.fields.map { it.fieldKey to it.required })
        assertTrue(cloned.fields.none { f -> original.fields.any { it.id == f.id } })

        val variantIds = cloned.variants.map { it.id }.toSet()
        assertEquals(2, cloned.prices.size)
        assertTrue(cloned.prices.all { it.productId == copy.id && (it.variantId == 0L || it.variantId in variantIds) })
        assertEquals(setOf("TRY" to 0L, "USD" to cloned.variants.first().id), cloned.prices.map { it.currency to it.variantId }.toSet())
        assertEquals(mapOf("stripe" to """{"packageId":"123"}"""), cloned.providerMeta.mapValues { it.value.filterNot { c -> c.isWhitespace() } })

        // the original keeps everything
        assertEquals(2, original.variants.size)
        assertEquals(2, original.prices.size)

        val clonedActions = io.vertx.core.json.JsonArray(cloned.product.actions)
        assertEquals(listOf("a1", "a2"), clonedActions.map { (it as JsonObject).getString("id") })

        val bundleCopy = service.clone(bundle.id, " (Copy)")
        assertEquals(listOf(child.id to 3), service.get(bundleCopy.id).bundleItems.map { it.productId to it.quantity })
        assertEquals(bundle.id, service.get(bundle.id).bundleItems.first().bundleProductId)
        assertEquals(ProductKind.BUNDLE, service.get(bundleCopy.id).product.kind)
    }

    @Test
    fun `clone numbers the slug and copies the image through the callback`(): Unit = runBlocking {
        val saved = create()
        w.products.update(w.products.getById(saved.id, pool)!!.let { p -> com.panomc.plugins.market.db.model.MarketProduct(
            id = p.id, slug = p.slug, name = p.name, price = p.price, imageFileName = "img.png", updatedAt = 1) }, pool)

        val first = service.clone(saved.id, " (Kopya)") { "copy-of-$it" }
        val second = service.clone(saved.id, " (Kopya)")
        val third = service.clone(saved.id, " (Kopya)")

        assertEquals("${service.get(saved.id).product.slug}-copy", first.slug)
        assertEquals("${service.get(saved.id).product.slug}-copy-2", second.slug)
        assertEquals("${service.get(saved.id).product.slug}-copy-3", third.slug)
        assertEquals("copy-of-img.png", service.get(first.id).product.imageFileName)
        assertNull(service.get(second.id).product.imageFileName)
    }

    @Test
    fun `clone of a missing or deleted product is not found and cloning twice at once yields distinct slugs`(): Unit = runBlocking {
        assertThrows(NotFound::class.java) { runBlocking { service.clone(99999, " (Copy)") } }

        val saved = create()
        val slugs = Race.run(6) { service.clone(saved.id, " (Copy)").slug }.mapNotNull { it.getOrNull() }

        assertEquals(slugs.size, slugs.toSet().size)

        val gone = create()
        Fixtures.insertRaw(pool, "market_order_item", mapOf("productId" to gone.id))
        service.delete(gone.id)
        assertThrows(NotFound::class.java) { runBlocking { service.clone(gone.id, " (Copy)") } }
    }

    // ----- comparisons (MK-051) -------------------------------------------------------------------------------------

    private fun comparison(vararg pairs: Pair<String, Any?>) = JsonObject(mapOf("name" to "Compare", *pairs))

    @Test
    fun `comparison is saved and limited to 12 products and 50 features`(): Unit = runBlocking {
        val ids = (1..13).map { w.fixtures.product().id }

        val id = service.saveComparison(null, comparison("selectedProducts" to JsonArray(ids.take(12)), "features" to JsonArray((1..50).map { JsonObject().put("id", "f$it") }), "cellValues" to JsonObject().put("a", "yes")))
        assertEquals(12, JsonArray(w.comparisons.getById(id, pool)!!.productIds).size())

        assertEquals("TOO_MANY", comparisonErrors { service.saveComparison(null, comparison("selectedProducts" to JsonArray(ids))) }["selectedProducts"])
        assertEquals("TOO_MANY", comparisonErrors { service.saveComparison(null, comparison("features" to JsonArray((1..51).map { JsonObject().put("id", "f$it") }))) }["features"])
        assertEquals(1L, count("market_comparison"))
    }

    @Test
    fun `comparison shape errors name their path and nothing is stored`(): Unit = runBlocking {
        val a = w.fixtures.product()

        val errors = comparisonErrors {
            service.saveComparison(
                null,
                JsonObject().put("name", " ").put("priority", "x").put("selectedProducts", JsonArray().add(a.id).add(a.id).add(99999).add("z"))
                    .put("features", JsonArray().add("str").add(JsonObject().put("id", "dup")).add(JsonObject().put("id", "dup")))
                    .put("cellValues", JsonObject().put("k", 5).put("k2", "x".repeat(501)))
            )
        }

        assertEquals("REQUIRED", errors["name"])
        assertEquals("INVALID", errors["priority"])
        assertEquals("DUPLICATE", errors["selectedProducts.1"])
        assertEquals("INVALID", errors["selectedProducts.3"])
        assertEquals("INVALID", errors["features.0"])
        assertEquals("DUPLICATE", errors["features.2.id"])
        assertEquals("INVALID", errors["cellValues.k"])
        assertEquals("INVALID", errors["cellValues.k2"])
        assertEquals(0L, count("market_comparison"))

        assertEquals("NOT_FOUND", comparisonErrors { service.saveComparison(null, comparison("selectedProducts" to JsonArray().add(99999))) }["selectedProducts.0"])
        assertThrows(NotFound::class.java) { runBlocking { service.saveComparison(424242, comparison()) } }
    }

    @Test
    fun `comparison update replaces the row`(): Unit = runBlocking {
        val a = w.fixtures.product()
        val id = service.saveComparison(null, comparison("selectedProducts" to JsonArray().add(a.id)))

        service.saveComparison(id, comparison("name" to "Renamed", "status" to "INACTIVE", "priority" to 4))

        val row = w.comparisons.getById(id, pool)!!
        assertEquals("Renamed", row.name)
        assertEquals(MarketStatus.INACTIVE, row.status)
        assertEquals(4, row.priority)
        assertEquals("[]", row.productIds)
    }

    private suspend fun comparisonErrors(block: suspend () -> Unit): Map<String, String> {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue(e is com.panomc.platform.error.BadRequest, "expected BAD_REQUEST, got $e")
        return ErrorBodies.details((e as com.panomc.platform.error.BadRequest)).getJsonObject("fieldErrors").map.mapValues { it.value as String }
    }
}
