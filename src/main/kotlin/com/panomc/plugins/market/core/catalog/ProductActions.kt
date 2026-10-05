package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.util.ProductActionType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Normalisation of the `actions` JSON of a product on save (01 section 2.2, 08 section 2.2). This is the structural part
 * only: whitelisted keys per type, enum names, stable ids. The privilege rule (`ActionGuard`), server checks, variable
 * checks and `WEBHOOK` actions (which need the secret cipher and masking) belong to the delivery slices (MK-100, MK-104);
 * until then a `WEBHOOK` action is refused with `INVALID`.
 *
 * Ids: an action keeps a valid `id` (`^[a-z0-9]{1,32}$`); one without gets `a<n>` where `n` is the highest number of an
 * existing `a<n>` id plus one (08 section 2.2: never reused after a deletion within one save). A duplicate id is
 * `DUPLICATE_ID`.
 */
object ProductActions {
    const val MAX_ACTIONS = 30
    private val ID = Regex("^[a-z0-9]{1,32}$")
    private val GENERATED = Regex("^a([0-9]{1,9})$")
    private val PHASES = setOf("GRANT", "RENEW", "EXPIRE", "REVOKE")
    private val VIA = setOf("PANO", "SERVER")
    private val SERVER_MODES = setOf("FIXED", "BUYER_CHOICE", "ALL_CONNECTED")

    class Result(val json: String?, val errors: Map<String, String>)

    /** [raw] is the JSON text of the request part. Blank text means "no actions" (`[]`). */
    fun normalize(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result("[]", emptyMap())

        val input = try {
            JsonArray(raw)
        } catch (e: Exception) {
            return Result(null, mapOf("actions" to "INVALID"))
        }

        return normalize(input)
    }

    fun normalize(input: JsonArray): Result {
        val errors = linkedMapOf<String, String>()

        if (input.size() > MAX_ACTIONS) return Result(null, mapOf("actions" to "TOO_MANY"))

        val usedIds = mutableSetOf<String>()
        var highest = 0

        input.forEach { raw ->
            val id = (raw as? JsonObject)?.getValue("id") as? String
            val number = id?.let { GENERATED.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

            if (number != null && number > highest) highest = number
        }

        val output = JsonArray()

        input.forEachIndexed { index, raw ->
            val path = "actions.$index"

            if (raw !is JsonObject) {
                errors[path] = "INVALID"
                return@forEachIndexed
            }

            val clean = JsonObject()
            val type = (raw.getValue("type") as? String)?.let { name -> ProductActionType.entries.firstOrNull { it.name == name } }

            if (type == null) {
                errors["$path.type"] = "INVALID"
                return@forEachIndexed
            }

            val givenId = raw.getValue("id")
            val id = when {
                givenId == null || (givenId is String && givenId.isBlank()) -> "a${++highest}"
                givenId is String && ID.matches(givenId) -> givenId
                else -> {
                    errors["$path.id"] = "INVALID"
                    return@forEachIndexed
                }
            }

            if (!usedIds.add(id)) {
                errors["$path.id"] = "DUPLICATE_ID"
                return@forEachIndexed
            }

            clean.put("id", id).put("type", type.name)

            enumKey(raw, "phase", PHASES, path, errors)?.let { clean.put("phase", it) }

            when (type) {
                ProductActionType.CREDIT -> {
                    val value = when (val v = raw.getValue("value")) {
                        is Number -> v.toDouble()
                        is String -> v.trim().toDoubleOrNull()
                        else -> null
                    }

                    if (value == null || value.isNaN() || value.isInfinite()) errors["$path.value"] = "INVALID_VALUE"
                    else clean.put("value", value)
                }

                ProductActionType.PERMISSION -> {
                    stringList(raw.getValue("value"))?.let { clean.put("value", JsonArray(it)) }
                        ?: run { errors["$path.value"] = "INVALID_VALUE" }
                    enumKey(raw, "via", VIA, path, errors)?.let { clean.put("via", it) }
                }

                ProductActionType.COMMAND -> {
                    stringList(raw.getValue("value"))?.let { clean.put("value", JsonArray(it)) }
                        ?: run { errors["$path.value"] = "INVALID_VALUE" }
                    enumKey(raw, "serverMode", SERVER_MODES, path, errors)?.let { clean.put("serverMode", it) }

                    raw.getValue("requiresOnline")?.let { clean.put("requiresOnline", it == true || it == "true") }
                    raw.getValue("perUnit")?.let { clean.put("perUnit", it == true || it == "true") }
                }
            }

            if (type != ProductActionType.CREDIT) {
                when (val delay = raw.getValue("delay")) {
                    null -> Unit
                    is Number, is String -> {
                        val seconds = (delay as? Number)?.toDouble()?.takeIf { it == Math.floor(it) }?.toInt()
                            ?: (delay as? String)?.trim()?.toIntOrNull()

                        if (seconds == null || seconds < 0 || seconds > MAX_DELAY_SECONDS) errors["$path.delay"] = "OUT_OF_RANGE"
                        else clean.put("delay", seconds)
                    }

                    else -> errors["$path.delay"] = "OUT_OF_RANGE"
                }

                when (val servers = raw.getValue("targetServers")) {
                    null -> Unit
                    is JsonArray -> {
                        val ids = servers.map { s ->
                            when (s) {
                                is Number -> s.toLong()
                                is String -> s.trim().toLongOrNull()
                                else -> null
                            }
                        }

                        if (ids.any { it == null || it < 1 } || ids.size > MAX_TARGET_SERVERS) errors["$path.targetServers"] = "INVALID"
                        else clean.put("targetServers", JsonArray(ids.distinct()))
                    }

                    else -> errors["$path.targetServers"] = "INVALID"
                }
            } else {
                // 08 section 2.2: CREDIT carries no delay-less server data; delay is allowed, the rest is dropped.
                (raw.getValue("delay") as? Number)?.toInt()?.takeIf { it in 0..MAX_DELAY_SECONDS }?.let { clean.put("delay", it) }
            }

            output.add(clean)
        }

        return if (errors.isEmpty()) Result(output.encode(), emptyMap()) else Result(null, errors)
    }

    /**
     * The stored `actions` for the panel: every action carries an `id` (a legacy row without one gets `a<n>` by the rule
     * of [normalize], so a read followed by a save keeps the ids). Never throws; malformed storage reads as `[]`.
     */
    fun view(stored: String?): JsonArray {
        val array = try {
            JsonArray(stored ?: "[]")
        } catch (e: Exception) {
            return JsonArray()
        }

        var highest = 0
        array.forEach { raw ->
            val number = ((raw as? JsonObject)?.getValue("id") as? String)?.let { GENERATED.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

            if (number != null && number > highest) highest = number
        }

        val out = JsonArray()
        array.forEach { raw ->
            if (raw !is JsonObject) return@forEach

            val copy = raw.copy()
            val id = copy.getValue("id")

            if (id !is String || id.isBlank()) copy.put("id", "a${++highest}")
            out.add(copy)
        }

        return out
    }

    private const val MAX_DELAY_SECONDS = 2_592_000
    private const val MAX_TARGET_SERVERS = 100

    private fun enumKey(raw: JsonObject, key: String, allowed: Set<String>, path: String, errors: MutableMap<String, String>): String? {
        val value = raw.getValue(key) ?: return null

        if (value is String && value in allowed) return value

        errors["$path.$key"] = if (key == "phase") "INVALID_PHASE" else "INVALID"

        return null
    }

    private fun stringList(value: Any?): List<String>? {
        if (value !is JsonArray || value.isEmpty || value.size() > 20) return null
        if (value.any { it !is String }) return null

        return value.map { it as String }
    }
}
