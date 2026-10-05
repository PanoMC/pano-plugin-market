package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.CodeRef
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.OrderChangedException
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidCoupon
import com.panomc.plugins.market.error.InvalidCreatorCode
import com.panomc.plugins.market.error.InvalidGiftCode
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.error.OutOfStock
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The reservation of an order on a real MariaDB (MK-073, 06 section 7 and 13): stock by conditional statements, the
 * limits and counters of codes and discounts, commit, release (exact, once), re-reserve, and the lock set of every
 * `Locks.forOrder` scope. The global invariants (I5, I6, I7, I12 among them) are checked after every test by the base.
 *
 * Orders are placed the way the order transaction does (B8 to B11: reserve, order row, item rows, redemption rows) by
 * [ReservationHarness]; the status changes that the order service will own (EXPIRED after a release, COMPLETED after a
 * commit) are written by the harness in the same transaction so the invariants judge a complete picture.
 */
class ReservationServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val h by lazy { ReservationHarness(w) }

    private suspend fun stockOf(table: String, id: Long): Int? = sql("SELECT `stock` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("stock")

    private suspend fun usedCount(table: String, id: Long): Int = sql("SELECT `usedCount` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("usedCount")

    private suspend fun soldCount(productId: Long): Int = sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", productId).single().getInteger("soldCount")

    private suspend fun redemptionStates(orderId: Long): List<String> =
        sql("SELECT `state` FROM `pano_market_redemption` WHERE `orderId` = ? ORDER BY `id`", orderId).map { it.getString("state") }

    private suspend fun reservationState(orderId: Long): ReservationState = w.orders.getById(orderId, pool)!!.reservationState

    private suspend fun stockReserved(orderId: Long): List<Int> =
        sql("SELECT `stockReserved` FROM `pano_market_order_item` WHERE `orderId` = ? ORDER BY `id`", orderId).map { it.getInteger("stockReserved") }

    private fun body(e: Throwable): JsonObject = JsonObject((e as com.panomc.platform.model.Error).encode(emptyMap()))

    private fun couponUse(c: com.panomc.plugins.market.db.model.MarketCoupon, amount: Long = 100) = CodeUse(RedemptionKind.COUPON, c.id, c.code, amount, "EUR")

    // ===== stock ==========================================================================================================

    @Test
    fun `stock of a product is deducted and each item holds its units`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 10)
        val a = h.demand(product, 3)
        val b = h.demand(product, 2)

        val placed = h.placeTx(listOf(ReservationHarness.Line(a), ReservationHarness.Line(b)))

        assertEquals(mapOf(a.itemKey to 3, b.itemKey to 2), placed.reservation.stockReserved)
        assertEquals(5, stockOf("market_product", product.id))
        assertEquals(listOf(3, 2), stockReserved(placed.orderId))
        assertEquals(ReservationState.HELD, reservationState(placed.orderId))
    }

    @Test
    fun `an unlimited product writes nothing and holds zero units`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = null)
        val before = w.products.getById(product.id, pool)!!.updatedAt
        w.clock.advance(5_000)

        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 4))))

        assertNull(stockOf("market_product", product.id))
        assertEquals(listOf(0), stockReserved(placed.orderId))
        assertEquals(before, w.products.getById(product.id, pool)!!.updatedAt, "no statement ran for the unlimited product")
    }

    @Test
    fun `the variant row is the stock subject and the product stock is ignored`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 1)
        val variant = w.fixtures.variant(product, stock = 4)

        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 3, variant))))

        assertEquals(1, stockOf("market_product", product.id), "the product stock is ignored for a product with variants")
        assertEquals(1, stockOf("market_product_variant", variant.id))
        assertEquals(listOf(3), stockReserved(placed.orderId))

        // an unlimited variant of a product that has a (now meaningless) stock holds nothing
        val unlimited = w.fixtures.variant(product, stock = null)
        val second = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 2, unlimited))))
        assertEquals(listOf(0), stockReserved(second.orderId))
        assertEquals(1, stockOf("market_product", product.id))
    }

    @Test
    fun `lines on one subject are summed first and every failing line is reported`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val other = w.fixtures.product(stock = 10)
        val lines = listOf(h.demand(product, 2, key = "l1"), h.demand(product, 2, key = "l2"), h.demand(product, 2, key = "l3"), h.demand(other, 1, key = "ok"))

        val failure = runCatching { h.placeTx(lines.map { ReservationHarness.Line(it) }) }.exceptionOrNull()

        assertTrue(failure is OutOfStock, "expected OUT_OF_STOCK, got $failure")
        assertEquals("OUT_OF_STOCK", body(failure!!).getString("error"))
        assertEquals(listOf("l1", "l2", "l3"), body(failure).getJsonArray("lines").list)
        assertEquals(5, stockOf("market_product", product.id), "nothing was deducted")
        assertEquals(10, stockOf("market_product", other.id), "the subject that fitted is rolled back with the rest")
        assertEquals(0, count("market_order"))
    }

    @Test
    fun `all failing subjects are reported, not only the first`(): Unit = runBlocking {
        val empty = w.fixtures.product(stock = 0)
        val short = w.fixtures.product(stock = 1)
        val fine = w.fixtures.product(stock = 5)

        val failure = runCatching {
            h.placeTx(
                listOf(
                    ReservationHarness.Line(h.demand(fine, 1, key = "c")),
                    ReservationHarness.Line(h.demand(short, 2, key = "b")),
                    ReservationHarness.Line(h.demand(empty, 1, key = "a"))
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is OutOfStock)
        assertEquals(setOf("a", "b"), body(failure!!).getJsonArray("lines").list.toSet())
        assertEquals(5, stockOf("market_product", fine.id))
        assertEquals(1, stockOf("market_product", short.id))
    }

    @Test
    fun `exactly the stock can be bought and one unit more is out of stock`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 3)

        h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 3))))
        assertEquals(0, stockOf("market_product", product.id))

        assertThrows(OutOfStock::class.java) { runBlocking { h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1)))) } }
        assertEquals(0, stockOf("market_product", product.id))
    }

    @Test
    fun `a product row that does not exist is out of stock`(): Unit = runBlocking {
        val failure = runCatching {
            w.db.tx { conn -> h.reservations.reserve(conn, listOf(com.panomc.plugins.market.service.StockDemand("ghost", "ghost", 987_654, null, 1))) }
        }.exceptionOrNull()

        assertTrue(failure is OutOfStock)
        assertEquals(listOf("ghost"), body(failure!!).getJsonArray("lines").list)
    }

    @Test
    fun `a bundle reserves its own stock and each child with the multiplied quantity, all or nothing`(): Unit = runBlocking {
        val bundle = w.fixtures.product(stock = 10)
        val child1 = w.fixtures.product(stock = 20)
        val child2 = w.fixtures.product(stock = null)
        // two bundles of a bundle whose children are 3 x child1 and 1 x child2
        val demands = listOf(
            ReservationHarness.Line(StockDemand("L", "bundle", bundle.id, null, 2), OrderItemKind.BUNDLE),
            ReservationHarness.Line(StockDemand("L", "child1", child1.id, null, 6), OrderItemKind.BUNDLE_CHILD, "bundle"),
            ReservationHarness.Line(StockDemand("L", "child2", child2.id, null, 2), OrderItemKind.BUNDLE_CHILD, "bundle")
        )

        val placed = h.placeTx(demands)

        assertEquals(mapOf("bundle" to 2, "child1" to 6, "child2" to 0), placed.reservation.stockReserved)
        assertEquals(8, stockOf("market_product", bundle.id))
        assertEquals(14, stockOf("market_product", child1.id))
        assertNull(stockOf("market_product", child2.id))

        // a child that cannot be served fails the whole line and gives the bundle's own units back
        val scarce = w.fixtures.product(stock = 1)
        val failure = runCatching {
            h.placeTx(
                listOf(
                    ReservationHarness.Line(StockDemand("M", "b2", bundle.id, null, 1), OrderItemKind.BUNDLE),
                    ReservationHarness.Line(StockDemand("M", "c3", scarce.id, null, 2), OrderItemKind.BUNDLE_CHILD, "b2")
                )
            )
        }.exceptionOrNull()
        assertTrue(failure is OutOfStock)
        assertEquals(listOf("M"), body(failure!!).getJsonArray("lines").list)
        assertEquals(8, stockOf("market_product", bundle.id))
        assertEquals(1, stockOf("market_product", scarce.id))
    }

    @Test
    fun `a forced reservation clamps at zero and holds the units that were there`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 3)
        val first = h.demand(product, 2)
        val second = h.demand(product, 2)

        // without force 2 + 2 > 3 is refused
        assertThrows(OutOfStock::class.java) { runBlocking { h.placeTx(listOf(ReservationHarness.Line(first), ReservationHarness.Line(second))) } }
        assertEquals(3, stockOf("market_product", product.id))

        val placed = h.placeTx(listOf(ReservationHarness.Line(first), ReservationHarness.Line(second)), force = true)

        assertEquals(mapOf(first.itemKey to 2, second.itemKey to 1), placed.reservation.stockReserved)
        assertEquals(0, stockOf("market_product", product.id), "clamped at zero, never negative (I5)")
        assertEquals(listOf(2, 1), stockReserved(placed.orderId))
    }

    // ===== codes ==========================================================================================================

    @Test
    fun `a coupon is counted and its redemption is recorded HELD with the customer keys`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(redeemLimit = 5)
        val customer = CustomerKeys(userId = 7, buyerKey = "u:7", email = "Buyer@Example.com", recipientKey = "g:steve")

        val placed = h.placeTx(emptyList(), listOf(couponUse(coupon, 250)), customer)

        assertEquals(1, usedCount("market_coupon", coupon.id))
        val row = w.redemptions.get(RedemptionKind.COUPON, coupon.id, placed.orderId, pool)!!
        assertEquals(coupon.code, row.code)
        assertEquals(7L, row.userId)
        assertEquals("u:7", row.buyerKey)
        assertEquals("buyer@example.com", row.email)
        assertEquals("g:steve", row.recipientKey)
        assertEquals(250L, row.amount)
        assertEquals("EUR", row.currency)
        assertEquals(com.panomc.plugins.market.db.model.RedemptionState.HELD, row.state)

        h.placeTx(emptyList(), listOf(couponUse(coupon)), h.customer())
        assertEquals(2, usedCount("market_coupon", coupon.id))
    }

    @Test
    fun `a coupon at its redeem limit is refused with CODE_LIMIT_REACHED and nothing changes`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(redeemLimit = 2)
        h.placeTx(emptyList(), listOf(couponUse(coupon)))
        h.placeTx(emptyList(), listOf(couponUse(coupon)))
        val product = w.fixtures.product(stock = 5)

        val failure = runCatching { h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))), listOf(couponUse(coupon))) }.exceptionOrNull()

        assertTrue(failure is InvalidCoupon, "expected INVALID_COUPON, got $failure")
        assertEquals("CODE_LIMIT_REACHED", body(failure!!).getString("reason"))
        assertEquals(2, usedCount("market_coupon", coupon.id))
        assertEquals(5, stockOf("market_product", product.id), "the stock taken before the code failed is rolled back")
        assertEquals(2, count("market_order"))
    }

    @Test
    fun `an unlimited coupon is counted without a limit`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(redeemLimit = null)
        repeat(4) { h.placeTx(emptyList(), listOf(couponUse(coupon))) }
        assertEquals(4, usedCount("market_coupon", coupon.id))
    }

    @Test
    fun `customerRedeemLimit counts the payer by key, a guest by e-mail and the recipient by key`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(redeemLimit = null, customerRedeemLimit = 1)

        h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:abc", "a@x.com", "g:steve"))

        // same payer key
        assertCodeLimit { h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:abc", null, "")) }
        // another payer name, same guest e-mail (compared lower-cased)
        assertCodeLimit { h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:xyz", "A@X.com", "")) }
        // another payer and e-mail, same recipient: a guest cannot multiply a single-use coupon by changing the payer name
        assertCodeLimit { h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:qqq", "b@x.com", "g:steve")) }
        // the recipient key set matches either key of the same person
        assertCodeLimit { h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:zzz", null, "u:9", listOf("u:9", "g:steve"))) }
        assertEquals(1, usedCount("market_coupon", coupon.id))

        // somebody else entirely may still use it; an empty key never matches the empty keys of other rows
        h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:other", "c@x.com", ""))
        h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:another", "d@x.com", "", listOf("")))
        assertEquals(3, usedCount("market_coupon", coupon.id))
    }

    private suspend fun assertCodeLimit(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is InvalidCoupon, "expected INVALID_COUPON, got $failure")
        assertEquals("CODE_LIMIT_REACHED", body(failure!!).getString("reason"))
    }

    @Test
    fun `a released use frees the per-customer allowance`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(redeemLimit = 1, customerRedeemLimit = 1)
        val customer = CustomerKeys(null, "g:abc", null, "")
        val first = h.placeTx(emptyList(), listOf(couponUse(coupon)), customer)

        assertCodeLimit { h.placeTx(emptyList(), listOf(couponUse(coupon)), customer) }
        assertTrue(h.expire(first.orderId))
        assertEquals(0, usedCount("market_coupon", coupon.id))

        h.placeTx(emptyList(), listOf(couponUse(coupon)), customer)
        assertEquals(1, usedCount("market_coupon", coupon.id))
    }

    @Test
    fun `creator code gift code and discount have their own limit errors`(): Unit = runBlocking {
        val creator = w.fixtures.creatorCode()
        Fixtures.setColumns(pool, "market_creator_code", creator.id, mapOf("redeemLimit" to 1))
        val gift = w.fixtures.gift(redeemLimit = 1)
        Fixtures.setColumns(pool, "market_gift", gift.id, mapOf("customerRedeemLimit" to null))
        val discount = w.fixtures.discount(usageLimit = 1)
        val uses = listOf(
            CodeUse(RedemptionKind.CREATOR_CODE, creator.id, creator.code, 50, "EUR"),
            CodeUse(RedemptionKind.GIFT, gift.id, gift.code, 0, "EUR"),
            CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 20, "EUR")
        )

        h.placeTx(emptyList(), uses)

        val creatorFailure = runCatching { h.placeTx(emptyList(), uses.take(1)) }.exceptionOrNull()
        assertTrue(creatorFailure is InvalidCreatorCode)
        assertEquals("CODE_LIMIT_REACHED", body(creatorFailure!!).getString("reason"))

        val giftFailure = runCatching { h.placeTx(emptyList(), uses.subList(1, 2)) }.exceptionOrNull()
        assertTrue(giftFailure is InvalidGiftCode)
        assertEquals("CODE_LIMIT_REACHED", body(giftFailure!!).getString("reason"))

        val discountFailure = runCatching { h.placeTx(emptyList(), uses.subList(2, 3)) }.exceptionOrNull()
        assertTrue(discountFailure is DiscountUnavailable)
        assertEquals(discount.id, (discountFailure as DiscountUnavailable).discountId)

        assertEquals(1, usedCount("market_creator_code", creator.id))
        assertEquals(1, usedCount("market_gift", gift.id))
        assertEquals(1, usedCount("market_discount", discount.id))
        assertEquals(1, count("market_order"))
    }

    @Test
    fun `a gift code limit per customer is counted like a coupon's`(): Unit = runBlocking {
        val gift = w.fixtures.gift(redeemLimit = null) // customerRedeemLimit keeps the column default 1
        val use = CodeUse(RedemptionKind.GIFT, gift.id, gift.code, 0, "EUR")
        val customer = CustomerKeys(7, "u:7", null, "")

        h.placeTx(emptyList(), listOf(use), customer)

        val failure = runCatching { h.placeTx(emptyList(), listOf(use), customer) }.exceptionOrNull()
        assertTrue(failure is InvalidGiftCode)
        h.placeTx(emptyList(), listOf(use), CustomerKeys(8, "u:8", null, ""))
        assertEquals(2, usedCount("market_gift", gift.id))
    }

    @Test
    fun `a creator code and a discount have no per-customer limit`(): Unit = runBlocking {
        val creator = w.fixtures.creatorCode()
        val discount = w.fixtures.discount(usageLimit = null)
        val customer = CustomerKeys(7, "u:7", "a@b.c", "u:7")
        val uses = listOf(CodeUse(RedemptionKind.CREATOR_CODE, creator.id, creator.code, 1, "EUR"), CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 1, "EUR"))

        repeat(3) { h.placeTx(emptyList(), uses, customer) }

        assertEquals(3, usedCount("market_creator_code", creator.id))
        assertEquals(3, usedCount("market_discount", discount.id))
    }

    @Test
    fun `a missing or soft deleted code is CODE_NOT_FOUND and a gone discount is unavailable`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon()
        Fixtures.setColumns(pool, "market_coupon", coupon.id, mapOf("deletedAt" to 5))

        val deleted = runCatching { h.placeTx(emptyList(), listOf(couponUse(coupon))) }.exceptionOrNull()
        assertTrue(deleted is InvalidCoupon)
        assertEquals("CODE_NOT_FOUND", body(deleted!!).getString("reason"))

        val missing = runCatching { h.placeTx(emptyList(), listOf(CodeUse(RedemptionKind.CREATOR_CODE, 424_242, "NOPE", 1, "EUR"))) }.exceptionOrNull()
        assertTrue(missing is InvalidCreatorCode)
        assertEquals("CODE_NOT_FOUND", body(missing!!).getString("reason"))

        val goneGift = runCatching { h.placeTx(emptyList(), listOf(CodeUse(RedemptionKind.GIFT, 424_242, "NOPE", 0, "EUR"))) }.exceptionOrNull()
        assertTrue(goneGift is InvalidGiftCode)

        val gone = runCatching { h.placeTx(emptyList(), listOf(CodeUse(RedemptionKind.DISCOUNT, 424_242, null, 1, "EUR"))) }.exceptionOrNull()
        assertTrue(gone is DiscountUnavailable)

        assertEquals(0, usedCount("market_coupon", coupon.id))
        assertEquals(0, count("market_order"))
    }

    @Test
    fun `the same code twice in one order is a programming error`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon()

        assertThrows(IllegalArgumentException::class.java) { runBlocking { h.placeTx(emptyList(), listOf(couponUse(coupon), couponUse(coupon))) } }
        assertEquals(0, usedCount("market_coupon", coupon.id))
    }

    @Test
    fun `recording the redemptions twice for one order inserts once`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon()
        val customer = h.customer()
        val placed = h.placeTx(emptyList(), listOf(couponUse(coupon)), customer)

        val again = w.db.tx { conn -> h.redemptions.record(conn, placed.orderId, listOf(couponUse(coupon)), customer) }

        assertEquals(0, again)
        assertEquals(1, count("market_redemption", "orderId = ?", placed.orderId))
        assertEquals(1, usedCount("market_coupon", coupon.id))
    }

    // ===== commit =========================================================================================================

    @Test
    fun `commit applies the redemptions, counts soldCount per product with bundle children and runs once`(): Unit = runBlocking {
        val bundle = w.fixtures.product(stock = 10)
        val child = w.fixtures.product(stock = null)
        val single = w.fixtures.product(stock = 9)
        val coupon = w.fixtures.coupon()
        val placed = h.placeTx(
            listOf(
                ReservationHarness.Line(StockDemand("L", "bundle", bundle.id, null, 2), OrderItemKind.BUNDLE),
                ReservationHarness.Line(StockDemand("L", "child", child.id, null, 6), OrderItemKind.BUNDLE_CHILD, "bundle"),
                ReservationHarness.Line(StockDemand("S", "single", single.id, null, 4))
            ),
            listOf(couponUse(coupon))
        )

        assertTrue(h.complete(placed.orderId))

        assertEquals(ReservationState.COMMITTED, reservationState(placed.orderId))
        assertEquals(listOf("APPLIED"), redemptionStates(placed.orderId))
        assertEquals(2, soldCount(bundle.id))
        assertEquals(6, soldCount(child.id))
        assertEquals(4, soldCount(single.id))
        assertEquals(8, stockOf("market_product", bundle.id), "stock was deducted at reserve and is not touched again")
        assertEquals(1, usedCount("market_coupon", coupon.id))

        assertFalse(h.complete(placed.orderId), "a second commit changes nothing")
        assertEquals(4, soldCount(single.id))
    }

    @Test
    fun `commit of a test order leaves soldCount alone`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 2))), testMode = true)

        assertTrue(h.complete(placed.orderId))

        assertEquals(0, soldCount(product.id))
        assertEquals(ReservationState.COMMITTED, reservationState(placed.orderId))
    }

    @Test
    fun `commit needs the COMMIT or RELEASE lock scope`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { w.db.tx { conn -> h.locks.forOrder(conn, placed.orderId, OrderLockScope.PAYMENT) { h.reservations.commit(conn, it) } } }
        }
        assertEquals(ReservationState.HELD, reservationState(placed.orderId))
    }

    // ===== release ========================================================================================================

    @Test
    fun `release restores stock, counters and redemptions exactly`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 10)
        val withVariants = w.fixtures.product(stock = 77)
        val variant = w.fixtures.variant(withVariants, stock = 6)
        val unlimited = w.fixtures.product(stock = null)
        val coupon = w.fixtures.coupon(redeemLimit = 3)
        val creator = w.fixtures.creatorCode()
        val discount = w.fixtures.discount(usageLimit = 5)
        val uses = listOf(couponUse(coupon), CodeUse(RedemptionKind.CREATOR_CODE, creator.id, creator.code, 5, "EUR"), CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 9, "EUR"))
        val placed = h.placeTx(
            listOf(
                ReservationHarness.Line(h.demand(product, 4)),
                ReservationHarness.Line(h.demand(withVariants, 2, variant)),
                ReservationHarness.Line(h.demand(unlimited, 3))
            ),
            uses
        )
        assertEquals(6, stockOf("market_product", product.id))
        assertEquals(4, stockOf("market_product_variant", variant.id))
        assertEquals(1, usedCount("market_coupon", coupon.id))

        assertTrue(h.expire(placed.orderId))

        assertEquals(10, stockOf("market_product", product.id))
        assertEquals(6, stockOf("market_product_variant", variant.id))
        assertEquals(77, stockOf("market_product", withVariants.id), "the ignored product stock is untouched")
        assertNull(stockOf("market_product", unlimited.id))
        assertEquals(0, usedCount("market_coupon", coupon.id))
        assertEquals(0, usedCount("market_creator_code", creator.id))
        assertEquals(0, usedCount("market_discount", discount.id))
        assertEquals(listOf("RELEASED", "RELEASED", "RELEASED"), redemptionStates(placed.orderId))
        assertEquals(listOf(0, 0, 0), stockReserved(placed.orderId))
        assertEquals(ReservationState.RELEASED, reservationState(placed.orderId))
    }

    @Test
    fun `a second release changes nothing`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val coupon = w.fixtures.coupon(redeemLimit = 2)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 3))), listOf(couponUse(coupon)))

        assertTrue(h.expire(placed.orderId))
        assertFalse(h.expire(placed.orderId))

        assertEquals(5, stockOf("market_product", product.id), "the stock came back once")
        assertEquals(0, usedCount("market_coupon", coupon.id), "the counter went down once")
    }

    @Test
    fun `a subject that became unlimited stays unlimited and a changed stock gets the units added`(): Unit = runBlocking {
        val becomesUnlimited = w.fixtures.product(stock = 5)
        val adjusted = w.fixtures.product(stock = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(becomesUnlimited, 2)), ReservationHarness.Line(h.demand(adjusted, 2))))
        w.products.setStock(becomesUnlimited.id, null, pool)
        w.products.setStock(adjusted.id, 10, pool)

        assertTrue(h.expire(placed.orderId))

        assertNull(stockOf("market_product", becomesUnlimited.id))
        assertEquals(12, stockOf("market_product", adjusted.id))
    }

    @Test
    fun `release of a committed order changes nothing`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val coupon = w.fixtures.coupon()
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 2))), listOf(couponUse(coupon)))
        assertTrue(h.complete(placed.orderId))

        val released = w.db.tx { conn -> h.locks.forOrder(conn, placed.orderId, OrderLockScope.RELEASE) { h.reservations.release(conn, it) } }

        assertFalse(released)
        assertEquals(3, stockOf("market_product", product.id))
        assertEquals(listOf("APPLIED"), redemptionStates(placed.orderId))
        assertEquals(1, usedCount("market_coupon", coupon.id))
    }

    @Test
    fun `release and re-reserve need the RELEASE lock scope`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { w.db.tx { conn -> h.locks.forOrder(conn, placed.orderId, OrderLockScope.COMMIT) { h.reservations.release(conn, it) } } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { w.db.tx { conn -> h.locks.forOrder(conn, placed.orderId, OrderLockScope.COMMIT) { h.reservations.reReserve(conn, it) } } }
        }
        assertEquals(ReservationState.HELD, reservationState(placed.orderId))
        assertEquals(4, stockOf("market_product", product.id))
    }

    // ===== re-reserve =====================================================================================================

    @Test
    fun `re-reserve after a release takes the stock and the codes again and a commit follows`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val withVariants = w.fixtures.product(stock = null)
        val variant = w.fixtures.variant(withVariants, stock = 3)
        val coupon = w.fixtures.coupon(redeemLimit = 2)
        val discount = w.fixtures.discount(usageLimit = 2)
        val placed = h.placeTx(
            listOf(ReservationHarness.Line(h.demand(product, 2)), ReservationHarness.Line(h.demand(withVariants, 1, variant))),
            listOf(couponUse(coupon), CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 5, "EUR"))
        )
        assertTrue(h.expire(placed.orderId))
        assertEquals(5, stockOf("market_product", product.id))

        assertTrue(h.reReserve(placed.orderId))

        assertEquals(ReservationState.HELD, reservationState(placed.orderId))
        assertEquals(3, stockOf("market_product", product.id))
        assertEquals(2, stockOf("market_product_variant", variant.id))
        assertEquals(listOf("HELD", "HELD"), redemptionStates(placed.orderId))
        assertEquals(1, usedCount("market_coupon", coupon.id))
        assertEquals(1, usedCount("market_discount", discount.id))
        assertEquals(listOf(2, 1), stockReserved(placed.orderId), "the items hold their units again")

        assertTrue(h.complete(placed.orderId))
        assertEquals(listOf("APPLIED", "APPLIED"), redemptionStates(placed.orderId))
        assertEquals(2, soldCount(product.id))

        // the cycle is repeatable: released again, the picture is the same as the first time
        assertFalse(h.reReserve(placed.orderId), "a committed order is not re-reserved")
    }

    @Test
    fun `re-reserve of an order that is not released does nothing`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 2))))

        assertFalse(h.reReserve(placed.orderId))

        assertEquals(3, stockOf("market_product", product.id))
        assertEquals(ReservationState.HELD, reservationState(placed.orderId))
    }

    @Test
    fun `re-reserve with too little stock fails strictly and the order stays released`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 3)
        val coupon = w.fixtures.coupon(redeemLimit = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 3))), listOf(couponUse(coupon)))
        assertTrue(h.expire(placed.orderId))
        // somebody else buys the stock in the meantime
        h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 2))))
        assertEquals(1, stockOf("market_product", product.id))

        val failure = runCatching { h.reReserve(placed.orderId) }.exceptionOrNull()

        assertTrue(failure is OutOfStock, "expected OUT_OF_STOCK, got $failure")
        assertEquals(ReservationState.RELEASED, reservationState(placed.orderId))
        assertEquals(1, stockOf("market_product", product.id))
        assertEquals(0, usedCount("market_coupon", coupon.id))
        assertEquals(listOf("RELEASED"), redemptionStates(placed.orderId))
    }

    @Test
    fun `a forced re-reserve clamps the stock and leaves the codes alone`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 3)
        val coupon = w.fixtures.coupon(redeemLimit = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 3))), listOf(couponUse(coupon)))
        assertTrue(h.expire(placed.orderId))
        h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 2))))

        assertTrue(h.reReserve(placed.orderId, force = true))

        assertEquals(0, stockOf("market_product", product.id), "clamped at zero")
        assertEquals(listOf(1), stockReserved(placed.orderId), "the order holds what was there")
        assertEquals(0, usedCount("market_coupon", coupon.id), "codes and limits are not re-reserved by a forced accept")
        assertEquals(listOf("RELEASED"), redemptionStates(placed.orderId))
        assertEquals(ReservationState.HELD, reservationState(placed.orderId))
    }

    @Test
    fun `re-reserve counts the code again even over its limit, the buyer already paid the discounted price`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(redeemLimit = 1)
        val first = h.placeTx(emptyList(), listOf(couponUse(coupon)))
        assertTrue(h.expire(first.orderId))
        // the single use is taken by somebody else while the first order sits released
        val second = h.placeTx(emptyList(), listOf(couponUse(coupon)))

        assertTrue(h.reReserve(first.orderId))

        assertEquals(2, usedCount("market_coupon", coupon.id), "usedCount + 1 without the limit condition (06 section 7.4)")
        assertEquals(listOf("HELD"), redemptionStates(first.orderId))

        // I7 (usedCount <= redeemLimit) cannot hold in this state by design: the spec's re-reserve and I7 disagree. Leave a clean
        // picture for the invariant check after the test: release both orders again.
        assertTrue(h.expire(first.orderId))
        assertTrue(h.expire(second.orderId))
        assertEquals(0, usedCount("market_coupon", coupon.id))
    }

    @Test
    fun `re-reserve fails with CODE_NOT_FOUND when the coupon row is gone and a forced accept does not care`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon()
        val discount = w.fixtures.discount()
        val placed = h.placeTx(emptyList(), listOf(couponUse(coupon), CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 1, "EUR")))
        assertTrue(h.expire(placed.orderId))
        w.coupons.deleteById(coupon.id, pool)

        val failure = runCatching { h.reReserve(placed.orderId) }.exceptionOrNull()
        assertTrue(failure is InvalidCoupon)
        assertEquals("CODE_NOT_FOUND", body(failure!!).getString("reason"))
        assertEquals(ReservationState.RELEASED, reservationState(placed.orderId))
        assertEquals(0, usedCount("market_discount", discount.id), "the failed accept counted nothing")

        assertTrue(h.reReserve(placed.orderId, force = true))
        assertEquals(ReservationState.HELD, reservationState(placed.orderId))

        // a vanished discount alone is skipped, its row stays released
        val other = h.placeTx(emptyList(), listOf(CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 1, "EUR")))
        assertTrue(h.expire(other.orderId))
        w.discounts.deleteById(discount.id, pool)
        assertTrue(h.reReserve(other.orderId))
        assertEquals(listOf("RELEASED"), redemptionStates(other.orderId))
        assertTrue(h.expire(placed.orderId))
        assertTrue(h.expire(other.orderId))
    }

    // ===== locks ==========================================================================================================

    /** Whether another connection cannot take the row lock of `pano_<table>` row [id] right now (`FOR UPDATE NOWAIT`). */
    private suspend fun lockedByOthers(table: String, id: Long): Boolean {
        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()
        var errorCode: Int? = null
        var failure: Throwable? = null

        runCatching { conn.query("SELECT `id` FROM `pano_$table` WHERE `id` = $id FOR UPDATE NOWAIT").execute().coAwait() }
            .onFailure { if (it is MySQLException) errorCode = it.errorCode else failure = it }

        runCatching { tx.rollback().coAwait() }
        conn.close().coAwait()

        failure?.let { throw it }
        val code = errorCode ?: return false
        assertTrue(code == 1205 || code == 3572, "unexpected error $code")

        return true
    }

    @Test
    fun `scope RELEASE locks codes, discounts, products, variants and the order and nothing else`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val withVariants = w.fixtures.product(stock = null)
        val variant = w.fixtures.variant(withVariants, stock = 5)
        val bystander = w.fixtures.product(stock = 5)
        val coupon = w.fixtures.coupon()
        val creator = w.fixtures.creatorCode()
        val discount = w.fixtures.discount()
        val gift = w.fixtures.gift(redeemLimit = null)
        val placed = h.placeTx(
            listOf(ReservationHarness.Line(h.demand(product, 1)), ReservationHarness.Line(h.demand(withVariants, 1, variant))),
            listOf(
                couponUse(coupon), CodeUse(RedemptionKind.CREATOR_CODE, creator.id, creator.code, 1, "EUR"),
                CodeUse(RedemptionKind.GIFT, gift.id, gift.code, 0, "EUR"), CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 1, "EUR")
            )
        )

        w.db.tx { conn ->
            h.locks.forOrder(conn, placed.orderId, OrderLockScope.RELEASE) { locked ->
                assertEquals(OrderLockScope.RELEASE, locked.scope)
                assertEquals(2, locked.items.size)
                assertEquals(4, locked.redemptions.size)
                assertTrue(lockedByOthers("market_order", placed.orderId))
                assertTrue(lockedByOthers("market_coupon", coupon.id))
                assertTrue(lockedByOthers("market_creator_code", creator.id))
                assertTrue(lockedByOthers("market_gift", gift.id))
                assertTrue(lockedByOthers("market_discount", discount.id))
                assertTrue(lockedByOthers("market_product", product.id))
                assertTrue(lockedByOthers("market_product", withVariants.id))
                assertTrue(lockedByOthers("market_product_variant", variant.id))
                assertFalse(lockedByOthers("market_product", bystander.id), "a product the order does not hold is not locked")
            }
        }
        assertFalse(lockedByOthers("market_order", placed.orderId), "everything is released after the commit")
        assertTrue(h.expire(placed.orderId))
    }

    @Test
    fun `scope COMMIT locks the creator code and the products but not coupon, discount or variants, PAYMENT only the order`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 5)
        val coupon = w.fixtures.coupon()
        val creator = w.fixtures.creatorCode()
        val placed = h.placeTx(
            listOf(ReservationHarness.Line(h.demand(product, 1))),
            listOf(couponUse(coupon), CodeUse(RedemptionKind.CREATOR_CODE, creator.id, creator.code, 1, "EUR"))
        )
        Fixtures.setColumns(pool, "market_order", placed.orderId, mapOf("creatorCodeId" to creator.id))

        w.db.tx { conn ->
            h.locks.forOrder(conn, placed.orderId, OrderLockScope.COMMIT) {
                assertTrue(lockedByOthers("market_order", placed.orderId))
                assertTrue(lockedByOthers("market_creator_code", creator.id))
                assertTrue(lockedByOthers("market_product", product.id))
                assertFalse(lockedByOthers("market_coupon", coupon.id))
            }
        }

        w.db.tx { conn ->
            h.locks.forOrder(conn, placed.orderId, OrderLockScope.PAYMENT) {
                assertTrue(lockedByOthers("market_order", placed.orderId))
                assertFalse(lockedByOthers("market_creator_code", creator.id))
                assertFalse(lockedByOthers("market_product", product.id))
            }
        }
        assertTrue(h.expire(placed.orderId))
    }

    @Test
    fun `credit accounts are locked per scope in ascending id order and the order itself last`(): Unit = runBlocking {
        val user = w.fixtures.user()
        val hold = w.creditAccounts.getBySystemKey(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD, pool)!!
        val spent = w.creditAccounts.getBySystemKey(com.panomc.plugins.market.db.model.CreditSystemKey.SPENT, pool)!!
        val issuance = w.creditAccounts.getBySystemKey(com.panomc.plugins.market.db.model.CreditSystemKey.ISSUANCE, pool)!!
        val orderId = w.orders.add(
            MarketOrder(
                userId = user.id, playerUsername = user.username, buyerKey = "u:${user.id}", source = OrderSource.RENEWAL, creditAmount = 500,
                createdAt = w.clock.now(), updatedAt = w.clock.now(), paymentMethodId = "manual"
            ),
            pool
        )

        try {
            w.db.tx { conn ->
                h.locks.forOrder(conn, orderId, OrderLockScope.CREDIT) {
                    assertTrue(lockedByOthers("market_credit_account", user.accountId))
                    assertTrue(lockedByOthers("market_credit_account", hold.id))
                    assertFalse(lockedByOthers("market_credit_account", spent.id))
                }
            }
            w.db.tx { conn ->
                h.locks.forOrder(conn, orderId, OrderLockScope.COMMIT) {
                    assertTrue(lockedByOthers("market_credit_account", user.accountId))
                    assertTrue(lockedByOthers("market_credit_account", hold.id))
                    assertTrue(lockedByOthers("market_credit_account", spent.id))
                    assertFalse(lockedByOthers("market_credit_account", issuance.id), "ISSUANCE only when a cashback or grant can be posted")
                }
            }
            w.db.tx { conn ->
                h.locks.forOrder(conn, orderId, OrderLockScope.COMMIT, cashback = true) {
                    assertTrue(lockedByOthers("market_credit_account", issuance.id))
                }
            }
            w.db.tx { conn ->
                h.locks.forOrder(conn, orderId, OrderLockScope.RELEASE) {
                    assertTrue(lockedByOthers("market_credit_account", user.accountId))
                    assertTrue(lockedByOthers("market_credit_account", hold.id))
                    assertTrue(lockedByOthers("market_credit_account", spent.id))
                }
            }
            w.db.tx { conn ->
                h.locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) {
                    assertFalse(lockedByOthers("market_credit_account", user.accountId))
                }
            }
        } finally {
            sql("DELETE FROM `pano_market_order` WHERE `id` = ?", orderId)
        }
    }

    @Test
    fun `an order without credits locks no credit account in any scope`(): Unit = runBlocking {
        val user = w.fixtures.user()
        val hold = w.creditAccounts.getBySystemKey(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD, pool)!!
        val placed = h.placeTx(emptyList(), customer = com.panomc.plugins.market.service.CustomerKeys(user.id, "u:${user.id}", null, ""))

        w.db.tx { conn ->
            h.locks.forOrder(conn, placed.orderId, OrderLockScope.COMMIT) {
                assertFalse(lockedByOthers("market_credit_account", user.accountId))
                assertFalse(lockedByOthers("market_credit_account", hold.id))
            }
        }
        assertTrue(h.expire(placed.orderId))
    }

    // ----- credit account lock sets of COMMIT and RELEASE (06 section 13.2, 07 section 3.3) ----------------------------------

    /** The payer, the recipient, a bystander and the system accounts of a probe order, by name. */
    private class CreditLockWorld(val payer: TestUser, val recipient: TestUser, val accounts: Map<String, Long>)

    private suspend fun creditLockWorld(): CreditLockWorld {
        val payer = w.fixtures.user()
        val recipient = w.fixtures.user()
        val bystander = w.fixtures.user()
        val accounts = linkedMapOf("payer" to payer.accountId, "recipient" to recipient.accountId, "bystander" to bystander.accountId)

        for (key in listOf(CreditSystemKey.HOLD, CreditSystemKey.SPENT, CreditSystemKey.ISSUANCE, CreditSystemKey.REVOKED)) {
            accounts[key.name] = w.creditAccounts.getBySystemKey(key, pool)!!.id
        }

        return CreditLockWorld(payer, recipient, accounts)
    }

    /** An order row (and a `CREDIT_TOPUP` item when [granted] is set) that only the lock tests look at; remove it with [dropProbeOrder]. */
    private suspend fun probeOrder(world: CreditLockWorld, withRecipient: Boolean = false, held: Long = 0, granted: Long? = null): Long {
        val now = w.clock.now()
        val orderId = w.orders.add(
            MarketOrder(
                userId = world.payer.id, playerUsername = world.payer.username, buyerKey = "u:${world.payer.id}",
                source = if (held > 0) OrderSource.RENEWAL else OrderSource.STOREFRONT, creditAmount = held,
                recipientUserId = if (withRecipient) world.recipient.id else null, createdAt = now, updatedAt = now, paymentMethodId = "manual"
            ),
            pool
        )

        if (granted != null) {
            w.orderItems.add(MarketOrderItem(orderId = orderId, productName = "pack", kind = OrderItemKind.CREDIT_TOPUP, creditAmount = granted, createdAt = now, updatedAt = now), pool)
        }

        return orderId
    }

    private suspend fun dropProbeOrder(orderId: Long) {
        sql("DELETE FROM `pano_market_order_item` WHERE `orderId` = ?", orderId)
        sql("DELETE FROM `pano_market_order` WHERE `id` = ?", orderId)
    }

    /** The names of the accounts of [world] that another connection cannot lock while `forOrder(scope, cashback)` runs its block. */
    private suspend fun lockedAccounts(world: CreditLockWorld, orderId: Long, scope: OrderLockScope, cashback: Boolean = false): Set<String> {
        var locked: Set<String> = emptySet()

        w.db.tx { conn ->
            h.locks.forOrder(conn, orderId, scope, cashback) {
                locked = world.accounts.filter { lockedByOthers("market_credit_account", it.value) }.keys.toSet()
            }
        }

        return locked
    }

    @Test
    fun `RELEASE locks payer, ISSUANCE and REVOKED when the cashback flag is set on an order without credits`(): Unit = runBlocking {
        val world = creditLockWorld()
        val orderId = probeOrder(world)

        try {
            assertEquals(setOf("payer", "ISSUANCE", "REVOKED"), lockedAccounts(world, orderId, OrderLockScope.RELEASE, cashback = true))
            assertEquals(setOf("payer", "ISSUANCE"), lockedAccounts(world, orderId, OrderLockScope.COMMIT, cashback = true), "COMMIT never posts to REVOKED")
            assertEquals(emptySet<String>(), lockedAccounts(world, orderId, OrderLockScope.RELEASE), "no flag, no credits, no grants: nothing")
        } finally {
            dropProbeOrder(orderId)
        }
    }

    @Test
    fun `an order that grants credits locks ISSUANCE, payer and recipient under COMMIT and under RELEASE`(): Unit = runBlocking {
        val world = creditLockWorld()
        val gift = probeOrder(world, withRecipient = true, granted = 1_000)
        val own = probeOrder(world, granted = 1_000)
        val recipientOnly = probeOrder(world, withRecipient = true)

        try {
            for (cashback in listOf(false, true)) {
                assertEquals(setOf("payer", "recipient", "ISSUANCE"), lockedAccounts(world, gift, OrderLockScope.COMMIT, cashback), "COMMIT, gift pack, cashback=$cashback")
                assertEquals(
                    setOf("payer", "recipient", "ISSUANCE", "REVOKED"), lockedAccounts(world, gift, OrderLockScope.RELEASE, cashback),
                    "RELEASE, gift pack, cashback=$cashback"
                )
                assertEquals(setOf("payer", "ISSUANCE"), lockedAccounts(world, own, OrderLockScope.COMMIT, cashback), "COMMIT, own pack, cashback=$cashback")
                assertEquals(setOf("payer", "ISSUANCE", "REVOKED"), lockedAccounts(world, own, OrderLockScope.RELEASE, cashback), "RELEASE, own pack, cashback=$cashback")
            }

            for (scope in listOf(OrderLockScope.COMMIT, OrderLockScope.RELEASE)) {
                assertEquals(emptySet<String>(), lockedAccounts(world, recipientOnly, scope), "a recipient alone locks nothing: $scope")
            }
        } finally {
            dropProbeOrder(gift)
            dropProbeOrder(own)
            dropProbeOrder(recipientOnly)
        }
    }

    @Test
    fun `RELEASE locks every credit account COMMIT locks for each mix of hold, grant, recipient and cashback`(): Unit = runBlocking {
        val world = creditLockWorld()
        val orders = LinkedHashMap<String, Long>()

        try {
            for (held in listOf(0L, 500L)) {
                for (granted in listOf<Long?>(null, 1_000L)) {
                    for (withRecipient in listOf(false, true)) {
                        orders["held=$held granted=$granted recipient=$withRecipient"] = probeOrder(world, withRecipient, held, granted)
                    }
                }
            }

            for ((mix, orderId) in orders) {
                val holds = mix.contains("held=500")
                val grants = mix.contains("granted=1000")
                val recipient = mix.contains("recipient=true")

                for (cashback in listOf(false, true)) {
                    val commit = lockedAccounts(world, orderId, OrderLockScope.COMMIT, cashback)
                    val release = lockedAccounts(world, orderId, OrderLockScope.RELEASE, cashback)
                    val expected = buildSet {
                        if (holds || grants || cashback) add("payer")
                        if (holds) addAll(listOf("HOLD", "SPENT"))
                        if (grants || cashback) add("ISSUANCE")
                        if (grants && recipient) add("recipient")
                    }

                    assertEquals(expected, commit, "COMMIT: $mix cashback=$cashback")
                    assertEquals(if (grants || cashback) expected + "REVOKED" else expected, release, "RELEASE: $mix cashback=$cashback")
                    assertTrue(release.containsAll(commit), "RELEASE is a superset of COMMIT: $mix cashback=$cashback")
                    assertFalse("bystander" in release, "an account the order does not touch is never locked")
                }
            }
        } finally {
            orders.values.forEach { dropProbeOrder(it) }
        }
    }

    @Test
    fun `without credits, grants or cashback no scope but CREDIT locks a credit account`(): Unit = runBlocking {
        val world = creditLockWorld()
        val orderId = probeOrder(world, withRecipient = true)

        try {
            for (scope in listOf(OrderLockScope.PAYMENT, OrderLockScope.COMMIT, OrderLockScope.RELEASE)) {
                assertEquals(emptySet<String>(), lockedAccounts(world, orderId, scope), "$scope")
            }

            assertEquals(setOf("payer", "HOLD"), lockedAccounts(world, orderId, OrderLockScope.CREDIT), "CREDIT always locks the payer and HOLD (POST .../pay)")
        } finally {
            dropProbeOrder(orderId)
        }
    }

    @Test
    fun `orderWithSubscription locks the subscription row and the order`(): Unit = runBlocking {
        val subscriptionId = Fixtures.insertRaw(pool, "market_subscription", mapOf("providerId" to "fake", "gatewaySubscriptionId" to "sub-1"))
        val product = w.fixtures.product(stock = 5)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))))
        Fixtures.setColumns(pool, "market_order", placed.orderId, mapOf("subscriptionId" to subscriptionId))

        w.db.tx { conn ->
            h.locks.orderWithSubscription(conn, placed.orderId) { locked ->
                assertEquals(subscriptionId, locked.order.subscriptionId)
                assertTrue(lockedByOthers("market_subscription", subscriptionId))
                assertTrue(lockedByOthers("market_order", placed.orderId))
                assertFalse(lockedByOthers("market_product", product.id))
            }
        }
        // the other scopes (but PAYMENT) lock it too
        w.db.tx { conn ->
            h.locks.forOrder(conn, placed.orderId, OrderLockScope.CREDIT) { assertTrue(lockedByOthers("market_subscription", subscriptionId)) }
        }
        w.db.tx { conn ->
            h.locks.forOrder(conn, placed.orderId, OrderLockScope.PAYMENT) { assertFalse(lockedByOthers("market_subscription", subscriptionId)) }
        }
        Fixtures.setColumns(pool, "market_order", placed.orderId, mapOf("subscriptionId" to null))
        sql("DELETE FROM `pano_market_subscription` WHERE `id` = ?", subscriptionId)
        assertTrue(h.expire(placed.orderId))
    }

    @Test
    fun `child rows are locked in the order of the table list after the order row`(): Unit = runBlocking {
        val placed = h.placeTx(emptyList())
        val paymentId = Fixtures.insertRaw(pool, "market_payment", mapOf("orderId" to placed.orderId, "providerId" to "fake", "reference" to "ref-1", "token" to "tok-1"))

        try {
            w.db.tx { conn ->
                h.locks.forOrder(conn, placed.orderId, OrderLockScope.PAYMENT) {
                    val ids = h.locks.children(conn, placed.orderId, OrderChild.SHIPMENT, OrderChild.PAYMENT)

                    assertEquals(listOf(OrderChild.PAYMENT, OrderChild.SHIPMENT), ids.keys.toList(), "locked in the declared order, not the argument order")
                    assertEquals(listOf(paymentId), ids[OrderChild.PAYMENT])
                    assertEquals(emptyList<Long>(), ids[OrderChild.SHIPMENT])
                    assertTrue(lockedByOthers("market_payment", paymentId))
                }
            }
        } finally {
            sql("DELETE FROM `pano_market_payment` WHERE `id` = ?", paymentId)
        }
        assertTrue(h.expire(placed.orderId))
    }

    @Test
    fun `the cart row of a user is level 0`(): Unit = runBlocking {
        val cartId = w.carts.ensure(4242, w.clock.now(), pool)

        w.db.tx { conn ->
            assertEquals(cartId, h.locks.cart(conn, 4242))
            assertTrue(lockedByOthers("market_cart", cartId))
            assertNull(h.locks.cart(conn, 999_999))
        }
        w.carts.deleteById(cartId, pool)
    }

    @Test
    fun `codes locks coupons then creator codes then gifts then discounts and reports only rows that exist`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(redeemLimit = 4, customerRedeemLimit = 2)
        val discount = w.fixtures.discount(usageLimit = 9)

        w.db.tx { conn ->
            val found = h.locks.codes(
                conn,
                listOf(CodeRef(RedemptionKind.DISCOUNT, discount.id), CodeRef(RedemptionKind.COUPON, coupon.id), CodeRef(RedemptionKind.GIFT, 123_456))
            )

            assertEquals(listOf(CodeRef(RedemptionKind.COUPON, coupon.id), CodeRef(RedemptionKind.DISCOUNT, discount.id)), found.keys.toList())
            val locked = found.getValue(CodeRef(RedemptionKind.COUPON, coupon.id))
            assertEquals(4, locked.limit)
            assertEquals(2, locked.customerLimit)
            assertEquals(0, locked.usedCount)
            assertNull(locked.deletedAt)
            assertEquals(9, found.getValue(CodeRef(RedemptionKind.DISCOUNT, discount.id)).limit)
            assertNull(found.getValue(CodeRef(RedemptionKind.DISCOUNT, discount.id)).customerLimit)
        }
    }

    // ===== order changed ==================================================================================================

    /** Holds the order row on its own connection, runs [whileBlocked] once [waiter] is blocked on that lock, then commits. */
    private suspend fun <T> changeOrderWhileLocking(orderId: Long, waiter: suspend () -> T): T {
        val blocker = pool.connection.coAwait()
        val tx = blocker.begin().coAwait()

        val outcome = runCatching {
            blocker.query("SELECT `id` FROM `pano_market_order` WHERE `id` = $orderId FOR UPDATE").execute().coAwait()
            val running = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { waiter() }

            // wait until the waiter's order lock statement is executing: its unlocked read is done and, because the blocker holds
            // the row, the statement cannot finish before the blocker commits
            val blocked = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                while (sql(
                        "SELECT COUNT(*) AS c FROM information_schema.PROCESSLIST WHERE COMMAND = 'Execute' AND INFO LIKE 'SELECT `status`, `reservationState`, `updatedAt`%FOR UPDATE'"
                    ).single().getLong("c") < 1
                ) delay(10)
                true
            }

            check(blocked == true) { "the waiter never reached the order lock: completed=${running.isCompleted}" }

            blocker.preparedQuery("UPDATE `pano_market_order` SET `status` = 'REVIEW', `updatedAt` = `updatedAt` + 7 WHERE `id` = ?").execute(Tuple.of(orderId)).coAwait()
            tx.commit().coAwait()

            running.await()
        }

        runCatching { tx.rollback().coAwait() }
        blocker.close().coAwait()

        return outcome.getOrThrow()
    }

    @Test
    fun `an order changed between the unlocked read and the lock is an OrderChangedException`(): Unit = runBlocking {
        val placed = h.placeTx(emptyList())

        val outcome = changeOrderWhileLocking(placed.orderId) {
            runCatching { w.db.tx { conn -> h.locks.forOrder(conn, placed.orderId, OrderLockScope.PAYMENT) { "locked" } } }
        }

        val failure = outcome.exceptionOrNull()
        assertTrue(failure is OrderChangedException, "expected OrderChangedException, got $failure")
        assertEquals(placed.orderId, (failure as OrderChangedException).orderId)
        assertTrue(failure.detail.contains("status") && failure.detail.contains("updatedAt"), failure.detail)

        // the use case that restarts on the new state decides on REVIEW
        Fixtures.setColumns(pool, "market_order", placed.orderId, mapOf("status" to "PENDING"))
        assertTrue(h.expire(placed.orderId))
    }

    @Test
    fun `a use case restarts after an order change and decides on the new state`(): Unit = runBlocking {
        val placed = h.placeTx(emptyList())
        val runs = AtomicInteger()

        val seen = changeOrderWhileLocking(placed.orderId) {
            w.db.txRestartingOnOrderChange { conn ->
                runs.incrementAndGet()
                h.locks.forOrder(conn, placed.orderId, OrderLockScope.PAYMENT) { it.order.status }
            }
        }

        assertEquals(OrderStatus.REVIEW, seen, "the second run read the committed change")
        assertEquals(2, runs.get())
        Fixtures.setColumns(pool, "market_order", placed.orderId, mapOf("status" to "PENDING"))
        assertTrue(h.expire(placed.orderId))
    }

    @Test
    fun `a use case that keeps meeting changes gives up after three runs with MarketBusyException`(): Unit = runBlocking {
        val runs = AtomicInteger()

        val failure = runCatching {
            w.db.txRestartingOnOrderChange { _ ->
                runs.incrementAndGet()
                throw OrderChangedException(1, "always")
            }
        }.exceptionOrNull()

        assertTrue(failure is MarketBusyException)
        assertEquals(3, (failure as MarketBusyException).attempts)
        assertEquals(3, runs.get())
    }

    @Test
    fun `an order that does not exist is a NoSuchElementException`(): Unit = runBlocking {
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { w.db.tx { conn -> h.locks.forOrder(conn, 777_777, OrderLockScope.PAYMENT) { 1 } } }
        }
    }

    @Test
    fun `release writes a strictly increasing updatedAt and the lock sees the next version`(): Unit = runBlocking {
        val product = w.fixtures.product(stock = 2)
        val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))))
        val before = w.orders.getById(placed.orderId, pool)!!.updatedAt

        // the clock stands still: the version still has to move
        assertTrue(h.expire(placed.orderId))

        assertTrue(w.orders.getById(placed.orderId, pool)!!.updatedAt > before)
    }
}

/**
 * Builds orders the way the order transaction does and runs the transitions of the reservation, for [ReservationServiceIT]
 * and [ReservationRaceIT]: the services under test are the real ones on the wiring's DAOs and database.
 */
internal class ReservationHarness(val w: TestWiring) {
    val locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
    val redemptions = RedemptionService(w.clock, locks, w.redemptions)
    val reservations = ReservationService(w.clock, locks, redemptions, w.orders)
    private val seq = AtomicLong()

    /** One order item to create: its stock demand, kind and (for a bundle child) the `itemKey` of its bundle line. */
    class Line(val demand: StockDemand, val kind: OrderItemKind = OrderItemKind.PRODUCT, val parentItemKey: String? = null)

    class Placed(val orderId: Long, val itemIds: Map<String, Long>, val reservation: Reservation)

    fun demand(product: MarketProduct, quantity: Int = 1, variant: MarketProductVariant? = null, key: String = "L${seq.incrementAndGet()}") =
        StockDemand(key, key, product.id, variant?.id, quantity)

    fun customer(n: Long = seq.incrementAndGet(), email: String? = null, recipientKey: String = "") =
        CustomerKeys(userId = null, buyerKey = "g:buyer$n", email = email, recipientKey = recipientKey)

    /** B8 to B11 in the caller's transaction: reserve stock and codes, insert the order, its items and the redemption rows. */
    suspend fun place(
        conn: SqlConnection,
        lines: List<Line>,
        uses: List<CodeUse> = emptyList(),
        customer: CustomerKeys = customer(),
        testMode: Boolean = false,
        force: Boolean = false
    ): Placed {
        val reservation = reservations.reserve(conn, lines.map { it.demand }, uses, customer, force)
        val now = w.clock.now()
        val orderId = w.orders.add(
            MarketOrder(
                userId = customer.userId, playerUsername = customer.buyerKey.removePrefix("g:").removePrefix("u:"), currency = "EUR",
                paymentMethodId = "manual", paymentLabel = "manual", status = OrderStatus.PENDING, createdAt = now, updatedAt = now,
                source = OrderSource.STOREFRONT, buyerKey = customer.buyerKey, email = customer.email, recipientKey = customer.recipientKey,
                reservationState = ReservationState.HELD, testMode = testMode,
                couponId = uses.firstOrNull { it.kind == RedemptionKind.COUPON }?.refId,
                creatorCodeId = uses.firstOrNull { it.kind == RedemptionKind.CREATOR_CODE }?.refId
            ),
            conn
        )
        val itemIds = LinkedHashMap<String, Long>()

        for (line in lines) {
            itemIds[line.demand.itemKey] = w.orderItems.add(
                MarketOrderItem(
                    orderId = orderId, productId = line.demand.productId, productName = "p${line.demand.productId}", quantity = line.demand.quantity,
                    kind = line.kind, parentItemId = line.parentItemKey?.let { itemIds.getValue(it) }, variantId = line.demand.variantId,
                    stockReserved = reservation.stockReserved.getValue(line.demand.itemKey), createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        redemptions.record(conn, orderId, uses, customer)

        return Placed(orderId, itemIds, reservation)
    }

    suspend fun placeTx(
        lines: List<Line>,
        uses: List<CodeUse> = emptyList(),
        customer: CustomerKeys = customer(),
        testMode: Boolean = false,
        force: Boolean = false
    ): Placed = w.db.tx { conn -> place(conn, lines, uses, customer, testMode, force) }

    /** O6 as the order service will do it: release under `RELEASE`, and when a row changed the order becomes `EXPIRED`. */
    suspend fun expire(orderId: Long): Boolean = w.db.txRestartingOnOrderChange { conn ->
        locks.forOrder(conn, orderId, OrderLockScope.RELEASE) { locked ->
            val released = reservations.release(conn, locked)

            if (released) setStatus(conn, orderId, OrderStatus.EXPIRED)

            released
        }
    }

    /** O2: commit under `COMMIT`, and when a row changed the order is `COMPLETED` with `paidAt`. */
    suspend fun complete(orderId: Long): Boolean = w.db.txRestartingOnOrderChange { conn ->
        locks.forOrder(conn, orderId, OrderLockScope.COMMIT) { locked ->
            val committed = reservations.commit(conn, locked)

            if (committed) setStatus(conn, orderId, OrderStatus.COMPLETED, paid = true)

            committed
        }
    }

    /** O4's first half: re-reserve under `RELEASE`; the order goes back to `REVIEW` (where an accept finds it). */
    suspend fun reReserve(orderId: Long, force: Boolean = false): Boolean = w.db.txRestartingOnOrderChange { conn ->
        locks.forOrder(conn, orderId, OrderLockScope.RELEASE) { locked ->
            val reserved = reservations.reReserve(conn, locked, force)

            if (reserved) setStatus(conn, orderId, OrderStatus.REVIEW)

            reserved
        }
    }

    suspend fun setStatus(conn: SqlConnection, orderId: Long, status: OrderStatus, paid: Boolean = false) {
        conn.preparedQuery(
            "UPDATE `${w.orders.prefix()}market_order` SET `status` = ?, `paidAt` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ?"
        ).execute(Tuple.of(status.name, if (paid) w.clock.now() else null, w.clock.now(), orderId)).coAwait()
    }
}
