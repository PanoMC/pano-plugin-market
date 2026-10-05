package com.panomc.plugins.market.mc.link

import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 19 section 3: presence = authenticated; AuthMe on Spigot, "connected to a backend" on a proxy. */
class PresenceTest {
    private val tracker = PresenceTracker()
    private val present = ArrayList<String>()
    private val problems = ArrayList<String>()
    private var authMe = false
    private val authenticatedNow = HashSet<String>()
    private var authApiError: Throwable? = null

    private fun spigotRules() = PresenceRules(
        tracker,
        authRequired = { authMe },
        isAuthenticated = { n -> authApiError?.let { throw it }; n in authenticatedNow },
        onPresent = { present.add(it) },
        onProblem = { problems.add(it) }
    )

    private fun proxyRules() = PresenceRules(tracker, { false }, { true }, { present.add(it) })

    @Test
    fun `AuthMe absent - a joining player is present at once`() {
        val rules = spigotRules()
        rules.onJoin("Steve", "u1")
        assertTrue(tracker.isPresent("steve"))
        assertEquals("u1", tracker.uuid("STEVE"))
        assertEquals(listOf("Steve"), present)
    }

    @Test
    fun `AuthMe present - a joining player is NOT present until the login event`() {
        authMe = true
        val rules = spigotRules()
        rules.onJoin("Steve", "u1")
        assertFalse(tracker.isPresent("Steve"))
        assertNull(tracker.uuid("Steve"), "no uuid is handed out for an unauthenticated player")
        assertTrue(present.isEmpty())
        rules.onAuthLogin("Steve", "u1")
        assertTrue(tracker.isPresent("Steve"))
        assertEquals(listOf("Steve"), present)
    }

    @Test
    fun `AuthMe present - a player who is authenticated already on join (premium auto login) is present at once`() {
        authMe = true
        authenticatedNow.add("Alex")
        spigotRules().onJoin("Alex", "u2")
        assertTrue(tracker.isPresent("Alex"))
        assertEquals(listOf("Alex"), present)
    }

    @Test
    fun `a login event after an already authenticated join does not announce the player twice`() {
        authMe = true
        authenticatedNow.add("Alex")
        val rules = spigotRules()
        rules.onJoin("Alex", "u2")
        rules.onAuthLogin("Alex", "u2")
        assertEquals(listOf("Alex"), present)
    }

    @Test
    fun `an AuthMe logout removes presence while the player stays connected, the next login restores it`() {
        authMe = true
        val rules = spigotRules()
        rules.onJoin("Steve", "u1")
        rules.onAuthLogin("Steve", "u1")
        rules.onAuthLogout("Steve")
        assertFalse(tracker.isPresent("Steve"))
        rules.onAuthLogin("Steve", "u1")
        assertTrue(tracker.isPresent("Steve"))
        assertEquals(listOf("Steve", "Steve"), present)
    }

    @Test
    fun `quit removes presence and a later join starts again`() {
        val rules = spigotRules()
        rules.onJoin("Steve", "u1")
        rules.onQuit("STEVE")
        assertFalse(tracker.isPresent("Steve"))
        rules.onJoin("Steve", "u1")
        assertEquals(listOf("Steve", "Steve"), present)
    }

    @Test
    fun `an authentication API that throws means not authenticated yet, the login event still works`() {
        authMe = true
        authApiError = IllegalStateException("AuthMe is not loaded")
        val rules = spigotRules()
        rules.onJoin("Steve", "u1")
        assertFalse(tracker.isPresent("Steve"))
        assertEquals(1, problems.size)
        assertTrue(problems[0].contains("Steve"))
        rules.onAuthLogin("Steve", "u1")
        assertTrue(tracker.isPresent("Steve"))
    }

    @Test
    fun `a proxy - connected to a backend is present, disconnect is not, a server switch does not announce twice`() {
        val rules = proxyRules()
        rules.onJoin("Steve", "u1")
        rules.onJoin("Steve", "u1")
        assertTrue(tracker.isPresent("Steve"))
        assertEquals(listOf("Steve"), present)
        rules.onQuit("Steve")
        assertFalse(tracker.isPresent("Steve"))
    }

    @Test
    fun `names are compared case insensitively and the reported name is kept`() {
        proxyRules().onJoin("StEvE", "u1")
        assertTrue(tracker.isPresent("steve"))
        assertEquals(listOf("StEvE"), tracker.presentNames())
    }

    @Test
    fun `a login event after the quit does not make the player present and does not call onPresent`() {
        authMe = true
        val rules = spigotRules()
        rules.onJoin("Steve", "u1")
        rules.onQuit("Steve")
        rules.onAuthLogin("Steve", "u1")
        assertFalse(tracker.isPresent("steve"))
        assertNull(tracker.uuid("steve"))
        assertTrue(tracker.presentNames().isEmpty())
        assertTrue(present.isEmpty())
    }

    @Test
    fun `an authenticate event for a name that never connected is ignored`() {
        authMe = true
        spigotRules().onAuthLogin("Late", "u9")
        assertFalse(tracker.isPresent("late"))
        assertTrue(present.isEmpty())
    }
}
