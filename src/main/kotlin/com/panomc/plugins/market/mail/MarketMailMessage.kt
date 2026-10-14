package com.panomc.plugins.market.mail

import com.panomc.plugins.market.db.model.MailKind

/** One line of the items block: [quantity] and [total] are already formatted; a bundle child is indented and carries no price. */
class MailItem(val name: String, val variant: String?, val quantity: String, val total: String, val child: Boolean)

/** One `label: value` row of the totals, details or bank-transfer blocks; [strong] rows are bold. */
class MailRow(val label: String, val value: String, val strong: Boolean = false)

/**
 * One market mail as the platform's mail templates take it: the variant of the `pano-plugin-market` / [KEY] mail and its
 * variables. Every sentence lives in the per-language template (`mail-templates/order/<variant>/<locale>.hbs`, editable from
 * the panel); the builder supplies only values: [values] (text / url / boolean, all final and formatted) and the structured
 * blocks, which [variables] renders into the `html` variables with [MailBlocks].
 *
 * [testBannerLabel] is the translated banner text of a test order (shown only when [testMode]).
 */
class MarketMailMessage(
    val variant: String,
    val values: Map<String, Any?> = emptyMap(),
    val testMode: Boolean = false,
    val testBannerLabel: String = "",
    val items: List<MailItem> = emptyList(),
    val totals: List<MailRow> = emptyList(),
    val details: List<MailRow> = emptyList(),
    val instructionsHtml: String? = null,
    val instructionFields: List<MailRow> = emptyList()
) {
    /** What the template renders with: [values], `testMode`, and the `html` blocks (an empty string when a block has nothing). */
    fun variables(): Map<String, Any?> = LinkedHashMap(values).apply {
        put("testMode", testMode)
        put("testBanner", if (testMode) MailBlocks.testBanner(testBannerLabel) else "")
        put("itemsTable", MailBlocks.items(items))
        put("totalsTable", MailBlocks.totals(totals))
        put("detailsTable", MailBlocks.details(details))
        put("bankInstructions", MailBlocks.instructions(instructionsHtml, instructionFields))
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

/**
 * The fixed HTML partials of the structured blocks (the look of the former `market-mail.hbs`). Every value is HTML-escaped
 * here; only [instructions]' body is inserted as is, because it is the admin's text already passed through `HtmlSanitizer`.
 * Cells are separated by a space so the platform's plain-text alternative reads `label value` per row.
 */
object MailBlocks {
    private const val TABLE = """<table align="center" width="100%" border="0" cellpadding="0" cellspacing="0" role="presentation""""
    private const val ITEM_CELL = "padding-top:10px;padding-bottom:10px;border-bottom-style:solid;border-bottom-width:1px;border-color:rgb(243,244,246);font-size:14px;line-height:20px;color:rgb(54,65,83)"
    private const val ROW_CELL = "padding-top:4px;padding-bottom:4px;font-size:14px;line-height:20px;color:rgb(54,65,83)"
    private const val STRONG = ";font-weight:700;font-size:16px"
    private const val LABEL_CELL = "padding-top:4px;padding-bottom:4px;font-size:13px;line-height:20px;color:rgb(106,114,130)"

    fun testBanner(label: String): String =
        """$TABLE style="margin-bottom:16px;border-radius:0.5rem;background-color:rgb(254,243,199);padding:8px"><tbody><tr><td>""" +
            """<p style="font-size:13px;line-height:20px;margin:0;text-align:center;font-weight:600;color:rgb(146,64,14)">${escape(label)}</p></td></tr></tbody></table>"""

    fun items(items: List<MailItem>): String {
        if (items.isEmpty()) return ""

        val rows = items.joinToString("") { i ->
            val variant = i.variant?.takeIf { it.isNotBlank() }?.let { """ <span style="color:rgb(106,114,130)">(${escape(it)})</span>""" }.orEmpty()

            """<tr><td style="$ITEM_CELL${if (i.child) ";padding-left:20px" else ""}">${escape(i.quantity)} &#215; ${escape(i.name)}$variant</td> """ +
                """<td align="right" style="$ITEM_CELL;white-space:nowrap">${escape(i.total)}</td></tr>"""
        }

        return """$TABLE style="margin-top:24px;margin-bottom:8px;border-top-style:solid;border-top-width:1px;border-color:rgb(229,231,235)"><tbody>$rows</tbody></table>"""
    }

    fun totals(rows: List<MailRow>): String {
        if (rows.isEmpty()) return ""

        val body = rows.joinToString("") { r ->
            val strong = if (r.strong) STRONG else ""

            """<tr><td style="$ROW_CELL$strong">${escape(r.label)}</td> <td align="right" style="$ROW_CELL;white-space:nowrap$strong">${escape(r.value)}</td></tr>"""
        }

        return """$TABLE style="margin-bottom:16px"><tbody>$body</tbody></table>"""
    }

    fun details(rows: List<MailRow>): String {
        if (rows.isEmpty()) return ""

        val body = rows.joinToString("") { r ->
            """<tr><td style="$LABEL_CELL">${escape(r.label)}</td> <td align="right" style="padding-top:4px;padding-bottom:4px;font-size:13px;line-height:20px;color:rgb(31,41,55)">${escape(r.value)}</td></tr>"""
        }

        return """$TABLE style="margin-top:16px;margin-bottom:16px;border-radius:0.5rem;background-color:rgb(249,250,251);padding:16px"><tbody>$body</tbody></table>"""
    }

    /** The bank-transfer instructions: the sanitised [html] of the method, then its fields (IBAN, reference, ...). */
    fun instructions(html: String?, fields: List<MailRow>): String {
        val text = html?.takeIf { it.isNotBlank() }?.let {
            """<div style="font-size:15px;line-height:22px;color:rgb(54,65,83);margin-top:16px;margin-bottom:16px">$it</div>"""
        }.orEmpty()

        if (fields.isEmpty()) return text

        val body = fields.joinToString("") { r ->
            """<tr><td style="$LABEL_CELL">${escape(r.label)}</td> <td align="right" style="padding-top:4px;padding-bottom:4px;font-size:14px;line-height:20px;font-weight:600;color:rgb(31,41,55)">${escape(r.value)}</td></tr>"""
        }

        return text + """$TABLE style="margin-top:8px;margin-bottom:16px;border-radius:0.5rem;background-color:rgb(240,253,244);padding:16px"><tbody>$body</tbody></table>"""
    }

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)

        for (c in value) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#x27;")
                '`' -> out.append("&#x60;")
                '=' -> out.append("&#x3D;")
                else -> out.append(c)
            }
        }

        return out.toString()
    }
}
