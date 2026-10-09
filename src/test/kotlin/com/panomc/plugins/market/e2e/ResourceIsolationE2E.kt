package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * V-18 (17 section 9.11, 15 section 2.10 / X-16): a provider plugin that depends on market keeps its own resources. The fake provider plugin
 * (`pano-plugin-market-fake`, dependency `pano-plugin-market`) carries a `config.conf` that names its own logo (`fake-logo.png`) and nothing else: no locales,
 * no UI. Before X-16 a dependent plugin inherited the config, the locales, the logo and the UI bundle of the plugin it depends on, through the dependency class
 * loader. The scenario reads what the real instance serves for both plugin ids: the logo route (bytes and file name), the plugin list, the panel translations
 * and the plugin UI zip route.
 */
class ResourceIsolationE2E : E2eTestBase() {
    override val tag = "ri"

    private val marketId = "pano-plugin-market"
    private val fakeId = "pano-plugin-market-fake"

    private fun resource(name: String): ByteArray =
        (javaClass.getResourceAsStream("/$name") ?: throw AssertionError("test classpath has no resource $name")).use { it.readBytes() }

    /** `GET /api/panel/plugins/:id/logo`: the route answers 302 to the canonical `?hash=` url first, this follows it by hand (the client never follows redirects). */
    private fun logo(pluginId: String): E2eResponse {
        val path = "/api/v1/panel/addons/$pluginId/logo"
        val first = admin.get(path)

        if (first.status == 200) return first

        assertEquals(302, first.status, "the logo route redirects to the hashed url first")

        val location = first.header("Location") ?: throw AssertionError("no Location on the logo redirect")

        assertTrue(location.startsWith(path), location)

        return admin.get(location).also { assertEquals(200, it.status, "the hashed logo url answers") }
    }

    private fun plugins(): List<JsonObject> = admin.get("/api/v1/panel/addons").ok().obj().getJsonArray("items").map { it as JsonObject }

    @Test
    fun `V-18 the fake provider plugin serves its own config and logo, inherits no locales and serves no second UI bundle`() {
        val list = plugins()
        val market = list.firstOrNull { it.getString("id") == marketId } ?: throw AssertionError("the market plugin is not installed")
        val fake = list.firstOrNull { it.getString("id") == fakeId } ?: throw AssertionError("the fake provider plugin is not installed")

        assertEquals("STARTED", market.getString("status"))
        assertEquals("STARTED", fake.getString("status"), "the fake provider runs next to market")
        assertTrue(fake.getJsonArray("dependencies").encode().contains(marketId), "the fake provider depends on market: ${fake.getJsonArray("dependencies").encode()}")
        assertNotEquals(market.getString("hash"), fake.getString("hash"), "two jars")

        // the logo: each plugin serves the file ITS config.conf names, from its own jar
        val marketLogo = logo(marketId)
        val fakeLogo = logo(fakeId)

        assertTrue(marketLogo.header("Content-Disposition").orEmpty().contains("logo.png"), marketLogo.header("Content-Disposition") ?: "none")
        assertTrue(fakeLogo.header("Content-Disposition").orEmpty().contains("fake-logo.png"), "the fake plugin's own config names its own logo: ${fakeLogo.header("Content-Disposition")}")
        assertArrayEquals(resource("logo.png"), marketLogo.body, "market serves its logo")
        assertArrayEquals(resource("fake-logo.png"), fakeLogo.body, "the fake provider serves its own logo, not market's")
        assertFalse(marketLogo.body.contentEquals(fakeLogo.body), "the two logos differ")
        assertEquals("image/png", fakeLogo.header("Content-Type"))

        // the plugin UI bundle: a Kotlin-only provider registers none and serves none under its own id (market's own bundle is not served a second time)
        val fakeUi = visitor("ri-ui").get("/api/v1/plugins/$fakeId/_/ui.zip")

        assertEquals(404, fakeUi.status, "no UI bundle under the fake provider's id")

        val marketUi = visitor("ri-ui-market").get("/api/v1/plugins/$marketId/_/ui.zip")

        assertTrue(marketUi.status == 200 || marketUi.status == 404, "market's own bundle route answers ${marketUi.status}")

        if (marketUi.status == 200) assertTrue(marketUi.body.isNotEmpty(), "market's bundle is not empty")

        // the locales: market's strings are served under market's id, nothing is served under the fake provider's id (it has no locales and inherits none)
        val translations = admin.get("/api/v1/locales/en-US/translations/types/PANEL").ok().obj().getJsonObject("data")
        val byPlugin = translations.getJsonObject("plugins") ?: JsonObject()

        assertNotNull(byPlugin.getJsonObject(marketId), "market's translations are served: ${byPlugin.fieldNames()}")
        assertFalse(byPlugin.containsKey(fakeId), "the fake provider inherited no locales from market: ${byPlugin.fieldNames()}")
    }
}
