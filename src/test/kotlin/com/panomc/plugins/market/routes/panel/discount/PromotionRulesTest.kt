package com.panomc.plugins.market.routes.panel.discount

import com.panomc.platform.annotation.Endpoint
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The pure parts of the discounts panel (MK-113; 04 section 6): the validation of the four bodies, the money parser, the code format, and the shape of
 * the route classes. The behaviour on a database is `PromotionAdminIT`.
 */
class PromotionRulesTest {
    private fun parse(promotion: Promotion, json: String, stored: Map<String, Any?>? = null): Pair<PromotionWrite, Map<String, String>> {
        val errors = linkedMapOf<String, String>()

        return PromotionRules.parse(promotion, JsonObject(json), stored, errors) to errors
    }

    @Test
    fun `money is parsed from the text of the number, never as a double times 100`() {
        assertEquals(1999L, PromotionRules.minorOf(19.99))
        assertEquals(7L, PromotionRules.minorOf(0.07))
        assertEquals(1005L, PromotionRules.minorOf(10.05))
        assertEquals(29L, PromotionRules.minorOf(0.29), "0.29 * 100 is 28.999999999999996 as a double")
        assertEquals(100L, PromotionRules.minorOf(1))
        assertEquals(100L, PromotionRules.minorOf(1.0))
        assertNull(PromotionRules.minorOf(1.005), "three decimals are refused, not rounded")
        assertNull(PromotionRules.minorOf("1"))
        assertNull(PromotionRules.minorOf(null))
        assertNull(PromotionRules.minorOf(Double.NaN))
        assertNull(PromotionRules.minorOf(1.0E300))
    }

    @Test
    fun `a code is trimmed, upper-cased and held to the code format`() {
        assertEquals("ABC-1_2", PromotionRules.normalizeCode("  abc-1_2 "))
        assertNull(PromotionRules.normalizeCode("   "))
        assertNull(PromotionRules.normalizeCode(null))

        for (ok in listOf("A", "SUMMER-10", "a_b-c", "X".repeat(64))) assertTrue(PromotionRules.codeValid(PromotionRules.normalizeCode(ok)!!), ok)
        for (bad in listOf("has space", "UMLAUTÜ", "A.B", "<B>", "X".repeat(65), "ta\tb")) assertFalse(PromotionRules.codeValid(PromotionRules.normalizeCode(bad)!!), bad)
    }

    @Test
    fun `no body can name a counter, an id or a timestamp as a column to write`() {
        val evil = """"usedCount":9,"earnings":9,"paidOut":9,"id":9,"createdAt":9,"updatedAt":9,"deletedAt":9,"legacyUsedCount":9"""

        for (promotion in Promotion.entries) {
            val body = when (promotion) {
                Promotion.DISCOUNT -> """{"name":"n","value":1,$evil}"""
                Promotion.COUPON -> """{"name":"n","code":"CODE-ONE","discount":1,$evil}"""
                Promotion.CREATOR_CODE -> """{"creator":"c","code":"CODE-ONE","discount":1,$evil}"""
                Promotion.GIFT -> """{"code":"CODE-ONE","type":"CREDIT","creditAmount":1,$evil}"""
            }
            val (write, errors) = parse(promotion, body)

            assertTrue(errors.isEmpty(), "$promotion $errors")

            val forbidden = setOf("usedCount", "earnings", "paidOut", "id", "createdAt", "updatedAt", "deletedAt", "legacyUsedCount")

            assertTrue(write.values.keys.none { it in forbidden }, "$promotion writes ${write.values.keys}")
        }
    }

    @Test
    fun `an update carries only the keys that were sent`() {
        val stored = mapOf("startDate" to 1000L, "expiryDate" to 5000L, "unit" to "PERCENT", "discount" to 1000L)
        val (write, errors) = parse(Promotion.COUPON, """{"status":"INACTIVE"}""", stored)

        assertTrue(errors.isEmpty())
        assertEquals(listOf("status"), write.values.keys.toList())

        val (renamed, _) = parse(Promotion.COUPON, """{"name":"New","redeemLimit":null}""", stored)

        assertEquals(listOf("name", "redeemLimit"), renamed.values.keys.toList())
        assertNull(renamed.values["redeemLimit"])
    }

    @Test
    fun `the unit decides what the value may be`() {
        assertTrue(parse(Promotion.COUPON, """{"name":"n","code":"CODE-ONE","discount":100,"unit":"PERCENT"}""").second.isEmpty())
        assertEquals("OUT_OF_RANGE", parse(Promotion.COUPON, """{"name":"n","code":"CODE-ONE","discount":100.01,"unit":"PERCENT"}""").second["discount"])
        assertTrue(parse(Promotion.COUPON, """{"name":"n","code":"CODE-ONE","discount":500,"unit":"FIXED"}""").second.isEmpty())
        assertEquals("OUT_OF_RANGE", parse(Promotion.DISCOUNT, """{"name":"n","value":-0.01}""").second["value"])
        assertEquals("OUT_OF_RANGE", parse(Promotion.COUPON, """{"unit":"PERCENT"}""", mapOf("discount" to 50_000L, "unit" to "FIXED")).second["unit"], "a stored 500.00 is not a percentage")
    }

    @Test
    fun `a gift needs a type and what the type hands out, a credit gift an amount in range`() {
        assertEquals("REQUIRED", parse(Promotion.GIFT, """{"code":"GIFT-CODE-1"}""").second["type"])
        assertEquals("REQUIRED", parse(Promotion.GIFT, """{"code":"GIFT-CODE-1","type":"PRODUCT"}""").second["productId"])
        assertEquals("REQUIRED", parse(Promotion.GIFT, """{"code":"GIFT-CODE-1","type":"RANDOM"}""").second["productIds"])
        assertEquals("TOO_SHORT", parse(Promotion.GIFT, """{"code":"SEVEN77","type":"CREDIT","creditAmount":1}""").second["code"])
        assertTrue(parse(Promotion.GIFT, """{"code":"EIGHT888","type":"CREDIT","creditAmount":1}""").second.isEmpty())

        val body = { json: String -> JsonObject(json) }

        assertTrue(PromotionRules.creditAmountInvalid(body("""{}"""), null))
        assertTrue(PromotionRules.creditAmountInvalid(body("""{"creditAmount":0}"""), null))
        assertTrue(PromotionRules.creditAmountInvalid(body("""{"creditAmount":1000000.01}"""), null))
        assertTrue(PromotionRules.creditAmountInvalid(body("""{"creditAmount":1.001}"""), null))
        assertFalse(PromotionRules.creditAmountInvalid(body("""{"creditAmount":1000000}"""), null))
        assertFalse(PromotionRules.creditAmountInvalid(body("""{"creditAmount":0.01}"""), null))
        assertFalse(PromotionRules.creditAmountInvalid(body("""{}"""), mapOf("creditAmount" to 500L)), "a stored amount is kept when none is sent")
    }

    @Test
    fun `the gift columns of one type clear the columns of the others`() {
        val (product, _) = parse(Promotion.GIFT, """{"code":"GIFT-CODE-1","type":"PRODUCT","productId":3}""")

        assertEquals(3L, product.values["productId"])
        assertNull(product.values["productIds"])
        assertNull(product.values["creditAmount"])

        val (random, _) = parse(Promotion.GIFT, """{"code":"GIFT-CODE-1","type":"RANDOM","productIds":[4,5,4]}""")

        assertEquals("[4,5]", random.values["productIds"])
        assertNull(random.values["productId"])
        assertEquals(listOf(4L, 5L), random.productIds)
    }

    @Test
    fun `the list of columns of every table names the counters the panel shows and nothing else is writable`() {
        for (promotion in Promotion.entries) {
            val names = PromotionRules.columns(promotion).map { it.name }

            assertTrue("id" in names && "usedCount" in names, promotion.name)
            assertTrue(PromotionRules.fields(promotion).none { it in setOf("usedCount", "earnings", "paidOut", "id", "createdAt", "updatedAt", "deletedAt") }, promotion.name)
        }

        assertTrue("earnings" in PromotionRules.columns(Promotion.CREATOR_CODE).map { it.name })
        assertEquals(setOf(Promotion.COUPON, Promotion.CREATOR_CODE, Promotion.GIFT), Promotion.CODED.toSet())
    }

    @Test
    fun `the routes are endpoints under the market panel base on the discounts node, with the paths of 04 section 6`() {
        val dir = "src/main/kotlin/com/panomc/plugins/market/routes/panel"
        val expected = listOf(
            Triple("discount/PanelGetDiscountsAPI", "/discounts", "GET"),
            Triple("discount/PanelCreateDiscountAPI", "/discounts", "POST"),
            Triple("discount/PanelUpdateDiscountAPI", "/discounts/:id", "PUT"),
            Triple("discount/PanelDeleteDiscountAPI", "/discounts/:id", "DELETE"),
            Triple("coupon/PanelGetCouponsAPI", "/coupons", "GET"),
            Triple("coupon/PanelCreateCouponAPI", "/coupons", "POST"),
            Triple("coupon/PanelUpdateCouponAPI", "/coupons/:id", "PUT"),
            Triple("coupon/PanelDeleteCouponAPI", "/coupons/:id", "DELETE"),
            Triple("coupon/PanelGetCouponRedemptionsAPI", "/coupons/:id/redemptions", "GET"),
            Triple("creatorcode/PanelGetCreatorCodesAPI", "/creator-codes", "GET"),
            Triple("creatorcode/PanelCreateCreatorCodeAPI", "/creator-codes", "POST"),
            Triple("creatorcode/PanelUpdateCreatorCodeAPI", "/creator-codes/:id", "PUT"),
            Triple("creatorcode/PanelDeleteCreatorCodeAPI", "/creator-codes/:id", "DELETE"),
            Triple("creatorcode/PanelGetCreatorCodeRedemptionsAPI", "/creator-codes/:id/redemptions", "GET"),
            Triple("gift/PanelGetGiftsAPI", "/gifts", "GET"),
            Triple("gift/PanelCreateGiftAPI", "/gifts", "POST"),
            Triple("gift/PanelUpdateGiftAPI", "/gifts/:id", "PUT"),
            Triple("gift/PanelDeleteGiftAPI", "/gifts/:id", "DELETE"),
            Triple("gift/PanelGetGiftRedemptionsAPI", "/gifts/:id/redemptions", "GET")
        )

        for ((file, path, method) in expected) {
            val source = File("$dir/$file.kt").readText()
            val name = file.substringAfterLast('/')

            assertTrue(source.contains("Path(\"$path\", RouteType.$method)"), "$name serves $method $path")
            assertTrue(source.contains("@Endpoint"), "$name is an @Endpoint")
            assertTrue(Regex("class $name\\(plugin: MarketPlugin\\) : PromotionAdminRoute\\(plugin, Promotion\\.[A-Z_]+\\)").containsMatchIn(source), "$name is a PromotionAdminRoute")

            val type = Class.forName("com.panomc.plugins.market.routes.panel.${file.replace('/', '.')}")

            assertTrue(MarketPanelApi::class.java.isAssignableFrom(type), name)
            assertTrue(type.isAnnotationPresent(Endpoint::class.java), name)
        }

        // the node is the discounts permission (P:DISC); the umbrella and the admin bypass are the base class's
        val base = File("$dir/discount/PromotionAdminRoute.kt").readText()

        assertTrue(base.contains("override val nodes: Set<MarketNode> = setOf(MarketNode.DISCOUNTS)"))
        assertEquals("DISC", MarketNode.DISCOUNTS.shortName)
    }

    @Test
    fun `the redeem route is a user route`() {
        val source = File("src/main/kotlin/com/panomc/plugins/market/routes/user/gift/RedeemGiftAPI.kt").readText()

        assertTrue(source.contains("Path(\"/me/gifts/redeem\", RouteType.POST)"))
        assertTrue(source.contains(") : MarketUserApi()"))
        assertTrue(source.contains("@Endpoint"))
    }
}
