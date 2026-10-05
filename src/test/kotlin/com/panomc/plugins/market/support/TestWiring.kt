package com.panomc.plugins.market.support

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.impl.*
import com.panomc.plugins.market.db.tx.MarketDb
import io.vertx.sqlclient.Pool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The real object graph of a tier-T2 test, built by hand (17 section 5.3): real DAO implementations on the class's
 * throwaway database, the real [MarketDb], a [FakeClock], [SeqIds] and a mutable [MarketConfig]. One factory used by
 * every T2 test: `val w = TestWiring(pool)`. If a service cannot be constructed from these parts, rule S3 of 17
 * section 4 is broken.
 *
 * This is the base the service slices extend: each one adds its service (and the fakes of 17 section 5.5 that it
 * needs, `FakeMailGateway`, `FakeMcComponent`, `StaticProviderLookup`) as a property here and in [Fixtures]. The DAOs
 * read the table prefix through `MarketTables.prefixOverride`, which `MarketDbTestBase` sets for the class, so
 * constructing a wiring needs nothing else.
 *
 * Constructing a wiring points [InvariantChecker.nowProvider] at its clock (the age based invariants I18 and I19 must
 * judge rows written with the fake clock by that clock); [close] restores the system clock.
 */
class TestWiring(
    val pool: Pool,
    val clock: FakeClock = FakeClock(),
    val ids: SeqIds = SeqIds(),
    config: MarketConfig = defaultConfig()
) : AutoCloseable {
    /** Replaced by [configure]; services read it through the wiring, never copy it. */
    @Volatile
    var config: MarketConfig = config
        private set

    val db: MarketDb = MarketDb({ pool }, clock)
    val users = TestUsers()

    // --- DAOs (all 53 tables) ---
    val categories = MarketCategoryDaoImpl()
    val comparisons = MarketComparisonDaoImpl()
    val products = MarketProductDaoImpl()
    val variants = MarketProductVariantDaoImpl()
    val prices = MarketProductPriceDaoImpl()
    val fields = MarketProductFieldDaoImpl()
    val bundleItems = MarketBundleItemDaoImpl()
    val providerMeta = MarketProductProviderMetaDaoImpl()
    val currencyRates = MarketCurrencyRateDaoImpl()
    val discounts = MarketDiscountDaoImpl()
    val coupons = MarketCouponDaoImpl()
    val creatorCodes = MarketCreatorCodeDaoImpl()
    val gifts = MarketGiftDaoImpl()
    val redemptions = MarketRedemptionDaoImpl()
    val creatorEarnings = MarketCreatorEarningDaoImpl()
    val creatorPayouts = MarketCreatorPayoutDaoImpl()
    val orders = MarketOrderDaoImpl()
    val orderItems = MarketOrderItemDaoImpl()
    val orderEvents = MarketOrderEventDaoImpl()
    val legalTexts = MarketLegalTextDaoImpl()
    val entitlements = MarketEntitlementDaoImpl()
    val addresses = MarketAddressDaoImpl()
    val carts = MarketCartDaoImpl()
    val cartItems = MarketCartItemDaoImpl()
    val sequences = MarketSequenceDaoImpl()
    val invoices = MarketInvoiceDaoImpl()
    val paymentMethods = MarketPaymentMethodDaoImpl()
    val payments = MarketPaymentDaoImpl()
    val paymentEvents = MarketPaymentEventDaoImpl()
    val refunds = MarketRefundDaoImpl()
    val refundItems = MarketRefundItemDaoImpl()
    val disputes = MarketDisputeDaoImpl()
    val providerState = MarketProviderStateDaoImpl()
    val creditAccounts = MarketCreditAccountDaoImpl()
    val creditTxs = MarketCreditTxDaoImpl()
    val creditEntries = MarketCreditEntryDaoImpl()
    val deliveries = MarketDeliveryDaoImpl()
    val serverStates = MarketServerStateDaoImpl()
    val webhookEndpoints = MarketWebhookEndpointDaoImpl()
    val webhookDeliveries = MarketWebhookDeliveryDaoImpl()
    val mailOutbox = MarketMailOutboxDaoImpl()
    val subscriptions = MarketSubscriptionDaoImpl()
    val subscriptionRenewals = MarketSubscriptionRenewalDaoImpl()
    val blocks = MarketBlockDaoImpl()
    val throttles = MarketThrottleDaoImpl()
    val goals = MarketGoalDaoImpl()
    val shippingZones = MarketShippingZoneDaoImpl()
    val shippingMethods = MarketShippingMethodDaoImpl()
    val shippingRates = MarketShippingRateDaoImpl()
    val shippingCarriers = MarketShippingCarrierDaoImpl()
    val shipments = MarketShipmentDaoImpl()
    val shipmentItems = MarketShipmentItemDaoImpl()
    val shipmentEvents = MarketShipmentEventDaoImpl()

    val fixtures: Fixtures = Fixtures(this)

    init {
        InvariantChecker.nowProvider = clock::now
    }

    /** Applies [change] to the current config and installs the result (`MarketConfig` is immutable: build a new one). */
    fun configure(change: (MarketConfig) -> MarketConfig): MarketConfig {
        config = change(config)
        return config
    }

    /** `InvariantChecker.assertAll` with this wiring's clock and the revoke switches of its config. */
    suspend fun assertInvariants(legacy: Boolean = false) = InvariantChecker.assertAll(
        pool,
        InvariantChecker.Options(
            legacy = legacy,
            nowMs = clock.now(),
            revokeOnRefund = config.revokeOnRefund,
            revokeOnChargeback = config.revokeOnChargeback
        )
    )

    override fun close() {
        InvariantChecker.nowProvider = { System.currentTimeMillis() }
    }

    companion object {
        /** The store of 17 section 5.6: base currency EUR, VAT 20 % shown in the price, credit value 1.0, time zone UTC. */
        fun defaultConfig(): MarketConfig = MarketConfig(
            currency = com.panomc.plugins.market.util.CurrencyType.EUR,
            vatPercent = 20.0,
            showVatInPrice = true,
            creditValue = 1.0,
            storeTimeZone = "UTC"
        )
    }
}

/**
 * The in-memory user directory of the wiring (17 section 5.3): ids from 1, the credit account row is created by
 * [Fixtures.user]. The platform's `user` table is not touched; service tests never read it (S9).
 */
class TestUsers {
    private val next = AtomicLong(0)
    private val byName = ConcurrentHashMap<String, Long>()
    private val byId = ConcurrentHashMap<Long, String>()

    fun create(username: String): Long {
        require(byName.putIfAbsent(username.lowercase(), -1) == null) { "user $username exists" }
        val id = next.incrementAndGet()
        byName[username.lowercase()] = id
        byId[id] = username
        return id
    }

    fun idOf(username: String): Long? = byName[username.lowercase()]?.takeIf { it > 0 }

    fun nameOf(id: Long): String? = byId[id]

    val all: Map<Long, String> get() = byId.toMap()
}
