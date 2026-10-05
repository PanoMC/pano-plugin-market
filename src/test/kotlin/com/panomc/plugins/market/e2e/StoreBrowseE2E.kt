package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eTestBase
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Browse the store over HTTP (17 section 9.1): B-01 to B-05. Every scenario ends with the drain and the invariants (base class). */
class StoreBrowseE2E : E2eTestBase() {
    override val tag = "brws"

    private fun uniq(): String = System.nanoTime().toString(36).takeLast(9)

    /** A category of its own (panel API); [status] `ACTIVE` or `INACTIVE`. */
    private fun category(status: String): Long {
        val answer = admin.multipart("POST", "/api/panel/market/categories", mapOf("name" to "E2E cat ${uniq()}", "status" to status))
        assertEquals(200, answer.status, "category create: ${answer.error}")
        return answer.obj().getLong("id")
    }

    private fun slugs(array: JsonArray): List<String> = array.map { (it as JsonObject).getString("slug") }

    @Test
    fun `B-01 store lists the catalogue`() {
        val needle = "B01x" + uniq()
        val vip = catalog.fresh("VIP", "name" to needle)
        val anonymous = visitor("anon").get("/api/market/store")

        assertEquals(200, anonymous.status)
        assertEquals("public, max-age=30", anonymous.header("Cache-Control"), "an anonymous answer may be cached for 30 seconds")

        val body = anonymous.obj()
        val products = body.getJsonArray("products")
        assertTrue(products.size() > 0, "the store lists products")
        // the wire contract of the listing: cards carry no description, the paging counters live on the answer, not on a card
        products.map { it as JsonObject }.forEach { card ->
            assertFalse(card.containsKey("description"), "a ProductCard has no description")
            assertFalse(card.containsKey("productCount"), "a ProductCard has no productCount")
            assertFalse(card.containsKey("totalPage"), "a ProductCard has no totalPage")
            assertTrue(card.containsKey("id") && card.containsKey("slug") && card.containsKey("price") && card.containsKey("inStock"), "a ProductCard has its basic fields")
        }
        assertTrue(body.containsKey("productCount") && body.containsKey("totalPage"), "the answer carries the paging counters")
        assertTrue(body.containsKey("settings") && body.containsKey("categories"), "the answer carries the store settings and the category tree")

        // the product made for this scenario is on the storefront (the first page holds the default page size; the listing finds it by slug)
        val found = visitor("anon").get("/api/market/store/products?search=$needle&pageSize=60").ok().obj().getJsonArray("products")
        assertTrue(slugs(found).contains(vip.slug), "a fresh ACTIVE product is listed")

        // a logged-in caller's answer (owned flags) is never cached
        val loggedIn = buyer().client.get("/api/market/store")
        assertEquals(200, loggedIn.status)
        assertEquals("private, no-store", loggedIn.header("Cache-Control"))
    }

    @Test
    fun `B-02 product detail by slug`() {
        val crate = catalog.fresh("VAR", "description" to "<p>A <strong>crate</strong></p>")
        val client = visitor("anon")

        val detail = client.get("/api/market/products/${crate.slug}").ok().obj().getJsonObject("product")
        assertEquals(crate.slug, detail.getString("slug"))
        assertEquals(2, detail.getJsonArray("variants").size(), "the two variants S and L")
        assertEquals(1, detail.getJsonArray("fields").size(), "the custom field note")
        assertEquals("note", detail.getJsonArray("fields").getJsonObject(0).getString("fieldKey"))
        assertTrue(detail.getString("description").contains("<strong>crate</strong>"), "the description is returned sanitised, formatting kept")
        assertFalse(detail.containsKey("actions"), "server-only fields are never exposed")

        val unknown = client.get("/api/market/products/no-such-product-${uniq()}")
        assertEquals(404, unknown.status)
        assertEquals("NOT_FOUND", unknown.error)

        val archived = catalog.fresh("VIP")
        assertEquals(200, client.get("/api/market/products/${archived.slug}").status, "active before the archive")
        admin.multipart("PUT", "/api/panel/market/products/${archived.id}", mapOf("status" to "ARCHIVED")).ok()
        val gone = client.get("/api/market/products/${archived.slug}")
        assertEquals(404, gone.status, "an ARCHIVED product is not on the storefront")
        assertEquals("NOT_FOUND", gone.error)

        val inactiveCategory = category("INACTIVE")
        val hidden = catalog.fresh("VIP", "categoryId" to inactiveCategory.toString())
        val hiddenAnswer = client.get("/api/market/products/${hidden.slug}")
        assertEquals(404, hiddenAnswer.status, "a product of an inactive category is not on the storefront")
        assertEquals("NOT_FOUND", hiddenAnswer.error)
    }

    @Test
    fun `B-03 paging and filters`() {
        val category = category("ACTIVE")
        // seven products of one category with distinct prices, created out of order
        val prices = listOf(5, 2, 7, 1, 6, 3, 4)
        val made = prices.map { p -> p to catalog.fresh("VIP", "categoryId" to category.toString(), "price" to "$p.00", "creditPrice" to "$p.00") }.toMap()
        val ascending = prices.sorted().map { made.getValue(it).slug }
        val client = visitor("anon")
        val base = "/api/market/store/products?category=$category&sort=price-asc&pageSize=5"

        val first = client.get("$base&page=1").ok().obj()
        assertEquals(7L, first.getLong("productCount"))
        assertEquals(2L, first.getLong("totalPage"))
        assertEquals(ascending.take(5), slugs(first.getJsonArray("products")), "page 1: the five cheapest, cheapest first")

        val second = client.get("$base&page=2").ok().obj()
        assertEquals(ascending.drop(5), slugs(second.getJsonArray("products")), "page 2: the two dearest")

        val descending = client.get("/api/market/store/products?category=$category&sort=price-desc&pageSize=5&page=1").ok().obj()
        assertEquals(ascending.reversed().take(5), slugs(descending.getJsonArray("products")))

        val beyond = client.get("$base&page=3")
        assertEquals(404, beyond.status)
        assertEquals("PAGE_NOT_FOUND", beyond.error)

        assertEquals(400, client.get("/api/market/store/products?pageSize=61").status, "pageSize above 60 is refused")
        assertEquals(400, client.get("/api/market/store/products?sort=cheapest").status, "an unknown sort is refused")
    }

    @Test
    fun `B-04 stored XSS is neutralised`() {
        val dirty = "<p>safe <strong>bold</strong></p><script>alert(1)</script><img src=x onerror=alert(2)><a href=\"javascript:alert(3)\">click</a>"
        val product = catalog.fresh("VIP", "description" to dirty)
        val html = visitor("anon").get("/api/market/products/${product.slug}").ok().obj().getJsonObject("product").getString("description")

        assertFalse(html.contains("<script", ignoreCase = true), "no script tag")
        assertFalse(html.contains("onerror", ignoreCase = true), "no event handler attribute")
        assertFalse(html.contains("javascript:", ignoreCase = true), "no javascript: URL")
        assertTrue(html.contains("<p>"), "paragraphs are kept")
        assertTrue(html.contains("<strong>bold</strong>"), "strong is kept")
    }

    @Test
    fun `B-05 store disabled`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val publicId = publicIdOf(checkout(buyer.client, cart(line(vip.id))).ok())
        val anonymous = visitor("anon")

        session.withSettings(JsonObject().put("storeEnabled", false)) {
            fun assertDisabled(what: String, status: Int, error: String?) {
                assertEquals(503, status, "$what answers 503")
                assertEquals("STORE_DISABLED", error, "$what answers STORE_DISABLED")
            }

            anonymous.get("/api/market/store").let { assertDisabled("store", it.status, it.error) }
            anonymous.get("/api/market/products/${vip.slug}").let { assertDisabled("product detail", it.status, it.error) }
            anonymous.post("/api/market/checkout/quote", JsonObject().put("items", JsonArray().add(line(vip.id)))).let { assertDisabled("quote", it.status, it.error) }
            checkout(buyer.client, cart(line(vip.id))).let { assertDisabled("checkout", it.status, it.error) }
            buyer.client.get("/api/market/me/cart").let { assertDisabled("buyer cart", it.status, it.error) }

            // the panel keeps working
            assertEquals(200, admin.get("/api/panel/market/products").status, "panel products")
            assertEquals(200, admin.get("/api/panel/market/health", log = false).status, "panel health")

            // an inbound webhook is still processed: the payment of an order made before the switch-off completes it
            val reference = payViaFake(publicId)
            awaitOrder(publicId, "COMPLETED")
            assertEquals("SUCCEEDED", attemptStatus(reference))
        }

        assertEquals(200, anonymous.get("/api/market/store").status, "the store is back when the setting is restored")
    }
}
