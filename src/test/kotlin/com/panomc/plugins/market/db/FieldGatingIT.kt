package com.panomc.plugins.market.db

import com.panomc.platform.error.NoPermission
import com.panomc.plugins.market.db.impl.MarketComparisonDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderDaoImpl
import com.panomc.plugins.market.db.model.MarketComparison
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.permission.FieldGating
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Field-level gating of the panel read endpoints (11 section 14.5, 19.10 items 4 to 8; MK-160): below the PII tier (`OM` or `PAY`) the e-mail of an
 * order is masked, billing data, address, client IP, user agent and gift message are `null`, and a search never matches the e-mail (no oracle).
 * The projection is [FieldGating], the search predicate is the order DAO's, both are driven here against rows written the way checkout writes them;
 * that the two panel order routes call them is asserted on their source (the host is outside this tier, E2E-10 proves it over HTTP).
 */
class FieldGatingIT : MarketDaoITBase() {
    /** The rows are written raw and the cross-table invariants do not apply to a projection test. */
    override suspend fun assertInvariants() {}

    private val orders = MarketOrderDaoImpl()

    private val comparisons = MarketComparisonDaoImpl()

    private var sequence = 0

    private suspend fun order(email: String?, username: String = "Steve", billing: String? = "{\"type\":\"COMPANY\",\"name\":\"Acme\"}", address: String? = "{\"city\":\"Izmir\",\"country\":\"TR\"}"): MarketOrder {
        val n = ++sequence
        val id = orders.add(
            MarketOrder(
                playerUsername = username, totalPrice = 1000, publicId = "FGATING" + n.toString().padStart(13, '0'), buyerKey = "g:${username.lowercase()}$n",
                email = email, clientIp = "203.0.113.57", userAgent = "JUnit/1", giftMessage = "enjoy", billingInfo = billing, shippingAddress = address,
                createdAt = 1000L + n, updatedAt = 1000L + n
            ),
            pool
        )

        return orders.getById(id, pool)!!
    }

    @Test
    fun `without OM or PAY the e-mail is masked and billing, address, IP, agent and gift message are null`(): Unit = runBlocking {
        val stored = order("john@example.com")
        val view = FieldGating.orderPii(stored, pii = false)

        assertEquals("j***@e***.com", view["email"])
        for (key in listOf("billingInfo", "shippingAddress", "clientIp", "userAgent", "giftMessage")) assertNull(view[key], key)
        assertEquals(listOf("email", "billingInfo", "shippingAddress", "clientIp", "userAgent", "giftMessage"), view.keys.toList())
        assertFalse(view.values.any { it.toString().contains("203.0.113") || it.toString().contains("Acme") || it.toString().contains("Izmir") })
    }

    @Test
    fun `with OM or PAY every field comes back as stored, billing and address as objects`(): Unit = runBlocking {
        val stored = order("john@example.com")
        val view = FieldGating.orderPii(stored, pii = true)

        assertEquals("john@example.com", view["email"])
        assertEquals(JsonObject("{\"type\":\"COMPANY\",\"name\":\"Acme\"}"), view["billingInfo"])
        assertEquals(JsonObject("{\"city\":\"Izmir\",\"country\":\"TR\"}"), view["shippingAddress"])
        assertEquals("203.0.113.57", view["clientIp"])
        assertEquals("JUnit/1", view["userAgent"])
        assertEquals("enjoy", view["giftMessage"])
    }

    @Test
    fun `an order without personal data projects to nulls in both tiers and a broken JSON text never leaks as a string`(): Unit = runBlocking {
        val empty = order(email = null, billing = null, address = "not json")

        for (pii in listOf(false, true)) {
            val view = FieldGating.orderPii(empty, pii)

            assertNull(view["email"], "email pii=$pii")
            assertNull(view["billingInfo"])
            assertNull(view["shippingAddress"], "an unparseable address is not echoed (pii=$pii)")
        }
    }

    @Test
    fun `the e-mail predicate of the order search exists only for the PII tier`(): Unit = runBlocking {
        val john = order("john@example.com", username = "Steve")
        order("mary@example.org", username = "Alex")

        // below the tier a search for an address finds nothing, as if no order carried it
        assertEquals(0L, orders.count("john@example", null, pool, searchEmail = false))
        assertTrue(orders.getAllPaged(1, "john@example", null, pool, 10, false).isEmpty())

        // with the tier the same search finds exactly that order
        assertEquals(1L, orders.count("john@example", null, pool, searchEmail = true))
        assertEquals(listOf(john.id), orders.getAllPaged(1, "john@example", null, pool, 10, true).map { it.id })

        // the other search keys work in both tiers
        for (tier in listOf(false, true)) {
            assertEquals(1L, orders.count("Steve", null, pool, searchEmail = tier), "player name, tier=$tier")
            assertEquals(1L, orders.count(john.id.toString(), null, pool, searchEmail = tier), "order id, tier=$tier")
        }

        // a half-known address is no oracle either: the same empty answer for an existing and an unknown address
        assertEquals(orders.count("zzz@nowhere", null, pool, searchEmail = false), orders.count("mary@example", null, pool, searchEmail = false))
    }

    @Test
    fun `the order list honours pageSize and the comparison list too`(): Unit = runBlocking {
        repeat(7) { order("p$it@example.com", username = "Pager$it") }

        assertEquals(7L, orders.count(null, null, pool))
        assertEquals(3, orders.getAllPaged(1, null, null, pool, 3, false).size)
        assertEquals(3, orders.getAllPaged(2, null, null, pool, 3, false).size)
        assertEquals(1, orders.getAllPaged(3, null, null, pool, 3, false).size)
        assertEquals(7, orders.getAllPaged(1, null, null, pool).size, "the default page size is 10")
        assertEquals(
            orders.getAllPaged(1, null, null, pool, 7, false).map { it.id }.drop(3).take(3),
            orders.getAllPaged(2, null, null, pool, 3, false).map { it.id }
        )

        repeat(5) { comparisons.add(MarketComparison(name = "Cmp $it", createdAt = it.toLong(), updatedAt = it.toLong()), pool) }

        assertEquals(2, comparisons.getAllPaged(1, null, null, pool, 2).size)
        assertEquals(1, comparisons.getAllPaged(3, null, null, pool, 2).size)
        assertEquals(5, comparisons.getAllPaged(1, null, null, pool).size)
    }

    @Test
    fun `payment event bodies need the raw tier`() {
        val without = FieldGating.eventRaw("{\"a\":1}", "{\"h\":\"v\"}", "https://hook.invalid/x", raw = false)

        assertEquals(listOf("body", "headers", "url"), without.keys.toList())
        assertTrue(without.values.all { it == null })

        val with = FieldGating.eventRaw("{\"a\":1}", "{\"h\":\"v\"}", "https://hook.invalid/x", raw = true)

        assertEquals("{\"a\":1}", with["body"])
        assertEquals("https://hook.invalid/x", with["url"])
    }

    @Test
    fun `the CSV export refuses a PII column below the tier`() {
        assertThrows(NoPermission::class.java) { FieldGating.requireExportColumns(listOf("orderId", "email"), pii = false) }
        assertThrows(NoPermission::class.java) { FieldGating.requireExportColumns(listOf("country"), pii = false) }

        FieldGating.requireExportColumns(listOf("orderId", "publicId", "lineTotal", "status"), pii = false)
        FieldGating.requireExportColumns(listOf("orderId", "email", "country"), pii = true)
    }

    @Test
    fun `the PII tier is OM or PAY and the raw tier is SET`() {
        assertEquals(setOf("OM", "PAY"), FieldGating.PII_NODES.map { it.shortName }.toSet())
        assertEquals(setOf("SET"), FieldGating.RAW_NODES.map { it.shortName }.toSet())
    }

    @Test
    fun `the panel order routes project through FieldGating and search with the tier`() {
        val root = "src/main/kotlin/com/panomc/plugins/market/routes/panel/order/"
        val list = File(root + "PanelGetOrdersAPI.kt").readText()
        val detail = File(root + "PanelGetOrderAPI.kt").readText()

        assertTrue(list.contains("FieldGating.piiTier(context)") && list.contains("FieldGating.email(order.email, pii)"), "list rows mask the e-mail")
        assertTrue(list.contains("getAllPaged(page, search, status, sqlClient, window.pageSize, pii)"), "the e-mail predicate only with the tier")
        assertTrue(list.contains("marketOrderDao.count(search, status, sqlClient, pii)"), "the count uses the same predicate as the rows")
        assertTrue(detail.contains("FieldGating.piiTier(context)") && detail.contains("FieldGating.orderPii(order, pii)"), "the detail goes through the projection")
    }
}
