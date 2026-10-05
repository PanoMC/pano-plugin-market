package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketCouponDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreatorCodeDaoImpl
import com.panomc.plugins.market.db.impl.MarketCreditAccountDaoImpl
import com.panomc.plugins.market.db.impl.MarketDiscountDaoImpl
import com.panomc.plugins.market.db.impl.MarketGoalDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductDaoImpl
import com.panomc.plugins.market.db.impl.MarketProductVariantDaoImpl
import com.panomc.plugins.market.db.model.MarketCoupon
import com.panomc.plugins.market.db.model.MarketCreatorCode
import com.panomc.plugins.market.db.model.MarketDiscount
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `AtomicCounterIT` (17 section 11.3, 00 section 8.3): the counters `stock`, `usedCount`, `balance`, `soldCount`,
 * `progress` and `earnings` change only through conditional statements that report 0 rows when their guard fails, and
 * the generic `update()` of the DAOs never writes them.
 *
 * Where the DAO already has the guarded method (variant stock, credit balance, goal progress) the test goes through it;
 * for the counters whose guarded method belongs to a later slice (product stock reserve, code `usedCount`, `soldCount`,
 * `earnings`) the statement of 00 section 8.3 itself is executed, which proves the guard semantics on MariaDB (matched
 * rows, not changed rows) and gives the later DAO method its acceptance statement. Each one is also raced.
 */
class AtomicCounterIT : MarketDaoITBase() {
    private val variants = MarketProductVariantDaoImpl()
    private val products = MarketProductDaoImpl()
    private val accounts = MarketCreditAccountDaoImpl()
    private val goals = MarketGoalDaoImpl()
    private val coupons = MarketCouponDaoImpl()
    private val discounts = MarketDiscountDaoImpl()
    private val creators = MarketCreatorCodeDaoImpl()

    /** The tests move counters without the redemption / order rows that I6 and I17 reconcile them with, on purpose. */
    override suspend fun assertInvariants() {}

    private suspend fun rows(statement: String, vararg args: Any?): Int =
        pool.preparedQuery(statement).execute(Tuple.from(args.toList())).coAwait().rowCount()

    private suspend fun product(stock: Int? = null, soldCount: Int = 0): Long =
        products.add(MarketProduct(slug = "p${System.nanoTime()}", name = "P", stock = stock), pool).also {
            if (soldCount != 0) sql("UPDATE `pano_market_product` SET `soldCount` = ? WHERE `id` = ?", soldCount, it)
        }

    private suspend fun stockOf(table: String, id: Long): Int? = sql("SELECT `stock` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("stock")

    // --- stock ---

    @Test
    fun `variant stock reserve returns false and changes nothing when the stock is lower`(): Unit = runBlocking {
        val p = product()
        val v = variants.add(MarketProductVariant(productId = p, name = "S", stock = 2), pool)
        assertFalse(variants.reserveStock(v, 3, pool))
        assertEquals(2, stockOf("market_product_variant", v))
        assertTrue(variants.reserveStock(v, 2, pool))
        assertEquals(0, stockOf("market_product_variant", v))
        assertFalse(variants.reserveStock(v, 1, pool))
        assertEquals(0, stockOf("market_product_variant", v))
        variants.releaseStock(v, 4, pool)
        assertEquals(4, stockOf("market_product_variant", v))
    }

    @Test
    fun `an unlimited variant is never reserved and an unknown id returns false`(): Unit = runBlocking {
        val v = variants.add(MarketProductVariant(productId = product(), name = "L", stock = null), pool)
        assertFalse(variants.reserveStock(v, 1, pool))
        assertEquals(null, stockOf("market_product_variant", v))
        assertFalse(variants.reserveStock(9999, 1, pool))
    }

    @Test
    fun `twenty racers on a stock of five take exactly five and the stock never goes negative`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val v = variants.add(MarketProductVariant(productId = product(), name = "R$it", stock = 5), pool)
            val won = Race.run(20) { variants.reserveStock(v, 1, pool) }.count { r -> r.getOrThrow() }
            assertEquals(5, won)
            assertEquals(0, stockOf("market_product_variant", v))
        }
    }

    @Test
    fun `product stock guarded decrement of 00 section 8-3 reports 0 rows when the guard fails`(): Unit = runBlocking {
        val id = product(stock = 3)
        val take = "UPDATE `pano_market_product` SET `stock` = `stock` - ? WHERE `id` = ? AND `stock` IS NOT NULL AND `stock` >= ?"
        assertEquals(0, rows(take, 4, id, 4))
        assertEquals(3, stockOf("market_product", id))
        assertEquals(1, rows(take, 3, id, 3))
        assertEquals(0, rows(take, 1, id, 1))
        assertEquals(0, stockOf("market_product", id))
        val unlimited = product(stock = null)
        assertEquals(0, rows(take, 1, unlimited, 1))
        assertEquals(null, stockOf("market_product", unlimited))
        repeat(Race.rounds) {
            val raced = product(stock = 5)
            val won = Race.run(20) { rows(take, 1, raced, 1) }.count { r -> r.getOrThrow() == 1 }
            assertEquals(5, won)
            assertEquals(0, stockOf("market_product", raced))
        }
    }

    @Test
    fun `the generic updates never write stock, setStock does`(): Unit = runBlocking {
        val id = product(stock = 5)
        val before = products.getById(id, pool)!!
        products.update(MarketProduct(id = id, slug = before.slug, name = "Renamed", stock = 99, price = 7, updatedAt = before.updatedAt + 1), pool)
        val after = products.getById(id, pool)!!
        assertEquals("Renamed", after.name)
        assertEquals(5, after.stock)

        val v = variants.add(MarketProductVariant(productId = id, name = "S", stock = 2), pool)
        variants.update(MarketProductVariant(id = v, productId = id, name = "S2", stock = 50), pool)
        assertEquals("S2", variants.getById(v, pool)!!.name)
        assertEquals(2, stockOf("market_product_variant", v))

        products.setStock(id, 12, pool)
        assertEquals(12, stockOf("market_product", id))
        products.setStock(id, null, pool)
        assertEquals(null, stockOf("market_product", id))
    }

    // --- usedCount ---

    private suspend fun usedCount(table: String, id: Long): Int = sql("SELECT `usedCount` FROM `pano_$table` WHERE `id` = ?", id).single().getInteger("usedCount")

    @Test
    fun `the generic updates of coupon, discount and creator code never write usedCount or earnings`(): Unit = runBlocking {
        val coupon = coupons.add(MarketCoupon(name = "c", code = "C1", usedCount = 3), pool)
        coupons.update(MarketCoupon(id = coupon, name = "renamed", code = "C1", usedCount = 9), pool)
        assertEquals("renamed", coupons.getById(coupon, pool)!!.name)
        assertEquals(3, usedCount("market_coupon", coupon))

        val discount = discounts.add(MarketDiscount(name = "d", usedCount = 4), pool)
        discounts.update(MarketDiscount(id = discount, name = "renamed", usedCount = 9), pool)
        assertEquals("renamed", discounts.getById(discount, pool)!!.name)
        assertEquals(4, usedCount("market_discount", discount))

        val creator = creators.add(MarketCreatorCode(creator = "s", code = "S1", usedCount = 2, earnings = 500), pool)
        creators.update(MarketCreatorCode(id = creator, creator = "renamed", code = "S1", usedCount = 9, earnings = 99999), pool)
        val read = creators.getById(creator, pool)!!
        assertEquals("renamed", read.creator)
        assertEquals(2, read.usedCount)
        assertEquals(500L, read.earnings)
    }

    @Test
    fun `usedCount guarded increment reports 0 rows at the limit and when the code is unlimited it always counts`(): Unit = runBlocking {
        val use = "UPDATE `pano_market_coupon` SET `usedCount` = `usedCount` + 1 WHERE `id` = ? AND (`redeemLimit` IS NULL OR `usedCount` < `redeemLimit`)"
        val limited = coupons.add(MarketCoupon(name = "l", code = "L1", redeemLimit = 2), pool)
        assertEquals(1, rows(use, limited))
        assertEquals(1, rows(use, limited))
        assertEquals(0, rows(use, limited))
        assertEquals(2, usedCount("market_coupon", limited))
        val unlimited = coupons.add(MarketCoupon(name = "u", code = "U1", redeemLimit = null), pool)
        repeat(3) { assertEquals(1, rows(use, unlimited)) }
        assertEquals(3, usedCount("market_coupon", unlimited))
        assertEquals(0, rows(use, 9999))
    }

    @Test
    fun `twenty racers on a coupon limited to three redeem exactly three`(): Unit = runBlocking {
        val use = "UPDATE `pano_market_coupon` SET `usedCount` = `usedCount` + 1 WHERE `id` = ? AND (`redeemLimit` IS NULL OR `usedCount` < `redeemLimit`)"
        repeat(Race.rounds) {
            val id = coupons.add(MarketCoupon(name = "r", code = "R$it", redeemLimit = 3), pool)
            val won = Race.run(20) { rows(use, id) }.count { r -> r.getOrThrow() == 1 }
            assertEquals(3, won)
            assertEquals(3, usedCount("market_coupon", id))
        }
    }

    @Test
    fun `gift usedCount guard and the release that never goes below zero`(): Unit = runBlocking {
        val id = Fixtures.insertRaw(pool, "market_gift", mapOf("code" to "G1", "type" to "PRODUCT", "redeemLimit" to 1, "createdAt" to 1, "updatedAt" to 1))
        val use = "UPDATE `pano_market_gift` SET `usedCount` = `usedCount` + 1 WHERE `id` = ? AND (`redeemLimit` IS NULL OR `usedCount` < `redeemLimit`)"
        val release = "UPDATE `pano_market_gift` SET `usedCount` = `usedCount` - 1 WHERE `id` = ? AND `usedCount` > 0"
        assertEquals(1, rows(use, id))
        assertEquals(0, rows(use, id))
        assertEquals(1, rows(release, id))
        assertEquals(0, rows(release, id))
        assertEquals(0, usedCount("market_gift", id))
    }

    // --- balance ---

    private suspend fun balance(id: Long): Long = accounts.getById(id, pool)!!.balance

    @Test
    fun `guarded balance update returns 0 rows when it would go below zero, unguarded debt is allowed`(): Unit = runBlocking {
        val id = Fixtures.insertRaw(pool, "market_credit_account", mapOf("type" to "USER", "userId" to 1, "balance" to 10, "createdAt" to 1, "updatedAt" to 1))
        assertEquals(0, accounts.addToBalance(id, -15, true, pool))
        assertEquals(10L, balance(id))
        assertEquals(1, accounts.addToBalance(id, -10, true, pool))
        assertEquals(0L, balance(id))
        assertEquals(0, accounts.addToBalance(id, -1, true, pool))
        assertEquals(1, accounts.addToBalance(id, 5, true, pool)) // a credit is never refused by the guard
        // the unguarded form (a clawback of a user who may fall into debt, 07 section 8.5)
        assertEquals(1, accounts.addToBalance(id, -8, false, pool))
        assertEquals(-3L, balance(id))
        assertEquals(1, accounts.addToBalance(id, 3, true, pool)) // a user in debt can be repaid
        assertEquals(0L, balance(id))
        assertEquals(0, accounts.addToBalance(9999, -1, true, pool))
    }

    @Test
    fun `twenty guarded debits of one credit on a balance of seven succeed exactly seven times`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val id = Fixtures.insertRaw(pool, "market_credit_account", mapOf("type" to "USER", "userId" to 100 + it, "balance" to 7, "createdAt" to 1, "updatedAt" to 1))
            val won = Race.run(20) { accounts.addToBalance(id, -1, true, pool) }.count { r -> r.getOrThrow() == 1 }
            assertEquals(7, won)
            assertEquals(0L, balance(id))
        }
    }

    // --- soldCount, progress, earnings ---

    @Test
    fun `soldCount guarded decrement reports 0 rows below zero and the generic update does not write it`(): Unit = runBlocking {
        val id = product(soldCount = 2)
        val add = "UPDATE `pano_market_product` SET `soldCount` = `soldCount` + ? WHERE `id` = ? AND `soldCount` + ? >= 0"
        assertEquals(0, rows(add, -3, id, -3))
        assertEquals(2, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", id).single().getInteger(0))
        assertEquals(1, rows(add, -2, id, -2))
        assertEquals(1, rows(add, 4, id, 4))
        assertEquals(4, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", id).single().getInteger(0))
        val before = products.getById(id, pool)!!
        products.update(MarketProduct(id = id, slug = before.slug, name = "x", updatedAt = before.updatedAt + 1), pool)
        assertEquals(4, sql("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", id).single().getInteger(0))
    }

    @Test
    fun `goal progress is an atomic add that clamps at zero and reports false for an unknown goal`(): Unit = runBlocking {
        val id = goals.add(MarketGoal(name = "g", target = 100000), pool)
        val won = Race.run(20) { goals.addProgress(id, 5, 1, pool) }.count { r -> r.getOrThrow() }
        assertEquals(20, won)
        assertEquals(100L, goals.getById(id, pool)!!.progress)
        assertTrue(goals.addProgress(id, -1000, 2, pool))
        assertEquals(0L, goals.getById(id, pool)!!.progress)
        assertFalse(goals.addProgress(9999, 1, 3, pool))
    }

    @Test
    fun `creator earnings guarded adjustment reports 0 rows when it would go negative`(): Unit = runBlocking {
        val id = creators.add(MarketCreatorCode(creator = "s", code = "S1", earnings = 100), pool)
        val adjust = "UPDATE `pano_market_creator_code` SET `earnings` = `earnings` + ? WHERE `id` = ? AND `earnings` + ? >= 0"
        assertEquals(0, rows(adjust, -150, id, -150))
        assertEquals(100L, creators.getById(id, pool)!!.earnings)
        assertEquals(1, rows(adjust, -100, id, -100))
        assertEquals(1, rows(adjust, 40, id, 40))
        assertEquals(40L, creators.getById(id, pool)!!.earnings)
        val won = Race.run(10) { rows(adjust, -10, id, -10) }.count { r -> r.getOrThrow() == 1 }
        assertEquals(4, won)
        assertEquals(0L, creators.getById(id, pool)!!.earnings)
    }
}
