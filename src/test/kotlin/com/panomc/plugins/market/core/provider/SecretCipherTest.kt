package com.panomc.plugins.market.core.provider

import com.panomc.plugins.market.provider.SecretCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

/** 11 section 8.1 / 17 section 11.1: round trip, `v1:` prefix, tamper fails closed, random IV, legacy plaintext. */
class SecretCipherTest {
    private fun cipher(seed: Int = 1) = SecretCipher(ByteArray(32) { (it * seed + 7).toByte() })

    @Test
    fun `a value round trips and is stored with the v1 prefix`() {
        val c = cipher()
        for (plain in listOf("sk_live_abc123", "", "ünïcödé ✓ 日本語", "x".repeat(10_000), "line1\nline2 \"quoted\"")) {
            val stored = c.encrypt(plain)
            assertTrue(stored.startsWith("v1:"), stored)
            assertTrue(c.isEncrypted(stored))
            assertEquals(plain, c.decrypt(stored))
        }
    }

    @Test
    fun `the stored text is iv then ciphertext then tag in base64`() {
        val stored = cipher().encrypt("secret")
        val raw = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        assertEquals(12 + "secret".length + 16, raw.size)
        assertFalse(stored.contains("secret"))
    }

    @Test
    fun `two encryptions of the same value differ because the iv is random`() {
        val c = cipher()
        val seen = HashSet<String>()
        repeat(200) { seen.add(c.encrypt("same value")) }
        assertEquals(200, seen.size)
        val ivs = seen.map { Base64.getDecoder().decode(it.removePrefix("v1:")).copyOfRange(0, 12).toList() }.toSet()
        assertEquals(200, ivs.size, "every IV is fresh")
    }

    @Test
    fun `a flipped bit anywhere in the stored value fails closed to null`() {
        val c = cipher()
        val stored = c.encrypt("do not leak")
        val raw = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        for (i in raw.indices) {
            val tampered = raw.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(c.decrypt("v1:" + Base64.getEncoder().encodeToString(tampered)), "byte $i")
        }
    }

    @Test
    fun `truncated, extended, empty and malformed v1 values fail closed`() {
        val c = cipher()
        val raw = Base64.getDecoder().decode(c.encrypt("value").removePrefix("v1:"))
        fun v1(bytes: ByteArray) = "v1:" + Base64.getEncoder().encodeToString(bytes)
        assertNull(c.decrypt(v1(raw.copyOf(raw.size - 1))))
        assertNull(c.decrypt(v1(raw + 0)))
        assertNull(c.decrypt(v1(ByteArray(0))))
        assertNull(c.decrypt(v1(ByteArray(27))))
        assertNull(c.decrypt("v1:"))
        assertNull(c.decrypt("v1:not base64 at all !!"))
        assertNull(c.decrypt("v1:" + "A".repeat(3)))
    }

    @Test
    fun `a value encrypted with another key is unreadable, never an exception`() {
        val stored = cipher(1).encrypt("secret")
        assertNull(cipher(2).decrypt(stored))
        assertEquals("secret", cipher(1).decrypt(stored))
    }

    @Test
    fun `legacy plaintext without the prefix is accepted unchanged and flagged for re-encryption`() {
        val c = cipher()
        assertEquals("legacy-secret", c.decrypt("legacy-secret"))
        assertFalse(c.isEncrypted("legacy-secret"))
        assertTrue(c.needsEncryption("legacy-secret"))
        assertFalse(c.needsEncryption(""))
        assertFalse(c.needsEncryption(c.encrypt("x")))
        // a plaintext that merely looks like the format is damaged, not plaintext
        assertNull(c.decrypt("v1:legacy"))
    }

    @Test
    fun `the key must be 32 bytes and is never printed`() {
        assertThrowsIllegal { SecretCipher(ByteArray(16)) }
        assertThrowsIllegal { SecretCipher(ByteArray(33)) }
        val c = SecretCipher(ByteArray(32) { 0x41 })
        assertFalse(c.toString().contains("QUFB"))
        assertEquals("SecretCipher(AES-256-GCM)", c.toString())
    }

    private fun assertThrowsIllegal(block: () -> Unit) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java, block)
    }

    // ---- key file

    @Test
    fun `load creates the key file on first start and reuses it afterwards`(@TempDir dir: Path) {
        val first = SecretCipher.load(dir)
        assertTrue(first.keyCreated)
        val file = dir.resolve("secret.key")
        assertTrue(Files.isRegularFile(file))
        assertEquals(32, Base64.getDecoder().decode(String(Files.readAllBytes(file)).trim()).size)
        assertFalse(Files.exists(dir.resolve("secret.key.tmp")), "the temp file was moved, not copied")

        val stored = first.encrypt("persisted")
        val second = SecretCipher.load(dir)
        assertFalse(second.keyCreated)
        assertEquals("persisted", second.decrypt(stored))
    }

    @Test
    fun `the key file is readable by its owner only`(@TempDir dir: Path) {
        SecretCipher.load(dir)
        val perms = Files.getPosixFilePermissions(dir.resolve("secret.key"))
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `a missing key file with encrypted values around gives a new key and the old values become unreadable`(@TempDir dir: Path) {
        val old = SecretCipher.load(dir)
        val stored = old.encrypt("secret")
        Files.delete(dir.resolve("secret.key"))
        val fresh = SecretCipher.load(dir)
        assertTrue(fresh.keyCreated)
        assertNull(fresh.decrypt(stored))
        assertEquals("new", fresh.decrypt(fresh.encrypt("new")))
    }

    @Test
    fun `an invalid key file is moved aside, never overwritten or deleted`(@TempDir dir: Path) {
        Files.write(dir.resolve("secret.key"), "garbage".toByteArray())
        val c = SecretCipher.load(dir)
        assertTrue(c.keyCreated)
        assertEquals("garbage", String(Files.readAllBytes(dir.resolve("secret.key.invalid-1"))))
        // and a second invalid file does not clobber the first
        Files.write(dir.resolve("secret.key"), Base64.getEncoder().encode(ByteArray(8)))
        SecretCipher.load(dir)
        assertEquals("garbage", String(Files.readAllBytes(dir.resolve("secret.key.invalid-1"))))
        assertTrue(Files.exists(dir.resolve("secret.key.invalid-2")))
    }

    @Test
    fun `the data directory is created when it does not exist`(@TempDir dir: Path) {
        val nested = dir.resolve("a").resolve("b")
        assertTrue(SecretCipher.load(nested).keyCreated)
        assertTrue(Files.isRegularFile(nested.resolve("secret.key")))
    }

    @Test
    fun `a key from an explicit random source is used for the ivs`() {
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(9) }
        }
        val c = SecretCipher(ByteArray(32) { 3 }, fixed)
        val raw = Base64.getDecoder().decode(c.encrypt("a").removePrefix("v1:"))
        assertEquals(List(12) { 9.toByte() }, raw.copyOfRange(0, 12).toList())
        assertNotEquals("a", c.encrypt("a"))
    }
}
