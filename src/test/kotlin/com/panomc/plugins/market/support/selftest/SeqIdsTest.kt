package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.support.SeqIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeqIdsTest {
    @Test
    fun `is deterministic across instances`() {
        val a = SeqIds()
        val b = SeqIds()
        repeat(50) {
            assertEquals(a.publicId(), b.publicId())
            assertEquals(a.reference(), b.reference())
            assertEquals(a.hexToken(20), b.hexToken(20))
            assertEquals(a.uuid(), b.uuid())
        }
    }

    @Test
    fun `exact values for the first counter positions`() {
        val ids = SeqIds()
        assertEquals("00000000000000000001", ids.publicId())
        assertEquals("T0000000000000000002", ids.reference())
        assertEquals("0000000000000000000000000000000000000003", ids.hexToken(20))
        assertEquals("00000000-0000-4000-8000-000000000004", ids.uuid())
        assertEquals(4L, ids.issued)
    }

    @Test
    fun `publicId encodes the counter in crockford base32`() {
        assertEquals("0000000000000000000Z", SeqIds(start = 30).publicId())  // 31
        assertEquals("00000000000000000010", SeqIds(start = 31).publicId())  // 32
        assertEquals("0000000000000000001Z", SeqIds(start = 62).publicId())  // 63
    }

    @Test
    fun `output matches the production formats checked by IdsTest`() {
        val ids = SeqIds()
        repeat(5_000) {
            assertTrue(Ids.PUBLIC_ID_REGEX.matches(ids.publicId()))
            assertTrue(Ids.REFERENCE_REGEX.matches(ids.reference()))
            assertTrue(Regex("[0-9a-f]{40}").matches(ids.hexToken(20)))
            assertTrue(Ids.UUID_REGEX.matches(ids.uuid()))
        }
    }

    @Test
    fun `no value repeats within one kind`() {
        val ids = SeqIds()
        val n = 20_000
        assertEquals(n, (1..n).map { ids.publicId() }.toSet().size)
        assertEquals(n, (1..n).map { ids.reference() }.toSet().size)
        assertEquals(n, (1..n).map { ids.hexToken(20) }.toSet().size)
        assertEquals(n, (1..n).map { ids.uuid() }.toSet().size)
    }

    @Test
    fun `hexToken honours its size and keeps the low order characters of a wide counter`() {
        assertEquals("01", SeqIds().hexToken(1))
        assertEquals("00", SeqIds(start = 0xff).hexToken(1))   // counter 0x100 -> low byte
        assertEquals("ab", SeqIds(start = 0x1aa).hexToken(1))  // counter 0x1ab -> low byte
        assertEquals(64, SeqIds().hexToken(32).length)
    }

    @Test
    fun `is safe under concurrent use`() {
        val ids = SeqIds()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val threads = List(8) { Thread { repeat(1_000) { seen.add(ids.reference()) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8_000, seen.size)
    }
}
