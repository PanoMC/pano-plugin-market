package com.panomc.plugins.market.support

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.panomc.plugins.market.db.model.*
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.GiftType

/**
 * The standard catalogue of 17 section 5.6, seeded through the DAOs of a [TestWiring] (T4 seeds the same data through the
 * panel API). Base currency EUR, VAT 20 % shown in the price, credit value 1.0, time zone UTC (the config of
 * [TestWiring.defaultConfig]). Amounts are x100.
 *
 * `val c = StandardCatalog(w).seed()` then `c.vip`, `c.coupon("TEN")`, `c.alice`. A wiring seeds one catalogue: the
 * slugs, codes and user names are the fixed ones of the table.
 */
class StandardCatalog(private val w: TestWiring) {
    private val f get() = w.fixtures

    lateinit var vip: MarketProduct private set
    lateinit var last: MarketProduct private set
    lateinit var limited: MarketProduct private set
    lateinit var free: MarketProduct private set
    lateinit var timed: MarketProduct private set
    lateinit var sub: MarketProduct private set
    lateinit var variantProduct: MarketProduct private set
    lateinit var bundle: MarketProduct private set
    lateinit var tier1: MarketProduct private set
    lateinit var tier2: MarketProduct private set
    lateinit var pack: MarketProduct private set
    lateinit var shirt: MarketProduct private set
    lateinit var cmd: MarketProduct private set

    lateinit var tierCategory: MarketCategory private set
    lateinit var variantS: MarketProductVariant private set
    lateinit var variantL: MarketProductVariant private set
    lateinit var noteField: MarketProductField private set

    lateinit var alice: TestUser private set
    lateinit var bob: TestUser private set
    lateinit var carol: TestUser private set
    lateinit var admin: TestUser private set

    private val coupons = LinkedHashMap<String, MarketCoupon>()
    lateinit var streamer: MarketCreatorCode private set
    lateinit var gift1: MarketGift private set

    /** Every product by the key of the table (`VIP`, `LAST`, ...). */
    val products: Map<String, MarketProduct>
        get() = mapOf(
            "VIP" to vip, "LAST" to last, "LIMITED" to limited, "FREE" to free, "TIMED" to timed, "SUB" to sub, "VAR" to variantProduct,
            "BUNDLE" to bundle, "TIER1" to tier1, "TIER2" to tier2, "PACK" to pack, "SHIRT" to shirt, "CMD" to cmd
        )

    fun coupon(code: String): MarketCoupon = coupons.getValue(code)

    suspend fun seed(): StandardCatalog {
        vip = f.product("vip", "VIP", price = 1000, creditPrice = 1000, actions = grantAndRevoke(permission("group.vip"), credit(2.5), webhook("https://hooks.invalid/vip")))
        last = f.product("last-one", "Last one", price = 500, stock = 1)
        limited = f.product("one-per-player", "One per player", price = 300, columns = mapOf("limitPerPlayer" to 1, "cooldownSeconds" to 3600))
        free = f.product("free-kit", "Free kit", price = 0, actions = array(action("a1", "CREDIT", "GRANT", 1)))
        timed = f.product(
            "rank-30-day", "30 day rank", price = 800,
            actions = array(action("a1", "PERMISSION", "GRANT", arrayOf("group.timed")), action("a2", "PERMISSION", "EXPIRE", arrayOf("group.timed"))),
            columns = mapOf("billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to 30)
        )
        sub = f.product("monthly", "Monthly", price = 600, columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1))
        variantProduct = f.product("crate", "Crate", price = 400, columns = mapOf("variantOptions" to "[{\"key\":\"size\",\"label\":\"Size\",\"values\":[{\"key\":\"S\",\"label\":\"S\"},{\"key\":\"L\",\"label\":\"L\"}]}]"))
        variantS = f.variant(variantProduct, "S", price = 400, stock = 2, position = 0, optionValues = "{\"size\":\"S\"}")
        variantL = f.variant(variantProduct, "L", price = 700, stock = null, position = 1, optionValues = "{\"size\":\"L\"}")
        noteField = f.field(variantProduct, "note", ProductFieldType.TEXT, required = false)
        bundle = f.bundle(vip to 1, last to 1, slug = "starter-bundle", price = 1200)
        tierCategory = f.category("Ranks", tiered = true, upgradeMode = "DIFFERENCE")
        tier1 = f.product("tier-1", "Tier 1", price = 1000, categoryId = tierCategory.id, columns = mapOf("tierRank" to 1))
        tier2 = f.product("tier-2", "Tier 2", price = 2500, categoryId = tierCategory.id, columns = mapOf("tierRank" to 2))
        pack = f.product("credits-500", "500 credits", price = 500, columns = mapOf("kind" to "CREDIT_PACK", "creditAmount" to 50000))
        shirt = f.product("t-shirt", "T-shirt", price = 2000, stock = 10, columns = mapOf("physical" to true, "weightGrams" to 250))
        cmd = f.product(
            "diamonds", "Diamonds", price = 200,
            actions = array(
                action("a1", "COMMAND", "GRANT", arrayOf("give {username} diamond {quantity}")).apply {
                    addProperty("serverMode", "FIXED")
                    add("targetServers", JsonArray().apply { add(1) })
                    addProperty("requiresOnline", true)
                }
            )
        )

        coupons["TEN"] = f.coupon("TEN", DiscountUnit.PERCENT, 1000)
        coupons["FIVEOFF"] = f.coupon("FIVEOFF", DiscountUnit.FIXED, 500, minPaymentAmount = 2000)
        coupons["ONCE"] = f.coupon("ONCE", DiscountUnit.PERCENT, 5000, redeemLimit = 3, customerRedeemLimit = 1)
        coupons["FULL"] = f.coupon("FULL", DiscountUnit.PERCENT, 10000)
        streamer = f.creatorCode("STREAMER", "streamer", discount = 500, commissionPercent = 1000)
        gift1 = f.gift("GIFT1", GiftType.PRODUCT, productId = vip.id, redeemLimit = 1)

        alice = f.user("alice").also { f.credit(it, 10000) }
        bob = f.user("bob")
        carol = f.user("carol")
        admin = f.user("admin")
        return this
    }

    // --- action JSON of 01 section 2.2 ---

    private fun action(id: String, type: String, phase: String, value: Any): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", type)
        addProperty("phase", phase)
        when (value) {
            is Number -> addProperty("value", value)
            is Array<*> -> add("value", JsonArray().apply { value.forEach { add(it.toString()) } })
            is JsonObject -> add("value", value)
            else -> error("unsupported action value $value")
        }
    }

    private fun array(vararg actions: JsonObject): String = JsonArray().apply { actions.forEach { add(it) } }.toString()

    private fun permission(node: String) = Triple("PERMISSION", arrayOf(node) as Any, "p")
    private fun credit(amount: Double) = Triple("CREDIT", amount as Any, "c")
    private fun webhook(url: String) = Triple(
        "WEBHOOK",
        JsonObject().apply { addProperty("url", url); addProperty("format", "JSON"); addProperty("signing", "NONE") } as Any,
        "w"
    )

    /** GRANT rows for the given actions and the mirrored REVOKE rows (the inverse phase of 08 section 6). */
    private fun grantAndRevoke(vararg specs: Triple<String, Any, String>): String {
        val rows = ArrayList<JsonObject>()
        specs.forEachIndexed { i, (type, value, _) -> rows += action("a${i + 1}", type, "GRANT", value) }
        specs.forEachIndexed { i, (type, value, _) -> rows += action("r${i + 1}", type, "REVOKE", value) }
        return array(*rows.toTypedArray())
    }
}
