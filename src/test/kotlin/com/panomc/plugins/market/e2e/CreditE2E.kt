package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.util.MarketPaths

/**
 * The credit ledger over HTTP (17 section 9.5): CR-01 (manual grant and revoke, the admin and the buyer view of the ledger) and CR-02 (cashback and
 * its reversal). Every scenario uses a buyer of its own, so the balances it asserts are its own; the base class runs the ledger invariants (I1 to I22)
 * and the drain after each of them.
 *
 * Settings a scenario changes (`cashbackPercent`, `testMode`, the bank transfer method) are put back in a `finally`.
 */
class CreditE2E : E2eTestBase() {
    override val tag = "cr"

    private val sequence = AtomicInteger()

    private fun move(path: String, userId: Long, amount: Number, note: String, key: String = idempotencyKey()): E2eResponse =
        admin.post("${MarketPaths.PANEL_ROOT}/credits/accounts/$userId/$path", JsonObject().put("amount", amount).put("note", note), mapOf("Idempotency-Key" to key))

    private fun myCredits(client: E2eClient): JsonObject = client.get("${MarketPaths.SITE_ROOT}/me/credits").ok().obj()

    private fun entries(view: JsonObject, type: String): List<JsonObject> = view.getJsonArray("items").map { it as JsonObject }.filter { it.getString("type") == type }

    private fun product(price: String): Long {
        val n = sequence.incrementAndGet()

        return catalog.product(key = "CR$n", slug = "e2e-cr-${System.currentTimeMillis().toString(36)}-$n", name = "Credit product $n", price = price)
    }

    // --- CR-01 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `CR-01 manual grant and revoke are on the ledger of both views`() {
        val buyer = buyer(canPay = false)

        val grantKey = idempotencyKey()
        val grant = move("grant", buyer.userId, 50, "welcome gift", grantKey).ok().obj()

        assertEquals(50.0, grant.getDouble("balance"), 0.0001)
        assertEquals(0.0, grant.getDouble("shortfall"), 0.0001)

        // a replay of the same key is the same answer and moves nothing
        val replay = move("grant", buyer.userId, 50, "welcome gift", grantKey).ok().obj()

        assertEquals(50.0, replay.getDouble("balance"), 0.0001)
        assertEquals(1L, db.count("market_credit_tx", "`userId` = ? AND `type` = 'GRANT'", buyer.userId), "one GRANT transaction")

        // 80 credits taken from an account that holds 50: the balance is 0 and the missing 30 are the shortfall
        val revoke = move("revoke", buyer.userId, 80, "chargeback of a top-up").ok().obj()

        assertEquals(0.0, revoke.getDouble("balance"), 0.0001)
        assertEquals(30.0, revoke.getDouble("shortfall"), 0.0001)
        assertEquals(0L, db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId))

        val tx = db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `userId` = ? ORDER BY `id`", buyer.userId)

        assertEquals(listOf("GRANT", "REVOKE"), tx.map { it.getString("type") })
        assertEquals(listOf(5000L, 5000L), tx.map { it.getLong("amount") }, "the revoke moved what was there")
        assertEquals(listOf(0L, 3000L), tx.map { it.getLong("shortfall") })

        // the admin view: both rows with the acting admin
        val account = admin.get("${MarketPaths.PANEL_ROOT}/credits/accounts/${buyer.userId}").ok().obj()

        assertEquals(0.0, account.getDouble("balance"), 0.0001)
        assertEquals(2, account.getJsonObject("page").getInteger("totalItems"))

        val adminEntries = account.getJsonArray("items").map { it as JsonObject }

        assertEquals(setOf("GRANT", "REVOKE"), adminEntries.map { it.getString("type") }.toSet())
        assertTrue(adminEntries.all { it.getString("actorUsername") == session.env.adminUser }, "every row names the admin: ${adminEntries.map { it.getString("actorUsername") }}")
        assertEquals(30.0, adminEntries.first { it.getString("type") == "REVOKE" }.getDouble("shortfall"), 0.0001)
        assertEquals(setOf("welcome gift", "chargeback of a top-up"), adminEntries.map { it.getString("note") }.toSet())

        val listed = admin.get("${MarketPaths.PANEL_ROOT}/credits/accounts?search=${buyer.username}").ok().obj().getJsonArray("items").map { it as JsonObject }

        assertEquals(listOf(buyer.userId), listed.map { it.getLong("userId") })

        // the buyer view: signed amounts and the balance after every row
        val mine = myCredits(buyer.client)

        assertEquals(0.0, mine.getDouble("balance"), 0.0001)

        val gained = entries(mine, "GRANT").single()
        val taken = entries(mine, "REVOKE").single()

        assertEquals(50.0, gained.getDouble("amount"), 0.0001)
        assertEquals(50.0, gained.getDouble("balanceAfter"), 0.0001)
        assertEquals(-50.0, taken.getDouble("amount"), 0.0001, "a revoke is shown with its sign")
        assertEquals(0.0, taken.getDouble("balanceAfter"), 0.0001)
        assertEquals("welcome gift", gained.getString("note"))

        // the amount and the note are validated
        assertEquals("INVALID_CREDIT_AMOUNT", move("grant", buyer.userId, 0, "zero amount").error)
        assertEquals(400, move("grant", buyer.userId, 1, "x").status, "a note shorter than three characters")
        assertEquals(2L, db.count("market_credit_tx", "`userId` = ?", buyer.userId), "refused requests wrote nothing")
    }

    // --- CR-02 ---------------------------------------------------------------------------------------------------------

    private fun cashbackRows(orderId: Long, type: String) =
        db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` = ? ORDER BY `id`", orderId, type)

    /** Sets credit settings for [block] and puts the previous values back (credit keys live behind their own route). */
    private fun <T> withCredit(changes: JsonObject, block: () -> T): T {
        val before = admin.get("${MarketPaths.PANEL_ROOT}/settings").ok().obj()
        val restore = JsonObject().also { r -> changes.fieldNames().forEach { k -> before.getValue(k)?.let { v -> r.put(k, v) } } }

        admin.post("${MarketPaths.PANEL_ROOT}/settings/credits", changes).ok()
        try {
            return block()
        } finally {
            admin.post("${MarketPaths.PANEL_ROOT}/settings/credits", restore)
        }
    }

    @Test
    fun `CR-02 cashback is posted after a live payment and taken back by a full refund`() {
        val accounts = JsonArray().add(JsonObject().put("bank", "E2E Bank").put("holder", "E2E Store").put("iban", "DE89370400440532013000").put("currency", "EUR"))

        withCredit(JsonObject().put("cashbackPercent", 10.0)) {
            // 1. the instance runs in test mode and the fake gateway needs it: 07 section 9.1 excludes test orders from cashback, so no CASHBACK row
            val tester = buyer()
            val testOrder = checkout(tester.client, cart(line(product("10.00")))).ok().let { publicIdOf(it) }

            payViaFake(testOrder)
            awaitOrder(testOrder, "COMPLETED")

            val testOrderId = orderRow(testOrder).getLong("id")

            assertEquals(1L, orderRow(testOrder).getLong("testMode"), "a fake payment is a test order")
            assertEquals(0, cashbackRows(testOrderId, "CASHBACK").size, "a test-mode order earns no cashback")
            assertEquals(0.0, myCredits(tester.client).getDouble("balance"), 0.0001)

            // 2. a live payment: the bank transfer needs no gateway. The store is taken out of test mode only for this checkout and its approval (the
            // isolated instance has no live gateway, nothing can charge anyone), and put back in the finally of withSettings.
            val buyer = buyer(canPay = false)
            val productId = product("10.00")

            admin.post(
                "${MarketPaths.PANEL_ROOT}/payment-methods/bank-transfer",
                JsonObject().put("settings", JsonObject().put("accounts", accounts.encode()).put("instructions", "Transfer the exact amount."))
            ).ok()
            admin.post("${MarketPaths.PANEL_ROOT}/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", true)).ok()
            try {
                val publicId = session.withSettings(JsonObject().put("testMode", false)) {
                    val id = publicIdOf(checkout(buyer.client, cart(line(productId)), method = "bank-transfer").ok())

                    buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$id/bank-transfer/notify", JsonObject().put("senderName", "Ada").put("note", "paid today")).ok()
                    admin.post("${MarketPaths.PANEL_ROOT}/orders/${orderRow(id).getLong("id")}/bank-transfer", JsonObject().put("decision", "APPROVE")).ok()
                    awaitOrder(id, "COMPLETED")

                    id
                }
                val orderId = orderRow(publicId).getLong("id")

                assertEquals(0L, orderRow(publicId).getLong("testMode"), "the bank transfer order is a live order")

                // CASHBACK: 10 % of 10.00 at creditValue 1.0 = 1.00 credit, one transaction, on the payer's account
                val cashback = Await.untilValue(60_000, 500, "the cashback was posted") { cashbackRows(orderId, "CASHBACK").takeIf { it.isNotEmpty() } }

                assertEquals(1, cashback.size)
                assertEquals(100L, cashback[0].getLong("amount"))
                assertEquals(buyer.userId, cashback[0].getLong("userId"))
                assertEquals(1.0, myCredits(buyer.client).getDouble("balance"), 0.0001)
                assertEquals(1.0, entries(myCredits(buyer.client), "CASHBACK").single().getDouble("amount"), 0.0001)
                assertEquals(publicId, entries(myCredits(buyer.client), "CASHBACK").single().getString("orderPublicId"))

                // a full refund (bank transfers are refunded by hand): CASHBACK_REVERSAL takes the cashback back
                val refund = admin.post(
                    "${MarketPaths.PANEL_ROOT}/orders/$orderId/refunds", JsonObject().put("amount", 10.00).put("manual", true).put("reason", "returned to the sender"),
                    mapOf("Idempotency-Key" to idempotencyKey())
                ).ok().obj().getJsonObject("refund")

                assertEquals("SUCCEEDED", refund.getString("status"))
                assertEquals("REFUNDED", orderStatus(publicId))

                val reversal = Await.untilValue(60_000, 500, "the cashback was reversed") { cashbackRows(orderId, "CASHBACK_REVERSAL").takeIf { it.isNotEmpty() } }

                assertEquals(1, reversal.size)
                assertEquals(100L, reversal[0].getLong("amount"), "the whole cashback")
                assertEquals(buyer.userId, reversal[0].getLong("userId"))
                assertNotNull(reversal[0].getValue("refundId"), "the reversal belongs to the refund")
                assertEquals(0.0, myCredits(buyer.client).getDouble("balance"), 0.0001)
                assertEquals(0L, db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId))
            } finally {
                admin.post("${MarketPaths.PANEL_ROOT}/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", false))
            }
        }

        assertEquals(true, admin.get("${MarketPaths.PANEL_ROOT}/settings").ok().obj().getBoolean("testMode"), "the store is back in test mode")
        assertEquals(0.0, admin.get("${MarketPaths.PANEL_ROOT}/settings").ok().obj().getDouble("cashbackPercent"), 0.0001, "and the cashback is off again")
    }
}
