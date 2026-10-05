package com.panomc.plugins.marketfake

import com.panomc.platform.api.PanoPlugin

/**
 * The T3 fake payment provider plugin (17 section 6). It is built into `build/fake/pano-plugin-market-fake-<version>.jar`
 * by `fakeProviderJar` and is never part of the market jar (`verifyJar` rejects `com/panomc/plugins/marketfake/`).
 *
 * There is no license check (it is not a paid plugin). Instead the plugin is **inert** unless the JVM runs with
 * `-Dpano.market.fakeProvider=true`: without it nothing is registered and the plugin only logs why. The E2E instance
 * is the only JVM started with that flag.
 */
class FakePlugin : PanoPlugin() {
    @Volatile
    private var extension: FakeExtension? = null

    override suspend fun onStart() {
        if (System.getProperty(ENABLE_PROPERTY) != "true") {
            logger.info(DISABLED_MESSAGE)
            return
        }

        val ext = FakeExtension()
        extension = ext
        register(ext)
        logger.info("Market fake gateway registered: ${ext.paymentProviders().joinToString { it.id }}")
    }

    override suspend fun onStop() {
        extension?.let { unRegister(it) }
        extension = null
        logger.info("Market fake gateway stopped")
    }

    companion object {
        const val ENABLE_PROPERTY = "pano.market.fakeProvider"
        const val DISABLED_MESSAGE =
            "pano-plugin-market-fake is a test-only plugin and is disabled (start the JVM with -Dpano.market.fakeProvider=true)"
    }
}
