package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.spi.common.SafeRegex
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.ProductDurationType

/*
 * The rules a cart line must satisfy before it can be bought (06 sections 6.3 and 6.4, 09 section 4.1), as pure
 * functions of facts the caller has loaded: no database, no clock, no platform class. The quote (MK-072) reports the
 * verdict as `QuoteLine.errors` and `Quote.messages`; checkout (MK-075) runs the same rules again on locked rows and
 * maps a failure to its HTTP error. A rule never throws: a business problem is a code in a verdict.
 */

/** The line and message codes of 04 section 11 that the rules in this file produce. */
object LineCode {
    const val PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE"
    const val VARIANT_REQUIRED = "VARIANT_REQUIRED"
    const val VARIANT_UNAVAILABLE = "VARIANT_UNAVAILABLE"
    const val OUT_OF_STOCK = "OUT_OF_STOCK"
    const val QUANTITY_REDUCED = "QUANTITY_REDUCED"
    const val MAX_QUANTITY = "MAX_QUANTITY"
    const val PURCHASE_LIMIT_REACHED = "PURCHASE_LIMIT_REACHED"
    const val COOLDOWN_ACTIVE = "COOLDOWN_ACTIVE"
    const val REQUIREMENT_NOT_MET = "REQUIREMENT_NOT_MET"
    const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val ALREADY_OWNED = "ALREADY_OWNED"
    const val FIELD_REQUIRED = "FIELD_REQUIRED"
    const val FIELD_INVALID = "FIELD_INVALID"
    const val SERVER_REQUIRED = "SERVER_REQUIRED"
    const val SERVER_UNAVAILABLE = "SERVER_UNAVAILABLE"
    const val GIFT_NOT_ALLOWED = "GIFT_NOT_ALLOWED"
    const val LOGIN_REQUIRED = "LOGIN_REQUIRED"
    const val SUBSCRIPTION_MUST_BE_ALONE = "SUBSCRIPTION_MUST_BE_ALONE"

    /** Codes that make a line impossible to price: the product or the variant to charge cannot be resolved. */
    val BLOCKING = setOf(PRODUCT_UNAVAILABLE, VARIANT_REQUIRED, VARIANT_UNAVAILABLE)
}

/** One custom field of a product (`market_product_field`); [options] are the allowed `value`s of a `SELECT`. */
class RuleField(
    val key: String,
    val type: ProductFieldType,
    val required: Boolean,
    val options: List<String> = emptyList(),
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null
)

/** A variant row as the rules need it. */
class RuleVariant(val id: Long, val productId: Long, val active: Boolean, val deleted: Boolean, val stock: Int?)

/** A bundle child: [product] is `null` when the child row is gone, [variantId] 0 = no fixed variant. */
class RuleChild(val product: RuleProduct?, val variantId: Long, val quantity: Int)

/** The tier facts of a product in a tiered category (`market_category.tiered`, `market_product.tierRank`). */
class RuleTier(val categoryId: Long, val rank: Int)

/**
 * A product as the rules see it. [categoryActive] is true for a product without a category and for one whose whole
 * category chain is `ACTIVE`; [requiredProducts] holds only ids of products that still exist (a deleted prerequisite is
 * ignored, 06 section 6.4); [buyerChoiceServer] is true when the product (or a bundle child) has an action with
 * `serverMode = BUYER_CHOICE`.
 */
class RuleProduct(
    val id: Long,
    val kind: ProductKind = ProductKind.STANDARD,
    val billingMode: BillingMode = BillingMode.ONE_TIME,
    val status: MarketStatus = MarketStatus.ACTIVE,
    val deleted: Boolean = false,
    val categoryActive: Boolean = true,
    val durationType: ProductDurationType = ProductDurationType.LIFETIME,
    val durationStart: Long? = null,
    val durationExpiry: Long? = null,
    val hasVariants: Boolean = false,
    val variants: Map<Long, RuleVariant> = emptyMap(),
    val stock: Int? = null,
    val maxQuantityPerOrder: Int? = null,
    val limitPerPlayer: Int? = null,
    val cooldownSeconds: Long? = null,
    val requiredProducts: List<Long> = emptyList(),
    val requireOnlyOne: Boolean = false,
    val requiredPermission: String? = null,
    val allowGift: Boolean = true,
    val tier: RuleTier? = null,
    val fields: List<RuleField> = emptyList(),
    val serverChoices: List<Long> = emptyList(),
    val buyerChoiceServer: Boolean = false,
    val children: List<RuleChild> = emptyList()
) {
    /** `status = ACTIVE`, not deleted, category chain active, inside the `TEMPORARY` window (06 section 6.3). */
    fun sellable(now: Long): Boolean {
        if (deleted || status != MarketStatus.ACTIVE || !categoryActive) return false

        if (durationType == ProductDurationType.TEMPORARY) {
            if (durationStart != null && now < durationStart) return false
            if (durationExpiry != null && now >= durationExpiry) return false
        }

        return true
    }
}

/** One cart line with the product row it points at (`null` = the product does not exist). */
class RuleLine(
    val lineKey: String,
    val productId: Long,
    val variantId: Long,
    val quantity: Int,
    val fieldValues: Map<String, Any?>,
    val targetServerId: Long?,
    val product: RuleProduct?
)

/** What the recipient already used of a product: units counted from order items, and the newest such order (06 section 6.4). */
class ProductUsage(val used: Long, val lastOrderAt: Long?)

/** What the recipient holds: an `ACTIVE` entitlement in a tiered category, or any `ACTIVE` entitlement of a product. */
class OwnedEntitlement(val productId: Long, val tierCategoryId: Long?, val tierRank: Int?)

/**
 * Everything outside the lines. [usage] is keyed by product id and covers the recipient's key set (06 section 6.4);
 * [subscribedProductIds] holds the products the recipient has a subscription for in `PENDING, ACTIVE, PAST_DUE,
 * PAUSED`; [existingServerIds] is the subset of the requested target servers that still exist.
 */
class RuleContext(
    val now: Long,
    val loggedIn: Boolean,
    val isGift: Boolean,
    val hasPermission: (String) -> Boolean = { false },
    val existingServerIds: Set<Long> = emptySet(),
    val usage: Map<Long, ProductUsage> = emptyMap(),
    val owned: List<OwnedEntitlement> = emptyList(),
    val subscribedProductIds: Set<Long> = emptySet(),
    /** `creditsEnabled && creditTopUpEnabled` (07 section 8, 14.1): a `CREDIT_PACK` line is unavailable while this is false. */
    val creditPacksEnabled: Boolean = true
)

/** A failing code with the product it belongs to (a bundle line reports the code of a child with the child's id). */
class RuleDetail(val code: String, val productId: Long, val retryAfterSeconds: Long? = null, val limit: Int? = null)

/**
 * The outcome for one line. [priceable] is false when the product or variant cannot be resolved (the line is shown with
 * its errors and no price). [quantity] is the quantity to price (clamped to 1 for a subscription, a timed product and a
 * tiered product: the pricing code never sees more); [maxQuantity] is the most the buyer may put in this line right now.
 * [fieldValues] holds the known keys only (unknown keys are dropped silently), [targetServerId] is `null` unless the product
 * asks for a buyer-chosen server.
 */
class LineVerdict(
    val lineKey: String,
    val errors: List<String>,
    val details: List<RuleDetail>,
    val priceable: Boolean,
    val quantity: Int,
    val maxQuantity: Int,
    val fieldValues: Map<String, Any?>,
    val targetServerId: Long?
)

class RuleMessage(val code: String, val level: String, val lineKey: String? = null)

class RuleResult(val lines: List<LineVerdict>, val messages: List<RuleMessage>) {
    /** No line error and no error-level message. */
    val ok: Boolean get() = lines.all { it.errors.isEmpty() } && messages.none { it.level == "error" }
}

object LineRules {
    const val ERROR = "error"
    const val WARNING = "warning"

    private val USERNAME = Regex("[A-Za-z0-9_]{3,16}")
    private val DISCORD_ID = Regex("[0-9]{17,20}")
    private val INTEGER = Regex("-?[0-9]{1,18}")
    private const val TEXT_MAX = CartLimits.MAX_FIELD_VALUE_LENGTH

    /** The per-line and cart-wide rules, in the order of 06 section 6.3; every failing code of a line is reported. */
    fun evaluate(lines: List<RuleLine>, ctx: RuleContext): RuleResult {
        val states = lines.map { LineState(it) }
        val messages = ArrayList<RuleMessage>()

        for (state in states) staticRules(state, ctx)

        val inCart = states.filter { !it.blocked }.mapNotNull { it.line.product?.id }.toSet()

        stockRules(states, ctx.now)
        limitRules(states, ctx)
        cooldownRules(states, ctx)
        requirementRules(states, ctx, inCart)

        // 09 section 4.1: a subscription is bought alone (an error of the cart, not of one line)
        val subscription = states.any { it.line.product?.billingMode == BillingMode.SUBSCRIPTION && it.line.product.sellable(ctx.now) }

        if (subscription && states.size > 1) messages += RuleMessage(LineCode.SUBSCRIPTION_MUST_BE_ALONE, ERROR)

        for (state in states) {
            if (state.reduced) messages += RuleMessage(LineCode.QUANTITY_REDUCED, WARNING, state.line.lineKey)
        }

        return RuleResult(states.map { it.verdict() }, messages)
    }

    // ---------------------------------------------------------------------------------------------- per line

    private class LineState(val line: RuleLine) {
        val errors = LinkedHashSet<String>()
        val details = ArrayList<RuleDetail>()
        var blocked = false
        var reduced = false
        var quantity = line.quantity
        var perOrderMax = CartLimits.MAX_QUANTITY
        var stockMax = CartLimits.MAX_QUANTITY
        var allowanceMax = CartLimits.MAX_QUANTITY
        var fieldValues: Map<String, Any?> = emptyMap()
        var targetServerId: Long? = null

        fun fail(code: String, productId: Long = line.productId, retryAfter: Long? = null, limit: Int? = null) {
            if (errors.add(code)) details += RuleDetail(code, productId, retryAfter, limit)
        }

        fun block(code: String) {
            blocked = true
            fail(code)
        }

        fun verdict(): LineVerdict {
            val max = if (blocked) 0 else maxOf(0, minOf(perOrderMax, stockMax, allowanceMax, CartLimits.MAX_QUANTITY))

            return LineVerdict(line.lineKey, errors.toList(), details.toList(), !blocked, quantity, max, fieldValues, targetServerId)
        }
    }

    private fun staticRules(s: LineState, ctx: RuleContext) {
        val line = s.line
        val product = line.product

        if (product == null || !product.sellable(ctx.now)) return s.block(LineCode.PRODUCT_UNAVAILABLE)

        // credit packs are sold only while the credit system and its top-up are on (07 section 8 and 14.1)
        if (product.kind == ProductKind.CREDIT_PACK && !ctx.creditPacksEnabled) return s.block(LineCode.PRODUCT_UNAVAILABLE)

        // a bundle is as available as its weakest child (06 section 6.3, last paragraph)
        for (child in product.children) {
            val childProduct = child.product

            if (childProduct == null || !childProduct.sellable(ctx.now) || !childVariantOk(childProduct, child.variantId)) {
                return s.block(LineCode.PRODUCT_UNAVAILABLE)
            }
        }

        // variant
        if (product.hasVariants) {
            if (line.variantId == 0L) {
                s.block(LineCode.VARIANT_REQUIRED)
            } else {
                val variant = product.variants[line.variantId]

                if (variant == null || variant.deleted || !variant.active || variant.productId != product.id) s.block(LineCode.VARIANT_UNAVAILABLE)
            }
        } else if (line.variantId != 0L) {
            s.block(LineCode.VARIANT_UNAVAILABLE)
        }

        if (s.blocked) return

        // custom fields; unknown keys are dropped
        val known = product.fields.associateBy { it.key }
        s.fieldValues = line.fieldValues.filterKeys { it in known }

        for (field in product.fields) {
            when (fieldVerdict(field, line.fieldValues[field.key])) {
                LineCode.FIELD_REQUIRED -> s.fail(LineCode.FIELD_REQUIRED)
                LineCode.FIELD_INVALID -> s.fail(LineCode.FIELD_INVALID)
            }
        }

        // server choice
        if (product.buyerChoiceServer) {
            val target = line.targetServerId

            when {
                target == null -> s.fail(LineCode.SERVER_REQUIRED)
                target !in product.serverChoices || target !in ctx.existingServerIds -> s.fail(LineCode.SERVER_UNAVAILABLE)
                else -> s.targetServerId = target
            }
        }

        if (ctx.isGift && (!product.allowGift || product.billingMode == BillingMode.SUBSCRIPTION)) s.fail(LineCode.GIFT_NOT_ALLOWED)

        if (!ctx.loggedIn && (product.kind == ProductKind.CREDIT_PACK || product.billingMode == BillingMode.SUBSCRIPTION)) {
            s.fail(LineCode.LOGIN_REQUIRED)
        }

        // quantity: a subscription is always one (09 section 4.1), timed and tiered products one at most (06 section 6.3)
        s.perOrderMax = product.maxQuantityPerOrder?.let { maxOf(0, it) } ?: CartLimits.MAX_QUANTITY

        if (product.billingMode == BillingMode.SUBSCRIPTION) {
            s.perOrderMax = 1

            if (s.quantity != 1) {
                s.quantity = 1
                s.reduced = true
            }
        } else if (product.billingMode == BillingMode.TIMED || product.tier != null) {
            s.perOrderMax = minOf(s.perOrderMax, 1)
        }

        if (s.quantity > s.perOrderMax) s.fail(LineCode.MAX_QUANTITY)

        // a tiered or timed line is priced once whatever the request said; the error above keeps it from being bought
        if ((product.billingMode == BillingMode.TIMED || product.tier != null) && s.quantity > 1) s.quantity = 1

        val permission = product.requiredPermission

        if (!permission.isNullOrBlank() && !ctx.hasPermission(permission)) s.fail(LineCode.PERMISSION_REQUIRED)

        val tier = product.tier

        if (tier != null && ctx.owned.any { it.tierCategoryId == tier.categoryId && (it.tierRank ?: Int.MIN_VALUE) >= tier.rank }) {
            s.fail(LineCode.ALREADY_OWNED)
        } else if (product.billingMode == BillingMode.SUBSCRIPTION && product.id in ctx.subscribedProductIds) {
            s.fail(LineCode.ALREADY_OWNED)
        }
    }

    private fun childVariantOk(child: RuleProduct, variantId: Long): Boolean {
        if (variantId == 0L) return true

        val variant = child.variants[variantId] ?: return false

        return !variant.deleted && variant.active && variant.productId == child.id
    }

    // ------------------------------------------------------------------------------------------ aggregated rules

    /** One demand of a line on a product: the line itself, or a bundle child ([perUnit] = child quantity). */
    private class Demand(val state: LineState, val product: RuleProduct, val variantId: Long, val perUnit: Int) {
        val quantity: Long get() = state.quantity.toLong() * perUnit
    }

    private fun demands(states: List<LineState>): List<Demand> {
        val out = ArrayList<Demand>()

        for (state in states) {
            if (state.blocked) continue

            val product = state.line.product ?: continue

            out += Demand(state, product, state.line.variantId, 1)

            for (child in product.children) child.product?.let { out += Demand(state, it, child.variantId, child.quantity) }
        }

        return out
    }

    /** Stock is tracked per variant (when the demand names one) or per product; lines consume it in cart order (06 section 3). */
    private fun stockRules(states: List<LineState>, now: Long) {
        val remaining = HashMap<Pair<Char, Long>, Long>()

        for (demand in demands(states)) {
            val variant = if (demand.variantId != 0L) demand.product.variants[demand.variantId] else null
            val key = if (variant != null) 'v' to variant.id else 'p' to demand.product.id
            val stock = if (variant != null) variant.stock else demand.product.stock

            if (stock == null) continue

            val available = remaining.getOrPut(key) { maxOf(0, stock).toLong() }
            val line = demand.state
            val perUnit = demand.perUnit.toLong()

            line.stockMax = minOf(line.stockMax.toLong(), available / perUnit).toInt()

            if (demand.quantity > available) {
                line.fail(if (available < perUnit) LineCode.OUT_OF_STOCK else LineCode.MAX_QUANTITY, demand.product.id)
            }

            remaining[key] = maxOf(0L, available - demand.quantity)
        }
    }

    /** `used + requested <= limitPerPlayer`, summed per product over the whole cart; a timed chain is an extension (06 section 6.4). */
    private fun limitRules(states: List<LineState>, ctx: RuleContext) {
        val byProduct = demands(states).groupBy { it.product.id }

        for ((productId, list) in byProduct) {
            val product = list.first().product
            val limit = product.limitPerPlayer ?: continue

            if (product.billingMode == BillingMode.TIMED && ctx.owned.any { it.productId == productId }) continue

            val used = ctx.usage[productId]?.used ?: 0L
            val allowance = maxOf(0L, limit.toLong() - used)
            val requested = list.sumOf { it.quantity }

            for (demand in list) {
                demand.state.allowanceMax = minOf(demand.state.allowanceMax.toLong(), allowance / demand.perUnit).toInt()
            }

            if (requested > allowance) list.forEach { it.state.fail(LineCode.PURCHASE_LIMIT_REACHED, productId, limit = limit) }
        }
    }

    /** `now - last >= cooldownSeconds * 1000`; a pending order starts the cooldown (it is in [ProductUsage]). */
    private fun cooldownRules(states: List<LineState>, ctx: RuleContext) {
        for (demand in demands(states)) {
            val seconds = demand.product.cooldownSeconds ?: continue

            if (seconds <= 0) continue

            val last = ctx.usage[demand.product.id]?.lastOrderAt ?: continue
            val readyAt = last + seconds * 1000

            if (ctx.now < readyAt) {
                demand.state.fail(LineCode.COOLDOWN_ACTIVE, demand.product.id, retryAfter = retryAfter(readyAt, ctx.now))
                demand.state.allowanceMax = 0
            }
        }
    }

    /** `ceil((readyAt - now) / 1000)` seconds. */
    fun retryAfter(readyAt: Long, now: Long): Long = Math.floorDiv(readyAt - now + 999, 1000L)

    /**
     * A required product is satisfied by an `ACTIVE` entitlement of the recipient or by being in the same cart (buying the
     * prerequisite together with the product is allowed); `requireOnlyOne` = at least one, else all (06 section 6.4).
     */
    private fun requirementRules(states: List<LineState>, ctx: RuleContext, inCart: Set<Long>) {
        val held = ctx.owned.map { it.productId }.toSet()

        for (state in states) {
            if (state.blocked) continue

            val product = state.line.product ?: continue
            val required = product.requiredProducts.filter { it != product.id }.distinct()

            if (required.isEmpty()) continue

            val satisfied = required.map { it in held || it in inCart }
            val ok = if (product.requireOnlyOne) satisfied.any { it } else satisfied.all { it }

            if (!ok) state.fail(LineCode.REQUIREMENT_NOT_MET, product.id)
        }
    }

    // -------------------------------------------------------------------------------------------------- fields

    /**
     * `null` = fine, else `FIELD_REQUIRED` / `FIELD_INVALID` (06 section 6.3). A required field that is empty (absent, blank,
     * or an unticked `CHECKBOX`) is `FIELD_REQUIRED`; an optional empty one is fine. Values are scalars: a string, a number
     * or a boolean.
     */
    fun fieldVerdict(field: RuleField, value: Any?): String? {
        val empty = value == null || (value is String && value.isBlank()) || (field.type == ProductFieldType.CHECKBOX && asBoolean(value) == false)

        if (empty) return if (field.required) LineCode.FIELD_REQUIRED else null

        if (value !is String && value !is Number && value !is Boolean) return LineCode.FIELD_INVALID

        val text = scalarText(value)

        if (text.length > TEXT_MAX) return LineCode.FIELD_INVALID

        val valid = when (field.type) {
            ProductFieldType.NUMBER -> numberValid(field, value)
            ProductFieldType.SELECT -> text in field.options
            ProductFieldType.CHECKBOX -> asBoolean(value) != null
            ProductFieldType.USERNAME -> USERNAME.matches(text)
            ProductFieldType.EMAIL -> BuyerValidator.isValidEmail(text.trim().lowercase())
            ProductFieldType.DISCORD_ID -> DISCORD_ID.matches(text)
            ProductFieldType.TEXT -> textValid(field, text)
        }

        return if (valid) null else LineCode.FIELD_INVALID
    }

    private fun numberValid(field: RuleField, value: Any): Boolean {
        val number = when (value) {
            is Boolean -> return false
            is String -> value.trim().takeIf { INTEGER.matches(it) }?.toLongOrNull()
            is Int -> value.toLong()
            is Long -> value
            is Number -> value.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() && Math.abs(it) < 1e18 }?.toLong()
            else -> null
        } ?: return false

        if (field.minValue != null && number < field.minValue) return false
        if (field.maxValue != null && number > field.maxValue) return false

        return true
    }

    private fun textValid(field: RuleField, text: String): Boolean {
        if (text.any { it == '\r' || it == '\n' || it == '\u0000' }) return false

        val length = text.codePointCount(0, text.length)

        if (field.minLength != null && length < field.minLength) return false

        val max = minOf(field.maxLength ?: TEXT_MAX, TEXT_MAX)

        if (length > max) return false

        val pattern = field.pattern

        return pattern.isNullOrEmpty() || SafeRegex.test(pattern, text) == SafeRegex.Verdict.MATCH
    }

    private fun asBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    /** A scalar the way the line key writes it: numbers without a trailing `.0`. */
    private fun scalarText(value: Any): String = when (value) {
        is Double -> if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e18) value.toLong().toString() else value.toString()
        is Float -> if (value == Math.floor(value.toDouble()).toFloat()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}
