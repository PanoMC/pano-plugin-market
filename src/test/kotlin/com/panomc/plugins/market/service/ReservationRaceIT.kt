package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketCoupon
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.error.InvalidCoupon
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.error.OutOfStock
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reservation under contention on a real MariaDB (MK-073, 06 section 13.3; twins of R-04 to R-07, R-10 to R-12): 20
 * actors released together by [Race], five rounds per case with fresh rows. Every case also demands that no deadlock or
 * lock wait timeout surfaces (a [MarketBusyException] is a failed run) and the invariants of the base class hold after it.
 */
class ReservationRaceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val h by lazy { ReservationHarness(w) }

    private val actors = 20

    // a 20 actor race holds 20 transactions at once; the pool must not be the thing that serialises them
    override val poolSize: Int = 24

    private suspend fun stockOf(table: String, id: Long): Int? = sql("SELECT `stock` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("stock")

    private suspend fun usedCount(table: String, id: Long): Int = sql("SELECT `usedCount` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("usedCount")

    private fun couponUse(c: MarketCoupon) = CodeUse(RedemptionKind.COUPON, c.id, c.code, 100, "EUR")

    /** [winners] successes, every other run failed with [loser], and no run died of a deadlock or a lock wait timeout. */
    private fun <T> verify(results: List<Result<T>>, winners: Int, loser: Class<out Throwable>) {
        val failures = results.mapNotNull { it.exceptionOrNull() }

        assertEquals(0, failures.count { it is MarketBusyException }, "no deadlock or lock wait timeout may surface: $failures")
        assertTrue(failures.all { loser.isInstance(it) }, "unexpected failures: ${failures.filterNot { loser.isInstance(it) }}")
        assertEquals(winners, results.count { it.isSuccess }, "winners")
        assertEquals(results.size - winners, failures.size)
    }

    private fun <T> verifyAllSucceeded(results: List<Result<T>>) = verify(results, results.size, Throwable::class.java)

    @Test
    fun `the last stock unit has exactly one winner`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 1)

            val results = Race.run(actors) { h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1)))) }

            verify(results, 1, OutOfStock::class.java)
            assertEquals(0, stockOf("market_product", product.id))
            assertEquals(1, count("market_order_item", "`productId` = ? AND `stockReserved` = 1", product.id))
        }
    }

    @Test
    fun `variant stock two has exactly two winners and the product stock is ignored`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 0)
            val variant = w.fixtures.variant(product, stock = 2)

            val results = Race.run(actors) { h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1, variant)))) }

            verify(results, 2, OutOfStock::class.java)
            assertEquals(0, stockOf("market_product_variant", variant.id))
            assertEquals(0, stockOf("market_product", product.id))
        }
    }

    @Test
    fun `a stock of five with orders of two has exactly two winners`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 5)

            val results = Race.run(actors) { h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 2)))) }

            verify(results, 2, OutOfStock::class.java)
            assertEquals(1, stockOf("market_product", product.id))
        }
    }

    @Test
    fun `a coupon with redeemLimit three has exactly three winners`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val coupon = w.fixtures.coupon(redeemLimit = 3)

            val results = Race.run(actors) { h.placeTx(emptyList(), listOf(couponUse(coupon))) }

            verify(results, 3, InvalidCoupon::class.java)
            assertEquals(3, usedCount("market_coupon", coupon.id))
            assertEquals(3, count("market_redemption", "`kind` = 'COUPON' AND `refId` = ? AND `state` = 'HELD'", coupon.id))
        }
    }

    @Test
    fun `a creator code, a gift code and a discount keep their limits under contention`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val creator = w.fixtures.creatorCode()
            Fixtures.setColumns(pool, "market_creator_code", creator.id, mapOf("redeemLimit" to 3))
            val gift = w.fixtures.gift(redeemLimit = 3)
            Fixtures.setColumns(pool, "market_gift", gift.id, mapOf("customerRedeemLimit" to null))
            val discount = w.fixtures.discount(usageLimit = 3)

            val creatorResults = Race.run(actors) { h.placeTx(emptyList(), listOf(CodeUse(RedemptionKind.CREATOR_CODE, creator.id, creator.code, 1, "EUR"))) }
            val giftResults = Race.run(actors) { h.placeTx(emptyList(), listOf(CodeUse(RedemptionKind.GIFT, gift.id, gift.code, 0, "EUR"))) }
            val discountResults = Race.run(actors) { h.placeTx(emptyList(), listOf(CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 1, "EUR"))) }

            verify(creatorResults, 3, com.panomc.plugins.market.error.InvalidCreatorCode::class.java)
            verify(giftResults, 3, com.panomc.plugins.market.error.InvalidGiftCode::class.java)
            verify(discountResults, 3, DiscountUnavailable::class.java)
            assertEquals(3, usedCount("market_creator_code", creator.id))
            assertEquals(3, usedCount("market_gift", gift.id))
            assertEquals(3, usedCount("market_discount", discount.id))
        }
    }

    @Test
    fun `customerRedeemLimit one lets a customer through once, by payer key, by e-mail and by recipient`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val coupon = w.fixtures.coupon(redeemLimit = null, customerRedeemLimit = 1)

            // one payer
            val samePayer = Race.run(actors) { h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:payer$round", null, "")) }
            verify(samePayer, 1, InvalidCoupon::class.java)

            // a guest who changes the payer name on every order but keeps the e-mail
            val sameEmail = Race.run(actors) { i -> h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:name$round-$i", "same$round@x.com", "")) }
            verify(sameEmail, 1, InvalidCoupon::class.java)

            // a guest who changes payer and e-mail but gifts to the same player
            val sameRecipient = Race.run(actors) { i -> h.placeTx(emptyList(), listOf(couponUse(coupon)), CustomerKeys(null, "g:p$round-$i", "e$round-$i@x.com", "g:steve$round")) }
            verify(sameRecipient, 1, InvalidCoupon::class.java)

            assertEquals(3, usedCount("market_coupon", coupon.id))
        }
    }

    @Test
    fun `stock and a coupon limit together never leave a half reservation`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 5)
            val coupon = w.fixtures.coupon(redeemLimit = 3)

            // the coupon is the scarce thing: the stock taken by the losers is rolled back with them
            val results = Race.run(actors) { h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))), listOf(couponUse(coupon))) }

            verify(results, 3, InvalidCoupon::class.java)
            assertEquals(2, stockOf("market_product", product.id))
            assertEquals(3, usedCount("market_coupon", coupon.id))

            // the stock is the scarce thing: the coupon is not counted for a run that failed on stock
            val scarce = w.fixtures.product(stock = 2)
            val wide = w.fixtures.coupon(redeemLimit = 10)
            val second = Race.run(actors) { h.placeTx(listOf(ReservationHarness.Line(h.demand(scarce, 1))), listOf(couponUse(wide))) }

            verify(second, 2, OutOfStock::class.java)
            assertEquals(0, stockOf("market_product", scarce.id))
            assertEquals(2, usedCount("market_coupon", wide.id))
        }
    }

    @Test
    fun `opposite product orders and opposite code orders never deadlock`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val first = w.fixtures.product(stock = 100)
            val second = w.fixtures.product(stock = 100)
            val coupon = w.fixtures.coupon(redeemLimit = null)
            val discount = w.fixtures.discount(usageLimit = null)

            val results = Race.run(actors) { i ->
                val products = listOf(first, second).let { if (i % 2 == 0) it else it.reversed() }
                val uses = listOf(couponUse(coupon), CodeUse(RedemptionKind.DISCOUNT, discount.id, null, 1, "EUR")).let { if (i % 2 == 0) it else it.reversed() }

                h.placeTx(products.map { ReservationHarness.Line(h.demand(it, 1)) }, uses)
            }

            verifyAllSucceeded(results)
            assertEquals(100 - actors, stockOf("market_product", first.id))
            assertEquals(100 - actors, stockOf("market_product", second.id))
            assertEquals(actors, usedCount("market_coupon", coupon.id))
            assertEquals(actors, usedCount("market_discount", discount.id))
        }
    }

    @Test
    fun `reserve, release and commit on overlapping rows never deadlock and keep the totals`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val first = w.fixtures.product(stock = 100)
            val second = w.fixtures.product(stock = 100)
            val coupon = w.fixtures.coupon(redeemLimit = null)
            val lines = { reversed: Boolean -> listOf(first, second).let { if (reversed) it.reversed() else it }.map { ReservationHarness.Line(h.demand(it, 1)) } }
            val toRelease = List(10) { h.placeTx(lines(it % 2 == 0), listOf(couponUse(coupon))).orderId }
            val toCommit = List(10) { h.placeTx(lines(it % 2 == 1), listOf(couponUse(coupon))).orderId }

            val results = Race.run(30) { i ->
                when {
                    i < 10 -> h.placeTx(lines(i % 2 == 0), listOf(couponUse(coupon))).orderId.let { true }
                    i < 20 -> h.expire(toRelease[i - 10])
                    else -> h.complete(toCommit[i - 20])
                }
            }

            verifyAllSucceeded(results)
            assertTrue(results.all { it.getOrThrow() }, "every transition applied exactly once")
            // 10 new + 10 committed hold stock; the 10 released ones gave theirs back
            assertEquals(100 - 20, stockOf("market_product", first.id))
            assertEquals(100 - 20, stockOf("market_product", second.id))
            assertEquals(20, usedCount("market_coupon", coupon.id))
            assertEquals(10, count("market_order", "`status` = 'COMPLETED' AND `id` IN (${toCommit.joinToString(",")})"))
            assertEquals(10, count("market_order", "`status` = 'EXPIRED' AND `id` IN (${toRelease.joinToString(",")})"))
        }
    }

    @Test
    fun `twenty concurrent releases of one order restore it exactly once`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 6)
            val coupon = w.fixtures.coupon(redeemLimit = 2)
            val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 4))), listOf(couponUse(coupon)))
            assertEquals(2, stockOf("market_product", product.id))

            val results = Race.run(actors) { h.expire(placed.orderId) }

            verifyAllSucceeded(results)
            assertEquals(1, results.count { it.getOrThrow() }, "exactly one run released")
            assertEquals(6, stockOf("market_product", product.id))
            assertEquals(0, usedCount("market_coupon", coupon.id))
            assertEquals(listOf("RELEASED"), sql("SELECT `state` FROM `pano_market_redemption` WHERE `orderId` = ?", placed.orderId).map { it.getString("state") })
        }
    }

    @Test
    fun `commit and release racing for one order end in exactly one of the two states`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 6)
            val coupon = w.fixtures.coupon(redeemLimit = 2)
            val placed = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 4))), listOf(couponUse(coupon)))

            val results = Race.run(actors) { i -> if (i % 2 == 0) "commit" to h.complete(placed.orderId) else "release" to h.expire(placed.orderId) }

            verifyAllSucceeded(results)
            val applied = results.map { it.getOrThrow() }.filter { it.second }
            assertEquals(1, applied.size, "one transition won: $applied")

            val state = w.orders.getById(placed.orderId, pool)!!.reservationState

            if (applied.single().first == "commit") {
                assertEquals(ReservationState.COMMITTED, state)
                assertEquals(2, stockOf("market_product", product.id), "the units stay deducted")
                assertEquals(1, usedCount("market_coupon", coupon.id))
                assertEquals(4, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", product.id).single().getInteger("soldCount"))
            } else {
                assertEquals(ReservationState.RELEASED, state)
                assertEquals(6, stockOf("market_product", product.id))
                assertEquals(0, usedCount("market_coupon", coupon.id))
                assertEquals(0, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", product.id).single().getInteger("soldCount"))
            }
        }
    }

    @Test
    fun `a release racing buyers for the freed unit never oversells`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 1)
            val held = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))))
            assertEquals(0, stockOf("market_product", product.id))

            val results = Race.run(11) { i ->
                if (i == 0) h.expire(held.orderId) else h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1)))).orderId.let { true }
            }

            val failures = results.mapNotNull { it.exceptionOrNull() }
            assertEquals(0, failures.count { it is MarketBusyException }, "$failures")
            assertTrue(failures.all { it is OutOfStock }, "$failures")
            val buyers = results.count { it.isSuccess } - 1 // minus the release
            assertTrue(buyers in 0..1, "at most the one freed unit was sold, sold $buyers")
            assertEquals(1 - buyers, stockOf("market_product", product.id))
        }
    }

    @Test
    fun `an accept of a released order and fresh buyers share the last unit without overselling`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val product = w.fixtures.product(stock = 1)
            val released = h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1))))
            assertTrue(h.expire(released.orderId))
            assertEquals(1, stockOf("market_product", product.id))

            val results = Race.run(11) { i ->
                if (i == 0) h.reReserve(released.orderId) else h.placeTx(listOf(ReservationHarness.Line(h.demand(product, 1)))).orderId.let { true }
            }

            val failures = results.mapNotNull { it.exceptionOrNull() }
            assertEquals(0, failures.count { it is MarketBusyException }, "$failures")
            assertTrue(failures.all { it is OutOfStock }, "$failures")
            assertEquals(1, results.count { it.isSuccess }, "exactly one of the accept and the ten buyers got the unit")
            assertEquals(0, stockOf("market_product", product.id))
        }
    }
}
