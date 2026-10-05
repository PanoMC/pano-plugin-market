package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.market.mc.core.platform.McLog
import java.util.logging.Level
import java.util.logging.Logger

/** [McLog] over `java.util.logging` (Bukkit and BungeeCord plugin loggers). */
class JulMcLog(private val logger: Logger) : McLog {
    override fun info(message: String) = logger.info(message)
    override fun warn(message: String) = logger.warning(message)
    override fun error(message: String, error: Throwable?) {
        if (error == null) logger.severe(message) else logger.log(Level.SEVERE, message, error)
    }
}
