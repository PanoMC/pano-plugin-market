package com.panomc.plugins.market.core.shipping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `PhoneNormalizer` and `CallingCodes` (10 section 3.2, test 5 of section 16). */
class PhoneNormalizerTest {
    @Test
    fun `turkish national number loses the trunk zero and gets the country code`() {
        assertEquals("+905321234567", PhoneNormalizer.toE164("0532 123 45 67", "TR"))
        assertEquals("+905321234567", PhoneNormalizer.toE164("532-123-45-67", "TR"))
        assertEquals("+905321234567", PhoneNormalizer.toE164("(0532) 123 45 67", "tr"))
    }

    @Test
    fun `leading double zero becomes plus and a plus is kept`() {
        assertEquals("+4930123456", PhoneNormalizer.toE164("00 49 30 123456", "TR"))
        assertEquals("+12125550100", PhoneNormalizer.toE164("+1 (212) 555-0100", "TR"))
        assertEquals("+12125550100", PhoneNormalizer.toE164("+1 (212) 555-0100", null))
    }

    @Test
    fun `a plus inside the number is dropped`() {
        assertEquals("+905321234567", PhoneNormalizer.toE164("+90 532 123+45 67", null))
    }

    @Test
    fun `too short or too long numbers are invalid`() {
        assertNull(PhoneNormalizer.toE164("123", "TR"))
        assertNull(PhoneNormalizer.toE164("+1234567", null))       // 7 digits
        assertEquals("+12345678", PhoneNormalizer.toE164("+12345678", null))   // 8 digits is the minimum
        assertEquals("+123456789012345", PhoneNormalizer.toE164("+123456789012345", null))   // 15 digits is the maximum
        assertNull(PhoneNormalizer.toE164("+1234567890123456", null))
    }

    @Test
    fun `unknown country without a plus and empty input are invalid`() {
        assertNull(PhoneNormalizer.toE164("5321234567", "XX"))
        assertNull(PhoneNormalizer.toE164("5321234567", null))
        assertNull(PhoneNormalizer.toE164("", "TR"))
        assertNull(PhoneNormalizer.toE164("abc", "TR"))
        assertNull(PhoneNormalizer.toE164("+", "TR"))
        assertNull(PhoneNormalizer.toE164(null, "TR"))
    }

    @Test
    fun `a number may not start with zero after the plus`() {
        assertNull(PhoneNormalizer.toE164("+0123456789", null))
        assertFalse(PhoneNormalizer.isE164("+0123456789"))
        assertTrue(PhoneNormalizer.isE164("+905321234567"))
        assertFalse(PhoneNormalizer.isE164("905321234567"))
    }

    @Test
    fun `calling code table covers every country and nothing else`() {
        assertEquals(Countries.ALL, CallingCodes.all.keys)
        assertTrue(CallingCodes.all.values.all { Regex("[1-9][0-9]{0,2}").matches(it) })
        assertEquals("90", CallingCodes.of("TR"))
        assertEquals("49", CallingCodes.of("DE"))
        assertEquals("1", CallingCodes.of("US"))
        assertEquals("44", CallingCodes.of("GB"))
        assertNull(CallingCodes.of("XX"))
        assertNull(CallingCodes.of(null))
    }
}
