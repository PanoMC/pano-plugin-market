package com.panomc.plugins.market.core.abuse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlockMatcherTest {
    private val now = 1_760_000_000_000L
    private fun e(id: Long, t: BlockType, v: String, exp: Long? = null) = BlockEntry(id, t, BlockValue.normalize(t, v)!!, exp)

    @Test
    fun `player match is case insensitive`() {
        val m = BlockMatcher(listOf(e(1, BlockType.PLAYER, "Notch")))
        assertEquals(1L, m.match(BlockSubjects(usernames = setOf("NOTCH")), now)?.id)
        assertEquals(1L, m.match(BlockSubjects(usernames = setOf("notch", "x")), now)?.id)
        assertNull(m.match(BlockSubjects(usernames = setOf("notch2")), now))
    }

    @Test
    fun `email is lower cased and domain blocks match`() {
        val m = BlockMatcher(listOf(e(1, BlockType.EMAIL, "Bad@Example.com"), e(2, BlockType.EMAIL, "@Spam.Org")))
        assertEquals(1L, m.match(BlockSubjects(emails = setOf("BAD@example.COM")), now)?.id)
        assertEquals(2L, m.match(BlockSubjects(emails = setOf("anyone@SPAM.org")), now)?.id)
        assertNull(m.match(BlockSubjects(emails = setOf("anyone@sub.spam.org")), now))
        assertNull(m.match(BlockSubjects(emails = setOf("good@example.com")), now))
        assertNull(m.match(BlockSubjects(emails = setOf("")), now))
    }

    @Test
    fun `ip exact and cidr including ipv6`() {
        val m = BlockMatcher(listOf(e(1, BlockType.IP, "203.0.113.7"), e(2, BlockType.IP, "198.51.100.0/24"), e(3, BlockType.IP, "2001:db8::/32")))
        assertEquals(1L, m.match(BlockSubjects(ip = "203.0.113.7"), now)?.id)
        assertNull(m.match(BlockSubjects(ip = "203.0.113.8"), now))
        assertEquals(2L, m.match(BlockSubjects(ip = "198.51.100.255"), now)?.id)
        assertEquals(3L, m.match(BlockSubjects(ip = "2001:db8:aa::1"), now)?.id)
        assertNull(m.match(BlockSubjects(ip = "2001:db9::1"), now))
        assertNull(m.match(BlockSubjects(ip = null), now))
        assertNull(m.match(BlockSubjects(ip = "garbage"), now))
    }

    @Test
    fun `user id matches`() {
        val m = BlockMatcher(listOf(e(1, BlockType.USER, "42")))
        assertEquals(1L, m.match(BlockSubjects(userIds = setOf(7L, 42L)), now)?.id)
        assertNull(m.match(BlockSubjects(userIds = setOf(4L)), now))
    }

    @Test
    fun `expired rows are ignored and expiry boundary is exclusive`() {
        val m = BlockMatcher(listOf(e(1, BlockType.PLAYER, "abc", now - 1), e(2, BlockType.PLAYER, "def", now), e(3, BlockType.PLAYER, "ghi", now + 1)))
        assertNull(m.match(BlockSubjects(usernames = setOf("abc")), now))
        assertNull(m.match(BlockSubjects(usernames = setOf("def")), now))
        assertEquals(3L, m.match(BlockSubjects(usernames = setOf("ghi")), now)?.id)
        assertNull(m.match(BlockSubjects(usernames = setOf("ghi")), now + 1))
    }

    @Test
    fun `first hit wins in order user player email ip`() {
        val m = BlockMatcher(listOf(
            e(1, BlockType.IP, "1.2.3.4"), e(2, BlockType.EMAIL, "a@b.co"), e(3, BlockType.PLAYER, "steve"), e(4, BlockType.USER, "9"),
        ))
        val all = BlockSubjects(setOf("steve"), setOf(9L), setOf("a@b.co"), "1.2.3.4")
        assertEquals(4L, m.match(all, now)?.id)
        assertEquals(BlockType.USER, m.match(all, now)?.type)
        assertEquals(BlockType.PLAYER, m.match(BlockSubjects(setOf("steve"), emptySet(), setOf("a@b.co"), "1.2.3.4"), now)?.type)
        assertEquals(BlockType.EMAIL, m.match(BlockSubjects(emptySet(), emptySet(), setOf("a@b.co"), "1.2.3.4"), now)?.type)
        assertEquals(BlockType.IP, m.match(BlockSubjects(ip = "1.2.3.4"), now)?.type)
    }

    @Test
    fun `empty list never matches`() {
        assertNull(BlockMatcher(emptyList()).match(BlockSubjects(setOf("a"), setOf(1L), setOf("a@b.c"), "1.1.1.1"), now))
    }
}

class BlockValueTest {
    @Test
    fun `player`() {
        assertEquals("steve_1", BlockValue.normalize(BlockType.PLAYER, " Steve_1 "))
        assertEquals(".bedrock*", BlockValue.normalize(BlockType.PLAYER, ".Bedrock*"))
        assertNull(BlockValue.normalize(BlockType.PLAYER, "..."))
        assertNull(BlockValue.normalize(BlockType.PLAYER, "a b"))
        assertNull(BlockValue.normalize(BlockType.PLAYER, "a".repeat(33)))
        assertNull(BlockValue.normalize(BlockType.PLAYER, ""))
        assertNull(BlockValue.normalize(BlockType.PLAYER, null))
    }

    @Test
    fun `user`() {
        assertEquals("42", BlockValue.normalize(BlockType.USER, "42"))
        assertEquals("42", BlockValue.normalize(BlockType.USER, "042"))
        for (bad in listOf("0", "-1", "4x", "", "99999999999999999999", "1.5")) assertNull(BlockValue.normalize(BlockType.USER, bad), bad)
    }

    @Test
    fun `email`() {
        assertEquals("a@b.com", BlockValue.normalize(BlockType.EMAIL, " A@B.com "))
        assertEquals("@example.com", BlockValue.normalize(BlockType.EMAIL, "@Example.COM"))
        for (bad in listOf("a", "a@", "@a", "a@@b.com", "a b@c.com", "a,b@c.com", "a;b@c.com", "<a>@c.com", "a@b.com\r\n", "a\u0000@b.com",
            "@", "@.com", "@a..com", "@-a.com", "a@b c.com", "x@" + "a".repeat(250) + ".com")) {
            assertNull(BlockValue.normalize(BlockType.EMAIL, bad), bad)
        }
        assertNull(BlockValue.normalize(BlockType.EMAIL, "a".repeat(250) + "@b.com"))
    }

    @Test
    fun `ip canonical and limits`() {
        assertEquals("1.2.3.4", BlockValue.normalize(BlockType.IP, "1.2.3.4/32"))
        assertEquals("10.0.0.0/8", BlockValue.normalize(BlockType.IP, "10.1.2.3/8"))
        assertEquals("::1", BlockValue.normalize(BlockType.IP, "0:0:0:0:0:0:0:1"))
        assertEquals("2001:db8::/32", BlockValue.normalize(BlockType.IP, "2001:DB8:1::/32"))
        assertNull(BlockValue.normalize(BlockType.IP, "10.0.0.0/7"))
        assertTrue(BlockValue.isRangeTooWide("10.0.0.0/7"))
        assertTrue(BlockValue.isRangeTooWide("::/15"))
        assertNull(BlockValue.normalize(BlockType.IP, "2000::/15"))
        assertNotNull(BlockValue.normalize(BlockType.IP, "2000::/16"))
        assertFalse(BlockValue.isRangeTooWide("10.0.0.0/8"))
        assertFalse(BlockValue.isRangeTooWide("garbage"))
        assertNull(BlockValue.normalize(BlockType.IP, "nope"))
    }

    @Test
    fun `unknown type name is invalid`() {
        assertNull(BlockValue.normalize("BOGUS", "x"))
        assertEquals("a", BlockValue.normalize("PLAYER", "A"))
    }
}
