package com.panomc.plugins.market.routes.panel.discount

import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.GiftType
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.util.Locale

/** The four promotion tables of the discounts panel (01 section 3). The enum name is the [RedemptionKind] of the table. */
enum class Promotion(val table: String, val kind: RedemptionKind, val codeColumn: Boolean, val limitColumn: String) {
    DISCOUNT("market_discount", RedemptionKind.DISCOUNT, false, "usageLimit"),
    COUPON("market_coupon", RedemptionKind.COUPON, true, "redeemLimit"),
    CREATOR_CODE("market_creator_code", RedemptionKind.CREATOR_CODE, true, "redeemLimit"),
    GIFT("market_gift", RedemptionKind.GIFT, true, "redeemLimit");

    companion object {
        /** The three tables that share one code space (01 section 3.4). */
        val CODED = entries.filter { it.codeColumn }
    }
}

/** How a column is written to and read from JSON. */
internal enum class ColumnType { TEXT, LONG, INT, MONEY, IDS, BOOL }

internal class Column(val name: String, val type: ColumnType)

/**
 * What a promotion write changes: the column values to write ([values]; the keys are exactly the columns of the statement), and the values the
 * checks that need the database still have to look at. Never carries `usedCount`, `earnings` or `paidOut` (04 section 6, bug 2): they are not in
 * any column list of this file, and a body that names them is simply not read for them.
 */
internal class PromotionWrite(
    val values: LinkedHashMap<String, Any?>,
    val name: String?,
    val code: String?,
    val creator: String?,
    val creatorUserId: Long?,
    val creatorUserIdGiven: Boolean,
    val giftType: GiftType?,
    val productIds: List<Long>,
    val creditAmount: Long?
)

/**
 * The pure validation of the promotion bodies of 04 section 6 (T0): a body in, the columns to write and the `fieldErrors` out. Every value that is
 * outside its contract is refused with a code (`REQUIRED`, `INVALID`, `OUT_OF_RANGE`, `TOO_LONG`, ...); nothing is silently cleaned up except the
 * trim and the upper-casing of a code, which the lookup of a code does as well (11 section 6.2).
 */
internal object PromotionRules {
    private val CODE_FORMAT = Regex("^[A-Z0-9_-]{1,64}$")
    private const val MAX_NAME = 255
    private const val MAX_CREATOR = 64
    private const val MAX_MONEY_MINOR = 10_000_000_000L
    const val MAX_CREDIT_MINOR = 100_000_000L
    private const val MAX_LIMIT = 100_000_000
    private const val MAX_IDS = 1000

    /** A code as it is stored and looked up: trimmed and upper-cased. */
    fun normalizeCode(raw: String?): String? = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun codeValid(code: String): Boolean = CODE_FORMAT.matches(code)

    /** The columns a list or detail reads, per table (`id` and the timestamps included). */
    fun columns(promotion: Promotion): List<Column> = when (promotion) {
        Promotion.DISCOUNT -> listOf(
            c("id", ColumnType.LONG), c("name", ColumnType.TEXT), c("value", ColumnType.MONEY), c("unit", ColumnType.TEXT), c("minPaymentAmount", ColumnType.MONEY),
            c("scope", ColumnType.TEXT), c("productIds", ColumnType.IDS), c("categoryIds", ColumnType.IDS), c("startDate", ColumnType.LONG), c("expiryDate", ColumnType.LONG),
            c("usageLimit", ColumnType.INT), c("usedCount", ColumnType.INT), c("showBadge", ColumnType.BOOL), c("status", ColumnType.TEXT), c("createdAt", ColumnType.LONG),
            c("updatedAt", ColumnType.LONG)
        )

        Promotion.COUPON -> listOf(
            c("id", ColumnType.LONG), c("name", ColumnType.TEXT), c("code", ColumnType.TEXT), c("scope", ColumnType.TEXT), c("productIds", ColumnType.IDS),
            c("categoryIds", ColumnType.IDS), c("discount", ColumnType.MONEY), c("unit", ColumnType.TEXT), c("minPaymentAmount", ColumnType.MONEY),
            c("startDate", ColumnType.LONG), c("expiryDate", ColumnType.LONG), c("redeemLimit", ColumnType.INT), c("customerRedeemLimit", ColumnType.INT),
            c("usedCount", ColumnType.INT), c("status", ColumnType.TEXT), c("createdAt", ColumnType.LONG), c("updatedAt", ColumnType.LONG)
        )

        Promotion.CREATOR_CODE -> listOf(
            c("id", ColumnType.LONG), c("creator", ColumnType.TEXT), c("creatorUserId", ColumnType.LONG), c("code", ColumnType.TEXT), c("discount", ColumnType.MONEY),
            c("unit", ColumnType.TEXT), c("commissionPercent", ColumnType.MONEY), c("startDate", ColumnType.LONG), c("expiryDate", ColumnType.LONG),
            c("redeemLimit", ColumnType.INT), c("usedCount", ColumnType.INT), c("earnings", ColumnType.MONEY), c("paidOut", ColumnType.MONEY), c("status", ColumnType.TEXT),
            c("createdAt", ColumnType.LONG), c("updatedAt", ColumnType.LONG)
        )

        Promotion.GIFT -> listOf(
            c("id", ColumnType.LONG), c("name", ColumnType.TEXT), c("code", ColumnType.TEXT), c("type", ColumnType.TEXT), c("productId", ColumnType.LONG),
            c("creditAmount", ColumnType.MONEY), c("productIds", ColumnType.IDS), c("redeemLimit", ColumnType.INT), c("customerRedeemLimit", ColumnType.INT),
            c("usedCount", ColumnType.INT), c("status", ColumnType.TEXT), c("startDate", ColumnType.LONG), c("expiryDate", ColumnType.LONG), c("createdAt", ColumnType.LONG),
            c("updatedAt", ColumnType.LONG)
        )
    }

    private fun c(name: String, type: ColumnType) = Column(name, type)

    /** The keys a body of [promotion] may carry; counters, `id` and the timestamps are not among them (they are never read from a body). */
    fun fields(promotion: Promotion): Set<String> = when (promotion) {
        Promotion.DISCOUNT -> setOf(
            "name", "value", "unit", "minPaymentAmount", "scope", "productIds", "categoryIds", "startDate", "expiryDate", "usageLimit", "status", "showBadge"
        )

        Promotion.COUPON -> setOf(
            "name", "code", "discount", "unit", "scope", "productIds", "categoryIds", "minPaymentAmount", "startDate", "expiryDate", "redeemLimit", "customerRedeemLimit", "status"
        )

        Promotion.CREATOR_CODE -> setOf(
            "creator", "creatorUserId", "code", "discount", "unit", "commissionPercent", "startDate", "expiryDate", "redeemLimit", "status"
        )

        Promotion.GIFT -> setOf(
            "name", "code", "type", "productId", "productIds", "creditAmount", "startDate", "expiryDate", "redeemLimit", "customerRedeemLimit", "status"
        )
    }

    /**
     * Parses [body] for [promotion]. [stored] is the current row (`null` on create): on an update only the keys that are present are written (a
     * partial update), the rest keeps its stored value and takes part in the cross checks (the window, a percentage against the unit).
     * [errors] collects `field -> code`; a non-empty map is a 400 with `fieldErrors`.
     */
    fun parse(promotion: Promotion, body: JsonObject, stored: Map<String, Any?>?, errors: MutableMap<String, String>): PromotionWrite {
        val create = stored == null
        val values = LinkedHashMap<String, Any?>()

        // keys outside [fields] (`usedCount`, `earnings`, `paidOut`, `id`, the timestamps included) are never read, so they can never be written
        fun has(key: String) = body.containsKey(key)

        // ---- text
        var name: String? = null

        if (promotion != Promotion.CREATOR_CODE && (has("name") || (create && promotion != Promotion.GIFT))) {
            name = text(body, "name", errors, required = create && promotion != Promotion.GIFT, max = MAX_NAME)

            if (name != null || has("name")) values["name"] = name ?: ""
        }

        var code: String? = null

        if (promotion.codeColumn && (has("code") || create)) {
            val raw = body.getValue("code")

            code = normalizeCode(raw as? String)

            when {
                raw == null -> errors["code"] = "REQUIRED"
                raw !is String -> errors["code"] = "INVALID"
                code == null -> errors["code"] = "REQUIRED"
                !codeValid(code) -> errors["code"] = "INVALID_FORMAT"
                promotion == Promotion.GIFT && code.length < AbuseLimits.MIN_GIFT_CODE_LENGTH -> errors["code"] = "TOO_SHORT"
                else -> values["code"] = code
            }
        }

        var creator: String? = null

        if (promotion == Promotion.CREATOR_CODE && (has("creator") || create)) {
            // the owner given by id supplies the snapshot name (`PromotionAdminService`), so `creator` is only required without one
            creator = text(body, "creator", errors, required = create && body.getValue("creatorUserId") == null, max = MAX_CREATOR)

            if (creator != null) values["creator"] = creator
        }

        // ---- unit and the value the percentage is judged against
        val unit: DiscountUnit = when {
            promotion == Promotion.GIFT -> DiscountUnit.PERCENT
            has("unit") -> enumOf(DiscountUnit.entries.toTypedArray(), body.getValue("unit"), "unit", errors) ?: DiscountUnit.PERCENT
            else -> (stored?.get("unit") as? String)?.let { runCatching { DiscountUnit.valueOf(it) }.getOrNull() } ?: DiscountUnit.PERCENT
        }

        if (promotion != Promotion.GIFT && has("unit")) values["unit"] = unit.name

        val valueKey = if (promotion == Promotion.DISCOUNT) "value" else "discount"

        if (promotion != Promotion.GIFT && (has(valueKey) || create)) {
            val minor = money(body, valueKey, errors, required = create, max = MAX_MONEY_MINOR)

            if (minor != null && unit == DiscountUnit.PERCENT && minor > 10_000) errors[valueKey] = "OUT_OF_RANGE"
            else if (minor != null) values[valueKey] = minor
        } else if (promotion != Promotion.GIFT && has("unit")) {
            // a changed unit re-judges the stored percentage
            val storedValue = (stored?.get(valueKey) as? Number)?.toLong()

            if (storedValue != null && unit == DiscountUnit.PERCENT && storedValue > 10_000) errors["unit"] = "OUT_OF_RANGE"
        }

        if (promotion == Promotion.CREATOR_CODE && (has("commissionPercent") || create)) {
            val bp = if (has("commissionPercent")) money(body, "commissionPercent", errors, required = false, max = 10_000) else 0L

            if (bp != null) values["commissionPercent"] = bp
        }

        if ((promotion == Promotion.DISCOUNT || promotion == Promotion.COUPON) && has("minPaymentAmount")) {
            values["minPaymentAmount"] = if (body.getValue("minPaymentAmount") == null) null else money(body, "minPaymentAmount", errors, required = false, max = MAX_MONEY_MINOR)
        }

        // ---- scope and the id lists
        if (promotion == Promotion.DISCOUNT || promotion == Promotion.COUPON) {
            if (has("scope")) {
                val scope = if (promotion == Promotion.DISCOUNT) {
                    enumOf(DiscountScope.entries.toTypedArray(), body.getValue("scope"), "scope", errors)?.name
                } else {
                    enumOf(CouponScope.entries.toTypedArray(), body.getValue("scope"), "scope", errors)?.name
                }

                if (scope != null) values["scope"] = scope
            } else if (create) {
                values["scope"] = "ALL"
            }

            if (has("productIds")) values["productIds"] = ids(body, "productIds", errors)?.let { JsonArray(it).encode() }

            if (has("categoryIds")) values["categoryIds"] = ids(body, "categoryIds", errors)?.let { JsonArray(it).encode() }
        }

        // ---- window and limits
        if (has("startDate")) values["startDate"] = epoch(body, "startDate", errors)

        if (has("expiryDate")) values["expiryDate"] = epoch(body, "expiryDate", errors)

        val start = (if (has("startDate")) values["startDate"] else stored?.get("startDate")) as Long?
        val expiry = (if (has("expiryDate")) values["expiryDate"] else stored?.get("expiryDate")) as Long?

        if (start != null && expiry != null && start > expiry && "startDate" !in errors && "expiryDate" !in errors) errors["expiryDate"] = "BEFORE_START"

        if (has(promotion.limitColumn)) values[promotion.limitColumn] = limit(body, promotion.limitColumn, errors)

        if ((promotion == Promotion.COUPON || promotion == Promotion.GIFT) && has("customerRedeemLimit")) {
            values["customerRedeemLimit"] = limit(body, "customerRedeemLimit", errors)
        }

        if (has("status") || create) {
            val status = if (has("status")) enumOf(MarketStatus.entries.filter { it == MarketStatus.ACTIVE || it == MarketStatus.INACTIVE }.toTypedArray(), body.getValue("status"), "status", errors) else MarketStatus.ACTIVE

            if (status != null) values["status"] = status.name
        }

        if (promotion == Promotion.DISCOUNT && has("showBadge")) {
            val raw = body.getValue("showBadge")

            if (raw is Boolean) values["showBadge"] = raw else errors["showBadge"] = "INVALID"
        }

        // ---- creator code owner
        var creatorUserId: Long? = null
        val creatorUserIdGiven = promotion == Promotion.CREATOR_CODE && has("creatorUserId")

        if (creatorUserIdGiven) {
            val raw = body.getValue("creatorUserId")

            if (raw != null) {
                creatorUserId = (raw as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) && it.toLong() >= 1 }?.toLong()

                if (creatorUserId == null) errors["creatorUserId"] = "INVALID"
            }
        }

        // ---- gift
        var giftType: GiftType? = null
        var giftProducts: List<Long> = emptyList()
        var credit: Long? = null

        if (promotion == Promotion.GIFT) {
            val typeGiven = has("type")

            giftType = when {
                typeGiven -> enumOf(GiftType.entries.toTypedArray(), body.getValue("type"), "type", errors)
                create -> run { errors["type"] = "REQUIRED"; null }
                else -> (stored?.get("type") as? String)?.let { runCatching { GiftType.valueOf(it) }.getOrNull() }
            }

            if (giftType != null && (typeGiven || create)) values["type"] = giftType.name

            val productIdGiven = has("productId")
            val productIdsGiven = has("productIds")
            val creditGiven = has("creditAmount")
            val storedProduct = (stored?.get("productId") as? Number)?.toLong()
            val storedProducts = (stored?.get("productIds") as? String)?.let { raw -> runCatching { JsonArray(raw).map { (it as Number).toLong() } }.getOrNull() }.orEmpty()
            val storedCredit = (stored?.get("creditAmount") as? Number)?.toLong()

            val productId = if (productIdGiven) (body.getValue("productId")?.let { idOf(it, "productId", errors) }) else storedProduct
            val productIds = if (productIdsGiven) ids(body, "productIds", errors).orEmpty() else storedProducts
            val creditMinor = if (creditGiven) creditOf(body.getValue("creditAmount")) else storedCredit

            when (giftType) {
                GiftType.PRODUCT -> {
                    if (productId == null) errors["productId"] = "REQUIRED"

                    values["productId"] = productId
                    values["productIds"] = null
                    values["creditAmount"] = null

                    giftProducts = listOfNotNull(productId)
                }

                GiftType.RANDOM -> {
                    if (productIds.isEmpty()) errors["productIds"] = "REQUIRED"

                    values["productIds"] = if (productIds.isEmpty()) null else JsonArray(productIds.distinct()).encode()
                    values["productId"] = null
                    values["creditAmount"] = null

                    giftProducts = productIds.distinct()
                }

                GiftType.CREDIT -> {
                    // 07 section 10 / 04 section 6: 0 < creditAmount <= 1 000 000 with two decimals, else INVALID_CREDIT_AMOUNT (a field error would hide the code)
                    credit = creditMinor?.takeIf { it in 1..MAX_CREDIT_MINOR }
                    values["creditAmount"] = credit
                    values["productId"] = null
                    values["productIds"] = null
                }

                null -> Unit
            }
        }

        return PromotionWrite(values, name, code, creator, creatorUserId, creatorUserIdGiven, giftType, giftProducts, credit)
    }

    /** `true` when a CREDIT gift's amount (as sent, or as stored) is outside `(0, 1 000 000]` or has more than two decimals. */
    fun creditAmountInvalid(body: JsonObject, stored: Map<String, Any?>?): Boolean {
        val minor = if (body.containsKey("creditAmount")) creditOf(body.getValue("creditAmount")) else (stored?.get("creditAmount") as? Number)?.toLong()

        return minor == null || minor !in 1..MAX_CREDIT_MINOR
    }

    // ----- small parsers ---------------------------------------------------------------------------------------------

    private fun text(body: JsonObject, key: String, errors: MutableMap<String, String>, required: Boolean, max: Int): String? {
        val raw = body.getValue(key)

        if (raw == null) {
            if (required) errors[key] = "REQUIRED"

            return null
        }

        if (raw !is String) {
            errors[key] = "INVALID"

            return null
        }

        val clean = raw.filter { it.code >= 0x20 && it.code != 0x7f }.trim()

        if (clean.isEmpty()) {
            if (required) errors[key] = "REQUIRED"

            return null
        }

        if (clean.length > max) {
            errors[key] = "TOO_LONG"

            return null
        }

        return clean
    }

    private fun <E : Enum<E>> enumOf(values: Array<E>, raw: Any?, key: String, errors: MutableMap<String, String>): E? {
        val found = values.firstOrNull { it.name == raw }

        if (found == null) errors[key] = if (raw == null) "REQUIRED" else "UNKNOWN_VALUE"

        return found
    }

    /** A decimal with at most two decimals as x100 (`BigDecimal`, never `Double * 100`); `null` after an error. */
    private fun money(body: JsonObject, key: String, errors: MutableMap<String, String>, required: Boolean, max: Long): Long? {
        val raw = body.getValue(key)

        if (raw == null) {
            if (required) errors[key] = "REQUIRED"

            return null
        }

        val minor = minorOf(raw)

        if (minor == null) {
            errors[key] = "INVALID"

            return null
        }

        if (minor < 0 || minor > max) {
            errors[key] = "OUT_OF_RANGE"

            return null
        }

        return minor
    }

    /** A JSON number as x100, `null` for anything that is not a finite number with at most two decimals or that overflows. */
    fun minorOf(raw: Any?): Long? {
        if (raw !is Number) return null

        val value = runCatching { BigDecimal(raw.toString()) }.getOrNull() ?: return null
        val scaled = value.movePointRight(2)

        if (scaled.stripTrailingZeros().scale() > 0) return null

        return runCatching { scaled.longValueExact() }.getOrNull()
    }

    private fun creditOf(raw: Any?): Long? = minorOf(raw)

    private fun limit(body: JsonObject, key: String, errors: MutableMap<String, String>): Int? {
        val raw = body.getValue(key) ?: return null

        val value = (raw as? Number)?.takeIf { it !is Double && it !is Float || it.toDouble() == Math.floor(it.toDouble()) }?.toLong()

        if (value == null) {
            errors[key] = "INVALID"

            return null
        }

        if (value < 0 || value > MAX_LIMIT) {
            errors[key] = "OUT_OF_RANGE"

            return null
        }

        return value.toInt()
    }

    private fun epoch(body: JsonObject, key: String, errors: MutableMap<String, String>): Long? {
        val raw = body.getValue(key) ?: return null
        val value = (raw as? Number)?.takeIf { it !is Double && it !is Float || it.toDouble() == Math.floor(it.toDouble()) }?.toLong()

        if (value == null || value < 0) {
            errors[key] = "INVALID"

            return null
        }

        return value
    }

    private fun idOf(raw: Any, key: String, errors: MutableMap<String, String>): Long? {
        val value = (raw as? Number)?.takeIf { it !is Double && it !is Float || it.toDouble() == Math.floor(it.toDouble()) }?.toLong()

        if (value == null || value < 1) {
            errors[key] = "INVALID"

            return null
        }

        return value
    }

    /** A JSON array of positive ids (no duplicates kept); `null` for a JSON null. */
    private fun ids(body: JsonObject, key: String, errors: MutableMap<String, String>): List<Long>? {
        val raw = body.getValue(key) ?: return null

        if (raw !is JsonArray) {
            errors[key] = "INVALID"

            return null
        }

        if (raw.size() > MAX_IDS) {
            errors[key] = "TOO_MANY"

            return null
        }

        val out = ArrayList<Long>()

        for (element in raw.list) {
            val id = (element as? Number)?.takeIf { it !is Double && it !is Float || it.toDouble() == Math.floor(it.toDouble()) }?.toLong()

            if (id == null || id < 1) {
                errors[key] = "INVALID"

                return null
            }

            if (id !in out) out += id
        }

        return out
    }
}
