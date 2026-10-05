package com.panomc.plugins.market.spi.common

import com.panomc.plugins.market.spi.common.SafeRegex.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeRegexTest {
    @Test
    fun `grammar rejects nested quantifiers and alternation under a quantifier`() {
        for (p in listOf("(a+)+$", "(a|aa)+$", "(.*a){20}", "^(a*)*$", "^((ab)+c)+$", "(a|b)*", "(?:a|b)+", "(x+x+)+y", "(a{2,3})+")) {
            assertNotNull(SafeRegex.checkGrammar(p), p)
            assertFalse(SafeRegex.isSafe(p), p)
        }
    }

    @Test
    fun `grammar rejects back-references look-arounds and inline flags`() {
        for (p in listOf("(a)\\1", "(a)\\9", "(?<n>a)\\k<n>", "(?=a)b", "(?!a)b", "(?<=a)b", "(?<!a)b", "(?i)abc", "(?>a+)b", "(?<name>a)", "\\Qa+\\E")) {
            assertNotNull(SafeRegex.checkGrammar(p), p)
        }
    }

    @Test
    fun `grammar accepts plain patterns`() {
        for (p in listOf(
            "^[A-Z]{2}\\d{4}$", "[a-z0-9_]+", "^\\p{L}{1,32}$", "^(?:ab)$", "^(abc)?$", "^(a|b)$", "^(?:XX)?\\d{4}$",
            ".*", "a?b*c+", "^[\\w.+-]+@[\\w-]+\\.[\\w.]+$", "[+*{}|()]+", "\\(a+\\)+", "^(ab)(cd)+$", "^\\x{41}+$", "[a[bc]]+"
        )) {
            assertNull(SafeRegex.checkGrammar(p), p)
        }
    }

    @Test
    fun `grammar ignores metacharacters inside classes and after escapes`() {
        assertNull(SafeRegex.checkGrammar("([+*|{])x"))
        assertNull(SafeRegex.checkGrammar("(\\+)+"))
        assertNull(SafeRegex.checkGrammar("(\\|)+"))
        assertNotNull(SafeRegex.checkGrammar("([a-z]+)+"))
    }

    @Test
    fun `grammar rejects empty, too long and uncompilable patterns`() {
        assertNotNull(SafeRegex.checkGrammar(""))
        assertNotNull(SafeRegex.checkGrammar("a".repeat(256)))
        assertNull(SafeRegex.checkGrammar("a".repeat(255)))
        assertNotNull(SafeRegex.checkGrammar("(unclosed"))
        assertNotNull(SafeRegex.checkGrammar("[a-"))
        assertNotNull(SafeRegex.checkGrammar("*a"))
    }

    @Test
    fun `matches is anchored over the whole input`() {
        assertTrue(SafeRegex.matches("^[A-Z]{2}\\d{4}$", "AB1234"))
        assertTrue(SafeRegex.matches("[A-Z]{2}\\d{4}", "AB1234"))
        assertFalse(SafeRegex.matches("[A-Z]{2}\\d{4}", "xAB1234"))
        assertFalse(SafeRegex.matches("[A-Z]{2}\\d{4}", "AB12345"))
        assertFalse(SafeRegex.matches("^[A-Z]{2}\\d{4}$", "ab1234"))
        assertEquals(Verdict.MATCH, SafeRegex.test("a+", "aaa"))
        assertEquals(Verdict.NO_MATCH, SafeRegex.test("a+", "aab"))
        assertEquals(Verdict.MATCH, SafeRegex.test("a*", ""))
    }

    @Test
    fun `input over 128 characters is invalid`() {
        assertEquals(Verdict.MATCH, SafeRegex.test("a+", "a".repeat(128)))
        assertEquals(Verdict.INVALID, SafeRegex.test("a+", "a".repeat(129)))
        assertFalse(SafeRegex.matches("a+", "a".repeat(129)))
    }

    @Test
    fun `a refused stored pattern is invalid on use`() {
        assertEquals(Verdict.INVALID, SafeRegex.test("(a+)+$", "aaaa"))
        assertEquals(Verdict.INVALID, SafeRegex.test("(unclosed", "x"))
    }

    @Test
    fun `the step budget stops a hostile pattern that bypasses the grammar within 100 ms`() {
        // A stored pattern that the grammar would refuse today (or one stored before the check existed).
        val hostile = java.util.regex.Pattern.compile("(.*a){20}")
        val input = "a".repeat(127) + "!"
        val started = System.nanoTime()
        val verdict = SafeRegex.testCompiled(hostile, input)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(Verdict.INVALID, verdict)
        assertTrue(tookMs < 100, "took $tookMs ms")
        // a harmless pattern through the same path still answers normally
        val benign = java.util.regex.Pattern.compile("(.*a){2}")
        assertEquals(Verdict.MATCH, SafeRegex.testCompiled(benign, "aa"))
        assertEquals(Verdict.NO_MATCH, SafeRegex.testCompiled(benign, "a"))
    }

    @Test
    fun `the public API answers a hostile pattern and input within 100 ms`() {
        val input = "a".repeat(127) + "!"
        for (p in listOf("(a+)+$", "(a|aa)+$", "(.*a){20}")) {
            val started = System.nanoTime()
            val v = SafeRegex.test(p, input)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(Verdict.INVALID, v, p)
            assertTrue(tookMs < 100, "$p took $tookMs ms")
        }
    }
}
