package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eSession
import com.panomc.plugins.market.e2e.support.E2eTestBase
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.util.MarketPaths

/** The server cart and the quote over HTTP (17 section 9.1): C-01 to C-05. Every scenario ends with the drain and the invariants (base class). */
class CartE2E : E2eTestBase() {
    override val tag = "cart"

    private fun items(answer: E2eResponse): JsonArray = answer.ok().obj().getJsonObject("cart").getJsonArray("items")

    private fun cartRows(userId: Long): Long =
        db.count("market_cart_item", "`cartId` IN (SELECT `id` FROM `pano_market_cart` WHERE `userId` = ?)", userId)

    private fun setStock(productId: Long, value: Int) {
        val answer = admin.post("${MarketPaths.PANEL_ROOT}/products/$productId/stock", JsonObject().put("mode", "SET").put("value", value)).ok()
        assertEquals(value.toLong(), answer.obj().getLong("stock"))
    }

    private fun quoteLine(quote: JsonObject, productId: Long): JsonObject =
        quote.getJsonArray("lines").map { it as JsonObject }.first { it.getLong("productId") == productId }

    private fun codes(array: JsonArray?): List<String> = array?.map { (it as JsonObject).getString("code") } ?: emptyList()

    @Test
    fun `C-01 server cart CRUD`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val http = buyer.client

        val first = http.post("${MarketPaths.SITE_ROOT}/me/cart/items", line(vip.id)).ok()
        assertNotNull(first.obj().getJsonObject("quote"), "a write carries the quote")
        val second = http.post("${MarketPaths.SITE_ROOT}/me/cart/items", line(vip.id)).ok()
        assertNotNull(second.obj().getJsonObject("quote"))

        val merged = items(second)
        assertEquals(1, merged.size(), "the same line merges into one row")
        assertEquals(2, merged.getJsonObject(0).getInteger("quantity"))
        assertEquals(1L, cartRows(buyer.userId), "one cart row in the database")
        val itemId = merged.getJsonObject(0).getLong("id")

        val updated = http.put("${MarketPaths.SITE_ROOT}/me/cart/items/$itemId", JsonObject().put("quantity", 5)).ok()
        assertEquals(5, items(updated).getJsonObject(0).getInteger("quantity"))
        assertNotNull(updated.obj().getJsonObject("quote"))
        assertEquals(5L, db.long("SELECT `quantity` FROM `pano_market_cart_item` WHERE `id` = ?", itemId))

        val read = http.get("${MarketPaths.SITE_ROOT}/me/cart").ok()
        assertEquals(1, items(read).size())
        assertEquals(vip.id, quoteLine(read.obj().getJsonObject("quote"), vip.id).getLong("productId"))
        assertFalse(quoteLine(read.obj().getJsonObject("quote"), vip.id).getString("name").isNullOrBlank(), "the cart quote names its rows")

        val removed = http.delete("${MarketPaths.SITE_ROOT}/me/cart/items/$itemId").ok()
        assertEquals(0, items(removed).size())
        assertNotNull(removed.obj().getJsonObject("quote"))
        assertEquals(0L, cartRows(buyer.userId))

        // a line of another buyer's cart is not reachable
        val other = buyer()
        val otherItem = items(other.client.post("${MarketPaths.SITE_ROOT}/me/cart/items", line(vip.id))).getJsonObject(0).getLong("id")
        assertEquals(404, http.put("${MarketPaths.SITE_ROOT}/me/cart/items/$otherItem", JsonObject().put("quantity", 3)).status)
        assertEquals(1, items(other.client.get("${MarketPaths.SITE_ROOT}/me/cart")).getJsonObject(0).getInteger("quantity"), "the other cart is untouched")
    }

    @Test
    fun `C-02 browser cart merge after login`() {
        val vip = catalog.fresh("VIP")
        val free = catalog.fresh("FREE")
        val unknown = 987_654_321L
        val buyer = buyer()

        val merged = buyer.client.post(
            "${MarketPaths.SITE_ROOT}/me/cart/merge",
            JsonObject().put("items", JsonArray().add(line(vip.id, 2)).add(line(free.id)).add(line(unknown)))
        ).ok()

        assertEquals(2, items(merged).size(), "the two valid lines are stored")
        assertEquals(2L, cartRows(buyer.userId))
        assertEquals(setOf(vip.id, free.id), items(merged).map { (it as JsonObject).getLong("productId") }.toSet())
        assertTrue(codes(merged.obj().getJsonObject("quote").getJsonArray("messages")).contains("PRODUCT_UNAVAILABLE"), "the dropped line is reported")

        // a repeated merge changes nothing (max of server and browser)
        val again = buyer.client.post("${MarketPaths.SITE_ROOT}/me/cart/merge", JsonObject().put("items", JsonArray().add(line(vip.id, 2)))).ok()
        assertEquals(2, items(again).size())
        assertEquals(2, items(again).map { it as JsonObject }.first { it.getLong("productId") == vip.id }.getInteger("quantity"))
    }

    @Test
    fun `C-03 quote never fails for business reasons`() {
        val last = catalog.fresh("LAST")
        setStock(last.id, 0)
        val crate = catalog.fresh("VAR")
        val http = visitor("quoter")

        val answer = http.post(
            "${MarketPaths.SITE_ROOT}/checkout/quote",
            JsonObject().put("items", JsonArray().add(line(last.id)).add(line(crate.id))).put("couponCode", "NO-SUCH-CODE-${System.nanoTime()}")
        )
        assertEquals(200, answer.status, "a quote is a 200 whatever is wrong with the cart")
        val quote = answer.obj().getJsonObject("quote")

        assertEquals(false, quote.getBoolean("canCheckout"))
        assertTrue(quoteLine(quote, last.id).getJsonArray("errors").contains("OUT_OF_STOCK"), "the sold out line says OUT_OF_STOCK")
        assertTrue(quoteLine(quote, crate.id).getJsonArray("errors").contains("VARIANT_REQUIRED"), "the line without a variant says VARIANT_REQUIRED")
        assertEquals(false, quote.getJsonObject("coupon").getBoolean("valid"), "the unknown coupon is reported as invalid")
    }

    @Test
    fun `C-04 quantity is re-clamped when stock drops`() {
        val last = catalog.fresh("LAST")
        val buyer = buyer()
        val http = buyer.client

        val added = http.post("${MarketPaths.SITE_ROOT}/me/cart/items", line(last.id)).ok()
        val itemId = items(added).getJsonObject(0).getLong("id")

        // stock drops to zero: the server-cart quote (no items in the body) says OUT_OF_STOCK
        setStock(last.id, 0)
        val soldOut = http.post("${MarketPaths.SITE_ROOT}/checkout/quote", JsonObject()).ok().obj().getJsonObject("quote")
        assertTrue(quoteLine(soldOut, last.id).getJsonArray("errors").contains("OUT_OF_STOCK"))
        assertEquals(false, soldOut.getBoolean("canCheckout"))
        assertTrue(quoteLine(http.get("${MarketPaths.SITE_ROOT}/me/cart").ok().obj().getJsonObject("quote"), last.id).getJsonArray("errors").contains("OUT_OF_STOCK"), "the cart view agrees")

        // stock is one again while the cart asks for three: the line is blocked and says how many the buyer can still take
        // (deviation from 17 section 9.1, recorded in evidence/E2E-01.md: the code answers MAX_QUANTITY for a stock shortfall; QUANTITY_REDUCED is the
        // clamp of a subscription line, asserted below)
        setStock(last.id, 1)
        http.put("${MarketPaths.SITE_ROOT}/me/cart/items/$itemId", JsonObject().put("quantity", 3)).ok()
        val short = http.post("${MarketPaths.SITE_ROOT}/checkout/quote", JsonObject()).ok().obj().getJsonObject("quote")
        val line = quoteLine(short, last.id)

        assertEquals(1, line.getInteger("maxQuantity"), "the line knows the stock")
        assertTrue(line.getJsonArray("errors").contains("MAX_QUANTITY"), "three of one are too many")
        assertEquals(false, short.getBoolean("canCheckout"))
        assertTrue(quoteLine(http.get("${MarketPaths.SITE_ROOT}/me/cart").ok().obj().getJsonObject("quote"), last.id).getJsonArray("errors").contains("MAX_QUANTITY"), "the cart view agrees")

        // the buyer takes the offered maximum and the cart is clean again
        http.put("${MarketPaths.SITE_ROOT}/me/cart/items/$itemId", JsonObject().put("quantity", 1)).ok()
        val clean = quoteLine(http.post("${MarketPaths.SITE_ROOT}/checkout/quote", JsonObject()).ok().obj().getJsonObject("quote"), last.id)
        assertEquals(0, clean.getJsonArray("errors").size())
    }

    @Test
    fun `C-04b a subscription line is clamped to one with QUANTITY_REDUCED`() {
        val slug = "e2e-sub-${System.nanoTime().toString(36)}"
        val id = catalog.product("SUB-C04", slug, "Sub $slug", price = "6.00", extra = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to "1"))
        val quote = buyer().client.post("${MarketPaths.SITE_ROOT}/checkout/quote", JsonObject().put("items", JsonArray().add(line(id, 3)))).ok().obj().getJsonObject("quote")
        val line = quoteLine(quote, id)

        assertEquals(1, line.getInteger("quantity"), "a subscription is always one")
        assertEquals(1, line.getInteger("maxQuantity"))
        assertTrue(codes(quote.getJsonArray("messages")).contains("QUANTITY_REDUCED"), "the clamp is reported")
        assertEquals(line.getString("lineKey"), quote.getJsonArray("messages").map { it as JsonObject }.first { it.getString("code") == "QUANTITY_REDUCED" }.getString("lineKey"))
    }

    @Test
    fun `C-05 cart mutation needs CSRF`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val body = line(vip.id)

        // the session cookie alone is an ambient credential: no X-CSRF-Token, no write
        val refused = buyer.client.request("POST", "${MarketPaths.SITE_ROOT}/me/cart/items", body, csrf = false)
        assertEquals(403, refused.status)
        assertEquals("INVALID_CSRF_TOKEN", refused.error)
        assertEquals(0L, cartRows(buyer.userId), "nothing was written")

        // a bearer token is not ambient: no cookie, no CSRF header needed
        db.verifyEmail(buyer.userId)
        val login = E2eClient(baseUrl, "bearer-login")
        val answer = login.login(buyer.username, E2eSession.PASSWORD)
        assertEquals(200, answer.status)
        val jwt = answer.headers.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }
            .map { it.substringBefore(';') }.firstOrNull { it.substringBefore('=').contains("auth_token") }?.substringAfter('=')
        assertNotNull(jwt, "the login answer sets the auth token cookie")

        val api = E2eClient(baseUrl, "bearer").also { it.bearer = jwt }
        val accepted = api.request("POST", "${MarketPaths.SITE_ROOT}/me/cart/items", body, csrf = false, cookiesOn = false)
        assertEquals(200, accepted.status, "bearer without cookie: ${accepted.error}")
        assertEquals(1, items(accepted).size())
        assertFalse(cartRows(buyer.userId) == 0L)
    }
}
