package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.error.CooldownActive
import com.panomc.plugins.market.error.IdempotencyConflict
import com.panomc.plugins.market.error.InvalidCoupon
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.error.OutOfStock
import com.panomc.plugins.market.error.PriceChanged
import com.panomc.plugins.market.error.PurchaseLimitReached
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.DiscountUnit
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The checkout under contention on a real MariaDB (MK-075; twins of R-04 to R-07, R-10 to R-12, R-23 of 17 section 9.4 and
 * test 33 of 06 section 16): 20 actors released together by [Race], five rounds per case with fresh rows, through the whole
 * `CheckoutService.checkout` (phase A, the locks, the price again, the reservation, the inserts). Every case demands that no
 * deadlock or lock wait timeout surfaces (a [MarketBusyException] is a failed run) and the invariants I1 to I22 hold after it.
 *
 * R-11 follows 06 section 6.4 (an unpaid gift of a stranger does not use the recipient's limit): exactly one order of the
 * recipient himself wins, a gift of somebody else is let through only while no order of the recipient exists yet.
 */
class CheckoutRaceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: CheckoutHarness
    private val vertx: Vertx = Vertx.vertx()

    private val actors = 20

    // a 20 actor race holds 20 transactions at once; the pool must not be the thing that serialises them
    override val poolSize: Int = 24

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        h = CheckoutHarness(w, vertx)
    }

    private val fx get() = w.fixtures

    private fun line(product: MarketProduct, quantity: Int = 1, variant: Long = 0) = h.line(product, quantity, variant)

    private suspend fun buyer(name: String): QuoteCaller {
        val u: TestUser = fx.user(name)

        h.emails[u.id] = "$name@example.com".lowercase()

        return QuoteCaller(u.id)
    }

    private suspend fun buyers(prefix: String, n: Int = actors) = List(n) { buyer("$prefix$it") }

    private suspend fun stockOf(table: String, id: Long): Int? = sql("SELECT `stock` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("stock")

    private suspend fun usedCount(table: String, id: Long): Int = sql("SELECT `usedCount` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("usedCount")

    /** [winners] successes, every other run failed with [loser], and no run died of a deadlock or a lock wait timeout. */
    private fun <T> verify(results: List<Result<T>>, winners: Int, loser: Class<out Throwable>) {
        val failures = results.mapNotNull { it.exceptionOrNull() }

        assertEquals(0, failures.count { it is MarketBusyException }, "no deadlock or lock wait timeout may surface: $failures")
        assertTrue(failures.all { loser.isInstance(it) }, "unexpected failures: ${failures.filterNot { loser.isInstance(it) }}")
        assertEquals(winners, results.count { it.isSuccess }, "winners")
        assertEquals(results.size - winners, failures.size)
    }

    private fun noBusy(results: List<Result<*>>) {
        val failures = results.mapNotNull { it.exceptionOrNull() }

        assertEquals(0, failures.count { it is MarketBusyException }, "no deadlock or lock wait timeout may surface: $failures")
    }

    /** What the expiry job will do at O6 (MK-078), done by hand: the reservation goes back and the order is `EXPIRED`. */
    private suspend fun expire(orderId: Long) {
        val locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
        val redemptions = RedemptionService(w.clock, locks, w.redemptions)
        val reservations = ReservationService(w.clock, locks, redemptions, w.orders)

        w.db.tx { conn -> locks.forOrder(conn, orderId, OrderLockScope.RELEASE) { locked -> reservations.release(conn, locked) } }
        sql("UPDATE `pano_market_order` SET `status` = 'EXPIRED' WHERE `id` = ?", orderId)
    }

    private suspend fun method() = fx.paymentMethod("fake")

    private fun body(vararg pairs: Pair<String, Any?>) = h.body(*(pairs.toList() + ("paymentMethodId" to "fake")).toTypedArray())

    // ------------------------------------------------------------------------------------------------------------ R-04, R-05

    @Test
    fun `R-04 the last stock unit has exactly one winner, and the winner expiring gives it back`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "last-$round", price = 500, stock = 1)
            val who = buyers("r04r$round-")
            val results = Race.run(actors) { i -> h.checkout(body("items" to listOf(line(product))), caller = who[i]) }

            verify(results, 1, OutOfStock::class.java)
            assertEquals(0, stockOf("market_product", product.id))
            assertEquals(1, count("market_order_item", "`productId` = ? AND `stockReserved` = 1", product.id), "sum of stockReserved is 1")
            assertEquals(1, count("market_order_item", "`productId` = ?", product.id))

            val winner = results.first { it.isSuccess }.getOrThrow()

            expire(w.orders.getByPublicId(winner.order.getString("publicId"), pool)!!.id)
            assertEquals(1, stockOf("market_product", product.id), "the winner expires: the unit is back")
        }
    }

    @Test
    fun `R-04 every loser is told which line is out of stock`(): Unit = runBlocking {
        method()

        val product = fx.product(price = 500, stock = 1)
        val who = buyers("r04l-")
        val results = Race.run(actors) { i -> h.checkout(body("items" to listOf(line(product))), caller = who[i]) }

        verify(results, 1, OutOfStock::class.java)

        for (failure in results.mapNotNull { it.exceptionOrNull() }) {
            assertEquals(listOf(com.panomc.plugins.market.core.cart.CartLine(product.id, 0, 1, emptyMap(), null).lineKey), JsonObjectOf(failure).getJsonArray("lines").map { it.toString() })
        }
    }

    @Test
    fun `R-05 a variant with stock two has exactly two winners and the product stock is ignored`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "crate-$round", price = 400, stock = 0)
            val variant = fx.variant(product, "S", price = 400, stock = 2)
            val who = buyers("r05r$round-")
            val results = Race.run(actors) { i -> h.checkout(body("items" to listOf(line(product, 1, variant.id))), caller = who[i]) }

            verify(results, 2, OutOfStock::class.java)
            assertEquals(0, stockOf("market_product_variant", variant.id))
            assertEquals(0, stockOf("market_product", product.id))
        }
    }

    // ------------------------------------------------------------------------------------------------------------ R-06, R-07

    @Test
    fun `R-06 a coupon with redeemLimit three is held by exactly three orders`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "item-$round", price = 1000)
            val coupon = fx.coupon("C$round", DiscountUnit.PERCENT, 1000, redeemLimit = 3)
            val who = buyers("r06r$round-")
            val results = Race.run(actors) { i -> h.checkout(body("items" to listOf(line(product)), "couponCode" to coupon.code), caller = who[i]) }

            verify(results, 3, InvalidCoupon::class.java)

            for (failure in results.mapNotNull { it.exceptionOrNull() }) assertEquals("CODE_LIMIT_REACHED", JsonObjectOf(failure).getString("reason"))

            assertEquals(3, usedCount("market_coupon", coupon.id))
            assertEquals(3, count("market_redemption", "`kind` = 'COUPON' AND `refId` = ? AND `state` = 'HELD'", coupon.id))
            assertEquals(3, count("market_order", "`couponId` = ?", coupon.id))
        }
    }

    @Test
    fun `R-07 one customer with customerRedeemLimit one gets the coupon once out of eight parallel checkouts`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "item-$round", price = 1000)
            val coupon = fx.coupon("PC$round", DiscountUnit.PERCENT, 1000, redeemLimit = null, customerRedeemLimit = 1)
            val one = buyer("r07r$round")
            val results = Race.run(8) { h.checkout(body("items" to listOf(line(product)), "couponCode" to coupon.code), caller = one) }

            verify(results, 1, InvalidCoupon::class.java)
            assertEquals(1, usedCount("market_coupon", coupon.id))
            assertEquals(1, count("market_redemption", "`kind` = 'COUPON' AND `refId` = ? AND `state` = 'HELD'", coupon.id))
        }
    }

    // ----------------------------------------------------------------------------------------------------------------- R-10

    @Test
    fun `R-10 the same Idempotency-Key ten times at once is one order and ten identical answers, also when the stock is one`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "once-$round", price = 500, stock = 1)
            val me = buyer("r10r$round")
            val body = body("items" to listOf(line(product)))
            val key = "race-key-${round.toString().padStart(11, '0')}"
            val startedBefore = h.starter.started.size
            val results = Race.run(10) { h.checkout(body, key = key, caller = me) }

            noBusy(results)
            assertEquals(emptyList<Throwable>(), results.mapNotNull { it.exceptionOrNull() }, "every one of the ten requests is answered, the replays included")
            assertEquals(1, results.map { it.getOrThrow().order.getString("publicId") }.toSet().size, "all ten answers carry the same publicId")
            assertEquals(1, results.map { it.getOrThrow().orderToken }.toSet().size)
            assertEquals(1, count("market_order", "`idempotencyKey` = ?", key))
            assertEquals(0, stockOf("market_product", product.id))
            assertEquals(startedBefore + 1, h.starter.started.size, "one attempt was started at the gateway")
            assertEquals(1, count("market_payment", "`orderId` = (SELECT `id` FROM `pano_market_order` WHERE `idempotencyKey` = ?)", key))
        }
    }

    @Test
    fun `R-10 the same key with another body is IDEMPOTENCY_CONFLICT for every request`(): Unit = runBlocking {
        method()

        val product = fx.product(price = 500, stock = 20)
        val me = buyer("r10c")
        val key = "conflict-key-0000001"

        h.checkout(body("items" to listOf(line(product, 1))), key = key, caller = me)

        val results = Race.run(10) { h.checkout(body("items" to listOf(line(product, 2))), key = key, caller = me) }

        verify(results, 0, IdempotencyConflict::class.java)
        assertEquals(1, count("market_order"))
        assertEquals(19, stockOf("market_product", product.id))
    }

    @Test
    fun `R-10 the same key at once on a cart without product locks is settled by the unique index, one order and identical answers`(): Unit = runBlocking {
        method()
        h.config = h.config.copy(creditTopUpEnabled = true, creditTopUpFreeAmount = true, creditTopUpMin = 1.0, creditTopUpMax = 1000.0)

        repeat(Race.rounds) { round ->
            val me = buyer("r10t$round")
            val body = body("creditTopUp" to 12.5)
            val key = "topup-key-${round.toString().padStart(10, '0')}"
            val results = Race.run(10) { h.checkout(body, key = key, caller = me) }

            noBusy(results)
            assertEquals(emptyList<Throwable>(), results.mapNotNull { it.exceptionOrNull() })
            assertEquals(1, results.map { it.getOrThrow().order.getString("publicId") }.toSet().size)
            assertEquals(1, count("market_order", "`idempotencyKey` = ?", key))
            assertEquals(1, count("market_payment", "`orderId` = (SELECT `id` FROM `pano_market_order` WHERE `idempotencyKey` = ?)", key))
            assertEquals(1, count("market_order_item", "`orderId` = (SELECT `id` FROM `pano_market_order` WHERE `idempotencyKey` = ?)", key))
        }
    }

    // ------------------------------------------------------------------------------------------------------------ R-11, R-12

    @Test
    fun `R-11 limitPerPlayer one for one recipient admits exactly one order of the recipient himself`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "once-$round", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("limitPerPlayer" to 1)) }
            val recipient = fx.user("rec$round").also { h.emails[it.id] = "rec$round@example.com" }
            val own = List(5) { QuoteCaller(recipient.id) }
            val strangers = buyers("r11s$round-", 5)
            val results = Race.run(10) { i ->
                if (i < 5) {
                    h.checkout(body("items" to listOf(line(product))), caller = own[i])
                } else {
                    h.checkout(body("items" to listOf(line(product)), "recipientUsername" to recipient.username), caller = strangers[i - 5])
                }
            }

            noBusy(results)

            val failures = results.mapNotNull { it.exceptionOrNull() }

            assertTrue(failures.all { it is PurchaseLimitReached }, "unexpected failures: ${failures.filterNot { it is PurchaseLimitReached }}")
            assertEquals(1, results.take(5).count { it.isSuccess }, "exactly one order of the recipient himself")

            // 06 section 6.4: gifts of strangers that were placed before the recipient's order do not count (they are re-checked at payment)
            val held = count("market_order", "`recipientKey` = ? AND `reservationState` = 'HELD'", "u:${recipient.id}")

            assertEquals(1 + results.drop(5).count { it.isSuccess }.toLong(), held)
            assertEquals(1, count("market_order", "`recipientKey` = ? AND `buyerKey` = ?", "u:${recipient.id}", "u:${recipient.id}"))
        }
    }

    @Test
    fun `R-12 a cooldown of an hour lets one of six parallel checkouts through, the rest are told when to come back`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "cool-$round", price = 100).also { Fixtures.setColumns(pool, "market_product", it.id, mapOf("cooldownSeconds" to 3600)) }
            val me = buyer("r12r$round")
            val results = Race.run(6) { h.checkout(body("items" to listOf(line(product))), caller = me) }

            verify(results, 1, CooldownActive::class.java)

            for (failure in results.mapNotNull { it.exceptionOrNull() }) {
                val wait = JsonObjectOf(failure).getLong("retryAfter")

                assertTrue(wait in 1..3600, "retryAfter $wait")
                assertEquals(product.id, JsonObjectOf(failure).getLong("productId"))
            }

            assertEquals(1, count("market_order", "`buyerKey` = ?", "u:${me.userId}"))
        }
    }

    // ----------------------------------------------------------------------------------------------------------------- R-23

    @Test
    fun `R-23 a stock adjustment racing nineteen checkouts leaves exactly stock minus adjustment minus successes`(): Unit = runBlocking {
        method()

        repeat(Race.rounds) { round ->
            val product = fx.product(slug = "adj-$round", price = 100, stock = 3)
            val who = buyers("r23r$round-", 19)
            val results = Race.run(actors) { i ->
                if (i == 0) {
                    // `POST /products/:id/stock {mode: ADJUST, value: -1}`: the product row lock, then the guarded statement
                    w.db.tx { conn ->
                        w.products.getByIdForUpdate(product.id, conn)

                        w.products.adjustStock(product.id, -1, conn)
                    }
                } else {
                    h.checkout(body("items" to listOf(line(product))), caller = who[i - 1])
                }
            }

            noBusy(results)

            val adjusted = results[0].getOrThrow() as Boolean
            val failures = results.drop(1).mapNotNull { it.exceptionOrNull() }
            val successes = results.drop(1).count { it.isSuccess }

            assertTrue(failures.all { it is OutOfStock }, "unexpected failures: ${failures.filterNot { it is OutOfStock }}")

            val stock = stockOf("market_product", product.id)!!

            assertEquals(3 - (if (adjusted) 1 else 0) - successes, stock)
            assertTrue(stock >= 0)
            assertEquals(successes.toLong(), count("market_order_item", "`productId` = ? AND `stockReserved` = 1", product.id))
        }
    }

    // ----------------------------------------------------------------------------------------- lock order, test 33 of 06

    @Test
    fun `checkouts of products one and two against products two and one never deadlock and keep the totals consistent`(): Unit = runBlocking {
        method()

        val one = fx.product(slug = "p1", price = 100, stock = 1000)
        val two = fx.product(slug = "p2", price = 200, stock = 1000)
        val pairs = 20
        val who = buyers("lock-", pairs * 2)
        val results = Race.run(pairs * 2) { i ->
            val items = if (i % 2 == 0) listOf(line(one), line(two)) else listOf(line(two), line(one))

            h.checkout(body("items" to items), caller = who[i])
        }

        noBusy(results)
        assertEquals(emptyList<Throwable>(), results.mapNotNull { it.exceptionOrNull() })
        assertEquals(1000 - pairs * 2, stockOf("market_product", one.id))
        assertEquals(1000 - pairs * 2, stockOf("market_product", two.id))
        assertEquals(pairs * 2L, count("market_order", "`totalPrice` = 300"))
        assertEquals(pairs * 4L, count("market_order_item"))
    }

    @Test
    fun `twenty checkouts that each need the same discount, coupon and product keep every counter exact`(): Unit = runBlocking {
        method()

        val product = fx.product(price = 1000, stock = 100)
        val discount = fx.discount(value = 1000, unit = DiscountUnit.PERCENT, usageLimit = 12)
        val coupon = fx.coupon("MANY", DiscountUnit.PERCENT, 500, redeemLimit = null)
        val who = buyers("many-")
        val results = Race.run(actors) { i -> h.checkout(body("items" to listOf(line(product)), "couponCode" to coupon.code), caller = who[i]) }

        noBusy(results)

        val failures = results.mapNotNull { it.exceptionOrNull() }

        // a price that moves twice under one request (the discount ran out while it re-ran) is the buyer's to confirm again
        assertTrue(failures.all { it is PriceChanged }, "unexpected failures: ${failures.filterNot { it is PriceChanged }}")

        val orders = results.count { it.isSuccess }

        assertTrue(orders >= 12, "the 12 uses of the discount at least went through: $orders")
        assertEquals(orders, usedCount("market_coupon", coupon.id))
        assertEquals(100 - orders, stockOf("market_product", product.id))
        assertEquals(minOf(orders, 12), usedCount("market_discount", discount.id), "never above the usage limit")
        assertEquals(orders.toLong(), count("market_redemption", "`kind` = 'COUPON' AND `state` = 'HELD'"))
        assertEquals(minOf(orders, 12).toLong(), count("market_redemption", "`kind` = 'DISCOUNT' AND `state` = 'HELD'"))
    }
}

/** The error body of a failure as JSON (the extras of a market error). */
private fun JsonObjectOf(failure: Throwable): io.vertx.core.json.JsonObject = io.vertx.core.json.JsonObject((failure as Error).encode())
