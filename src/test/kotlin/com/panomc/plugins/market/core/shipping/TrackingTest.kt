package com.panomc.plugins.market.core.shipping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `TrackingSchedule`, `TrackingUrl` and `TrackingEventRules` (10 sections 7.2 and 10.2, tests 45, 48, 49 and 63 of section 16). */
class TrackingTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val created = 1_000_000_000L

    @Test
    fun `first poll is one hour after creation`() {
        assertEquals(created + hour, TrackingSchedule.first(created))
    }

    @Test
    fun `poll cadence by age`() {
        fun next(age: Long) = TrackingSchedule.next(created, created + age).let { it.nextPollAt?.minus(created + age) to it.stale }

        assertEquals(3 * hour to false, next(hour))
        assertEquals(3 * hour to false, next(47 * hour))
        assertEquals(6 * hour to false, next(49 * hour))
        assertEquals(6 * hour to false, next(9 * day))
        assertEquals(12 * hour to false, next(11 * day))
        assertEquals(12 * hour to false, next(44 * day))
        assertEquals(null to true, next(46 * day))
    }

    @Test
    fun `cadence boundaries`() {
        fun gap(age: Long) = TrackingSchedule.next(created, created + age).nextPollAt?.minus(created + age)

        assertEquals(3 * hour, gap(2 * day - 1))
        assertEquals(6 * hour, gap(2 * day))
        assertEquals(6 * hour, gap(10 * day - 1))
        assertEquals(12 * hour, gap(10 * day))
        assertEquals(12 * hour, gap(45 * day - 1))
        assertNull(gap(45 * day))
    }

    // ---- TrackingUrl ----

    @Test
    fun `template needs http or https and the placeholder`() {
        assertTrue(TrackingUrl.isValidTemplate("https://track.example.com/t?no={tracking}"))
        assertTrue(TrackingUrl.isValidTemplate("http://example.com/{tracking}"))
        assertFalse(TrackingUrl.isValidTemplate("https://track.example.com/t"))
        assertFalse(TrackingUrl.isValidTemplate("ftp://example.com/{tracking}"))
        assertFalse(TrackingUrl.isValidTemplate("javascript:alert({tracking})"))
        assertFalse(TrackingUrl.isValidTemplate("//example.com/{tracking}"))
        assertFalse(TrackingUrl.isValidTemplate("https://example.com/{tracking}" + "x".repeat(512)))
        assertFalse(TrackingUrl.isValidTemplate("{tracking}"))
    }

    @Test
    fun `rendering url-encodes the number`() {
        val t = "https://track.example.com/t?no={tracking}"

        assertEquals("https://track.example.com/t?no=ABC123", TrackingUrl.render(t, "ABC123"))
        assertEquals("https://track.example.com/t?no=AB%2012%2F3%26x", TrackingUrl.render(t, "AB 12/3&x"))
        assertEquals("https://track.example.com/t?no=%C3%BC%C5%9F", TrackingUrl.render(t, "üş"))
        assertEquals("https://x/a%2Fa/a%2Fa", TrackingUrl.render("https://x/{tracking}/{tracking}", "a/a"))
    }

    @Test
    fun `a provider url is accepted only as http or https within 1024 characters`() {
        assertEquals("https://track.example.com/abc", TrackingUrl.accept(" https://track.example.com/abc "))
        assertEquals("http://example.com", TrackingUrl.accept("http://example.com"))
        assertNull(TrackingUrl.accept("javascript:alert(1)"))
        assertNull(TrackingUrl.accept("data:text/html,<script>"))
        assertNull(TrackingUrl.accept("ftp://example.com/x"))
        assertNull(TrackingUrl.accept("https://"))
        assertNull(TrackingUrl.accept("not a url"))
        assertNull(TrackingUrl.accept("   "))
        assertNull(TrackingUrl.accept(null))
        assertNull(TrackingUrl.accept("https://example.com/" + "x".repeat(1005)))
        assertEquals(1024, TrackingUrl.accept("https://example.com/" + "x".repeat(1004))!!.length)
    }

    // ---- TrackingEventRules ----

    @Test
    fun `future events are clamped to now beyond 24 hours`() {
        val now = 1_000L * day

        assertEquals(now + day, TrackingEventRules.clampOccurredAt(now + day, now))
        assertEquals(now, TrackingEventRules.clampOccurredAt(now + day + 1, now))
        assertEquals(now - day, TrackingEventRules.clampOccurredAt(now - day, now))
    }

    @Test
    fun `dedupe key is the provider event id or a stable sha1 of the content`() {
        assertEquals("evt-1", TrackingEventRules.dedupeKey("evt-1", "IN_TRANSIT", 5, null, null))
        assertEquals("e".repeat(128), TrackingEventRules.dedupeKey("e".repeat(300), "IN_TRANSIT", 5, null, null))

        val a = TrackingEventRules.dedupeKey(null, "IN_TRANSIT", 5, "Ankara", "X1")
        assertEquals(40, a.length)
        assertEquals(a, TrackingEventRules.dedupeKey("", "IN_TRANSIT", 5, "Ankara", "X1"))
        assertEquals(a, TrackingEventRules.dedupeKey(null, "IN_TRANSIT", 5, "Ankara", "X1"))
        assertNotEquals(a, TrackingEventRules.dedupeKey(null, "DELIVERED", 5, "Ankara", "X1"))
        assertNotEquals(a, TrackingEventRules.dedupeKey(null, "IN_TRANSIT", 6, "Ankara", "X1"))
        assertNotEquals(a, TrackingEventRules.dedupeKey(null, "IN_TRANSIT", 5, "Izmir", "X1"))
        assertNotEquals(a, TrackingEventRules.dedupeKey(null, "IN_TRANSIT", 5, "Ankara", "X2"))
        assertEquals("4d7a3d2318dc29e49541e79ef20ed75d64037786", a)     // sha1("IN_TRANSIT|5|Ankara|X1"), computed with sha1sum
        assertEquals(TrackingEventRules.dedupeKey(null, "S", 1, null, null), TrackingEventRules.dedupeKey(null, "S", 1, "", ""))
    }

    @Test
    fun `dedupe key has a fixed vector`() {
        // sha1("DELIVERED|1760000000000|Istanbul|D") computed once with sha1sum and frozen here
        assertEquals("dd38a64776a71c22659aaf481c29274fdb8efdeb", TrackingEventRules.dedupeKey(null, "DELIVERED", 1760000000000, "Istanbul", "D"))
    }

    @Test
    fun `limits per update and per shipment`() {
        assertEquals(200, TrackingEventRules.room(201, 0))
        assertEquals(200, TrackingEventRules.room(500, 100))
        assertEquals(150, TrackingEventRules.room(200, 350))
        assertEquals(0, TrackingEventRules.room(10, 500))
        assertEquals(0, TrackingEventRules.room(10, 700))
        assertEquals(5, TrackingEventRules.room(5, 0))
    }

    @Test
    fun `truncation`() {
        assertEquals("abc", TrackingEventRules.truncate("abcdef", 3))
        assertEquals("ab", TrackingEventRules.truncate("ab", 3))
        assertNull(TrackingEventRules.truncate(null, 3))
    }
}
