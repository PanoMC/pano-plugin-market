package fr.xephi.authme.api.v3

import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/** Test double of AuthMe's API class (same name and signature, loaded by reflection exactly like the real one). */
class AuthMeApi private constructor() {
    fun isAuthenticated(player: Player): Boolean = authenticated.contains(player.name)

    companion object {
        @JvmField
        val authenticated: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val instance = AuthMeApi()

        @JvmStatic
        fun getInstance(): AuthMeApi = instance
    }
}
