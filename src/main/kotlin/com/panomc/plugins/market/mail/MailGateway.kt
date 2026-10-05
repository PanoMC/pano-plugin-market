package com.panomc.plugins.market.mail

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.mail.MailFile
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailOptions
import com.panomc.platform.mail.MailResult
import com.panomc.plugins.market.db.model.MarketMailOutbox
import io.vertx.sqlclient.SqlClient

/** One regular attachment of a mail (the market side value; `MailFile` of the host never leaves [PlatformMailGateway]). */
class MailAttachment(val name: String, val contentType: String, val data: ByteArray)

/** Everything one send needs; the recipient and the locale are the values fixed on the outbox row (12 section 3.2). */
class OutboundMail(
    val recipient: String,
    val locale: String,
    val content: MailContent,
    val replyTo: String? = null,
    val attachments: List<MailAttachment> = emptyList()
)

/** What a send reports; failures are exceptions. */
enum class MailSendResult { SENT, DISABLED }

/**
 * Outbound mail seam (17 section 4 S6). The production binding is [PlatformMailGateway] (X-4); tests use
 * `FakeMailGateway`.
 */
interface MailGateway {
    /** Sends [message]; throws on every delivery failure (the outbox retries). */
    suspend fun send(message: OutboundMail): MailSendResult
}

/**
 * The only class of market that references `MailOptions` (12 section 3.2): built only when the host probe found the
 * class (`MarketRuntime.capabilities.mail`), else [UnavailableMailGateway] stands in. `userId` is always null: recipient
 * and locale are fixed on the row and a deleted user must not turn into `NotExists`.
 */
class PlatformMailGateway(
    private val mailManager: MailManager,
    private val configManager: ConfigManager,
    private val sqlClient: suspend () -> SqlClient
) : MailGateway {
    /** The platform's master e-mail switch (`config.email.enabled`). */
    fun isEnabled(): Boolean = configManager.config.email.enabled

    override suspend fun send(message: OutboundMail): MailSendResult =
        when (mailManager.sendMail(sqlClient(), null, MarketMail(message.content), toOptions(message))) {
            MailResult.SENT -> MailSendResult.SENT
            MailResult.DISABLED -> MailSendResult.DISABLED
        }

    companion object {
        /** `MailOptions(email = recipient, locale, subject, text = toText(), replyTo, attachments)` (12 section 3.2). */
        fun toOptions(message: OutboundMail): MailOptions = MailOptions(
            email = message.recipient,
            locale = message.locale,
            subject = message.content.subject,
            text = message.content.toText(),
            replyTo = message.replyTo?.takeIf { it.isNotBlank() },
            attachments = message.attachments.map { MailFile(it.name, it.contentType, it.data) }
        )
    }
}

/**
 * Stand-in when the host has no X-4 (`MailOptions` missing): never sends, throws so nothing can mistake it for a delivery.
 * The job checks the host probe first and marks rows `SKIPPED (HOST_TOO_OLD)`; this class is the fail-closed second line.
 */
object UnavailableMailGateway : MailGateway {
    override suspend fun send(message: OutboundMail): MailSendResult =
        throw IllegalStateException("HOST_TOO_OLD: the host has no MailOptions (X-4), market sends no mail")
}

/** Attachments of one row and whether the invoice PDF was wanted but could not be rendered (12 section 4.4). */
class MailAttachments(val files: List<MailAttachment> = emptyList(), val invoiceRenderFailed: Boolean = false) {
    companion object {
        val NONE = MailAttachments()
    }
}

/**
 * The content side of the outbox (12 sections 4.3.5, 4.3.6, 4.4, 5), implemented by `MailComposer` (MK-142 / MK-146 /
 * MK-144 for the invoice bytes). The job depends on this port only.
 */
interface MailComposition {
    /** Relevance check of 12 section 4.3.5: `true` => the row is `SKIPPED (OBSOLETE)`. Not asked for forced resends. */
    suspend fun isObsolete(row: MarketMailOutbox, sqlClient: SqlClient): Boolean

    /** Builds the final content of [row]; an exception ends the row `FAILED (RENDER_ERROR)`. */
    suspend fun compose(row: MarketMailOutbox, sqlClient: SqlClient): MailContent

    /** Invoice / credit-note attachments of [row] (12 section 4.4); a failure is reported, not thrown. */
    suspend fun attachments(row: MarketMailOutbox, sqlClient: SqlClient): MailAttachments = MailAttachments.NONE
}

/**
 * Production binding until `MailComposer` exists: FAILS CLOSED. Every row ends `FAILED` with a `RENDER_ERROR` naming
 * this class, so no mail is sent and the gap is visible in the outbox (panel resend works once the composer is wired).
 */
object UnwiredMailComposition : MailComposition {
    override suspend fun isObsolete(row: MarketMailOutbox, sqlClient: SqlClient): Boolean =
        throw IllegalStateException("the mail composer is not wired (MK-142)")

    override suspend fun compose(row: MarketMailOutbox, sqlClient: SqlClient): MailContent =
        throw IllegalStateException("the mail composer is not wired (MK-142)")
}
