package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.AccountLocales
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AccountLocalesTest {
    @Test
    fun `pano-mc-plugin's copy from the login comes first, Pano's query answers stand in, the client language is last`() {
        val loggedIn = HashMap<String, String>()
        val l = AccountLocales { loggedIn[it.lowercase()] }

        assertEquals("en_us", l.resolve("Steve", "en_us"), "nothing known: the client language")

        l.record("Steve", "ru")
        assertEquals("ru", l.resolve("steve", "en_us"), "a query answer stands in")

        loggedIn["steve"] = "tr"
        assertEquals("tr", l.resolve("Steve", "en_us"), "pano-mc-plugin's copy wins")

        loggedIn["steve"] = "not a language!"
        assertEquals("ru", l.resolve("Steve", "en_us"), "nonsense from anywhere is ignored")

        loggedIn.clear()
        l.forget("STEVE")
        assertNull(l.of("Steve"))
    }

    @Test
    fun `the real source is pano-mc-plugin's PlatformExtensions until the player leaves`() {
        val ext = com.panomc.plugins.pano.core.platform.PlatformExtensions
        ext.rememberPlayer("Alex", "tr")
        try {
            assertEquals("tr", AccountLocales().of("alex"))
        } finally {
            ext.forgetPlayer("Alex")
        }
        assertNull(AccountLocales().of("alex"))
    }
}
