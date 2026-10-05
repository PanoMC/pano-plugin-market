package com.panomc.plugins.market.core.order

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `BuyerValidator` (06 section 6.1): guest username and e-mail, order e-mail of an account. */
class BuyerValidatorTest {
    private fun invalid(username: String?, email: String?): List<String> =
        (BuyerValidator.validateGuest(username, email) as BuyerValidator.GuestResult.Invalid).fields

    private fun valid(username: String?, email: String?): BuyerValidator.Guest =
        (BuyerValidator.validateGuest(username, email) as BuyerValidator.GuestResult.Valid).guest

    @Test
    fun `username alphabet and length`() {
        listOf("abc", "Steve_01", "A".repeat(16), "___", "123").forEach { assertTrue(BuyerValidator.isValidUsername(it), it) }
        listOf("ab", "A".repeat(17), "", "has space", "dot.name", "star*", "ünal", "a-b", ".Steve", "a\nb", "abc\n").forEach {
            assertFalse(BuyerValidator.isValidUsername(it), "[$it]")
        }
    }

    @Test
    fun `username is trimmed, kept as typed and compared lower-cased`() {
        val guest = valid("  Steve_01 ", "a@b.co")

        assertEquals("Steve_01", guest.username)
        assertEquals("steve_01", guest.usernameKey)
        assertEquals("g:steve_01", guest.buyerKey)
    }

    @Test
    fun `email is trimmed and lower-cased`() {
        assertEquals("steve@example.com", valid("Steve", "  Steve@Example.COM ").email)
    }

    @Test
    fun `accepted e-mail shapes including long top level domains`() {
        listOf(
            "a@b.co", "first.last+tag@sub.example.org", "x@example.photography", "x@example.international",
            "a@b-c.com", "a_b@1.example.io", "A".repeat(64).lowercase() + "@example.com", "ünal@example.com"
        ).forEach { assertTrue(BuyerValidator.isValidEmail(BuyerValidator.normalizeEmail(it)), it) }
    }

    @Test
    fun `rejected e-mail shapes`() {
        listOf(
            "", "plain", "a@b", "a@@b.com", "a b@c.com", "@b.com", "a@.com", "a@b..com", "a@-b.com", "a@b-.com",
            "a@b.c", "a@b.c1", "a@b.123", "a@b.com.", "a@b.c_m", "a@b.com\n", "a@b.com x",
            "a".repeat(65) + "@example.com", "a@" + "b".repeat(64) + ".com"
        ).forEach { assertFalse(BuyerValidator.isValidEmail(it), "[$it]") }
    }

    @Test
    fun `e-mail longer than 255 is rejected`() {
        val label = "b".repeat(60)
        val long = "a@$label.$label.$label.$label.$label.com"

        assertTrue(long.length > 255)
        assertFalse(BuyerValidator.isValidEmail(long))
        assertTrue(BuyerValidator.isValidEmail("a@$label.$label.com"))
    }

    @Test
    fun `control characters inside an e-mail are rejected`() {
        assertFalse(BuyerValidator.isValidEmail("a\u0000b@example.com"))
        assertFalse(BuyerValidator.isValidEmail("a\tb@example.com"))
    }

    @Test
    fun `invalid guest reports every offending path in order`() {
        assertEquals(listOf("guest.username"), invalid("no", "a@b.co"))
        assertEquals(listOf("guest.email"), invalid("Steve", "nope"))
        assertEquals(listOf("guest.username", "guest.email"), invalid(null, null))
        assertEquals(listOf("guest.username", "guest.email"), invalid("  ", " "))
    }

    @Test
    fun `order e-mail of an account is the account e-mail, else a valid billing e-mail`() {
        assertEquals("acc@example.com", BuyerValidator.orderEmailOfAccount(" Acc@Example.com ", "bill@example.com"))
        assertEquals("bill@example.com", BuyerValidator.orderEmailOfAccount(null, " Bill@Example.com"))
        assertEquals("bill@example.com", BuyerValidator.orderEmailOfAccount("  ", "bill@example.com"))
        assertNull(BuyerValidator.orderEmailOfAccount(null, null))
        assertNull(BuyerValidator.orderEmailOfAccount("", "not-an-email"))
    }
}
