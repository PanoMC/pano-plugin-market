package com.panomc.plugins.market.mc.core.feature

import java.util.concurrent.ConcurrentHashMap

/**
 * The language each player chose on the website (their Pano account): first pano-mc-plugin's copy, which it learns at the login and
 * keeps until the player leaves, else the `locale` of the `MARKET_QUERY` answers (the join query `PENDING`, every command that asks
 * Pano) for a login pano-mc-plugin could not look up. It wins over the Minecraft client language, which most players never change, so a
 * player reads the store in the language they use on the site. A player without an account (or an old Pano that sends no `locale`)
 * keeps the client language.
 */
class AccountLocales(
    /** pano-mc-plugin's copy (learned at the login, kept until the player leaves): the first source. */
    private val loggedIn: (String) -> String? = ::fromPanoMc
) {
    private val locales = ConcurrentHashMap<String, String>()

    /** What Pano answered for [username]: a language replaces the remembered one, `null` (no account) forgets it. */
    fun record(username: String, locale: String?) {
        val key = username.trim().lowercase()
        if (key.isEmpty()) return
        val clean = locale?.trim()?.takeIf { Messages.normalize(it) != null }
        if (clean == null) locales.remove(key) else locales[key] = clean
    }

    fun of(username: String): String? =
        loggedIn(username)?.takeIf { Messages.normalize(it) != null } ?: locales[username.trim().lowercase()]

    fun forget(username: String) {
        locales.remove(username.trim().lowercase())
    }

    /** The language for [username]: the account's, else [clientLocale]. */
    fun resolve(username: String, clientLocale: String?): String? = of(username) ?: clientLocale

    companion object {
        /** pano-mc-plugin's language of a logged-in player; `null` when it does not know it. */
        fun fromPanoMc(username: String): String? = try {
            com.panomc.plugins.pano.core.platform.PlatformExtensions.playerLocale(username)
        } catch (_: LinkageError) {
            null
        }
    }

    /** [sender] with its locale replaced by the account's when one is known (the console stays as it is). */
    fun wrap(sender: McSender): McSender = if (sender.isConsole || sender is Localized) sender else Localized(sender, this)

    private class Localized(private val sender: McSender, private val locales: AccountLocales) : McSender by sender {
        override val locale: String? get() = locales.resolve(sender.name, sender.locale)
    }
}
