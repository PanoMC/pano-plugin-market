package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** Payment failure, retry and the trust rules of the inbound routes (17 section 9.3): F-01, F-02, F-04, F-08. */
class PaymentFlowE2E : E2eTestBase() {
    override val tag = "pay"

    /** Checks out one fresh VIP as a new buyer and returns (client, publicId, reference). */
    private fun pending(): Triple<com.panomc.plugins.market.e2e.support.E2eBuyer, String, String> {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val publicId = publicIdOf(checkout(buyer.client, cart(line(vip.id))).ok())

        return Triple(buyer, publicId, referenceOf(publicId))
    }

    /** The path (and query) of the return URL the gateway was given for [reference]. */
    private fun returnPath(reference: String, outcome: String): String {
        val url = gateway.payments[reference]!!.returnSuccess ?: error("the gateway got no return URL")
        val uri = URI.create(url)

        assertTrue(uri.path.endsWith("/success"), "the success return URL ends in /success: ${uri.path}")

        return (uri.rawPath.removeSuffix("/success") + "/" + outcome) + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    @Test
    fun `F-01 browser return is not proof`() {
        val (buyer, publicId, reference) = pending()

        assertEquals("pending", gateway.payments[reference]!!.status)

        val back = visitor("browser").get(returnPath(reference, "success"))

        assertEquals(303, back.status, "the return only ever redirects")
        assertEquals("/store/order/$publicId", URI.create(back.header("Location")!!).path, "Location was ${back.header("Location")}")
        assertEquals("PENDING", orderStatus(publicId), "the order stays PENDING while the gateway says pending")
        assertEquals("PENDING", order(buyer.client, publicId).getString("status"))
    }

    @Test
    fun `F-02 return triggers a status query`() {
        val (_, publicId, reference) = pending()

        gateway.setStatus(reference, "paid") // paid at the gateway, no webhook is sent

        val back = visitor("browser").get(returnPath(reference, "success"))

        assertEquals(303, back.status)
        awaitOrder(publicId, "COMPLETED")
        assertEquals("SUCCEEDED", attemptStatus(reference))
        assertTrue(gateway.requests(FakePayGateway.Op.QUERY).any { it.path.endsWith("/$reference") }, "the provider asked the gateway")
    }

    @Test
    fun `F-04 invalid signature`() {
        val (_, publicId, reference) = pending()
        val data = JsonObject().put("reference", reference).put("amount", gateway.payments[reference]!!.amount.toPlainString()).put("currency", "EUR")

        for (signature in listOf(FakePayGateway.Signature.INVALID, FakePayGateway.Signature.MISSING, FakePayGateway.Signature.STALE)) {
            val rejected = rejectedEvents()
            val answers = gateway.sendWebhook("payment.succeeded", data, signature = signature)

            assertEquals(listOf(400), answers.map { it.statusCode() }, "$signature is refused with 400")
            assertEquals(rejected + 1, rejectedEvents(), "$signature leaves one REJECTED, unverified event row")
            assertEquals("PENDING", orderStatus(publicId), "$signature changes nothing")
        }

        assertTrue(attemptStatus(reference) != "SUCCEEDED", "the attempt did not succeed")
    }

    private fun rejectedEvents(): Long = db.count("market_payment_event", "`providerId` = 'fake' AND `status` = 'REJECTED' AND `verified` = 0")

    @Test
    fun `F-08 failed then retry with a new attempt`() {
        val (buyer, publicId, first) = pending()
        val failed = gateway.sendWebhook(
            "payment.failed", JsonObject().put("reference", first).put("code", "card_declined").put("message", "The card was declined").put("final", true)
        )

        assertEquals(listOf(200), failed.map { it.statusCode() })
        assertEquals("FAILED", attemptStatus(first))
        assertEquals("card_declined", db.string("SELECT `failureCode` FROM `pano_market_payment` WHERE `reference` = ?", first))
        assertEquals("PENDING", orderStatus(publicId))
        assertEquals(true, order(buyer.client, publicId).getBoolean("canRetryPayment"))

        val retry = buyer.client.post("/api/market/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake")).ok()

        assertNotNull(retry.obj().getJsonObject("payment"), "the retry answers a PaymentStart")

        val second = referenceOf(publicId)
        assertNotEquals(first, second, "a new attempt has a new reference")
        assertNotEquals(
            db.string("SELECT `token` FROM `pano_market_payment` WHERE `reference` = ?", first),
            db.string("SELECT `token` FROM `pano_market_payment` WHERE `reference` = ?", second), "and a new token"
        )
        assertEquals("FAILED", attemptStatus(first), "the old attempt stays closed")
        assertEquals(2L, db.count("market_payment", "`orderId` = (SELECT `id` FROM `pano_market_order` WHERE `publicId` = ?)", publicId))

        gateway.pay(second)
        awaitOrder(publicId, "COMPLETED")
        assertEquals("SUCCEEDED", attemptStatus(second))
        assertEquals("FAILED", attemptStatus(first))
    }
}
