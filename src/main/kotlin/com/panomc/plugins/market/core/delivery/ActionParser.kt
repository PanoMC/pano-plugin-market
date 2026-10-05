package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.net.URI
import java.util.Locale

/**
 * Action JSON -> [ProductAction] with the validation of 08 section 2.2 (error `INVALID_PRODUCT`,
 * `fieldErrors["actions.<i>.<key>"] = CODE`). Pure: everything the rules need from the catalogue or the platform comes
 * in through [Context]. The privilege rule (`ActionGuard`, 11 section 14.4) is not here.
 *
 * Unknown keys are ignored, keys that do not apply to a type are dropped silently (CREDIT: server data, perUnit,
 * requiresOnline; PERMISSION: requiresOnline, perUnit; `requiresOnline` outside COMMAND). At most one error per path.
 */
object ActionParser {
    const val MAX_ACTIONS = 30
    const val MAX_DELAY_SECONDS = 2_592_000
    const val MAX_CREDIT = 1_000_000
    private const val MAX_NODES = 20
    private const val MAX_COMMANDS = 20
    private const val MAX_COMMAND_LENGTH = 512
    private const val MAX_URL_LENGTH = 1024
    private const val MAX_TARGET_SERVERS = 100
    private const val MAX_PER_UNIT_QUANTITY = 100

    private val ID = Regex("^[a-z0-9]{1,32}$")
    private val NODE = Regex("^[a-z0-9_.*\\-]{1,128}$")

    /** Which list is being parsed; decides the id prefix and the extra restrictions of 08 section 2.2 (last paragraph). */
    enum class Kind(val idPrefix: String) { PRODUCT("a"), CHARGEBACK("c"), PAYOUT("p") }

    /**
     * [billingMode] (`null` = unknown, only `GRANT` / `REVOKE` pass) gates `RENEW` and `EXPIRE`. [maxQuantityPerOrder]
     * is the product limit `perUnit` needs. [fields] maps `fieldKey` to `usableInCommands`. [serverIds] are the existing
     * platform server rows, [hasServers] = `countOfPermissionGranted > 0`, [hasServerChoices] = product `serverChoices`
     * non-empty. [webhookUrlOk] is the `WebhookUrlPolicy` hook (MK-105); the syntax rules of this file always apply.
     */
    class Context(
        val kind: Kind = Kind.PRODUCT,
        val billingMode: BillingMode? = null,
        val maxQuantityPerOrder: Int? = null,
        val fields: Map<String, Boolean> = emptyMap(),
        val serverIds: Set<Long> = emptySet(),
        val hasServers: Boolean = true,
        val hasServerChoices: Boolean = false,
        val webhookUrlOk: (String) -> Boolean = { true },
        internal val lenient: Boolean = false
    )

    class Result(val actions: List<ProductAction>, val errors: Map<String, String>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    fun parse(raw: String?, ctx: Context = Context()): Result {
        if (raw.isNullOrBlank()) return Result(emptyList(), emptyMap())

        val input = try {
            JsonArray(raw)
        } catch (e: Exception) {
            return Result(emptyList(), mapOf("actions" to "INVALID"))
        }

        return parse(input, ctx)
    }

    fun parse(input: JsonArray, ctx: Context = Context()): Result {
        val errors = linkedMapOf<String, String>()
        val parsed = parseAll(input, ctx, errors, null)

        return Result(if (errors.isEmpty()) parsed else emptyList(), errors)
    }

    /**
     * One action of a stored list that could not be converted. [index] is its position in the stored array (`-1` = the
     * whole text is not a JSON array, [path] `actions`), [id] the id it carried (`null` if none or not a string), [path]
     * and [code] the first rule it broke (`actions.<i>.<key>`, the codes of 08 section 2.2). The delivery planner
     * (MK-101) turns each one into a `FAILED` row so an admin can see and retry it; nothing is dropped silently.
     */
    class Dropped(val index: Int, val id: String?, val path: String, val code: String)

    /** Outcome of [parseStored]: the actions that converted and the ones that did not (in stored order). */
    class Stored(val actions: List<ProductAction>, val dropped: List<Dropped>)

    /**
     * Reads a stored list (a product, its order item snapshot, `chargebackActions`, a payout). Rules that depend on the
     * current catalogue (servers, fields, billing mode, URL policy) and the save-time limits of v2 (credit ceiling,
     * command length, node alphabet, list and target counts) are not applied: an older save path accepted such values and
     * a paid snapshot must still deliver them. What stays is what a safe conversion needs: a finite positive credit with
     * an exact x100 value, string lists, no CR / LF / NUL in a command, valid ids and enums, a sane delay. Every action
     * that still fails is reported in [Stored.dropped], never skipped without a trace. Legacy actions without an `id` get
     * `a<n>` (the rule of `ProductActions.view`).
     */
    fun parseStored(raw: String?, kind: Kind = Kind.PRODUCT): Stored {
        if (raw.isNullOrBlank()) return Stored(emptyList(), emptyList())

        val input = try {
            JsonArray(raw)
        } catch (e: Exception) {
            return Stored(emptyList(), listOf(Dropped(-1, null, "actions", "INVALID")))
        }

        val dropped = ArrayList<Dropped>()
        val parsed = parseAll(input, Context(kind = kind, lenient = true), linkedMapOf(), dropped)

        return Stored(parsed, dropped)
    }

    fun toJson(actions: List<ProductAction>): JsonArray = JsonArray(actions.map { it.toJson() })

    private fun parseAll(input: JsonArray, ctx: Context, errors: MutableMap<String, String>, dropped: MutableList<Dropped>?): List<ProductAction> {
        val lenient = ctx.lenient

        if (!lenient && input.size() > MAX_ACTIONS) {
            errors["actions"] = "TOO_MANY"

            return emptyList()
        }

        val prefix = Regex("^" + ctx.kind.idPrefix + "([0-9]{1,9})$")
        val used = HashSet<String>()
        var highest = 0

        input.forEach { raw ->
            val id = (raw as? JsonObject)?.getValue("id") as? String
            val number = id?.let { prefix.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }

            if (number != null && number > highest) highest = number
        }

        val out = ArrayList<ProductAction>()

        input.forEachIndexed { index, raw ->
            val path = "actions.$index"
            val local = linkedMapOf<String, String>()

            fun err(key: String, code: String) {
                local.putIfAbsent("$path.$key", code)
            }

            if (raw !is JsonObject) {
                if (dropped != null) dropped.add(Dropped(index, null, path, "INVALID")) else errors[path] = "INVALID"

                return@forEachIndexed
            }

            val type = (raw.getValue("type") as? String)?.let { name -> DeliveryActionType.entries.firstOrNull { it.name == name } }

            if (type == null) err("type", "INVALID")

            val givenId = raw.getValue("id")
            var id = ""

            when {
                givenId == null || (givenId is String && givenId.isBlank()) -> id = ctx.kind.idPrefix + (++highest)
                givenId is String && ID.matches(givenId) -> id = givenId
                else -> err("id", "INVALID")
            }

            if (id.isNotEmpty() && !used.add(id)) err("id", "DUPLICATE_ID")

            val phase = parsePhase(raw, ctx, type, ::err)
            val delay = parseDelay(raw, ::err)

            var action = ProductAction(id = id, type = type ?: DeliveryActionType.COMMAND, phase = phase, delaySeconds = delay)

            if (type != null) {
                action = when (type) {
                    DeliveryActionType.CREDIT -> action.copy(credit = parseCredit(raw, ctx, ::err))
                    DeliveryActionType.PERMISSION -> parsePermission(raw, ctx, action, ::err)
                    DeliveryActionType.COMMAND -> parseCommand(raw, ctx, action, ::err)
                    DeliveryActionType.WEBHOOK -> parseWebhook(raw, ctx, action, ::err)
                }
            }

            if (local.isEmpty()) {
                out.add(action)
            } else if (dropped != null) {
                val first = local.entries.first()

                dropped.add(Dropped(index, (raw.getValue("id") as? String)?.takeIf { it.isNotEmpty() }, first.key, first.value))
            } else {
                errors.putAll(local)
            }
        }

        return out
    }

    private fun parsePhase(raw: JsonObject, ctx: Context, type: DeliveryActionType?, err: (String, String) -> Unit): DeliveryPhase {
        // 08 section 2.2 last paragraph: chargeback and payout actions are always GRANT actions.
        if (ctx.kind != Kind.PRODUCT) return DeliveryPhase.GRANT

        val value = raw.getValue("phase") ?: return DeliveryPhase.GRANT
        val phase = (value as? String)?.let { name -> DeliveryPhase.entries.firstOrNull { it.name == name } }

        if (phase == null) {
            err("phase", "INVALID")
            return DeliveryPhase.GRANT
        }

        val stateOnly = type == DeliveryActionType.CREDIT || type == DeliveryActionType.PERMISSION

        if (stateOnly && phase != DeliveryPhase.GRANT && phase != DeliveryPhase.RENEW) err("phase", "INVALID_PHASE")

        val timed = ctx.billingMode == BillingMode.TIMED || ctx.billingMode == BillingMode.SUBSCRIPTION

        if (!ctx.lenient && (phase == DeliveryPhase.EXPIRE || phase == DeliveryPhase.RENEW) && !timed) err("phase", "INVALID_PHASE")

        return phase
    }

    private fun parseDelay(raw: JsonObject, err: (String, String) -> Unit): Int {
        val value = raw.getValue("delay") ?: return 0

        val seconds: Int? = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Number -> value.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() && Math.abs(it) < Int.MAX_VALUE }?.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

        if (seconds == null || seconds < 0 || seconds > MAX_DELAY_SECONDS) {
            err("delay", "OUT_OF_RANGE")
            return 0
        }

        return seconds
    }

    private fun parseCredit(raw: JsonObject, ctx: Context, err: (String, String) -> Unit): Long? {
        val value: BigDecimal? = try {
            when (val v = raw.getValue("value")) {
                is Double -> if (v.isNaN() || v.isInfinite()) null else BigDecimal.valueOf(v)
                is Float -> if (v.isNaN() || v.isInfinite()) null else BigDecimal(v.toString())
                is Number -> BigDecimal(v.toString())
                is String -> BigDecimal(v.trim())
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }

        // The ceiling is save-time policy; a stored value only has to convert exactly (x100 into a Long).
        val ceiling = if (ctx.lenient) BigDecimal(Long.MAX_VALUE).movePointLeft(2) else BigDecimal(MAX_CREDIT)

        if (value == null || value.signum() <= 0 || value > ceiling || value.stripTrailingZeros().scale() > 2) {
            err("value", "INVALID_VALUE")
            return null
        }

        return value.movePointRight(2).longValueExact()
    }

    private fun parsePermission(raw: JsonObject, ctx: Context, base: ProductAction, err: (String, String) -> Unit): ProductAction {
        val list = stringList(raw.getValue("value"), if (ctx.lenient) Int.MAX_VALUE else MAX_NODES)
        val nodes = list?.map { it.trim().lowercase(Locale.ROOT) }

        if (nodes == null || nodes.any { if (ctx.lenient) it.isEmpty() else !NODE.matches(it) }) err("value", "INVALID_VALUE")

        val via = parseVia(raw, err)
        val scope = parseServers(raw, ctx, base.copy(via = via), err)

        return scope.copy(nodes = nodes?.distinct().orEmpty(), via = via)
    }

    private fun parseVia(raw: JsonObject, err: (String, String) -> Unit): PermissionVia {
        val value = raw.getValue("via") ?: return PermissionVia.PANO
        val via = (value as? String)?.let { name -> PermissionVia.entries.firstOrNull { it.name == name } }

        if (via == null) err("via", "INVALID")

        return via ?: PermissionVia.PANO
    }

    private fun parseCommand(raw: JsonObject, ctx: Context, base: ProductAction, err: (String, String) -> Unit): ProductAction {
        val list = stringList(raw.getValue("value"), if (ctx.lenient) Int.MAX_VALUE else MAX_COMMANDS)
        val commands = list?.map { it.trim().removePrefix("/").trim() }

        // Stored commands only need the line-break rule (it would smuggle a second command); length and the other
        // control characters are save-time policy.
        val badCommand = { c: String ->
            c.isEmpty() || if (ctx.lenient) c.any { it == '\r' || it == '\n' || it == '\u0000' }
            else c.length > MAX_COMMAND_LENGTH || c.any { Character.isISOControl(it) }
        }

        if (commands == null || commands.any(badCommand)) {
            err("value", "INVALID_VALUE")
        } else if (!ctx.lenient) {
            val unusable = commands.any { command ->
                VariableContext.TOKEN.findAll(command).any { match ->
                    val name = match.groupValues[1]

                    name.startsWith("field.") && ctx.fields[name.removePrefix("field.")] != true
                }
            }

            if (unusable) err("value", "FIELD_NOT_USABLE")
        }

        val scoped = parseServers(raw, ctx, base, err)
        val perUnit = parsePerUnit(raw, ctx, err)
        val requiresOnline = parseBoolean(raw, "requiresOnline", err)

        return scoped.copy(commands = commands.orEmpty(), perUnit = perUnit, requiresOnline = requiresOnline)
    }

    private fun parseBoolean(raw: JsonObject, key: String, err: (String, String) -> Unit): Boolean =
        when (val v = raw.getValue(key)) {
            null -> false
            is Boolean -> v
            is String -> when (v.trim().lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> {
                    err(key, "INVALID")
                    false
                }
            }

            else -> {
                err(key, "INVALID")
                false
            }
        }

    /** `perUnit` needs a product with `maxQuantityPerOrder` in 1..100; chargeback and payout actions have no quantity. */
    private fun parsePerUnit(raw: JsonObject, ctx: Context, err: (String, String) -> Unit): Boolean {
        val perUnit = parseBoolean(raw, "perUnit", err)

        if (!perUnit) return false
        if (ctx.kind != Kind.PRODUCT) return false

        val max = ctx.maxQuantityPerOrder

        if (!ctx.lenient && (max == null || max < 1 || max > MAX_PER_UNIT_QUANTITY)) err("perUnit", "PER_UNIT_NEEDS_MAX_QUANTITY")

        return true
    }

    /** `serverMode` and `targetServers` of a COMMAND or a PERMISSION; for a server action the catalogue rules apply. */
    private fun parseServers(raw: JsonObject, ctx: Context, base: ProductAction, err: (String, String) -> Unit): ProductAction {
        val modeValue = raw.getValue("serverMode")
        var mode = ServerMode.FIXED

        if (modeValue != null) {
            val parsed = (modeValue as? String)?.let { name -> ServerMode.entries.firstOrNull { it.name == name } }

            if (parsed == null) err("serverMode", "INVALID") else mode = parsed
        }

        if (mode == ServerMode.BUYER_CHOICE && ctx.kind != Kind.PRODUCT) err("serverMode", "INVALID")

        var targets = emptyList<Long>()

        when (val servers = raw.getValue("targetServers")) {
            null -> Unit
            is JsonArray -> {
                val ids = servers.map { s ->
                    when (s) {
                        is Int -> s.toLong()
                        is Long -> s
                        is String -> s.trim().toLongOrNull()
                        else -> null
                    }
                }

                if (ids.any { it == null || it < 1 } || (!ctx.lenient && ids.size > MAX_TARGET_SERVERS)) err("targetServers", "INVALID") else targets = ids.filterNotNull().distinct()
            }

            else -> err("targetServers", "INVALID")
        }

        if (mode != ServerMode.FIXED) targets = emptyList()

        val action = base.copy(serverMode = mode, targetServers = targets)

        if (!ctx.lenient && action.isServerAction) {
            when {
                !ctx.hasServers -> err("serverMode", "NO_SERVERS")
                mode == ServerMode.BUYER_CHOICE && !ctx.hasServerChoices -> err("serverMode", "SERVER_CHOICES_REQUIRED")
                mode == ServerMode.FIXED && targets.any { it !in ctx.serverIds } -> err("targetServers", "UNKNOWN_SERVER")
            }
        }

        return action
    }

    private fun parseWebhook(raw: JsonObject, ctx: Context, base: ProductAction, err: (String, String) -> Unit): ProductAction {
        val value = raw.getValue("value") as? JsonObject

        if (value == null) {
            err("value", "INVALID_VALUE")
            return base
        }

        val url = (value.getValue("url") as? String)?.trim().orEmpty()

        if (!urlSyntaxOk(url) || (!ctx.lenient && !ctx.webhookUrlOk(url))) err("value.url", "INVALID_WEBHOOK_URL")

        val format = when (val f = value.getValue("format")) {
            null -> WebhookFormat.JSON
            else -> (f as? String)?.let { name -> WebhookFormat.entries.firstOrNull { it.name == name } } ?: run { err("value.format", "INVALID_VALUE"); WebhookFormat.JSON }
        }

        val signing = when (val s = value.getValue("signing")) {
            null -> WebhookSigning.NONE
            else -> (s as? String)?.let { name -> WebhookSigning.entries.firstOrNull { it.name == name } } ?: run { err("value.signing", "INVALID_VALUE"); WebhookSigning.NONE }
        }

        val secret = (value.getValue("secret") as? String)?.takeIf { it.isNotEmpty() }
        val perUnit = parsePerUnit(raw, ctx, err)

        return base.copy(webhook = WebhookSpec(url, format, signing, secret), perUnit = perUnit)
    }

    /** Syntax only (08 section 15.6 step 1): http(s), host, no user-info, port 1..65535, <= 1024 chars, no `{`. DNS is `WebhookUrlPolicy`. */
    private fun urlSyntaxOk(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH || url.contains('{') || url.contains('}')) return false
        if (url.any { Character.isISOControl(it) || it == ' ' }) return false

        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        if (scheme != "http" && scheme != "https") return false
        if (uri.rawUserInfo != null || uri.host.isNullOrEmpty()) return false

        return uri.port == -1 || uri.port in 1..65535
    }

    private fun stringList(value: Any?, max: Int): List<String>? {
        if (value !is JsonArray || value.isEmpty || value.size() > max) return null
        if (value.any { it !is String }) return null

        return value.map { it as String }
    }
}
