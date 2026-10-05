package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.core.cart.CartMessage
import java.util.Locale

/**
 * Who receives an order (06 section 6.2). Pure apart from the player lookup the caller hands in. The subject of every
 * per-player rule (limits, cooldown, entitlements, deliveries) is the [Recipient]; credits, billing and the order
 * e-mail belong to the payer.
 */
object RecipientResolver {
    const val RECIPIENT_UNKNOWN = "RECIPIENT_UNKNOWN"
    const val MAX_GIFT_MESSAGE = 255

    /** The buyer paying: a logged-in account ([userId] set) or a guest ([userId] `null`, [username] the guest name). */
    class Payer(val userId: Long?, val username: String)

    /** A registered player as the lookup knows it (the stored spelling of the name). */
    class KnownPlayer(val userId: Long, val username: String)

    class Recipient(
        val isGift: Boolean,
        /** `null` when the recipient has never joined (a gift to an unknown name, or a guest buying for themselves). */
        val userId: Long?,
        /** The stored spelling when the player is known, else as typed. */
        val username: String,
        /** `u:<id>` or `g:<lower>`. */
        val key: String,
        /** Stored only when [isGift]. */
        val giftMessage: String?,
        /** `RECIPIENT_UNKNOWN` (warning) for a gift to a player who never joined; never blocking. */
        val messages: List<CartMessage>
    )

    enum class Rejection { GIFTS_DISABLED, INVALID_NAME, UNKNOWN_FOR_CREDIT_PACK }

    sealed class Result {
        class Resolved(val recipient: Recipient) : Result()

        /** The caller throws `INVALID_RECIPIENT`. */
        class Rejected(val reason: Rejection) : Result()
    }

    /**
     * [lookup] finds a registered player by name, case-insensitively (`userDao.getUserIdFromUsername`).
     * [cartHasCreditPack]: a gift to an unknown recipient is refused for such a cart (credits need an account).
     */
    suspend fun resolve(
        payer: Payer,
        recipientUsername: String?,
        giftMessage: String?,
        allowGiftPurchase: Boolean,
        cartHasCreditPack: Boolean,
        lookup: suspend (String) -> KnownPlayer?
    ): Result {
        val name = recipientUsername?.trim().orEmpty()

        if (name.isEmpty() || name.equals(payer.username, ignoreCase = true)) {
            return Result.Resolved(self(payer, lookup))
        }

        if (!allowGiftPurchase) return Result.Rejected(Rejection.GIFTS_DISABLED)

        val known = lookup(name)
        val message = cleanGiftMessage(giftMessage)

        if (known != null) {
            return Result.Resolved(Recipient(true, known.userId, known.username, "u:${known.userId}", message, emptyList()))
        }

        if (!BuyerValidator.isValidUsername(name)) return Result.Rejected(Rejection.INVALID_NAME)
        if (cartHasCreditPack) return Result.Rejected(Rejection.UNKNOWN_FOR_CREDIT_PACK)

        return Result.Resolved(
            Recipient(
                true, null, name, "g:${name.lowercase(Locale.ROOT)}", message,
                listOf(CartMessage(RECIPIENT_UNKNOWN, "warning"))
            )
        )
    }

    /** Not a gift: the recipient is the payer. A guest naming a registered player lands on that account (06 section 6.1). */
    private suspend fun self(payer: Payer, lookup: suspend (String) -> KnownPlayer?): Recipient {
        if (payer.userId != null) return Recipient(false, payer.userId, payer.username, "u:${payer.userId}", null, emptyList())

        val known = lookup(payer.username)

        return if (known != null) {
            Recipient(false, known.userId, known.username, "u:${known.userId}", null, emptyList())
        } else {
            Recipient(false, null, payer.username, "g:${payer.username.lowercase(Locale.ROOT)}", null, emptyList())
        }
    }

    /** Trimmed, control characters removed, at most [MAX_GIFT_MESSAGE] characters; blank becomes `null`. */
    fun cleanGiftMessage(raw: String?): String? {
        if (raw == null) return null

        val cleaned = raw.filterNot { Character.isISOControl(it) }.trim()
        val cut = if (cleaned.length > MAX_GIFT_MESSAGE) {
            cleaned.take(MAX_GIFT_MESSAGE).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }.trimEnd()
        } else {
            cleaned
        }

        return cut.ifEmpty { null }
    }
}
