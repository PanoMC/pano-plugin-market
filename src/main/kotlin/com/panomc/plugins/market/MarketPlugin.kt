package com.panomc.plugins.market

import com.panomc.platform.api.PanoPlugin

class MarketPlugin : PanoPlugin() {
    override suspend fun onStart() {
        logger.info("Starting...")
    }

    override suspend fun onEnable() {
        logger.info("Enabled!")
    }

    override suspend fun onUninstall() {
        logger.info("Uninstalling...")

        // add some cleanup codes for your data used in plugin before uninstalling
    }
}

