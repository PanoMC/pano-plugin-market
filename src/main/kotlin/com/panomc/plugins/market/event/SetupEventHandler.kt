package com.panomc.plugins.market.event

import com.panomc.platform.api.annotation.EventListener
import com.panomc.platform.api.event.SetupEventListener
import com.panomc.plugins.market.MarketPlugin

@EventListener
class SetupEventHandler(private val plugin: MarketPlugin) : SetupEventListener {
    private val logger by lazy {
        plugin.logger
    }

    override suspend fun onSetupFinished() {
        // plugin.pluginState is a snapshot taken at instance creation and never reads STARTED (see MarketPlugin.isRunning).
        if (plugin.isRunning) {
            logger.info("Setup finished! Initializing plugin...")

            plugin.startPlugin()
        }
    }
}
