package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.core.order.RecipientResolver.KnownPlayer
import com.panomc.plugins.market.core.order.RecipientResolver.Payer
import com.panomc.plugins.market.core.order.RecipientResolver.Rejection
import com.panomc.plugins.market.core.order.RecipientResolver.Result
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `RecipientResolver` (06 section 6.2): own name, known and unknown players, gift switch, credit packs, gift message. */
class RecipientResolverTest {
    private val players = mapOf("alex" to KnownPlayer(7, "Alex"), "steve" to KnownPlayer(3, "Steve"))
    private val lookups = mutableListOf<String>()

    private val lookup: suspend (String) -> KnownPlayer? = {
        lookups += it
        players[it.lowercase()]
    }

    private val steve = Payer(3, "Steve")

    private fun resolve(
        payer: Payer = steve,
        name: String?,
        message: String? = null,
        allowGift: Boolean = true,
        creditPack: Boolean = false
    ): Result = runBlocking { RecipientResolver.resolve(payer, name, message, allowGift, creditPack, lookup) }

    private fun resolved(result: Result) = (result as Result.Resolved).recipient

    private fun rejected(result: Result) = (result as Result.Rejected).reason

    @Test
    fun `absent blank or own name is not a gift and the message is ignored`() {
        listOf(null, "", "   ", "Steve", "sTEVE", " steve ").forEach {
            val r = resolved(resolve(name = it, message = "hello"))

            assertFalse(r.isGift, "[$it]")
            assertEquals(3L, r.userId)
            assertEquals("u:3", r.key)
            assertEquals("Steve", r.username)
            assertNull(r.giftMessage)
            assertTrue(r.messages.isEmpty())
        }

        assertTrue(lookups.isEmpty(), "a logged-in payer needs no lookup")
    }

    @Test
    fun `own name is not a gift even when gifts are switched off`() {
        assertFalse(resolved(resolve(name = "steve", allowGift = false)).isGift)
    }

    @Test
    fun `gift needs the switch`() {
        assertEquals(Rejection.GIFTS_DISABLED, rejected(resolve(name = "Alex", allowGift = false)))
        assertEquals(Rejection.GIFTS_DISABLED, rejected(resolve(name = "Nobody", allowGift = false)))
    }

    @Test
    fun `gift to a known player uses the stored spelling and the account key`() {
        val r = resolved(resolve(name = " aLeX ", message = "  enjoy "))

        assertTrue(r.isGift)
        assertEquals(7L, r.userId)
        assertEquals("Alex", r.username)
        assertEquals("u:7", r.key)
        assertEquals("enjoy", r.giftMessage)
        assertTrue(r.messages.isEmpty())
    }

    @Test
    fun `gift to an unknown player warns and keys by lower-cased name`() {
        val r = resolved(resolve(name = "NewPlayer_9"))

        assertTrue(r.isGift)
        assertNull(r.userId)
        assertEquals("NewPlayer_9", r.username)
        assertEquals("g:newplayer_9", r.key)
        assertEquals(listOf("RECIPIENT_UNKNOWN" to "warning"), r.messages.map { it.code to it.level })
    }

    @Test
    fun `unknown recipient must be a valid username`() {
        listOf("ab", "has space", "dot.name", "x".repeat(17), "star*").forEach {
            assertEquals(Rejection.INVALID_NAME, rejected(resolve(name = it)), it)
        }
    }

    @Test
    fun `unknown recipient is refused for a cart with a credit pack but a known one is allowed`() {
        assertEquals(Rejection.UNKNOWN_FOR_CREDIT_PACK, rejected(resolve(name = "Nobody_1", creditPack = true)))
        assertTrue(resolved(resolve(name = "Alex", creditPack = true)).isGift)
    }

    @Test
    fun `guest naming a registered player lands on that account without a gift`() {
        val r = resolved(resolve(payer = Payer(null, "alex"), name = null))

        assertFalse(r.isGift)
        assertEquals(7L, r.userId)
        assertEquals("u:7", r.key)
        assertEquals("Alex", r.username)
    }

    @Test
    fun `guest naming an unknown player is keyed by the guest name`() {
        val r = resolved(resolve(payer = Payer(null, "Fresh_One"), name = "fresh_one"))

        assertFalse(r.isGift)
        assertNull(r.userId)
        assertEquals("g:fresh_one", r.key)
        assertTrue(r.messages.isEmpty())
    }

    @Test
    fun `guest gift to a known player`() {
        val r = resolved(resolve(payer = Payer(null, "Fresh_One"), name = "Alex"))

        assertTrue(r.isGift)
        assertEquals("u:7", r.key)
    }

    @Test
    fun `gift message is trimmed, stripped of control characters and cut at 255`() {
        assertEquals("a b", RecipientResolver.cleanGiftMessage(" a\u0000 \u0007b\u001f "))
        assertEquals("line1line2", RecipientResolver.cleanGiftMessage("line1\nline2"))
        assertNull(RecipientResolver.cleanGiftMessage("   "))
        assertNull(RecipientResolver.cleanGiftMessage("\u0000\n"))
        assertNull(RecipientResolver.cleanGiftMessage(null))
        assertEquals(255, RecipientResolver.cleanGiftMessage("x".repeat(400))!!.length)
        assertEquals("x".repeat(254), RecipientResolver.cleanGiftMessage("x".repeat(254) + "😀")!!)
    }
}
