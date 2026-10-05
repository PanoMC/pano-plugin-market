package com.panomc.plugins.market.support

import com.panomc.plugins.market.support.InvariantChecker.Options
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests of the test infrastructure (17 section 15): without these a green suite proves nothing. For every invariant of
 * 17 section 7 (I1 to I22, with I3b and I11b) one seeded violation makes `InvariantChecker` fail and name exactly that
 * id, and the consistent databases (empty, standard catalogue, a clean paid order) pass with every check executed.
 *
 * The seeds are raw rows ([Fixtures.insertRaw]): a DAO could not express an inconsistent row. Two things are
 * impossible to seed and are asserted instead as impossible, because a unique index forbids them: a second creator
 * earning for one `(order, code)` (I14), a second invoice for one `(order, type, refund)` (I15).
 */
class InvariantCheckerSelfIT : MarketDbTestBase() {
    private val now = FakeClock.START_MS
    private val options = Options(nowMs = now)

    /** The tests seed violations on purpose; each one runs the checker itself. */
    override suspend fun assertInvariants() {}

    /** The clock registration is per pool and survives a closed wiring: every test starts on the system clock. */
    @BeforeEach
    fun resetClock() = InvariantChecker.clearClock(pool)

    // --- seeding helpers ---

    private suspend fun raw(table: String, vararg values: Pair<String, Any?>): Long = Fixtures.insertRaw(pool, table, mapOf(*values))

    private suspend fun systemAccount(key: String): Long =
        sql("SELECT `id` FROM `pano_market_credit_account` WHERE `systemKey` = ?", key).single().getLong("id")

    private suspend fun userAccount(userId: Long): Long = raw("market_credit_account", "type" to "USER", "userId" to userId, "createdAt" to now, "updatedAt" to now)

    /** One ledger transaction with its legs `account to delta`; balances and `balanceAfter` follow the legs. */
    private suspend fun post(type: String, key: String, userId: Long?, orderId: Long?, amount: Long, vararg legs: Pair<Long, Long>): Long {
        val tx = raw("market_credit_tx", "type" to type, "idempotencyKey" to key, "userId" to userId, "orderId" to orderId, "amount" to amount, "createdAt" to now, "updatedAt" to now)
        for ((account, delta) in legs) {
            sql("UPDATE `pano_market_credit_account` SET `balance` = `balance` + ? WHERE `id` = ?", delta, account)
            val after = sql("SELECT `balance` AS b FROM `pano_market_credit_account` WHERE `id` = ?", account).single().getLong("b")
            raw("market_credit_entry", "txId" to tx, "accountId" to account, "amount" to delta, "balanceAfter" to after, "createdAt" to now, "updatedAt" to now)
        }
        return tx
    }

    /** `pricingMode = 'EXTERNAL'` keeps the arithmetic check I8 out of the way of the other seeds. */
    private suspend fun order(vararg values: Pair<String, Any?>): Long =
        raw("market_order", "playerUsername" to "alice", "pricingMode" to "EXTERNAL", "createdAt" to now, "updatedAt" to now, *values)

    /** A paid order that satisfies I8, I11 and I20: `paymentMethodId = 'manual'` needs no attempt row. */
    private suspend fun paidOrder(status: String = "COMPLETED", vararg values: Pair<String, Any?>): Long =
        order("status" to status, "paidAt" to now, "reservationState" to "COMMITTED", "paymentMethodId" to "manual", *values)

    private suspend fun payment(orderId: Long, status: String, n: Int, vararg values: Pair<String, Any?>): Long =
        raw(
            "market_payment", "orderId" to orderId, "providerId" to "fake", "status" to status, "reference" to "R$n", "token" to "t".repeat(39) + n,
            "amount" to 100, "currency" to "EUR", "createdAt" to now, "updatedAt" to now, *values
        )

    private suspend fun product(vararg values: Pair<String, Any?>): Long =
        raw("market_product", "slug" to "p${System.nanoTime()}", "name" to "P", "createdAt" to now, "updatedAt" to now, *values)

    private suspend fun coupon(vararg values: Pair<String, Any?>): Long =
        raw("market_coupon", "name" to "C", "code" to "C${System.nanoTime()}", "createdAt" to now, "updatedAt" to now, *values)

    private suspend fun redemption(kind: String, refId: Long, orderId: Long, state: String = "APPLIED"): Long =
        raw("market_redemption", "kind" to kind, "refId" to refId, "orderId" to orderId, "buyerKey" to "u:1", "currency" to "EUR", "state" to state, "createdAt" to now, "updatedAt" to now)

    private suspend fun delivery(orderId: Long, key: String, status: String = "PENDING", phase: String = "GRANT", attemptGroup: Int = 0, vararg values: Pair<String, Any?>): Long =
        raw(
            "market_delivery", "orderId" to orderId, "orderItemId" to 1, "phase" to phase, "actionId" to "a1", "actionType" to "COMMAND", "serverId" to 1,
            "idempotencyKey" to key, "status" to status, "attemptGroup" to attemptGroup, "playerUsername" to "alice", "runAfter" to now, "createdAt" to now, "updatedAt" to now, *values
        )

    /** The ids of every violated check, in order, without repeats. */
    private suspend fun violatedIds(opts: Options = options): List<String> {
        val violations = InvariantChecker.violations(pool, opts)
        assertTrue(InvariantChecker.lastRun.skipped.isEmpty(), "every check has its tables: ${InvariantChecker.lastRun.skipped}")
        return violations.map { it.id }.distinct()
    }

    private suspend fun expectOnly(id: String, opts: Options = options) {
        assertEquals(listOf(id), violatedIds(opts), "seeded violation of $id")
        assertTrue(id in InvariantChecker.lastRun.ran)
    }

    private suspend fun expectClean(opts: Options = options) = assertEquals(emptyList<String>(), violatedIds(opts))

    // --- the consistent databases pass, with every check executed ---

    @Test
    fun `an empty database passes and every invariant id has a check that ran`(): Unit = runBlocking {
        InvariantChecker.assertAll(pool)
        InvariantChecker.assertAll(pool, true)
        InvariantChecker.assertAll(pool, options)
        assertTrue(InvariantChecker.lastRun.skipped.isEmpty())
        assertEquals(InvariantChecker.ids.toSet(), InvariantChecker.lastRun.ran.toSet())
        assertEquals(24, InvariantChecker.ids.size)
    }

    @Test
    fun `the standard catalogue with its users, credits and codes passes`(): Unit = runBlocking {
        TestWiring(pool).use { w ->
            val catalog = StandardCatalog(w).seed()
            w.assertInvariants()
            w.assertInvariants(legacy = true)
            assertEquals(13L, count("market_product"))
            assertEquals(4L, count("market_coupon"))
            assertEquals(10000L, w.fixtures.creditBalance(catalog.alice))
        }
    }

    @Test
    fun `a failed run throws the first violation and carries the others as suppressed`(): Unit = runBlocking {
        product("stock" to -1)
        coupon("redeemLimit" to 1, "usedCount" to 2) // I6 (no redemptions) and I7
        val e = assertThrows(InvariantViolation::class.java) { runBlocking { InvariantChecker.assertAll(pool, options) } }
        assertEquals("I5", e.id)
        assertEquals(listOf("I6", "I7"), e.suppressed.map { (it as InvariantViolation).id })
    }

    // --- credit ledger ---

    @Test
    fun `I1 an unbalanced transaction`(): Unit = runBlocking {
        val account = userAccount(1)
        post("GRANT", "k1", 1, null, 10, account to 10)
        expectOnly("I1")
    }

    @Test
    fun `a balanced transaction is clean`(): Unit = runBlocking {
        val account = userAccount(1)
        post("GRANT", "k1", 1, null, 10, account to 10, systemAccount("ISSUANCE") to -10)
        expectClean()
    }

    @Test
    fun `I2 a cached balance that is not the sum of its entries`(): Unit = runBlocking {
        sql("INSERT INTO `pano_market_credit_account` (`type`, `userId`, `balance`, `createdAt`, `updatedAt`) VALUES ('USER', 1, 5, 1, 1)")
        expectOnly("I2")
    }

    @Test
    fun `I3 a negative user balance without a dispute clawback`(): Unit = runBlocking {
        val account = userAccount(1)
        post("REVOKE", "k1", 1, null, 5, account to -5, systemAccount("REVOKED") to 5)
        expectOnly("I3")
    }

    @Test
    fun `a negative user balance after a dispute clawback is allowed`(): Unit = runBlocking {
        val account = userAccount(1)
        post("REVOKE", "dispute:77", 1, null, 5, account to -5, systemAccount("REVOKED") to 5)
        expectClean()
    }

    @Test
    fun `I3 a negative HOLD balance`(): Unit = runBlocking {
        post("RELEASE", "k1", null, null, 5, systemAccount("HOLD") to -5, systemAccount("ISSUANCE") to 5)
        // the HOLD balance is also not what the open orders hold (nothing): I4 names it too, I3 must be among the ids
        val ids = violatedIds()
        assertTrue("I3" in ids, ids.toString())
    }

    @Test
    fun `I3b a held order whose ledger hold is not its credit amount`(): Unit = runBlocking {
        val user = userAccount(1)
        post("GRANT", "g", 1, null, 100, user to 100, systemAccount("ISSUANCE") to -100)
        val hold = systemAccount("HOLD")
        // two orders hold 40 each in the ledger but claim 50 and 30: the HOLD balance (80) equals the claimed total (80), so I4 stays quiet
        val a = order("status" to "PENDING", "reservationState" to "HELD", "creditAmount" to 50)
        val b = order("status" to "PENDING", "reservationState" to "HELD", "creditAmount" to 30)
        post("HOLD", "h-a", 1, a, 40, user to -40, hold to 40)
        post("HOLD", "h-b", 1, b, 40, user to -40, hold to 40)
        expectOnly("I3b")
    }

    @Test
    fun `I3b a held order with the exact hold is clean and a released hold nets to zero`(): Unit = runBlocking {
        val user = userAccount(1)
        post("GRANT", "g", 1, null, 100, user to 100, systemAccount("ISSUANCE") to -100)
        val hold = systemAccount("HOLD")
        val a = order("status" to "PENDING", "reservationState" to "HELD", "creditAmount" to 40)
        post("HOLD", "h-a", 1, a, 40, user to -40, hold to 40)
        expectClean()
        // the order is released: hold and release net to 0, the order says RELEASED
        sql("UPDATE `pano_market_order` SET `status` = 'EXPIRED', `reservationState` = 'RELEASED' WHERE `id` = ?", a)
        post("RELEASE", "r-a", 1, a, 40, hold to -40, user to 40)
        expectClean()
    }

    @Test
    fun `I3b an order with credits and no hold at all`(): Unit = runBlocking {
        order("status" to "PENDING", "reservationState" to "NONE", "creditAmount" to 20)
        expectOnly("I3b")
    }

    @Test
    fun `I4 the HOLD balance is not what open orders hold`(): Unit = runBlocking {
        post("HOLD", "k1", null, null, 7, systemAccount("HOLD") to 7, systemAccount("ISSUANCE") to -7)
        expectOnly("I4")
    }

    // --- stock and codes ---

    @Test
    fun `I5 negative stock of a product`(): Unit = runBlocking {
        product("stock" to -1)
        expectOnly("I5")
    }

    @Test
    fun `I5 negative stock of a variant`(): Unit = runBlocking {
        val p = product()
        raw("market_product_variant", "productId" to p, "name" to "S", "stock" to -3, "createdAt" to now, "updatedAt" to now)
        expectOnly("I5")
    }

    @Test
    fun `I6 a coupon counter that does not match its redemptions`(): Unit = runBlocking {
        coupon("usedCount" to 2)
        redemption("COUPON", sql("SELECT MAX(`id`) AS i FROM `pano_market_coupon`").single().getLong("i"), 1)
        expectOnly("I6")
    }

    @Test
    fun `I6 the counters of creator codes, discounts and gifts are checked too`(): Unit = runBlocking {
        val creator = raw("market_creator_code", "creator" to "s", "code" to "S1", "discount" to 0, "usedCount" to 1, "createdAt" to now, "updatedAt" to now)
        assertEquals(listOf("I6"), violatedIds())
        sql("UPDATE `pano_market_creator_code` SET `usedCount` = 0 WHERE `id` = ?", creator)
        raw("market_discount", "name" to "D", "value" to 0, "usedCount" to 2, "createdAt" to now, "updatedAt" to now)
        assertEquals(listOf("I6"), violatedIds())
        sql("DELETE FROM `pano_market_discount`")
        raw("market_gift", "code" to "G1", "type" to "PRODUCT", "usedCount" to 1, "createdAt" to now, "updatedAt" to now)
        assertEquals(listOf("I6"), violatedIds())
    }

    @Test
    fun `I6 counts held and applied redemptions, not released ones, and subtracts the legacy counter`(): Unit = runBlocking {
        val c = coupon("usedCount" to 5, "legacyUsedCount" to 3)
        redemption("COUPON", c, 1, "HELD")
        redemption("COUPON", c, 2, "APPLIED")
        redemption("COUPON", c, 3, "RELEASED")
        expectClean()
    }

    @Test
    fun `I6 in legacy mode waits for the legacyUsedCount fixup marker`(): Unit = runBlocking {
        sql("DELETE FROM `pano_market_sequence` WHERE `name` LIKE 'fixup:%'") // a fresh install writes the markers; this one has not run the fixup
        coupon("usedCount" to 4) // a version 2 coupon the fixup has not converted yet
        assertEquals(emptyList<String>(), violatedIds(options.copy(legacy = true)))
        assertEquals(listOf("I6"), violatedIds(options))
        raw("market_sequence", "name" to "fixup:legacyUsedCount", "value" to 1)
        assertEquals(listOf("I6"), violatedIds(options.copy(legacy = true)))
    }

    @Test
    fun `I7 a coupon used beyond its limit`(): Unit = runBlocking {
        val c = coupon("redeemLimit" to 1, "usedCount" to 2)
        redemption("COUPON", c, 1)
        redemption("COUPON", c, 2)
        expectOnly("I7")
    }

    @Test
    fun `I7 a gift used beyond its limit`(): Unit = runBlocking {
        val g = raw("market_gift", "code" to "G1", "type" to "PRODUCT", "redeemLimit" to 1, "usedCount" to 2, "createdAt" to now, "updatedAt" to now)
        redemption("GIFT", g, 1)
        redemption("GIFT", g, 2)
        expectOnly("I7")
    }

    // --- orders and payments ---

    @Test
    fun `I8 an order whose total is not its lines plus shipping and fee`(): Unit = runBlocking {
        order("pricingMode" to "MARKET", "totalPrice" to 100, "gatewayAmount" to 100)
        expectOnly("I8")
    }

    @Test
    fun `I8 the tender does not add up to the total`(): Unit = runBlocking {
        val o = order("pricingMode" to "MARKET", "totalPrice" to 100, "gatewayAmount" to 60, "creditValue" to 30)
        raw("market_order_item", "orderId" to o, "productName" to "P", "lineTotal" to 100, "createdAt" to now, "updatedAt" to now)
        expectOnly("I8")
    }

    @Test
    fun `an arithmetically consistent market order is clean and legacy mode skips unconverted and legacy rows`(): Unit = runBlocking {
        val o = order("pricingMode" to "MARKET", "totalPrice" to 100, "gatewayAmount" to 60, "creditValue" to 40, "shippingTotal" to 10, "paymentFee" to 5)
        raw("market_order_item", "orderId" to o, "productName" to "P", "lineTotal" to 85, "createdAt" to now, "updatedAt" to now)
        expectClean()
        // a version 2 row: pricing mode default, no items, no gateway amount
        order("pricingMode" to "MARKET", "totalPrice" to 70, "publicId" to null)
        assertEquals(listOf("I8"), violatedIds())
        assertEquals(emptyList<String>(), violatedIds(options.copy(legacy = true)))
    }

    @Test
    fun `I9 refunds beyond the order`(): Unit = runBlocking {
        order("status" to "PENDING", "totalPrice" to 100, "paidAmount" to 100, "refundedTotal" to 150)
        expectOnly("I9")
    }

    @Test
    fun `I9 the refunded total is not the sum of the succeeded refunds`(): Unit = runBlocking {
        val o = order("status" to "PENDING", "totalPrice" to 100, "paidAmount" to 100, "refundedTotal" to 30)
        raw("market_refund", "orderId" to o, "status" to "SUCCEEDED", "origin" to "PANEL", "idempotencyKey" to "i1", "amount" to 20, "currency" to "EUR", "createdAt" to now, "updatedAt" to now)
        expectOnly("I9")
        raw("market_refund", "orderId" to o, "status" to "FAILED", "origin" to "PANEL", "idempotencyKey" to "i2", "amount" to 50, "currency" to "EUR", "createdAt" to now, "updatedAt" to now)
        sql("UPDATE `pano_market_order` SET `refundedTotal` = 20, `refundedGatewayAmount` = 20")
        expectClean()
    }

    @Test
    fun `I10 two open attempts for one order`(): Unit = runBlocking {
        val o = order("status" to "PENDING")
        payment(o, "CREATED", 1)
        payment(o, "PENDING", 2)
        expectOnly("I10")
    }

    @Test
    fun `an open attempt next to failed ones is clean`(): Unit = runBlocking {
        val o = order("status" to "PENDING")
        payment(o, "FAILED", 1)
        payment(o, "CANCELLED", 2)
        payment(o, "PROCESSING", 3)
        expectClean()
    }

    @Test
    fun `I11 a paid order without paidAt`(): Unit = runBlocking {
        order("status" to "COMPLETED", "reservationState" to "COMMITTED", "paymentMethodId" to "manual")
        expectOnly("I11")
    }

    @Test
    fun `I11 a paid order that is not committed or has no attempt`(): Unit = runBlocking {
        order("status" to "REFUNDED", "paidAt" to now, "reservationState" to "RELEASED", "paymentMethodId" to "manual")
        assertEquals(listOf("I11"), violatedIds())
        sql("DELETE FROM `pano_market_order`")
        order("status" to "COMPLETED", "paidAt" to now, "reservationState" to "COMMITTED", "paymentMethodId" to "fake")
        assertEquals(listOf("I11"), violatedIds())
        // a legacy order never had an attempt, a manual order needs none
        sql("UPDATE `pano_market_order` SET `source` = 'LEGACY'")
        expectClean()
    }

    @Test
    fun `I11 in legacy mode leaves the unconverted version 2 rows alone`(): Unit = runBlocking {
        order("status" to "COMPLETED", "paymentMethodId" to "fake") // no paidAt, not committed, no publicId, buyerKey ''
        assertEquals(listOf("I11"), violatedIds())
        assertEquals(emptyList<String>(), violatedIds(options.copy(legacy = true)))
    }

    @Test
    fun `I11b a succeeded attempt with another tender than its order`(): Unit = runBlocking {
        val o = paidOrder("COMPLETED", "totalPrice" to 100, "gatewayAmount" to 100)
        val p = payment(o, "SUCCEEDED", 1, "amount" to 90)
        sql("UPDATE `pano_market_order` SET `paymentId` = ? WHERE `id` = ?", p, o)
        expectOnly("I11b")
        sql("UPDATE `pano_market_payment` SET `amount` = 100")
        expectClean()
    }

    @Test
    fun `I12 a released order that still holds a reservation`(): Unit = runBlocking {
        order("status" to "EXPIRED", "reservationState" to "COMMITTED")
        expectOnly("I12")
    }

    @Test
    fun `I12 a released order with a redemption that is not released`(): Unit = runBlocking {
        val o = order("status" to "CANCELLED", "reservationState" to "RELEASED")
        redemption("COUPON", 9999, o, "HELD")
        expectOnly("I12")
        sql("UPDATE `pano_market_redemption` SET `state` = 'RELEASED'")
        expectClean()
    }

    @Test
    fun `I13 two succeeded attempts for one order`(): Unit = runBlocking {
        val o = order("status" to "PENDING")
        payment(o, "SUCCEEDED", 1)
        payment(o, "SUCCEEDED", 2)
        expectOnly("I13")
    }

    @Test
    fun `a succeeded attempt and its duplicates are clean`(): Unit = runBlocking {
        val o = order("status" to "PENDING")
        payment(o, "SUCCEEDED", 1)
        payment(o, "SUCCEEDED", 2, "duplicate" to 1)
        expectClean()
    }

    // --- creators, invoices ---

    @Test
    fun `I14 creator earnings that are not the sum of the earning rows`(): Unit = runBlocking {
        val c = raw("market_creator_code", "creator" to "s", "code" to "S1", "discount" to 0, "earnings" to 100, "createdAt" to now, "updatedAt" to now)
        raw("market_creator_earning", "creatorCodeId" to c, "orderId" to 1, "baseAmount" to 1000, "commissionPercent" to 1000, "amount" to 100, "reversedAmount" to 30, "currency" to "EUR", "createdAt" to now, "updatedAt" to now)
        expectOnly("I14")
        sql("UPDATE `pano_market_creator_code` SET `earnings` = 70")
        expectClean()
    }

    @Test
    fun `I14 a second earning for one order and code cannot exist, the unique index refuses it`(): Unit = runBlocking {
        val c = raw("market_creator_code", "creator" to "s", "code" to "S1", "discount" to 0, "createdAt" to now, "updatedAt" to now)
        val row = arrayOf<Pair<String, Any?>>(
            "creatorCodeId" to c, "orderId" to 1, "baseAmount" to 0, "commissionPercent" to 0, "amount" to 0, "currency" to "EUR", "createdAt" to now, "updatedAt" to now
        )
        raw("market_creator_earning", *row)
        assertThrows(Exception::class.java) { runBlocking { raw("market_creator_earning", *row) } }
    }

    @Test
    fun `I15 a gap in an invoice series`(): Unit = runBlocking {
        for ((order, sequence) in listOf(1L to 1L, 2L to 3L)) {
            raw(
                "market_invoice", "orderId" to order, "type" to "INVOICE", "series" to "A", "sequence" to sequence, "number" to "A-$sequence", "locale" to "en-US", "currency" to "EUR",
                "total" to 1, "vatTotal" to 0, "issuedAt" to now, "createdAt" to now, "updatedAt" to now
            )
        }
        expectOnly("I15")
        sql("UPDATE `pano_market_invoice` SET `sequence` = 2 WHERE `orderId` = 2")
        expectClean()
    }

    @Test
    fun `I15 a second invoice for one order, type and refund cannot exist, the unique index refuses it`(): Unit = runBlocking {
        val row = arrayOf<Pair<String, Any?>>(
            "orderId" to 1, "type" to "INVOICE", "series" to "A", "number" to "x", "locale" to "en-US", "currency" to "EUR", "total" to 1, "vatTotal" to 0,
            "issuedAt" to now, "createdAt" to now, "updatedAt" to now
        )
        raw("market_invoice", "sequence" to 1, *row)
        assertThrows(Exception::class.java) { runBlocking { raw("market_invoice", "sequence" to 2, *row) } }
    }

    @Test
    fun `I16 a held order that is neither pending nor in review`(): Unit = runBlocking {
        order("status" to "EXPIRED", "reservationState" to "HELD")
        expectOnly("I16")
    }

    @Test
    fun `I17 a sold counter that does not match the paid order items`(): Unit = runBlocking {
        product("soldCount" to 5)
        expectOnly("I17")
    }

    @Test
    fun `I17 counts paid non-test orders minus refunded quantity`(): Unit = runBlocking {
        val p = product("soldCount" to 4)
        val paid = paidOrder("COMPLETED")
        raw("market_order_item", "orderId" to paid, "productId" to p, "productName" to "P", "quantity" to 3, "createdAt" to now, "updatedAt" to now)
        val partial = paidOrder("PARTIALLY_REFUNDED")
        raw("market_order_item", "orderId" to partial, "productId" to p, "productName" to "P", "quantity" to 2, "refundedQuantity" to 1, "createdAt" to now, "updatedAt" to now)
        val test = paidOrder("COMPLETED", "testMode" to 1)
        raw("market_order_item", "orderId" to test, "productId" to p, "productName" to "P", "quantity" to 9, "createdAt" to now, "updatedAt" to now)
        val pending = order("status" to "PENDING")
        raw("market_order_item", "orderId" to pending, "productId" to p, "productName" to "P", "quantity" to 7, "createdAt" to now, "updatedAt" to now)
        expectClean() // 3 + (2 - 1) = 4
    }

    @Test
    fun `I17 in legacy mode waits for the soldCount fixup marker`(): Unit = runBlocking {
        sql("DELETE FROM `pano_market_sequence` WHERE `name` LIKE 'fixup:%'")
        product("soldCount" to 0)
        val p = sql("SELECT MAX(`id`) AS i FROM `pano_market_product`").single().getLong("i")
        val o = order("status" to "COMPLETED", "source" to "LEGACY", "paidAt" to now, "reservationState" to "COMMITTED", "paymentMethodId" to "x")
        raw("market_order_item", "orderId" to o, "productId" to p, "productName" to "P", "quantity" to 2, "createdAt" to now, "updatedAt" to now)
        assertEquals(listOf("I17"), violatedIds())
        assertEquals(emptyList<String>(), violatedIds(options.copy(legacy = true)))
        raw("market_sequence", "name" to "fixup:soldCount", "value" to 1)
        assertEquals(listOf("I17"), violatedIds(options.copy(legacy = true)))
        sql("UPDATE `pano_market_product` SET `soldCount` = 2")
        expectClean()
    }

    // --- queues ---

    private suspend fun inbound(key: String, vararg values: Pair<String, Any?>) =
        raw("market_payment_event", "providerId" to "fake", "direction" to "IN", "channel" to "WEBHOOK", "eventKey" to key, "createdAt" to now, "updatedAt" to now, *values)

    @Test
    fun `I18 an inbound event stuck in RECEIVED`(): Unit = runBlocking {
        inbound("r:1", "status" to "RECEIVED", "createdAt" to now - 61_000)
        expectOnly("I18")
    }

    @Test
    fun `I18 a failed inbound event whose retry is overdue`(): Unit = runBlocking {
        inbound("r:1", "status" to "FAILED", "attempts" to 3, "nextAttemptAt" to now - 121_000)
        expectOnly("I18")
    }

    @Test
    fun `I18 young, exhausted, processed and outbound events are clean`(): Unit = runBlocking {
        inbound("r:1", "status" to "RECEIVED", "createdAt" to now - 30_000)
        inbound("r:2", "status" to "FAILED", "attempts" to 10, "nextAttemptAt" to now - 999_000)
        inbound("r:3", "status" to "FAILED", "attempts" to 2, "nextAttemptAt" to now - 60_000)
        inbound("r:4", "status" to "PROCESSED", "createdAt" to now - 999_000)
        inbound("r:5", "status" to "RECEIVED", "direction" to "OUT", "createdAt" to now - 999_000)
        expectClean()
    }

    @Test
    fun `I18 judges by the clock it is given`(): Unit = runBlocking {
        inbound("r:1", "status" to "RECEIVED", "createdAt" to now)
        expectClean()
        assertEquals(listOf("I18"), violatedIds(Options(nowMs = now + 61_000)))
        TestWiring(pool, FakeClock(now + 61_000)).use { assertEquals(listOf("I18"), InvariantChecker.violations(pool).map { v -> v.id }) }
        assertEquals(emptyList<String>(), InvariantChecker.violations(pool, Options(nowMs = now)).map { it.id })
    }

    @Test
    fun `I18 the clock of a closed wiring still judges the check after the block`(): Unit = runBlocking {
        val clock = FakeClock()
        TestWiring(pool, clock).use {
            // a young RECEIVED event under the wiring's clock; judged by the wall clock (a year later) it would be stuck
            inbound("r:1", "status" to "RECEIVED", "createdAt" to clock.now())
        }
        InvariantChecker.assertAll(pool) // the @AfterEach pattern: no violation
        assertEquals(emptyList<String>(), InvariantChecker.violations(pool).map { it.id })
        // the same row IS stuck for a pool without a registered clock: the case above is not vacuous
        InvariantChecker.clearClock(pool)
        assertEquals(listOf("I18"), InvariantChecker.violations(pool).map { it.id })
    }

    @Test
    fun `I19 the clock of a closed wiring still judges a SENDING delivery after the block`(): Unit = runBlocking {
        val clock = FakeClock()
        TestWiring(pool, clock).use {
            val o = order("status" to "PENDING")
            delivery(o, "k1", "SENDING", "GRANT", 0, "claimedUntil" to clock.now() + 30_000)
        }
        InvariantChecker.assertAll(pool)
        InvariantChecker.clearClock(pool)
        assertEquals(listOf("I19"), InvariantChecker.violations(pool).map { it.id })
    }

    @Test
    fun `a wiring on another pool does not change the clock used for this pool`(): Unit = runBlocking {
        val other = MarketTestDb.pool(databaseName, 2)
        try {
            val mine = FakeClock()
            TestWiring(pool, mine).use {
                inbound("r:1", "status" to "RECEIVED", "createdAt" to mine.now())
                // a wiring of another pool whose clock is a day ahead
                TestWiring(other, FakeClock(mine.now() + 86_400_000)).use {
                    assertEquals(emptyList<String>(), InvariantChecker.violations(pool).map { v -> v.id })
                    assertEquals(listOf("I18"), InvariantChecker.violations(other).map { v -> v.id })
                }
            }
            assertEquals(emptyList<String>(), InvariantChecker.violations(pool).map { it.id })
            // a new wiring on the same pool replaces its entry
            TestWiring(pool, FakeClock(mine.now() + 86_400_000))
            assertEquals(listOf("I18"), InvariantChecker.violations(pool).map { it.id })
        } finally {
            InvariantChecker.clearClock(other)
            other.close().coAwait()
        }
    }

    @Test
    fun `I19 two delivery rows for one logical delivery`(): Unit = runBlocking {
        val o = order("status" to "PENDING")
        delivery(o, "k1")
        delivery(o, "k2")
        expectOnly("I19")
    }

    @Test
    fun `I19 a delivery stuck in SENDING`(): Unit = runBlocking {
        val o = order("status" to "PENDING")
        delivery(o, "k1", "SENDING", claimedUntil = now - 61_000)
        expectOnly("I19")
        sql("UPDATE `pano_market_delivery` SET `claimedUntil` = ?", now - 30_000)
        expectClean()
        sql("UPDATE `pano_market_delivery` SET `claimedUntil` = NULL")
        expectOnly("I19")
    }

    private suspend fun delivery(orderId: Long, key: String, status: String, claimedUntil: Long) = delivery(orderId, key, status, "GRANT", 0, "claimedUntil" to claimedUntil)

    @Test
    fun `I20 an active entitlement of a refunded order while revoke on refund is on`(): Unit = runBlocking {
        val o = paidOrder("REFUNDED")
        raw("market_entitlement", "playerUsername" to "alice", "ownerKey" to "u:1", "productId" to 1, "orderId" to o, "orderItemId" to 1, "status" to "ACTIVE", "startsAt" to now, "createdAt" to now, "updatedAt" to now)
        expectOnly("I20")
        expectClean(options.copy(revokeOnRefund = false))
        sql("UPDATE `pano_market_entitlement` SET `status` = 'REVOKED'")
        expectClean()
    }

    @Test
    fun `I20 the chargeback switch is separate`(): Unit = runBlocking {
        val o = paidOrder("CHARGEBACK")
        raw("market_entitlement", "playerUsername" to "alice", "ownerKey" to "u:1", "productId" to 1, "orderId" to o, "orderItemId" to 1, "status" to "ACTIVE", "startsAt" to now, "createdAt" to now, "updatedAt" to now)
        expectOnly("I20")
        expectClean(options.copy(revokeOnChargeback = false))
        expectClean(options.copy(revokeOnRefund = false, revokeOnChargeback = false))
    }

    @Test
    fun `I21 an order shown as revoked while an undo is still open`(): Unit = runBlocking {
        val o = order("status" to "PENDING", "fulfillmentStatus" to "REVOKED")
        delivery(o, "k1", "PENDING", "REVOKE")
        expectOnly("I21")
    }

    @Test
    fun `I21 uses the effective row of a logical delivery and drops cancelled ones`(): Unit = runBlocking {
        val o = order("status" to "PENDING", "fulfillmentStatus" to "REVOKED")
        delivery(o, "k1", "FAILED", "REVOKE", 0)
        delivery(o, "k2", "CONFIRMED", "REVOKE", 1) // the retry (higher attempt group) is the effective row
        delivery(o, "k3", "CANCELLED", "EXPIRE", 0)
        expectClean()
        delivery(o, "k4", "FAILED", "REVOKE", 2) // a later failed retry reopens it
        expectOnly("I21")
    }

    @Test
    fun `I22 a credit-free renewal that carries credits`(): Unit = runBlocking {
        order("status" to "PENDING", "source" to "RENEWAL", "creditAmount" to 50)
        expectOnly("I22")
    }
}
