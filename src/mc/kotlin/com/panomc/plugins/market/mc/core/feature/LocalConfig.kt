package com.panomc.plugins.market.mc.core.feature

/** The nine feature switches of the local `features:` block (19 section 9). A local `false` always wins. */
data class LocalFeatures(
    val storeCommand: Boolean = true,
    val creditsCommand: Boolean = true,
    val broadcast: Boolean = true,
    val joinNotifications: Boolean = true,
    val storeMenu: Boolean = true,
    val adminCommands: Boolean = true,
    val placeholders: Boolean = true,
    val vault: Boolean = true,
    val luckPerms: Boolean = true
) {
    fun allows(feature: Feature): Boolean = when (feature) {
        Feature.STORE_COMMAND -> storeCommand
        Feature.CREDITS_COMMAND -> creditsCommand
        Feature.BROADCAST -> broadcast
        Feature.JOIN_NOTIFICATIONS -> joinNotifications
        Feature.STORE_MENU -> storeMenu
        Feature.ADMIN_COMMANDS -> adminCommands
        Feature.PLACEHOLDERS -> placeholders
        Feature.VAULT -> vault
        Feature.LUCKPERMS -> luckPerms
    }

    companion object {
        val ALL_OFF = LocalFeatures(false, false, false, false, false, false, false, false, false)
    }
}

/**
 * The component's local `config.yml` (19 section 9): `enabled`, `deliveries`, the nine `features`, extra message
 * `locales` (at most three), `command-aliases` and `menu-rows`. The local file can only switch things OFF or change
 * local presentation; it can never enable what the panel disabled ([EffectiveConfig]).
 */
data class LocalConfig(
    val enabled: Boolean = true,
    /** `false` answers every delivery `FAILED / DISABLED_LOCALLY` (08 section 8.2 rule 8). */
    val deliveries: Boolean = true,
    val features: LocalFeatures = LocalFeatures(),
    val locales: List<String> = emptyList(),
    /** canonical command (`store`, `credits`, `panomarket`) -> extra names. */
    val commandAliases: Map<String, List<String>> = DEFAULT_ALIASES,
    val menuRows: Int = 6,
    /** Things in the file that were ignored (unknown key, bad alias, ...): logged once at start. */
    val warnings: List<String> = emptyList(),
    /** Set when the file could not be read at all: every switch is OFF (fail closed) until the file is fixed. */
    val error: String? = null
) {
    fun namesOf(command: String): List<String> = (listOf(command) + (commandAliases[command] ?: emptyList())).distinct()

    companion object {
        val DEFAULT_ALIASES: Map<String, List<String>> = mapOf("store" to listOf("buy"), "credits" to emptyList(), "panomarket" to emptyList())
        val COMMANDS = listOf("store", "credits", "panomarket")
        const val MAX_LOCALES = 3
        private val ALIAS = Regex("[a-z0-9_-]{1,32}")
        private val LOCALE = Regex("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})?")
        private val KNOWN_ROOT = setOf("enabled", "deliveries", "features", "locales", "command-aliases", "menu-rows")
        private val KNOWN_FEATURES = setOf(
            "store-command", "credits-command", "broadcast", "join-notifications", "store-menu",
            "admin-commands", "placeholders", "vault", "luckperms"
        )

        /**
         * A file that cannot be parsed (or whose switches are not booleans) fails CLOSED: deliveries and every feature
         * are off and [error] says why. The admin may have disabled something in the part that cannot be read, and a
         * silent default of "everything on" would switch it back on.
         */
        fun failedClosed(error: String) = LocalConfig(
            enabled = true, deliveries = false, features = LocalFeatures.ALL_OFF, error = error
        )

        fun parse(text: String): LocalConfig {
            val root = try {
                MiniYaml.parse(text)
            } catch (e: YamlException) {
                return failedClosed("config.yml is not valid: ${e.message}")
            }
            val warnings = ArrayList<String>()
            try {
                root.keys.filter { it !in KNOWN_ROOT }.forEach { warnings.add("unknown key '$it' ignored") }

                fun bool(map: Map<String, Any?>, key: String, path: String): Boolean {
                    if (!map.containsKey(key) || map[key] == null) return true
                    return map[key] as? Boolean ?: throw IllegalArgumentException("'$path' must be true or false, found '${map[key]}'")
                }

                val featuresRaw = when (val f = root["features"]) {
                    null -> emptyMap()
                    is Map<*, *> -> f.entries.associate { it.key.toString() to it.value }
                    else -> throw IllegalArgumentException("'features' must be a map")
                }
                featuresRaw.keys.filter { it !in KNOWN_FEATURES }.forEach { warnings.add("unknown feature '$it' ignored") }
                val features = LocalFeatures(
                    storeCommand = bool(featuresRaw, "store-command", "features.store-command"),
                    creditsCommand = bool(featuresRaw, "credits-command", "features.credits-command"),
                    broadcast = bool(featuresRaw, "broadcast", "features.broadcast"),
                    joinNotifications = bool(featuresRaw, "join-notifications", "features.join-notifications"),
                    storeMenu = bool(featuresRaw, "store-menu", "features.store-menu"),
                    adminCommands = bool(featuresRaw, "admin-commands", "features.admin-commands"),
                    placeholders = bool(featuresRaw, "placeholders", "features.placeholders"),
                    vault = bool(featuresRaw, "vault", "features.vault"),
                    luckPerms = bool(featuresRaw, "luckperms", "features.luckperms")
                )

                val locales = ArrayList<String>()
                when (val l = root["locales"]) {
                    null -> Unit
                    is List<*> -> l.forEach { v ->
                        val s = v.toString().trim()
                        when {
                            !LOCALE.matches(s) -> warnings.add("locale '$s' ignored (not a locale like en-US)")
                            locales.size >= MAX_LOCALES -> warnings.add("locale '$s' ignored (at most $MAX_LOCALES locales)")
                            locales.any { it.equals(s, true) } -> Unit
                            else -> locales.add(s)
                        }
                    }
                    else -> throw IllegalArgumentException("'locales' must be a list")
                }

                val aliases: Map<String, List<String>> = when (val a = root["command-aliases"]) {
                    null -> DEFAULT_ALIASES
                    is Map<*, *> -> {
                        val out = LinkedHashMap<String, List<String>>()
                        COMMANDS.forEach { out[it] = emptyList() }
                        val used = HashSet<String>(COMMANDS)
                        a.entries.forEach { (k, v) ->
                            val command = k.toString()
                            if (command !in COMMANDS) {
                                warnings.add("command-aliases: unknown command '$command' ignored")
                                return@forEach
                            }
                            val names = when (v) {
                                null -> emptyList()
                                is List<*> -> v.map { it.toString().trim().lowercase() }
                                else -> throw IllegalArgumentException("'command-aliases.$command' must be a list")
                            }
                            out[command] = names.filter { n ->
                                when {
                                    !ALIAS.matches(n) -> false.also { warnings.add("alias '$n' ignored (letters, digits, - and _ only)") }
                                    !used.add(n) -> false.also { warnings.add("alias '$n' ignored (already a command or alias)") }
                                    else -> true
                                }
                            }
                        }
                        out
                    }
                    else -> throw IllegalArgumentException("'command-aliases' must be a map")
                }

                val rows = when (val r = root["menu-rows"]) {
                    null -> 6
                    is Long -> r.toInt().coerceIn(1, 6).also { if (it.toLong() != r) warnings.add("menu-rows $r is outside 1..6, using $it") }
                    else -> throw IllegalArgumentException("'menu-rows' must be a number from 1 to 6")
                }

                return LocalConfig(
                    enabled = bool(root, "enabled", "enabled"),
                    deliveries = bool(root, "deliveries", "deliveries"),
                    features = features,
                    locales = locales,
                    commandAliases = aliases,
                    menuRows = rows,
                    warnings = warnings
                )
            } catch (e: IllegalArgumentException) {
                return failedClosed("config.yml: ${e.message}")
            }
        }
    }
}
