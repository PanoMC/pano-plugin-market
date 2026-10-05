package com.panomc.plugins.market.service

import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.core.cart.CartMessage
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.error.InvalidCart
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `CartService` on a real MariaDB (MK-070): get-or-create, identical lines merge, structural validation, the 50 line
 * limit, advice for stock and `maxQuantityPerOrder`, line update with key change, remove, clear, replace, merge on
 * login, the clear that belongs to a checkout, and the races (R-24 twin: 10 concurrent identical POSTs = one row).
 */
class CartServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val service by lazy { CartService(w.db, w.clock, w.carts, w.cartItems, w.products, w.variants, w.fields) }
    private var userSeq = 1000L

    private fun newUser(): Long = ++userSeq

    private fun line(product: MarketProduct, quantity: Int = 1, variantId: Long = 0, vararg values: Pair<String, Any>, target: Long? = null) =
        CartLine(product.id, variantId, quantity, mapOf(*values), target)

    private suspend fun lineErrors(block: suspend () -> Unit): Map<String, List<String>> {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue(e is InvalidCart, "expected INVALID_CART, got $e")
        val body = JsonObject((e as InvalidCart).encode(emptyMap()))
        assertEquals("INVALID_CART", body.getString("error"))
        return body.getJsonObject("lineErrors").map.mapValues { (_, v) -> (v as List<*>).map { it.toString() } }
    }

    private suspend fun rows(userId: Long) = w.cartItems.getByCartId(w.carts.getByUserId(userId, pool)!!.id, pool)

    // ----- get -------------------------------------------------------------------------------------------------------

    @Test
    fun `get creates an empty cart once and stores the currency`(): Unit = runBlocking {
        val user = newUser()

        val first = service.get(user)
        val second = service.get(user)

        assertEquals(first.cart.id, second.cart.id)
        assertTrue(first.lines.isEmpty())
        assertFalse(first.canCheckout)
        assertNull(first.cart.currency)
        assertEquals(1, count("market_cart", "userId = ?", user))

        assertEquals("USD", service.get(user, "USD").cart.currency)
        assertEquals("USD", service.get(user).cart.currency)
        assertThrows(BadRequest::class.java) { runBlocking { service.get(user, "ZZZ") } }
        assertEquals("USD", service.get(user).cart.currency)
    }

    @Test
    fun `ten concurrent first reads create exactly one cart`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val user = newUser()
            val results = Race.run(10) { runCatching { service.get(user).cart.id } }

            assertTrue(results.all { it.getOrThrow().isSuccess }, results.toString())
            assertEquals(1, results.map { it.getOrThrow().getOrThrow() }.toSet().size)
            assertEquals(1, count("market_cart", "userId = ?", user))
        }
    }

    // ----- add -------------------------------------------------------------------------------------------------------

    @Test
    fun `an added line is stored with its line key`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        w.fixtures.field(product, "nick")

        val view = service.addItem(user, line(product, 2, 0, "nick" to " Steve "))

        val row = view.lines.single().item
        assertEquals(2, row.quantity)
        assertEquals(CartLineKey.of(product.id, 0, mapOf("nick" to "Steve"), null), row.lineKey)
        assertEquals(mapOf<String, Any?>("nick" to "Steve"), view.lines.single().fieldValues)
        assertTrue(view.canCheckout)
        assertNull(view.lines.single().item.targetServerId)
    }

    @Test
    fun `identical lines merge by summing the quantity`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        w.fixtures.field(product, "a")
        w.fixtures.field(product, "b")

        service.addItem(user, CartLine(product.id, 0, 2, linkedMapOf("a" to "1", "b" to "2")))
        val view = service.addItem(user, CartLine(product.id, 0, 3, linkedMapOf("b" to "2", "a" to "1")))

        assertEquals(1, view.lines.size)
        assertEquals(5, view.lines.single().item.quantity)
    }

    @Test
    fun `lines that differ in variant, field or server stay apart`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        val v1 = w.fixtures.variant(product)
        val v2 = w.fixtures.variant(product)
        w.fixtures.field(product, "nick")

        service.addItem(user, line(product, 1))
        service.addItem(user, line(product, 1, v1.id))
        service.addItem(user, line(product, 1, v2.id))
        service.addItem(user, line(product, 1, 0, "nick" to "a"))
        service.addItem(user, line(product, 1, 0, "nick" to "b"))
        val view = service.addItem(user, line(product, 1, 0, target = 9))

        assertEquals(6, view.lines.size)
        assertEquals(6, view.lines.map { it.item.lineKey }.toSet().size)
    }

    @Test
    fun `merged quantity is capped at 999`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()

        service.addItem(user, line(product, 600))
        val view = service.addItem(user, line(product, 600))

        assertEquals(999, view.lines.single().item.quantity)
    }

    @Test
    fun `a structurally bad line is refused as INVALID_CART keyed by its line key and nothing is stored`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        val other = w.fixtures.product()
        val foreignVariant = w.fixtures.variant(other)
        val deleted = w.fixtures.product()
        Fixtures.setColumns(pool, "market_product", deleted.id, mapOf("deletedAt" to 5L))
        w.fixtures.field(product, "nick")

        val unknown = CartLine(999_999, 0, 1)
        assertEquals(mapOf(unknown.lineKey to listOf("PRODUCT_UNAVAILABLE")), lineErrors { service.addItem(user, unknown) })

        val gone = line(deleted)
        assertEquals(mapOf(gone.lineKey to listOf("PRODUCT_UNAVAILABLE")), lineErrors { service.addItem(user, gone) })

        val foreign = line(product, 1, foreignVariant.id)
        assertEquals(mapOf(foreign.lineKey to listOf("VARIANT_UNAVAILABLE")), lineErrors { service.addItem(user, foreign) })

        val noVariants = line(product, 1, 12345)
        assertEquals(mapOf(noVariants.lineKey to listOf("VARIANT_UNAVAILABLE")), lineErrors { service.addItem(user, noVariants) })

        val badField = line(product, 1, 0, "other" to "x")
        assertEquals(mapOf(badField.lineKey to listOf("FIELD_INVALID")), lineErrors { service.addItem(user, badField) })

        val badServer = line(product, 1, 0, target = -4)
        assertEquals(mapOf(badServer.lineKey to listOf("SERVER_UNAVAILABLE")), lineErrors { service.addItem(user, badServer) })

        assertEquals(0, count("market_cart_item"))
    }

    @Test
    fun `a soft-deleted variant is refused`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        val variant = w.fixtures.variant(product)
        assertTrue(w.variants.markDeleted(variant.id, 1L, pool))

        assertEquals(listOf("VARIANT_UNAVAILABLE"), lineErrors { service.addItem(user, line(product, 1, variant.id)) }.values.single())
    }

    @Test
    fun `the 51st distinct line is refused, an identical one still merges`(): Unit = runBlocking {
        val user = newUser()
        val products = (1..CartLimits.MAX_LINES).map { w.fixtures.product() }

        products.forEach { service.addItem(user, line(it)) }

        val extra = w.fixtures.product()
        assertEquals(listOf("CART_FULL"), lineErrors { service.addItem(user, line(extra)) }.values.single())
        assertEquals(50L, count("market_cart_item"))

        val view = service.addItem(user, line(products.first(), 4))
        assertEquals(50, view.lines.size)
        assertEquals(5, view.lines.first().item.quantity)
    }

    // ----- advice (stock and maxQuantityPerOrder are reported, not enforced on write) -----------------------------------

    @Test
    fun `stock and the per order limit are reported as advice and never change the stored quantity`(): Unit = runBlocking {
        val user = newUser()
        val limited = w.fixtures.product(stock = 2)
        val soldOut = w.fixtures.product(stock = 0)
        val capped = w.fixtures.product(columns = mapOf("maxQuantityPerOrder" to 3))
        val both = w.fixtures.product(stock = 10, columns = mapOf("maxQuantityPerOrder" to 4))
        val free = w.fixtures.product()

        service.addItem(user, line(limited, 5))
        service.addItem(user, line(soldOut, 1))
        service.addItem(user, line(capped, 8))
        service.addItem(user, line(both, 4))
        val view = service.addItem(user, line(free, 999))

        fun advice(p: MarketProduct) = view.lines.single { it.item.productId == p.id }

        assertEquals(2, advice(limited).maxQuantity)
        assertEquals(listOf("MAX_QUANTITY"), advice(limited).errors)
        assertEquals(5, advice(limited).item.quantity)

        assertEquals(0, advice(soldOut).maxQuantity)
        assertEquals(listOf("OUT_OF_STOCK"), advice(soldOut).errors)

        assertEquals(3, advice(capped).maxQuantity)
        assertEquals(listOf("MAX_QUANTITY"), advice(capped).errors)
        assertEquals(8, advice(capped).item.quantity)

        assertEquals(4, advice(both).maxQuantity)
        assertEquals(emptyList<String>(), advice(both).errors)

        assertEquals(999, advice(free).maxQuantity)
        assertEquals(emptyList<String>(), advice(free).errors)
        assertFalse(view.canCheckout)
    }

    @Test
    fun `variant stock is the stock of a variant line`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product(stock = 100)
        val scarce = w.fixtures.variant(product, stock = 1)
        val plenty = w.fixtures.variant(product, stock = null)

        service.addItem(user, line(product, 3, scarce.id))
        val view = service.addItem(user, line(product, 3, plenty.id))

        assertEquals(1, view.lines.single { it.item.variantId == scarce.id }.maxQuantity)
        assertEquals(listOf("MAX_QUANTITY"), view.lines.single { it.item.variantId == scarce.id }.errors)
        assertEquals(999, view.lines.single { it.item.variantId == plenty.id }.maxQuantity)
    }

    @Test
    fun `a line whose product or variant vanished is reported by the next read`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        val variant = w.fixtures.variant(product)
        val doomed = w.fixtures.product()

        service.addItem(user, line(product, 1, variant.id))
        service.addItem(user, line(doomed))
        w.products.deleteById(doomed.id, pool)
        w.variants.markDeleted(variant.id, 1L, pool)

        val view = service.get(user)

        assertEquals(listOf("VARIANT_UNAVAILABLE"), view.lines.single { it.item.productId == product.id }.errors)
        assertEquals(listOf("PRODUCT_UNAVAILABLE"), view.lines.single { it.item.productId == doomed.id }.errors)
        assertEquals(0, view.lines.sumOf { it.maxQuantity })
        assertFalse(view.canCheckout)
    }

    // ----- update ----------------------------------------------------------------------------------------------------

    @Test
    fun `update changes the quantity in place`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        val id = service.addItem(user, line(product, 2)).lines.single().item.id

        val view = service.updateItem(user, id, CartService.ItemPatch(quantity = 7))

        assertEquals(id, view.lines.single().item.id)
        assertEquals(7, view.lines.single().item.quantity)
    }

    @Test
    fun `update with a changed key moves the line and merges into an identical one`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        w.fixtures.field(product, "nick")
        val plain = service.addItem(user, line(product, 2)).lines.single().item.id
        service.addItem(user, line(product, 3, 0, "nick" to "Steve"))

        // the plain line gains the nick of the other one: it merges into it (2 + 3)
        val view = service.updateItem(user, plain, CartService.ItemPatch(fieldValues = CartService.Field(mapOf("nick" to "Steve"))))

        assertEquals(1, view.lines.size)
        assertEquals(5, view.lines.single().item.quantity)
        assertEquals(CartLineKey.of(product.id, 0, mapOf("nick" to "Steve"), null), view.lines.single().item.lineKey)

        // a changed key without a twin keeps the quantity and the new key
        val moved = service.updateItem(user, view.lines.single().item.id, CartService.ItemPatch(targetServerId = CartService.Field(8L)))
        assertEquals(1, moved.lines.size)
        assertEquals(8L, moved.lines.single().item.targetServerId)
        assertEquals(5, moved.lines.single().item.quantity)
        assertNotEquals(view.lines.single().item.lineKey, moved.lines.single().item.lineKey)

        val cleared = service.updateItem(user, moved.lines.single().item.id, CartService.ItemPatch(targetServerId = CartService.Field(null)))
        assertNull(cleared.lines.single().item.targetServerId)
    }

    @Test
    fun `update of a missing line or a line of another cart is NOT_FOUND`(): Unit = runBlocking {
        val alice = newUser()
        val bob = newUser()
        val product = w.fixtures.product()
        val aliceLine = service.addItem(alice, line(product, 2)).lines.single().item.id
        service.get(bob)

        assertThrows(NotFound::class.java) { runBlocking { service.updateItem(bob, aliceLine, CartService.ItemPatch(quantity = 9)) } }
        assertThrows(NotFound::class.java) { runBlocking { service.updateItem(bob, 987654, CartService.ItemPatch(quantity = 9)) } }
        assertEquals(2, rows(alice).single().quantity)
    }

    @Test
    fun `update to an invalid field key is INVALID_CART and leaves the line`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        val id = service.addItem(user, line(product, 2)).lines.single().item.id

        val errors = lineErrors { service.updateItem(user, id, CartService.ItemPatch(fieldValues = CartService.Field(mapOf("nope" to "x")))) }

        assertEquals(listOf("FIELD_INVALID"), errors.values.single())
        assertEquals(2, rows(user).single().quantity)
    }

    // ----- remove / clear --------------------------------------------------------------------------------------------

    @Test
    fun `remove deletes one line, is idempotent and never touches another cart`(): Unit = runBlocking {
        val alice = newUser()
        val bob = newUser()
        val a = w.fixtures.product()
        val b = w.fixtures.product()
        val aliceLine = service.addItem(alice, line(a)).lines.single().item.id
        service.addItem(alice, line(b))
        val bobLine = service.addItem(bob, line(a)).lines.single().item.id

        assertEquals(1, service.removeItem(alice, aliceLine).lines.size)
        assertEquals(1, service.removeItem(alice, aliceLine).lines.size)
        // bob's id given by alice removes nothing
        assertEquals(1, service.removeItem(alice, bobLine).lines.size)
        assertEquals(1, rows(bob).size)
    }

    @Test
    fun `clear empties the lines and the cart level fields but keeps the currency`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        service.addItem(user, line(product, 3))
        service.replace(
            user,
            CartService.Replacement(
                currency = CartService.Field("USD"), couponCode = CartService.Field("c"), creatorCode = CartService.Field("d"),
                recipientUsername = CartService.Field("Steve"), giftMessage = CartService.Field("hi"),
                shippingAddressId = CartService.Field(4L), shippingMethodId = CartService.Field(5L)
            )
        )

        val view = service.clear(user)

        assertTrue(view.lines.isEmpty())
        assertNull(view.cart.couponCode)
        assertNull(view.cart.creatorCode)
        assertNull(view.cart.recipientUsername)
        assertNull(view.cart.giftMessage)
        assertNull(view.cart.shippingAddressId)
        assertNull(view.cart.shippingMethodId)
        assertEquals("USD", view.cart.currency)
        assertEquals(0, count("market_cart_item"))
    }

    // ----- replace ---------------------------------------------------------------------------------------------------

    @Test
    fun `replace swaps the lines, sums equal keys and caps at 999`(): Unit = runBlocking {
        val user = newUser()
        val a = w.fixtures.product()
        val b = w.fixtures.product()
        service.addItem(user, line(a, 2))

        val view = service.replace(user, CartService.Replacement(items = listOf(line(b, 600), line(b, 600), line(a, 1))))

        assertEquals(listOf(b.id to 999, a.id to 1), view.lines.map { it.item.productId to it.item.quantity })
    }

    @Test
    fun `replace applies only the cart level fields that are present and null clears`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        service.addItem(user, line(product, 2))

        val first = service.replace(
            user,
            CartService.Replacement(couponCode = CartService.Field("  summer "), creatorCode = CartService.Field("streamer"), giftMessage = CartService.Field("hello"), recipientUsername = CartService.Field(" Steve "))
        )

        assertEquals(1, first.lines.size)
        assertEquals("SUMMER", first.cart.couponCode)
        assertEquals("STREAMER", first.cart.creatorCode)
        assertEquals("hello", first.cart.giftMessage)
        assertEquals("Steve", first.cart.recipientUsername)

        val second = service.replace(user, CartService.Replacement(couponCode = CartService.Field(null), giftMessage = CartService.Field("   ")))

        assertNull(second.cart.couponCode)
        assertNull(second.cart.giftMessage)
        assertEquals("STREAMER", second.cart.creatorCode)
        assertEquals("Steve", second.cart.recipientUsername)
        assertEquals(1, second.lines.size)

        assertThrows(BadRequest::class.java) { runBlocking { service.replace(user, CartService.Replacement(couponCode = CartService.Field("X".repeat(65)))) } }
        assertThrows(BadRequest::class.java) { runBlocking { service.replace(user, CartService.Replacement(currency = CartService.Field("???"))) } }
    }

    @Test
    fun `replace with an invalid line changes nothing`(): Unit = runBlocking {
        val user = newUser()
        val a = w.fixtures.product()
        val b = w.fixtures.product()
        service.addItem(user, line(a, 2))
        val bad = CartLine(777_777, 0, 1)

        val errors = lineErrors { service.replace(user, CartService.Replacement(items = listOf(line(b), bad), couponCode = CartService.Field("x"))) }

        assertEquals(mapOf(bad.lineKey to listOf("PRODUCT_UNAVAILABLE")), errors)
        assertEquals(listOf(a.id), rows(user).map { it.productId })
        assertNull(service.get(user).cart.couponCode)
    }

    @Test
    fun `replace with more than 50 distinct lines is refused`(): Unit = runBlocking {
        val user = newUser()
        val products = (1..51).map { w.fixtures.product() }

        assertEquals(mapOf("cart" to listOf("CART_FULL")), lineErrors { service.replace(user, CartService.Replacement(items = products.map { line(it) })) })
        // 51 lines that sum to 50 are fine
        val view = service.replace(user, CartService.Replacement(items = products.take(50).map { line(it) } + line(products.first())))
        assertEquals(50, view.lines.size)
        assertEquals(2, view.lines.first().item.quantity)
    }

    @Test
    fun `replace with an empty list empties the lines and keeps the fields`(): Unit = runBlocking {
        val user = newUser()
        val product = w.fixtures.product()
        service.addItem(user, line(product, 2))
        service.replace(user, CartService.Replacement(couponCode = CartService.Field("keep")))

        val view = service.replace(user, CartService.Replacement(items = emptyList()))

        assertTrue(view.lines.isEmpty())
        assertEquals("KEEP", view.cart.couponCode)
    }

    // ----- merge on login --------------------------------------------------------------------------------------------

    @Test
    fun `merge adds the browser lines and drops invalid ones with a message`(): Unit = runBlocking {
        val user = newUser()
        val a = w.fixtures.product()
        val b = w.fixtures.product()
        val deleted = w.fixtures.product()
        Fixtures.setColumns(pool, "market_product", deleted.id, mapOf("deletedAt" to 5L))
        val ghost = CartLine(555_555, 0, 1)
        val badVariant = line(a, 1, 424242)

        service.replace(user, CartService.Replacement(couponCode = CartService.Field("keep")))
        val view = service.merge(user, listOf(line(a, 2), line(b, 1), line(deleted, 1), ghost, badVariant, line(a, 3)), unreadable = 1)

        assertEquals(listOf(a.id to 5, b.id to 1), view.lines.map { it.item.productId to it.item.quantity })
        assertEquals(
            listOf(
                CartMessage("PRODUCT_UNAVAILABLE", "warning"),
                CartMessage("PRODUCT_UNAVAILABLE", "warning", line(deleted, 1).lineKey),
                CartMessage("PRODUCT_UNAVAILABLE", "warning", ghost.lineKey),
                CartMessage("PRODUCT_UNAVAILABLE", "warning", badVariant.lineKey)
            ),
            view.messages
        )
        // cart level fields are never touched by a merge
        assertEquals("KEEP", view.cart.couponCode)
    }

    @Test
    fun `merge takes the larger quantity of an equal line and is idempotent`(): Unit = runBlocking {
        val user = newUser()
        val a = w.fixtures.product()
        val b = w.fixtures.product()
        service.addItem(user, line(a, 3))
        service.addItem(user, line(b, 8))

        val browser = listOf(line(a, 5), line(b, 2))
        val first = service.merge(user, browser)
        val second = service.merge(user, browser)

        assertEquals(listOf(a.id to 5, b.id to 8), first.lines.map { it.item.productId to it.item.quantity })
        assertEquals(first.lines.map { it.item.id to it.item.quantity }, second.lines.map { it.item.id to it.item.quantity })
        assertEquals(2, count("market_cart_item"))
    }

    @Test
    fun `merge stops at 50 lines and warns about the rest`(): Unit = runBlocking {
        val user = newUser()
        val products = (1..52).map { w.fixtures.product() }
        products.take(48).forEach { service.addItem(user, line(it)) }

        val view = service.merge(user, products.drop(48).map { line(it) } + line(products.first(), 6))

        assertEquals(50, view.lines.size)
        assertEquals(6, view.lines.single { it.item.productId == products.first().id }.item.quantity)
        assertEquals(
            listOf(CartMessage("MAX_QUANTITY", "warning", line(products[50]).lineKey), CartMessage("MAX_QUANTITY", "warning", line(products[51]).lineKey)),
            view.messages.filter { it.code == "MAX_QUANTITY" }
        )
        assertEquals(50L, count("market_cart_item"))
    }

    @Test
    fun `merge clamps a huge browser quantity and keeps at most 50 browser lines`(): Unit = runBlocking {
        val user = newUser()
        val products = (1..55).map { w.fixtures.product() }

        val view = service.merge(user, listOf(line(products[0], 1)) + products.drop(1).map { line(it, 1) } + CartLine(products[0].id, 0, Int.MAX_VALUE))

        assertEquals(50, view.lines.size)
        assertEquals(999, view.lines.first().item.quantity)
    }

    // ----- clear after checkout --------------------------------------------------------------------------------------

    @Test
    fun `the checkout clears the lines and the cart level fields of its buyer only`(): Unit = runBlocking {
        val buyer = newUser()
        val other = newUser()
        val product = w.fixtures.product()
        service.addItem(buyer, line(product, 2))
        service.addItem(other, line(product, 1))
        service.replace(buyer, CartService.Replacement(currency = CartService.Field("USD"), couponCode = CartService.Field("c"), giftMessage = CartService.Field("g"), shippingMethodId = CartService.Field(3L)))
        service.replace(other, CartService.Replacement(couponCode = CartService.Field("keepme")))

        assertTrue(w.db.tx { conn -> service.clearAfterCheckout(conn, buyer) })

        val view = service.get(buyer)
        assertTrue(view.lines.isEmpty())
        assertNull(view.cart.couponCode)
        assertNull(view.cart.giftMessage)
        assertNull(view.cart.shippingMethodId)
        assertEquals("USD", view.cart.currency)
        assertEquals(1, service.get(other).lines.size)
        assertEquals("KEEPME", service.get(other).cart.couponCode)
    }

    @Test
    fun `clearing after a checkout without a cart is a no-op`(): Unit = runBlocking {
        assertFalse(w.db.tx { conn -> service.clearAfterCheckout(conn, newUser()) })
    }

    @Test
    fun `a rolled back checkout leaves the cart as it was`(): Unit = runBlocking {
        val buyer = newUser()
        val product = w.fixtures.product()
        service.addItem(buyer, line(product, 2))

        runCatching {
            w.db.tx { conn ->
                service.clearAfterCheckout(conn, buyer)
                error("order creation failed")
            }
        }

        assertEquals(2, service.get(buyer).lines.single().item.quantity)
    }

    // ----- races -----------------------------------------------------------------------------------------------------

    /** R-24 twin. */
    @Test
    fun `ten concurrent identical posts leave one row with quantity ten`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val user = newUser()
            val product = w.fixtures.product()
            w.fixtures.field(product, "nick")

            val results = Race.run(10) { runCatching { service.addItem(user, CartLine(product.id, 0, 1, mapOf("nick" to "Steve"))) } }

            assertTrue(results.all { it.getOrThrow().isSuccess }, "round $round: ${results.map { it.getOrThrow().exceptionOrNull() }}")
            val stored = rows(user)
            assertEquals(1, stored.size, "round $round")
            assertEquals(10, stored.single().quantity, "round $round")
        }
    }

    @Test
    fun `concurrent distinct posts never exceed 50 lines`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val user = newUser()
            val products = (1..60).map { w.fixtures.product() }

            val results = Race.run(60) { i -> runCatching { service.addItem(user, line(products[i])) } }

            val failures = results.map { it.getOrThrow() }.filter { it.isFailure }.map { it.exceptionOrNull() }

            assertTrue(failures.all { it is InvalidCart }, "round $round: $failures")
            assertEquals(50, results.count { it.getOrThrow().isSuccess }, "round $round")
            assertEquals(50, rows(user).size, "round $round")
        }
    }

    @Test
    fun `concurrent merges of one browser cart are idempotent and never deadlock`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val user = newUser()
            val products = (1..6).map { w.fixtures.product() }
            val browser = products.mapIndexed { i, p -> line(p, i + 1) }

            val results = Race.run(12) { runCatching { service.merge(user, browser) } }

            val failures = results.map { it.getOrThrow() }.filter { it.isFailure }.map { it.exceptionOrNull() }
            assertTrue(failures.isEmpty(), "round $round: $failures")
            assertTrue(failures.none { it is MarketBusyException })
            assertEquals(products.mapIndexed { i, p -> p.id to i + 1 }, rows(user).map { it.productId to it.quantity }, "round $round")
        }
    }

    @Test
    fun `adds racing a replace and a clear leave a consistent cart`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val user = newUser()
            val products = (1..8).map { w.fixtures.product() }

            val results = Race.run(16) { i ->
                runCatching {
                    when (i % 4) {
                        0 -> service.replace(user, CartService.Replacement(items = products.take(3).map { line(it, 2) }))
                        1 -> service.clear(user)
                        else -> service.addItem(user, line(products[i % products.size], 1))
                    }
                }
            }

            val failures = results.map { it.getOrThrow() }.filter { it.isFailure }.map { it.exceptionOrNull() }
            assertTrue(failures.isEmpty(), "round $round: $failures")

            val stored = rows(user)
            assertEquals(stored.map { it.lineKey }.toSet().size, stored.size, "round $round")
            assertTrue(stored.size <= CartLimits.MAX_LINES)
            assertTrue(stored.all { it.quantity in 1..CartLimits.MAX_QUANTITY })
        }
    }
}
