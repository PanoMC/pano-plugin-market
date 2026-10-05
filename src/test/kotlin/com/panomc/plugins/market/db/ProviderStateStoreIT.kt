package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketProviderStateDaoImpl
import com.panomc.plugins.market.db.model.ProviderStateKind
import com.panomc.plugins.market.provider.ProviderStateMaintenance
import com.panomc.plugins.market.provider.ProviderStateStoreImpl
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** `market_provider_state` behind `ProviderStateStore` (02 section 3, 01 section 6.6): encrypted values, TTL, atomic compareAndSet. */
class ProviderStateStoreIT : MarketDaoITBase() {
    private val dao = MarketProviderStateDaoImpl()
    private val cipher = SecretCipher(ByteArray(32) { (it + 5).toByte() })
    private val clock = FakeClock()

    private fun store(
        provider: String = "stripe",
        kind: ProviderStateKind = ProviderStateKind.PAYMENT,
        withCipher: SecretCipher = cipher
    ) = ProviderStateStoreImpl(kind, provider, dao, marketDb(clock = clock), withCipher, clock)

    private suspend fun rawValue(key: String, provider: String = "stripe", kind: String = "PAYMENT"): String? =
        sql("SELECT `value` FROM `${prefix}market_provider_state` WHERE kind = ? AND providerId = ? AND stateKey = ?", kind, provider, key)
            .firstOrNull()?.getString("value")

    // ---- basics

    @Test
    fun `a value round trips and is stored encrypted`(): Unit = runBlocking {
        val s = store()
        assertNull(s.get("token"))
        s.put("token", "oauth-access-token-123")
        assertEquals("oauth-access-token-123", s.get("token"))
        val raw = rawValue("token")!!
        assertTrue(raw.startsWith("v1:"), raw)
        assertFalse(raw.contains("oauth-access-token"))
        assertEquals(1L, count("market_provider_state"))
    }

    @Test
    fun `put overwrites in place and keeps one row`(): Unit = runBlocking {
        val s = store()
        s.put("k", "one")
        val created = sql("SELECT createdAt, updatedAt FROM `${prefix}market_provider_state`").single()
        clock.advance(5_000)
        s.put("k", "two")
        assertEquals("two", s.get("k"))
        assertEquals(1L, count("market_provider_state"))
        val row = sql("SELECT createdAt, updatedAt FROM `${prefix}market_provider_state`").single()
        assertEquals(created.getLong("createdAt"), row.getLong("createdAt"))
        assertEquals(created.getLong("updatedAt") + 5_000, row.getLong("updatedAt"))
    }

    @Test
    fun `remove deletes the key and is harmless for an absent key`(): Unit = runBlocking {
        val s = store()
        s.put("k", "v")
        s.remove("k")
        assertNull(s.get("k"))
        assertEquals(0L, count("market_provider_state"))
        s.remove("k")
        s.remove("never-existed")
    }

    @Test
    fun `values with unicode, newlines and large size survive`(): Unit = runBlocking {
        val s = store()
        val big = "x".repeat(30_000)
        for ((k, v) in mapOf("u" to "ünï ✓ 日本語 😀", "n" to "a\nb\r\n\"c\"\\", "e" to "", "big" to big)) {
            s.put(k, v)
            assertEquals(v, s.get(k), k)
        }
    }

    @Test
    fun `entries are scoped by kind and provider`(): Unit = runBlocking {
        val stripe = store("stripe")
        val paypal = store("paypal")
        val ship = store("stripe", ProviderStateKind.SHIPPING)
        stripe.put("k", "stripe-payment")
        paypal.put("k", "paypal-payment")
        ship.put("k", "stripe-shipping")
        assertEquals("stripe-payment", stripe.get("k"))
        assertEquals("paypal-payment", paypal.get("k"))
        assertEquals("stripe-shipping", ship.get("k"))
        paypal.remove("k")
        assertEquals("stripe-payment", stripe.get("k"))
        assertNull(paypal.get("k"))
        assertTrue(stripe.compareAndSet("k", "stripe-payment", "changed"))
        assertEquals("stripe-shipping", ship.get("k"))
        assertEquals(2L, count("market_provider_state"))
    }

    @Test
    fun `another store instance on the same database reads what was written`(): Unit = runBlocking {
        store().put("shared", "value")
        assertEquals("value", store().get("shared"))
    }

    // ---- TTL

    @Test
    fun `a value with a ttl disappears when the clock passes it, before any purge`(): Unit = runBlocking {
        val s = store()
        s.put("short", "v", ttlSeconds = 60)
        s.put("forever", "v")
        clock.advance(59_999)
        assertEquals("v", s.get("short"))
        clock.advance(1)
        assertNull(s.get("short"), "expiresAt <= now is expired")
        assertEquals("v", s.get("forever"))
        assertEquals(2L, count("market_provider_state"), "the row stays until the purge")
        s.put("short", "again", ttlSeconds = 60)
        assertEquals("again", s.get("short"))
        assertEquals(2L, count("market_provider_state"))
        assertEquals(clock.now() + 60_000, sql("SELECT expiresAt FROM `${prefix}market_provider_state` WHERE stateKey = 'short'").single().getLong("expiresAt"))
    }

    @Test
    fun `put without a ttl clears an earlier expiry`(): Unit = runBlocking {
        val s = store()
        s.put("k", "v", ttlSeconds = 10)
        s.put("k", "w")
        clock.advance(3_600_000)
        assertEquals("w", s.get("k"))
    }

    @Test
    fun `purgeExpired deletes only expired rows`(): Unit = runBlocking {
        val s = store()
        s.put("a", "1", ttlSeconds = 10)
        s.put("b", "2", ttlSeconds = 100)
        s.put("c", "3")
        clock.advance(50_000)
        val maintenance = ProviderStateMaintenance(dao, marketDb(clock = clock), clock)
        assertEquals(1, maintenance.purgeExpired())
        assertEquals(0, maintenance.purgeExpired())
        assertEquals(setOf("b", "c"), sql("SELECT stateKey FROM `${prefix}market_provider_state`").map { it.getString("stateKey") }.toSet())
    }

    @Test
    fun `a non positive ttl is refused`(): Unit = runBlocking {
        val s = store()
        for (ttl in listOf(0L, -1L)) {
            val e = assertThrows(ProviderException::class.java) { runBlocking { s.put("k", "v", ttl) } }
            assertEquals(ProviderErrorCode.INVALID_REQUEST, e.code)
            assertThrows(ProviderException::class.java) { runBlocking { s.compareAndSet("k", null, "v", ttl) } }
        }
        assertEquals(0L, count("market_provider_state"))
    }

    // ---- compareAndSet

    @Test
    fun `compareAndSet creates an absent key only when null is expected`(): Unit = runBlocking {
        val s = store()
        assertFalse(s.compareAndSet("k", "something", "v"), "absent but a value was expected")
        assertNull(s.get("k"))
        assertTrue(s.compareAndSet("k", null, "first"))
        assertEquals("first", s.get("k"))
        assertFalse(s.compareAndSet("k", null, "second"), "present: null no longer matches")
        assertEquals("first", s.get("k"))
    }

    @Test
    fun `compareAndSet swaps only on an exact match of the decrypted value`(): Unit = runBlocking {
        val s = store()
        s.put("k", "one")
        assertFalse(s.compareAndSet("k", "ONE", "x"))
        assertFalse(s.compareAndSet("k", "one ", "x"))
        assertFalse(s.compareAndSet("k", "", "x"))
        assertEquals("one", s.get("k"))
        assertTrue(s.compareAndSet("k", "one", "two"))
        assertEquals("two", s.get("k"))
        assertTrue(s.compareAndSet("k", "two", "three", ttlSeconds = 30))
        assertEquals(clock.now() + 30_000, sql("SELECT expiresAt FROM `${prefix}market_provider_state`").single().getLong("expiresAt"))
        assertEquals(1L, count("market_provider_state"))
        assertTrue(rawValue("k")!!.startsWith("v1:"))
    }

    @Test
    fun `an expired key counts as absent for compareAndSet`(): Unit = runBlocking {
        val s = store()
        s.put("k", "old", ttlSeconds = 10)
        clock.advance(10_000)
        assertFalse(s.compareAndSet("k", "old", "x"), "the expired value cannot be expected")
        assertTrue(s.compareAndSet("k", null, "fresh", ttlSeconds = 100))
        assertEquals("fresh", s.get("k"))
        assertEquals(1L, count("market_provider_state"), "the expired row was reused")
    }

    @Test
    fun `a value that cannot be decrypted reads as absent, never matches and can be overwritten`(): Unit = runBlocking {
        val foreign = SecretCipher(ByteArray(32) { 77 })
        store(withCipher = foreign).put("k", "written with another key")
        val s = store()
        assertNull(s.get("k"))
        assertFalse(s.compareAndSet("k", null, "x"), "a row is there")
        assertFalse(s.compareAndSet("k", "written with another key", "x"))
        s.put("k", "re-entered")
        assertEquals("re-entered", s.get("k"))
    }

    @Test
    fun `compareAndSet is atomic when 16 callers race to create the same key`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val key = "race-$round"
            val s = store()
            val results = Race.run(16) { i -> s.compareAndSet(key, null, "actor-$i") }
            assertTrue(results.all { it.isSuccess }, results.toString())
            assertEquals(1, results.count { it.getOrThrow() }, "exactly one creator wins")
            val winner = s.get(key)!!
            assertTrue(winner.startsWith("actor-"))
            assertEquals(1L, count("market_provider_state", "stateKey = ?", key))
        }
    }

    @Test
    fun `compareAndSet is atomic when callers swap the same value`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val key = "swap-$round"
            val s = store()
            s.put(key, "start")
            val results = Race.run(16) { i -> s.compareAndSet(key, "start", "actor-$i") }
            assertTrue(results.all { it.isSuccess }, results.toString())
            assertEquals(1, results.count { it.getOrThrow() }, "exactly one swap wins")
        }
    }

    @Test
    fun `a counter built on compareAndSet loses no increment under contention`(): Unit = runBlocking {
        val s = store()
        s.put("counter", "0")
        val retries = AtomicInteger()
        val results = Race.run(8) {
            repeat(10) {
                while (true) {
                    val current = s.get("counter")!!
                    if (s.compareAndSet("counter", current, (current.toLong() + 1).toString())) break
                    retries.incrementAndGet()
                }
            }
        }
        assertTrue(results.all { it.isSuccess }, results.toString())
        assertEquals("80", s.get("counter"))
        assertTrue(retries.get() > 0, "the callers really contended")
    }

    // ---- validation

    @Test
    fun `keys and values are validated`(): Unit = runBlocking {
        val s = store()
        for (bad in listOf("", "k".repeat(192))) {
            assertEquals(ProviderErrorCode.INVALID_REQUEST, assertThrows(ProviderException::class.java) { runBlocking { s.put(bad, "v") } }.code)
            assertThrows(ProviderException::class.java) { runBlocking { s.get(bad) } }
            assertThrows(ProviderException::class.java) { runBlocking { s.remove(bad) } }
            assertThrows(ProviderException::class.java) { runBlocking { s.compareAndSet(bad, null, "v") } }
        }
        s.put("k".repeat(191), "ok")
        assertEquals("ok", s.get("k".repeat(191)))
        assertThrows(ProviderException::class.java) { runBlocking { s.put("big", "x".repeat(40_001)) } }
        assertThrows(ProviderException::class.java) { runBlocking { s.compareAndSet("big", null, "x".repeat(40_001)) } }
        s.put("fits", "x".repeat(40_000))
        assertEquals(40_000, s.get("fits")!!.length)
    }

    // ---- housekeeping and values

    @Test
    fun `deleteUserKeys removes the keys of one user in every provider and nothing else`(): Unit = runBlocking {
        val stripe = store("stripe")
        val paypal = store("paypal")
        val ship = store("ups", ProviderStateKind.SHIPPING)
        for (s in listOf(stripe, paypal, ship)) {
            s.put("user:5:customer", "cus_5")
            s.put("user:55:customer", "cus_55")
            s.put("user:5", "no trailing colon")
            s.put("global", "g")
        }
        val maintenance = ProviderStateMaintenance(dao, marketDb(clock = clock), clock)
        assertEquals(3, maintenance.deleteUserKeys(5))
        for (s in listOf(stripe, paypal, ship)) {
            assertNull(s.get("user:5:customer"))
            assertEquals("cus_55", s.get("user:55:customer"))
            assertEquals("no trailing colon", s.get("user:5"))
            assertEquals("g", s.get("global"))
        }
        assertEquals(0, maintenance.deleteUserKeys(5))
    }

    @Test
    fun `values returns the live readable values of one provider for the redactor`(): Unit = runBlocking {
        val s = store()
        s.put("a", "token-aaaaaa")
        s.put("b", "token-bbbbbb", ttlSeconds = 10)
        s.put("c", "token-expired", ttlSeconds = 1)
        store("paypal").put("a", "token-other-provider")
        store(withCipher = SecretCipher(ByteArray(32) { 1 })).put("unreadable", "token-unreadable")
        clock.advance(2_000)
        assertEquals(setOf("token-aaaaaa", "token-bbbbbb"), s.values())
    }

    @Test
    fun `the row carries the kind, provider and key columns`(): Unit = runBlocking {
        store("ups", ProviderStateKind.SHIPPING).put("session", "v")
        val row = sql("SELECT kind, providerId, stateKey, expiresAt FROM `${prefix}market_provider_state`").single()
        assertEquals("SHIPPING", row.getString("kind"))
        assertEquals("ups", row.getString("providerId"))
        assertEquals("session", row.getString("stateKey"))
        assertNull(row.getValue("expiresAt"))
    }
}
