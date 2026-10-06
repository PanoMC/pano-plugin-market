package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.CurrencyType
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `StatsService` on a real MariaDB (MK-171; 01 section 14.3, 00 section 9, 17 `StatsIT`): revenue = `gatewayAmount - refundedGatewayAmount` of paid, non-test
 * orders dated by `paidAt` in the store zone, converted per order; test orders, unpaid and fully refunded or charged-back orders excluded; legacy rows
 * counted (I17); top products by product id; the additive keys of v2.
 */
class StatsIT : MarketDaoITBase() {
    /** Orders are written raw (the cross-table invariants do not describe a stats fixture). */
    override suspend fun assertInvariants() {}

    private val w by lazy { TestWiring(pool) }

    private val service by lazy { StatsService({ w.config }, w.clock) { w.orders.prefix() } }

    private val utc = ZoneId.of("UTC")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, zone: ZoneId = utc) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private var seq = 0

    private fun config(zone: String = "UTC", stats: CurrencyType = CurrencyType.EUR, sales: CurrencyType = CurrencyType.EUR, rate: Double = 1.0) =
        MarketConfig(currency = sales, statsCurrency = stats, exchangeRate = rate, storeTimeZone = zone, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0)

    @BeforeEach
    fun setUp() {
        // Wednesday 7 October 2026, 12:00 UTC
        w.clock.set(at(2026, 10, 7))
        w.configure { config() }
    }

    private suspend fun order(
        paidAt: Long?,
        gateway: Long,
        refunded: Long = 0,
        status: OrderStatus = OrderStatus.COMPLETED,
        test: Boolean = false,
        currency: String = "EUR",
        rate: Double? = null,
        credit: Long = 0,
        label: String = "Card",
        source: OrderSource = OrderSource.STOREFRONT,
        createdAt: Long = paidAt ?: 1000L
    ): Long {
        val n = ++seq

        return w.orders.add(
            MarketOrder(
                playerUsername = "p$n", totalPrice = gateway + credit, currency = currency, paymentLabel = label, paymentMethodId = label.lowercase(), status = status, exchangeRate = rate,
                publicId = "STATS" + n.toString().padStart(15, '0'), source = source, buyerKey = "g:p$n", creditValue = credit, gatewayAmount = gateway, refundedGatewayAmount = refunded,
                refundedTotal = refunded, paidAt = paidAt, testMode = test, createdAt = createdAt, updatedAt = paidAt ?: createdAt
            ),
            pool
        )
    }

    private suspend fun stats(from: Long? = null, to: Long? = null): JsonObject = service.stats(from, to, pool)

    private fun JsonObject.block(name: String) = getJsonObject("summary").getJsonObject(name)

    private fun JsonObject.doubles(chart: String): List<Double> = getJsonObject("charts").getJsonObject(chart).getJsonArray("values").map { (it as Number).toDouble() }

    private suspend fun seedWeek() {
        order(at(2026, 10, 7, 8), 10000)                                   // A: today 100.00
        order(at(2026, 10, 6, 10), 5000, refunded = 2000, status = OrderStatus.PARTIALLY_REFUNDED) // B: 30.00 net
        order(at(2026, 10, 1, 9), 20000)                                   // C: 6 days ago 200.00
        order(at(2026, 9, 30, 9), 8000)                                    // D: previous week 80.00
        order(at(2026, 8, 15), 10000)                                      // E: old 100.00
        order(at(2026, 10, 7, 9), 99999, test = true)                      // test order
        order(at(2026, 10, 7, 9), 3000, refunded = 3000, status = OrderStatus.REFUNDED)
        order(at(2026, 10, 7, 9), 4000, status = OrderStatus.CHARGEBACK)
        order(null, 5000, status = OrderStatus.PENDING)
    }

    @Test
    fun `revenue is the money the gateway kept by paidAt, test, unpaid, refunded and charged-back orders do not count`(): Unit = runBlocking {
        seedWeek()

        val weekly = stats().block("weekly")
        val monthly = stats().block("monthly")
        val total = stats().block("total")

        assertEquals(3L, weekly.getLong("count"))
        assertEquals(330.0, weekly.getDouble("revenue"), 0.001)
        assertEquals(80.0, weekly.getDouble("previous"), 0.001)
        assertEquals(312.5, weekly.getDouble("trend"), 0.001)

        assertEquals(4L, monthly.getLong("count"))
        assertEquals(410.0, monthly.getDouble("revenue"), 0.001)
        // E (15 August) lies in the 30 days before the monthly window (9 August to 7 September)
        assertEquals(100.0, monthly.getDouble("previous"), 0.001)
        assertEquals(310.0, monthly.getDouble("trend"), 0.001)

        assertEquals(5L, total.getLong("count"))
        assertEquals(510.0, total.getDouble("revenue"), 0.001)
    }

    @Test
    fun `the sparklines are per local day and add up to the window`(): Unit = runBlocking {
        seedWeek()

        val body = stats()
        val spark = body.block("weekly").getJsonArray("spark").map { (it as Number).toDouble() }

        assertEquals(7, spark.size)
        // days 1 .. 7 October: C on the 1st (200), B on the 6th (30), A on the 7th (100)
        assertEquals(listOf(200.0, 0.0, 0.0, 0.0, 0.0, 30.0, 100.0), spark)
        assertEquals(330.0, spark.sum(), 0.001)
        assertEquals(30, body.block("monthly").getJsonArray("spark").size())
    }

    @Test
    fun `revenue is dated by paidAt, not by createdAt`(): Unit = runBlocking {
        // created two months ago (an old pending order), paid today
        order(at(2026, 10, 7, 9), 12345, createdAt = at(2026, 8, 1))
        // created today, paid ... never counted before it is paid; and one paid last year but created this week
        order(at(2025, 10, 7), 7777, createdAt = at(2026, 10, 7))

        val body = stats()

        assertEquals(1L, body.block("weekly").getLong("count"))
        assertEquals(123.45, body.block("weekly").getDouble("revenue"), 0.001)
        assertEquals(2L, body.block("total").getLong("count"))
    }

    @Test
    fun `the buckets follow the store time zone, an order after local midnight is on the new local day`(): Unit = runBlocking {
        // 21:30 UTC on 6 October is 00:30 on 7 October in Istanbul (UTC+3)
        val paid = ZonedDateTime.of(2026, 10, 6, 21, 30, 0, 0, utc).toInstant().toEpochMilli()

        order(paid, 10000)

        w.configure { config(zone = "UTC") }
        val inUtc = stats().block("weekly").getJsonArray("spark").map { (it as Number).toDouble() }

        w.configure { config(zone = "Europe/Istanbul") }
        val inIstanbul = stats().block("weekly").getJsonArray("spark").map { (it as Number).toDouble() }

        assertEquals(100.0, inUtc[5], 0.001, "6 October in UTC")
        assertEquals(0.0, inUtc[6], 0.001)
        assertEquals(0.0, inIstanbul[5], 0.001)
        assertEquals(100.0, inIstanbul[6], 0.001, "7 October in Istanbul")
    }

    @Test
    fun `legacy rows count once the migration fixups ran, a legacy row that was never paid does not`(): Unit = runBlocking {
        val updated = at(2026, 10, 5)

        // what a version 2 install holds after the fixups: source LEGACY, no publicId / buyerKey, paidAt = updatedAt, gatewayAmount = totalPrice
        w.orders.add(
            MarketOrder(playerUsername = "old", totalPrice = 15000, currency = "EUR", paymentLabel = "Card", status = OrderStatus.COMPLETED, source = OrderSource.LEGACY, gatewayAmount = 15000, paidAt = updated, createdAt = at(2026, 4, 1), updatedAt = updated),
            pool
        )
        w.orders.add(
            MarketOrder(playerUsername = "old2", totalPrice = 9000, currency = "EUR", status = OrderStatus.PENDING, source = OrderSource.LEGACY, gatewayAmount = 9000, createdAt = at(2026, 4, 1), updatedAt = at(2026, 4, 1)),
            pool
        )

        val body = stats()

        assertEquals(1L, body.block("total").getLong("count"))
        assertEquals(150.0, body.block("total").getDouble("revenue"), 0.001)
        assertEquals(150.0, body.block("weekly").getDouble("revenue"), 0.001, "dated by paidAt = updatedAt, not by the creation in April")
    }

    @Test
    fun `from and to bound the total, the payment methods, the currencies and the top products but not the rolling windows`(): Unit = runBlocking {
        seedWeek()

        val body = stats(from = at(2026, 10, 1, 0), to = at(2026, 10, 7, 0))

        // C (1 Oct) and B (6 Oct): A is on the 7th, at or after `to`
        assertEquals(2L, body.block("total").getLong("count"))
        assertEquals(230.0, body.block("total").getDouble("revenue"), 0.001)
        assertEquals(3L, body.block("weekly").getLong("count"), "the weekly block is a rolling window")
        assertEquals(setOf("Card"), body.getJsonObject("charts").getJsonObject("paymentMethods").getJsonArray("labels").map { it.toString() }.toSet())
        assertEquals(listOf(2L), body.getJsonObject("charts").getJsonObject("paymentMethods").getJsonArray("values").map { (it as Number).toLong() })
    }

    @Test
    fun `weekly and monthly charts are zero filled ISO weeks and calendar months ending now`(): Unit = runBlocking {
        seedWeek()

        val body = stats()
        val weeks = body.getJsonObject("charts").getJsonObject("weeklyRevenue")
        val months = body.getJsonObject("charts").getJsonObject("monthlyRevenue")

        assertEquals(8, weeks.getJsonArray("labels").size())
        assertEquals("202641", weeks.getJsonArray("labels").getString(7))
        assertEquals(130.0, body.doubles("weeklyRevenue")[7], 0.001, "A and B in the week of Monday 5 October")
        assertEquals(280.0, body.doubles("weeklyRevenue")[6], 0.001, "C and D in the week of Monday 28 September")

        assertEquals(listOf("2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10"), months.getJsonArray("labels").map { it.toString() })
        assertEquals(listOf(0.0, 0.0, 0.0, 100.0, 80.0, 330.0), body.doubles("monthlyRevenue"))
    }

    @Test
    fun `revenue is converted per order with the frozen rate, else the stats or the sales currency rule`(): Unit = runBlocking {
        // stats currency TRY, sales currency EUR with a view rate of 40 TRY per EUR
        w.configure { config(stats = CurrencyType.TRY, sales = CurrencyType.EUR, rate = 40.0) }

        order(at(2026, 10, 7, 9), 10000, currency = "EUR", rate = 35.0)  // frozen: 100 EUR x 35 = 3500.00 TRY
        order(at(2026, 10, 7, 9), 10000, currency = "EUR")               // no frozen rate: the sales currency uses the view rate 40 = 4000.00
        order(at(2026, 10, 7, 9), 10000, currency = "TRY")               // the stats currency: 1 = 100.00
        order(at(2026, 10, 7, 9), 10000, currency = "USD")               // neither: 1 = 100.00

        assertEquals(3500.0 + 4000.0 + 100.0 + 100.0, stats().block("weekly").getDouble("revenue"), 0.001)
    }

    @Test
    fun `credits paid value is no revenue, a mixed order counts its gateway part only`(): Unit = runBlocking {
        order(at(2026, 10, 7, 9), 4000, credit = 6000)
        order(at(2026, 10, 7, 9), 0, credit = 5000, label = "Credits") // paid entirely with credits

        val body = stats()

        assertEquals(2L, body.block("weekly").getLong("count"), "both are sales")
        assertEquals(40.0, body.block("weekly").getDouble("revenue"), 0.001)
        assertEquals(setOf("Card", "Credits"), body.getJsonObject("charts").getJsonObject("paymentMethods").getJsonArray("labels").map { it.toString() }.toSet())
    }

    @Test
    fun `top products group by product id with the name of the newest line and count the gateway share net of refunds`(): Unit = runBlocking {
        val vip = w.fixtures.product(slug = "vip", name = "VIP")
        val kit = w.fixtures.product(slug = "kit", name = "Kit")
        val o1 = order(at(2026, 10, 7, 9), 10000)
        val o2 = order(at(2026, 10, 7, 10), 5000, credit = 5000) // half paid with credits: a line of 10000 earns 5000 of gateway money
        val o3 = order(at(2026, 10, 7, 11), 3000, test = true)

        // the product was renamed between the two orders
        w.orderItems.add(MarketOrderItem(orderId = o1, productId = vip.id, productName = "VIP (old name)", quantity = 1, lineTotal = 10000, refundedAmount = 2000, kind = OrderItemKind.PRODUCT), pool)
        w.orderItems.add(MarketOrderItem(orderId = o2, productId = vip.id, productName = "VIP", quantity = 1, lineTotal = 10000, kind = OrderItemKind.PRODUCT), pool)
        w.orderItems.add(MarketOrderItem(orderId = o2, productId = kit.id, productName = "Kit", quantity = 1, lineTotal = 100, kind = OrderItemKind.BUNDLE), pool)
        // a bundle child and a credit top-up are no products, a test order is no sale
        w.orderItems.add(MarketOrderItem(orderId = o2, productId = kit.id, productName = "Kit child", quantity = 1, lineTotal = 99999, kind = OrderItemKind.BUNDLE_CHILD), pool)
        w.orderItems.add(MarketOrderItem(orderId = o2, productId = null, productName = "Credits", quantity = 1, lineTotal = 99999, kind = OrderItemKind.CREDIT_TOPUP), pool)
        w.orderItems.add(MarketOrderItem(orderId = o3, productId = kit.id, productName = "Kit", quantity = 1, lineTotal = 99999, kind = OrderItemKind.PRODUCT), pool)

        val top = service.topProductsFor(null, null, 5, pool)

        // VIP: (10000 - 2000) x 10000/10000 + 10000 x 5000/10000 = 8000 + 5000 = 13000 => 130.00, listed under the newest name; Kit: 100 x 0.5 = 0.50
        assertEquals(listOf("VIP", "Kit"), top.map { it.first })
        assertEquals(130.0, top[0].second, 0.001)
        assertEquals(0.5, top[1].second, 0.001)
        assertEquals(1, service.topProductsFor(null, null, 1, pool).size)
    }

    @Test
    fun `the additive keys count successful refunds, active non-test subscriptions and revenue per currency`(): Unit = runBlocking {
        val a = order(at(2026, 10, 7, 9), 10000)
        val t = order(at(2026, 10, 7, 9), 5000, test = true)
        order(at(2026, 10, 7, 9), 20000, currency = "USD", rate = 0.9)

        fun refund(orderId: Long, status: RefundStatus, gateway: Long, completedAt: Long?) = MarketRefund(
            orderId = orderId, status = status, idempotencyKey = "k${++seq}", amount = gateway, gatewayAmount = gateway, currency = "EUR", completedAt = completedAt,
            createdAt = 1000, updatedAt = 1000
        )

        w.refunds.add(refund(a, RefundStatus.SUCCEEDED, 2500, at(2026, 10, 7, 10)), pool)
        w.refunds.add(refund(a, RefundStatus.FAILED, 9999, null), pool)
        w.refunds.add(refund(t, RefundStatus.SUCCEEDED, 5000, at(2026, 10, 7, 10)), pool)

        fun subscription(status: SubscriptionStatus, test: Boolean) = MarketSubscription(
            userId = 1, playerUsername = "s${++seq}", ownerKey = "u:1:$seq", productId = 1, variantId = 1, productName = "VIP", initialOrderId = a, providerId = "fake",
            mode = SubscriptionMode.MERCHANT, status = status, price = 1000, currency = "EUR", testMode = test, createdAt = 1000, updatedAt = 1000
        )

        w.subscriptions.add(subscription(SubscriptionStatus.ACTIVE, false), pool)
        w.subscriptions.add(subscription(SubscriptionStatus.PAST_DUE, false), pool)
        w.subscriptions.add(subscription(SubscriptionStatus.CANCELLED, false), pool)
        w.subscriptions.add(subscription(SubscriptionStatus.ACTIVE, true), pool)

        val body = stats()
        val refunds = body.block("refunds")
        val currencies = body.getJsonObject("charts").getJsonObject("currencies")

        assertEquals(1L, refunds.getLong("count"), "only the succeeded refund of a real order")
        assertEquals(25.0, refunds.getDouble("amount"), 0.001)
        assertEquals(2L, body.getJsonObject("summary").getLong("activeSubscriptions"))
        assertEquals(listOf("USD", "EUR"), currencies.getJsonArray("labels").map { it.toString() }, "largest first, unconverted")
        assertEquals(listOf(200.0, 100.0), currencies.getJsonArray("values").map { (it as Number).toDouble() })
        assertEquals("EUR", body.getString("statsCurrency"))
        assertEquals("€", body.getString("statsCurrencySymbol"))
    }

    @Test
    fun `an empty store answers zeros in every block and chart`(): Unit = runBlocking {
        val body = stats()

        for (name in listOf("weekly", "monthly", "total")) {
            assertEquals(0L, body.block(name).getLong("count"))
            assertEquals(0.0, body.block(name).getDouble("revenue"), 0.0)
            assertEquals(0.0, body.block(name).getDouble("trend"), 0.0)
        }

        assertEquals(0L, body.block("refunds").getLong("count"))
        assertEquals(0L, body.getJsonObject("summary").getLong("activeSubscriptions"))
        assertTrue(body.doubles("weeklyRevenue").all { it == 0.0 })
        assertEquals(JsonArray(), body.getJsonObject("charts").getJsonObject("topProducts").getJsonArray("labels"))
    }
}
