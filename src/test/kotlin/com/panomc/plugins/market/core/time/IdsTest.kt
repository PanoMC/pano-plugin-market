package com.panomc.plugins.market.core.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdsTest {
    private val ids = SecureIds()

    @Test
    fun `publicId is 20 Crockford characters without I L O U`() {
        repeat(2_000) {
            val v = ids.publicId()
            assertEquals(20, v.length)
            assertTrue(Ids.PUBLIC_ID_REGEX.matches(v), v)
            assertTrue(v.none { it in "ILOU" }, v)
            assertTrue(v.all { it in Ids.CROCKFORD_ALPHABET }, v)
        }
    }

    @Test
    fun `crockford alphabet has 32 distinct symbols`() {
        assertEquals(32, Ids.CROCKFORD_ALPHABET.toSet().size)
        assertFalse(Ids.CROCKFORD_ALPHABET.any { it in "ILOU" })
    }

    @Test
    fun `reference matches A-Z0-9 20`() {
        repeat(2_000) {
            val v = ids.reference()
            assertTrue(Regex("[A-Z0-9]{20}").matches(v), v)
            assertTrue(Ids.REFERENCE_REGEX.matches(v), v)
        }
    }

    @Test
    fun `tokens are lower-case hex of twice the byte count`() {
        for (bytes in listOf(1, 8, 16, 20, 32, 64)) {
            val v = ids.hexToken(bytes)
            assertEquals(bytes * 2, v.length)
            assertTrue(Regex("[0-9a-f]+").matches(v), v)
        }
        assertEquals(40, ids.hexToken(20).length)
    }

    @Test
    fun `hexToken rejects a non positive size`() {
        assertThrows<IllegalArgumentException> { ids.hexToken(0) }
        assertThrows<IllegalArgumentException> { ids.hexToken(-1) }
    }

    @Test
    fun `uuid is a canonical version 4 uuid`() {
        repeat(2_000) {
            val v = ids.uuid()
            assertTrue(Ids.UUID_REGEX.matches(v), v)
            assertEquals(v, java.util.UUID.fromString(v).toString())
            assertEquals(4, java.util.UUID.fromString(v).version())
            assertEquals(2, java.util.UUID.fromString(v).variant())
        }
    }

    @Test
    fun `100000 values of each kind do not collide`() {
        val n = 100_000
        assertEquals(n, (1..n).map { ids.publicId() }.toSet().size)
        assertEquals(n, (1..n).map { ids.reference() }.toSet().size)
        assertEquals(n, (1..n).map { ids.hexToken(20) }.toSet().size)
        assertEquals(n, (1..n).map { ids.uuid() }.toSet().size)
    }

    @Test
    fun `a seeded SecureRandom still yields distinct consecutive values`() {
        val a = ids.publicId()
        val b = ids.publicId()
        assertNotEquals(a, b)
    }

    @Test
    fun `every symbol of the alphabets is reachable`() {
        val publicChars = HashSet<Char>()
        val refChars = HashSet<Char>()
        repeat(2_000) {
            publicChars += ids.publicId().toSet()
            refChars += ids.reference().toSet()
        }
        assertEquals(Ids.CROCKFORD_ALPHABET.toSet(), publicChars)
        assertEquals(Ids.REFERENCE_ALPHABET.toSet(), refChars)
    }
}
