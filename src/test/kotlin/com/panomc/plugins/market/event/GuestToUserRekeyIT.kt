package com.panomc.plugins.market.event

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Guest -> account adoption on a real MariaDB (MK-153; 01 section 5.5, 11 section 5.3, 17 section 11 `GuestToUserRekeyIT`): when a guest's Minecraft name is
 * registered, `market_entitlement.ownerKey`, `market_order.recipientKey` and the redemption keys are rewritten from `g:<name>` to `u:<id>` and the limits keep
 * counting; `market_order.userId`, `buyerKey` and `recipientUserId` are set only for orders whose e-mail equals the verified e-mail of the new account. A name
 * nobody registered is not touched, the job can run any number of times, and its rounds cover every name.
 */
class GuestToUserRekeyIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private val accounts = HashMap<String, AccountFacts>()

    @BeforeEach
    fun fresh() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        accounts.clear()
    }

    private val lookup = AccountLookup { name: String, _: SqlClient -> accounts[name.lowercase(Locale.ROOT)] }

    private fun adoption() = GuestAdoption(marketDb(), { "pano_" }, { pool }, lookup)

    private var unique = 0

    private suspend fun guestOrder(name: String, email: String?, recipient: String = name, status: String = "COMPLETED"): Long =
        Fixtures.insertRaw(
            pool, "market_order",
            mapOf(
                "status" to status, "playerUsername" to name, "buyerKey" to "g:$name", "recipientKey" to "g:$recipient", "recipientUsername" to recipient, "email" to email,
                "reservationState" to if (status == "COMPLETED") "COMMITTED" else "NONE", "paidAt" to if (status == "COMPLETED") 1 else null,
                "publicId" to "GUEST${(++unique).toString().padStart(15, '0')}", "paymentMethodId" to "manual", "createdAt" to 1, "updatedAt" to 1
            )
        )

    private suspend fun entitlement(name: String, orderId: Long): Long =
        Fixtures.insertRaw(pool, "market_entitlement", mapOf("playerUsername" to name, "ownerKey" to "g:$name", "productId" to 1, "orderId" to orderId, "orderItemId" to orderId, "startsAt" to 1))

    private suspend fun redemption(name: String, orderId: Long, refId: Long): Long =
        Fixtures.insertRaw(
            pool, "market_redemption",
            mapOf("kind" to "COUPON", "refId" to refId, "orderId" to orderId, "buyerKey" to "g:$name", "recipientKey" to "g:$name", "currency" to "EUR", "state" to "RELEASED")
        )

    private suspend fun value(table: String, id: Long, column: String): Any? = sql("SELECT `$column` AS v FROM `pano_$table` WHERE `id` = ?", id).single().getValue("v")

    @Test
    fun `registering the name rewrites the keys of goods, gifts and limits, and adopts only the orders with the verified e-mail of the account`(): Unit = runBlocking {
        val own = guestOrder("steve", "Steve@Example.com")
        val other = guestOrder("steve", "someone.else@example.com")
        val gift = guestOrder("alex", "alex@example.com", recipient = "steve")
        val goods = entitlement("steve", own)
        val used = redemption("steve", own, 1)

        accounts["steve"] = AccountFacts(7, "Steve", "steve@example.com", emailVerified = true)

        assertEquals(1, adoption().run())

        // the name owns the goods and the limits follow it
        assertEquals("u:7", value("market_entitlement", goods, "ownerKey"))
        assertEquals("u:7", value("market_redemption", used, "buyerKey"))
        assertEquals("u:7", value("market_redemption", used, "recipientKey"))
        assertEquals("u:7", value("market_order", own, "recipientKey"))
        assertEquals("u:7", value("market_order", other, "recipientKey"))
        assertEquals("u:7", value("market_order", gift, "recipientKey"))

        // the order of the same e-mail (any case) belongs to the account
        assertEquals(7L, value("market_order", own, "userId"))
        assertEquals("u:7", value("market_order", own, "buyerKey"))
        assertEquals(7L, value("market_order", own, "recipientUserId"))

        // the order of somebody else's e-mail stays a guest order: no account, the old buyer key, no recipient user
        assertNull(value("market_order", other, "userId"))
        assertEquals("g:steve", value("market_order", other, "buyerKey"))
        assertNull(value("market_order", other, "recipientUserId"))

        // a gift to the name paid by another e-mail: the recipient key follows the name, the order is the payer's
        assertEquals("g:alex", value("market_order", gift, "buyerKey"))
        assertNull(value("market_order", gift, "userId"))
        assertNull(value("market_order", gift, "recipientUserId"))
    }

    @Test
    fun `an unverified e-mail adopts no order, only the keys of the name move`(): Unit = runBlocking {
        val order = guestOrder("kim", "kim@example.com")
        val goods = entitlement("kim", order)

        accounts["kim"] = AccountFacts(8, "Kim", "kim@example.com", emailVerified = false)

        adoption().run()

        assertEquals("u:8", value("market_entitlement", goods, "ownerKey"))
        assertEquals("u:8", value("market_order", order, "recipientKey"))
        assertNull(value("market_order", order, "userId"), "registering a name proves nothing about the payer")
        assertEquals("g:kim", value("market_order", order, "buyerKey"))
        assertNull(value("market_order", order, "recipientUserId"))
    }

    @Test
    fun `an account without an e-mail adopts no order`(): Unit = runBlocking {
        val order = guestOrder("lee", null)

        accounts["lee"] = AccountFacts(9, "Lee", null, emailVerified = true)

        adoption().run()

        assertNull(value("market_order", order, "userId"))
        assertEquals("u:9", value("market_order", order, "recipientKey"))
    }

    @Test
    fun `a name nobody registered is not touched, and a name that differs in more than the case is not adopted`(): Unit = runBlocking {
        val order = guestOrder("ghost", "ghost@example.com")
        val goods = entitlement("ghost", order)
        val spoof = guestOrder("mallory", "mallory@example.com")

        // the platform finds a different account for the typed name (a fuzzy lookup): nothing may move
        accounts["mallory"] = AccountFacts(3, "Mallory2", "mallory@example.com", emailVerified = true)

        assertEquals(0, adoption().run())

        assertEquals("g:ghost", value("market_entitlement", goods, "ownerKey"))
        assertEquals("g:ghost", value("market_order", order, "recipientKey"))
        assertEquals("g:mallory", value("market_order", spoof, "recipientKey"))
    }

    @Test
    fun `the job is idempotent and a later round adopts a name that registered after the first one`(): Unit = runBlocking {
        val first = guestOrder("pat", "pat@example.com")
        val second = guestOrder("quinn", "quinn@example.com")
        val job = adoption()

        accounts["pat"] = AccountFacts(11, "Pat", "pat@example.com", emailVerified = true)

        assertEquals(1, job.run())
        assertEquals(0, job.run(), "nothing left for pat")
        assertEquals(11L, value("market_order", first, "userId"))
        assertNull(value("market_order", second, "userId"))

        accounts["quinn"] = AccountFacts(12, "Quinn", "quinn@example.com", emailVerified = true)

        assertEquals(1, job.run())
        assertEquals(12L, value("market_order", second, "userId"))
        assertEquals(11L, value("market_order", first, "userId"), "an earlier adoption stays")
    }

    @Test
    fun `rounds of one name cover every name, so names nobody registered cannot starve the others`(): Unit = runBlocking {
        for (n in listOf("aaa", "bbb", "ccc")) guestOrder(n, "$n@example.com")

        accounts["ccc"] = AccountFacts(21, "Ccc", "ccc@example.com", emailVerified = true)

        val job = adoption()
        var adopted = 0

        repeat(4) { adopted += job.run(limit = 1) }

        assertEquals(1, adopted)
        assertEquals(1L, count("market_order", "`userId` = 21"))
        assertEquals(2L, count("market_order", "`buyerKey` LIKE 'g:%'"))
    }
}
