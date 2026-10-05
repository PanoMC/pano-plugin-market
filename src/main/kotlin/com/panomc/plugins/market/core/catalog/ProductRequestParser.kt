package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.ProductFieldType
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.util.HtmlSanitizer
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.ProductDurationType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads the body of POST `/products` and PUT `/products/:id` (04 section 5) into a [ProductInput]. Pure: no database,
 * no file access, never throws for a bad value; everything wrong lands in [ProductInput.parseErrors] under the dotted
 * path of the field (`price`, `variants.2.name`, `fields.0.options`). Values may be typed (JSON numbers and booleans) or
 * strings (a multipart form carries only strings); a blank string or JSON `null` clears a nullable field.
 *
 * Nothing is validated against business rules here (ranges, type matrix, reserved slugs): that is [ProductRules].
 */
object ProductRequestParser {
    private const val MAX_MONEY_DECIMAL = 1.0E12

    /**
     * [data] is the form / JSON object; [productImage] and [variantImages] (variant index -> stored file name) describe
     * the file parts the route already stored.
     */
    fun parse(
        data: JsonObject,
        productImage: ImageChange = ImageChange.Keep,
        variantImages: Map<Int, String> = emptyMap()
    ): ProductInput {
        val errors = linkedMapOf<String, String>()
        val r = Reader(data, "", errors)
        val s = linkedMapOf<String, Any?>()

        if (r.has("name")) s["name"] = r.string("name") ?: ""
        if (r.has("slug")) s["slug"] = r.string("slug")
        if (r.has("description")) {
            // 11 section 6.1: the 100 000 character limit is judged on the raw text, the stored value is the sanitised one.
            val raw = r.string("description", trim = false)

            if (raw != null && raw.length > ProductRules.MAX_DESCRIPTION) {
                errors["description"] = "TOO_LONG"
                s["description"] = null
            } else {
                s["description"] = HtmlSanitizer.sanitizeOrNull(raw)
            }
        }
        if (r.has("shortDescription")) s["shortDescription"] = r.string("shortDescription")
        if (r.has("categoryId")) s["categoryId"] = r.long("categoryId")?.takeIf { it != -1L }
        if (r.has("price")) s["price"] = r.money("price") ?: 0L
        if (r.has("creditPrice")) s["creditPrice"] = r.money("creditPrice") ?: 0L
        if (r.has("compareAtPrice")) s["compareAtPrice"] = r.money("compareAtPrice")
        if (r.has("stock")) s["stock"] = r.int("stock")
        if (r.has("requiredProducts")) s["requiredProducts"] = r.idList("requiredProducts")
        if (r.has("requireOnlyOne")) s["requireOnlyOne"] = r.bool("requireOnlyOne") ?: false
        if (r.has("requiredPermission")) s["requiredPermission"] = r.string("requiredPermission")
        if (r.has("status")) s["status"] = r.enum("status", MarketStatus.entries.toTypedArray()) ?: MarketStatus.ACTIVE
        if (r.has("featured")) s["featured"] = r.bool("featured") ?: false
        if (r.has("durationType")) s["durationType"] = r.enum("durationType", ProductDurationType.entries.toTypedArray()) ?: ProductDurationType.LIFETIME
        if (r.has("durationStart")) s["durationStart"] = r.long("durationStart")
        if (r.has("durationExpiry")) s["durationExpiry"] = r.long("durationExpiry")
        if (r.has("priority")) s["priority"] = r.int("priority") ?: 0
        if (r.has("icon")) s["icon"] = r.string("icon") ?: "fa-box"
        if (r.has("kind")) s["kind"] = r.enum("kind", ProductKind.entries.toTypedArray()) ?: ProductKind.STANDARD
        if (r.has("vatPercent")) s["vatPercent"] = r.percent("vatPercent")
        if (r.has("physical")) s["physical"] = r.bool("physical") ?: false
        if (r.has("sku")) s["sku"] = r.string("sku")
        for (key in listOf("weightGrams", "lengthMm", "widthMm", "heightMm", "periodCount", "subscriptionMaxCycles", "limitPerPlayer", "maxQuantityPerOrder", "tierRank")) {
            if (r.has(key)) s[key] = r.int(key)
        }
        if (r.has("hsCode")) s["hsCode"] = r.string("hsCode")?.replace(".", "")?.replace(" ", "")?.takeIf { it.isNotEmpty() }
        if (r.has("originCountry")) s["originCountry"] = r.string("originCountry")?.uppercase()
        if (r.has("billingMode")) s["billingMode"] = r.enum("billingMode", BillingMode.entries.toTypedArray()) ?: BillingMode.ONE_TIME
        if (r.has("periodUnit")) s["periodUnit"] = r.enum("periodUnit", PeriodUnit.entries.toTypedArray())
        if (r.has("cooldownSeconds")) s["cooldownSeconds"] = r.long("cooldownSeconds")
        if (r.has("creditAmount")) s["creditAmount"] = r.money("creditAmount")
        if (r.has("allowGift")) s["allowGift"] = r.bool("allowGift") ?: true
        if (r.has("hasVariants")) s["hasVariants"] = r.bool("hasVariants") ?: false
        if (r.has("metaTitle")) s["metaTitle"] = r.string("metaTitle")
        if (r.has("metaDescription")) s["metaDescription"] = r.string("metaDescription")
        if (r.has("serverChoices")) s["serverChoices"] = serverChoices(r)
        if (r.has("variantOptions")) s["variantOptions"] = variantOptions(r)

        val actions = if (r.has("actions")) {
            val raw = data.getValue("actions")
            val result = when (raw) {
                is JsonArray -> ProductActions.normalize(raw)
                is String -> ProductActions.normalize(raw)
                null -> ProductActions.Result("[]", emptyMap())
                else -> ProductActions.Result(null, mapOf("actions" to "INVALID"))
            }
            errors.putAll(result.errors)
            result.json
        } else null

        val variants = if (r.has("variants")) r.array("variants")?.let { variants(it, variantImages, errors) } else null

        // Sending variants without saying `hasVariants` means "this product has variants exactly when the list is not empty".
        if (variants != null && !r.has("hasVariants")) s["hasVariants"] = variants.isNotEmpty()
        val fields = if (r.has("fields")) r.array("fields")?.let { fields(it, errors) } else null
        val bundleItems = if (r.has("bundleItems")) r.array("bundleItems")?.let { bundleItems(it, errors) } else null
        val prices = if (r.has("prices")) r.array("prices")?.let { prices(it, "prices", errors) } else null
        val providerMeta = if (r.has("providerMeta")) providerMeta(r, errors) else null

        if (r.has("removeImage") && r.bool("removeImage") == true && productImage is ImageChange.Keep) {
            return ProductInput(s, variants, fields, bundleItems, prices, providerMeta, actions, ImageChange.Remove, errors)
        }

        return ProductInput(s, variants, fields, bundleItems, prices, providerMeta, actions, productImage, errors)
    }

    // ----- set parts -------------------------------------------------------------------------------------------

    private fun variants(array: JsonArray, images: Map<Int, String>, errors: MutableMap<String, String>): List<VariantDraft> =
        array.mapIndexedNotNull { index, element ->
            val path = "variants.$index."
            val obj = element as? JsonObject ?: return@mapIndexedNotNull refuse(errors, "variants.$index")
            val r = Reader(obj, path, errors)

            val image: ImageChange = images[index]?.let { ImageChange.Set(it) }
                ?: if (r.bool("removeImage") == true) ImageChange.Remove else ImageChange.Keep

            VariantDraft(
                id = r.long("id")?.takeIf { it > 0 },
                name = r.string("name") ?: "",
                sku = r.string("sku"),
                optionValues = r.stringMap("optionValues"),
                attributes = r.stringMap("attributes"),
                price = r.money("price"),
                creditPrice = r.money("creditPrice"),
                compareAtPrice = r.money("compareAtPrice"),
                stock = r.int("stock"),
                weightGrams = r.int("weightGrams"),
                periodCount = r.int("periodCount"),
                position = r.int("position"),
                status = r.enum("status", MarketStatus.entries.toTypedArray()) ?: MarketStatus.ACTIVE,
                image = image,
                prices = if (r.has("prices")) r.array("prices")?.let { prices(it, "variants.$index.prices", errors, nested = true) } else null
            )
        }

    private fun fields(array: JsonArray, errors: MutableMap<String, String>): List<FieldDraft> =
        array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull refuse(errors, "fields.$index")
            val r = Reader(obj, "fields.$index.", errors)

            FieldDraft(
                id = r.long("id")?.takeIf { it > 0 },
                fieldKey = r.string("fieldKey") ?: "",
                label = r.string("label") ?: "",
                helpText = r.string("helpText"),
                type = r.enum("type", ProductFieldType.entries.toTypedArray()) ?: ProductFieldType.TEXT,
                required = r.bool("required") ?: false,
                options = fieldOptions(r),
                pattern = r.string("pattern", trim = false)?.takeIf { it.isNotEmpty() },
                minLength = r.int("minLength"),
                maxLength = r.int("maxLength"),
                minValue = r.long("minValue"),
                maxValue = r.long("maxValue"),
                placeholder = r.string("placeholder"),
                defaultValue = r.string("defaultValue"),
                usableInCommands = r.bool("usableInCommands") ?: true,
                position = r.int("position")
            )
        }

    private fun fieldOptions(r: Reader): List<Pair<String, String>>? {
        if (!r.has("options")) return null

        val array = r.array("options") ?: return null
        val result = mutableListOf<Pair<String, String>>()

        array.forEachIndexed { index, element ->
            val obj = element as? JsonObject
            val value = obj?.getValue("value")?.let { if (it is String || it is Number || it is Boolean) it.toString() else null }
            val label = obj?.getValue("label")?.let { if (it is String || it is Number || it is Boolean) it.toString() else null }

            if (value == null || label == null) r.fail("options.$index")
            else result.add(value to label)
        }

        return result
    }

    private fun bundleItems(array: JsonArray, errors: MutableMap<String, String>): List<BundleItemDraft> =
        array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull refuse(errors, "bundleItems.$index")
            val r = Reader(obj, "bundleItems.$index.", errors)

            val productId = r.long("productId")
            if (productId == null && !errors.containsKey("bundleItems.$index.productId")) errors["bundleItems.$index.productId"] = "REQUIRED"

            BundleItemDraft(
                productId = productId ?: 0,
                variantId = r.long("variantId") ?: 0,
                quantity = r.int("quantity") ?: 1,
                position = r.int("position")
            )
        }

    private fun prices(array: JsonArray, root: String, errors: MutableMap<String, String>, nested: Boolean = false): List<PriceDraft> =
        array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull refuse(errors, "$root.$index")
            val r = Reader(obj, "$root.$index.", errors)

            val price = r.money("price")
            if (price == null && !errors.containsKey("$root.$index.price")) errors["$root.$index.price"] = "REQUIRED"

            PriceDraft(
                variantId = if (nested) null else r.long("variantId"),
                currency = r.string("currency")?.uppercase() ?: "",
                price = price ?: 0,
                compareAtPrice = r.money("compareAtPrice")
            )
        }

    private fun providerMeta(r: Reader, errors: MutableMap<String, String>): Map<String, String>? {
        val raw = r.obj.getValue("providerMeta")
        val obj = when (raw) {
            is JsonObject -> raw
            is String -> if (raw.isBlank()) JsonObject() else try { JsonObject(raw) } catch (e: Exception) { null }
            null -> JsonObject()
            else -> null
        }

        if (obj == null) {
            errors["providerMeta"] = "INVALID"
            return null
        }

        val result = linkedMapOf<String, String>()

        obj.forEach { (key, value) ->
            val meta = when (value) {
                is JsonObject -> value
                is Map<*, *> -> JsonObject(@Suppress("UNCHECKED_CAST") (value as Map<String, Any?>))
                else -> null
            }

            when {
                !PROVIDER_ID.matches(key) -> errors["providerMeta.$key"] = "INVALID"
                meta == null -> errors["providerMeta.$key"] = "INVALID"
                meta.encode().length > MAX_PROVIDER_META -> errors["providerMeta.$key"] = "TOO_LONG"
                else -> result[key] = meta.encode()
            }
        }

        return result
    }

    private fun refuse(errors: MutableMap<String, String>, path: String): Nothing? {
        errors[path] = "INVALID"

        return null
    }

    private val PROVIDER_ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private const val MAX_PROVIDER_META = 8192

    // ----- JSON string columns ----------------------------------------------------------------------------------

    private fun serverChoices(r: Reader): String? {
        val array = r.array("serverChoices") ?: return null
        val ids = array.map { element ->
            when (element) {
                is Number -> element.toLong().takeIf { element.toDouble() == it.toDouble() }
                is String -> element.trim().toLongOrNull()
                else -> null
            }
        }

        if (ids.any { it == null || it < 1 }) {
            r.fail("serverChoices")
            return null
        }

        return ids.filterNotNull().distinct().takeIf { it.isNotEmpty() }?.let { JsonArray(it).encode() }
    }

    private fun variantOptions(r: Reader): String? {
        val array = r.array("variantOptions") ?: return null

        return if (array.isEmpty) null else array.encode()
    }

    // ----- value reader -----------------------------------------------------------------------------------------

    private class Reader(val obj: JsonObject, private val path: String, private val errors: MutableMap<String, String>) {
        fun has(key: String) = obj.containsKey(key)

        fun fail(key: String, code: String = "INVALID") {
            errors["$path$key"] = code
        }

        private fun raw(key: String): Any? = obj.getValue(key)

        /** Scalar text; `null` for JSON null and blank text; objects and arrays are refused. */
        fun string(key: String, trim: Boolean = true): String? {
            val value = when (val v = raw(key)) {
                null -> return null
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> {
                    fail(key)
                    return null
                }
            }
            val text = if (trim) value.trim() else value

            return if (text.isBlank()) null else text
        }

        fun long(key: String): Long? {
            val v = raw(key)

            val result: Long? = when (v) {
                null -> return null
                is Int, is Long, is Short, is Byte -> (v as Number).toLong()
                is Double, is Float -> (v as Number).toDouble().takeIf { it == Math.floor(it) && Math.abs(it) < 9.0E15 }?.toLong()
                is String -> {
                    val text = v.trim()
                    if (text.isEmpty() || text == "null") return null
                    text.toLongOrNull() ?: text.toBigDecimalOrNull()?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.let { runCatching { it.longValueExact() }.getOrNull() }
                }

                else -> null
            }

            if (result == null) fail(key)

            return result
        }

        fun int(key: String): Int? {
            val value = long(key) ?: return null

            if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) {
                fail(key, "OUT_OF_RANGE")
                return null
            }

            return value.toInt()
        }

        fun bool(key: String): Boolean? = when (val v = raw(key)) {
            null -> null
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true" -> true
                "false" -> false
                "", "null" -> null
                else -> {
                    fail(key)
                    null
                }
            }

            else -> {
                fail(key)
                null
            }
        }

        fun <E : Enum<E>> enum(key: String, values: Array<E>): E? {
            val text = string(key) ?: return null

            return values.firstOrNull { it.name == text } ?: run {
                fail(key)
                null
            }
        }

        /** A decimal money amount as x100 (half up); more than two decimals are rounded, not refused (the old routes did the same). */
        fun money(key: String): Long? {
            val decimal = decimal(key) ?: return null

            return try {
                decimal.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
            } catch (e: ArithmeticException) {
                fail(key, "OUT_OF_RANGE")
                null
            }
        }

        /** A percent as basis points (x100). */
        fun percent(key: String): Long? = money(key)

        private fun decimal(key: String): BigDecimal? {
            val result: BigDecimal? = when (val v = raw(key)) {
                null -> return null
                is Int, is Long, is Short, is Byte -> BigDecimal.valueOf((v as Number).toLong())
                is Double, is Float -> (v as Number).toDouble().takeIf { !it.isNaN() && !it.isInfinite() }?.let { BigDecimal.valueOf(it) }
                is String -> {
                    val text = v.trim()
                    if (text.isEmpty() || text == "null") return null
                    text.toBigDecimalOrNull()
                }

                else -> null
            }

            if (result == null || result.abs() > BigDecimal.valueOf(MAX_MONEY_DECIMAL)) {
                fail(key, if (result == null) "INVALID" else "OUT_OF_RANGE")
                return null
            }

            return result
        }

        /** A JSON array given as an array or as JSON text; blank text and JSON null are an empty array. */
        fun array(key: String): JsonArray? = when (val v = raw(key)) {
            null -> JsonArray()
            is JsonArray -> v
            is List<*> -> JsonArray(v)
            is String -> if (v.isBlank()) JsonArray() else try {
                JsonArray(v)
            } catch (e: Exception) {
                fail(key)
                null
            }

            else -> {
                fail(key)
                null
            }
        }

        fun idList(key: String): List<Long> {
            val array = array(key) ?: return emptyList()
            val ids = array.map { element ->
                when (element) {
                    is Number -> element.toLong()
                    is String -> element.trim().toLongOrNull()
                    else -> null
                }
            }

            if (ids.any { it == null || it < 1 }) {
                fail(key)
                return emptyList()
            }

            return ids.filterNotNull().distinct()
        }

        /** `{string: scalar}` object (given as an object or JSON text) as strings; `null` when absent or empty. */
        fun stringMap(key: String): Map<String, String>? {
            val obj = when (val v = raw(key)) {
                null -> return null
                is JsonObject -> v
                is Map<*, *> -> JsonObject(@Suppress("UNCHECKED_CAST") (v as Map<String, Any?>))
                is String -> if (v.isBlank()) return null else try {
                    JsonObject(v)
                } catch (e: Exception) {
                    fail(key)
                    return null
                }

                else -> {
                    fail(key)
                    return null
                }
            }

            val result = linkedMapOf<String, String>()

            obj.forEach { (k, v) ->
                if (v is String || v is Number || v is Boolean) result[k] = v.toString()
                else {
                    fail(key)
                    return null
                }
            }

            return result.takeIf { it.isNotEmpty() }
        }
    }
}
