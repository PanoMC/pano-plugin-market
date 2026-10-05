package com.panomc.plugins.market.mail

import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.mail.Mail
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailParameters

/**
 * The block model of every market mail (12 section 3.1): all strings are final, already translated and formatted by
 * the composer, the template only lays them out. Lists that are empty / null omit their block.
 *
 * [testModeLabel] is the translated text of the "test order" banner shown when [testMode] is set (the design names
 * the flag only; a template cannot translate, so the composer supplies the words).
 */
class MailContent(
    val subject: String,
    val preheader: String,
    val heading: String,
    val paragraphs: List<String> = emptyList(),
    val testMode: Boolean = false,
    val quote: String? = null,
    val items: List<Item> = emptyList(),
    val totals: List<Row> = emptyList(),
    val details: List<Row> = emptyList(),
    val instructionsHtml: String? = null,
    val instructionFields: List<Row> = emptyList(),
    val buttonLabel: String? = null,
    val buttonUrl: String? = null,
    val secondaryLabel: String? = null,
    val secondaryUrl: String? = null,
    val footerNote: String? = null,
    val testModeLabel: String = "TEST MODE"
) {
    class Item(val name: String, val variant: String?, val quantity: String, val total: String, val child: Boolean)

    class Row(val label: String, val value: String, val strong: Boolean = false)

    /**
     * The plain-text alternative (`\r\n` line ends): heading, paragraphs, quote, one `- <qty> x <name> ... <total>` line
     * per item, `label: value` rows, the instructions as text, the buttons as `label: URL`, the footer.
     */
    fun toText(): String {
        val lines = ArrayList<String>()
        fun blank() {
            if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
        }

        if (testMode) lines.add("[$testModeLabel]")
        lines.add(heading)
        paragraphs.forEach { blank(); lines.add(it) }
        quote?.takeIf { it.isNotBlank() }?.let { q ->
            blank()
            q.lines().forEach { lines.add("> $it") }
        }
        if (items.isNotEmpty()) {
            blank()
            items.forEach { i ->
                val name = if (i.variant.isNullOrBlank()) i.name else "${i.name} (${i.variant})"
                lines.add("${if (i.child) "  " else ""}- ${i.quantity} x $name ... ${i.total}")
            }
        }
        if (totals.isNotEmpty()) {
            blank()
            totals.forEach { lines.add("${it.label}: ${it.value}") }
        }
        if (details.isNotEmpty()) {
            blank()
            details.forEach { lines.add("${it.label}: ${it.value}") }
        }
        instructionsHtml?.takeIf { it.isNotBlank() }?.let { blank(); lines.add(htmlToPlainText(it)) }
        if (instructionFields.isNotEmpty()) {
            blank()
            instructionFields.forEach { lines.add("${it.label}: ${it.value}") }
        }
        if (!buttonLabel.isNullOrBlank() && !buttonUrl.isNullOrBlank()) {
            blank()
            lines.add("$buttonLabel: $buttonUrl")
        }
        if (!secondaryLabel.isNullOrBlank() && !secondaryUrl.isNullOrBlank()) {
            lines.add("$secondaryLabel: $secondaryUrl")
        }
        footerNote?.takeIf { it.isNotBlank() }?.let { blank(); lines.add(it) }

        return lines.joinToString("\r\n") { it.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n") }
    }

    private companion object {
        /** Strips tags, `<br>` and `</p>` become a line break, the common entities are decoded. */
        fun htmlToPlainText(html: String): String =
            html
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
                .replace(Regex("<[^>]*>"), "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&amp;", "&")
                .lines().joinToString("\n") { it.trimEnd() }
                .trim()
    }
}

/** What the template of [MarketMail] reads: exactly the fields of [MailContent] except `subject` (12 section 3.1). */
data class MarketMailParameters(
    val preheader: String,
    val heading: String,
    val paragraphs: List<String>,
    val testMode: Boolean,
    val testModeLabel: String,
    val quote: String?,
    val items: List<MailContent.Item>,
    val totals: List<MailContent.Row>,
    val details: List<MailContent.Row>,
    val instructionsHtml: String?,
    val instructionFields: List<MailContent.Row>,
    val buttonLabel: String?,
    val buttonUrl: String?,
    val secondaryLabel: String?,
    val secondaryUrl: String?,
    val footerNote: String?
) : MailParameters

/**
 * The one `Mail` class of market (the platform caches a compiled template per class): the content is already
 * translated, so `subject` is unused (the subject travels in `MailOptions.subject`) and no translation lookup happens.
 */
class MarketMail(val content: MailContent) : Mail {
    override val templatePath: String = TEMPLATE_PATH

    override val subject: String = ""

    override suspend fun generateParameters(
        systemParameters: MailManager.Companion.SystemParameters,
        i18nManager: I18nManager,
        locale: String
    ): MailParameters = parameters()

    /** The template parameters of [content] (no translation lookup, so no host manager is needed). */
    fun parameters(): MarketMailParameters = MarketMailParameters(
        preheader = content.preheader,
        heading = content.heading,
        paragraphs = content.paragraphs,
        testMode = content.testMode,
        testModeLabel = content.testModeLabel,
        quote = content.quote,
        items = content.items,
        totals = content.totals,
        details = content.details,
        instructionsHtml = content.instructionsHtml,
        instructionFields = content.instructionFields,
        buttonLabel = content.buttonLabel,
        buttonUrl = content.buttonUrl,
        secondaryLabel = content.secondaryLabel,
        secondaryUrl = content.secondaryUrl,
        footerNote = content.footerNote
    )

    companion object {
        const val TEMPLATE_PATH = "mail/market-mail.hbs"
    }
}
