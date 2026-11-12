package com.email.server

import com.email.server.mail.BodyReader
import com.email.server.mail.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizerTest {
    private fun clean(html: String, images: Boolean = false) =
        Sanitizer.clean(html, cidUrl = { "/api/parts/x/$it" }, proxy = if (images) { url -> "/proxy/${url.length}" } else null)

    @Test
    fun `nothing that runs code survives`() {
        val attacks = listOf(
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "<a href=\"javascript:alert(1)\">x</a>",
            "<a href=\"JaVaScRiPt:alert(1)\">x</a>",
            "<a href=\"jav&#x09;ascript:alert(1)\">x</a>",
            "<svg onload=alert(1)>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<object data=\"x\"></object><embed src=\"x\">",
            "<form action=\"https://evil.example\"><input name=p><button>Go</button></form>",
            "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\">",
            "<base href=\"https://evil.example/\">",
            "<div style=\"background:url(javascript:alert(1))\">x</div>",
            "<div style=\"width: expression(alert(1))\">x</div>",
            "<div style=\"-moz-binding:url(x)\">x</div>",
            "<style>@import url(https://evil.example/a.css); body{behavior:url(x)}</style>",
            "<style>a{}</style><style></style><script>alert(1)</script>",
            "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
            "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">",
        )
        attacks.forEach { attack ->
            val out = clean(attack).html
            val doc = org.jsoup.Jsoup.parse(out)
            val body = doc.body()
            assertTrue("dangerous elements in $out", body.select("script, iframe, object, embed, form, input, button, svg, math, meta, base, link, noscript").isEmpty())
            assertEquals("only our own head", 1, doc.head().select("base").size)
            doc.select("*").forEach { el ->
                el.attributes().forEach { a ->
                    assertFalse("${a.key} on ${el.tagName()} from $attack", a.key.lowercase().startsWith("on"))
                    assertFalse("${a.key}=${a.value} from $attack", a.value.lowercase().replace(Regex("\\s"), "").contains("javascript:"))
                    assertFalse("${a.key}=${a.value} from $attack", a.value.contains("evil.example"))
                }
            }
            val css = doc.select("style").joinToString(" ") { it.data() }.lowercase() + body.select("[style]").joinToString(" ") { it.attr("style") }.lowercase()
            listOf("expression(", "-moz-binding", "behavior:", "@import", "javascript:", "evil.example", "</style").forEach {
                assertFalse("'$it' in the CSS from $attack: $css", css.contains(it))
            }
        }
    }

    @Test
    fun `remote content is left out until asked for, then proxied`() {
        val html = """<p style="background-image:url('https://t.example/a.png')">Hi</p><img src="https://t.example/p.gif"><img src="//t.example/q.gif">
            <table background="http://t.example/bg.jpg"><tr><td>x</td></tr></table>"""
        val blocked = clean(html)
        assertTrue(blocked.remoteImages)
        assertFalse(blocked.html.contains("t.example"))
        val shown = clean(html, images = true)
        assertFalse(shown.html.contains("t.example"))
        assertEquals(4, Regex("/proxy/").findAll(shown.html).count())
    }

    @Test
    fun `the message's own images, layout and links are kept`() {
        val out = clean(
            """<table width="600" bgcolor="#ffffff" cellpadding="4"><tr><td align="center" style="color:#333;font-size:14px">
               <img src="cid:logo@x" alt="Logo" width="120"><a href="https://example.com/a?b=1&c=2">Read</a>
               <a href="mailto:hi@example.com">Mail</a></td></tr></table><img src="data:image/png;base64,iVBORw0KGgo=">""",
        ).html
        assertTrue(out, out.contains("src=\"/api/parts/x/logo@x\""))
        assertTrue(out.contains("width=\"600\""))
        assertTrue(out.contains("bgcolor=\"#ffffff\""))
        assertTrue(out.contains("color:#333"))
        assertTrue(out.contains("href=\"https://example.com/a?b=1&amp;c=2\""))
        assertTrue(out.contains("rel=\"noopener noreferrer\""))
        assertTrue(out.contains("mailto:hi@example.com"))
        assertTrue(out.contains("data:image/png;base64"))
        assertFalse(clean("<img src=\"data:image/svg+xml;base64,PHN2Zz4=\">").html.contains("data:image/svg"))
    }

    @Test
    fun `HTML becomes readable text`() {
        val text = BodyReader.htmlToText(
            """<h1>Hello</h1><p>First <b>line</b><br>second line</p><ul><li>One</li><li>Two</li></ul>
               <blockquote><p>Quoted</p></blockquote><p><a href="https://example.com">Example</a></p>""",
        )
        assertEquals("Hello\n\nFirst line\nsecond line\n\n• One\n• Two\n> Quoted\nExample <https://example.com>", text)
    }

    @Test
    fun `previews decode cut-off text in any encoding`() {
        val qp = BodyReader.PreviewPart("1", "quoted-printable", "utf-8", html = false)
        assertEquals("Café au lait", BodyReader.previewOf("Caf=C3=A9 au l=\r\nait =C3".toByteArray(), qp))
        val b64 = BodyReader.PreviewPart("1", "base64", "utf-8", html = true)
        val encoded = java.util.Base64.getMimeEncoder().encodeToString("<p>Hello <b>there</b> friend</p>".toByteArray())
        assertEquals("Hello there friend", BodyReader.previewOf(encoded.toByteArray(), b64))
        // Cut short mid-way, it's the start of it.
        assertTrue(BodyReader.previewOf(encoded.dropLast(5).toByteArray(), b64).startsWith("Hello there"))
        assertEquals("Hi there", BodyReader.preview("> quoted\nHi\n\n  there\n--\n"))
    }
}
