package com.panomc.plugins.market.e2e.support

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The standard catalogue of 17 section 5.6, seeded through the panel API (17 section 8.2 step 5). Prices are decimal strings (EUR, 20 % VAT shown
 * in the price). [products] maps the key of the table (`VIP`, `LAST`, ...) to the product id; [slugs] to its slug.
 *
 * Seeding is idempotent against an instance that was started with `--keep` (a product, category or code that already exists is looked up, not
 * created again), so a run can be repeated against the same instance while a scenario is being developed.
 *
 * Deviations from the table, forced by what the backend accepts today (recorded in `evidence/MK-080.md`): the `WEBHOOK` action of `VIP` is left out
 * (`ProductActions` refuses it until MK-104), and the store webhook endpoint `sink` is not created (there is no webhook route yet).
 */
class E2eCatalog(private val admin: E2eClient, private val db: E2eDb) {
    val products = LinkedHashMap<String, Long>()
    val slugs = LinkedHashMap<String, String>()
    val variants = LinkedHashMap<String, Long>()
    val coupons = LinkedHashMap<String, Long>()
    val problems = ArrayList<String>()
    var tierCategoryId: Long = 0
        private set

    private val unique = AtomicInteger()

    fun id(key: String): Long = products[key] ?: throw IllegalStateException("product $key was not seeded: ${problems.joinToString("; ")}")

    fun slug(key: String): String = slugs.getValue(key)

    fun seed(): E2eCatalog {
        category("Ranks", tiered = true, upgradeMode = "DIFFERENCE").also { tierCategoryId = it }

        product("VIP", "vip", "VIP", price = "10.00", creditPrice = "10.00", actions = grantAndRevoke(permission("group.vip"), credit(2.5)))
        product("LAST", "last-one", "Last one", price = "5.00", stock = 1)
        product("LIMITED", "one-per-player", "One per player", price = "3.00", extra = mapOf("limitPerPlayer" to "1", "cooldownSeconds" to "3600"))
        product("FREE", "free-kit", "Free kit", price = "0.00", actions = JsonArray().add(action("a1", "CREDIT", "GRANT", 1)).encode())
        product(
            "TIMED", "rank-30-day", "30 day rank", price = "8.00",
            actions = JsonArray().add(action("a1", "PERMISSION", "GRANT", JsonArray().add("group.timed"))).add(action("a2", "PERMISSION", "EXPIRE", JsonArray().add("group.timed"))).encode(),
            extra = mapOf("billingMode" to "TIMED", "periodUnit" to "DAY", "periodCount" to "30")
        )
        product("SUB", "monthly", "Monthly", price = "6.00", extra = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to "1"))
        product(
            "VAR", "crate", "Crate", price = "4.00",
            extra = mapOf(
                "hasVariants" to "true",
                "variantOptions" to """[{"key":"size","label":"Size","values":[{"key":"S","label":"S"},{"key":"L","label":"L"}]}]""",
                "variants" to """[{"name":"S","price":"4.00","stock":2,"position":0,"optionValues":{"size":"S"}},{"name":"L","price":"7.00","position":1,"optionValues":{"size":"L"}}]""",
                "fields" to """[{"fieldKey":"note","label":"Note","type":"TEXT","required":false}]"""
            )
        )
        product(
            "BUNDLE", "starter-bundle", "Starter bundle", price = "12.00",
            extra = mapOf("kind" to "BUNDLE", "bundleItems" to """[{"productId":${products["VIP"]},"variantId":0,"quantity":1},{"productId":${products["LAST"]},"variantId":0,"quantity":1}]""")
        )
        product("TIER1", "tier-1", "Tier 1", price = "10.00", extra = mapOf("categoryId" to tierCategoryId.toString(), "tierRank" to "1"))
        product("TIER2", "tier-2", "Tier 2", price = "25.00", extra = mapOf("categoryId" to tierCategoryId.toString(), "tierRank" to "2"))
        product("PACK", "credits-500", "500 credits", price = "5.00", extra = mapOf("kind" to "CREDIT_PACK", "creditAmount" to "500.00"))
        product("SHIRT", "t-shirt", "T-shirt", price = "20.00", stock = 10, extra = mapOf("physical" to "true", "weightGrams" to "250"))
        product(
            "CMD", "diamonds", "Diamonds", price = "2.00",
            actions = JsonArray().add(
                action("a1", "COMMAND", "GRANT", JsonArray().add("give {username} diamond {quantity}"))
                    .put("serverMode", "FIXED").put("targetServers", JsonArray().add(1)).put("requiredOnline", true).put("requiresOnline", true)
            ).encode()
        )

        coupon("TEN", JsonObject().put("name", "Ten").put("discount", 10).put("unit", "PERCENT"))
        coupon("FIVEOFF", JsonObject().put("name", "Five off").put("discount", 5).put("unit", "FIXED").put("minPaymentAmount", 20))
        coupon("ONCE", JsonObject().put("name", "Once").put("discount", 50).put("unit", "PERCENT").put("redeemLimit", 3).put("customerRedeemLimit", 1))
        coupon("FULL", JsonObject().put("name", "Full").put("discount", 100).put("unit", "PERCENT"))
        creatorCode("STREAMER", JsonObject().put("creator", "streamer").put("discount", 5).put("unit", "PERCENT").put("commissionPercent", 10))
        gift("GIFT1", JsonObject().put("type", "PRODUCT").put("productId", products["VIP"]).put("redeemLimit", 1))

        return this
    }

    // --- products ------------------------------------------------------------------------------------------------------

    /** Creates (or finds) one product of the table. */
    fun product(
        key: String, slug: String, name: String, price: String, creditPrice: String? = null, stock: Int? = null, actions: String? = null,
        extra: Map<String, String> = emptyMap()
    ): Long {
        existingProduct(slug)?.let { found ->
            products[key] = found
            slugs[key] = slug
            return found
        }
        val form = linkedMapOf("name" to name, "slug" to slug, "price" to price, "status" to "ACTIVE")
        creditPrice?.let { form["creditPrice"] = it }
        stock?.let { form["stock"] = it.toString() }
        actions?.let { form["actions"] = it }
        form.putAll(extra)
        val answer = admin.multipart("POST", "/api/panel/market/products", form)
        if (answer.status != 200) {
            problems += "product $key: ${answer.status} ${answer.error} ${answer.json?.encode()?.take(500)}"
            throw AssertionError(problems.last())
        }
        val id = answer.obj().getLong("id")
        products[key] = id
        slugs[key] = slug
        return id
    }

    /**
     * A fresh, ACTIVE product built like the standard product [key] with its own slug: scenarios create the products whose counters they assert
     * and never rely on leftovers (17 section 8.2). The standard definition is re-posted with [overrides] on top (the equivalent of the clone
     * endpoint, which clones as `INACTIVE` and so needs a second call to activate it).
     */
    fun fresh(key: String, vararg overrides: Pair<String, String>): FreshProduct {
        val standard = definitions.getValue(key)
        val n = unique.incrementAndGet()
        val slug = "e2e-${key.lowercase()}-${System.currentTimeMillis().toString(36)}-$n"
        val form = LinkedHashMap(standard.form(this))
        form["slug"] = slug
        form["name"] = "${standard.name} $n"
        form["status"] = "ACTIVE"
        overrides.forEach { (k, v) -> if (v.isEmpty()) form.remove(k) else form[k] = v }
        val answer = admin.multipart("POST", "/api/panel/market/products", form)
        if (answer.status != 200) throw AssertionError("fresh $key: ${answer.status} ${answer.error} ${answer.json}")
        return FreshProduct(answer.obj().getLong("id"), slug)
    }

    class FreshProduct(val id: Long, val slug: String)

    private class Definition(val name: String, val form: (E2eCatalog) -> Map<String, String>)

    private val definitions: Map<String, Definition> by lazy {
        fun def(name: String, build: (E2eCatalog) -> Map<String, String>) = Definition(name, build)
        mapOf(
            "VIP" to def("VIP") { mapOf("price" to "10.00", "creditPrice" to "10.00", "actions" to grantAndRevoke(permission("group.vip"), credit(2.5))) },
            "LAST" to def("Last one") { mapOf("price" to "5.00", "stock" to "1") },
            "LIMITED" to def("One per player") { mapOf("price" to "3.00", "limitPerPlayer" to "1", "cooldownSeconds" to "3600") },
            "FREE" to def("Free kit") { mapOf("price" to "0.00", "actions" to JsonArray().add(action("a1", "CREDIT", "GRANT", 1)).encode()) },
            "VAR" to def("Crate") {
                mapOf(
                    "price" to "4.00", "hasVariants" to "true",
                    "variantOptions" to """[{"key":"size","label":"Size","values":[{"key":"S","label":"S"},{"key":"L","label":"L"}]}]""",
                    "variants" to """[{"name":"S","price":"4.00","stock":2,"position":0,"optionValues":{"size":"S"}},{"name":"L","price":"7.00","position":1,"optionValues":{"size":"L"}}]""",
                    "fields" to """[{"fieldKey":"note","label":"Note","type":"TEXT","required":false}]"""
                )
            }
        )
    }

    private fun existingProduct(slug: String): Long? = db.long("SELECT `id` FROM `pano_market_product` WHERE `slug` = ? AND `deletedAt` IS NULL", slug)

    private fun category(name: String, tiered: Boolean, upgradeMode: String): Long {
        db.long("SELECT `id` FROM `pano_market_category` WHERE `name` = ? LIMIT 1", name)?.let { return it }
        val answer = admin.multipart("POST", "/api/panel/market/categories", mapOf("name" to name, "tiered" to tiered.toString(), "upgradeMode" to upgradeMode, "status" to "ACTIVE"))
        if (answer.status != 200) {
            problems += "category $name: ${answer.status} ${answer.error} ${answer.json}"
            throw AssertionError(problems.last())
        }
        return answer.obj().getLong("id")
    }

    // --- codes ---------------------------------------------------------------------------------------------------------

    private fun coupon(code: String, body: JsonObject) {
        coupons[code] = existingCode("market_coupon", code) ?: create("/api/panel/market/coupons", body.put("code", code), "coupon $code")
    }

    private fun creatorCode(code: String, body: JsonObject) {
        coupons[code] = existingCode("market_creator_code", code) ?: create("/api/panel/market/creator-codes", body.put("code", code), "creator code $code")
    }

    private fun gift(code: String, body: JsonObject) {
        coupons[code] = existingCode("market_gift", code) ?: create("/api/panel/market/gifts", body.put("code", code), "gift $code")
    }

    private fun existingCode(table: String, code: String): Long? = db.long("SELECT `id` FROM `pano_$table` WHERE `code` = ?", code)

    private fun create(path: String, body: JsonObject, what: String): Long {
        val answer = admin.post(path, body)
        if (answer.status != 200) {
            problems += "$what: ${answer.status} ${answer.error}"
            throw AssertionError(problems.last())
        }
        return answer.obj().getLong("id")
    }

    /** A coupon of its own for a scenario that asserts its counter (a unique code, so repeated runs never collide). */
    fun freshCoupon(discountPercent: Int, redeemLimit: Int? = null, customerRedeemLimit: Int? = null): Pair<Long, String> {
        val code = "E2E" + System.currentTimeMillis().toString(36).uppercase() + unique.incrementAndGet()
        val body = JsonObject().put("name", "E2E $code").put("code", code).put("discount", discountPercent).put("unit", "PERCENT")
        redeemLimit?.let { body.put("redeemLimit", it) }
        customerRedeemLimit?.let { body.put("customerRedeemLimit", it) }
        return create("/api/panel/market/coupons", body, "fresh coupon") to code
    }

    // --- action JSON of 01 section 2.2 ---------------------------------------------------------------------------------

    private fun action(id: String, type: String, phase: String, value: Any): JsonObject = JsonObject().put("id", id).put("type", type).put("phase", phase).put("value", value)

    private fun permission(node: String) = Triple("PERMISSION", JsonArray().add(node) as Any, "p")

    private fun credit(amount: Double) = Triple("CREDIT", amount as Any, "c")

    private fun grantAndRevoke(vararg specs: Triple<String, Any, String>): String {
        val rows = JsonArray()
        specs.forEachIndexed { i, (type, value, _) -> rows.add(action("a${i + 1}", type, "GRANT", value)) }
        specs.forEachIndexed { i, (type, value, _) -> rows.add(action("r${i + 1}", type, "REVOKE", value)) }
        return rows.encode()
    }
}
