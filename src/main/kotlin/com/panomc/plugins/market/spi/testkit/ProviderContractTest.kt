package com.panomc.plugins.market.spi.testkit

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * A notification the plugin signed itself (with the test settings) and the same notification with one more
 * **unsigned** field (an added query parameter, header or body field outside the signature).
 */
class SignedSample(val request: InboundRequest, val withUnsignedField: InboundRequest)

/**
 * Inbound requests no provider may ever accept: [InboundRequest]s of every [InboundKind] with empty, truncated,
 * oversized, binary and structurally wrong bodies and bogus signature headers. Shared by payment and shipping.
 */
object Garbage {
    private val random = java.util.Random(20251009L)

    private fun bytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** (content type, body) pairs. */
    val bodies: List<Pair<String?, ByteArray>> by lazy {
        listOf(
            null to ByteArray(0),
            "application/json" to ByteArray(0),
            "application/json" to "null".toByteArray(),
            "application/json" to "[]".toByteArray(),
            "application/json" to "{".toByteArray(),
            "application/json" to "{\"type\":123,\"data\":[1,2,3],\"id\":{}}".toByteArray(),
            "application/json" to "{\"type\":\"__contract_test_unknown__\",\"data\":{\"object\":null}}".toByteArray(),
            "application/x-www-form-urlencoded" to "a=1&a=2&&=&b".toByteArray(),
            "application/x-www-form-urlencoded" to "status=success&hash=&amount=-1&order_id=%00".toByteArray(),
            // Malformed percent escapes (a truncated "%" and "%ZZ") are the most likely hostile input of every form-encoded
            // gateway: InboundRequest.form() throws on them, so a provider has to survive that (02 section 14.1, 10 rule 8).
            "application/x-www-form-urlencoded" to "status=success&hash=%ZZ&amount=%".toByteArray(),
            // Invalid UTF-8 (0xC3 0x28) followed by a bare "&%".
            "application/x-www-form-urlencoded" to byteArrayOf(0x61, 0x3d, 0xC3.toByte(), 0x28, 0x26, 0x25),
            "multipart/form-data; boundary=x" to "--x\r\nContent-Disposition: form-data; name=\"hash\"\r\n\r\n%ZZ\r\n--x".toByteArray(),
            "text/xml" to "<?xml version=\"1.0\"?><a><b/>".toByteArray(),
            "application/octet-stream" to bytes(4096),
            "text/plain" to ByteArray(256 * 1024) { 'A'.code.toByte() },
            "application/json" to "😀 {\"emoji\":true}".toByteArray(Charsets.UTF_8)
        )
    }

    /** The multipart / attribute variant: the host hands the parsed fields over in [InboundRequest.formAttributes]. */
    private val multipartAttributes = mapOf("hash" to listOf("%ZZ", ""), "" to listOf("%"), "amount" to listOf("-1"))

    fun requests(kind: InboundKind): List<InboundRequest> = bodies.flatMapIndexed { i, (contentType, body) ->
        val headers = HashMap<String, List<String>>()
        contentType?.let { headers["content-type"] = listOf(it) }
        // Every header a gateway might sign with, carrying nonsense.
        for (name in listOf("x-signature", "stripe-signature", "x-hub-signature-256", "x-webhook-signature", "signature", "authorization")) {
            headers[name] = listOf(if (i % 2 == 0) "" else "v1=deadbeef,t=0,garbage")
        }
        listOf(
            InboundRequest(
                kind = kind, channel = "default", method = "POST", rawQuery = null, query = emptyMap(), headers = headers,
                contentType = contentType, body = body, remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
            ),
            InboundRequest(
                kind = kind, channel = "default", method = "GET", rawQuery = "status=ok&hash=zz&token=", headers = headers.filterKeys { it == "content-type" },
                query = mapOf("status" to listOf("ok"), "hash" to listOf("zz"), "token" to listOf("")), contentType = contentType, body = ByteArray(0),
                remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
            ),
            // A GET whose query carries a malformed percent escape, as the buyer's browser could send it.
            InboundRequest(
                kind = kind, channel = "default", method = "GET", rawQuery = "hash=%ZZ", headers = headers.filterKeys { it == "content-type" },
                query = mapOf("hash" to listOf("%ZZ")), contentType = contentType, body = ByteArray(0),
                remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
            ),
            InboundRequest(
                kind = kind, channel = "default", method = "POST", rawQuery = null, query = emptyMap(), headers = headers,
                contentType = contentType, body = body, remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
            ).also { it.formAttributes = multipartAttributes }
        )
    }
}

/** The public attributes of [target] as `name=value` lines, to compare two answers of a pure function. */
internal fun snapshot(target: Any): List<String> = target.javaClass.methods
    .filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("is")) && it.name != "getClass" }
    .sortedBy { it.name }
    .map { m ->
        val v = m.invoke(target)
        val text = when (v) {
            is Collection<*> -> v.map { it.toString() }.sorted().toString()
            is Map<*, *> -> v.entries.map { "${it.key}=${it.value}" }.sorted().toString()
            else -> "$v"
        }
        "${m.name}=$text"
    }

internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

internal val ID_PATTERN = Regex("^[a-z0-9-]{2,32}$")

private val KEY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")

/**
 * The checks of 02 section 14.1, as plain functions so a test can run them against any provider (including a
 * deliberately broken one, to prove a check bites). [ProviderContractTest] wraps each in a JUnit test.
 *
 * Override the hooks to describe the provider under test. Tests that would need a gateway (start, validate, saved)
 * run only when [gatewayIsFake] is true, i.e. the settings of [settingValues] point at a [FakeGateway]; nothing here
 * ever contacts a real host (URL settings default to a closed loopback port).
 */
abstract class PaymentContractChecks : AutoCloseable {
    /** A fresh provider instance. */
    protected abstract fun createProvider(): PaymentProvider

    /** Complete settings; secret fields should carry the markers of [TestContexts.defaultValues]. */
    protected open fun settingValues(provider: PaymentProvider): Map<String, Any?> = TestContexts.defaultValues(provider.settingsSchema())

    /** True when the provider's endpoints in [settingValues] are a [FakeGateway] (or it makes no outbound calls). */
    protected open val gatewayIsFake: Boolean get() = false

    protected open fun startRequest(provider: PaymentProvider): StartPaymentRequest = SampleData.startRequest(providerId = provider.id)

    /** A notification of an event type the provider does not know, signed correctly; null = not provided (test skipped). */
    protected open fun signedUnknownEvent(ctx: com.panomc.plugins.market.spi.payment.PaymentContext): InboundRequest? = null

    /** A signed notification plus the same one with an added unsigned field; null = not provided (test skipped). */
    protected open fun signedNotification(ctx: com.panomc.plugins.market.spi.payment.PaymentContext): SignedSample? = null

    private val vertxHolder = lazy { Vertx.vertx() }
    protected val vertx: Vertx get() = vertxHolder.value

    override fun close() {
        if (vertxHolder.isInitialized()) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun context(provider: PaymentProvider, settings: ProviderSettings = TestContexts.settings(settingValues(provider))) =
        TestContexts.payment(provider.id, settings, vertx)

    private fun secretValues(provider: PaymentProvider, settings: ProviderSettings): List<String> =
        provider.settingsSchema().secretKeys.mapNotNull { settings.string(it) }.filter { it.length >= 4 }

    private fun handle(provider: PaymentProvider, ctx: com.panomc.plugins.market.spi.payment.PaymentContext, http: InboundRequest): InboundResult {
        val attempt = if (http.kind == InboundKind.WEBHOOK) null else SampleData.attemptView()
        val outcome = if (http.kind == InboundKind.RETURN) ReturnOutcome.SUCCESS else null
        return blocking { provider.handleInbound(ctx, PaymentInboundRequest(http, attempt, outcome, null)) }
    }

    fun checkDescriptor() {
        val p = createProvider()
        assertTrue(ID_PATTERN.matches(p.id), "provider id '${p.id}' must match [a-z0-9-]{2,32}")
        assertTrue(p.descriptor.displayName.resolve("en-US").isNotBlank(), "descriptor displayName has no English text")
        assertTrue(p.descriptor.description.resolve("en-US").isNotBlank(), "descriptor description has no English text")
        assertTrue(p.descriptor.icon.isNotBlank(), "descriptor icon is blank")
        for (locale in listOf("tr", "ru", "de-DE", "xx")) p.descriptor.displayName.resolve(locale)
    }

    fun checkSchema() {
        val schema = createProvider().settingsSchema()
        val keys = schema.fields.map { it.key }
        assertTrue(keys.all { it.isNotEmpty() }, "empty schema key")
        assertTrue(keys.all { KEY_PATTERN.matches(it) }, "schema key outside ${KEY_PATTERN.pattern}: ${keys.filterNot { KEY_PATTERN.matches(it) }}")
        assertEquals(keys.size, keys.toSet().size, "duplicate schema keys: ${keys.groupBy { it }.filterValues { it.size > 1 }.keys}")
        assertTrue(keys.containsAll(schema.secretKeys), "secret keys must be schema fields")
        schema.toJson()
    }

    fun checkCapabilitiesArePure() {
        val p = createProvider()
        val values = settingValues(p)
        val a = snapshot(p.capabilities(TestContexts.settings(values)))
        val b = snapshot(p.capabilities(TestContexts.settings(values)))
        assertEquals(a, b, "capabilities() gave different answers for the same settings")
        val c = snapshot(createProvider().capabilities(TestContexts.settings(values)))
        assertEquals(a, c, "capabilities() differs between two provider instances")
    }

    fun checkSecretsNeverLogged() {
        val provider = createProvider()
        val settings = TestContexts.settings(settingValues(provider))
        val secrets = secretValues(provider, settings)
        val ctx = context(provider, settings)
        provider.capabilities(settings)
        for (kind in InboundKind.entries) for (req in Garbage.requests(kind)) runCatching { handle(provider, ctx, req) }
        signedUnknownEvent(ctx)?.let { runCatching { handle(provider, ctx, it) } }
        signedNotification(ctx)?.let { s ->
            runCatching { handle(provider, ctx, s.request) }
            runCatching { handle(provider, ctx, s.withUnsignedField) }
        }
        if (gatewayIsFake) {
            runCatching { blocking { provider.validateSettings(ctx, settings) } }
            runCatching { blocking { provider.onSettingsSaved(ctx, null) } }
            runCatching { blocking { provider.startPayment(ctx, startRequest(provider)) } }
        }
        val logged = ctx.recordedLog.everything()
        for (secret in secrets) assertFalse(logged.contains(secret), "a secret setting value was written to ProviderLog")
    }

    fun checkGarbageInboundIsRejectedOrIgnored() {
        val provider = createProvider()
        val ctx = context(provider)
        for (kind in InboundKind.entries) for (req in Garbage.requests(kind)) {
            val result = try {
                handle(provider, ctx, req)
            } catch (e: Throwable) {
                fail("handleInbound threw ${e.javaClass.name} for garbage ${req.kind} ${req.method} (${req.body.size} bytes, ${req.contentType})", e)
            }
            assertTrue(result.reply.status in 100..599)
            if (kind == InboundKind.RETURN) {
                // The buyer can forge a browser return at will: it must be rejected or ignored like any other garbage; only a
                // trigger-style "pending" answer is tolerated (02 section 14.1, 7.3 step 6 applies every event of a verified result).
                assertTrue(
                    !result.verified || result.events.all { it is PaymentEvent.Pending },
                    "a garbage RETURN produced ${result.events.map { it.javaClass.simpleName }}"
                )
            } else {
                assertTrue(result.events.isEmpty(), "garbage ${req.kind} (${req.body.size} bytes, ${req.contentType}) produced ${result.events.size} event(s)")
            }
        }
    }

    fun checkEmptySettingsOnlyConfigurationErrors() {
        val provider = createProvider()
        val ctx = TestContexts.payment(provider.id, TestContexts.settings(), vertx)
        for (kind in InboundKind.entries) for (req in Garbage.requests(kind)) {
            try {
                handle(provider, ctx, req)
            } catch (e: ProviderException) {
                assertEquals(ProviderErrorCode.CONFIGURATION, e.code, "with empty settings handleInbound may only throw CONFIGURATION, got ${e.code}")
            } catch (e: Throwable) {
                fail("with empty settings handleInbound threw ${e.javaClass.name} instead of ProviderException(CONFIGURATION)", e)
            }
        }
    }

    fun checkUnknownEventsAreIgnored() {
        val provider = createProvider()
        val ctx = context(provider)
        val request = signedUnknownEvent(ctx)
        assumeTrue(request != null, "provider test supplies no signed unknown event (signedUnknownEvent)")
        val result = handle(provider, ctx, request!!)
        assertTrue(result.events.isEmpty(), "an unknown event type produced events")
        assertTrue(result.reply.status < 500, "an unknown event type must not make the gateway retry (status ${result.reply.status})")
    }

    fun checkEventKeyIgnoresUnsignedFields() {
        val provider = createProvider()
        val ctx = context(provider)
        val sample = signedNotification(ctx)
        assumeTrue(sample != null, "provider test supplies no signed notification (signedNotification)")
        val first = handle(provider, ctx, sample!!.request)
        val second = handle(provider, ctx, sample.withUnsignedField)
        assertTrue(first.verified, "the signed notification was not accepted as verified; fix the sample")
        assumeTrue(first.eventKey != null, "provider sets no eventKey")
        assertEquals(first.eventKey, second.eventKey, "eventKey changed when an unsigned field was added")
    }

    fun checkStartResultIsWellFormed() {
        assumeTrue(gatewayIsFake, "gatewayIsFake is false: startPayment is not exercised")
        val provider = createProvider()
        val ctx = context(provider)
        val request = startRequest(provider)
        val result = try {
            blocking { provider.startPayment(ctx, request) }
        } catch (e: Throwable) {
            fail("startPayment against the fake gateway threw ${e.javaClass.name}: ${e.message}", e)
        }
        when (result) {
            is StartPaymentResult.Html -> for (origin in result.scriptOrigins + result.frameOrigins + result.connectOrigins + result.formActionOrigins)
                assertTrue(origin.startsWith("https://"), "Html origin '$origin' is not https")
            else -> Unit
        }
        val json = result.toPaymentStartJson("en-US", attemptPageUrl = "https://shop.example/api/market/payments/attempts/tok/page")
        assertEquals(result.kind, json.getString("kind"))
    }
}

/**
 * The provider contract of 02 section 14.1. A plugin test extends this class, implements [createProvider] and
 * overrides the hooks it can; every check below then runs as a JUnit test. Published in the API jar.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ProviderContractTest : PaymentContractChecks() {
    @Test fun `descriptor is complete`() = checkDescriptor()

    @Test fun `schema keys are unique and non-empty`() = checkSchema()

    @Test fun `capabilities are pure`() = checkCapabilitiesArePure()

    @Test fun `secret settings never reach the provider log`() = checkSecretsNeverLogged()

    @Test fun `garbage inbound traffic is rejected or ignored and never throws`() = checkGarbageInboundIsRejectedOrIgnored()

    @Test fun `with empty settings inbound traffic only fails with CONFIGURATION`() = checkEmptySettingsOnlyConfigurationErrors()

    @Test fun `unknown event types are ignored`() = checkUnknownEventsAreIgnored()

    @Test fun `eventKey does not change when an unsigned field is added`() = checkEventKeyIgnoresUnsignedFields()

    @Test fun `start result is well formed and Html declares https origins only`() = checkStartResultIsWellFormed()

    @AfterAll
    fun closeContractResources() = close()
}
