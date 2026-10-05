package com.panomc.plugins.market.mc.core.link

import java.util.concurrent.ConcurrentHashMap

/**
 * Who is "present" for the delivery engine: connected AND authenticated (19 section 3). Names are compared
 * case-insensitively; the name kept is the one the platform reported. Safe from any thread (the engine asks from its
 * own thread, the platform events write from theirs).
 */
class PresenceTracker {
    private class Entry(val name: String, var uuid: String?, var authenticated: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(name: String) = name.lowercase()

    /** A player connected. Returns `true` when this made them present. */
    @Synchronized
    fun join(name: String, uuid: String?, authenticated: Boolean): Boolean {
        val before = isPresent(name)
        entries[key(name)] = Entry(name, uuid, authenticated)
        return !before && authenticated
    }

    /** Authentication finished (AuthMe login) of a connected player. Returns `true` when this made them present; a name that is not connected is ignored. */
    @Synchronized
    fun authenticate(name: String, uuid: String?): Boolean {
        // No entry = not connected (every connected player has one from the join event or the enable-time seeding): a
        // login event that arrives after the quit must not bring the player back, nothing would ever remove them again.
        val e = entries[key(name)] ?: return false
        if (uuid != null) e.uuid = uuid
        val was = e.authenticated
        e.authenticated = true
        return !was
    }

    /** The player logged out of the authentication plugin but is still connected: no longer present. */
    @Synchronized
    fun deauthenticate(name: String) {
        entries[key(name)]?.authenticated = false
    }

    @Synchronized
    fun leave(name: String) {
        entries.remove(key(name))
    }

    @Synchronized
    fun clear() = entries.clear()

    fun isPresent(name: String): Boolean = entries[key(name)]?.authenticated == true

    fun uuid(name: String): String? = entries[key(name)]?.takeIf { it.authenticated }?.uuid

    fun presentNames(): List<String> = entries.values.filter { it.authenticated }.map { it.name }
}

/**
 * The presence rules of 19 section 3 on top of a [PresenceTracker], shared by every platform:
 * - Spigot / Paper / Folia: [authRequired] is `true` while AuthMe is installed; a joining player is present at once
 *   only when [isAuthenticated] says so, otherwise at the AuthMe login ([onAuthLogin]); a logout removes presence;
 * - BungeeCord / Velocity: [authRequired] is `false`; "connected to a backend server" is the join event (LimboAuth
 *   keeps unauthenticated players in its limbo, they never reach a backend);
 * - an unknown authentication plugin is not detected: it falls back to "online".
 * [onPresent] is called (quickly, from the event thread) every time a player becomes present.
 */
class PresenceRules(
    private val tracker: PresenceTracker,
    private val authRequired: () -> Boolean,
    private val isAuthenticated: (String) -> Boolean,
    private val onPresent: (String) -> Unit,
    private val onProblem: (String) -> Unit = {}
) {
    fun onJoin(name: String, uuid: String?) {
        var authenticated = true
        if (authRequired()) {
            authenticated = try {
                isAuthenticated(name)
            } catch (t: Throwable) {
                onProblem("The authentication plugin could not tell whether $name is logged in ($t); waiting for its login event")
                false
            }
        }
        if (tracker.join(name, uuid, authenticated)) onPresent(name)
    }

    fun onAuthLogin(name: String, uuid: String?) {
        if (tracker.authenticate(name, uuid)) onPresent(name)
    }

    fun onAuthLogout(name: String) = tracker.deauthenticate(name)

    fun onQuit(name: String) = tracker.leave(name)
}
