package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.support.LateBound
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Target of the late-bound calls below: a Kotlin object with suspend functions, like `MarketSchema`. */
object LateBoundTarget {
    val calls = mutableListOf<String>()

    suspend fun plain(text: String, number: Int) {
        calls += "plain:$text:$number"
    }

    suspend fun suspending(text: String) {
        delay(20) // really suspends: the result comes back through the continuation
        calls += "suspending:$text"
    }

    suspend fun withFlag(text: String, flag: Boolean) {
        calls += "withFlag:$text:$flag"
    }

    suspend fun onlyText(text: String) {
        calls += "onlyText:$text"
    }

    suspend fun boom(text: String) {
        throw IllegalStateException("boom $text")
    }

    suspend fun boomAfterSuspend(text: String) {
        delay(10)
        throw IllegalArgumentException("late boom $text")
    }
}

class LateBoundTest {
    private val target = LateBoundTarget::class.java.name

    @Test
    fun `an absent class is skipped`(): Unit = runBlocking {
        assertFalse(LateBound.call("com.panomc.plugins.market.DoesNotExist", "ensure", "x"))
    }

    @Test
    fun `calls a suspend function that returns at once`(): Unit = runBlocking {
        LateBoundTarget.calls.clear()
        assertTrue(LateBound.call(target, "plain", "a", 7))
        assertEquals(listOf("plain:a:7"), LateBoundTarget.calls)
    }

    @Test
    fun `calls a suspend function that really suspends`(): Unit = runBlocking {
        LateBoundTarget.calls.clear()
        assertTrue(LateBound.call(target, "suspending", "b"))
        assertEquals(listOf("suspending:b"), LateBoundTarget.calls)
    }

    @Test
    fun `the first argument list that fits wins`(): Unit = runBlocking {
        LateBoundTarget.calls.clear()
        assertTrue(LateBound.callFirst(target, "withFlag", listOf(arrayOf("c", true), arrayOf("c"))))
        assertTrue(LateBound.callFirst(target, "onlyText", listOf(arrayOf("d", true), arrayOf("d"))))
        assertEquals(listOf("withFlag:c:true", "onlyText:d"), LateBoundTarget.calls)
    }

    @Test
    fun `exceptions of the target arrive unwrapped`(): Unit = runBlocking {
        val e1 = assertThrows<IllegalStateException> { runBlocking { LateBound.call(target, "boom", "x") } }
        assertEquals("boom x", e1.message)
        val e2 = assertThrows<IllegalArgumentException> { runBlocking { LateBound.call(target, "boomAfterSuspend", "y") } }
        assertEquals("late boom y", e2.message)
    }

    @Test
    fun `a present class without a fitting function is an error, not a silent skip`() {
        val noMethod = assertThrows<IllegalStateException> { runBlocking { LateBound.call(target, "missing", "x") } }
        assertTrue(noMethod.message!!.contains("has no suspend missing"), noMethod.message)
        val wrongArgs = assertThrows<IllegalStateException> { runBlocking { LateBound.call(target, "plain", 1, "swapped") } }
        assertTrue(wrongArgs.message!!.contains("has no suspend plain"), wrongArgs.message)
    }
}
