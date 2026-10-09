package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.StoreLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StoreLinksTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the template and the register url win when usable`() {
        val l = StoreLinks("https://x.example/", "https://shop.example/p/{slug}", "https://shop.example/join")
        assertEquals("https://shop.example/p/rank-vip", l.productUrl("rank-vip"))
        assertEquals("https://shop.example/join", l.registerUrl())
        assertEquals("https://x.example/store", l.productUrl(null))
    }

    @Test
    fun `absent or unusable fields fall back to storeUrl paths`() {
        listOf(null, "", "javascript:alert(1)", "ftp://x/{slug}").forEach { t ->
            val l = StoreLinks("https://x.example/", t, t)
            assertEquals("https://x.example/store/vip", l.productUrl("vip"), "$t")
            assertEquals("https://x.example/register", l.registerUrl(), "$t")
        }
        // a template without the placeholder cannot name a product, so it is unusable too
        assertEquals("https://x.example/store/vip", StoreLinks("https://x.example/", "https://shop.example/p/nope").productUrl("vip"))
        assertNull(StoreLinks(null).productUrl("vip"))
        assertEquals("https://x.example/store", StoreLinks("https://x.example", "https://x.example/{slug}").productUrl("a/b"))
    }

    @Test
    fun `a Pano config answer feeds the links of the running component`() {
        val r = FeatureRig(dir)
        r.loadConfig(panoConfig("h", productUrlTemplate = "https://shop.example/p/{slug}", registerUrl = "https://shop.example/join"))
        assertEquals("https://shop.example/p/vip", r.features.config.remote!!.links.productUrl("vip"))
        r.loadConfig(panoConfig("h2", storeUrl = "https://x.example"))
        assertEquals("https://x.example/register", r.features.config.remote!!.links.registerUrl())
    }
}
