package com.panomc.plugins.market.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McDownloadUrlTest {
    private val releases = "https://github.com/PanoMC/pano-plugin-market/releases"

    @Test
    fun `a released version downloads its own jar from GitHub`() {
        assertEquals("$releases/download/v1.0.0-dev.30/pano-plugin-market-1.0.0-dev.30.jar", McSyncService.githubDownloadUrl("1.0.0-dev.30", fabric = false))
        assertEquals("$releases/download/v1.2.0/pano-plugin-market-1.2.0.jar", McSyncService.githubDownloadUrl("1.2.0", fabric = false))
    }

    @Test
    fun `a Fabric server gets the Fabric jar of the same release`() {
        assertEquals("$releases/download/v1.0.0-dev.30/pano-plugin-market-fabric-1.0.0-dev.30.jar", McSyncService.githubDownloadUrl("1.0.0-dev.30", fabric = true))
    }

    @Test
    fun `a build that was never released opens the release list`() {
        assertEquals(releases, McSyncService.githubDownloadUrl("local-build", fabric = false))
        assertEquals(releases, McSyncService.githubDownloadUrl("local-build", fabric = true))
        assertEquals(releases, McSyncService.githubDownloadUrl("", fabric = false))
    }
}
