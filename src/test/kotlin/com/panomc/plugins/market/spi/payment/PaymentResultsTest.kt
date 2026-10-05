package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.payment.PaymentTestData.eur
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PaymentResultsTest {
    private val attempt = PaymentTarget.Attempt(5)

    // ---- AttemptUrls -------------------------------------------------------------------------------------------

    @Test
    fun `attempt urls derive the notify channel and the return step from the base urls`() {
        val u = PaymentTestData.urls()
        assertEquals("https://shop.example/api/market/payments/fake/notify/tok/subscription", u.notify("subscription"))
        assertEquals("https://shop.example/api/market/payments/fake/return/tok/step/basket-auth", u.step("basket-auth"))
        assertEquals("https://shop.example/api/market/payments/fake/notify/tok", u.notify)
        assertEquals("https://shop.example/store/order/ABCDEFGHJKMNPQRSTVWX", u.orderPage)
    }

    @Test
    fun `channel and step names are restricted to lower-case words`() {
        val u = PaymentTestData.urls()
        for (bad in listOf("", "UPPER", "a/b", "a b", "a?x=1", "..", "a".repeat(33), "ü")) {
            assertThrows<IllegalArgumentException>("notify($bad)") { u.notify(bad) }
            assertThrows<IllegalArgumentException>("step($bad)") { u.step(bad) }
        }
        u.notify("a".repeat(32))
    }

    // ---- inbound ------------------------------------------------------------------------------------------------

    @Test
    fun `accepted carries the events and the delivery key and is verified`() {
        val ev = PaymentEvent.Cancelled(attempt)
        val r = InboundResult.accepted(HttpReply.text("OK"), listOf(ev), "evt_1")
        assertTrue(r.verified)
        assertEquals("evt_1", r.eventKey)
        assertTrue(r.events.single() === ev)
        assertNull(r.rejectReason)
        assertEquals(200, r.reply.status)
        assertNull(InboundResult.accepted(HttpReply.empty(), emptyList()).eventKey)
        assertThrows<IllegalArgumentException> { InboundResult.accepted(HttpReply.empty(), emptyList(), " ") }
    }

    @Test
    fun `ignored is verified and empty and rejected is unverified with a reason and no events`() {
        val ignored = InboundResult.ignored(HttpReply.text("OK"))
        assertTrue(ignored.verified)
        assertEquals(emptyList<PaymentEvent>(), ignored.events)
        assertNull(ignored.eventKey)

        val rejected = InboundResult.rejected(HttpReply.text("bad", 401), "bad signature")
        assertFalse(rejected.verified)
        assertEquals("bad signature", rejected.rejectReason)
        assertEquals(emptyList<PaymentEvent>(), rejected.events)
        assertNull(rejected.eventKey)
        assertEquals(401, rejected.reply.status)
    }

    @Test
    fun `a plain InboundResult is not verified until the provider says so`() {
        assertFalse(InboundResult(HttpReply.empty()).verified)
    }

    // ---- query / cancel -----------------------------------------------------------------------------------------

    @Test
    fun `query results tell unknown from unsupported from a real answer`() {
        val ev = PaymentEvent.Succeeded(attempt, eur(100))
        val of = PaymentQueryResult.of(ev)
        assertFalse(of.unknown)
        assertFalse(of.unsupported)
        assertEquals(1, of.events.size)
        val unknown = PaymentQueryResult.unknown()
        assertTrue(unknown.unknown)
        assertFalse(unknown.unsupported)
        assertEquals(emptyList<PaymentEvent>(), unknown.events)
        val unsupported = PaymentQueryResult.unsupported()
        assertTrue(unsupported.unsupported)
        assertFalse(unsupported.unknown)
        of.pollAgainAfterSeconds = 30
        assertEquals(30L, of.pollAgainAfterSeconds)
        assertEquals(0, PaymentQueryResult.of().events.size)
    }

    @Test
    fun `cancel results are three distinct states`() {
        val ok = CancelPaymentResult.cancelled()
        assertTrue(ok.cancelled && ok.supported)
        val no = CancelPaymentResult.notCancellable()
        assertTrue(!no.cancelled && no.supported)
        val un = CancelPaymentResult.unsupported()
        assertTrue(!un.cancelled && !un.supported)
    }

    // ---- refund -------------------------------------------------------------------------------------------------

    @Test
    fun `refund requests and results hold the documented data`() {
        val attempt = PaymentTestData.attemptView()
        val order = PaymentTestData.order(listOf(PaymentTestData.line(11, 1000)))
        val request = RefundRequest(
            refundId = 9, idempotencyKey = "rf-9", attempt = attempt, order = order, amount = eur(400), full = false,
            lines = listOf(RefundLine(11, "li_1", 1, eur(400)), RefundLine(OrderLine.SHIPPING_LINE_ID, null, 1, eur(0))),
            reason = "customer request", paidAt = 5
        )
        assertEquals(400L, request.amount.amount)
        assertEquals("li_1", request.lines[0].gatewayItemRef)
        assertNull(request.lines[1].gatewayItemRef)

        val ok = RefundResult.Succeeded().also { it.gatewayRefundId = "re_1"; it.refundedAmount = eur(400); it.providerData = io.vertx.core.json.JsonObject() }
        assertEquals("re_1", ok.gatewayRefundId)
        val pending = RefundResult.Pending().also { it.buyerActionUrl = "https://claim.example" }
        assertEquals("https://claim.example", pending.buyerActionUrl)
        val failed = RefundResult.Failed("TOO_LATE", "window closed").also { it.retryable = true }
        assertEquals("TOO_LATE", failed.code)
        assertTrue(failed.retryable)
        assertFalse(RefundResult.Failed("X", null).retryable)
        assertTrue(RefundResult.unknown() is RefundResult.Unknown)
        assertEquals(RefundResult::class.sealedSubclasses.size, 4)
        val q = QueryRefundRequest(9, "rf-9", null, attempt, eur(400))
        assertNull(q.gatewayRefundId)
    }

    // ---- recurring ----------------------------------------------------------------------------------------------

    @Test
    fun `cancel subscription results cover every outcome of the table in 02 section 8`() {
        val all: List<CancelSubscriptionResult> = listOf(
            CancelSubscriptionResult.Cancelled(null), CancelSubscriptionResult.Cancelled(5L), CancelSubscriptionResult.Scheduled(9),
            CancelSubscriptionResult.localOnly(), CancelSubscriptionResult.BuyerActionRequired("https://x.example"), CancelSubscriptionResult.Failed("no")
        )
        assertNull((all[0] as CancelSubscriptionResult.Cancelled).effectiveAt)
        assertEquals(9L, (all[2] as CancelSubscriptionResult.Scheduled).endsAt)
        assertTrue(all[3] is CancelSubscriptionResult.LocalOnly)
        assertEquals("https://x.example", (all[4] as CancelSubscriptionResult.BuyerActionRequired).url)
        assertEquals(5, CancelSubscriptionResult::class.sealedSubclasses.size)
    }

    @Test
    fun `resume portal and subscription query defaults`() {
        assertTrue(ResumeSubscriptionResult.unsupported() is ResumeSubscriptionResult.Unsupported)
        assertTrue(SubscriptionPortalResult.unsupported() is SubscriptionPortalResult.Unsupported)
        assertEquals("https://p.example", (SubscriptionPortalResult.Redirect("https://p.example") as SubscriptionPortalResult.Redirect).url)
        assertEquals("<p/>", SubscriptionPortalResult.Html("<p/>").document)
        assertTrue(SubscriptionQueryResult.unsupported().unsupported)
        assertFalse(SubscriptionQueryResult(emptyList()).unsupported)
        assertEquals(listOf("MANAGE", "UPDATE_PAYMENT_METHOD", "CANCEL"), PortalPurpose.values().map { it.name })
    }

    @Test
    fun `recurring charge request and result hold the attempt and the stored method`() {
        val ev = PaymentEvent.Succeeded(attempt, eur(500))
        val req = RecurringChargeRequest(
            AttemptRef(7, "R".repeat(20), "t"), eur(500), PaymentTestData.subscriptionView(), StoredPaymentMethod("tok_1"),
            PaymentTestData.order(emptyList()), PaymentTestData.buyer().let { it }, "idem-7", "https://shop.example/notify"
        )
        assertEquals("idem-7", req.idempotencyKey)
        assertEquals("tok_1", req.storedMethod.token)
        assertTrue(RecurringChargeResult(listOf(ev)).events.single() === ev)
    }

    // ---- hooks --------------------------------------------------------------------------------------------------

    @Test
    fun `settings validation is ok or carries field errors`() {
        assertTrue(SettingsValidation.ok().ok)
        assertEquals(emptyMap<String, LocalizedText>(), SettingsValidation.ok().fieldErrors)
        val bad = SettingsValidation.invalid(mapOf("apiKey" to LocalizedText.of("Wrong key")), LocalizedText.of("Check the form"))
        assertFalse(bad.ok)
        assertEquals("Wrong key", bad.fieldErrors.getValue("apiKey").fallback)
        assertEquals("Check the form", bad.message!!.fallback)
        assertFalse(SettingsValidation.invalid(emptyMap(), LocalizedText.of("m")).ok)
        assertThrows<IllegalArgumentException> { SettingsValidation.invalid(emptyMap()) }
    }

    @Test
    fun `eligibility verdicts`() {
        val yes = Eligibility.eligible()
        assertTrue(yes.eligible && !yes.oneOffOnly)
        assertNull(yes.code)
        val no = Eligibility.ineligible("CURRENCY", LocalizedText.of("Not in your currency"))
        assertFalse(no.eligible)
        assertFalse(no.oneOffOnly)
        assertEquals("CURRENCY", no.code)
        assertEquals("Not in your currency", no.reason!!.fallback)
        val oneOff = Eligibility.oneOffOnly("NO_RECURRING")
        assertTrue(oneOff.eligible)
        assertTrue(oneOff.oneOffOnly)
        assertEquals("NO_RECURRING", oneOff.code)
        assertThrows<IllegalArgumentException> { Eligibility.ineligible(" ", LocalizedText.of("x")) }
        assertThrows<IllegalArgumentException> { Eligibility.oneOffOnly("") }
    }

    @Test
    fun `action results`() {
        assertTrue(ActionResult.none() is ActionResult.None)
        val msg = ActionResult.Message(LocalizedText.of("Connected"), true)
        assertTrue(msg.success)
        val patch = ActionResult.SettingsPatch(mapOf("webhookSecret" to "whsec_x", "old" to null), LocalizedText.of("Webhook registered"))
        assertNull(patch.values.getValue("old"))
        val import = ActionResult.CatalogImport(
            listOf(ImportedCategory("c1", "Ranks", null)),
            listOf(ImportedProduct("p1", "VIP", null, eur(500), "c1", null, IntervalUnit.MONTH, 1, io.vertx.core.json.JsonObject().put("pkg", 1)))
        )
        assertEquals("c1", import.products.single().categoryExternalId)
        assertEquals(IntervalUnit.MONTH, import.products.single().intervalUnit)
        assertEquals(4, ActionResult::class.sealedSubclasses.size)
    }

    @Test
    fun `checkout snapshot and attempt views are plain read-only data`() {
        val snap = CheckoutSnapshot(PaymentTestData.order(emptyList()), PaymentTestData.buyer(), null, true)
        assertTrue(snap.hasPanoPriceModifiers)
        val view = PaymentTestData.attemptView()
        assertEquals(eur(1000), view.amount)
        assertEquals(eur(0), view.refundedAmount)
        assertEquals("ABCDEFGHJKMNPQRSTVWX", view.reference)
    }
}
