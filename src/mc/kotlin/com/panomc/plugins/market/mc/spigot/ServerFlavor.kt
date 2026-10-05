package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.core.wire.McPlatformName

/** Which member of the Bukkit family this is (`MARKET_SYNC.platform`, and whether the Folia schedulers are needed). */
object ServerFlavor {
    fun detect(classExists: (String) -> Boolean = ::classExists, serverName: () -> String = { "" }): String = when {
        classExists("io.papermc.paper.threadedregions.RegionizedServer") -> McPlatformName.FOLIA
        classExists("com.destroystokyo.paper.PaperConfig") || classExists("io.papermc.paper.configuration.Configuration") -> McPlatformName.PAPER
        serverName().contains("paper", ignoreCase = true) || serverName().contains("purpur", ignoreCase = true) -> McPlatformName.PAPER
        else -> McPlatformName.SPIGOT
    }

    fun isFolia(classExists: (String) -> Boolean = ::classExists): Boolean = detect(classExists) == McPlatformName.FOLIA

    private fun classExists(name: String): Boolean = try {
        Class.forName(name, false, ServerFlavor::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }
}
