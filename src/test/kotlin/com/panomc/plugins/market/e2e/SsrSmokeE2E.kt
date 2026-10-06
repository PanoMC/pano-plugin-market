package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eSession
import com.panomc.plugins.market.e2e.support.E2eTestBase
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * SSR smoke of the storefront (17 section 10, T5-SSR; 14 section 14): `/store`, `/store/<slug>`, `/store/checkout` and `/store/order/<publicId>`
 * answer 200 with the server-rendered product name / order number, the page title and the meta description, and a product whose stored
 * description carried a script (scenario B-04) renders without it. Needs the jar built WITH the UI and the instance started with the host UIs
 * (`e2e-instance.sh start --ui external:<themePort>,<panelPort>`): the pages are fetched from `MARKET_E2E_THEME_URL` (the theme dev server that
 * server-renders the plugin bundle and calls this instance), else from the instance itself (the bundled theme behind the platform proxy).
 */
class SsrSmokeE2E : E2eTestBase() {
    override val tag = "ssr"

    private val pageBase: String = (System.getenv("MARKET_E2E_THEME_URL")?.takeIf { it.isNotBlank() } ?: baseUrl).trimEnd('/')

    /**
     * The theme of the local checkout (`MARKET_E2E_THEME_URL`) carries the host feature `page-meta` (X-8): the pages must then set their meta tags.
     * Fetched through the instance, the page comes from the vanilla theme zip bundled in the platform jar, which may predate it; 14 section 14
     * documents that degraded mode (no `meta`, only the document title), so only the title is asserted there.
     */
    private val metaExpected: Boolean = pageBase != baseUrl.trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10)).build()

    private fun uniq(): String = System.nanoTime().toString(36).takeLast(9)

    /** A page of the storefront as a browser asks for it (HTML, optional session cookie); the first request of a dev server compiles the bundle, hence the long timeout. */
    private fun page(path: String, cookie: String? = null): Html {
        val request = HttpRequest.newBuilder(URI.create(pageBase + path)).timeout(Duration.ofSeconds(240))
            .header("Accept", "text/html").header("Accept-Language", "en-US,en;q=0.9").apply { cookie?.let { header("Cookie", it) } }.GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        println("e2e[ssr] GET $path -> ${response.statusCode()} (${response.body().length} chars)")
        return Html(response.statusCode(), response.body(), response.headers().firstValue("content-type").orElse(""))
    }

    private class Html(val status: Int, val html: String, val contentType: String)

    /** The session cookie of a registered buyer (a second login of the same user; the harness client keeps its own jar private). */
    private fun cookieOf(buyer: com.panomc.plugins.market.e2e.support.E2eBuyer): String {
        db.verifyEmail(buyer.userId) // a second login needs a verified e-mail (the registration session did not)
        val username = buyer.username
        val response = E2eClient(baseUrl, "ssr-cookie").login(username, E2eSession.PASSWORD)
        assertEquals(200, response.status, "login of $username: ${response.error} ${response.json?.encode()?.take(300)}")
        val cookie = (response.headers.entries.firstOrNull { it.key.equals("set-cookie", true) }?.value ?: emptyList())
            .map { it.substringBefore(';') }.filter { it.substringAfter('=', "").isNotEmpty() }
        assertTrue(cookie.isNotEmpty(), "the login set a session cookie")
        return cookie.joinToString("; ")
    }

    private fun title(html: String): String = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim() ?: ""

    private fun metaContent(html: String, name: String): String? =
        Regex("<meta\\s+name=\"${Regex.escape(name)}\"\\s+content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)

    /** No SvelteKit / Svelte error page and no host error text. */
    private fun assertRendered(path: String, answer: Html) {
        assertEquals(200, answer.status, "$path answers 200")
        assertTrue(answer.contentType.startsWith("text/html"), "$path is HTML, was ${answer.contentType}")
        // the page also carries the serialised locale texts (they hold words like "Internal Error"), so the visible text is what is judged
        listOf("svelte.dev/e/", "lifecycle_outside_component", "[GLOBAL ERROR", "Unhandled Rejection", "Internal Error").forEach {
            assertFalse(visibleText(answer.html).contains(it, ignoreCase = true), "$path shows no server-render error text ($it)")
        }
        assertFalse(Regex("<h1[^>]*>\\s*(500|404)\\s*</h1>").containsMatchIn(answer.html), "$path is not an error page")
        assertFalse(Regex("theme\\.[a-z-]+\\.[a-z-.]+").containsMatchIn(visibleText(answer.html)), "$path shows no raw theme key")
    }

    private fun visibleText(html: String): String =
        html.replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("<[^>]+>"), " ")

    private fun unescape(text: String): String = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    @Test
    fun `SSR-01 store page lists the product by name with title and description`() {
        val name = "Ssr Crate " + uniq()
        val product = catalog.fresh("VIP", "name" to name)

        val answer = page("/store?search=${name.replace(" ", "%20")}")
        assertRendered("/store", answer)
        assertTrue(answer.html.contains(name), "the product name is in the server-rendered HTML")
        assertTrue(answer.html.contains("/store/${product.slug}"), "the card links to the product page")
        assertTrue(title(answer.html).isNotBlank(), "the document has a title")
        if (metaExpected) {
            val description = metaContent(answer.html, "description")
            assertTrue(description != null && description.isNotBlank(), "the store page has a meta description")
        }
    }

    @Test
    fun `SSR-02 product page renders its name and sanitised description with title and meta description`() {
        val name = "Ssr Pack " + uniq()
        val description = "<p>safe <strong>bold</strong></p><script>alert('ssr-xss-1')</script><img src=x onerror=alert('ssr-xss-2')><a href=\"javascript:alert('ssr-xss-3')\">click</a>"
        val product = catalog.fresh(
            "VIP", "name" to name, "description" to description,
            "metaTitle" to "Meta $name", "metaDescription" to "Meta description of $name & more"
        )

        val answer = page("/store/${product.slug}")
        assertRendered("/store/${product.slug}", answer)
        assertTrue(answer.html.contains(name), "the product name is in the HTML")
        assertTrue(title(answer.html).isNotBlank(), "the document has a title")
        if (metaExpected) assertTrue(title(answer.html).contains("Meta $name"), "the document title is the product's metaTitle, was '${title(answer.html)}'")
        if (metaExpected) {
            assertEquals("Meta description of $name & more", unescape(metaContent(answer.html, "description") ?: ""), "the meta description is the product's metaDescription")
        }

        // scenario B-04: the stored script of the description never reaches the page
        assertTrue(answer.html.contains("<strong>bold</strong>"), "the formatting that is safe stays")
        listOf("ssr-xss-1", "ssr-xss-2", "ssr-xss-3").forEach { assertFalse(answer.html.contains(it), "the hostile description part $it is not in the HTML") }
        assertFalse(answer.html.contains("onerror=", ignoreCase = true), "no inline event handler from the description")
        assertFalse(Regex("<a[^>]*href=\"javascript:", RegexOption.IGNORE_CASE).containsMatchIn(answer.html), "no javascript: link")

        // without a metaDescription the page falls back to the short description / plain description (14 section 14)
        val plain = catalog.fresh("VIP", "name" to "Ssr Plain " + uniq(), "description" to "<p>Plain fallback text for the description meta.</p>", "metaDescription" to "")
        val fallback = page("/store/${plain.slug}")
        assertRendered("/store/${plain.slug}", fallback)
        if (metaExpected) assertTrue((metaContent(fallback.html, "description") ?: "").isNotBlank(), "the meta description falls back to the product texts")

        // an unknown slug is the host's 404, not a 500
        val unknown = page("/store/no-such-product-${uniq()}")
        assertEquals(404, unknown.status, "an unknown product is a 404")
    }

    @Test
    fun `SSR-03 checkout page renders its title and is noindex`() {
        val answer = page("/store/checkout")
        assertRendered("/store/checkout", answer)
        assertTrue(title(answer.html).isNotBlank(), "the checkout page has a title")
        if (metaExpected) assertTrue((metaContent(answer.html, "robots") ?: "").contains("noindex"), "the checkout page is noindex, was ${metaContent(answer.html, "robots")}")
    }

    @Test
    fun `SSR-04 order page renders the order number for its owner`() {
        val product = catalog.fresh("VIP")
        val buyer = buyer()
        val created = checkout(buyer.client, cart(line(product.id))).ok()
        val publicId = publicIdOf(created)
        val number = order(buyer.client, publicId).getValue("number").toString()
        assertTrue(number.isNotBlank() && number != "null", "the order has a number")

        val path = "/store/order/$publicId"
        val answer = page(path, cookieOf(buyer))
        assertRendered(path, answer)
        assertTrue(answer.html.contains(number), "the order number $number is in the server-rendered HTML")
        assertTrue(title(answer.html).isNotBlank(), "the order page has a title")
        if (metaExpected) assertTrue((metaContent(answer.html, "robots") ?: "").contains("noindex"), "the order page is noindex")

        // a visitor without the token gets the page too (the limited view), never an error
        val anonymous = page(path)
        assertEquals(200, anonymous.status, "the limited order page answers 200")

        // a malformed id is a 404
        assertEquals(404, page("/store/order/not-an-order").status, "a malformed order id is a 404")
    }
}
