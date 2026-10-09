package com.panomc.plugins.market.core.webhook

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.time.Instant

/**
 * The localised texts of one Discord message (08 section 16.2). [title] and [description] are the `webhooks.discord.<event>.*` translations of the
 * store's default locale; they may hold the placeholders `{username}`, `{items.inline}`, `{order.id}` ... which [DiscordRenderer.vars] fills.
 */
class DiscordLabels(
    val title: String,
    val description: String,
    val player: String,
    val total: String,
    val items: String
) {
    companion object {
        /** English texts, used by tests and whenever no translator is wired. */
        val DEFAULT = DiscordLabels("Store event", "{username}", "Player", "Total", "Items")
    }
}

/** Resolves the [DiscordLabels] of an event (the production source reads `MarketI18n`; a test passes a lambda). */
fun interface DiscordLabelSource {
    suspend fun labels(event: String): DiscordLabels
}

/** A rendered Discord body; [templateError] is `true` when a custom template was unusable and the built-in one was sent instead. */
class DiscordRendered(val body: String, val templateError: Boolean)

/**
 * Discord webhook bodies (08 section 16). Pure: no I/O.
 *
 * - [render] substitutes tokens with JSON-string-escaped values, parses, falls back to the built-in template on a parse error, enforces the Discord limits
 *   by truncation with a trailing `...`, drops empty fields and empty `url` / `timestamp` keys, and **forces** `allowed_mentions: {"parse": []}` so buyer
 *   controlled text can never ping `@everyone`.
 * - [validate] is the save-time check of a custom template (`INVALID_JSON`).
 * - [vars] builds the token map of an event from its JSON envelope (08 section 15.4): it never reads e-mail, IP or address data.
 */
object DiscordRenderer {
    const val ELLIPSIS = "\u2026"

    const val CONTENT_MAX = 2000
    const val TITLE_MAX = 256
    const val DESCRIPTION_MAX = 4096
    const val FIELD_NAME_MAX = 256
    const val FIELD_VALUE_MAX = 1024
    const val FOOTER_MAX = 2048
    const val AUTHOR_MAX = 256
    const val MAX_FIELDS = 25
    const val MAX_EMBEDS = 10
    const val EMBED_TOTAL_MAX = 6000
    const val TEMPLATE_MAX = 8192
    const val ITEM_LINES_MAX = 10

    /** The built-in embed of 08 section 16.2 (also what the panel shows as the default of a custom template). */
    const val BUILT_IN_TEMPLATE =
        """{ "username": "{store.name}",
  "embeds": [ { "title": "{event.title}", "url": "{order.url}", "description": "{event.description}",
                "color": {event.color}, "timestamp": "{event.iso}",
                "fields": [ { "name": "{label.player}", "value": "{username}", "inline": true },
                            { "name": "{label.total}",  "value": "{order.total} {order.currency}", "inline": true },
                            { "name": "{label.items}",  "value": "{items}" } ],
                "footer": { "text": "{store.name} · #{order.id}" } } ] }"""

    /** Token syntax of 08 section 3.1. */
    private val TOKEN = Regex("""\{([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)?)(?:\|([A-Za-z0-9_.:\- ]{0,64}))?\}""")

    /** Every name a Discord template may use (08 sections 3.2 and 16.2); a name outside this list stays verbatim. */
    val KNOWN_TOKENS: Set<String> = setOf(
        "username", "player", "uuid", "buyer.username", "recipient.username",
        "order.id", "order.publicId", "order.total", "order.currency", "order.url",
        "product.id", "product.name", "product", "product.slug", "product.sku", "variant.name", "variant.sku", "quantity", "unit", "phase",
        "expiresAt", "expiresAt.iso", "date", "time", "gift.message",
        "event.name", "event.title", "event.description", "event.color", "event.iso",
        "store.name", "store.url", "items", "items.inline", "label.player", "label.total", "label.items",
        "refund.amount", "refund.reason", "dispute.reason", "subscription.status",
        "shipment.trackingNumber", "shipment.trackingUrl", "shipment.carrierName"
    )

    /** Decimal colour of an event (08 section 16.2). */
    fun colorOf(event: String): Int = when {
        event == WebhookEvents.ORDER_PAID -> 3066993
        event == WebhookEvents.ORDER_REFUNDED -> 15105570
        event == WebhookEvents.ORDER_CHARGEBACK -> 15158332
        event == WebhookEvents.ORDER_CHARGEBACK_WON -> 3447003
        event.startsWith("subscription.") -> 10181046
        event.startsWith("shipment.") -> 1752220
        else -> 9807270
    }

    // ----- rendering ---------------------------------------------------------------------------------------------------------

    /** The body for [template] (`null` / blank = the built-in one) and the token values [vars]. */
    fun render(template: String?, vars: Map<String, String>): String = renderChecked(template, vars).body

    /** As [render], also telling whether a custom template had to be replaced by the built-in one (`TEMPLATE_ERROR`). */
    fun renderChecked(template: String?, vars: Map<String, String>): DiscordRendered {
        val custom = template?.takeIf { it.isNotBlank() }

        if (custom != null) {
            val parsed = parse(substitute(custom, vars))

            if (parsed != null) return DiscordRendered(finish(parsed).encode(), false)
        }

        val builtIn = parse(substitute(BUILT_IN_TEMPLATE, vars)) ?: JsonObject()

        return DiscordRendered(finish(builtIn).encode(), custom != null)
    }

    private fun parse(text: String): JsonObject? = try {
        JsonObject(text)
    } catch (e: Exception) {
        null
    }

    /** Single pass: each known token becomes the escaped value (`event.color`: digits only); an unknown name stays verbatim. */
    private fun substitute(template: String, vars: Map<String, String>): String {
        val out = StringBuilder(template.length + 128)
        var last = 0

        for (match in TOKEN.findAll(template)) {
            val name = match.groupValues[1]

            if (name !in KNOWN_TOKENS) continue

            val raw = vars[name].orEmpty().ifEmpty { match.groups[2]?.value.orEmpty() }
            val value = if (name == "event.color") raw.filter { it in '0'..'9' }.ifEmpty { "0" } else escape(raw)

            out.append(template, last, match.range.first).append(value)
            last = match.range.last + 1
        }

        return out.append(template, last, template.length).toString()
    }

    /** The inside of a JSON string for [text]: quotes, backslashes, control characters and the line separators are escaped. */
    internal fun escape(text: String): String {
        val encoded = io.vertx.core.json.Json.encode(text)

        return encoded.substring(1, encoded.length - 1).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }

    private fun finish(root: JsonObject): JsonObject {
        (root.getValue("content") as? String)?.let { text -> if (text.isEmpty()) root.remove("content") else root.put("content", cut(text, CONTENT_MAX)) }

        val embeds = root.getValue("embeds") as? JsonArray

        if (embeds != null) {
            val kept = JsonArray()

            for (value in embeds.take(MAX_EMBEDS)) (value as? JsonObject)?.let { kept.add(cleanEmbed(it)) }

            root.put("embeds", kept)
        }

        // forced, whatever the template says: buyer-controlled text can never ping anybody
        root.put("allowed_mentions", JsonObject().put("parse", JsonArray()))

        return root
    }

    private fun cleanEmbed(embed: JsonObject): JsonObject {
        for (key in listOf("url", "timestamp")) if ((embed.getValue(key) as? String)?.isEmpty() == true) embed.remove(key)

        truncate(embed, "title", TITLE_MAX)
        truncate(embed, "description", DESCRIPTION_MAX)

        (embed.getValue("footer") as? JsonObject)?.let { footer ->
            // an event without an order has no number: "Store · #" would read like an error
            (footer.getValue("text") as? String)?.let { footer.put("text", it.removeSuffix(" \u00b7 #")) }
            truncate(footer, "text", FOOTER_MAX)
        }

        (embed.getValue("author") as? JsonObject)?.let { truncate(it, "name", AUTHOR_MAX) }

        val fields = JsonArray()

        for (value in (embed.getValue("fields") as? JsonArray).orEmpty()) {
            val field = value as? JsonObject ?: continue
            val text = (field.getValue("value") as? String) ?: continue

            if (text.isBlank()) continue

            truncate(field, "name", FIELD_NAME_MAX)
            field.put("value", cut(text, FIELD_VALUE_MAX))
            fields.add(field)
        }

        while (fields.size() > MAX_FIELDS) fields.remove(fields.size() - 1)

        embed.put("fields", fields)

        if (fields.isEmpty) embed.remove("fields")

        // 6000 characters over title, description, field names and values, footer text and author name: fields go from the end
        while (embedSize(embed) > EMBED_TOTAL_MAX && !fields.isEmpty) fields.remove(fields.size() - 1)

        if (fields.isEmpty) embed.remove("fields")

        return embed
    }

    private fun embedSize(embed: JsonObject): Int {
        var n = (embed.getValue("title") as? String)?.length ?: 0

        n += (embed.getValue("description") as? String)?.length ?: 0
        n += ((embed.getValue("footer") as? JsonObject)?.getValue("text") as? String)?.length ?: 0
        n += ((embed.getValue("author") as? JsonObject)?.getValue("name") as? String)?.length ?: 0

        for (value in (embed.getValue("fields") as? JsonArray).orEmpty()) {
            val field = value as? JsonObject ?: continue

            n += (field.getValue("name") as? String)?.length ?: 0
            n += (field.getValue("value") as? String)?.length ?: 0
        }

        return n
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()

    private fun truncate(obj: JsonObject, key: String, max: Int) {
        (obj.getValue(key) as? String)?.let { obj.put(key, cut(it, max)) }
    }

    /** [text] cut to [max] characters, the last one being the ellipsis; a surrogate pair is never split. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text

        var end = max - 1

        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--

        return text.substring(0, end) + ELLIPSIS
    }

    // ----- validation (save time, 08 section 16.3) -------------------------------------------------------------------------------

    /**
     * `null` when [template] is usable, else the field error code (`INVALID_JSON`; `TOO_LONG` above 8192 characters). Every known token is replaced by `0`
     * outside a JSON string and by `x` inside one, then the text must parse as a JSON object holding `content` (string) or `embeds` (1..10 objects).
     */
    fun validate(template: String): String? {
        if (template.length > TEMPLATE_MAX) return "TOO_LONG"

        val probe = StringBuilder(template.length)
        var inString = false
        var escaped = false
        var i = 0

        while (i < template.length) {
            val c = template[i]

            if (!inString && c == '{') {
                val match = TOKEN.matchAt(template, i)

                if (match != null && match.groupValues[1] in KNOWN_TOKENS) {
                    probe.append('0')
                    i = match.range.last + 1

                    continue
                }
            } else if (inString && c == '{') {
                val match = TOKEN.matchAt(template, i)

                if (match != null && match.groupValues[1] in KNOWN_TOKENS) {
                    probe.append('x')
                    i = match.range.last + 1

                    continue
                }
            }

            probe.append(c)

            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else if (c == '"') {
                inString = true
            }

            i++
        }

        val root = parse(probe.toString()) ?: return "INVALID_JSON"
        val content = root.getValue("content")
        val embeds = root.getValue("embeds")

        if (content is String) return null

        if (embeds is JsonArray && embeds.size() in 1..MAX_EMBEDS && embeds.all { it is JsonObject }) return null

        return "INVALID_JSON"
    }

    // ----- variables ------------------------------------------------------------------------------------------------------

    /**
     * The token map of [event] from its [envelope] (08 sections 15.4 and 16.2). Absent values are empty strings. [labels] are the localised texts; their
     * placeholders are filled from the same map (one pass).
     */
    fun vars(event: String, envelope: JsonObject, labels: DiscordLabels): Map<String, String> {
        val data = envelope.getJsonObject("data") ?: JsonObject()
        // core's envelope names the site `site`; the planner's own envelope of a product action still says `store`
        val store = envelope.getJsonObject("site") ?: envelope.getJsonObject("store") ?: JsonObject()
        val order = data.getJsonObject("order")
        val buyer = data.getJsonObject("buyer")
        val recipient = data.getJsonObject("recipient")
        val itemList = lines(data)
        val out = LinkedHashMap<String, String>()

        fun put(key: String, value: Any?) {
            out[key] = value?.let { text(it) }.orEmpty()
        }

        val recipientName = recipient?.getString("username").orEmpty()
        val buyerName = buyer?.getString("username").orEmpty()

        put("username", recipientName.ifEmpty { buyerName })
        out["player"] = out.getValue("username")
        put("recipient.username", recipientName.ifEmpty { buyerName })
        put("buyer.username", buyerName)
        put("uuid", recipient?.getValue("uuid") ?: buyer?.getValue("uuid"))

        put("order.id", order?.getValue("id"))
        put("order.publicId", order?.getValue("publicId"))
        put("order.total", order?.getValue("total")?.let { plainDecimal(it) })
        put("order.currency", order?.getValue("currency"))
        put("order.url", order?.getValue("url"))
        put("gift.message", order?.getValue("giftMessage"))

        val first = itemList.firstOrNull()

        put("product.id", first?.getValue("productId"))
        put("product.name", first?.getValue("productName"))
        out["product"] = out.getValue("product.name")
        put("product.slug", first?.getValue("slug"))
        put("product.sku", first?.getValue("sku"))
        put("variant.name", first?.getValue("variantName"))
        put("variant.sku", first?.getValue("sku"))
        put("quantity", data.getJsonObject("delivery")?.getValue("quantity") ?: first?.getValue("quantity"))
        put("unit", data.getJsonObject("delivery")?.getValue("unit"))
        put("phase", data.getJsonObject("delivery")?.getValue("phase")?.toString()?.lowercase())
        put("expiresAt", data.getJsonObject("entitlement")?.getValue("expiresAt")?.let { (it as? Number)?.let { n -> n.toLong() / 1000 } })
        put("expiresAt.iso", (data.getJsonObject("entitlement")?.getValue("expiresAt") as? Number)?.let { Instant.ofEpochMilli(it.toLong()).toString() })

        val createdAt = (envelope.getValue("createdAt") as? Number)?.toLong() ?: 0L
        val iso = Instant.ofEpochMilli(createdAt).toString()

        put("date", iso.substring(0, 10))
        put("time", iso.substring(11, 16))
        put("event.name", event)
        put("event.color", colorOf(event))
        put("event.iso", iso)
        put("store.name", store.getValue("name"))
        put("store.url", store.getValue("url"))

        put("items", itemLines(itemList, "\n"))
        put("items.inline", itemLines(itemList, ", "))

        data.getJsonObject("refund")?.let {
            put("refund.amount", it.getValue("amount")?.let { a -> plainDecimal(a) })
            put("refund.reason", it.getValue("reason"))
        }

        put("dispute.reason", data.getJsonObject("dispute")?.getValue("reason"))
        put("subscription.status", data.getJsonObject("subscription")?.getValue("status"))

        data.getJsonObject("shipment")?.let {
            put("shipment.trackingNumber", it.getValue("trackingNumber"))
            put("shipment.trackingUrl", it.getValue("trackingUrl"))
            put("shipment.carrierName", it.getValue("carrierName"))
        }

        for (key in listOf("refund.amount", "refund.reason", "dispute.reason", "subscription.status", "shipment.trackingNumber", "shipment.trackingUrl", "shipment.carrierName")) {
            out.putIfAbsent(key, "")
        }

        out["label.player"] = labels.player
        out["label.total"] = labels.total
        out["label.items"] = labels.items
        out["event.title"] = fill(labels.title, out)
        out["event.description"] = fill(labels.description, out)

        return out
    }

    /** Fills `{name}` placeholders of a label from [vars] in one pass; an unknown name stays. */
    private fun fill(text: String, vars: Map<String, String>): String {
        if ('{' !in text) return text

        return TOKEN.replace(text) { m -> vars[m.groupValues[1]]?.takeIf { it.isNotEmpty() } ?: m.groups[2]?.value ?: vars[m.groupValues[1]] ?: m.value }
    }

    private fun lines(data: JsonObject): List<JsonObject> {
        val list = data.getJsonArray("items")?.filterIsInstance<JsonObject>()

        if (list != null) return list

        return listOfNotNull(data.getJsonObject("item"))
    }

    private fun itemLines(items: List<JsonObject>, separator: String): String {
        if (items.isEmpty()) return ""

        val lines = items.take(ITEM_LINES_MAX).map { item ->
            val variant = item.getString("variantName")?.takeIf { it.isNotEmpty() }
            val name = item.getString("productName").orEmpty()

            "${item.getValue("quantity") ?: 1}\u00d7 $name" + if (variant != null) " ($variant)" else ""
        }.toMutableList()

        if (items.size > ITEM_LINES_MAX) lines += "+${items.size - ITEM_LINES_MAX} more"

        return lines.joinToString(separator)
    }

    private fun text(value: Any): String = when (value) {
        is String -> value
        is Number -> plainDecimal(value)
        else -> value.toString()
    }

    private fun plainDecimal(value: Any): String = try {
        val d = BigDecimal(value.toString())

        // whole numbers (ids, quantities) print without decimals, money keeps its two
        if (value is Int || value is Long || value is java.math.BigInteger) d.toPlainString() else d.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    } catch (e: NumberFormatException) {
        value.toString()
    }
}
