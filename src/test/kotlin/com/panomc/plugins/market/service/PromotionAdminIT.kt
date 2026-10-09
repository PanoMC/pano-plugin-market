package com.panomc.plugins.market.service

import com.panomc.platform.model.PageRequest
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketRedemption
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.error.CodeAlreadyExists
import com.panomc.plugins.market.error.InvalidCreditAmount
import com.panomc.plugins.market.error.InvalidGiftCode
import com.panomc.plugins.market.routes.panel.discount.Promotion
import com.panomc.plugins.market.routes.panel.discount.PromotionAdminService
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.support.ErrorBodies

/**
 * The discounts panel on a real MariaDB (MK-113; 04 section 6, 01 sections 3 and 13): create, partial update, soft and hard delete and the lists
 * of discounts, coupons, creator codes and gifts. The contract tests of the slice: a PUT never writes `usedCount`, `earnings` or `paidOut`, a missing
 * row is 404, a row with redemptions is soft-deleted and a row without is removed, and a code is unique across the three coded tables even when the
 * same code is created in all three at once (R-28 twin).
 */
class PromotionAdminIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var admin: PromotionAdminService
    private lateinit var redemptions: RedemptionService
    private var vipId: Long? = null
    private var skipInvariants = false

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        vipId = null
        skipInvariants = false

        val locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)

        redemptions = RedemptionService(w.clock, locks, w.redemptions)
        admin = PromotionAdminService(
            db = w.db, pool = { pool }, client = { pool }, prefix = { "pano_" }, clock = w.clock, redemptions = redemptions,
            users = object : UserDirectory {
                override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

                override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

                override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

                override suspend fun hasPermission(userId: Long, node: String): Boolean = false
            },
            products = w.products, bundleItems = w.bundleItems, categories = w.categories
        )
    }

    private val fx get() = w.fixtures

    override suspend fun assertInvariants() {
        if (!skipInvariants) super.assertInvariants()
    }

    private suspend fun vip(): Long = vipId ?: fx.product("vip", price = 1000).id.also { vipId = it }

    private fun body(json: String) = JsonObject(json)

    private fun window(page: Int = 1, size: Int = 10) = PageRequest(page, size)

    private suspend fun row(promotion: Promotion, id: Long): Map<String, Any?> {
        val r = sql("SELECT * FROM `pano_${promotion.table}` WHERE `id` = ?", id).single()

        return (0 until r.size()).associate { r.getColumnName(it) to r.getValue(it) }
    }

    private suspend fun fieldErrors(block: suspend () -> Any?): Map<String, String> {
        try {
            block()
        } catch (e: BadRequest) {
            val errors = ErrorBodies.details(e).getJsonObject("fieldErrors") ?: error("a 400 without fieldErrors: ${e.encode()}")

            return errors.map.mapValues { it.value.toString() }
        }

        error("expected a 400 with fieldErrors")
    }

    private suspend fun <T : Error> fails(type: Class<T>, block: suspend () -> Any?): T {
        try {
            block()
        } catch (e: Throwable) {
            if (type.isInstance(e)) return type.cast(e)

            throw AssertionError("expected ${type.simpleName}, got $e", e)
        }

        error("expected ${type.simpleName}, nothing was thrown")
    }

    private suspend fun makeCoupon(code: String = "SUMMER10"): Long =
        admin.create(Promotion.COUPON, body("""{"name":"Summer","code":"$code","discount":10,"unit":"PERCENT","redeemLimit":5,"customerRedeemLimit":1}""")).id

    private suspend fun makeCreator(code: String = "STREAMER1"): Long =
        admin.create(Promotion.CREATOR_CODE, body("""{"creator":"streamer","code":"$code","discount":5,"commissionPercent":10}""")).id

    private suspend fun makeDiscount(): Long =
        admin.create(Promotion.DISCOUNT, body("""{"name":"Weekend","value":15,"unit":"PERCENT","usageLimit":10}""")).id

    private suspend fun makeGift(code: String = "GIFT-CODE-1", productId: Long? = null): Long {
        val product = productId ?: vip()

        return admin.create(Promotion.GIFT, body("""{"name":"Welcome","code":"$code","type":"PRODUCT","productId":$product,"redeemLimit":3,"customerRedeemLimit":1}""")).id
    }

    /** A redemption row of a code (a real order is not needed here) and the counter it makes true (I6); a `RELEASED` row counts nothing. */
    private suspend fun redemptionRow(kind: RedemptionKind, refId: Long, orderId: Long, state: RedemptionState = RedemptionState.APPLIED) {
        w.redemptions.add(
            MarketRedemption(kind = kind, refId = refId, code = "X", orderId = orderId, buyerKey = "u:1", amount = 250, currency = "EUR", state = state, createdAt = w.clock.now() + orderId),
            pool
        )

        if (state != RedemptionState.RELEASED) {
            val table = Promotion.entries.first { it.kind == kind }.table

            sql("UPDATE `pano_$table` SET `usedCount` = `usedCount` + 1 WHERE `id` = ?", refId)
        }
    }

    // ============================================================================================ create + list

    @Test
    fun `a coupon is created normalised, with zero counters, and listed`(): Unit = runBlocking {
        val id = makeCoupon("summer10")
        val stored = row(Promotion.COUPON, id)

        assertEquals("SUMMER10", stored["code"], "trimmed and upper-cased like a lookup does")
        assertEquals(1000L, stored["discount"], "10 percent as basis points")
        assertEquals(0, stored["usedCount"])
        assertNull(stored["deletedAt"])
        assertEquals(5, stored["redeemLimit"])
        assertEquals(1, stored["customerRedeemLimit"])

        val page = admin.list(Promotion.COUPON, window(), null, null)

        assertEquals(1, page.total)
        assertEquals(10.0, page.rows.single()["discount"])
        assertEquals("SUMMER10", page.rows.single()["code"])
        assertEquals(0, page.rows.single()["usedCount"])
    }

    @Test
    fun `every promotion kind round-trips its fields`(): Unit = runBlocking {
        val p1 = fx.product("a", price = 100)
        val category = fx.category("Ranks")
        val discount = admin.create(
            Promotion.DISCOUNT,
            body("""{"name":"Sale","value":20,"unit":"PERCENT","scope":"PRODUCTS","productIds":[${p1.id}],"categoryIds":[${category.id}],"minPaymentAmount":5.5,"startDate":1000,"expiryDate":2000,"usageLimit":7,"showBadge":false,"status":"INACTIVE"}""")
        ).id
        val stored = row(Promotion.DISCOUNT, discount)

        assertEquals("Sale", stored["name"])
        assertEquals(2000L, stored["value"])
        assertEquals("PRODUCTS", stored["scope"])
        assertEquals(550L, stored["minPaymentAmount"])
        assertEquals(7, stored["usageLimit"])
        assertEquals("INACTIVE", stored["status"])
        assertEquals(false, (stored["showBadge"] as Number).toInt() != 0)

        val listed = admin.list(Promotion.DISCOUNT, window(), null, null).rows.single()

        assertEquals(listOf(p1.id), listed["productIds"])
        assertEquals(listOf("a"), listed["products"], "the resolved names next to the ids")
        assertEquals(false, listed["showBadge"])

        val coupon = admin.create(
            Promotion.COUPON,
            body("""{"name":"C","code":"CATS","discount":3.25,"unit":"FIXED","scope":"SELECTED","productIds":[${p1.id}],"categoryIds":[${category.id}],"minPaymentAmount":10}""")
        ).id

        assertEquals(325L, row(Promotion.COUPON, coupon)["discount"])
        assertEquals("[${category.id}]", row(Promotion.COUPON, coupon)["categoryIds"])
    }

    @Test
    fun `the list skips soft-deleted rows, filters by search and status and pages`(): Unit = runBlocking {
        for (i in 1..5) makeCoupon("CODE-$i")

        admin.update(Promotion.COUPON, 2, body("""{"status":"INACTIVE"}"""))

        assertEquals(5, admin.list(Promotion.COUPON, window(), null, null).total)
        assertEquals(listOf("CODE-2"), admin.list(Promotion.COUPON, window(), null, "INACTIVE").rows.map { it["code"] })
        assertEquals(listOf("CODE-3"), admin.list(Promotion.COUPON, window(), "code-3", null).rows.map { it["code"] })
        assertEquals(0, admin.list(Promotion.COUPON, window(), "100%", null).total, "a LIKE wildcard in the search is text")

        val firstPage = admin.list(Promotion.COUPON, window(1, 2), null, null)

        assertEquals(listOf("CODE-5", "CODE-4"), firstPage.rows.map { it["code"] }, "newest first")
        assertEquals(5, firstPage.total)

        redemptionRow(RedemptionKind.COUPON, 5, 90)
        admin.delete(Promotion.COUPON, 5)

        assertEquals(4, admin.list(Promotion.COUPON, window(), null, null).total, "a soft-deleted coupon is not listed")
    }

    // =================================================================================== counters are never written

    @Test
    fun `PUT never writes usedCount, earnings or paidOut`(): Unit = runBlocking {
        val coupon = makeCoupon()
        val creator = makeCreator()
        val discount = makeDiscount()
        val gift = makeGift()

        // the earnings and payouts have no ledger rows here (I14), so this test checks the invariants itself at the end
        skipInvariants = true

        Fixtures.setColumns(pool, "market_creator_code", creator, mapOf("earnings" to 900, "paidOut" to 300))

        // the redemption rows that make the counters true (I6), none of them touched by the updates
        for (n in 1..7) redemptionRow(RedemptionKind.COUPON, coupon, 100L + n)
        for (n in 1..4) redemptionRow(RedemptionKind.CREATOR_CODE, creator, 200L + n)
        for (n in 1..3) redemptionRow(RedemptionKind.DISCOUNT, discount, 300L + n)
        for (n in 1..2) redemptionRow(RedemptionKind.GIFT, gift, 400L + n)

        val evil = """"usedCount":0,"earnings":0,"paidOut":0,"id":999,"createdAt":1,"deletedAt":5"""

        admin.update(Promotion.COUPON, coupon, body("""{"name":"Renamed","redeemLimit":50,$evil}"""))
        admin.update(Promotion.CREATOR_CODE, creator, body("""{"commissionPercent":20,$evil}"""))
        admin.update(Promotion.DISCOUNT, discount, body("""{"name":"Renamed","usageLimit":99,$evil}"""))
        admin.update(Promotion.GIFT, gift, body("""{"name":"Renamed","redeemLimit":9,$evil}"""))

        assertEquals(7, row(Promotion.COUPON, coupon)["usedCount"])
        assertEquals(4, row(Promotion.CREATOR_CODE, creator)["usedCount"])
        assertEquals(900L, row(Promotion.CREATOR_CODE, creator)["earnings"])
        assertEquals(300L, row(Promotion.CREATOR_CODE, creator)["paidOut"])
        assertEquals(3, row(Promotion.DISCOUNT, discount)["usedCount"])
        assertEquals(2, row(Promotion.GIFT, gift)["usedCount"])

        assertEquals("Renamed", row(Promotion.COUPON, coupon)["name"])
        assertEquals(50, row(Promotion.COUPON, coupon)["redeemLimit"])
        assertEquals(2000L, row(Promotion.CREATOR_CODE, creator)["commissionPercent"])
        assertEquals(99, row(Promotion.DISCOUNT, discount)["usageLimit"])
        assertEquals(9, row(Promotion.GIFT, gift)["redeemLimit"])

        for ((promotion, id) in listOf(Promotion.COUPON to coupon, Promotion.CREATOR_CODE to creator, Promotion.DISCOUNT to discount, Promotion.GIFT to gift)) {
            assertEquals(id, row(promotion, id)["id"], "the id is not a form field")
            assertNull(row(promotion, id)["deletedAt"], "neither is the soft delete mark")
        }

        // a create that names a counter starts at zero
        val created = admin.create(Promotion.COUPON, body("""{"name":"Cheat","code":"CHEAT-CODE","discount":10,"usedCount":99}""")).id

        assertEquals(0, row(Promotion.COUPON, created)["usedCount"])

        Fixtures.setColumns(pool, "market_creator_code", creator, mapOf("earnings" to 0, "paidOut" to 0))
        skipInvariants = false
    }

    @Test
    fun `an update is partial, keys that were not sent keep their value`(): Unit = runBlocking {
        val id = makeCoupon()

        admin.update(Promotion.COUPON, id, body("""{"status":"INACTIVE"}"""))

        val stored = row(Promotion.COUPON, id)

        assertEquals("INACTIVE", stored["status"])
        assertEquals("Summer", stored["name"])
        assertEquals("SUMMER10", stored["code"])
        assertEquals(1000L, stored["discount"])
        assertEquals(5, stored["redeemLimit"])
        assertEquals(1, stored["customerRedeemLimit"])

        admin.update(Promotion.COUPON, id, body("""{"redeemLimit":null}"""))

        assertNull(row(Promotion.COUPON, id)["redeemLimit"], "an explicit null is unlimited")
        assertEquals("Summer", row(Promotion.COUPON, id)["name"])
    }

    // =============================================================================================== validation

    @Test
    fun `values outside their range are field errors and nothing is written`(): Unit = runBlocking {
        val bad = mapOf(
            """{"name":"x","code":"OVER-100","discount":150,"unit":"PERCENT"}""" to ("discount" to "OUT_OF_RANGE"),
            """{"name":"x","code":"NEG-VALUE","discount":-1}""" to ("discount" to "OUT_OF_RANGE"),
            """{"name":"x","code":"THREE-DEC","discount":1.234}""" to ("discount" to "INVALID"),
            """{"name":"x","code":"NEG-LIMIT","discount":1,"redeemLimit":-1}""" to ("redeemLimit" to "OUT_OF_RANGE"),
            """{"name":"x","code":"FRAC-LIMIT","discount":1,"customerRedeemLimit":1.5}""" to ("customerRedeemLimit" to "INVALID"),
            """{"name":"x","code":"WINDOW-ODD","discount":1,"startDate":2000,"expiryDate":1000}""" to ("expiryDate" to "BEFORE_START"),
            """{"name":"x","code":"has space","discount":1}""" to ("code" to "INVALID_FORMAT"),
            """{"name":"x","code":"","discount":1}""" to ("code" to "REQUIRED"),
            """{"name":"x","discount":1}""" to ("code" to "REQUIRED"),
            """{"code":"NO-NAME-1","discount":1}""" to ("name" to "REQUIRED"),
            """{"name":"x","code":"NO-VALUE-1"}""" to ("discount" to "REQUIRED"),
            """{"name":"x","code":"BAD-SCOPE","discount":1,"scope":"EVERYTHING"}""" to ("scope" to "UNKNOWN_VALUE"),
            """{"name":"x","code":"BAD-STATUS","discount":1,"status":"DELETED"}""" to ("status" to "UNKNOWN_VALUE"),
            """{"name":"x","code":"BAD-IDS-1","discount":1,"productIds":[1,"a"]}""" to ("productIds" to "INVALID"),
            """{"name":"${"n".repeat(256)}","code":"LONG-NAME","discount":1}""" to ("name" to "TOO_LONG"),
            """{"name":"x","code":"${"C".repeat(65)}","discount":1}""" to ("code" to "INVALID_FORMAT")
        )

        for ((json, expected) in bad) {
            val errors = fieldErrors { admin.create(Promotion.COUPON, body(json)) }

            assertEquals(expected.second, errors[expected.first], "$json -> $errors")
        }

        assertEquals(0, count("market_coupon"))

        // the same range checks for the other kinds
        assertEquals("OUT_OF_RANGE", fieldErrors { admin.create(Promotion.DISCOUNT, body("""{"name":"x","value":101}""")) }["value"])
        assertEquals("OUT_OF_RANGE", fieldErrors { admin.create(Promotion.DISCOUNT, body("""{"name":"x","value":1,"usageLimit":-5}""")) }["usageLimit"])
        assertEquals("OUT_OF_RANGE", fieldErrors { admin.create(Promotion.CREATOR_CODE, body("""{"creator":"c","code":"CC-CODE-1","discount":1,"commissionPercent":100.01}""")) }["commissionPercent"])
        assertEquals("REQUIRED", fieldErrors { admin.create(Promotion.CREATOR_CODE, body("""{"code":"CC-CODE-2","discount":1}""")) }["creator"])
        assertEquals(0, count("market_discount") + count("market_creator_code"))
    }

    @Test
    fun `a percent value at the edges is accepted, and a unit change re-judges the stored value`(): Unit = runBlocking {
        val zero = admin.create(Promotion.COUPON, body("""{"name":"z","code":"ZERO-PCT","discount":0}""")).id
        val full = admin.create(Promotion.COUPON, body("""{"name":"f","code":"FULL-PCT","discount":100}""")).id

        assertEquals(0L, row(Promotion.COUPON, zero)["discount"])
        assertEquals(10000L, row(Promotion.COUPON, full)["discount"])

        val fixed = admin.create(Promotion.COUPON, body("""{"name":"x","code":"FIXED-500","discount":500,"unit":"FIXED"}""")).id

        assertEquals(50000L, row(Promotion.COUPON, fixed)["discount"])
        assertEquals("OUT_OF_RANGE", fieldErrors { admin.update(Promotion.COUPON, fixed, body("""{"unit":"PERCENT"}""")) }["unit"], "500.00 is not a percentage")
    }

    @Test
    fun `an update checks the window against the stored dates`(): Unit = runBlocking {
        val id = admin.create(Promotion.COUPON, body("""{"name":"w","code":"WINDOW-UPD","discount":1,"startDate":5000,"expiryDate":9000}""")).id

        assertEquals("BEFORE_START", fieldErrors { admin.update(Promotion.COUPON, id, body("""{"expiryDate":4000}""")) }["expiryDate"])
        assertEquals("BEFORE_START", fieldErrors { admin.update(Promotion.COUPON, id, body("""{"startDate":9500}""")) }["expiryDate"])

        admin.update(Promotion.COUPON, id, body("""{"startDate":1000,"expiryDate":null}"""))

        assertEquals(1000L, row(Promotion.COUPON, id)["startDate"])
        assertNull(row(Promotion.COUPON, id)["expiryDate"])
    }

    // ============================================================================================ 404 and delete

    @Test
    fun `a missing row is 404 on update and delete, and so is a soft-deleted one`(): Unit = runBlocking {
        for (promotion in Promotion.entries) {
            val e = fails(NotFound::class.java) { admin.update(promotion, 424242, body("""{"status":"INACTIVE"}""")) }

            assertEquals(404, e.getStatusCode())
            assertEquals("NOT_FOUND", e.getErrorCode())
            assertEquals("NOT_FOUND", fails(NotFound::class.java) { admin.delete(promotion, 424242) }.getErrorCode())
            assertEquals("NOT_FOUND", fails(NotFound::class.java) { admin.redemptionList(promotion, 424242, window()) }.getErrorCode())
        }
    }

    @Test
    fun `a row without redemptions is removed, one with redemptions is soft-deleted and keeps its history`(): Unit = runBlocking {
        val fresh = makeCoupon("NEVER-USED")
        val used = makeCoupon("USED-ONE")
        val released = makeCoupon("RELEASED-ONE")

        redemptionRow(RedemptionKind.COUPON, used, 50)
        redemptionRow(RedemptionKind.COUPON, used, 51)
        redemptionRow(RedemptionKind.COUPON, released, 52, RedemptionState.RELEASED)

        assertEquals("NEVER-USED", admin.delete(Promotion.COUPON, fresh))
        assertEquals(0, count("market_coupon", "`id` = ?", fresh), "hard delete")

        assertEquals("USED-ONE", admin.delete(Promotion.COUPON, used))
        assertNotNull(row(Promotion.COUPON, used)["deletedAt"], "soft delete")
        assertEquals(2, row(Promotion.COUPON, used)["usedCount"], "the counter keeps counting what is still held")

        admin.delete(Promotion.COUPON, released)

        assertNotNull(row(Promotion.COUPON, released)["deletedAt"], "a RELEASED row is a redemption too: the history stays")

        assertEquals(0, admin.list(Promotion.COUPON, window(), null, null).total)
        assertEquals("NOT_FOUND", fails(NotFound::class.java) { admin.delete(Promotion.COUPON, used) }.getErrorCode(), "deleted twice")
        assertEquals("NOT_FOUND", fails(NotFound::class.java) { admin.update(Promotion.COUPON, used, body("""{"status":"ACTIVE"}""")) }.getErrorCode())
        assertNull(w.coupons.getByCode("USED-ONE", pool), "checkout no longer finds the code")
        assertEquals(2, admin.redemptionList(Promotion.COUPON, used, window()).total, "the history of a deleted row is still readable")
    }

    @Test
    fun `a soft-deleted code stays reserved, the other tables cannot take it`(): Unit = runBlocking {
        val id = makeCoupon("RESERVED-CODE")

        redemptionRow(RedemptionKind.COUPON, id, 60)
        admin.delete(Promotion.COUPON, id)

        fails(CodeAlreadyExists::class.java) { makeGift("RESERVED-CODE") }
        fails(CodeAlreadyExists::class.java) { makeCreator("reserved-code") }
    }

    @Test
    fun `soft delete applies to every kind`(): Unit = runBlocking {
        val ids = mapOf(Promotion.DISCOUNT to makeDiscount(), Promotion.CREATOR_CODE to makeCreator(), Promotion.GIFT to makeGift())

        for ((promotion, id) in ids) {
            redemptionRow(promotion.kind, id, 70)
            admin.delete(promotion, id)

            assertNotNull(row(promotion, id)["deletedAt"], promotion.name)
            assertEquals(0, admin.list(promotion, window(), null, null).total, promotion.name)
        }
    }

    // ============================================================================================ code uniqueness

    @Test
    fun `a code is unique across coupons, creator codes and gifts, whatever its case`(): Unit = runBlocking {
        makeCoupon("TAKEN-CODE")

        fails(CodeAlreadyExists::class.java) { makeCoupon("TAKEN-CODE") }
        fails(CodeAlreadyExists::class.java) { makeCreator("taken-code") }
        fails(CodeAlreadyExists::class.java) { makeGift("Taken-Code") }

        assertEquals(409, CodeAlreadyExists().getStatusCode())

        // an update to a taken code is refused, an update that keeps the own code is fine
        val other = makeCoupon("OTHER-CODE")

        fails(CodeAlreadyExists::class.java) { admin.update(Promotion.COUPON, other, body("""{"code":"taken-code"}""")) }
        admin.update(Promotion.COUPON, other, body("""{"code":"OTHER-CODE","name":"Same code"}"""))

        val gift = makeGift("GIFT-ONLY-1")

        fails(CodeAlreadyExists::class.java) { admin.update(Promotion.GIFT, gift, body("""{"code":"TAKEN-CODE"}""")) }
        assertEquals("GIFT-ONLY-1", row(Promotion.GIFT, gift)["code"])
    }

    @Test
    fun `R-28 twin - the same code created at once in three tables has one winner`(): Unit = runBlocking {
        val product = fx.product("vip", price = 1000)

        repeat(Race.rounds) { round ->
            val code = "RACE-CODE-$round"
            val results = Race.run(9) { i ->
                when (i % 3) {
                    0 -> admin.create(Promotion.COUPON, body("""{"name":"c","code":"$code","discount":1}"""))
                    1 -> admin.create(Promotion.CREATOR_CODE, body("""{"creator":"c","code":"$code","discount":1}"""))
                    else -> admin.create(Promotion.GIFT, body("""{"code":"$code","type":"PRODUCT","productId":${product.id}}"""))
                }
            }

            assertEquals(1, results.count { it.isSuccess }, "round $round: ${results.mapNotNull { it.exceptionOrNull() }.map { it.javaClass.simpleName }}")
            assertTrue(results.mapNotNull { it.exceptionOrNull() }.all { it is CodeAlreadyExists }, "the losers answer CODE_ALREADY_EXISTS: ${results.mapNotNull { it.exceptionOrNull() }}")

            val total = Promotion.CODED.sumOf { count(it.table, "`code` = ?", code) }

            assertEquals(1, total, "round $round: one row in the whole code space")
        }
    }

    @Test
    fun `an update to the same new code at once, from two rows, has one winner`(): Unit = runBlocking {
        val a = makeCoupon("MINE-A")
        val b = makeCreator("MINE-B")
        val results = Race.run(2) { i -> if (i == 0) admin.update(Promotion.COUPON, a, body("""{"code":"NEW-SHARED"}""")) else admin.update(Promotion.CREATOR_CODE, b, body("""{"code":"NEW-SHARED"}""")) }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, Promotion.CODED.sumOf { count(it.table, "`code` = 'NEW-SHARED'") })
    }

    // ============================================================================================== creator code

    @Test
    fun `the owner of a creator code is resolved from the creator name or given explicitly`(): Unit = runBlocking {
        val streamer = fx.user("Streamer")
        val other = fx.user("Other")

        val named = admin.create(Promotion.CREATOR_CODE, body("""{"creator":"streamer","code":"BY-NAME-1","discount":5}""")).id

        assertEquals(streamer.id, row(Promotion.CREATOR_CODE, named)["creatorUserId"])
        assertEquals("streamer", row(Promotion.CREATOR_CODE, named)["creator"], "the creator text stays the snapshot the admin typed")

        val unknown = admin.create(Promotion.CREATOR_CODE, body("""{"creator":"nobody","code":"BY-NAME-2","discount":5}""")).id

        assertNull(row(Promotion.CREATOR_CODE, unknown)["creatorUserId"], "a name nobody owns: no creator account")
        assertEquals("nobody", row(Promotion.CREATOR_CODE, unknown)["creator"])

        val explicit = admin.create(Promotion.CREATOR_CODE, body("""{"creatorUserId":${other.id},"code":"BY-ID-1","discount":5}""")).id

        assertEquals(other.id, row(Promotion.CREATOR_CODE, explicit)["creatorUserId"])
        assertEquals("Other", row(Promotion.CREATOR_CODE, explicit)["creator"])

        assertEquals("NOT_FOUND", fieldErrors { admin.create(Promotion.CREATOR_CODE, body("""{"creatorUserId":9999,"code":"BY-ID-2","discount":5}""")) }["creatorUserId"])

        // renaming the creator moves the owner; a key that was not sent leaves it
        admin.update(Promotion.CREATOR_CODE, unknown, body("""{"creator":"other"}"""))
        assertEquals(other.id, row(Promotion.CREATOR_CODE, unknown)["creatorUserId"])

        admin.update(Promotion.CREATOR_CODE, unknown, body("""{"commissionPercent":15}"""))
        assertEquals(other.id, row(Promotion.CREATOR_CODE, unknown)["creatorUserId"])

        admin.update(Promotion.CREATOR_CODE, unknown, body("""{"creatorUserId":null,"creator":"someone else"}"""))
        assertNull(row(Promotion.CREATOR_CODE, unknown)["creatorUserId"])
    }

    // ================================================================================================== gifts

    @Test
    fun `gift rules - code length, type fields, defaults of the limits`(): Unit = runBlocking {
        val product = fx.product("vip", price = 1000)

        assertEquals("TOO_SHORT", fieldErrors { admin.create(Promotion.GIFT, body("""{"code":"SHORT7X","type":"PRODUCT","productId":${product.id}}""".replace("SHORT7X", "ABCDEFG"))) }["code"])
        assertEquals("REQUIRED", fieldErrors { admin.create(Promotion.GIFT, body("""{"code":"GIFT-NOTYPE"}""")) }["type"])
        assertEquals("REQUIRED", fieldErrors { admin.create(Promotion.GIFT, body("""{"code":"GIFT-NOPROD","type":"PRODUCT"}""")) }["productId"])
        assertEquals("REQUIRED", fieldErrors { admin.create(Promotion.GIFT, body("""{"code":"GIFT-NOPOOL","type":"RANDOM","productIds":[]}""")) }["productIds"])
        assertEquals("NOT_FOUND", fieldErrors { admin.create(Promotion.GIFT, body("""{"code":"GIFT-GHOST1","type":"PRODUCT","productId":9999}""")) }["productId"])
        assertEquals("NOT_FOUND", fieldErrors { admin.create(Promotion.GIFT, body("""{"code":"GIFT-GHOST2","type":"RANDOM","productIds":[${product.id},9999]}""")) }["productIds"])

        // no redeemLimit key: the column default 1 (01 section 3.4); an explicit null: unlimited
        val defaulted = admin.create(Promotion.GIFT, body("""{"code":"GIFT-DEFAULT","type":"PRODUCT","productId":${product.id}}""")).id
        val unlimited = admin.create(Promotion.GIFT, body("""{"code":"GIFT-NOLIMIT","type":"PRODUCT","productId":${product.id},"redeemLimit":null,"customerRedeemLimit":null}""")).id

        assertEquals(1, row(Promotion.GIFT, defaulted)["redeemLimit"])
        assertEquals(1, row(Promotion.GIFT, defaulted)["customerRedeemLimit"])
        assertNull(row(Promotion.GIFT, unlimited)["redeemLimit"])
        assertNull(row(Promotion.GIFT, unlimited)["customerRedeemLimit"])
        assertEquals(0, count("market_gift", "`code` IN ('ABCDEFG', 'GIFT-NOTYPE', 'GIFT-NOPROD', 'GIFT-NOPOOL', 'GIFT-GHOST1', 'GIFT-GHOST2')"))
    }

    @Test
    fun `a credit gift needs 0 lt creditAmount le 1000000 with two decimals, else INVALID_CREDIT_AMOUNT`(): Unit = runBlocking {
        for (amount in listOf("0", "-5", "1000000.01", "1.234", "null", "\"12\"")) {
            val e = fails(InvalidCreditAmount::class.java) { admin.create(Promotion.GIFT, body("""{"code":"CREDIT-BAD-1","type":"CREDIT","creditAmount":$amount}""")) }

            assertEquals(400, e.getStatusCode())
            assertEquals("INVALID_CREDIT_AMOUNT", e.getErrorCode(), amount)
        }

        fails(InvalidCreditAmount::class.java) { admin.create(Promotion.GIFT, body("""{"code":"CREDIT-BAD-2","type":"CREDIT"}""")) }

        assertEquals(0, count("market_gift"))

        val ok = admin.create(Promotion.GIFT, body("""{"code":"CREDIT-OK-1","type":"CREDIT","creditAmount":1000000}""")).id
        val cents = admin.create(Promotion.GIFT, body("""{"code":"CREDIT-OK-2","type":"CREDIT","creditAmount":0.01}""")).id

        assertEquals(100_000_000L, row(Promotion.GIFT, ok)["creditAmount"])
        assertEquals(1L, row(Promotion.GIFT, cents)["creditAmount"])
        assertNull(row(Promotion.GIFT, ok)["productId"])

        // an update to CREDIT without an amount is refused; with one it clears the product columns
        val product = fx.product("vip", price = 1000)
        val gift = makeGift("GIFT-SWITCH-1", product.id)

        fails(InvalidCreditAmount::class.java) { admin.update(Promotion.GIFT, gift, body("""{"type":"CREDIT"}""")) }
        admin.update(Promotion.GIFT, gift, body("""{"type":"CREDIT","creditAmount":12.5}"""))

        assertEquals("CREDIT", row(Promotion.GIFT, gift)["type"])
        assertEquals(1250L, row(Promotion.GIFT, gift)["creditAmount"])
        assertNull(row(Promotion.GIFT, gift)["productId"])
    }

    @Test
    fun `the gift form refuses a physical product and a bundle with a physical child`(): Unit = runBlocking {
        val shirt = fx.product("shirt", price = 2500, columns = mapOf("physical" to true, "weightGrams" to 300))
        val digital = fx.product("digital", price = 100)
        val bundle = fx.bundle(digital to 1, shirt to 1)

        for (json in listOf(
            """{"code":"SHIRT-GIFT-1","type":"PRODUCT","productId":${shirt.id}}""",
            """{"code":"SHIRT-GIFT-2","type":"RANDOM","productIds":[${digital.id},${shirt.id}]}""",
            """{"code":"SHIRT-GIFT-3","type":"PRODUCT","productId":${bundle.id}}"""
        )) {
            val e = fails(InvalidGiftCode::class.java) { admin.create(Promotion.GIFT, body(json)) }

            assertEquals("PHYSICAL_NOT_SUPPORTED", ErrorBodies.details(e).getString("reason"), json)
        }

        assertEquals(0, count("market_gift"))

        // an update that points an existing gift at a physical product is refused too, the row stays
        val gift = makeGift("GIFT-OK-ONE", digital.id)

        fails(InvalidGiftCode::class.java) { admin.update(Promotion.GIFT, gift, body("""{"productId":${shirt.id}}""")) }
        assertEquals(digital.id, row(Promotion.GIFT, gift)["productId"])
    }

    @Test
    fun `a random gift stores its pool and clears the single product`(): Unit = runBlocking {
        val a = fx.product("a", price = 100)
        val b = fx.product("b", price = 100)
        val gift = admin.create(Promotion.GIFT, body("""{"code":"RANDOM-POOL","type":"RANDOM","productIds":[${a.id},${b.id},${a.id}]}""")).id

        assertEquals("[${a.id},${b.id}]", row(Promotion.GIFT, gift)["productIds"], "duplicates dropped")
        assertNull(row(Promotion.GIFT, gift)["productId"])

        val listed = admin.list(Promotion.GIFT, window(), null, null).rows.single()

        assertEquals(listOf("a", "b"), listed["productNames"])
        assertEquals(listOf(a.id, b.id), listed["productIds"])
    }

    // ============================================================================================ redemptions

    @Test
    fun `the redemption list pages newest first`(): Unit = runBlocking {
        val coupon = makeCoupon()

        for (n in 1..5) redemptionRow(RedemptionKind.COUPON, coupon, 10L + n)

        val first = admin.redemptionList(Promotion.COUPON, coupon, window(1, 2))
        val last = admin.redemptionList(Promotion.COUPON, coupon, window(3, 2))

        assertEquals(5, first.total)
        assertEquals(listOf(15L, 14L), first.rows.map { it.orderId })
        assertEquals(listOf(11L), last.rows.map { it.orderId })
        assertEquals(2.5, first.rows.first().amount / 100.0)
        assertEquals("EUR", first.rows.first().currency)
        assertEquals(RedemptionState.APPLIED, first.rows.first().state)
        assertEquals(0, admin.redemptionList(Promotion.COUPON, makeCoupon("OTHER-COUPON"), window()).total)
        assertFalse(admin.redemptionList(Promotion.COUPON, coupon, window(9, 2)).rows.isNotEmpty())
    }
}
