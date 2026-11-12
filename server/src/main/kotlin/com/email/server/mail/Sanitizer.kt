package com.email.server.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Makes other people's HTML safe to show. Only an allow-list of tags and attributes survives (no
 * scripts, forms, frames, objects, event handlers or `javascript:` links), CSS loses what could
 * run code or load things, and nothing is fetched from the web unless the person asks: remote
 * images (which tell senders when mail is opened, and from where) are left out until then, and
 * then come through the server's image proxy. Images inside the message (`cid:`) point at the
 * message's own parts.
 *
 * The apps show the result in a frame without scripts as well, so a gap here still can't run code.
 */
object Sanitizer {
    data class Result(val html: String, val remoteImages: Boolean)

    private val safelist: Safelist = Safelist()
        .addTags(
            "a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote", "br", "caption", "center", "cite",
            "code", "col", "colgroup", "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "figcaption", "figure", "font",
            "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "i", "img", "ins", "kbd", "li", "main", "mark", "nav",
            "ol", "p", "pre", "q", "rp", "rt", "ruby", "s", "samp", "section", "small", "span", "strike", "strong", "sub",
            "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time", "tr", "tt", "u", "ul", "var", "wbr",
        )
        .addAttributes(
            ":all", "style", "class", "dir", "lang", "title", "align", "valign", "width", "height", "bgcolor", "color", "border",
            "cellpadding", "cellspacing", "face", "size", "colspan", "rowspan", "nowrap", "role", "start", "type", "span",
        )
        .addAttributes("a", "href", "name")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addAttributes("img", "src", "alt", "hspace", "vspace")
        .addProtocols("img", "src", "http", "https", "cid", "data")
        .addAttributes("table", "background")
        .addAttributes("td", "background")
        .addAttributes("th", "background")
        .addProtocols("table", "background", "http", "https", "cid")
        .addProtocols("td", "background", "http", "https", "cid")
        .addProtocols("th", "background", "http", "https", "cid")
        .addAttributes("blockquote", "cite", "type")

    /** How messages look when they don't say: on white, like paper, as most mail is written for. */
    private const val BASE_CSS = """
html { background: #ffffff; }
body { margin: 0; padding: 18px 20px; font-family: Lexend, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; font-size: 15px;
  line-height: 1.5; color: #111827; background: #ffffff; overflow-wrap: anywhere; word-wrap: break-word; }
img { max-width: 100%; height: auto; }
pre { white-space: pre-wrap; }
a { color: #5b3fd1; }
blockquote { margin: 8px 0; padding: 0 0 0 12px; border-left: 3px solid #d0c3ef; color: #4b5565; }
"""

    /**
     * [cidUrl] gives the address of the part with a Content-ID (null: there's no such part);
     * [proxy] gives the address to load a remote image from, or is null to leave them out.
     */
    fun clean(html: String, cidUrl: (String) -> String?, proxy: ((String) -> String)?): Result {
        val dirty = Jsoup.parse(html)
        var remote = false

        fun rewrite(raw: String): String? {
            val url = raw.trim().replace(Regex("[\\u0000-\\u001f]"), "")
            return when {
                url.startsWith("cid:", ignoreCase = true) -> cidUrl(url.substring(4).trim().removePrefix("<").removeSuffix(">"))
                url.startsWith("data:image/", ignoreCase = true) && !url.startsWith("data:image/svg", ignoreCase = true) -> url
                url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> {
                    remote = true
                    proxy?.invoke(url)
                }
                url.startsWith("//") -> {
                    remote = true
                    proxy?.invoke("https:$url")
                }
                else -> null
            }
        }

        dirty.select("[src^=//], [background^=//]").forEach { el ->
            listOf("src", "background").filter { el.attr(it).startsWith("//") }.forEach { el.attr(it, "https:" + el.attr(it)) }
        }
        // Style sheets, kept (much mail is laid out with them), less what's dangerous.
        val styles = dirty.select("style").map { cleanCss(it.data(), ::rewrite) }
        val clean: Document = Cleaner(safelist).clean(dirty)

        clean.select("img").forEach { img ->
            val src = rewrite(img.attr("src"))
            if (src != null) img.attr("src", src) else img.removeAttr("src")
        }
        clean.select("[background]").forEach { el ->
            val src = rewrite(el.attr("background"))
            if (src != null) el.attr("background", src) else el.removeAttr("background")
        }
        clean.select("[style]").forEach { el -> el.attr("style", cleanCss(el.attr("style"), ::rewrite)) }
        clean.select("a[href]").forEach { a ->
            a.attr("target", "_blank")
            a.attr("rel", "noopener noreferrer")
        }
        clean.outputSettings().prettyPrint(false)

        val head = buildString {
            append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<base target=\"_blank\"><style>").append(BASE_CSS).append("</style>")
            styles.filter { it.isNotBlank() }.forEach { append("<style>").append(it).append("</style>") }
        }
        return Result("<!DOCTYPE html><html><head>$head</head><body>${clean.body().html()}</body></html>", remote)
    }

    private val COMMENTS = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val IMPORT = Regex("@import[^;]*;?", RegexOption.IGNORE_CASE)
    private val FONT_FACE = Regex("@font-face\\s*\\{[^}]*\\}", RegexOption.IGNORE_CASE)
    private val EXPRESSION = Regex("expression\\s*\\(", RegexOption.IGNORE_CASE)
    private val BINDING = Regex("(-moz-binding|behavior|-webkit-user-modify)\\s*:", RegexOption.IGNORE_CASE)
    private val URL = Regex("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)", RegexOption.IGNORE_CASE)
    private val ESCAPE = Regex("\\\\[0-9a-fA-F]{1,6}\\s?")

    /** CSS less what could run code or load things; [rewrite] decides what each url() becomes. */
    fun cleanCss(css: String, rewrite: (String) -> String?): String {
        // CSS escapes can spell out "expression" or "url"; mail has no need of them, so they go.
        var out = css.replace(ESCAPE, "").replace(COMMENTS, "")
        out = out.replace(IMPORT, "").replace(FONT_FACE, "")
        out = out.replace(EXPRESSION, "x(").replace(BINDING, "x-blocked:")
        out = URL.replace(out) { match -> rewrite(match.groupValues[2])?.let { "url('${it.replace("'", "%27")}')" } ?: "none" }
        // Nothing gets out of the <style> element ("</style>" needs a "<").
        return out.replace("<", " ")
    }

    /** HTML for quoting a message in a reply: its content without images (they'd point at this server). */
    fun forQuote(cleanHtml: String): String {
        val doc = Jsoup.parse(cleanHtml)
        doc.select("img").forEach { img ->
            val alt = img.attr("alt")
            if (alt.isNotBlank()) img.replaceWith(org.jsoup.nodes.TextNode("[$alt]")) else img.remove()
        }
        doc.select("[background]").removeAttr("background")
        doc.select("style").remove()
        return doc.body().html()
    }

    /** Escapes text for HTML, keeping its lines, with web addresses made into links. */
    fun textToHtml(text: String): String {
        val escaped = Element("div").text(text).html()
        val linked = Regex("(https?://[^\\s<>\"']+[^\\s<>\"'.,;:!?)\\]])").replace(escaped) { m ->
            "<a href=\"${m.value}\" target=\"_blank\" rel=\"noopener noreferrer\">${m.value}</a>"
        }
        return "<div style=\"white-space: pre-wrap\">$linked</div>"
    }
}
