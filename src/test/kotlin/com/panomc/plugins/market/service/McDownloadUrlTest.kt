package com.panomc.plugins.market.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McDownloadUrlTest {
    private val releases = "https://github.com/PanoMC/pano-plugin-market/releases"

    @Test
    fun `a released version downloads the jar of the server's platform from GitHub`() {
        assertEquals("$releases/download/v1.0.0-dev.30/pano-plugin-market-spigot-1.0.0-dev.30.jar", McSyncService.githubDownloadUrl("1.0.0-dev.30", "PAPER"))
        assertEquals("$releases/download/v1.2.0/pano-plugin-market-spigot-1.2.0.jar", McSyncService.githubDownloadUrl("1.2.0", "FOLIA"))
        assertEquals("$releases/download/v1.2.0/pano-plugin-market-spigot-1.2.0.jar", McSyncService.githubDownloadUrl("1.2.0", "SPIGOT"))
        assertEquals("$releases/download/v1.2.0/pano-plugin-market-spigot-1.2.0.jar", McSyncService.githubDownloadUrl("1.2.0", "PURPUR"))
        assertEquals("$releases/download/v1.2.0/pano-plugin-market-bungee-1.2.0.jar", McSyncService.githubDownloadUrl("1.2.0", "BUNGEECORD"))
        assertEquals("$releases/download/v1.2.0/pano-plugin-market-bungee-1.2.0.jar", McSyncService.githubDownloadUrl("1.2.0", "WATERFALL"))
        assertEquals("$releases/download/v1.2.0/pano-plugin-market-velocity-1.2.0.jar", McSyncService.githubDownloadUrl("1.2.0", "VELOCITY"))
        assertEquals("$releases/download/v1.0.0-dev.30/pano-plugin-market-fabric-1.0.0-dev.30.jar", McSyncService.githubDownloadUrl("1.0.0-dev.30", "FABRIC"))
    }

    @Test
    fun `a platform without a Market jar opens the release page of the version`() {
        assertEquals("$releases/tag/v1.2.0", McSyncService.githubDownloadUrl("1.2.0", null))
        assertEquals("$releases/tag/v1.2.0", McSyncService.githubDownloadUrl("1.2.0", "VANILLA"))
        assertEquals("$releases/tag/v1.2.0", McSyncService.githubDownloadUrl("1.2.0", "FORGE"))
    }

    @Test
    fun `a build that was never released opens the release list`() {
        assertEquals(releases, McSyncService.githubDownloadUrl("local-build", "PAPER"))
        assertEquals(releases, McSyncService.githubDownloadUrl("local-build", "FABRIC"))
        assertEquals(releases, McSyncService.githubDownloadUrl("", null))
    }
}
