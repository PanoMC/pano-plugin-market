package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.spi.common.Currencies
import com.panomc.plugins.market.spi.common.SafeRegex
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The product rules of 04 section 5, 01 sections 2.2-2.5 and 10 section 2.1, as one pure function: the merged product
 * and the submitted set parts go in, `fieldErrors` (`{dotted.path: CODE}`) come out; empty = the save may proceed.
 * No database, no clock. What needs the database (the category exists, a bundle child exists, the slug is free, a
 * variant id belongs to the product) is checked by `CatalogService` and reported with the same codes.
 *
 * Money in the product is x100; `baseCurrency` decides whether a price must be a whole unit (zero-decimal currencies
 * such as JPY), and each row of `prices[]` is judged by its own currency.
 */
object ProductRules {
    const val MAX_MONEY = 9_999_999_999L
    const val MAX_STOCK = 1_000_000_000
    const val MAX_DESCRIPTION = 100_000
    const val MAX_VARIANTS = 200
    const val MAX_FIELDS = 30
    const val MAX_BUNDLE_ITEMS = 50
    const val MAX_PRICES = 400
    const val MAX_PERIOD_COUNT = 100_000
    const val MAX_QUANTITY = 1000
    const val MAX_SERVER_CHOICES = 100

    private val SKU = Regex("^[A-Za-z0-9._/-]+$")
    private val HS_CODE = Regex("^[0-9]{6,10}$")
    private val KEY = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val VALUE_KEY = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val DISCORD_ID = Regex("^[0-9]{17,20}$")
    private val SUBSCRIPTION_UNITS = setOf(PeriodUnit.DAY, PeriodUnit.WEEK, PeriodUnit.MONTH, PeriodUnit.YEAR)
    private val PRODUCT_STATUSES = setOf(MarketStatus.ACTIVE, MarketStatus.INACTIVE, MarketStatus.ARCHIVED)

    /** What the rules need besides the product: the store's base currency, create or update, and the variant count after the save. */
    class Context(
        val baseCurrency: String,
        val create: Boolean,
        val categoryTiered: Boolean = false,
        /** Live (not soft-deleted) variants the product has after the save. */
        val liveVariantCount: Int = 0,
        /** Bundle rows the product has after the save. */
        val bundleItemCount: Int = 0
    )

    fun validate(p: MarketProduct, input: ProductInput, ctx: Context): Map<String, String> {
        val e = linkedMapOf<String, String>()
        e.putAll(input.parseErrors)

        scalars(p, ctx, e)
        shipping(p, e)
        kindAndBilling(p, ctx, e)

        input.variants?.let { variants(it, p, ctx, e) }
        input.fields?.let { fields(it, e) }
        input.bundleItems?.let { bundleItems(it, e) }
        input.prices?.let { prices(it, "prices", e, allowVariantRef = true) }
        input.variants?.forEachIndexed { index, v -> v.prices?.let { prices(it, "variants.$index.prices", e, allowVariantRef = false) } }
        crossPriceDuplicates(input, e)
        checkBaseCurrency(input, ctx.baseCurrency, e)

        if (ctx.create || input.variants != null || input.has("hasVariants") || input.has("kind")) {
            if (p.hasVariants && ctx.liveVariantCount < 1) e.putIfAbsent("variants", "REQUIRED")
            if (!p.hasVariants && ctx.liveVariantCount > 0) e.putIfAbsent("variants", "NOT_ALLOWED")
        }

        if (p.kind == ProductKind.BUNDLE && ctx.bundleItemCount < 1) e.putIfAbsent("bundleItems", "REQUIRED")
        if (p.kind != ProductKind.BUNDLE && ctx.bundleItemCount > 0) e.putIfAbsent("bundleItems", "NOT_ALLOWED")

        return e
    }

    // ----- scalars ---------------------------------------------------------------------------------------------

    private fun scalars(p: MarketProduct, ctx: Context, e: MutableMap<String, String>) {
        text("name", p.name, 255, e, required = true)
        text("shortDescription", p.shortDescription, 512, e)
        text("metaTitle", p.metaTitle, 255, e)
        text("metaDescription", p.metaDescription, 512, e)
        text("icon", p.icon, 255, e)
        text("requiredPermission", p.requiredPermission, 255, e)

        if ((p.description?.length ?: 0) > MAX_DESCRIPTION) e["description"] = "TOO_LONG"

        SlugRules.check(p.slug)?.let { e["slug"] = it }
        if (p.slug.length > SlugRules.COLUMN_LENGTH) e["slug"] = "TOO_LONG"

        if (p.status !in PRODUCT_STATUSES) e["status"] = "INVALID"

        money("price", p.price, ctx.baseCurrency, e)
        p.compareAtPrice?.let { compare ->
            money("compareAtPrice", compare, ctx.baseCurrency, e)
            if (!e.containsKey("compareAtPrice") && compare <= p.price) e["compareAtPrice"] = "MUST_EXCEED_PRICE"
        }
        if (p.creditPrice < 0 || p.creditPrice > MAX_MONEY) e["creditPrice"] = "OUT_OF_RANGE"

        p.stock?.let { if (ctx.create && (it < 0 || it > MAX_STOCK)) e["stock"] = "OUT_OF_RANGE" }

        p.vatPercent?.let { if (it < 0 || it > 10_000) e["vatPercent"] = "OUT_OF_RANGE" }

        if (p.durationStart != null && p.durationExpiry != null && p.durationExpiry <= p.durationStart) e["durationExpiry"] = "INVALID_RANGE"

        p.limitPerPlayer?.let { if (it < 1) e["limitPerPlayer"] = "OUT_OF_RANGE" }
        p.maxQuantityPerOrder?.let { if (it < 1 || it > MAX_QUANTITY) e["maxQuantityPerOrder"] = "OUT_OF_RANGE" }
        p.cooldownSeconds?.let { if (it < 0 || it > 315_360_000L) e["cooldownSeconds"] = "OUT_OF_RANGE" }
        p.tierRank?.let { if (it < 0 || it > 10_000) e["tierRank"] = "OUT_OF_RANGE" }
        if (ctx.categoryTiered && p.tierRank == null) e["tierRank"] = "REQUIRED"

        p.serverChoices?.let { if (!validServerChoices(it)) e["serverChoices"] = "INVALID" }
        p.variantOptions?.let { variantOptionErrors(it, e) }
    }

    private fun text(key: String, value: String?, max: Int, e: MutableMap<String, String>, required: Boolean = false) {
        when {
            value == null || value.isBlank() -> if (required) e[key] = "REQUIRED"
            value.length > max -> e[key] = "TOO_LONG"
            value.any { it < ' ' && it != '\n' && it != '\t' && it != '\r' } -> e[key] = "INVALID"
        }
    }

    private fun money(key: String, value: Long, currency: String, e: MutableMap<String, String>) {
        when {
            value < 0 || value > MAX_MONEY -> e[key] = "OUT_OF_RANGE"
            !wholeUnitsOk(value, currency) -> e[key] = "NOT_WHOLE_UNITS"
        }
    }

    /** A zero-decimal currency (JPY) carries whole units: the x100 amount must be a multiple of 100. */
    fun wholeUnitsOk(amount: Long, currency: String): Boolean {
        val exponent = runCatching { Currencies.exponent(currency) }.getOrDefault(2)

        return exponent != 0 || amount % 100L == 0L
    }

    private fun validServerChoices(json: String): Boolean = try {
        val array = JsonArray(json)

        array.size() <= MAX_SERVER_CHOICES && array.all { it is Number && it.toLong() >= 1 }
    } catch (e: Exception) {
        false
    }

    // ----- shipping data (10 section 2.1) -----------------------------------------------------------------------

    private fun shipping(p: MarketProduct, e: MutableMap<String, String>) {
        if (p.physical && (p.kind != ProductKind.STANDARD || p.billingMode != BillingMode.ONE_TIME)) e["physical"] = "PHYSICAL_NOT_ALLOWED"

        if (p.physical && p.weightGrams == null) e["weightGrams"] = "REQUIRED"
        p.weightGrams?.let { if (it < 1 || it > 1_000_000) e["weightGrams"] = "OUT_OF_RANGE" }

        val dimensions = listOf("lengthMm" to p.lengthMm, "widthMm" to p.widthMm, "heightMm" to p.heightMm)
        val given = dimensions.count { it.second != null }

        if (given in 1..2) dimensions.filter { it.second == null }.forEach { e.putIfAbsent(it.first, "DIMENSIONS_INCOMPLETE") }
        dimensions.forEach { (key, value) -> if (value != null && (value < 1 || value > 5000)) e[key] = "OUT_OF_RANGE" }

        p.hsCode?.let { if (!HS_CODE.matches(it)) e["hsCode"] = "INVALID" }
        p.originCountry?.let { if (!CountryCodes.isValid(it)) e["originCountry"] = "INVALID" }
        p.sku?.let { if (it.length > 64 || !SKU.matches(it)) e["sku"] = "INVALID" }
    }

    // ----- kind and billing matrix (01 section 2.2, 04 section 5) -----------------------------------------------

    private fun kindAndBilling(p: MarketProduct, ctx: Context, e: MutableMap<String, String>) {
        val creditFree = p.kind == ProductKind.CREDIT_PACK || p.billingMode == BillingMode.SUBSCRIPTION

        if (creditFree && p.creditPrice != 0L) e["creditPrice"] = "MUST_BE_ZERO"

        if (p.kind == ProductKind.CREDIT_PACK) {
            if (p.creditAmount == null || p.creditAmount <= 0) e["creditAmount"] = "REQUIRED"
            else if (p.creditAmount > MAX_MONEY) e["creditAmount"] = "OUT_OF_RANGE"
            if (p.billingMode != BillingMode.ONE_TIME) e["billingMode"] = "NOT_ALLOWED"
            if (p.hasVariants) e["hasVariants"] = "NOT_ALLOWED"
        }

        when (p.billingMode) {
            BillingMode.ONE_TIME -> Unit
            BillingMode.TIMED, BillingMode.SUBSCRIPTION -> {
                if (p.periodUnit == null) e["periodUnit"] = "REQUIRED"
                else if (p.billingMode == BillingMode.SUBSCRIPTION && p.periodUnit !in SUBSCRIPTION_UNITS) e["periodUnit"] = "INVALID_FOR_SUBSCRIPTION"

                if (p.periodCount == null) e["periodCount"] = "REQUIRED"
                else if (p.periodCount < 1 || p.periodCount > MAX_PERIOD_COUNT) e["periodCount"] = "OUT_OF_RANGE"

                p.subscriptionMaxCycles?.let { if (it < 1) e["subscriptionMaxCycles"] = "OUT_OF_RANGE" }
            }
        }
    }

    // ----- variantOptions -------------------------------------------------------------------------------------

    /** Axis key -> allowed value keys of a stored / submitted `variantOptions` JSON, or `null` when it is malformed. */
    fun axes(json: String?): Map<String, Set<String>>? {
        if (json.isNullOrBlank()) return emptyMap()

        val errors = linkedMapOf<String, String>()
        val axes = readAxes(json, errors)

        return if (errors.isEmpty()) axes else null
    }

    private fun variantOptionErrors(json: String, e: MutableMap<String, String>) {
        readAxes(json, e)
    }

    private fun readAxes(json: String, e: MutableMap<String, String>): Map<String, Set<String>> {
        val result = linkedMapOf<String, Set<String>>()
        val array = try {
            JsonArray(json)
        } catch (ex: Exception) {
            e["variantOptions"] = "INVALID"
            return result
        }

        if (array.size() > 5) {
            e["variantOptions"] = "TOO_MANY"
            return result
        }

        array.forEachIndexed { index, element ->
            val path = "variantOptions.$index"
            val axis = element as? JsonObject

            if (axis == null) {
                e[path] = "INVALID"
                return@forEachIndexed
            }

            val key = axis.getValue("key") as? String
            if (key == null || !KEY.matches(key)) e["$path.key"] = "INVALID"
            else if (key in result) e["$path.key"] = "DUPLICATE"

            val label = axis.getValue("label") as? String
            if (label.isNullOrBlank()) e["$path.label"] = "REQUIRED" else if (label.length > 255) e["$path.label"] = "TOO_LONG"

            val values = axis.getValue("values") as? JsonArray
            val keys = linkedSetOf<String>()

            if (values == null || values.isEmpty) e["$path.values"] = "REQUIRED"
            else if (values.size() > 50) e["$path.values"] = "TOO_MANY"
            else values.forEachIndexed { vi, raw ->
                val value = raw as? JsonObject
                val valueKey = value?.getValue("key") as? String
                val valueLabel = value?.getValue("label") as? String

                if (valueKey == null || !VALUE_KEY.matches(valueKey)) e["$path.values.$vi.key"] = "INVALID"
                else if (!keys.add(valueKey)) e["$path.values.$vi.key"] = "DUPLICATE"

                if (valueLabel.isNullOrBlank()) e["$path.values.$vi.label"] = "REQUIRED"
                else if (valueLabel.length > 255) e["$path.values.$vi.label"] = "TOO_LONG"
            }

            if (key != null && KEY.matches(key) && key !in result) result[key] = keys
        }

        return result
    }

    // ----- variants (01 section 2.3) ----------------------------------------------------------------------------

    private fun variants(list: List<VariantDraft>, p: MarketProduct, ctx: Context, e: MutableMap<String, String>) {
        if (list.size > MAX_VARIANTS) {
            e["variants"] = "TOO_MANY"
            return
        }

        val axes = axes(p.variantOptions)
        val skus = mutableSetOf<String>()
        val combinations = mutableSetOf<Map<String, String>>()
        val ids = mutableSetOf<Long>()

        list.forEachIndexed { index, v ->
            val path = "variants.$index"

            v.id?.let { if (!ids.add(it)) e["$path.id"] = "DUPLICATE" }

            text("$path.name", v.name, 255, e, required = true)

            v.sku?.let {
                if (it.length > 64 || !SKU.matches(it)) e["$path.sku"] = "INVALID"
                else if (!skus.add(it)) e["$path.sku"] = "DUPLICATE"
            }

            v.price?.let { money("$path.price", it, ctx.baseCurrency, e) }
            v.creditPrice?.let { if (it < 0 || it > MAX_MONEY) e["$path.creditPrice"] = "OUT_OF_RANGE" }
            if (v.creditPrice != null && v.creditPrice != 0L && (p.kind == ProductKind.CREDIT_PACK || p.billingMode == BillingMode.SUBSCRIPTION)) {
                e["$path.creditPrice"] = "MUST_BE_ZERO"
            }
            v.compareAtPrice?.let { compare ->
                if (compare < 0 || compare > MAX_MONEY) e["$path.compareAtPrice"] = "OUT_OF_RANGE"
                else if (!wholeUnitsOk(compare, ctx.baseCurrency)) e["$path.compareAtPrice"] = "NOT_WHOLE_UNITS"
                else if (compare <= (v.price ?: p.price)) e["$path.compareAtPrice"] = "MUST_EXCEED_PRICE"
            }

            v.stock?.let { if (it < 0 || it > MAX_STOCK) e["$path.stock"] = "OUT_OF_RANGE" }
            v.weightGrams?.let { if (it < 1 || it > 1_000_000) e["$path.weightGrams"] = "OUT_OF_RANGE" }

            v.periodCount?.let {
                if (p.billingMode == BillingMode.ONE_TIME) e["$path.periodCount"] = "NOT_APPLICABLE"
                else if (it < 1 || it > MAX_PERIOD_COUNT) e["$path.periodCount"] = "OUT_OF_RANGE"
            }

            if (v.status != MarketStatus.ACTIVE && v.status != MarketStatus.INACTIVE) e["$path.status"] = "INVALID"

            v.attributes?.let { attributes ->
                if (attributes.size > 20) e["$path.attributes"] = "TOO_MANY"
                else if (attributes.any { (k, value) -> !KEY.matches(k) || value.length > 255 || value.any { c -> c < ' ' } }) e["$path.attributes"] = "INVALID"
            }

            if (axes != null) {
                val chosen = v.optionValues.orEmpty()

                if (axes.isEmpty()) {
                    if (chosen.isNotEmpty()) e["$path.optionValues"] = "INVALID"
                } else if (chosen.keys != axes.keys || chosen.any { (axis, value) -> value !in axes.getValue(axis) }) {
                    e["$path.optionValues"] = "INVALID"
                } else if (!combinations.add(chosen)) {
                    e["$path.optionValues"] = "DUPLICATE"
                }
            }
        }
    }

    // ----- fields (01 section 2.5) ----------------------------------------------------------------------------

    private fun fields(list: List<FieldDraft>, e: MutableMap<String, String>) {
        if (list.size > MAX_FIELDS) {
            e["fields"] = "TOO_MANY"
            return
        }

        val keys = mutableSetOf<String>()
        val ids = mutableSetOf<Long>()

        list.forEachIndexed { index, f ->
            val path = "fields.$index"

            f.id?.let { if (!ids.add(it)) e["$path.id"] = "DUPLICATE" }

            if (!KEY.matches(f.fieldKey)) e["$path.fieldKey"] = "INVALID"
            else if (!keys.add(f.fieldKey)) e["$path.fieldKey"] = "DUPLICATE"

            text("$path.label", f.label, 255, e, required = true)
            text("$path.helpText", f.helpText, 512, e)
            text("$path.placeholder", f.placeholder, 255, e)
            text("$path.defaultValue", f.defaultValue, 255, e)

            val isText = f.type == ProductFieldType.TEXT
            val isNumber = f.type == ProductFieldType.NUMBER

            if (f.type == ProductFieldType.SELECT) {
                val options = f.options

                when {
                    options.isNullOrEmpty() -> e["$path.options"] = "REQUIRED"
                    options.size > 100 -> e["$path.options"] = "TOO_MANY"
                    options.any { (value, label) -> value.isBlank() || value.length > 255 || label.isBlank() || label.length > 255 } -> e["$path.options"] = "INVALID"
                    options.map { it.first }.toSet().size != options.size -> e["$path.options"] = "DUPLICATE"
                }
            } else if (!f.options.isNullOrEmpty()) {
                e["$path.options"] = "NOT_APPLICABLE"
            }

            if (f.pattern != null) {
                when {
                    !isText -> e["$path.pattern"] = "NOT_APPLICABLE"
                    f.pattern.length > 255 -> e["$path.pattern"] = "TOO_LONG"
                    SafeRegex.checkGrammar(f.pattern) != null -> e["$path.pattern"] = "INVALID_PATTERN"
                }
            }

            if (!isText && (f.minLength != null || f.maxLength != null)) e["$path.${if (f.minLength != null) "minLength" else "maxLength"}"] = "NOT_APPLICABLE"
            if (isText) {
                f.maxLength?.let { if (it < 1 || it > 128) e["$path.maxLength"] = "OUT_OF_RANGE" }
                f.minLength?.let { if (it < 0 || it > 128) e["$path.minLength"] = "OUT_OF_RANGE" }
                if (f.minLength != null && f.maxLength != null && f.minLength > f.maxLength && !e.containsKey("$path.minLength")) e["$path.minLength"] = "INVALID_RANGE"
            }

            if (!isNumber && (f.minValue != null || f.maxValue != null)) e["$path.${if (f.minValue != null) "minValue" else "maxValue"}"] = "NOT_APPLICABLE"
            if (isNumber && f.minValue != null && f.maxValue != null && f.minValue > f.maxValue) e["$path.minValue"] = "INVALID_RANGE"

            f.defaultValue?.let { default -> if (!e.containsKey("$path.defaultValue") && !defaultFits(f, default)) e["$path.defaultValue"] = "INVALID" }
        }
    }

    private fun defaultFits(f: FieldDraft, value: String): Boolean = when (f.type) {
        ProductFieldType.TEXT -> (f.maxLength == null || value.length <= f.maxLength) && (f.minLength == null || value.length >= f.minLength)
        ProductFieldType.NUMBER -> value.toLongOrNull()?.let { (f.minValue == null || it >= f.minValue) && (f.maxValue == null || it <= f.maxValue) } ?: false
        ProductFieldType.SELECT -> f.options?.any { it.first == value } ?: false
        ProductFieldType.CHECKBOX -> value == "true" || value == "false"
        ProductFieldType.USERNAME -> Regex("^[A-Za-z0-9_]{3,16}$").matches(value)
        ProductFieldType.EMAIL -> value.length <= 254 && value.count { it == '@' } == 1 && value.none { it.isWhitespace() || it in ",;<>" }
        ProductFieldType.DISCORD_ID -> DISCORD_ID.matches(value)
    }

    // ----- bundle items ---------------------------------------------------------------------------------------

    private fun bundleItems(list: List<BundleItemDraft>, e: MutableMap<String, String>) {
        if (list.size > MAX_BUNDLE_ITEMS) {
            e["bundleItems"] = "TOO_MANY"
            return
        }

        val seen = mutableSetOf<Pair<Long, Long>>()

        list.forEachIndexed { index, item ->
            val path = "bundleItems.$index"

            if (item.productId < 1) e.putIfAbsent("$path.productId", "INVALID")
            if (item.variantId < 0) e["$path.variantId"] = "INVALID"
            if (item.quantity < 1 || item.quantity > MAX_QUANTITY) e["$path.quantity"] = "OUT_OF_RANGE"
            if (!seen.add(item.productId to item.variantId)) e["$path.productId"] = "DUPLICATE"
        }
    }

    // ----- prices (01 section 2.4) ----------------------------------------------------------------------------

    private fun prices(list: List<PriceDraft>, root: String, e: MutableMap<String, String>, allowVariantRef: Boolean) {
        if (list.size > MAX_PRICES) {
            e[root] = "TOO_MANY"
            return
        }

        val seen = mutableSetOf<Pair<Long, String>>()

        list.forEachIndexed { index, row ->
            val path = "$root.$index"

            if (!Currencies.isSupported(row.currency)) e["$path.currency"] = "INVALID"
            else if (!seen.add((row.variantId ?: 0L) to row.currency)) e["$path.currency"] = "DUPLICATE"

            if (!allowVariantRef && row.variantId != null) e["$path.variantId"] = "NOT_APPLICABLE"
            if (row.variantId != null && row.variantId < 0) e["$path.variantId"] = "INVALID"

            if (row.price < 0 || row.price > MAX_MONEY) e["$path.price"] = "OUT_OF_RANGE"
            else if (Currencies.isSupported(row.currency) && !wholeUnitsOk(row.price, row.currency)) e["$path.price"] = "NOT_WHOLE_UNITS"

            row.compareAtPrice?.let { compare ->
                if (compare < 0 || compare > MAX_MONEY) e["$path.compareAtPrice"] = "OUT_OF_RANGE"
                else if (Currencies.isSupported(row.currency) && !wholeUnitsOk(compare, row.currency)) e["$path.compareAtPrice"] = "NOT_WHOLE_UNITS"
                else if (compare <= row.price) e["$path.compareAtPrice"] = "MUST_EXCEED_PRICE"
            }
        }
    }

    /** The base currency never appears in `prices[]` (it is the product's own price), and a price is given once per (variant, currency) across both forms. */
    private fun checkBaseCurrency(input: ProductInput, baseCurrency: String, e: MutableMap<String, String>) {
        input.prices?.forEachIndexed { i, row -> if (row.currency == baseCurrency) e["prices.$i.currency"] = "BASE_CURRENCY" }
        input.variants?.forEachIndexed { vi, v ->
            v.prices?.forEachIndexed { i, row -> if (row.currency == baseCurrency) e["variants.$vi.prices.$i.currency"] = "BASE_CURRENCY" }
        }
    }

    private fun crossPriceDuplicates(input: ProductInput, e: MutableMap<String, String>) {
        val top = input.prices ?: return
        val topKeys = top.map { (it.variantId ?: 0L) to it.currency }.toSet()

        input.variants?.forEachIndexed { vi, v ->
            val id = v.id ?: return@forEachIndexed

            v.prices?.forEachIndexed { i, row -> if ((id to row.currency) in topKeys) e["variants.$vi.prices.$i.currency"] = "DUPLICATE" }
        }
    }
}
