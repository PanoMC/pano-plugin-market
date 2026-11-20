package com.panomc.plugins.market.mc.jarrules

/**
 * The jar rules of 19 section 2.1 / 17 section 3.2 for the Minecraft side, as pure functions over jar entries, so the
 * rules themselves can be shown to fail on a bad jar. `verifyJar` in build.gradle.kts applies the same rules to the real jars:
 * one jar per platform ([violations]) and a market jar without any Minecraft class or descriptor ([marketViolations]).
 */
object JarScan {
    const val MARKET = "com/panomc/plugins/market/"
    const val MC = "com/panomc/plugins/market/mc/"
    const val MC_VELOCITY = "com/panomc/plugins/market/mc/velocity/"

    /** The platform jars and the descriptor each one carries at its root. */
    val DESCRIPTORS = mapOf("spigot" to "plugin.yml", "bungee" to "bungee.yml", "velocity" to "velocity-plugin.json")

    private val bundledPrefixes = listOf(
        "com/panomc/plugins/pano/core/", "org/bukkit/", "org/spigotmc/", "net/md_5/", "net/luckperms/",
        "com/velocitypowered/", "net/milkbowl/", "me/clip/"
    )
    private val forbiddenPrefixes = listOf(
        "com/panomc/plugins/marketfake/", "com/panomc/platform/", "kotlin/", "kotlinx/", "io/vertx/", "org/springframework/", "com/google/gson/"
    )
    private val otherMarketRef = Regex("com/panomc/plugins/market/(?!mc/)")

    fun classMajor(bytes: ByteArray): Int = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    /** Every rule the jar of [platform] (`spigot`, `bungee` or `velocity`) violates, one line each; empty = the jar passes. */
    fun violations(entries: Map<String, ByteArray>, version: String, platform: String): List<String> {
        val out = mutableListOf<String>()
        val own = DESCRIPTORS.getValue(platform)
        val body = entries[own]
        if (body == null) {
            out += "$own missing at the jar root"
        } else {
            val t = String(body, Charsets.UTF_8)
            if (!t.contains(version)) out += "$own does not carry the version $version"
            if (t.contains("\${")) out += "$own holds an unexpanded placeholder"
        }
        DESCRIPTORS.values.filter { it != own && entries.containsKey(it) }.forEach { out += "foreign descriptor $it" }
        DESCRIPTORS.keys.filter { it != platform }.forEach { other ->
            entries.keys.filter { it.startsWith("$MC$other/") }.forEach { out += "foreign platform class $it" }
        }
        if (entries.keys.none { it.startsWith("${MC}core/") }) out += "no mc.core class"
        entries.keys.filter { n -> forbiddenPrefixes.any { n.startsWith(it) } }.forEach { out += "forbidden entry $it" }
        entries.keys.filter { n -> bundledPrefixes.any { n.startsWith(it) } }.forEach { out += "bundled Minecraft-side library $it" }
        entries.filterKeys { it.endsWith(".class") && !it.startsWith("META-INF/") }.forEach { (n, b) ->
            val limit = if (n.startsWith(MC_VELOCITY)) 61 else 55
            val major = classMajor(b)
            if (major > limit) out += "$n is class major $major (> $limit)"
            if (!n.startsWith(MC)) out += "$n is outside mc.**"
            val t = text(b)
            if (t.contains("com/panomc/platform/")) out += "$n references com/panomc/platform/"
            if (otherMarketRef.containsMatchIn(t)) out += "$n references a market package outside mc.**"
        }
        return out
    }

    /** Every rule the market jar violates: it carries no Minecraft class, no descriptor, and main never references mc.**. */
    fun marketViolations(entries: Map<String, ByteArray>): List<String> {
        val out = mutableListOf<String>()
        entries.keys.filter { it.startsWith(MC) }.forEach { out += "Minecraft class $it in the market jar" }
        (DESCRIPTORS.values + "fabric.mod.json").filter { entries.containsKey(it) }.forEach { out += "descriptor $it in the market jar" }
        entries.filterKeys { it.endsWith(".class") && it.startsWith(MARKET) && !it.startsWith(MC) }.forEach { (n, b) ->
            if (text(b).contains(MC)) out += "$n (main) references $MC"
        }
        return out
    }
}
