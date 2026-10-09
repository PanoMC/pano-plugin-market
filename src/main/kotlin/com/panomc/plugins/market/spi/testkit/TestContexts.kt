package com.panomc.plugins.market.spi.testkit

import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.FieldType
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderLog
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.ProviderStateStore
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.spi.payment.AttemptRef
import com.panomc.plugins.market.spi.payment.AttemptUrls
import com.panomc.plugins.market.spi.payment.BuyerInfo
import com.panomc.plugins.market.spi.payment.OrderLine
import com.panomc.plugins.market.spi.payment.OrderSnapshot
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentLookup
import com.panomc.plugins.market.spi.payment.PaymentUrls
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.SubscriptionView
import com.panomc.plugins.market.spi.shipping.CreateShipmentRequest
import com.panomc.plugins.market.spi.shipping.Parcel
import com.panomc.plugins.market.spi.shipping.ShipItem
import com.panomc.plugins.market.spi.shipping.ShipmentLookup
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingUrls
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import com.panomc.plugins.market.spi.MarketSpiPaths

/**
 * Ready-made provider contexts for plugin tests (02 section 14, 17 section 5.5): settings from a map, an in-memory
 * state store, a recording log and a real Vert.x [WebClient], with no database and no market runtime. Point the
 * provider's base-url setting at a [FakeGateway] and everything it sends is recorded.
 */
object TestContexts {
    /** Epoch ms every test context starts at (17 section 5.5 FakeClock). */
    const val START_MS: Long = 1_760_000_000_000L

    /** Settings backed by [values]: strings are trimmed, a blank string counts as absent. */
    fun settings(values: Map<String, Any?> = emptyMap()): ProviderSettings = MapSettings(values)

    /** Settings with a value for every stored field of [schema] (see [defaultValues]). */
    fun settings(schema: SettingsSchema): ProviderSettings = MapSettings(defaultValues(schema))

    /**
     * A plausible value for every stored field: secrets get a distinct, long, recognisable marker (so a leak is easy
     * to find), selects the first option, switches / numbers / texts the declared default or a neutral value.
     */
    fun defaultValues(schema: SettingsSchema): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (f in schema.storedFields) {
            out[f.key] = when {
                f.secret -> secretMarker(f.key)
                f.type == FieldType.SELECT -> f.options.first().value
                f.type == FieldType.SWITCH -> f.default ?: false
                f.type == FieldType.NUMBER -> f.default ?: f.min ?: 1
                f.type == FieldType.URL -> f.default ?: "http://127.0.0.1:9/${f.key}" // closed port: a provider that calls out by mistake fails fast, never reaches the internet
                f.type == FieldType.HIDDEN -> f.default ?: "hidden-${f.key}"
                else -> f.default ?: "value-${f.key}"
            }
        }
        return out
    }

    /** The marker [defaultValues] uses for a secret field. */
    fun secretMarker(key: String): String = "SECRET-$key-7f3a91c2e8b4"

    fun payment(
        providerId: String = "fake",
        settings: ProviderSettings = settings(),
        vertx: Vertx,
        testMode: Boolean = false,
        payments: PaymentLookup = EmptyPaymentLookup,
        site: SiteInfo = defaultSite()
    ): TestPaymentContext = TestPaymentContext(providerId, settings, vertx, testMode, payments, site)

    fun shipping(
        providerId: String = "fake",
        settings: ProviderSettings = settings(),
        vertx: Vertx,
        testMode: Boolean = false,
        shipments: ShipmentLookup = EmptyShipmentLookup,
        site: SiteInfo = defaultSite()
    ): TestShippingContext = TestShippingContext(providerId, settings, vertx, testMode, shipments, site)

    fun defaultSite() = SiteInfo("Test shop", "https://shop.example", https = true, publiclyReachable = true, defaultLocale = "en-US")

    /** A lookup that knows nothing. */
    object EmptyPaymentLookup : PaymentLookup {
        override suspend fun byId(attemptId: Long): PaymentAttemptView? = null

        override suspend fun byReference(reference: String): PaymentAttemptView? = null

        override suspend fun byGatewayTransactionId(id: String): PaymentAttemptView? = null

        override suspend fun byGatewayRef(name: String, value: String): PaymentAttemptView? = null

        override suspend fun subscriptionByGatewayId(gatewaySubscriptionId: String): SubscriptionView? = null
    }

    /** A lookup that knows nothing. */
    object EmptyShipmentLookup : ShipmentLookup {
        override suspend fun byMerchantReference(reference: String): ShipmentView? = null

        override suspend fun byCarrierReference(reference: String): ShipmentView? = null

        override suspend fun byTrackingNumber(trackingNumber: String): ShipmentView? = null
    }
}

/** [ProviderSettings] over a plain map. */
class MapSettings(values: Map<String, Any?>) : ProviderSettings {
    private val values: Map<String, Any?> = LinkedHashMap(values)

    override fun string(key: String): String? = when (val v = values[key]) {
        null -> null
        is String -> v.trim().ifEmpty { null }
        else -> v.toString()
    }

    override fun require(key: String): String =
        string(key) ?: throw ProviderException(ProviderErrorCode.CONFIGURATION, "Setting '$key' is not configured")

    override fun boolean(key: String, default: Boolean): Boolean = when (val v = values[key]) {
        null -> default
        is Boolean -> v
        else -> v.toString().trim().lowercase().let { it == "true" || it == "1" }
    }

    override fun long(key: String): Long? = when (val v = values[key]) {
        null -> null
        is Number -> v.toLong()
        else -> v.toString().trim().toLongOrNull()
    }

    override fun asJson(): JsonObject = JsonObject().also { json -> values.forEach { (k, v) -> if (v != null) json.put(k, v) } }
}

/** In-memory [ProviderStateStore] with TTL (against the context clock) and compare-and-set. */
class InMemoryStateStore(private val clock: () -> Long = System::currentTimeMillis) : ProviderStateStore {
    private class Entry(val value: String, val expiresAt: Long?)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Live keys, for assertions. */
    val keys: Set<String> get() = entries.keys.filter { live(it) }.toSet()

    private fun live(key: String): Boolean {
        val e = entries[key] ?: return false
        val exp = e.expiresAt
        if (exp != null && exp <= clock()) {
            entries.remove(key, e)
            return false
        }
        return true
    }

    private fun expiry(ttlSeconds: Long?): Long? = ttlSeconds?.let { clock() + it * 1000 }

    override suspend fun get(key: String): String? = if (live(key)) entries[key]?.value else null

    override suspend fun put(key: String, value: String, ttlSeconds: Long?) {
        entries[key] = Entry(value, expiry(ttlSeconds))
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }

    @Synchronized
    private fun cas(key: String, expected: String?, value: String, ttlSeconds: Long?): Boolean {
        val current = if (live(key)) entries[key]?.value else null
        if (current != expected) return false
        entries[key] = Entry(value, expiry(ttlSeconds))
        return true
    }

    override suspend fun compareAndSet(key: String, expected: String?, value: String, ttlSeconds: Long?): Boolean =
        cas(key, expected, value, ttlSeconds)
}

/**
 * [ProviderLog] that keeps everything, **unredacted**: market redacts secret values in production, this log shows
 * what the provider itself wrote, so a test can prove that no secret was ever handed to it.
 */
class RecordingLog : ProviderLog {
    class Exchange(val channel: String, val request: String?, val response: String?, val status: Int?, val durationMs: Long)

    private val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val exchangeList = java.util.concurrent.CopyOnWriteArrayList<Exchange>()

    /** `LEVEL message` lines, error stack traces included as `ExceptionClass: message`. */
    val entries: List<String> get() = lines.toList()
    val exchanges: List<Exchange> get() = exchangeList.toList()

    /** Everything the provider logged, as one string to search in. */
    fun everything(): String = buildString {
        lines.forEach { appendLine(it) }
        exchangeList.forEach { appendLine("${it.channel} ${it.request} ${it.response} ${it.status}") }
    }

    override fun info(message: String) {
        lines += "INFO $message"
    }

    override fun warn(message: String, error: Throwable?) {
        lines += "WARN $message${describe(error)}"
    }

    override fun error(message: String, error: Throwable?) {
        lines += "ERROR $message${describe(error)}"
    }

    override fun exchange(channel: String, request: String?, response: String?, status: Int?, durationMs: Long) {
        exchangeList += Exchange(channel, request, response, status, durationMs)
    }

    private fun describe(e: Throwable?): String = if (e == null) "" else " ${e.javaClass.name}: ${e.message}"
}

private class ContextBase(
    val providerId: String,
    val settings: ProviderSettings,
    val vertx: Vertx,
    val testMode: Boolean,
    val site: SiteInfo
) {
    val clock = java.util.concurrent.atomic.AtomicLong(TestContexts.START_MS)
    val state = InMemoryStateStore { clock.get() }
    val log = RecordingLog()
    val http: WebClient by lazy { WebClient.create(vertx) }
}

/** A [PaymentContext] for tests. [recordedLog] and [stateStore] expose what the provider did; [advance] moves the clock. */
class TestPaymentContext internal constructor(
    override val providerId: String,
    override val settings: ProviderSettings,
    override val vertx: Vertx,
    override val testMode: Boolean,
    override val payments: PaymentLookup,
    override val site: SiteInfo
) : PaymentContext {
    private val base = ContextBase(providerId, settings, vertx, testMode, site)
    private val locks = ConcurrentHashMap<Long, Mutex>()

    override val http: WebClient get() = base.http
    override val log: ProviderLog get() = base.log
    override val state: ProviderStateStore get() = base.state
    val recordedLog: RecordingLog get() = base.log
    val stateStore: InMemoryStateStore get() = base.state

    override val urls: PaymentUrls = object : PaymentUrls {
        override fun webhook(channel: String): String =
            "${site.baseUrl}${MarketSpiPaths.site("/payments/$providerId/webhook")}" + if (channel == MarketSpi.DEFAULT_CHANNEL) "" else "/$channel"

        override fun checkoutPage(): String = "${site.baseUrl}/store/checkout"

        override fun forAttempt(attempt: PaymentAttemptView): AttemptUrls = SampleData.attemptUrls(site.baseUrl, providerId, attempt.token, attempt.orderPublicId)
    }

    override fun now(): Long = base.clock.get()

    /** Moves the context clock (state store TTLs follow it). */
    fun advance(ms: Long) {
        base.clock.addAndGet(ms)
    }

    override suspend fun <T> withAttemptLock(attemptId: Long, block: suspend () -> T): T =
        locks.computeIfAbsent(attemptId) { Mutex() }.withLock { block() }
}

/** A [ShippingContext] for tests, same shape as [TestPaymentContext]. */
class TestShippingContext internal constructor(
    override val providerId: String,
    override val settings: ProviderSettings,
    override val vertx: Vertx,
    override val testMode: Boolean,
    override val shipments: ShipmentLookup,
    override val site: SiteInfo
) : ShippingContext {
    private val base = ContextBase(providerId, settings, vertx, testMode, site)

    override val http: WebClient get() = base.http
    override val log: ProviderLog get() = base.log
    override val state: ProviderStateStore get() = base.state
    val recordedLog: RecordingLog get() = base.log
    val stateStore: InMemoryStateStore get() = base.state

    override val urls: ShippingUrls = object : ShippingUrls {
        override fun webhook(channel: String): String =
            "${site.baseUrl}${MarketSpiPaths.site("/shipping/$providerId/webhook/test-install-token")}" +
                if (channel == MarketSpi.DEFAULT_CHANNEL) "" else "/$channel"
    }

    override fun now(): Long = base.clock.get()

    fun advance(ms: Long) {
        base.clock.addAndGet(ms)
    }
}

/** Fixtures the contract tests (and plugin tests) use: one EUR order, a buyer, URLs, an address, a parcel. */
object SampleData {
    /** EUR amount in internal units (two decimals: 1000 = 10.00). */
    fun eur(internal: Long): Money = Money(internal, "EUR")

    fun attemptUrls(base: String = "https://shop.example", providerId: String = "fake", token: String = "tok", orderPublicId: String = "ABCDEFGHJKMNPQRSTVWX") = AttemptUrls(
        success = "$base${MarketSpiPaths.site("/payments/$providerId/return/$token/success")}",
        cancel = "$base${MarketSpiPaths.site("/payments/$providerId/return/$token/cancel")}",
        pending = "$base${MarketSpiPaths.site("/payments/$providerId/return/$token/pending")}",
        result = "$base${MarketSpiPaths.site("/payments/$providerId/return/$token/result")}",
        notify = "$base${MarketSpiPaths.site("/payments/$providerId/notify/$token")}",
        orderPage = "$base/store/order/$orderPublicId"
    )

    fun buyer() = BuyerInfo(
        userId = 7, guest = false, numericId = 7, stableId = "u7", username = "steve", email = "steve@example.com", ip = "203.0.113.5",
        userAgent = "contract-test", locale = "en-US", firstName = "Steve", lastName = "Miner", phone = null, country = "DE",
        identityNumber = null, registeredAt = TestContexts.START_MS - 86_400_000L
    )

    /** One order line for [total] (default 10.00 EUR), no shipping, no fee. */
    fun order(total: Money = Money(1000, "EUR")): OrderSnapshot {
        val currency = total.currency
        val line = OrderLine(
            orderItemId = 1, productId = 11, name = "VIP rank", sku = "VIP", variantName = null, quantity = 1, unitPrice = total,
            total = total, vatPercent = 20, physical = false, categoryName = null, providerMeta = null
        )
        val zero = Money(0, currency)
        return OrderSnapshot(
            id = 1, publicId = "ABCDEFGHJKMNPQRSTVWX", description = "Order ABCDEFGHJKMNPQRSTVWX", currency = currency, lines = listOf(line),
            subtotal = total, discount = zero, shipping = zero, fee = zero, vat = zero, total = total, creditValue = zero,
            requiresShipping = false, recipientUsername = "steve", gift = false, pricingMode = "MARKET"
        )
    }

    fun startRequest(amount: Money = Money(1000, "EUR"), baseUrl: String = "https://shop.example", providerId: String = "fake"): StartPaymentRequest {
        val order = order(amount)
        return StartPaymentRequest(
            attempt = AttemptRef(5, "ABCDEFGHJKMNPQRSTVWX", "tok"), amount = amount, order = order, buyer = buyer(), billing = null,
            shipping = null, subscription = null, urls = attemptUrls(baseUrl, providerId), idempotencyKey = "idem-1", locale = "en-US",
            expiresAt = TestContexts.START_MS + 3_600_000L, replaces = null
        )
    }

    fun attemptView(amount: Money = Money(1000, "EUR")) = PaymentAttemptView(
        id = 5, reference = "ABCDEFGHJKMNPQRSTVWX", token = "tok", status = "PENDING", amount = amount, orderId = 1,
        orderPublicId = "ABCDEFGHJKMNPQRSTVWX", gatewayTransactionId = null, gatewayRefs = emptyMap(), providerData = null,
        testMode = false, createdAt = TestContexts.START_MS, expiresAt = null, subscription = null, paidAmount = null,
        refundedAmount = Money(0, amount.currency), paidAt = null
    )

    fun address(country: String = "DE") = Address(
        firstName = "Steve", lastName = "Miner", company = null, phone = "+491701234567", email = "steve@example.com", country = country,
        state = null, city = "Berlin", district = null, neighborhood = null, line1 = "Example Street 1", line2 = null, postalCode = "10115",
        taxOffice = null, taxNumber = null, identityNumber = null
    )

    fun parcel() = Parcel(weightGrams = 500, lengthMm = 200, widthMm = 150, heightMm = 100)

    fun shipItem() = ShipItem(
        orderItemId = 1, name = "T-shirt", sku = "SHIRT", quantity = 1, unitWeightGrams = 250, unitValue = Money(2000, "EUR"),
        hsCode = null, originCountry = "DE"
    )

    fun createShipmentRequest(previousCarrierReference: String? = null, previousProviderData: JsonObject? = null) = CreateShipmentRequest(
        shipmentId = 9, merchantReference = "SHP-9", orderPublicId = "ABCDEFGHJKMNPQRSTVWX", from = address("DE"), to = address("DE"),
        parcels = listOf(parcel()), items = listOf(shipItem()), serviceCode = null, rateRef = null, preferredLabelFormat = null,
        declaredValue = Money(2000, "EUR"), note = null, previousCarrierReference = previousCarrierReference,
        previousProviderData = previousProviderData
    )
}
