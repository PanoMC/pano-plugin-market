package com.panomc.plugins.market.spi.common

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Admin-supplied regular expressions (`SettingsField.pattern`, `market_product_field.pattern`; 11 section 6.3,
 * 01 section 2.5). `java.util.regex` backtracks and there is no RE2 here, so two layers apply:
 *
 * 1. [checkGrammar] on save: length <= 255, compiles, no back-reference, no look-around / inline flag / atomic
 *    group (`(?` other than `(?:`), no `\Q`, and no quantified group (`* + {`) whose body contains a quantifier
 *    (`* + {`) or an alternation (`|`): `(a+)+$`, `(a|aa)+$` and `(.*a){20}` are rejected; a counted repetition
 *    above 1000; a quantifier on a zero-width atom (`^ $ \b \B \G \A \z \Z`) or on a group that can only match empty
 *    (`(?:^^){2000000000}` would loop without ever reading a char, so the step budget would not stop it).
 * 2. [test] on use: anchored full match, input <= [MAX_INPUT] chars, and the input `CharSequence` throws after
 *    [STEP_BUDGET] `charAt` calls, so a hostile pattern that slipped through (or was stored earlier) cannot burn CPU.
 */
object SafeRegex {
    const val MAX_PATTERN = 255
    const val MAX_INPUT = 128
    const val STEP_BUDGET = 100_000

    enum class Verdict { MATCH, NO_MATCH, INVALID }

    /** Null when [pattern] is acceptable, otherwise a short English reason. */
    fun checkGrammar(pattern: String): String? {
        if (pattern.isEmpty()) return "pattern is empty"
        if (pattern.length > MAX_PATTERN) return "pattern is longer than $MAX_PATTERN characters"
        try {
            Pattern.compile(pattern)
        } catch (e: Exception) {
            return "pattern does not compile"
        }
        return try {
            scan(pattern)
        } catch (e: Exception) {
            "pattern refused"
        }
    }

    fun isSafe(pattern: String): Boolean = checkGrammar(pattern) == null

    /** MATCH / NO_MATCH, or INVALID when the input is too long, the step budget ran out or the pattern is refused. */
    fun test(pattern: String, input: String): Verdict {
        if (input.length > MAX_INPUT) return Verdict.INVALID
        val compiled = compiled(pattern) ?: return Verdict.INVALID
        return testCompiled(compiled, input)
    }

    /** The budgeted full match, without the grammar check (tests use it to run a pattern the grammar refuses). */
    internal fun testCompiled(compiled: Pattern, input: String): Verdict = try {
        if (compiled.matcher(BudgetedSequence(input, STEP_BUDGET)).matches()) Verdict.MATCH else Verdict.NO_MATCH
    } catch (e: BudgetExhausted) {
        Verdict.INVALID
    }

    /** True only for a clean match; INVALID counts as no match. */
    fun matches(pattern: String, input: String): Boolean = test(pattern, input) == Verdict.MATCH

    private val cache = ConcurrentHashMap<String, Pattern>()

    private fun compiled(pattern: String): Pattern? {
        cache[pattern]?.let { return it }
        if (checkGrammar(pattern) != null) return null
        if (cache.size >= 512) cache.clear()
        return Pattern.compile(pattern).also { cache[pattern] = it }
    }

    private class BudgetExhausted : RuntimeException(null, null, false, false)

    private class BudgetedSequence(private val text: CharSequence, private var left: Int) : CharSequence {
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (--left < 0) throw BudgetExhausted()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            BudgetedSequence(text.subSequence(startIndex, endIndex), left)

        override fun toString(): String = text.toString()
    }

    /** Largest counted repetition (`{n}`, `{n,}`, `{n,m}`) a stored pattern may use; input is at most [MAX_INPUT]. */
    private const val MAX_REPEAT = 1000

    private class Group(
        var hasQuantifier: Boolean = false,
        var hasAlternation: Boolean = false,
        /** True while every atom seen in the body is zero-width (or the body is empty). */
        var allZeroWidth: Boolean = true
    )

    private fun isQuantifierStart(c: Char) = c == '*' || c == '+' || c == '{'

    /** Index of the last char of a class opener at [i]: `[`, `[^`, and the literal `]` Java allows right after them. */
    private fun classOpenerEnd(p: String, i: Int): Int {
        var j = i
        if (p.getOrNull(j + 1) == '^') j++
        if (p.getOrNull(j + 1) == ']') j++
        return j
    }

    /** Walks the pattern text once, tracking groups; the pattern already compiles. Never throws. */
    private fun scan(p: String): String? {
        val stack = ArrayList<Group>()
        var i = 0
        var classDepth = 0
        // the atom completed last is zero-width (`^ $ \b \B \G \A \z \Z`, or a group whose body is)
        var prevZero = false

        fun atom(zero: Boolean) {
            prevZero = zero
            if (!zero) stack.lastOrNull()?.allZeroWidth = false
        }

        while (i < p.length) {
            val c = p[i]
            if (c == '\\') {
                val n = p.getOrNull(i + 1) ?: return "dangling escape"
                if (n in '1'..'9') return "back-references are not allowed"
                if (n == 'k') return "named back-references are not allowed"
                if (n == 'Q' || n == 'E') return "quoted sections are not allowed"
                if (n == 'c') {
                    // `\cX` takes the next char as its operand, whatever it is (`\c[` does not open a class)
                    if (i + 3 > p.length) return "dangling escape"
                    i += 3
                    if (classDepth == 0) atom(false)
                    continue
                }
                i += 2
                if ((n == 'p' || n == 'P' || n == 'x' || n == 'N' || n == 'b') && p.getOrNull(i) == '{') {
                    val end = p.indexOf('}', i)
                    if (end < 0) return "unterminated escape"
                    i = end + 1
                }
                if (classDepth == 0) atom(n == 'b' || n == 'B' || n == 'G' || n == 'A' || n == 'z' || n == 'Z')
                continue
            }
            if (classDepth > 0) {
                if (c == '[') {
                    classDepth++
                    i = classOpenerEnd(p, i)
                } else if (c == ']') classDepth--
                i++
                continue
            }
            when (c) {
                '[' -> {
                    classDepth = 1
                    i = classOpenerEnd(p, i)
                    atom(false)
                }
                '(' -> {
                    if (p.getOrNull(i + 1) == '?') {
                        if (p.getOrNull(i + 2) != ':') return "look-around, flags and named groups are not allowed"
                        i += 2
                    }
                    stack.add(Group())
                    prevZero = false
                }
                ')' -> {
                    if (stack.isEmpty()) return "unbalanced group"
                    val g = stack.removeAt(stack.size - 1)
                    val next = p.getOrNull(i + 1)
                    if (next != null && isQuantifierStart(next) && (g.hasQuantifier || g.hasAlternation)) {
                        return "a quantified group must not contain a quantifier or an alternation"
                    }
                    stack.lastOrNull()?.let {
                        it.hasQuantifier = it.hasQuantifier || g.hasQuantifier
                        it.hasAlternation = it.hasAlternation || g.hasAlternation
                    }
                    atom(g.allZeroWidth)
                }
                '|' -> {
                    stack.lastOrNull()?.hasAlternation = true
                    prevZero = false
                }
                '*', '+', '?', '{' -> {
                    if (prevZero) return "a quantifier must not follow a zero-width atom or an empty group"
                    if (c != '?') stack.lastOrNull()?.hasQuantifier = true
                    if (c == '{') {
                        val end = p.indexOf('}', i)
                        if (end < 0) return "unterminated repetition"
                        val bounds = p.substring(i + 1, end).split(',', limit = 2)
                        for (b in bounds) {
                            if (b.isEmpty()) continue
                            val v = b.toLongOrNull() ?: return "malformed repetition"
                            if (v > MAX_REPEAT) return "a repetition count must not exceed $MAX_REPEAT"
                        }
                        i = end
                    }
                    prevZero = false
                }
                '^', '$' -> atom(true)
                else -> atom(false)
            }
            i++
        }
        return null
    }
}
