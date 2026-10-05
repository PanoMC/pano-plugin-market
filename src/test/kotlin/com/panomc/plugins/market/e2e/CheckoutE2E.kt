package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Checkout and payment, happy paths (17 section 9.2): P-02, P-03, P-05, P-06. Every scenario ends with the drain and the invariants (base class). */
class CheckoutE2E : E2eTestBase() {
    override val tag = "chk"

    private fun guestName(): String = "Guest_" + System.nanoTime().toString(36).takeLast(8)

    private fun guestBody(productId: Long, username: String): JsonObject =
        cart(line(productId)).put("guest", JsonObject().put("username", username).put("email", "${username.lowercase()}@example.com"))

    @Test
    fun `P-02 guest checkout`() {
        val vip = catalog.fresh("VIP")
        val free = catalog.fresh("FREE")

        // 06 section 6.7: a guest can never use a test-mode method, and the instance is in test mode, so the fake provider is refused for a guest
        // (17 section 9.2 writes "pay -> COMPLETED" for this scenario; that predates the rule, V-03 / 06 test 61: recorded in evidence/MK-080.md)
        val refused = checkout(visitor("guest"), guestBody(vip.id, guestName()))
        assertEquals(400, refused.status)
        assertEquals("PAYMENT_METHOD_UNAVAILABLE", refused.error)
        assertEquals("TEST_MODE", refused.json!!.getString("reason"))

        // the rest of the scenario with the one method a guest may use, the free provider: the order of a guest, its token and its two views
        val guest = visitor("guest")
        val username = guestName()
        val answer = checkout(guest, guestBody(free.id, username), method = null).ok()
        val publicId = publicIdOf(answer)
        val token = answer.obj().getString("orderToken")

        assertTrue(!token.isNullOrBlank(), "the order token is returned once")
        assertEquals("COMPLETED", answer.obj().getJsonObject("payment").getString("kind"))

        val row = orderRow(publicId)
        assertEquals(null, row.getValue("userId"), "a guest order has no user")
        assertEquals("g:" + username.lowercase(), row.getString("buyerKey"))
        assertEquals("COMPLETED", row.getString("status"))

        val limited = order(guest, publicId)
        assertEquals(true, limited.getBoolean("limited"), "without the token the order is the limited view")
        assertEquals(null, limited.getString("email"), "the limited view carries no e-mail")

        val full = order(guest, publicId, token)
        assertEquals(false, full.getBoolean("limited"), "with X-Order-Token the order is complete")
        assertEquals("${username.lowercase()}@example.com", full.getString("email"))
        assertEquals("COMPLETED", full.getString("status"))
    }

    @Test
    fun `P-02b registered buyer pays through the fake provider`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val answer = checkout(buyer.client, cart(line(vip.id))).ok()
        val publicId = publicIdOf(answer)

        assertEquals("PENDING", answer.obj().getJsonObject("order").getString("status"))
        assertEquals("REDIRECT", answer.obj().getJsonObject("payment").getString("kind"))
        assertEquals(1L, orderRow(publicId).getLong("testMode"), "an order paid through the fake provider is a test-mode order")

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")
        assertEquals("COMMITTED", orderRow(publicId).getString("reservationState"))
    }

    @Test
    fun `P-02c registered buyer without the PAY node is refused in test mode`() {
        val vip = catalog.fresh("VIP")
        val plain = buyer(canPay = false)

        val refused = checkout(plain.client, cart(line(vip.id)))
        assertEquals(400, refused.status)
        assertEquals("PAYMENT_METHOD_UNAVAILABLE", refused.error)
        assertEquals("TEST_MODE", refused.json!!.getString("reason"))
        assertEquals(0L, db.count("market_order", "`userId` = ?", plain.userId), "the refused checkout created no order")

        // a buyer of the paying group buys the same product: the refusal was the missing node, nothing else
        checkout(buyer().client, cart(line(vip.id))).ok()
    }

    @Test
    fun `P-03 guest checkout disabled`() {
        val vip = catalog.fresh("VIP")

        session.withSettings(JsonObject().put("allowGuestCheckout", false)) {
            val answer = checkout(visitor("guest"), guestBody(vip.id, guestName()))
            assertEquals(401, answer.status)
            assertEquals("NOT_LOGGED_IN", answer.error)
        }

        // the settings are back: a guest can check out again (a free product, the one method a guest may use in test mode)
        checkout(visitor("guest"), guestBody(catalog.fresh("FREE").id, guestName()), method = null).ok()
    }

    @Test
    fun `P-05 free product skips payment`() {
        val free = catalog.fresh("FREE")
        val buyer = buyer()
        val creates = gateway.requests(FakePayGateway.Op.CREATE).size

        val answer = checkout(buyer.client, cart(line(free.id)), method = null).ok()
        val publicId = publicIdOf(answer)

        assertEquals("COMPLETED", answer.obj().getJsonObject("payment").getString("kind"))
        assertEquals("free", orderRow(publicId).getString("paymentMethodId"))
        assertEquals("COMPLETED", answer.obj().getJsonObject("order").getString("status"), "the order is COMPLETED in the checkout response itself")
        assertEquals(creates, gateway.requests(FakePayGateway.Op.CREATE).size, "no attempt is sent to the gateway")
    }

    @Test
    fun `P-06 100 percent coupon`() {
        val vip = catalog.fresh("VIP")
        val (couponId, code) = catalog.freshCoupon(100)
        val buyer = buyer()
        val creates = gateway.requests(FakePayGateway.Op.CREATE).size
        val usedBefore = db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId)

        val answer = checkout(buyer.client, cart(line(vip.id)).put("couponCode", code), method = null).ok()
        val publicId = publicIdOf(answer)
        val row = orderRow(publicId)

        assertEquals(0L, row.getLong("totalPrice"), "the total is zero")
        assertEquals("free", row.getString("paymentMethodId"), "a zero total is paid by the free provider")
        assertEquals("COMPLETED", row.getString("status"))
        assertEquals((usedBefore ?: 0L) + 1, db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId))
        assertEquals(
            1L,
            db.count("market_redemption", "`kind` = 'COUPON' AND `refId` = ? AND `state` = 'APPLIED' AND `orderId` = ?", couponId, row.getLong("id")),
            "one APPLIED redemption of the coupon"
        )
        assertEquals(creates, gateway.requests(FakePayGateway.Op.CREATE).size, "nothing reaches the gateway")
        assertNotNull(answer.obj().getString("orderToken"))
        assertFalse(answer.obj().getString("orderToken").isBlank())
    }
}
