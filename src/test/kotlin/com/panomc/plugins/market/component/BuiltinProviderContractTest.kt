package com.panomc.plugins.market.component

import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.CreditsProvider
import com.panomc.plugins.market.provider.FreeProvider
import com.panomc.plugins.market.provider.ProviderAvailability
import com.panomc.plugins.market.provider.ProviderKind
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.SettingsCodec
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.testkit.ProviderContractTest
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.StaticProviderLookup
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * 02 section 14.1 / 17 section 11.2: the provider contract of the testkit run against the three built-ins, plus their
 * own rules (02 section 12, 06 section 14) and the two test fakes of MK-044.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuiltinProviderContractTest {
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun close() {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    private fun ctx(provider: PaymentProvider, values: Map<String, Any?> = emptyMap(), id: String = provider.id) =
        TestContexts.payment(id, TestContexts.settings(values), vertx)

    private fun zeroStart(provider: PaymentProvider) = SampleData.startRequest(Money(0, "EUR"), providerId = provider.id)

    private fun snapshot(order: Money, guest: Boolean = false): CheckoutSnapshot {
        val buyer = SampleData.buyer().let {
            com.panomc.plugins.market.spi.payment.BuyerInfo(
                it.userId.takeIf { !guest }, guest, it.numericId, it.stableId, it.username, it.email, it.ip, it.userAgent, it.locale,
                it.firstName, it.lastName, it.phone, it.country, it.identityNumber, it.registeredAt
            )
        }
        return CheckoutSnapshot(SampleData.order(order), buyer, null, false)
    }


    private fun unknownEvent() = com.panomc.plugins.market.spi.common.InboundRequest(
        kind = com.panomc.plugins.market.spi.common.InboundKind.WEBHOOK, channel = "default", method = "POST", rawQuery = null, query = emptyMap(),
        headers = mapOf("content-type" to listOf("application/json")), contentType = "application/json",
        body = """{"type":"__contract_test_unknown__","data":{}}""".toByteArray(), remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
    )

    private val accountsJson = JsonArray()
        .add(JsonObject().put("bank", "Test Bank").put("holder", "Pano Shop Ltd").put("iban", "DE89370400440532013000").put("currency", "EUR"))
        .encode()

    // ---- the contract of 02 section 14.1 against every built-in

    @Nested
    inner class FreeContract : ProviderContractTest() {
        override fun createProvider(): PaymentProvider = FreeProvider()
        override val gatewayIsFake: Boolean get() = true
        override fun signedUnknownEvent(ctx: com.panomc.plugins.market.spi.payment.PaymentContext) = unknownEvent()
        override fun startRequest(provider: PaymentProvider): StartPaymentRequest = zeroStart(provider)
    }

    @Nested
    inner class CreditsContract : ProviderContractTest() {
        override fun createProvider(): PaymentProvider = CreditsProvider()
        override val gatewayIsFake: Boolean get() = true
        override fun signedUnknownEvent(ctx: com.panomc.plugins.market.spi.payment.PaymentContext) = unknownEvent()
        override fun startRequest(provider: PaymentProvider): StartPaymentRequest = zeroStart(provider)
    }

    @Nested
    inner class BankTransferContract : ProviderContractTest() {
        override fun createProvider(): PaymentProvider = BankTransferProvider()
        override val gatewayIsFake: Boolean get() = true
        override fun signedUnknownEvent(ctx: com.panomc.plugins.market.spi.payment.PaymentContext) = unknownEvent()
        override fun settingValues(provider: PaymentProvider): Map<String, Any?> =
            mapOf("accounts" to accountsJson, "instructions" to "Pay within 3 days.", "requireBuyerNotice" to true)
    }

    // ---- free and credits

    @Test
    fun `free and credits complete with a zero payment on the attempt`() {
        for (p in listOf(FreeProvider(), CreditsProvider())) {
            val request = zeroStart(p)
            val result = run { p.startPayment(ctx(p), request) }
            val completed = result as StartPaymentResult.Completed
            assertEquals("COMPLETED", result.kind)
            assertEquals(Money(0, "EUR"), completed.event.paid)
            assertEquals(request.attempt.id, (completed.event.target as PaymentTarget.Attempt).attemptId)
        }
    }

    @Test
    fun `free and credits refuse a non zero gateway amount and keep the currency of the order`() {
        for (p in listOf(FreeProvider(), CreditsProvider())) {
            val e = assertThrows(ProviderException::class.java) { run { p.startPayment(ctx(p), SampleData.startRequest(Money(1, "EUR"), providerId = p.id)) } }
            assertEquals(ProviderErrorCode.INVALID_REQUEST, e.code)
        }
        val jpy = run { FreeProvider().startPayment(ctx(FreeProvider()), SampleData.startRequest(Money(0, "JPY"), providerId = "free")) } as StartPaymentResult.Completed
        assertEquals("JPY", jpy.event.paid.currency)
    }

    @Test
    fun `free and credits have no inbound routes and no settings`() {
        for (p in listOf(FreeProvider(), CreditsProvider(), BankTransferProvider())) {
            val http = com.panomc.plugins.market.spi.common.InboundRequest(
                kind = com.panomc.plugins.market.spi.common.InboundKind.WEBHOOK, channel = "default", method = "POST", rawQuery = null, query = emptyMap(),
                headers = emptyMap(), contentType = "application/json", body = "{}".toByteArray(), remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
            )
            val result: InboundResult = run { p.handleInbound(ctx(p), com.panomc.plugins.market.spi.payment.PaymentInboundRequest(http, null, null, null)) }
            assertEquals(404, result.reply.status, p.id)
            assertFalse(result.verified, p.id)
            assertTrue(result.events.isEmpty(), p.id)
        }
        assertTrue(FreeProvider().settingsSchema().fields.isEmpty())
        assertTrue(CreditsProvider().settingsSchema().fields.isEmpty())
    }

    @Test
    fun `free and credits capabilities`() {
        val settings = TestContexts.settings()
        for (p in listOf(FreeProvider(), CreditsProvider())) {
            val c = p.capabilities(settings)
            assertEquals(RefundSupport.NONE, c.refund, p.id)
            assertEquals(RecurringSupport.NONE, c.recurring, p.id)
            assertEquals(TestModeSupport.NONE, c.testMode, p.id)
            assertEquals(WebhookSetup.NONE, c.webhookSetup, p.id)
            assertFalse(c.mixedCredit, p.id)
            assertNull(c.currencies, p.id)
        }
        assertTrue(FreeProvider().capabilities(settings).guests)
        assertFalse(CreditsProvider().capabilities(settings).guests)
    }

    @Test
    fun `free is eligible for a zero total only and credits need a logged in buyer`() {
        val free = FreeProvider()
        assertTrue(free.checkEligibility(ctx(free), snapshot(Money(0, "EUR"))).eligible)
        val paid = free.checkEligibility(ctx(free), snapshot(Money(1, "EUR")))
        assertFalse(paid.eligible)
        assertEquals("NOT_FREE", paid.code)

        val credits = CreditsProvider()
        assertTrue(credits.checkEligibility(ctx(credits), snapshot(Money(1000, "EUR"))).eligible)
        val guest = credits.checkEligibility(ctx(credits), snapshot(Money(1000, "EUR"), guest = true))
        assertFalse(guest.eligible)
        assertEquals("LOGIN_REQUIRED", guest.code)
    }

    // ---- bank transfer

    @Test
    fun `bank transfer answers instructions with accounts, exact amount and the attempt reference`() {
        val p = BankTransferProvider()
        val settings = mapOf("accounts" to accountsJson, "instructions" to "Pay within 3 days.")
        val request = SampleData.startRequest(Money(12345, "EUR"), providerId = p.id)
        val result = run { p.startPayment(ctx(p, settings), request) } as StartPaymentResult.Instructions
        val values = result.fields.map { it.value }
        assertTrue(values.containsAll(listOf("Test Bank", "Pano Shop Ltd", "DE89370400440532013000")), values.toString())
        assertTrue("123.45 EUR" in values, values.toString())
        assertEquals(request.attempt.reference, result.fields.last().value)
        assertTrue(result.body.resolve("en-US").endsWith("Pay within 3 days."))
        assertTrue(result.body.resolve("tr").endsWith("Pay within 3 days."))
        assertTrue(result.buyerConfirms)
        assertEquals("INSTRUCTIONS", result.toPaymentStartJson("en-US").getString("kind"))
        assertNull(result.expiresAt, "the lifetime is market's bankTransferExpiryHours, not the provider's")
    }

    @Test
    fun `bank transfer uses only the accounts that accept the order currency`() {
        val p = BankTransferProvider()
        val two = JsonArray()
            .add(JsonObject().put("bank", "Euro Bank").put("holder", "A").put("iban", "DE1").put("currency", "eur"))
            .add(JsonObject().put("bank", "Lira Bank").put("holder", "A").put("iban", "TR1").put("currency", "TRY"))
            .add(JsonObject().put("bank", "Any Bank").put("holder", "A").put("iban", "XX1"))
            .encode()
        val values = run { p.startPayment(ctx(p, mapOf("accounts" to two)), SampleData.startRequest(Money(1000, "TRY"), providerId = p.id)) }
            .let { (it as StartPaymentResult.Instructions).fields.map { f -> f.value } }
        assertTrue("Lira Bank" in values && "Any Bank" in values && "Euro Bank" !in values, values.toString())

        // JPY: only the account without a currency is left.
        val jpy = run { p.startPayment(ctx(p, mapOf("accounts" to two)), SampleData.startRequest(Money(50000, "JPY"), providerId = p.id)) }
            .let { (it as StartPaymentResult.Instructions).fields.map { f -> f.value } }
        assertTrue("Any Bank" in jpy && "Euro Bank" !in jpy && "Lira Bank" !in jpy, jpy.toString())

        val onlyEuro = ctx(p, mapOf("accounts" to JsonArray().add(JsonObject().put("iban", "DE1").put("currency", "EUR")).encode()))
        assertTrue(p.checkEligibility(onlyEuro, snapshot(Money(1000, "EUR"))).eligible)
        val no = p.checkEligibility(onlyEuro, snapshot(Money(1000, "TRY")))
        assertFalse(no.eligible)
        assertEquals("NO_ACCOUNT_FOR_CURRENCY", no.code)
        val e = assertThrows(ProviderException::class.java) { run { p.startPayment(onlyEuro, SampleData.startRequest(Money(1000, "TRY"), providerId = p.id)) } }
        assertEquals(ProviderErrorCode.CONFIGURATION, e.code)
    }

    @Test
    fun `bank transfer capabilities follow the configured accounts`() {
        val p = BankTransferProvider()
        val caps = p.capabilities(TestContexts.settings(mapOf("accounts" to accountsJson)))
        assertEquals(RefundSupport.NONE, caps.refund)
        assertEquals(RecurringSupport.NONE, caps.recurring)
        assertTrue(caps.longPending)
        assertEquals(WebhookSetup.NONE, caps.webhookSetup)
        assertEquals(TestModeSupport.NONE, caps.testMode)
        assertEquals(setOf("EUR"), caps.currencies)
        assertNull(p.capabilities(TestContexts.settings()).currencies)
        val mixed = JsonArray().add(JsonObject().put("iban", "A").put("currency", "EUR")).add(JsonObject().put("iban", "B")).encode()
        assertNull(p.capabilities(TestContexts.settings(mapOf("accounts" to mixed))).currencies)
    }

    @Test
    fun `the legacy single account keys are read as the first account`() {
        val p = BankTransferProvider()
        val legacy = mapOf("bankName" to "Old Bank", "iban" to "TR330006100519786457841326", "accountHolder" to "Old Holder", "description" to "ignored here")
        val accounts = BankTransferProvider.accountsOf(TestContexts.settings(legacy))
        assertEquals(1, accounts.size)
        assertEquals("Old Bank", accounts[0].bank)
        assertEquals("Old Holder", accounts[0].holder)
        assertEquals("TR330006100519786457841326", accounts[0].iban)
        val values = run { p.startPayment(ctx(p, legacy), SampleData.startRequest(Money(1000, "TRY"), providerId = p.id)) }
            .let { (it as StartPaymentResult.Instructions).fields.map { f -> f.value } }
        assertTrue(values.containsAll(listOf("Old Bank", "Old Holder", "TR330006100519786457841326")))
        // A configured list wins over the legacy keys.
        val both = legacy + ("accounts" to accountsJson)
        assertEquals("Test Bank", BankTransferProvider.accountsOf(TestContexts.settings(both)).single().bank)
        assertTrue(run { p.validateSettings(ctx(p, legacy), TestContexts.settings(legacy)) }.ok)
    }

    @Test
    fun `migrating legacy keys puts the old account first, maps description and drops autoApprove`() {
        val stored = JsonObject()
            .put("bankName", "Old Bank").put("iban", "TR33").put("accountHolder", "Old Holder")
            .put("description", "Send it to us").put("autoApprove", true)
        val migrated = BankTransferProvider.migrateLegacy(stored)
        for (k in listOf("bankName", "iban", "accountHolder", "description", "autoApprove")) assertFalse(migrated.containsKey(k), k)
        val accounts = JsonArray(migrated.getString("accounts"))
        assertEquals(1, accounts.size())
        assertEquals("Old Bank", accounts.getJsonObject(0).getString("bank"))
        assertEquals("Old Holder", accounts.getJsonObject(0).getString("holder"))
        assertEquals("TR33", accounts.getJsonObject(0).getString("iban"))
        assertEquals("Send it to us", migrated.getString("instructions"))
        assertTrue(stored.containsKey("bankName"), "the input is not modified")

        // Existing entries stay behind the legacy one; instructions already set are kept.
        val withList = JsonObject().put("iban", "TR33").put("accounts", accountsJson).put("description", "old text").put("instructions", "new text")
        val merged = BankTransferProvider.migrateLegacy(withList)
        val list = JsonArray(merged.getString("accounts"))
        assertEquals(listOf("TR33", "DE89370400440532013000"), list.map { (it as JsonObject).getString("iban") })
        assertEquals("new text", merged.getString("instructions"))

        // Nothing legacy: unchanged; unreadable accounts: left for the admin, legacy keys stay.
        val plain = JsonObject().put("accounts", accountsJson).put("requireBuyerNotice", true)
        assertEquals(plain, BankTransferProvider.migrateLegacy(plain))
        val broken = JsonObject().put("iban", "TR33").put("accounts", "not json")
        assertEquals(broken, BankTransferProvider.migrateLegacy(broken))
    }

    @Test
    fun `a migrated legacy object passes the settings codec and keeps its account`() {
        val p = BankTransferProvider()
        val codec = SettingsCodec(p.settingsSchema(), SecretCipher(ByteArray(32) { (it + 3).toByte() }))
        val legacy = JsonObject().put("bankName", "Old Bank").put("iban", "TR33").put("accountHolder", "Old Holder").put("description", "Send it to us")
        val migrated = BankTransferProvider.migrateLegacy(legacy)
        val saved = codec.applyForm(null, migrated)
        assertTrue(saved.ok, saved.issues.toString())
        val back = codec.decrypt(saved.settings)
        val account = BankTransferProvider.accountsOf(back).single()
        assertEquals("Old Bank", account.bank)
        assertEquals("TR33", account.iban)
        assertEquals("Send it to us", back.string("instructions"))
        assertFalse(back.boolean("requireBuyerNotice"))
    }

    @Test
    fun `account list validation rejects bad input`() {
        val p = BankTransferProvider()
        fun check(value: String?) = run { p.validateSettings(ctx(p), TestContexts.settings(if (value == null) emptyMap() else mapOf("accounts" to value))) }
        assertFalse(check(null).ok)
        assertFalse(check("[]").ok)
        assertFalse(check("{").ok)
        assertFalse(check("""[{"bank":"x"}]""").ok, "iban is required")
        assertFalse(check("""[{"iban":"A","currency":"EURO"}]""").ok)
        assertFalse(check("""[{"iban":1}]""").ok)
        assertFalse(check(JsonArray((1..11).map { JsonObject().put("iban", "I$it") }).encode()).ok, "more than 10 accounts")
        assertTrue(check(accountsJson).ok)
        assertEquals(setOf("accounts"), check("[]").fieldErrors.keys)
        assertTrue(BankTransferProvider.accountsOf(TestContexts.settings(mapOf("accounts" to "{"))).isEmpty(), "unreadable input never throws at runtime")
    }

    // ---- the test fakes

    @Test
    fun `StaticProviderLookup serves the given providers and reports the rest as missing`() {
        val fake = FakePaymentProvider()
        val lookup = StaticProviderLookup(listOf(fake))
        assertSame(fake, lookup.payment("fake")!!.provider)
        assertNull(lookup.payment("other"))
        assertEquals(ProviderAvailability.AVAILABLE, lookup.state(ProviderKind.PAYMENT, "fake").availability)
        assertEquals(ProviderAvailability.MISSING, lookup.state(ProviderKind.PAYMENT, "other").availability)
        assertEquals(listOf("fake"), lookup.listing(ProviderKind.PAYMENT).map { it.id })
        lookup.add(FreeProvider())
        assertEquals(listOf("fake", "free"), lookup.allPayment().map { it.id })
        lookup.remove("fake")
        assertEquals(ProviderAvailability.MISSING, lookup.state(ProviderKind.PAYMENT, "fake").availability)
        assertTrue(lookup.allShipping().isEmpty())
    }

    @Test
    fun `FakePaymentProvider is scriptable and records every call`() {
        val fake = FakePaymentProvider()
        val ctx = ctx(fake)
        val request = SampleData.startRequest(providerId = "fake")
        val default = run { fake.startPayment(ctx, request) } as StartPaymentResult.Redirect
        assertEquals("https://gateway.invalid/pay/${request.attempt.reference}", default.url)

        fake.onStart = { StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(it.attempt.id), it.amount)) }
        assertTrue(run { fake.startPayment(ctx, request) } is StartPaymentResult.Completed)

        fake.failNext(FakePaymentProvider.Op.START, ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down", retryable = true))
        val e = assertThrows(ProviderException::class.java) { run { fake.startPayment(ctx, request) } }
        assertEquals(ProviderErrorCode.GATEWAY_UNREACHABLE, e.code)
        run { fake.startPayment(ctx, request) } // only the next call failed

        assertEquals(4, fake.calls(FakePaymentProvider.Op.START).size)
        assertEquals(4, fake.calls.size)
        assertSame(request, fake.calls.first().request)

        val inbound = run { fake.handleInbound(ctx, com.panomc.plugins.market.spi.payment.PaymentInboundRequest(
            com.panomc.plugins.market.spi.common.InboundRequest(
                kind = com.panomc.plugins.market.spi.common.InboundKind.WEBHOOK, channel = "default", method = "POST", rawQuery = null, query = emptyMap(),
                headers = emptyMap(), contentType = null, body = ByteArray(0), remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
            ), null, null, null
        )) }
        assertTrue(inbound.verified)
        assertEquals(HttpReply.empty().status, inbound.reply.status)
        assertTrue(run { fake.queryPayment(ctx, com.panomc.plugins.market.spi.payment.QueryPaymentRequest(SampleData.attemptView(), com.panomc.plugins.market.spi.payment.QueryReason.PANEL)) }.unknown)
        assertTrue(run { fake.cancelPayment(ctx, com.panomc.plugins.market.spi.payment.CancelPaymentRequest(SampleData.attemptView())) }.cancelled)
    }

    @Test
    fun `FakePaymentProvider delay holds a call open until the gate completes`() {
        val fake = FakePaymentProvider()
        val ctx = ctx(fake)
        val request = SampleData.startRequest(providerId = "fake")
        val gate = fake.delay(FakePaymentProvider.Op.START)
        val finished = CompletableDeferred<StartPaymentResult>()
        val job = Thread { runBlocking { finished.complete(fake.startPayment(ctx, request)) } }.also { it.start() }
        // The call is recorded (so a test can see it is in flight) but not finished.
        val deadline = System.currentTimeMillis() + 60000
        while (fake.calls.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(1, fake.calls.size)
        Thread.sleep(150)
        assertFalse(finished.isCompleted)
        gate.complete(Unit)
        job.join(60000)
        assertTrue(finished.isCompleted)

        // A timed delay releases itself.
        fake.delay(FakePaymentProvider.Op.START, 50)
        run { fake.startPayment(ctx, request) }
        assertEquals(2, fake.calls.size)
    }
}
