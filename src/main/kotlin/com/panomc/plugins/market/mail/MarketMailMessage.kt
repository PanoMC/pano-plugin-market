package com.panomc.plugins.market.mail

import com.panomc.plugins.market.db.model.MailKind

/** One row of the `items` list: [quantity] and [total] are already formatted; a bundle child is indented and carries no price ([total] empty). */
class MailItem(val name: String, val variant: String?, val quantity: String, val total: String, val child: Boolean) {
    /** The row as the template reads it; never a null value (Handlebars would look a null up outside the loop). */
    fun toMap(): Map<String, Any?> = linkedMapOf("name" to name, "variant" to variant.orEmpty(), "quantity" to quantity, "total" to total, "child" to child)
}

/** One `label: value` row of the bank-transfer fields; both come from the payment method's own config (data, not translations). */
class MailRow(val label: String, val value: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("label" to label, "value" to value)
}

/**
 * One market mail as the platform's mail templates take it: the variant of the `pano-plugin-market` / [KEY] mail and its
 * variables. Every text, the table labels too, lives in the per-language template (`mail-templates/order/<variant>/<locale>.hbs`,
 * editable from the panel); the builder supplies only values: [values] (text / url / boolean, all final and formatted; a table
 * row that is not shown is an empty string), the `items` and `instructionFields` lists, and [instructionsHtml], the admin's own
 * bank-transfer text already passed through `HtmlSanitizer` (the only `html` variable).
 */
class MarketMailMessage(
    val variant: String,
    val values: Map<String, Any?> = emptyMap(),
    val testMode: Boolean = false,
    val items: List<MailItem> = emptyList(),
    val instructionsHtml: String? = null,
    val instructionFields: List<MailRow> = emptyList()
) {
    /** What the template renders with: [values], `testMode`, `items`, `instructions` and `instructionFields` (empty when the mail has none). */
    fun variables(): Map<String, Any?> = LinkedHashMap(values).apply {
        put("testMode", testMode)
        put("items", items.map { it.toMap() })
        put("instructions", instructionsHtml.orEmpty())
        put("instructionFields", instructionFields.map { it.toMap() })
    }

    companion object {
        /** The mail key in `mail-templates/index.json` (the owner is the plugin id). */
        const val KEY = "order"

        /** The variant of the panel's plain test mail (no order). */
        const val TEST_VARIANT = "test"

        /** `ORDER_RECEIVED` -> `order-received`. */
        fun variantOf(kind: MailKind): String = kind.name.lowercase().replace('_', '-')
    }
}
