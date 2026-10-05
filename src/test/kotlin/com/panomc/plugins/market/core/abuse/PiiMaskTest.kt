package com.panomc.plugins.market.core.abuse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PiiMaskTest {
    @Test
    fun `email`() {
        assertEquals("j***@e***.com", PiiMask.email("john@example.com"))
        assertEquals("a***@b***.uk", PiiMask.email("al@bb.co.uk"))
        assertEquals("j***@l***", PiiMask.email("j@localhost"))
        assertEquals("***", PiiMask.email("nonsense"))
        assertEquals("***@e***.com", PiiMask.email("@example.com"))
        assertEquals("j***@***", PiiMask.email("john@"))
        assertNull(PiiMask.email(null))
    }

    @Test
    fun `email never leaks more than first characters`() {
        val m = PiiMask.email("verysecret@privatehost.org")!!
        assertFalse(m.contains("secret"))
        assertFalse(m.contains("private"))
        assertTrue(m.endsWith(".org"))
    }

    @Test
    fun `ip`() {
        assertEquals("203.0.113.x", PiiMask.ip("203.0.113.57"))
        assertEquals("2001:db8:1::x", PiiMask.ip("2001:db8:1:2:3:4:5:6"))
        assertEquals("203.0.113.x/24", PiiMask.ip("203.0.113.0/24"))
        assertEquals("2001:db8:0::x/32", PiiMask.ip("2001:db8::/32"))
        assertEquals("***", PiiMask.ip("garbage"))
        assertNull(PiiMask.ip(null))
    }
}
