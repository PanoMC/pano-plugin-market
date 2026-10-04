package com.panomc.plugins.market.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlSanitizerTest {
    private val xssPayloads = listOf(
        "<script>alert(1)</script>",
        "<SCRIPT SRC=//evil.example/x.js></SCRIPT>",
        "<scr\u0000ipt>alert(1)</scr\u0000ipt>",
        "<scr<script>ipt>alert(1)</scr</script>ipt>",
        "<<script>alert(1)//<</script>",
        "<script/x>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<img src=\"x\" onerror=\"alert(1)\" />",
        "<IMG SRC=\"javascript:alert('XSS')\">",
        "<img src=\"data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+\">",
        "<img/src=x/onerror=alert(1)>",
        "<svg/onload=alert(1)>",
        "<svg><script>alert(1)</script></svg>",
        "<math><mi//xlink:href=\"javascript:alert(1)\">x</mi></math>",
        "<iframe src=\"javascript:alert(1)\"></iframe>",
        "<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>",
        "<object data=\"javascript:alert(1)\"></object>",
        "<embed src=\"javascript:alert(1)\">",
        "<body onload=alert(1)>",
        "<input onfocus=alert(1) autofocus>",
        "<form action=\"javascript:alert(1)\"><button formaction=javascript:alert(1)>x</button></form>",
        "<a href=\"javascript:alert(1)\">x</a>",
        "<a href=\"  JaVa\tScRiPt:alert(1)\">x</a>",
        "<a href=\"jav&#x09;ascript:alert(1)\">x</a>",
        "<a href=\"jav&Tab;ascript:alert(1)\">x</a>",
        "<a href=\"&#106;avascript&colon;alert(1)\">x</a>",
        "<a href=\"&#0000106&#0000097&#0000118&#0000097&#0000115&#0000099&#0000114&#0000105&#0000112&#0000116&#0000058alert(1)\">x</a>",
        "<a href=javascript:alert(1)>x</a>",
        "<a href=\"vbscript:msgbox(1)\">x</a>",
        "<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">x</a>",
        "<a href=\"\" onclick=\"alert(1)\">x</a>",
        "<a href=\"https://ok.example\" onmouseover=\"alert(1)\">x</a>",
        "<a href=\"https://ok.example\" \" onmouseover=\"alert(1)\">x</a>",
        "<div style=\"background:url(javascript:alert(1))\">x</div>",
        "<div style=\"width:expression(alert(1))\">x</div>",
        "<p onclick=alert(1)>x</p>",
        "<b onclick='alert(1)'>x</b>",
        "<style>body{background:url(javascript:alert(1))}</style>",
        "<link rel=stylesheet href=\"javascript:alert(1)\">",
        "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
        "<base href=\"javascript:alert(1)//\">",
        "<template><script>alert(1)</script></template>",
        "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
        "<textarea><script>alert(1)</script></textarea>",
        "<title><img src=x onerror=alert(1)></title>",
        "<xmp><script>alert(1)</script></xmp>",
        "<!--<script>alert(1)</script>-->",
        "<!--[if IE]><script>alert(1)</script><![endif]-->",
        "<![CDATA[<script>alert(1)</script>]]>",
        "<?xml version=\"1.0\"?><script>alert(1)</script>",
        "<!DOCTYPE html><script>alert(1)</script>",
        "<a href=\"x\" title=\"\"><img src=x onerror=alert(1)>\">x</a>",
        "<img src=\"x\" alt=\"\"onerror=\"alert(1)\">",
        "<table><td colspan=\"1\" onclick=\"alert(1)\">x</td></table>",
        "<a href=\"https://ok.example\" target=\"_blank\" onclick=alert(1)>x</a>",
        "<script",
        "<img src=\"x"
    )

    // The sanitized form of any input must contain no live script vector, whatever the input was.
    private fun assertNoVector(output: String, input: String) {
        val lower = output.lowercase()
        val ctx = "input: $input -> output: $output"

        listOf(
            "<script", "<iframe", "<svg", "<math", "<style", "<object", "<embed", "<form", "<input",
            "<body", "<link", "<meta", "<base", "<template", "<textarea", "<title", "<xmp", "<!--"
        ).forEach { assertFalse(lower.contains(it), "found $it; $ctx") }

        assertFalse(Regex("\\son[a-z]+\\s*=").containsMatchIn(lower), "event handler; $ctx")
        // A style attribute may only survive on span, and never with a fetching/evaluating value.
        assertFalse(Regex("<(?!span)[a-z0-9]+[^>]*\\sstyle\\s*=").containsMatchIn(lower), "style attribute outside span; $ctx")
        Regex("\\sstyle=\"([^\"]*)\"").findAll(lower).forEach { style ->
            listOf("url(", "expression(", "javascript:", "\\", "/*").forEach {
                assertFalse(style.groupValues[1].contains(it), "dangerous style value $it; $ctx")
            }
        }
        assertFalse(Regex("(href|src)=\"\\s*(javascript|vbscript|data):").containsMatchIn(lower), "bad url; $ctx")

        // Only allowlisted tags, with only allowlisted attributes, may appear.
        Regex("<\\s*/?\\s*([a-zA-Z][^\\s/>]*)([^>]*)>").findAll(output).forEach { tag ->
            val name = tag.groupValues[1].lowercase()
            assertTrue(name in setOf(
                "p", "br", "hr", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
                "strong", "b", "em", "i", "u", "s", "strike", "del", "ins", "sub", "sup", "small", "mark",
                "blockquote", "pre", "code", "a", "img", "table", "thead", "tbody", "tfoot", "tr", "th",
                "td", "caption"
            ), "tag $name not allowed; $ctx")
        }

        Regex("\\s([a-zA-Z\\-]+)=\"").findAll(output).forEach { attr ->
            assertTrue(
                attr.groupValues[1] in setOf("href", "src", "alt", "title", "width", "height", "colspan", "rowspan", "target", "rel", "style"),
                "attribute ${attr.groupValues[1]} not allowed; $ctx"
            )
        }
    }

    @Test
    fun `classic XSS payloads leave no script vector`() {
        xssPayloads.forEach { payload ->
            val output = HtmlSanitizer.sanitize(payload)
            assertNoVector(output, payload)
        }
    }

    @Test
    fun `sanitizing is idempotent`() {
        xssPayloads.forEach { payload ->
            val once = HtmlSanitizer.sanitize(payload)
            assertEquals(once, HtmlSanitizer.sanitize(once), "not idempotent for: $payload")
        }
    }

    @Test
    fun `script and other raw text elements are dropped with their content`() {
        assertEquals("Hi", HtmlSanitizer.sanitize("<script>alert(1)</script>Hi"))
        assertEquals("Hi", HtmlSanitizer.sanitize("Hi<style>p{color:red}</style>"))
        assertEquals("ab", HtmlSanitizer.sanitize("a<iframe src=\"https://x.example\">hidden</iframe>b"))
        assertEquals("ab", HtmlSanitizer.sanitize("a<!-- <script>alert(1)</script> -->b"))
        // Unterminated raw-text element: everything after it is dropped, never emitted.
        assertEquals("a", HtmlSanitizer.sanitize("a<script>alert(1)"))
    }

    @Test
    fun `event handlers and style attributes are stripped but the element is kept`() {
        assertEquals("<b>bold</b>", HtmlSanitizer.sanitize("<b onclick=\"x()\">bold</b>"))
        assertEquals("<div>hi</div>", HtmlSanitizer.sanitize("<div style=\"color:red\" class=\"a\">hi</div>"))
        assertEquals("<img src=\"x\">", HtmlSanitizer.sanitize("<img src=x onerror=alert(1)>"))
    }

    @Test
    fun `text styling on span keeps only filtered declarations`() {
        assertEquals("<span style=\"color: #ff0000\">x</span>", HtmlSanitizer.sanitize("<span style=\"color: #ff0000\">x</span>"))
        assertEquals(
            "<span style=\"color: red\">x</span>",
            HtmlSanitizer.sanitize("<span style=\"color:red;background:url(javascript:alert(1))\">x</span>")
        )
        assertEquals(
            "<span style=\"color: rgb(1, 2, 3); font-size: 14px\">x</span>",
            HtmlSanitizer.sanitize("<span style=\"COLOR : rgb(1, 2, 3);font-size:14px\">x</span>")
        )
        assertEquals("<span>x</span>", HtmlSanitizer.sanitize("<span style=\"background-color:url(x)\">x</span>"))
        assertEquals("<span>x</span>", HtmlSanitizer.sanitize("<span style=\"color:expression(alert(1))\">x</span>"))
        assertEquals("<span>x</span>", HtmlSanitizer.sanitize("<span style=\"color:\\72ed\">x</span>"))
        assertEquals("<span>x</span>", HtmlSanitizer.sanitize("<span style=\"position:fixed\">x</span>"))
        assertEquals("<span>x</span>", HtmlSanitizer.sanitize("<span style=\"color:red/*;*/x\">x</span>"))
        // Entity-encoded separators are decoded before filtering, so they cannot smuggle a declaration.
        assertEquals("<span>x</span>", HtmlSanitizer.sanitize("<span style=\"background&#58;url(x)\">x</span>"))
        // Only span carries a style.
        assertEquals("<p>x</p>", HtmlSanitizer.sanitize("<p style=\"color:red\">x</p>"))
    }

    @Test
    fun `javascript and data urls are removed from links and images`() {
        assertEquals("<a>x</a>", HtmlSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>"))
        assertEquals("<a>x</a>", HtmlSanitizer.sanitize("<a href=\" JaVa\tScRiPt:alert(1)\">x</a>"))
        assertEquals("<a>x</a>", HtmlSanitizer.sanitize("<a href=\"jav&#x09;ascript:alert(1)\">x</a>"))
        assertEquals("<a>x</a>", HtmlSanitizer.sanitize("<a href=\"&#106;avascript&colon;alert(1)\">x</a>"))
        assertEquals("<a>x</a>", HtmlSanitizer.sanitize("<a href=\"data:text/html,hi\">x</a>"))
        assertEquals("<img>", HtmlSanitizer.sanitize("<img src=\"javascript:alert(1)\">"))
        assertEquals("<img>", HtmlSanitizer.sanitize("<img src=\"data:image/png;base64,AAAA\">"))
    }

    @Test
    fun `legit rich text passes through unchanged`() {
        val html = "<h2>Rank</h2><p>Gets <strong>fly</strong>, <em>kits</em> and <u>more</u>.</p>" +
            "<ul><li>One</li><li>Two</li></ul><ol><li>A</li></ol><blockquote>Quote</blockquote>" +
            "<p>Line<br>break<hr></p><pre><code>/home</code></pre>"

        assertEquals(html, HtmlSanitizer.sanitize(html))
    }

    @Test
    fun `links keep safe hrefs and get rel noopener`() {
        assertEquals(
            "<a href=\"https://panomc.com/docs?a=1&amp;b=2\" title=\"Docs\" rel=\"noopener\">Docs</a>",
            HtmlSanitizer.sanitize("<a href=\"https://panomc.com/docs?a=1&b=2\" title=\"Docs\">Docs</a>")
        )
        assertEquals(
            "<a href=\"mailto:a@b.example\" rel=\"noopener\">m</a>",
            HtmlSanitizer.sanitize("<a href=\"mailto:a@b.example\">m</a>")
        )
        assertEquals(
            "<a href=\"/store\" rel=\"noopener\">s</a>",
            HtmlSanitizer.sanitize("<a href=\"/store\">s</a>")
        )
        // A merchant-supplied rel is dropped, target=_blank gets the full rel.
        assertEquals(
            "<a href=\"http://x.example\" target=\"_blank\" rel=\"noopener noreferrer\">x</a>",
            HtmlSanitizer.sanitize("<a href=\"http://x.example\" target=\"_blank\" rel=\"opener\">x</a>")
        )
        assertEquals("<a href=\"http://x.example\" rel=\"noopener\">x</a>", HtmlSanitizer.sanitize("<a href=\"http://x.example\" target=\"_top\">x</a>"))
    }

    @Test
    fun `images keep http and https sources and sizes`() {
        assertEquals(
            "<img src=\"https://cdn.example/a.png\" alt=\"A\" width=\"200\" height=\"100\">",
            HtmlSanitizer.sanitize("<img src=\"https://cdn.example/a.png\" alt=\"A\" width=\"200\" height=\"100\">")
        )
        assertEquals("<img src=\"http://cdn.example/a.png\">", HtmlSanitizer.sanitize("<img src='http://cdn.example/a.png' />"))
        assertEquals("<img src=\"/api/market/products/image/a.png\">", HtmlSanitizer.sanitize("<img src=\"/api/market/products/image/a.png\">"))
        assertEquals("<img src=\"https://x.example/a.png\">", HtmlSanitizer.sanitize("<img src=\"https://x.example/a.png\" width=\"100%\" height=\"expression(1)\">"))
    }

    @Test
    fun `tables keep span attributes`() {
        val html = "<table><thead><tr><th colspan=\"2\">H</th></tr></thead><tbody><tr><td rowspan=\"2\">a</td><td>b</td></tr></tbody></table>"

        assertEquals(html, HtmlSanitizer.sanitize(html))
    }

    @Test
    fun `text is escaped and entities are preserved`() {
        assertEquals("1 &lt; 2 &amp;&amp; 3 &gt; 2", HtmlSanitizer.sanitize("1 < 2 && 3 > 2"))
        assertEquals("Fish &amp; Chips &copy; &#169; &#xA9;", HtmlSanitizer.sanitize("Fish &amp; Chips &copy; &#169; &#xA9;"))
        assertEquals("&lt;3 &amp;nope", HtmlSanitizer.sanitize("<3 &nope"))
        assertEquals("Türkçe İçerik ✓ 日本語", HtmlSanitizer.sanitize("Türkçe İçerik ✓ 日本語"))
    }

    @Test
    fun `unknown tags are unwrapped and their text kept`() {
        assertEquals("hello", HtmlSanitizer.sanitize("<font color=\"red\">hello</font>"))
        assertEquals("x", HtmlSanitizer.sanitize("<custom-tag onclick=alert(1)>x</custom-tag>"))
    }

    @Test
    fun `markup is balanced`() {
        assertEquals("<b>bold</b>", HtmlSanitizer.sanitize("<b>bold"))
        assertEquals("<p>a</p><i>b</i>", HtmlSanitizer.sanitize("<p>a</p></p><i>b"))
        assertEquals("<b><i>x</i></b>", HtmlSanitizer.sanitize("<b><i>x</b>"))
    }

    @Test
    fun `plain text and empty input are unchanged and null stays null`() {
        assertEquals("", HtmlSanitizer.sanitize(""))
        assertEquals("Just text.\nSecond line", HtmlSanitizer.sanitize("Just text.\nSecond line"))
        assertNull(HtmlSanitizer.sanitizeOrNull(null))
        assertEquals("<p>x</p>", HtmlSanitizer.sanitizeOrNull("<p>x</p>"))
    }
}
