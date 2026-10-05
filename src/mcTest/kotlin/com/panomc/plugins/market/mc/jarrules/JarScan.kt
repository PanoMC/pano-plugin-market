package com.panomc.plugins.market.mc.jarrules

/**
 * The jar rules of 19 section 2.1 / 17 section 3.2 for the Minecraft side, as a pure function over jar entries, so the
 * rules themselves can be shown to fail on a bad jar. `verifyJar` in build.gradle.kts applies the same rules to the real jar.
 */
object JarScan {
    const val MARKET = "com/panomc/plugins/market/"
    const val MC = "com/panomc/plugins/market/mc/"
    const val MC_VELOCITY = "com/panomc/plugins/market/mc/velocity/"

    private val bundledPrefixes = listOf(
        "com/panomc/plugins/pano/core/", "org/bukkit/", "org/spigotmc/", "net/md_5/", "net/luckperms/",
        "com/velocitypowered/", "net/milkbowl/", "me/clip/"
    )
    private val forbiddenPrefixes = listOf(
        "com/panomc/plugins/marketfake/", "com/panomc/platform/", "kotlin/", "kotlinx/coroutines/", "io/vertx/", "org/springframework/", "com/google/gson/"
    )
    private val otherMarketRef = Regex("com/panomc/plugins/market/(?!mc/)")

    fun classMajor(bytes: ByteArray): Int = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    /** Every violated rule, one line each; empty = the jar passes. */
    fun violations(entries: Map<String, ByteArray>, version: String): List<String> {
        val out = mutableListOf<String>()
        for (f in listOf("plugin.yml", "bungee.yml", "velocity-plugin.json")) {
            val body = entries[f]
            if (body == null) {
                out += "$f missing at the jar root"
                continue
            }
            val t = String(body, Charsets.UTF_8)
            if (!t.contains(version)) out += "$f does not carry the version $version"
            if (t.contains("\${")) out += "$f holds an unexpanded placeholder"
        }
        entries.keys.filter { n -> forbiddenPrefixes.any { n.startsWith(it) } }.forEach { out += "forbidden entry $it" }
        entries.keys.filter { n -> bundledPrefixes.any { n.startsWith(it) } }.forEach { out += "bundled Minecraft-side library $it" }
        entries.filterKeys { it.endsWith(".class") && !it.startsWith("META-INF/") }.forEach { (n, b) ->
            val limit = if (n.startsWith(MC_VELOCITY)) 61 else 55
            val major = classMajor(b)
            if (major > limit) out += "$n is class major $major (> $limit)"
        }
        entries.filterKeys { it.endsWith(".class") && it.startsWith(MARKET) }.forEach { (n, b) ->
            val body = text(b)
            if (n.startsWith(MC)) {
                if (body.contains("com/panomc/platform/")) out += "$n references com/panomc/platform/"
                if (otherMarketRef.containsMatchIn(body)) out += "$n references a market package outside mc.**"
            } else if (body.contains(MC)) {
                out += "$n (main) references $MC"
            }
        }
        return out
    }
}
