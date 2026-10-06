package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eBuyer
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checkout and payment, happy paths (17 section 9.2): P-01 to P-11 and P-12 to P-21 (tier upgrade, legal acceptance, VAT and billing info on the
 * invoice, gateway fee, method filtering, minimum order amount, bank transfer, manual order, credit pack, every start kind). Every scenario ends with the
 * drain and the invariants (base class). Settings that a scenario changes are global state of the one instance and are always put back (`withSettings`,
 * or a `finally`).
 */
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

    private val unique = java.util.concurrent.atomic.AtomicInteger()

    private fun slug(prefix: String) = "e2e-$prefix-${System.currentTimeMillis().toString(36)}-${unique.incrementAndGet()}"

    private fun cents(o: JsonObject, key: String): Long = Math.round(o.getDouble(key) * 100)

    private fun totals(view: JsonObject): JsonObject = view.getJsonObject("totals")

    private fun quote(client: E2eClient, body: JsonObject): JsonObject = client.post("/api/market/checkout/quote", body).ok().obj().getJsonObject("quote")

    private fun option(quote: JsonObject, methodId: String): JsonObject =
        quote.getJsonArray("paymentMethods").map { it as JsonObject }.firstOrNull { it.getString("id") == methodId }
            ?: throw AssertionError("the quote lists no payment method $methodId: ${quote.getJsonArray("paymentMethods").map { (it as JsonObject).getString("id") }}")

    private fun errorCodes(array: JsonArray?): List<String> = array?.map { it.toString() } ?: emptyList()

    private fun buy(client: E2eClient, body: JsonObject): String {
        val publicId = publicIdOf(checkout(client, body).ok())
        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")
        return publicId
    }

    /** Cancels an order the scenario leaves unpaid, so no `PENDING` order with a live gateway attempt outlives the run. */
    private fun cancel(client: E2eClient, publicId: String) {
        val answer = client.post("/api/market/orders/$publicId/cancel")
        assertTrue(answer.status == 200 || answer.status == 409, "cancel of $publicId answered ${answer.status} ${answer.error}")
    }

    private fun entitlements(userId: Long, productId: Long): List<Row> =
        db.sql("SELECT * FROM `pano_market_entitlement` WHERE `userId` = ? AND `productId` = ? ORDER BY `id`", userId, productId)

    private fun tierCategory(mode: String): Long {
        val name = "E2E tiers ${slug("cat")}"
        val answer = admin.multipart("POST", "/api/panel/market/categories", mapOf("name" to name, "tiered" to "true", "upgradeMode" to mode, "status" to "ACTIVE")).ok()
        return answer.obj().getLong("id")
    }

    private fun tierProduct(categoryId: Long, price: String, rank: Int): Long {
        val s = slug("tier$rank")
        return catalog.product(s, s, "Tier $rank $s", price = price, extra = mapOf("categoryId" to categoryId.toString(), "tierRank" to rank.toString()))
    }

    // --- P-12 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-12 tier upgrade pays the difference`() {
        val category = tierCategory("DIFFERENCE")
        val tier1 = tierProduct(category, "10.00", 1)
        val tier2 = tierProduct(category, "25.00", 2)
        val carol = buyer()

        val first = buy(carol.client, cart(line(tier1)))
        val owned = entitlements(carol.userId, tier1).single()
        assertEquals("ACTIVE", owned.getString("status"))
        assertEquals(1000L, owned.getLong("pricePaid"), "the entitlement remembers what was paid")
        assertEquals(0L, orderRow(first).getLong("upgradeDiscount"), "the first purchase of a tier is no upgrade")

        // the quote deducts what carol paid for the lower tier
        val quoted = quote(carol.client, cart(line(tier2)))
        assertEquals(1000L, cents(quoted, "upgradeDiscount"), "upgradeDiscount = what TIER1 cost")
        assertEquals(1500L, cents(quoted, "total"), "TIER2 (25.00) minus 10.00")
        assertTrue(quoted.getBoolean("canCheckout"))
        val quotedLine = quoted.getJsonArray("lines").map { it as JsonObject }.single { it.getLong("productId") == tier2 }
        assertEquals(1000L, cents(quotedLine, "upgradeAmount"))

        val answer = checkout(carol.client, cart(line(tier2)).put("expectedTotal", 15.0)).ok()
        val upgradeId = publicIdOf(answer)
        val row = orderRow(upgradeId)
        assertEquals(1000L, row.getLong("upgradeDiscount"))
        assertEquals(1500L, row.getLong("totalPrice"))
        val item = db.sql("SELECT * FROM `pano_market_order_item` WHERE `orderId` = ?", row.getLong("id")).single()
        assertEquals(owned.getLong("id"), item.getLong("upgradeFromEntitlementId"), "the line names the entitlement it replaces")
        assertEquals(1500L, db.sql("SELECT `amount` FROM `pano_market_payment` WHERE `reference` = ?", referenceOf(upgradeId)).single().getLong("amount"), "the attempt asks for the difference only")

        payViaFake(upgradeId)
        awaitOrder(upgradeId, "COMPLETED")
        Await.until(30_000, 250, "the old entitlement is marked UPGRADED") { entitlements(carol.userId, tier1).single().getString("status") == "UPGRADED" }

        val old = entitlements(carol.userId, tier1).single()
        val new = entitlements(carol.userId, tier2).single()
        assertEquals("ACTIVE", new.getString("status"))
        assertEquals(new.getLong("id"), old.getLong("replacedById"), "replacedById points at the new entitlement")
        assertEquals(2500L, new.getLong("pricePaid"), "the new tier is worth the full 25.00 (paid 15.00 + credited 10.00)")

        // category mode FULL: the lower tier is not credited, the replaced entitlement is still named
        val fullCategory = tierCategory("FULL")
        val full1 = tierProduct(fullCategory, "10.00", 1)
        val full2 = tierProduct(fullCategory, "25.00", 2)
        val dave = buyer()
        buy(dave.client, cart(line(full1)))
        val fullQuote = quote(dave.client, cart(line(full2)))
        assertEquals(0L, cents(fullQuote, "upgradeDiscount"))
        assertEquals(2500L, cents(fullQuote, "total"), "with upgradeMode FULL the whole 25.00 is due")

        val fullId = buy(dave.client, cart(line(full2)).put("expectedTotal", 25.0))
        val fullRow = orderRow(fullId)
        assertEquals(2500L, fullRow.getLong("totalPrice"))
        val fullItem = db.sql("SELECT * FROM `pano_market_order_item` WHERE `orderId` = ?", fullRow.getLong("id")).single()
        assertNotNull(fullItem.getValue("upgradeFromEntitlementId"), "FULL mode still replaces the lower entitlement")
        Await.until(30_000, 250, "the FULL-mode upgrade marks the old entitlement") { entitlements(dave.userId, full1).single().getString("status") == "UPGRADED" }
    }

    // --- P-13 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-13 legal acceptance`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val title = "Terms ${slug("legal")}"

        val v1 = admin.post("/api/panel/market/settings/legal", JsonObject().put("locale", "en-US").put("title", title).put("content", "<p>The terms, first version.</p>")).ok().obj()
        val firstId = v1.getLong("id")

        session.withSettings(JsonObject().put("legalTextRequired", true)) {
            val quoted = quote(buyer.client, cart(line(vip.id)))
            assertEquals(true, quoted.getJsonObject("legal").getBoolean("required"))
            assertEquals(firstId, quoted.getJsonObject("legal").getLong("id"), "the quote names the active text")

            // without acceptance, or with the id of another text: refused, no order
            val before = db.count("market_order", "`userId` = ?", buyer.userId)
            val refused = checkout(buyer.client, cart(line(vip.id)))
            assertEquals(400, refused.status)
            assertEquals("LEGAL_ACCEPTANCE_REQUIRED", refused.error)
            assertEquals(firstId, refused.obj().getLong("legalTextId"), "the answer names the text to accept")
            val wrong = checkout(buyer.client, cart(line(vip.id)).put("acceptLegal", true).put("legalTextId", firstId + 100_000))
            assertEquals(400, wrong.status)
            assertEquals("LEGAL_ACCEPTANCE_REQUIRED", wrong.error)
            val unticked = checkout(buyer.client, cart(line(vip.id)).put("acceptLegal", false).put("legalTextId", firstId))
            assertEquals(400, unticked.status)
            assertEquals(before, db.count("market_order", "`userId` = ?", buyer.userId), "a refused checkout creates no order")

            // with it: the order stores the id and the moment
            val answer = checkout(buyer.client, cart(line(vip.id)).put("acceptLegal", true).put("legalTextId", firstId)).ok()
            val publicId = publicIdOf(answer)
            val row = orderRow(publicId)
            assertEquals(firstId, row.getLong("legalTextId"))
            assertNotNull(row.getValue("legalAcceptedAt"))
            assertTrue(row.getLong("legalAcceptedAt") > 0)

            // a new version becomes the active one; the stored order keeps what its buyer accepted
            val secondId = admin.post("/api/panel/market/settings/legal", JsonObject().put("locale", "en-US").put("title", title).put("content", "<p>The terms, second version.</p>")).ok().obj().getLong("id")
            assertNotEquals(firstId, secondId)
            assertEquals(firstId, orderRow(publicId).getLong("legalTextId"), "a new legal version does not change the stored id")
            assertEquals(secondId, quote(buyer.client, cart(line(vip.id))).getJsonObject("legal").getLong("id"), "new checkouts see the new version")
            val stale = checkout(buyer.client, cart(line(vip.id)).put("acceptLegal", true).put("legalTextId", firstId))
            assertEquals(400, stale.status, "accepting the superseded text is not accepting the current one")
            assertEquals("LEGAL_ACCEPTANCE_REQUIRED", stale.error)

            cancel(buyer.client, publicId)
        }

        // the setting is back: no acceptance is needed any more
        val free = checkout(buyer.client, cart(line(catalog.fresh("FREE").id)), method = null).ok()
        assertNull(orderRow(publicIdOf(free)).getValue("legalTextId"), "no text is recorded while none is required")
    }

    // --- P-14 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-14 VAT and billing info on the invoice`() {
        val reduced = catalog.fresh("VIP", "vatPercent" to "8")
        val standard = catalog.fresh("LAST")
        val buyer = buyer()
        val body = { cart(line(reduced.id), line(standard.id)) }

        session.withSettings(JsonObject().put("billingInfoMode", "REQUIRED")) {
            val quoted = quote(buyer.client, body())
            val required = errorCodes(quoted.getJsonArray("requiredBuyerFields"))
            assertTrue(required.contains("billingInfo.firstName") && required.contains("billingInfo.line1"), "the quote lists the required billing fields: $required")

            val refused = checkout(buyer.client, body())
            assertEquals(400, refused.status)
            assertEquals("BUYER_INFO_REQUIRED", refused.error)
            val fields = errorCodes(refused.obj().getJsonArray("fields"))
            assertTrue(fields.contains("billingInfo.firstName") && fields.contains("billingInfo.country") && fields.contains("billingInfo.line1"), "fields: $fields")

            val billing = JsonObject().put("type", "INDIVIDUAL").put("firstName", "Ada").put("lastName", "Lovelace").put("country", "DE")
                .put("city", "Berlin").put("line1", "Unter den Linden 1").put("postalCode", "10117")
            val publicId = publicIdOf(checkout(buyer.client, body().put("billingInfo", billing)).ok())
            val order = orderRow(publicId)
            val stored = JsonObject(order.getString("billingInfo"))
            assertEquals("Ada", stored.getString("firstName"))
            assertEquals("Berlin", stored.getString("city"))

            payViaFake(publicId)
            awaitOrder(publicId, "COMPLETED")
            Await.until(30_000, 250, "the invoice is issued") { orderRow(publicId).getValue("invoiceId") != null }

            val paid = orderRow(publicId)
            val invoice = db.sql("SELECT * FROM `pano_market_invoice` WHERE `orderId` = ?", paid.getLong("id")).single()
            val snapshot = JsonObject(invoice.getString("snapshot"))
            val rows = snapshot.getJsonArray("vatRows").map { it as JsonObject }

            assertEquals(2, rows.size, "two VAT rows: 8 % and the default 20 %")
            assertEquals(listOf(800L, 2000L), rows.map { it.getLong("vatPercent") }.sorted())
            assertEquals(paid.getLong("totalPrice"), invoice.getLong("total"), "the invoice total is the order total")
            assertEquals(paid.getLong("totalPrice"), rows.sumOf { it.getLong("gross") }, "the VAT rows add up to the total")
            assertEquals(paid.getLong("vatTotal"), invoice.getLong("vatTotal"), "the invoice VAT is the order VAT")
            assertEquals(paid.getLong("vatTotal"), rows.sumOf { it.getLong("vat") })
            assertEquals(1500L, paid.getLong("totalPrice"))
            // 10.00 at 8 % and 5.00 at 20 %, VAT inside the price
            assertEquals(74L, rows.single { it.getLong("vatPercent") == 800L }.getLong("vat"))
            assertEquals(83L, rows.single { it.getLong("vatPercent") == 2000L }.getLong("vat"))
            assertEquals("Ada Lovelace", snapshot.getJsonObject("buyer").getString("name"), "the invoice carries the billing snapshot")
            assertEquals("DE", snapshot.getJsonObject("buyer").getString("country"))

            // the buyer can download it
            val pdf = buyer.client.get("/api/market/orders/$publicId/invoice")
            assertEquals(200, pdf.status)
            assertEquals("%PDF", String(pdf.body, 0, 4, Charsets.US_ASCII))
            assertTrue(pdf.header("Content-Disposition").orEmpty().startsWith("attachment"))
        }
    }

    // --- P-15 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-15 gateway fee passed to the buyer`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()

        admin.post("/api/panel/market/payment-methods/fake", JsonObject().put("config", JsonObject().put("feeMode", "BUYER").put("feePercent", 2.5).put("feeFixed", 0.30))).ok()
        try {
            // 2.5 % of 10.00 = 0.25, plus 0.30
            val quoted = quote(buyer.client, cart(line(vip.id)).put("paymentMethodId", "fake"))
            assertEquals(55L, cents(quoted, "paymentFee"), "the quote carries the fee")
            assertEquals(1055L, cents(quoted, "total"))
            assertEquals(55L, cents(option(quoted, "fake"), "feeAmount"), "the method option shows its fee")
            assertEquals(0L, cents(quote(buyer.client, cart(line(vip.id))), "paymentFee"), "no method chosen, no fee")

            val answer = checkout(buyer.client, cart(line(vip.id)).put("expectedTotal", 10.55)).ok()
            val publicId = publicIdOf(answer)
            val row = orderRow(publicId)
            assertEquals(55L, row.getLong("paymentFee"))
            assertEquals(1055L, row.getLong("totalPrice"))
            assertEquals(1055L, row.getLong("gatewayAmount"))
            val view = order(buyer.client, publicId)
            assertEquals(55L, cents(totals(view), "paymentFee"))
            assertEquals(1055L, cents(totals(view), "total"))
            assertEquals(1055L, db.sql("SELECT `amount` FROM `pano_market_payment` WHERE `reference` = ?", referenceOf(publicId)).single().getLong("amount"), "the attempt asks for goods plus fee")

            // another method on /pay: only the fee (and the credit part) is re-priced, the goods are not
            val eur = buyer.client.post("/api/market/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake-eur")).ok()
            assertNotNull(eur.obj().getJsonObject("payment"))
            val switched = orderRow(publicId)
            assertEquals(0L, switched.getLong("paymentFee"), "fake-eur charges no fee")
            assertEquals(1000L, switched.getLong("totalPrice"))
            assertEquals(1000L, switched.getLong("subtotal"), "the goods are untouched")
            assertEquals("fake-eur", switched.getString("paymentMethodId"))

            // and back: the fee returns and the order is paid for goods plus fee
            buyer.client.post("/api/market/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake")).ok()
            val back = orderRow(publicId)
            assertEquals(55L, back.getLong("paymentFee"))
            assertEquals(1055L, back.getLong("totalPrice"))
            assertEquals(1000L, back.getLong("subtotal"))

            payViaFake(publicId)
            awaitOrder(publicId, "COMPLETED")
            assertEquals(1055L, orderRow(publicId).getLong("paidAmount"), "the buyer paid goods plus fee")
        } finally {
            admin.post("/api/panel/market/payment-methods/fake", JsonObject().put("config", JsonObject().put("feeMode", "NONE").put("feePercent", 0).put("feeFixed", 0))).ok()
        }
    }

    // --- P-16 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-16 method filtering`() {
        val big = catalog.fresh("VIP", "price" to "600.00")
        val small = catalog.fresh("VIP", "price" to "10.00")
        val tiny = catalog.fresh("VIP", "price" to "0.50")
        val buyer = buyer()

        // 600.00 is above the 500.00 hard limit of fake-eur; fake has no limit
        val quoted = quote(buyer.client, cart(line(big.id)))
        val eur = option(quoted, "fake-eur")
        assertEquals(false, eur.getBoolean("available"))
        assertEquals("AMOUNT_ABOVE_MAXIMUM", eur.getString("unavailableReason"))
        assertEquals(true, option(quoted, "fake").getBoolean("available"), "the unlimited fake method stays available")

        // 0.50 is below the 1.00 minimum
        val under = option(quote(buyer.client, cart(line(tiny.id))), "fake-eur")
        assertEquals(false, under.getBoolean("available"))
        assertEquals("AMOUNT_BELOW_MINIMUM", under.getString("unavailableReason"))

        // inside the window it is offered
        assertEquals(true, option(quote(buyer.client, cart(line(small.id))), "fake-eur").getBoolean("available"))

        // a guest cannot use fake-eur at all (guests = false)
        val guest = option(quote(visitor("guest"), cart(line(small.id))), "fake-eur")
        assertEquals(false, guest.getBoolean("available"))
        assertEquals("GUESTS_NOT_SUPPORTED", guest.getString("unavailableReason"))

        // checkout with an unavailable method is refused and creates no order
        val before = db.count("market_order", "`userId` = ?", buyer.userId)
        val refused = checkout(buyer.client, cart(line(big.id)), method = "fake-eur")
        assertEquals(400, refused.status)
        assertEquals("PAYMENT_METHOD_UNAVAILABLE", refused.error)
        assertEquals("AMOUNT_ABOVE_MAXIMUM", refused.json!!.getString("reason"))
        assertEquals(before, db.count("market_order", "`userId` = ?", buyer.userId))

        val guestBody = cart(line(small.id)).put("guest", JsonObject().put("username", "Guest_${System.nanoTime().toString(36).takeLast(8)}").put("email", "guest@example.com"))
        val guestRefused = checkout(visitor("guest"), guestBody, method = "fake-eur")
        assertEquals(400, guestRefused.status)
        assertEquals("PAYMENT_METHOD_UNAVAILABLE", guestRefused.error)
        assertEquals("GUESTS_NOT_SUPPORTED", guestRefused.json!!.getString("reason"))

        // the same buyer pays the same cart with the method that fits: the refusal was the amount
        val ok = checkout(buyer.client, cart(line(big.id)), method = "fake").ok()
        cancel(buyer.client, publicIdOf(ok))
    }

    // --- P-17 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-17 minimum order amount`() {
        val three = catalog.fresh("VIP", "price" to "3.00")
        val six = catalog.fresh("VIP", "price" to "6.00")
        val buyer = buyer()

        session.withSettings(JsonObject().put("minimumOrderAmount", 5)) {
            val quoted = quote(buyer.client, cart(line(three.id)))
            assertEquals(false, quoted.getBoolean("canCheckout"), "the quote is a 200 that cannot be checked out")
            assertEquals(5L, cents(quoted, "minimumOrderAmount") / 100)

            val before = db.count("market_order", "`userId` = ?", buyer.userId)
            val refused = checkout(buyer.client, cart(line(three.id)))
            assertEquals(400, refused.status)
            assertEquals("MINIMUM_ORDER_AMOUNT_NOT_REACHED", refused.error)
            assertEquals(5.0, refused.obj().getDouble("minimum"), 0.0001)
            assertEquals(before, db.count("market_order", "`userId` = ?", buyer.userId), "no order was created")

            // a cart at or above the minimum goes through
            cancel(buyer.client, publicIdOf(checkout(buyer.client, cart(line(six.id))).ok()))
        }

        // the minimum is gone with the setting
        cancel(buyer.client, publicIdOf(checkout(buyer.client, cart(line(three.id))).ok()))
    }

    // --- P-18 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-18 bank transfer`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val accounts = JsonArray().add(JsonObject().put("bank", "E2E Bank").put("holder", "E2E Store").put("iban", "DE89370400440532013000").put("currency", "EUR"))

        admin.post("/api/panel/market/payment-methods/bank-transfer", JsonObject().put("settings", JsonObject().put("accounts", accounts.encode()).put("instructions", "Transfer the exact amount."))).ok()
        admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", true)).ok()
        try {
            assertEquals(true, option(quote(buyer.client, cart(line(vip.id))), "bank-transfer").getBoolean("available"))

            // approve
            val answer = checkout(buyer.client, cart(line(vip.id)), method = "bank-transfer").ok()
            val publicId = publicIdOf(answer)
            val start = answer.obj().getJsonObject("payment")
            assertEquals("INSTRUCTIONS", start.getString("kind"))
            val reference = referenceOf(publicId)
            val shown = start.getJsonObject("instructions").getJsonArray("fields").map { (it as JsonObject).getString("value") }
            assertTrue(shown.contains(reference), "the instructions carry the attempt reference as the transfer note: $shown")
            assertEquals("PENDING", attemptStatus(reference))
            assertEquals("PENDING", orderStatus(publicId))

            buyer.client.post("/api/market/orders/$publicId/bank-transfer/notify", JsonObject().put("senderName", "Ada").put("note", "paid today")).ok()
            assertEquals("PROCESSING", attemptStatus(reference), "the buyer's notice moves the attempt to PROCESSING")
            assertEquals("PENDING", orderStatus(publicId), "the order waits for the admin")

            val orderId = orderRow(publicId).getLong("id")
            admin.post("/api/panel/market/orders/$orderId/bank-transfer", JsonObject().put("decision", "APPROVE")).ok()
            awaitOrder(publicId, "COMPLETED")
            assertEquals("SUCCEEDED", attemptStatus(reference))
            assertNotNull(orderRow(publicId).getValue("paidAt"))
            assertEquals("COMMITTED", orderRow(publicId).getString("reservationState"))

            // reject: the attempt fails, the order stays open
            val second = publicIdOf(checkout(buyer.client, cart(line(vip.id)), method = "bank-transfer").ok())
            val secondReference = referenceOf(second)
            buyer.client.post("/api/market/orders/$second/bank-transfer/notify", JsonObject()).ok()
            admin.post("/api/panel/market/orders/${orderRow(second).getLong("id")}/bank-transfer", JsonObject().put("decision", "REJECT").put("note", "no money received")).ok()
            assertEquals("FAILED", attemptStatus(secondReference))
            assertEquals("PENDING", orderStatus(second), "a rejected transfer leaves the order PENDING until it expires")
            assertEquals("HELD", orderRow(second).getString("reservationState"))

            cancel(buyer.client, second)
            assertEquals("CANCELLED", orderStatus(second))
        } finally {
            admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", false)).ok()
        }
    }

    // --- P-19 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-19 manual order from the panel`() {
        val vip = catalog.fresh("VIP")
        val target = buyer()
        val body = JsonObject().put("playerUsername", target.username).put("items", JsonArray().add(line(vip.id))).put("markPaid", true).put("runDeliveries", true)
            .put("paymentLabel", "Cash").put("note", "e2e manual order")
        val key = idempotencyKey()

        val created = admin.post("/api/panel/market/orders", body, mapOf("Idempotency-Key" to key)).ok().obj()
        val publicId = created.getString("publicId")
        val id = created.getLong("id")
        val row = orderRow(publicId)

        assertEquals("PANEL", row.getString("source"))
        assertEquals(admin.userIdOrLookup(), row.getLong("createdBy"), "the order records the admin who created it")
        assertEquals("COMPLETED", row.getString("status"))
        assertEquals(target.userId, row.getLong("userId"))
        assertNotNull(row.getValue("paidAt"))

        Await.until(30_000, 250, "the deliveries of the manual order exist and are settled") {
            val rows = db.sql("SELECT `status` FROM `pano_market_delivery` WHERE `orderId` = ?", id)
            rows.size >= 2 && rows.all { it.getString("status") in setOf("CONFIRMED", "SENT") }
        }
        assertEquals(1L, entitlements(target.userId, vip.id).size.toLong(), "the buyer owns the product")

        // a replay with the same key and body is the same order, nothing is created twice
        val replay = admin.post("/api/panel/market/orders", body, mapOf("Idempotency-Key" to key)).ok().obj()
        assertEquals(id, replay.getLong("id"))
        assertEquals(publicId, replay.getString("publicId"))
        assertEquals(1L, db.count("market_order", "`userId` = ? AND `source` = 'PANEL'", target.userId), "one order for the replayed request")

        // no key: refused, nothing created
        val refused = admin.post("/api/panel/market/orders", body)
        assertEquals(400, refused.status)
        assertEquals(1L, db.count("market_order", "`userId` = ? AND `source` = 'PANEL'", target.userId))

        // the payer reads it as the owner of the order (GET /me/orders is not built yet, so the order view is the buyer-side proof)
        val view = order(target.client, publicId)
        assertEquals("COMPLETED", view.getString("status"))
        assertEquals(false, view.getBoolean("limited"))
    }

    // --- P-20 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-20 credit pack and top-up`() {
        val s = slug("pack")
        val pack = catalog.product(s, s, "500 credits $s", price = "5.00", extra = mapOf("kind" to "CREDIT_PACK", "creditAmount" to "500.00"))
        val buyer = buyer()

        // packs are only sold while credit top-up is on (the instance bootstrap leaves it off): on for this scenario, off again afterwards
        admin.post("/api/panel/market/settings/credits", JsonObject().put("creditTopUpEnabled", true)).ok()
        try {
            creditPackScenario(pack, buyer)
        } finally {
            admin.post("/api/panel/market/settings/credits", JsonObject().put("creditTopUpEnabled", false)).ok()
        }
    }

    private fun creditPackScenario(pack: Long, buyer: E2eBuyer) {
        val before = buyer.client.get("/api/market/me/credits").ok().obj().getDouble("balance")
        assertEquals(0.0, before, 0.0001)

        val publicId = buy(buyer.client, cart(line(pack)))

        Await.until(30_000, 250, "the pack's credits arrive") { buyer.client.get("/api/market/me/credits", log = false).ok().obj().getDouble("balance") >= 500.0 }
        val credits = buyer.client.get("/api/market/me/credits").ok().obj()
        assertEquals(500.0, credits.getDouble("balance"), 0.0001, "balance +500.00")
        val topUp = credits.getJsonArray("entries").map { it as JsonObject }.single { it.getString("type") == "TOPUP" }
        assertEquals(500.0, topUp.getDouble("amount"), 0.0001)
        assertEquals(publicId, topUp.getString("orderPublicId"))
        assertEquals(1L, db.count("market_credit_tx", "`type` = 'TOPUP' AND `orderId` = ?", orderRow(publicId).getLong("id")), "exactly one TOPUP ledger row")

        // a credit pack cannot be paid with credits, even by someone who has them
        val quoted = quote(buyer.client, cart(line(pack)))
        assertEquals(false, quoted.getJsonObject("credits").getBoolean("payableInCredits"))
        val ordersBefore = db.count("market_order", "`userId` = ?", buyer.userId)
        val refused = checkout(buyer.client, cart(line(pack)).put("payWithCredits", true), method = null)
        assertCreditTenderRefused(refused, "payWithCredits=true")
        // the same refusal through the other two ways of paying with credits: the method id `credits`, and `useCredits` on a pack
        assertCreditTenderRefused(checkout(buyer.client, cart(line(pack)), method = "credits"), "paymentMethodId=credits")
        val mixed = checkout(buyer.client, cart(line(pack)).put("useCredits", 1.0))
        assertEquals(400, mixed.status, "useCredits=1.00 next to a gateway method: ${mixed.error}")
        assertEquals("PAYMENT_METHOD_UNAVAILABLE", mixed.error, "a pack next to credits is refused by the credit tender check (A7, 05 test 55)")
        assertEquals("MIXED_CREDIT_NOT_SUPPORTED", mixed.json!!.getString("reason"))
        assertEquals(ordersBefore, db.count("market_order", "`userId` = ?", buyer.userId), "no refused checkout created an order")
        assertEquals(500.0, buyer.client.get("/api/market/me/credits").ok().obj().getDouble("balance"), 0.0001, "nothing was spent")
    }

    /**
     * Paying a credit pack with credits is refused by the line rule of the pack (A4, 05 section 8: line error `NOT_PAYABLE_WITH_CREDITS` => 400
     * `INVALID_CART`), which the credit tender check (A7, `PAYMENT_METHOD_UNAVAILABLE`) never gets to: assert that exact refusal, not just a 400.
     */
    private fun assertCreditTenderRefused(refused: E2eResponse, how: String) {
        assertEquals(400, refused.status, "$how: ${refused.error}")
        assertEquals("INVALID_CART", refused.error, how)
        val lineErrors = refused.json!!.getJsonObject("lineErrors")
        assertTrue(lineErrors != null && !lineErrors.isEmpty, "$how: the refusal names the pack's line: ${refused.json}")
        assertTrue(lineErrors.fieldNames().all { key -> lineErrors.getJsonArray(key).list.contains("NOT_PAYABLE_WITH_CREDITS") }, "$how: line error NOT_PAYABLE_WITH_CREDITS: $lineErrors")
    }

    // --- P-21 ------------------------------------------------------------------------------------------------------------

    private fun setStartKind(kind: String) {
        admin.post("/api/panel/market/payment-methods/fake", JsonObject().put("settings", JsonObject().put("startKind", kind))).ok()
    }

    @Test
    fun `P-21 every start kind`() {
        val vip = catalog.fresh("VIP")
        // L4 (11 section 11): at most three unpaid held orders per buyer, so every start kind gets a buyer of its own
        val open = ArrayList<Pair<E2eClient, String>>()
        val origin = gateway.baseUrl.trimEnd('/')
        lateinit var buyer: E2eBuyer

        fun start(kind: String): Pair<String, JsonObject> {
            setStartKind(kind)
            buyer = buyer()
            val answer = checkout(buyer.client, cart(line(vip.id))).ok()
            val publicId = publicIdOf(answer)
            open += buyer.client to publicId
            val payment = answer.obj().getJsonObject("payment")
            assertEquals(kind, payment.getString("kind"), "checkout answers a $kind start")
            return publicId to payment
        }

        fun page(url: String): E2eResponse {
            assertTrue(url.startsWith("/api/market/payments/attempts/") && url.endsWith("/page"), "market serves the page itself: $url")
            return visitor("browser").get(url)
        }

        try {
            // REDIRECT is the default and goes to the gateway
            val redirect = start("REDIRECT").second
            assertTrue(redirect.getString("url").startsWith(origin), "a redirect points at the gateway: ${redirect.getString("url")}")

            // FORM_POST: market's own auto-submitting document, form-action limited to the gateway origin
            val (formOrder, form) = start("FORM_POST")
            val formPage = page(form.getString("url")).also { assertEquals(200, it.status) }
            assertEquals("no-store", formPage.header("Cache-Control"))
            val formCsp = formPage.header("Content-Security-Policy").orEmpty()
            assertTrue(formCsp.contains("form-action $origin"), "form-action is the gateway origin only: $formCsp")
            assertTrue(formCsp.contains("default-src 'none'"), formCsp)
            assertTrue(formPage.text.contains("<form") && formPage.text.contains(referenceOf(formOrder)), "the document posts the attempt reference")

            // HTML: the gateway's document in a sandbox without allow-same-origin
            val html = start("HTML").second
            val htmlPage = page(html.getString("url")).also { assertEquals(200, it.status) }
            assertEquals("no-store", htmlPage.header("Cache-Control"))
            val htmlCsp = htmlPage.header("Content-Security-Policy").orEmpty()
            assertTrue(htmlCsp.startsWith("sandbox allow-scripts allow-forms allow-top-navigation allow-popups"), "sandbox first: $htmlCsp")
            assertFalse(htmlCsp.contains("allow-same-origin"), "the document never runs with the site's origin: $htmlCsp")
            assertTrue(htmlPage.text.contains("Fake gateway payment"))

            // an unknown token is a 404
            assertEquals(404, visitor("browser").get("/api/market/payments/attempts/${"0".repeat(32)}/page").status)

            // IFRAME, INSTRUCTIONS, EMBEDDED are rendered by the order page from OrderView.payment.start
            val (iframeOrder, iframe) = start("IFRAME")
            assertTrue(iframe.getJsonObject("iframe").getString("url").startsWith(origin))
            val iframeView = order(buyer.client, iframeOrder).getJsonObject("payment").getJsonObject("start")
            assertEquals("IFRAME", iframeView.getString("kind"))
            assertEquals(iframe.getJsonObject("iframe").getString("url"), iframeView.getJsonObject("iframe").getString("url"))

            val (instructionsOrder, instructions) = start("INSTRUCTIONS")
            val values = instructions.getJsonObject("instructions").getJsonArray("fields").map { (it as JsonObject).getString("value") }
            assertTrue(values.contains(referenceOf(instructionsOrder)), "the instructions carry the reference: $values")
            val instructionsView = order(buyer.client, instructionsOrder).getJsonObject("payment").getJsonObject("start")
            assertEquals("INSTRUCTIONS", instructionsView.getString("kind"))
            assertNotNull(instructionsView.getJsonObject("instructions"))

            val (embeddedOrder, embedded) = start("EMBEDDED")
            assertNotNull(embedded.getJsonObject("embedded"))
            val embeddedView = order(buyer.client, embeddedOrder).getJsonObject("payment").getJsonObject("start")
            assertEquals("EMBEDDED", embeddedView.getString("kind"))
            assertEquals(embedded.getJsonObject("embedded").getString("component"), embeddedView.getJsonObject("embedded").getString("component"))

            // no start of any kind confirms anything: every order is still waiting for the webhook
            open.forEach { (_, publicId) -> assertEquals("PENDING", orderStatus(publicId)) }
        } finally {
            setStartKind("REDIRECT")
            open.forEach { (client, publicId) -> cancel(client, publicId) }
        }
    }

    // --- shared helpers of P-01, P-04 and P-07 to P-11 -------------------------------------------------------------------

    private fun hookName(prefix: String) = prefix + System.nanoTime().toString(36).takeLast(8)

    private fun rawNode(prefix: String) = "essentials.e2e$prefix${System.currentTimeMillis().toString(36)}${unique.incrementAndGet()}"

    private fun action(id: String, type: String, value: Any, phase: String = "GRANT"): JsonObject =
        JsonObject().put("id", id).put("type", type).put("phase", phase).put("value", value)

    /** A raw game node written to the Pano permission tables (`via = PANO`), the automatic inverse removes it again. */
    private fun permissionAction(id: String, node: String): JsonObject = action(id, "PERMISSION", JsonArray().add(node)).put("via", "PANO")

    private fun holdsNode(userId: Long, node: String): Boolean =
        db.count("permission_node", "`holderType` = 'USER' AND `holderId` = ? AND `node` = ? AND `active` = 1", userId, node) > 0

    private fun credits(client: E2eClient): Double = client.get("/api/market/me/credits").ok().obj().getDouble("balance")

    private fun grantCredits(userId: Long, amount: Number) {
        admin.post(
            "/api/panel/market/credits/accounts/$userId/grant", JsonObject().put("amount", amount).put("note", "e2e checkout seed"), mapOf("Idempotency-Key" to idempotencyKey())
        ).ok()
    }

    private fun deliveriesOf(orderId: Long, phase: String): List<Row> =
        db.sql("SELECT * FROM `pano_market_delivery` WHERE `orderId` = ? AND `phase` = ? ORDER BY `id`", orderId, phase)

    /** Waits until at least [atLeast] delivery rows of [phase] exist and none is open any more (the delivery job ran them). */
    private fun settledDeliveries(orderId: Long, phase: String, atLeast: Int): List<Row> =
        Await.untilValue(120_000, 500, "the $phase deliveries of order $orderId are settled") {
            deliveriesOf(orderId, phase).takeIf { rows -> rows.size >= atLeast && rows.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING") } }
        }

    /** A WEBHOOK delivery is `SENT` until the webhook job got its answer (then `CONFIRMED`): wait until every row of [phase] is `CONFIRMED`. */
    private fun confirmedDeliveries(orderId: Long, phase: String, atLeast: Int): List<Row> =
        Await.untilValue(120_000, 500, "the $phase deliveries of order $orderId are CONFIRMED") {
            deliveriesOf(orderId, phase).takeIf { rows -> rows.size >= atLeast && rows.all { it.getString("status") == "CONFIRMED" } }
        }

    /** 17 section 8.3: every mail row of the order ends in a terminal state ({SENT, FAILED, SKIPPED}) without help from the scenario. */
    private fun assertMailsTerminal(orderId: Long) {
        Await.until(30_000, 250, "every mail row of order $orderId is terminal") {
            db.sql("SELECT `status` FROM `pano_market_mail_outbox` WHERE `orderId` = ?", orderId).all { it.getString("status") in setOf("SENT", "FAILED", "SKIPPED") }
        }
        assertTrue(db.count("market_mail_outbox", "`orderId` = ?", orderId) > 0L, "the order has mail rows")
    }

    private fun mailsOf(orderId: Long, kind: String): List<Row> =
        db.sql("SELECT * FROM `pano_market_mail_outbox` WHERE `orderId` = ? AND `kind` = ? ORDER BY `id`", orderId, kind)

    private fun verifySignature(request: com.panomc.plugins.market.spi.testkit.Recorded, secret: String) {
        val header = request.header("X-Pano-Signature") ?: throw AssertionError("no X-Pano-Signature")
        val parts = header.split(',').associate { it.substringBefore('=') to it.substringAfter('=') }

        assertEquals(FakePayGateway.hmac(secret, parts.getValue("t").toLong(), request.body), parts.getValue("v1"), "the signature verifies with the endpoint secret")
    }

    private fun events(hook: String): List<JsonObject> = gateway.hooks(hook).map { JsonObject(it.bodyText()) }

    private fun storeWebhook(hook: String, secret: String, vararg events: String): Long = admin.post(
        "/api/panel/market/webhooks",
        JsonObject().put("name", "E2E $hook").put("url", "${gateway.baseUrl}/hooks/$hook").put("events", JsonArray(events.toList())).put("format", "JSON")
            .put("signing", "HMAC_SHA256").put("secret", secret)
    ).ok().obj().getLong("id")

    // --- P-01 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-01 full path browse cart checkout webhook delivery refund`() {
        val storeHook = hookName("p01s")
        val actionHook = hookName("p01a")
        val storeSecret = "whsec_e2e_p01s_" + System.nanoTime().toString(36)
        val actionSecret = "whsec_e2e_p01a_" + System.nanoTime().toString(36)
        val node = rawNode("p01")
        val vip = catalog.fresh(
            "VIP",
            "actions" to JsonArray()
                .add(action("a1", "CREDIT", 2.5))
                .add(permissionAction("a2", node))
                .add(
                    action("a3", "WEBHOOK", JsonObject().put("url", "${gateway.baseUrl}/hooks/$actionHook").put("format", "JSON").put("signing", "HMAC_SHA256").put("secret", actionSecret))
                ).encode()
        )
        val endpointId = storeWebhook(storeHook, storeSecret, "order.paid", "order.refunded")

        try {
            val alice = buyer()
            val http = alice.client

            // browse: the product page, then the server cart, then the quote of that cart
            val detail = visitor("anon").get("/api/market/products/${vip.slug}").ok().obj().getJsonObject("product")
            assertEquals(vip.slug, detail.getString("slug"))
            http.post("/api/market/me/cart/items", line(vip.id)).ok()
            val quoted = quote(http, JsonObject())
            assertEquals(1000L, cents(quoted, "total"), "VIP costs 10.00")
            assertTrue(quoted.getBoolean("canCheckout"))

            // checkout from the server cart through the fake provider
            val key = idempotencyKey()
            val answer = checkout(http, JsonObject().put("expectedTotal", 10.0), key = key).ok()
            val publicId = publicIdOf(answer)
            assertEquals("PENDING", answer.obj().getJsonObject("order").getString("status"))
            assertEquals("REDIRECT", answer.obj().getJsonObject("payment").getString("kind"))
            val reference = referenceOf(publicId)
            assertEquals("PENDING", orderStatus(publicId))
            val replay = checkout(http, JsonObject().put("expectedTotal", 10.0), key = key).ok()
            assertEquals(publicId, publicIdOf(replay), "the same Idempotency-Key is the same order")

            // the gateway confirms: webhook -> order COMPLETED
            payViaFake(publicId)
            awaitOrder(publicId, "COMPLETED")
            val row = orderRow(publicId)
            val orderId = row.getLong("id")
            assertNotNull(row.getValue("paidAt"))
            assertEquals("COMMITTED", row.getString("reservationState"))
            assertEquals("SUCCEEDED", attemptStatus(reference))
            assertEquals(1000L, row.getLong("paidAmount"))

            // deliveries: PERMISSION + CREDIT + WEBHOOK, all confirmed
            val grants = confirmedDeliveries(orderId, "GRANT", 3)
            assertEquals(setOf("PERMISSION", "CREDIT", "WEBHOOK"), grants.map { it.getString("actionType") }.toSet())
            assertTrue(holdsNode(alice.userId, node), "the permission was granted")
            Await.until(30_000, 250, "the credit of the action arrived") { credits(http) >= 2.5 }
            assertEquals(2.5, credits(http), 0.0001, "alice's balance +2.50")
            assertEquals(1L, db.count("market_credit_tx", "`type` = 'ACTION' AND `orderId` = ?", orderId))
            val entitlement = entitlements(alice.userId, vip.id).single()
            assertEquals("ACTIVE", entitlement.getString("status"))

            // the action webhook reached its sink once, signed with the action's own secret
            Await.until(60_000, 500, "the action webhook reached its sink") { gateway.hooks(actionHook).isNotEmpty() }
            val actionCalls = gateway.hooks(actionHook)
            assertEquals(1, actionCalls.size, "the WEBHOOK delivery was sent once")
            verifySignature(actionCalls.single(), actionSecret)

            // invoice: a row and a PDF the buyer can download
            Await.until(30_000, 250, "the invoice is issued") { orderRow(publicId).getValue("invoiceId") != null }
            val invoice = db.sql("SELECT * FROM `pano_market_invoice` WHERE `orderId` = ? AND `type` = 'INVOICE'", orderId).single()
            assertEquals(1000L, invoice.getLong("total"))
            val pdf = http.get("/api/market/orders/$publicId/invoice")
            assertEquals(200, pdf.status)
            assertEquals("%PDF", String(pdf.body, 0, 4, Charsets.US_ASCII))
            assertTrue(pdf.header("Content-Disposition").orEmpty().startsWith("attachment"))

            // mail: one ORDER_CONFIRMATION
            Await.until(30_000, 250, "the confirmation mail is queued") { mailsOf(orderId, "ORDER_CONFIRMATION").isNotEmpty() }
            assertEquals(1, mailsOf(orderId, "ORDER_CONFIRMATION").size)
            assertEquals("${alice.username}@example.com", mailsOf(orderId, "ORDER_CONFIRMATION").single().getString("recipient"))

            // the store webhook order.paid with a valid signature
            Await.until(60_000, 500, "order.paid reached the sink") { events(storeHook).any { it.getString("event") == "order.paid" } }
            val paid = gateway.hooks(storeHook).single { JsonObject(it.bodyText()).getString("event") == "order.paid" }
            verifySignature(paid, storeSecret)
            assertEquals(publicId, JsonObject(paid.bodyText()).getJsonObject("data").getJsonObject("order").getString("publicId"))

            // the panel refunds the whole order: once at the gateway, with the refund's idempotency key
            val refundCalls = gateway.requests(FakePayGateway.Op.REFUND).size
            val refundKey = idempotencyKey()
            val refunded = admin.post("/api/panel/market/orders/$orderId/refunds", JsonObject().put("amount", 10.00).put("revoke", true), mapOf("Idempotency-Key" to refundKey)).ok()
            assertEquals("SUCCEEDED", refunded.obj().getJsonObject("refund").getString("status"))
            assertEquals(refundCalls + 1, gateway.requests(FakePayGateway.Op.REFUND).size, "the gateway was asked once")
            assertEquals(refundKey, db.sql("SELECT `idempotencyKey` FROM `pano_market_refund` WHERE `orderId` = ?", orderId).single().getString("idempotencyKey"))
            // the provider call of THIS order's refund (picked by the order's gateway reference) carries the refund's own idempotency key, so a retry cannot refund twice
            val storedKey = db.sql("SELECT `idempotencyKey` FROM `pano_market_refund` WHERE `orderId` = ?", orderId).single().getString("idempotencyKey")
            val gatewayPaymentId = gateway.payments.getValue(reference).id
            val ownCalls = gateway.requests(FakePayGateway.Op.REFUND).filter { JsonObject(it.bodyText()).getString("paymentId") == gatewayPaymentId }
            assertEquals(1, ownCalls.size, "exactly one provider refund call names this order's gateway payment $gatewayPaymentId")
            assertEquals(storedKey, ownCalls.single().header("Idempotency-Key"), "the gateway call carries the refund's idempotency key")
            assertEquals("REFUNDED", orderStatus(publicId))

            // the undo rows: permission gone, credits taken back (ACTION_REVERSAL), entitlement REVOKED
            val revokes = confirmedDeliveries(orderId, "REVOKE", 2)
            assertTrue(revokes.map { it.getString("actionType") }.containsAll(listOf("PERMISSION", "CREDIT")), "undo rows: ${revokes.map { it.getString("actionType") }}")
            assertFalse(holdsNode(alice.userId, node), "the permission was removed")
            assertEquals(0.0, credits(http), 0.0001, "the 2.50 credits were taken back")
            assertEquals(1L, db.count("market_credit_tx", "`type` = 'ACTION_REVERSAL' AND `orderId` = ?", orderId))
            Await.until(30_000, 250, "the entitlement is REVOKED") { entitlements(alice.userId, vip.id).single().getString("status") == "REVOKED" }

            // credit note, order.refunded webhook, ORDER_REFUNDED mail
            Await.until(30_000, 250, "the credit note is issued") { db.count("market_invoice", "`orderId` = ? AND `type` = 'CREDIT_NOTE'", orderId) == 1L }
            Await.until(60_000, 500, "order.refunded reached the sink") { events(storeHook).any { it.getString("event") == "order.refunded" } }
            val refundedHook = gateway.hooks(storeHook).single { JsonObject(it.bodyText()).getString("event") == "order.refunded" }
            verifySignature(refundedHook, storeSecret)
            Await.until(30_000, 250, "the refund mail is queued") { mailsOf(orderId, "ORDER_REFUNDED").isNotEmpty() }
            assertEquals(1, mailsOf(orderId, "ORDER_REFUNDED").size)
            assertMailsTerminal(orderId)
        } finally {
            admin.delete("/api/panel/market/webhooks/$endpointId")
        }
    }

    // --- P-04 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-04 gift purchase`() {
        val node = rawNode("p04")
        val gift = catalog.fresh(
            "LIMITED",
            "actions" to JsonArray().add(permissionAction("a1", node)).encode()
        )
        val alice = buyer()
        val bob = buyer()

        val body = cart(line(gift.id)).put("recipientUsername", bob.username).put("giftMessage", "Enjoy!")
        val publicId = publicIdOf(checkout(alice.client, body).ok())
        val row = orderRow(publicId)

        assertEquals(1L, row.getLong("isGift"))
        assertEquals(alice.userId, row.getLong("userId"), "alice pays")
        assertEquals(bob.userId, row.getLong("recipientUserId"), "bob receives")
        assertEquals("Enjoy!", row.getString("giftMessage"))

        // an unpaid gift is invisible to the recipient
        assertTrue(bob.client.get("/api/market/me/orders").ok().obj().getJsonArray("orders").none { (it as JsonObject).getString("publicId") == publicId })

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")
        val orderId = orderRow(publicId).getLong("id")

        // deliveries and entitlement target bob
        val grants = settledDeliveries(orderId, "GRANT", 1)
        assertTrue(grants.all { it.getString("status") == "CONFIRMED" })
        assertTrue(holdsNode(bob.userId, node), "bob got the permission")
        assertFalse(holdsNode(alice.userId, node), "alice did not")
        assertEquals(1, entitlements(bob.userId, gift.id).size, "the entitlement belongs to bob")
        assertEquals(0, entitlements(alice.userId, gift.id).size)

        // the mail goes to bob
        Await.until(30_000, 250, "the gift mail is queued") { mailsOf(orderId, "GIFT_RECEIVED").isNotEmpty() }
        assertEquals("${bob.username}@example.com", mailsOf(orderId, "GIFT_RECEIVED").single().getString("recipient"))
        assertMailsTerminal(orderId)

        // bob's order list shows it as received, alice's as her own
        val bobs = bob.client.get("/api/market/me/orders").ok().obj().getJsonArray("orders").map { it as JsonObject }.single { it.getString("publicId") == publicId }
        assertEquals(true, bobs.getBoolean("received"))
        assertEquals(true, bobs.getBoolean("isGift"))
        val alices = alice.client.get("/api/market/me/orders").ok().obj().getJsonArray("orders").map { it as JsonObject }.single { it.getString("publicId") == publicId }
        assertEquals(false, alices.getBoolean("received"))

        // the limit (one per player) is counted on the recipient: another gift of the product to bob is refused, a purchase for alice is not
        val second = checkout(alice.client, cart(line(gift.id)).put("recipientUsername", bob.username))
        assertEquals(409, second.status, "a second one-per-player gift to bob: ${second.error} ${second.json}")
        assertEquals("PURCHASE_LIMIT_REACHED", second.error)
        assertEquals(gift.id, second.obj().getLong("productId"))
        assertEquals(1, second.obj().getInteger("limit"))
        val forAlice = checkout(alice.client, cart(line(gift.id))).ok()
        cancel(alice.client, publicIdOf(forAlice))
    }

    // --- P-07 ------------------------------------------------------------------------------------------------------------

    /** The revenue formula of 00 section 6.8, in minor units, over every paid (not test) order of the instance. */
    private fun gatewayRevenue(): Long = db.long(
        "SELECT COALESCE(SUM(`gatewayAmount` - `refundedGatewayAmount`), 0) FROM `pano_market_order` WHERE `testMode` = 0 AND `paidAt` IS NOT NULL AND `status` IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')"
    ) ?: 0L

    @Test
    fun `P-07 credits-only payment`() {
        val node = rawNode("p07")
        val vip = catalog.fresh("VIP", "actions" to JsonArray().add(action("a1", "CREDIT", 2.5)).add(permissionAction("a2", node)).encode())
        val alice = buyer()
        grantCredits(alice.userId, 100)
        assertEquals(100.0, credits(alice.client), 0.0001)
        val revenueBefore = gatewayRevenue()
        val creates = gateway.requests(FakePayGateway.Op.CREATE).size

        // stats revenue (17 section 9.2): the panel stats only count paid orders that are NOT test orders, so the credits-only order is placed with the store
        // out of test mode (nothing needs a gateway: payWithCredits asks none), put back in the finally of withSettings. Its total, weekly and monthly revenue
        // are read through the panel route before the checkout and after COMPLETED.
        val statsBefore = stats()
        val publicId = session.withSettings(JsonObject().put("testMode", false)) {
            val id = publicIdOf(checkout(alice.client, cart(line(vip.id)).put("payWithCredits", true), method = null).ok())

            awaitOrder(id, "COMPLETED")
            id
        }
        val row = orderRow(publicId)
        val orderId = row.getLong("id")

        assertEquals(0L, row.getLong("testMode"), "the order is a live order: the stats do count it")
        assertEquals("credits", row.getString("paymentMethodId"))
        assertEquals(0L, row.getLong("gatewayAmount"), "nothing is asked of the gateway")
        assertEquals(1000L, row.getLong("creditAmount"), "10.00 credits")
        assertEquals(creates, gateway.requests(FakePayGateway.Op.CREATE).size, "no attempt reached the gateway")

        // ledger: the hold, then the capture
        val tx = db.sql("SELECT `type` FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` IN ('HOLD', 'CAPTURE') ORDER BY `id`", orderId).map { it.getString("type") }
        assertEquals(listOf("HOLD", "CAPTURE"), tx, "the credits were held, then captured")

        // 100.00 - 10.00 + the 2.50 of the action
        settledDeliveries(orderId, "GRANT", 2)
        Await.until(30_000, 250, "the action credit arrived") { credits(alice.client) >= 92.5 }
        assertEquals(92.5, credits(alice.client), 0.0001)
        assertTrue(holdsNode(alice.userId, node))

        // revenue = gatewayAmount - refundedGatewayAmount of paid, non-test orders (00 section 6.8): credit-paid value is not revenue. The route is the
        // primary check (the order IS counted as an order, and adds nothing to any revenue figure), the SQL formula the secondary one.
        val statsAfter = stats()
        assertEquals(statsBefore.total.getLong("count") + 1, statsAfter.total.getLong("count"), "the live credits-only order is counted by the stats")
        assertEquals(statsBefore.total.getDouble("revenue"), statsAfter.total.getDouble("revenue"), 0.0001, "paying with credits moves no total revenue")
        assertEquals(statsBefore.weekly.getDouble("revenue"), statsAfter.weekly.getDouble("revenue"), 0.0001, "nor the weekly revenue")
        assertEquals(statsBefore.monthly.getDouble("revenue"), statsAfter.monthly.getDouble("revenue"), 0.0001, "nor the monthly revenue")
        assertEquals(0L, row.getLong("gatewayAmount") - orderRow(publicId).getLong("refundedGatewayAmount"), "the order contributes no revenue")
        assertEquals(revenueBefore, gatewayRevenue(), "paying with credits moves no store revenue (SQL formula)")
    }

    private class StatsSummary(val total: JsonObject, val weekly: JsonObject, val monthly: JsonObject)

    /** `GET /api/panel/market/stats`: the three summary blocks (`count`, `revenue`, ...). */
    private fun stats(): StatsSummary {
        val summary = admin.get("/api/panel/market/stats").ok().obj().getJsonObject("summary")

        return StatsSummary(summary.getJsonObject("total"), summary.getJsonObject("weekly"), summary.getJsonObject("monthly"))
    }

    // --- P-08 ------------------------------------------------------------------------------------------------------------

    private fun <T> withShipping(block: (Long) -> T): T {
        val others = admin.get("/api/panel/market/shipping/zones").ok().obj().getJsonArray("zones").map { it as JsonObject }.filter { it.getString("status") == "ACTIVE" }.map { it.getLong("id") }

        others.forEach { admin.put("/api/panel/market/shipping/zones/$it", JsonObject().put("status", "INACTIVE")).ok() }
        try {
            val zoneId = admin.post(
                "/api/panel/market/shipping/zones", JsonObject().put("name", "E2E P-08 zone ${unique.incrementAndGet()}").put("countries", JsonArray().add("DE")).put("status", "ACTIVE")
            ).ok().obj().getLong("id")

            try {
                val methodId = admin.post(
                    "/api/panel/market/shipping/methods",
                    JsonObject().put("name", "E2E P-08 method ${unique.get()}").put("providerId", "manual").put("rateSource", "RULES").put("status", "ACTIVE")
                        .put("rates", JsonArray().add(JsonObject().put("zoneId", zoneId).put("basis", "WEIGHT").put("rangeFrom", 0).put("rangeTo", 1999).put("price", 4.9)))
                ).ok().obj().getLong("id")

                try {
                    return block(methodId)
                } finally {
                    admin.delete("/api/panel/market/shipping/methods/$methodId")
                }
            } finally {
                admin.delete("/api/panel/market/shipping/zones/$zoneId")
            }
        } finally {
            others.forEach { admin.put("/api/panel/market/shipping/zones/$it", JsonObject().put("status", "ACTIVE")) }
        }
    }

    @Test
    fun `P-08 mixed credit and gateway`() {
        val vip = catalog.fresh("VIP")
        val s = slug("shirt")
        val shirt = catalog.product(s, s, "Shirt $s", price = "20.00", stock = 10, extra = mapOf("physical" to "true", "weightGrams" to "250"))
        val address = JsonObject().put("firstName", "Ada").put("lastName", "Lovelace").put("phone", "+4915112345678").put("country", "DE")
            .put("city", "Berlin").put("line1", "Unter den Linden 1").put("postalCode", "10117")
        val alice = buyer()

        grantCredits(alice.userId, 10)
        assertEquals(10.0, credits(alice.client), 0.0001)

        withShipping { methodId ->
            fun body() = cart(line(vip.id), line(shirt)).put("shippingAddress", address).put("shippingMethodId", methodId)

            // never applied without useCredits
            val without = quote(alice.client, body())
            assertEquals(0L, cents(without.getJsonObject("credits"), "applied"), "credits are not spent unless the buyer asks")
            assertEquals(3490L, cents(without, "total"), "30.00 of goods + 4.90 shipping")
            assertEquals(3490L, cents(without, "gatewayAmount"), "the whole total goes to the gateway")

            val withCredits = quote(alice.client, body().put("useCredits", 10))
            assertEquals(3490L, cents(withCredits, "total"))
            assertEquals(1000L, cents(withCredits.getJsonObject("credits"), "applied"))
            assertEquals(1000L, cents(withCredits.getJsonObject("credits"), "appliedValue"))
            assertEquals(2490L, cents(withCredits, "gatewayAmount"), "gatewayAmount = total - 10.00")

            val publicId = publicIdOf(checkout(alice.client, body().put("useCredits", 10)).ok())
            val row = orderRow(publicId)
            val orderId = row.getLong("id")

            assertEquals(3490L, row.getLong("totalPrice"))
            assertEquals(1000L, row.getLong("creditValue"), "creditValue = 10.00")
            assertEquals(2490L, row.getLong("gatewayAmount"), "gatewayAmount = total - 10.00")
            assertEquals(490L, row.getLong("shippingTotal"))
            assertEquals(2490L, db.sql("SELECT `amount` FROM `pano_market_payment` WHERE `reference` = ?", referenceOf(publicId)).single().getLong("amount"), "the attempt asks for the gateway part only")
            assertEquals("HOLD", db.sql("SELECT `type` FROM `pano_market_credit_tx` WHERE `orderId` = ? ORDER BY `id`", orderId).first().getString("type"))
            assertEquals(0.0, credits(alice.client), 0.0001, "the 10 credits are held")

            payViaFake(publicId)
            awaitOrder(publicId, "COMPLETED")
            assertEquals(listOf("HOLD", "CAPTURE"), db.sql("SELECT `type` FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` IN ('HOLD', 'CAPTURE') ORDER BY `id`", orderId).map { it.getString("type") })
            assertEquals(3490L, orderRow(publicId).getLong("paidAmount") + orderRow(publicId).getLong("creditValue"), "gateway money plus credits is the total")
        }
    }

    // --- P-09 ------------------------------------------------------------------------------------------------------------

    private fun bankTransferOn() {
        val accounts = JsonArray().add(JsonObject().put("bank", "E2E Bank").put("holder", "E2E Store").put("iban", "DE89370400440532013000").put("currency", "EUR"))

        admin.post(
            "/api/panel/market/payment-methods/bank-transfer",
            JsonObject().put("settings", JsonObject().put("accounts", accounts.encode()).put("instructions", "Transfer the exact amount."))
        ).ok()
        admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", true)).ok()
    }

    @Test
    fun `P-09 coupon and creator code`() {
        val vip = catalog.fresh("VIP")
        val streamer = buyer()
        val (couponId, coupon) = catalog.freshCoupon(10)
        val code = "STR" + System.currentTimeMillis().toString(36).uppercase() + unique.incrementAndGet()
        val codeId = admin.post(
            "/api/panel/market/creator-codes",
            JsonObject().put("creator", streamer.username).put("code", code).put("discount", 5).put("unit", "PERCENT").put("commissionPercent", 10)
        ).ok().obj().getLong("id")

        // a store in test mode never pays a creator commission (21 section 7.1) and the fake gateway needs test mode, so the order is a LIVE bank
        // transfer, approved from the panel; settings are put back by withSettings
        bankTransferOn()
        try {
            session.withSettings(JsonObject().put("testMode", false).put("creatorEarningHoldDays", 0)) {
                for (combine in listOf(true, false)) {
                    session.withSettings(JsonObject().put("combineDiscountsAndCoupons", combine)) {
                        val buyer = buyer()
                        val body = { cart(line(vip.id, 2)).put("couponCode", coupon).put("creatorCode", code) }
                        val quoted = quote(buyer.client, body())

                        assertEquals(2000L, cents(quoted, "subtotal"))
                        assertEquals(200L, cents(quoted, "couponDiscount"), "combine=$combine: TEN takes 10 % of 20.00")
                        assertEquals(90L, cents(quoted, "creatorDiscount"), "combine=$combine: STREAMER takes 5 % of the remaining 18.00")
                        assertEquals(1710L, cents(quoted, "total"))

                        val used = db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId)!!
                        val publicId = publicIdOf(checkout(buyer.client, body(), method = "bank-transfer").ok())

                        buyer.client.post("/api/market/orders/$publicId/bank-transfer/notify", JsonObject().put("senderName", "Ada").put("note", "paid")).ok()
                        admin.post("/api/panel/market/orders/${orderRow(publicId).getLong("id")}/bank-transfer", JsonObject().put("decision", "APPROVE")).ok()
                        awaitOrder(publicId, "COMPLETED")

                        val row = orderRow(publicId)
                        val orderId = row.getLong("id")

                        assertEquals(0L, row.getLong("testMode"))
                        assertEquals(1710L, row.getLong("totalPrice"))
                        assertEquals(200L, row.getLong("couponDiscount"))
                        assertEquals(90L, row.getLong("creatorDiscount"))
                        assertEquals(codeId, row.getLong("creatorCodeId"))
                        assertEquals(used + 1, db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId))

                        // one earning, written with the payment: base = lineTotal - VAT, commission 10 %
                        val earning = Await.untilValue(30_000, 250, "the creator earning of $publicId") { db.sql("SELECT * FROM `pano_market_creator_earning` WHERE `orderId` = ?", orderId).singleOrNull() }
                        val base = row.getLong("totalPrice") - row.getLong("vatTotal")

                        assertEquals(base, earning.getLong("baseAmount"), "the base excludes VAT")
                        assertTrue(Math.abs(earning.getLong("amount") - Math.round(base * 0.10)) <= 1, "10 % of $base is ${earning.getLong("amount")}")
                        assertEquals("AVAILABLE", earning.getString("state"), "0 hold days: available at once")
                        assertEquals(1L, db.count("market_creator_earning", "`creatorCodeId` = ? AND `orderId` = ?", codeId, orderId))
                    }
                }

                // the totals of the code say the same thing as its two earnings, and the creator sees them
                val earned = db.long("SELECT COALESCE(SUM(`amount`), 0) FROM `pano_market_creator_earning` WHERE `creatorCodeId` = ?", codeId)!!
                assertEquals(earned, db.long("SELECT `earnings` FROM `pano_market_creator_code` WHERE `id` = ?", codeId), "earnings is updated with every earning")

                val mine = streamer.client.get("/api/market/me/creator").ok().obj()
                assertEquals(2, mine.getJsonArray("earnings").size(), "the creator's page lists both earnings")
                assertEquals(earned / 100.0, mine.getJsonObject("totals").getDouble("earned"), 0.0001)
                assertTrue(mine.getJsonArray("earnings").map { it as JsonObject }.all { it.getString("state") == "AVAILABLE" })
            }
        } finally {
            admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", false))
        }
    }

    // --- P-10 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-10 variant and custom field`() {
        val crate = catalog.fresh("VAR")
        val large = db.long("SELECT `id` FROM `pano_market_product_variant` WHERE `productId` = ? AND `name` = 'L'", crate.id)!!
        val buyer = buyer()

        // no variant chosen: refused, nothing is created
        val orders = db.count("market_order", "`userId` = ?", buyer.userId)
        val missing = checkout(buyer.client, cart(line(crate.id)))
        assertEquals(400, missing.status)
        assertEquals("INVALID_CART", missing.error)
        val lineErrors = missing.obj().getJsonObject("lineErrors")
        assertTrue(lineErrors.fieldNames().all { key -> lineErrors.getJsonArray(key).list.contains("VARIANT_REQUIRED") } && !lineErrors.isEmpty, "VARIANT_REQUIRED: $lineErrors")
        assertEquals(orders, db.count("market_order", "`userId` = ?", buyer.userId))

        val body = cart(line(crate.id, 1, large).put("fieldValues", JsonObject().put("note", "hello")))
        val quoted = quote(buyer.client, body)
        assertEquals(700L, cents(quoted, "total"), "variant L costs 7.00")

        val publicId = buy(buyer.client, cart(line(crate.id, 1, large).put("fieldValues", JsonObject().put("note", "hello"))))
        val item = db.sql("SELECT * FROM `pano_market_order_item` WHERE `orderId` = ?", orderRow(publicId).getLong("id")).single()

        assertEquals(large, item.getLong("variantId"))
        assertEquals("L", item.getString("variantName"))
        assertEquals(700L, item.getLong("listUnitPrice"))
        assertEquals("hello", JsonObject(item.getString("fieldValues")).getString("note"))
        val view = order(buyer.client, publicId).getJsonArray("items").getJsonObject(0)
        assertEquals("L", view.getString("variantName"))
    }

    // --- P-11 ------------------------------------------------------------------------------------------------------------

    @Test
    fun `P-11 bundle`() {
        val node = rawNode("p11")
        val vip = catalog.fresh("VIP", "actions" to JsonArray().add(action("a1", "CREDIT", 2.5)).add(permissionAction("a2", node)).encode())
        val last = catalog.fresh("LAST")
        val s = slug("bundle")
        val bundle = catalog.product(
            s, s, "Bundle $s", price = "12.00",
            extra = mapOf("kind" to "BUNDLE", "bundleItems" to """[{"productId":${vip.id},"variantId":0,"quantity":1},{"productId":${last.id},"variantId":0,"quantity":1}]""")
        )
        val buyer = buyer()
        val stock = productStock(last.id)
        assertEquals(1L, stock)

        val publicId = buy(buyer.client, cart(line(bundle)))
        val orderId = orderRow(publicId).getLong("id")
        val items = db.sql("SELECT * FROM `pano_market_order_item` WHERE `orderId` = ? ORDER BY `id`", orderId)
        val parent = items.single { it.getString("kind") == "BUNDLE" }
        val children = items.filter { it.getString("kind") == "BUNDLE_CHILD" }

        assertEquals(3, items.size, "one BUNDLE line and two BUNDLE_CHILD lines")
        assertEquals(1200L, parent.getLong("lineTotal"), "the bundle line carries the price")
        assertEquals(setOf(vip.id, last.id), children.map { it.getLong("productId") }.toSet())
        assertTrue(children.all { it.getLong("lineTotal") == 0L && it.getLong("unitPrice") == 0L && it.getLong("parentItemId") == parent.getLong("id") }, "the children are free and point at the bundle line")
        assertEquals(1200L, orderRow(publicId).getLong("totalPrice"))

        // the shelf: the child with stock 1 is gone, and nothing was reserved or sold twice
        assertEquals(0L, productStock(last.id), "child stock is decremented")

        // deliveries come from the children (the VIP child), never from the bundle line itself
        val grants = settledDeliveries(orderId, "GRANT", 2)
        val vipChild = children.single { it.getLong("productId") == vip.id }.getLong("id")
        assertTrue(grants.all { it.getLong("orderItemId") == vipChild }, "deliveries belong to the VIP child: ${grants.map { it.getLong("orderItemId") }}")
        assertTrue(grants.all { it.getString("status") == "CONFIRMED" })
        assertTrue(holdsNode(buyer.userId, node))
        Await.until(30_000, 250, "the child's credit arrived") { credits(buyer.client) >= 2.5 }
        assertEquals(1, entitlements(buyer.userId, vip.id).size, "the entitlement is the child product's")

        // sold out: the same bundle cannot be bought again
        val again = quote(buyer().client, cart(line(bundle)))
        assertEquals(false, again.getBoolean("canCheckout"), "the bundle is as available as its weakest child")
    }

    private fun E2eClient.userIdOrLookup(): Long =
        userId ?: db.long("SELECT `id` FROM `pano_user` WHERE `username` = ?", session.env.adminUser) ?: error("no admin user id")
}
