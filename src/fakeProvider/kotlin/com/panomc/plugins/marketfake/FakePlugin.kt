package com.panomc.plugins.marketfake

import com.panomc.platform.api.PanoPlugin

/**
 * Inert skeleton of the T3 fake payment provider plugin (17 section 6). It is built into
 * `build/fake/pano-plugin-market-fake-<version>.jar` by `fakeProviderJar` and is never part of the market jar
 * (`verifyJar` rejects `com/panomc/plugins/marketfake/`). The provider itself is added by the slices that
 * own the payment SPI; until then this class only proves that the source set compiles and packages.
 */
class FakePlugin : PanoPlugin() {
    override suspend fun onStart() {
        logger.info("Market fake gateway skeleton loaded (inert)")
    }

    override suspend fun onStop() {
        logger.info("Market fake gateway skeleton stopped")
    }
}
