package com.panomc.plugins.market.core.abuse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IpRangeTest {
    @Test
    fun `ipv4 single and cidr`() {
        assertEquals("1.2.3.4", IpRange.parse("1.2.3.4")!!.canonical())
        assertEquals("10.0.0.0/8", IpRange.parse("10.9.8.7/8")!!.canonical())
        assertEquals("1.2.3.4", IpRange.parse("1.2.3.4/32")!!.canonical())
        assertTrue(IpRange.parse("192.168.1.0/24")!!.contains("192.168.1.200"))
        assertFalse(IpRange.parse("192.168.1.0/24")!!.contains("192.168.2.1"))
        assertTrue(IpRange.parse("10.0.0.0/9")!!.contains("10.127.255.255"))
        assertFalse(IpRange.parse("10.0.0.0/9")!!.contains("10.128.0.0"))
        assertTrue(IpRange.parse("0.0.0.0/0")!!.contains("8.8.8.8"))
    }

    @Test
    fun `invalid input`() {
        for (bad in listOf("", " ", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1.2.3.4/33", "1.2.3.4/", "1.2.3.4/-1", "1.2.3.4/x",
            "abc", "1.2.3.4/+8", ":::", "1::2::3", "12345::", "g::1", "fe80::1%eth0", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "::1/129",
            "1.2.3.4::", "1:2:3:4:5:6:7:8::")) {
            assertNull(IpRange.parse(bad), bad)
        }
        assertNull(IpRange.parse(null))
    }

    @Test
    fun `ipv6 canonical form is compressed and lower case`() {
        assertEquals("::1", IpRange.parse("0:0:0:0:0:0:0:1")!!.canonical())
        assertEquals("::", IpRange.parse("::")!!.canonical())
        assertEquals("2001:db8::1", IpRange.parse("2001:0DB8:0:0:0:0:0:1")!!.canonical())
        assertEquals("2001:db8::", IpRange.parse("2001:db8:0:0:0:0:0:0")!!.canonical())
        assertEquals("1:0:0:2::3", IpRange.parse("1:0:0:2:0:0:0:3")!!.canonical())
        assertEquals("1::2:0:0:3", IpRange.parse("1:0:0:0:2:0:0:3")!!.canonical())
        assertEquals("2001:db8:0:1:1:1:1:1", IpRange.parse("2001:db8:0:1:1:1:1:1")!!.canonical())
        assertEquals("2001:db8::/32", IpRange.parse("2001:db8:ffff::/32")!!.canonical())
        assertEquals("::1", IpRange.parse("::1/128")!!.canonical())
    }

    @Test
    fun `ipv6 cidr membership`() {
        val r = IpRange.parse("2001:db8::/32")!!
        assertTrue(r.contains("2001:db8:1234::5"))
        assertTrue(r.contains("2001:0db8:ffff:ffff:ffff:ffff:ffff:ffff"))
        assertFalse(r.contains("2001:db9::1"))
        assertFalse(r.contains("1.2.3.4"))
        val odd = IpRange.parse("2001:db8:8000::/33")!!
        assertTrue(odd.contains("2001:db8:ffff::1"))
        assertFalse(odd.contains("2001:db8:7fff::1"))
        assertTrue(IpRange.parse("::1")!!.contains("0:0:0:0:0:0:0:1"))
    }

    @Test
    fun `ipv4 mapped ipv6 is the ipv4 address`() {
        assertEquals("1.2.3.4", IpRange.parse("::ffff:1.2.3.4")!!.canonical())
        assertEquals("1.2.3.4", IpRange.parse("::ffff:102:304")!!.canonical())
        assertTrue(IpRange.parse("1.2.3.0/24")!!.contains("::ffff:1.2.3.9"))
        assertEquals("64:ff9b::102:304", IpRange.parse("64:ff9b::1.2.3.4")!!.canonical())
    }

    @Test
    fun `contains only matches single addresses of the same family`() {
        val r = IpRange.parse("10.0.0.0/8")!!
        assertFalse(r.contains("10.0.0.0/8"))
        assertFalse(r.contains("nonsense"))
        assertFalse(r.contains(""))
    }

    @Test
    fun `bucket key groups ipv6 by 64`() {
        assertEquals("203.0.113.5", IpRange.bucketKey("203.0.113.5"))
        val a = IpRange.bucketKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        assertEquals("2001:db8:1:2::/64", a)
        assertEquals(a, IpRange.bucketKey("2001:DB8:1:2::1"))
        assertNotEquals(a, IpRange.bucketKey("2001:db8:1:3::1"))
        assertNull(IpRange.bucketKey("x"))
        assertNull(IpRange.bucketKey(null))
        assertNull(IpRange.bucketKey("10.0.0.0/8"))
    }
}
