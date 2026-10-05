package com.panomc.plugins.market.spi.common

import com.panomc.plugins.market.spi.common.SafeRegex.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

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

    @Test
    fun `zero-width repetition cannot burn CPU outside the step budget`() {
        val bombs = listOf(
            "(?:^^){2000000000}a", "(^){2000000000}a", "^{2000000000}a", "(?:^^)*a", "(?:)+a", "()*a", "\\b+a",
            "(?:\\b|^)+a", "(?:(?:^)(?:$))*a", "\\G{5}a", "a{1001}", "a{1,1001}", "a{1001,}", "[ab]{99999}"
        )
        for (p in bombs) {
            assertNotNull(SafeRegex.checkGrammar(p), p)
            val started = System.nanoTime()
            assertEquals(Verdict.INVALID, SafeRegex.test(p, "a"), p)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < 100, "$p took $tookMs ms")
        }
        // bounded counts and anchors around real atoms stay valid
        for (p in listOf("a{1000}", "a{0,1000}", "^a{2}$", "(?:^a)", "(a|^)b", "^(?:ab){2}$", "\\bword\\b", "(?:^|-)a")) {
            assertNull(SafeRegex.checkGrammar(p), p)
        }
    }

    @Test
    fun `a leading close bracket in a class is a literal and never desyncs the scanner`() {
        for (p in listOf("[])]", "[^])]", "[]a]+", "[]]", "[a[]b]]", "[^]]+x")) {
            Pattern.compile(p) // Java accepts them
            assertNull(SafeRegex.checkGrammar(p), p)
            assertTrue(SafeRegex.isSafe(p), p)
        }
        assertTrue(SafeRegex.matches("[])]", ")"))
        assertTrue(SafeRegex.matches("[])]", "]"))
        assertFalse(SafeRegex.matches("[])]", "a"))
        assertTrue(SafeRegex.matches("[]a]+", "a]a"))
        assertEquals(Verdict.NO_MATCH, SafeRegex.test("[^])]", ")"))
        // a quantifier hidden behind such a class is still seen
        assertNotNull(SafeRegex.checkGrammar("([])]+)+"))
    }

    @Test
    fun `a control escape consumes its operand so it cannot hide the rest of the pattern`() {
        for (p in listOf("\\c[?(a+)+$", "\\c[?(.*a){20}", "\\c[(a|aa)+$", "\\c((a+)+$")) {
            Pattern.compile(p) // Java accepts them
            assertNotNull(SafeRegex.checkGrammar(p), p)
            assertEquals(Verdict.INVALID, SafeRegex.test(p, "a"), p)
        }
        assertNull(SafeRegex.checkGrammar("\\c[a+"))
        assertNull(SafeRegex.checkGrammar("[\\c]]+"))
    }

    @Test
    fun `an unbalanced pattern is refused instead of throwing`() {
        // `)` alone does not compile in Java; the scanner must also be safe if it is ever reached
        for (p in listOf(")", "a)", "(a))", "[", "(", "\\")) {
            assertNotNull(SafeRegex.checkGrammar(p), p)
            assertEquals(Verdict.INVALID, SafeRegex.test(p, "a"), p)
            assertFalse(SafeRegex.matches(p, "a"), p)
        }
    }
}
