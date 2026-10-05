package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.core.abuse.ThrottlePolicy
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.routes.user.gift.GiftCodeGuard
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * The brute-force lock of codes (11 section 12.2, MK-152): coupon and creator codes share the scope `COUPON` (one input surface), gift codes
 * use `GIFT`. A request that carries a code first asks [lockedUntil]: a locked subject is refused even with a valid code, the code is not looked
 * up. A code that is not found (unknown, malformed or soft-deleted) is [recordUnknown]ed; a code that exists but is expired, used up or not
 * applicable is never counted, and a successful lookup never resets a counter (an attacker could interleave one known code).
 *
 * Subjects ([subjectsOf]): `ip:<bucketKey>` when the address is trusted, `b:<buyerKey>` for a session or a guest who typed a name, otherwise the
 * single subject `anon` with ten times the threshold. The threshold and the lock length are read from [config] at every call
 * (`couponLockThreshold`, `couponLockMinutes`; the window equals the lock length); a threshold of 0 disables the mechanism.
 *
 * De-duplication: the same wrong code tried again by the same subject inside the window counts once. In memory only: the last
 * [AbuseLimits.CODE_DEDUP_HASHES_PER_SUBJECT] SHA-256 hashes per subject (never the code itself), [AbuseLimits.CODE_DEDUP_SUBJECTS] subjects,
 * least recently used out; a restart forgets it (the counters in `market_throttle` do not).
 */
class CodeGuard(
    private val throttle: ThrottleService,
    private val config: () -> MarketConfig,
    private val clock: Clock
) {
    private class Seen(val hashes: LinkedHashMap<String, Long> = LinkedHashMap())

    private val seen = object : LinkedHashMap<String, Seen>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?): Boolean = size > AbuseLimits.CODE_DEDUP_SUBJECTS
    }

    /** Subjects of a request (12.2): [clientIp] is the trusted address (`null` = untrusted), [buyerKey] the account or guest key (`null` / blank = none). */
    fun subjectsOf(clientIp: String?, buyerKey: String?): List<String> {
        val ip = IpRange.bucketKey(clientIp)?.let { ThrottlePolicy.truncate("ip:$it") }
        val buyer = buyerKey?.takeIf { it.isNotBlank() }?.let { ThrottlePolicy.truncate("b:$it") }

        return listOfNotNull(ip, buyer).ifEmpty { listOf(AbuseLimits.SUBJECT_ANON) }
    }

    /** `lockedUntil` (epoch ms) when one of [subjects] is locked in [scope], else `null`; always `null` while the mechanism is disabled. */
    suspend fun lockedUntil(scope: String, subjects: List<String>): Long? {
        if (config().couponLockThreshold <= 0 || subjects.isEmpty()) return null

        return throttle.isLockedAny(scope, subjects.map { ThrottlePolicy.truncate(it) })
    }

    /** The seconds a client has to wait for a lock that ends at [until] (at least 1). */
    fun retryAfterSeconds(until: Long): Long = throttle.retryAfterSeconds(until)

    /** A code that was not found was tried by [subjects]: one failure per subject, once per distinct code inside the window. */
    suspend fun recordUnknown(scope: String, subjects: List<String>, code: String) {
        val c = config()
        val threshold = c.couponLockThreshold

        if (threshold <= 0 || subjects.isEmpty()) return

        val windowMinutes = c.couponLockMinutes
        val hash = sha256(code)
        val now = clock.now()

        for (raw in subjects) {
            val subject = ThrottlePolicy.truncate(raw)

            if (!firstTime(scope, subject, hash, now, windowMinutes * 60_000L)) continue

            val limit = if (subject == AbuseLimits.SUBJECT_ANON) threshold * AbuseLimits.ANON_THRESHOLD_FACTOR else threshold

            throttle.fail(scope, subject, limit, windowMinutes, windowMinutes)
        }
    }

    /** The gift-redeem seam (`GiftCodeGuards`) of this guard: scope `GIFT`. */
    fun forScope(scope: String): GiftCodeGuard = object : GiftCodeGuard {
        override suspend fun lockedUntil(subjects: List<String>): Long? = this@CodeGuard.lockedUntil(scope, subjects)

        override suspend fun recordUnknown(subjects: List<String>, code: String) = this@CodeGuard.recordUnknown(scope, subjects, code)
    }

    /** `true` when [hash] was not yet counted for ([scope], [subject]) inside the window; remembers it. */
    private fun firstTime(scope: String, subject: String, hash: String, now: Long, windowMs: Long): Boolean = synchronized(seen) {
        val entry = seen.getOrPut("$scope|$subject") { Seen() }
        val at = entry.hashes[hash]

        if (at != null && at + windowMs > now) {
            // most recent use last, so the oldest of the 32 is the one that goes
            return@synchronized false
        }

        entry.hashes.remove(hash)
        entry.hashes[hash] = now

        while (entry.hashes.size > AbuseLimits.CODE_DEDUP_HASHES_PER_SUBJECT) {
            val oldest = entry.hashes.keys.iterator()
            oldest.next()
            oldest.remove()
        }

        true
    }

    private fun sha256(code: String): String =
        MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
