package com.email.server.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/** A part of a message that isn't its text: an attachment, or an image the HTML shows. */
data class PartInfo(
    /** The part's IMAP section, e.g. "2" or "1.2", which finds it again. */
    val path: String,
    val contentType: String,
    val filename: String,
    val size: Int,
    val inline: Boolean,
    /** Its Content-ID without the brackets, which the HTML refers to as cid:… */
    val contentId: String?,
)

/** A message's text, HTML and other parts. */
data class Bodies(val text: String?, val html: String?, val parts: List<PartInfo>)

/** Reading what's in a message: its text and HTML, its attachments, and short previews. */
object BodyReader {
    /** Signatures of signed mail: nothing a person wants to open. */
    private val SIGNATURES = setOf("application/pgp-signature", "application/pkcs7-signature", "application/x-pkcs7-signature")

    fun read(root: Part): Bodies {
        val texts = mutableListOf<String>()
        val htmls = mutableListOf<String>()
        val parts = mutableListOf<PartInfo>()
        walk(root, "", texts, htmls, parts)
        return Bodies(
            text = texts.joinToString("\n\n").ifBlank { null },
            html = htmls.joinToString("<hr>").ifBlank { null },
            parts = parts,
        )
    }

    private fun walk(part: Part, path: String, texts: MutableList<String>, htmls: MutableList<String>, parts: MutableList<PartInfo>) {
        when {
            part.isMimeType("multipart/alternative") -> {
                // The same message several ways: the plain text, and the richest HTML.
                val multipart = part.content as? Multipart ?: return
                var text: String? = null
                var html: String? = null
                for (i in 0 until multipart.count) {
                    val childTexts = mutableListOf<String>()
                    val childHtmls = mutableListOf<String>()
                    walk(multipart.getBodyPart(i), child(path, i), childTexts, childHtmls, parts)
                    if (text == null && childTexts.isNotEmpty()) text = childTexts.joinToString("\n\n")
                    if (childHtmls.isNotEmpty()) html = childHtmls.joinToString("<hr>")
                }
                text?.let(texts::add)
                html?.let(htmls::add)
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                for (i in 0 until multipart.count) walk(multipart.getBodyPart(i), child(path, i), texts, htmls, parts)
            }
            isAttachment(part) -> describe(part, section(path))?.let(parts::add)
            part.isMimeType("text/plain") -> texts += textOf(part)
            part.isMimeType("text/html") -> htmls += textOf(part)
            else -> describe(part, section(path))?.let(parts::add)
        }
    }

    private fun child(path: String, index: Int) = if (path.isEmpty()) "${index + 1}" else "$path.${index + 1}"

    /** A single-part message's body is its section 1. */
    private fun section(path: String) = path.ifEmpty { "1" }

    private fun isAttachment(part: Part): Boolean {
        if (part.isMimeType("message/rfc822")) return true
        val disposition = runCatching { part.disposition }.getOrNull()
        if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) return true
        val filename = runCatching { part.fileName }.getOrNull()
        val isText = part.isMimeType("text/plain") || part.isMimeType("text/html")
        return !isText || (filename != null && !Part.INLINE.equals(disposition, ignoreCase = true))
    }

    private fun describe(part: Part, path: String): PartInfo? {
        val type = baseType(runCatching { part.contentType }.getOrNull())
        if (type in SIGNATURES) return null
        val contentId = (part as? MimePart)?.let { runCatching { it.contentID }.getOrNull() }?.trim()?.removePrefix("<")?.removeSuffix(">")
        val disposition = runCatching { part.disposition }.getOrNull()
        val filename = runCatching { part.fileName }.getOrNull()?.let { decodeWord(it) }?.takeIf { it.isNotBlank() }
            ?: defaultName(type, contentId)
        val encoded = runCatching { part.size }.getOrDefault(-1)
        val base64 = (part as? MimePart)?.let { runCatching { it.encoding }.getOrNull() }.equals("base64", ignoreCase = true)
        return PartInfo(
            path = path,
            contentType = type,
            filename = cleanFilename(filename),
            size = if (encoded < 0) 0 else if (base64) encoded / 4 * 3 else encoded,
            inline = contentId != null && !Part.ATTACHMENT.equals(disposition, ignoreCase = true) && type.startsWith("image/"),
            contentId = contentId,
        )
    }

    private fun defaultName(type: String, contentId: String?): String = when {
        type == "message/rfc822" -> "Forwarded message.eml"
        type == "text/calendar" -> "invite.ics"
        type.startsWith("image/") -> "${contentId?.substringBefore('@')?.take(40) ?: "image"}.${type.substringAfter('/').substringBefore('+')}"
        else -> "attachment"
    }

    /** Filenames from other people's mail: no paths, no control characters. */
    fun cleanFilename(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim().take(200).ifBlank { "attachment" }

    private fun decodeWord(text: String) = runCatching { MimeUtility.decodeText(text) }.getOrDefault(text)

    fun baseType(contentType: String?): String = contentType?.substringBefore(';')?.trim()?.lowercase()?.ifBlank { null } ?: "application/octet-stream"

    /** The part at [path] (as [PartInfo.path] gives it). */
    fun find(root: Part, path: String): Part? {
        if (!root.isMimeType("multipart/*")) return root.takeIf { path == "1" }
        var current: Part = root
        for (piece in path.split('.')) {
            val index = piece.toIntOrNull()?.minus(1) ?: return null
            val multipart = current.content as? Multipart ?: return null
            if (index !in 0 until multipart.count) return null
            current = multipart.getBodyPart(index)
        }
        return current
    }

    /** A text part's text, even when it names a character set Java doesn't know. */
    fun textOf(part: Part): String = try {
        when (val content = part.content) {
            is String -> content
            else -> decode(part.inputStream.readBytes(), charsetOf(part.contentType))
        }
    } catch (e: java.io.UnsupportedEncodingException) {
        decode(part.inputStream.readBytes(), null)
    }

    private fun charsetOf(contentType: String?): String? =
        contentType?.let { Regex("charset=\"?([^\";\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }

    fun decode(bytes: ByteArray, charset: String?): String {
        val chosen = charset?.let { runCatching { Charset.forName(MimeUtility.javaCharset(it)) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, chosen)
    }

    // ---- Previews, from the start of a message's text without fetching the whole message ----

    /** Which part to read the preview from, and how it's encoded. */
    data class PreviewPart(val section: String, val encoding: String?, val charset: String?, val html: Boolean)

    /** The part of a message to preview (its plain text, else its HTML), from its BODYSTRUCTURE. */
    fun previewPart(structure: BODYSTRUCTURE): PreviewPart? {
        val candidates = mutableListOf<PreviewPart>()
        fun visit(body: BODYSTRUCTURE, section: String) {
            when {
                body.isMulti -> body.bodies.orEmpty().forEachIndexed { i, child -> visit(child, if (section.isEmpty()) "${i + 1}" else "$section.${i + 1}") }
                body.isNested -> Unit
                body.type.equals("text", true) && !body.disposition.equals("attachment", true) &&
                    (body.subtype.equals("plain", true) || body.subtype.equals("html", true)) ->
                    candidates += PreviewPart(section.ifEmpty { "1" }, body.encoding, body.cParams?.get("charset"), body.subtype.equals("html", true))
            }
        }
        visit(structure, "")
        return candidates.firstOrNull { !it.html } ?: candidates.firstOrNull()
    }

    /** Whether a message has files attached (not counting images its HTML shows). */
    fun hasAttachments(structure: BODYSTRUCTURE): Boolean {
        fun visit(body: BODYSTRUCTURE, parentSubtype: String?): Boolean = when {
            body.isMulti -> body.bodies.orEmpty().any { visit(it, body.subtype?.lowercase()) }
            body.disposition.equals("attachment", true) -> true
            body.isNested -> true
            body.type.equals("text", true) -> body.dParams?.get("filename") != null && !body.disposition.equals("inline", true)
            "${body.type}/${body.subtype}".lowercase() in SIGNATURES -> false
            parentSubtype == "related" -> false
            else -> true
        }
        return visit(structure, null)
    }

    /** Decodes the first bytes of a part (possibly cut mid-way) into a preview. */
    fun previewOf(bytes: ByteArray, part: PreviewPart): String {
        val decoded = when (part.encoding?.lowercase()) {
            "base64" -> {
                val clean = String(bytes, Charsets.US_ASCII).filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
                runCatching { Base64.getMimeDecoder().decode(clean.take(clean.length / 4 * 4)) }.getOrDefault(ByteArray(0))
            }
            "quoted-printable" -> quotedPrintable(bytes)
            else -> bytes
        }
        val text = decode(decoded, part.charset).trimEnd('\uFFFD')
        return preview(if (part.html) htmlToText(text) else text)
    }

    private fun quotedPrintable(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '='.code.toByte()) {
                if (i + 1 < bytes.size && (bytes[i + 1] == '\r'.code.toByte() || bytes[i + 1] == '\n'.code.toByte())) {
                    i += if (i + 2 < bytes.size && bytes[i + 1] == '\r'.code.toByte() && bytes[i + 2] == '\n'.code.toByte()) 3 else 2
                    continue
                }
                if (i + 2 < bytes.size) {
                    val hex = String(byteArrayOf(bytes[i + 1], bytes[i + 2]), Charsets.US_ASCII).toIntOrNull(16)
                    if (hex != null) {
                        out.write(hex)
                        i += 3
                        continue
                    }
                }
                break // cut off mid-escape
            }
            out.write(b.toInt())
            i++
        }
        return out.toByteArray()
    }

    /** A message's first words: quoted replies left out, spaces tidied, a sentence or two long. */
    fun preview(text: String, length: Int = 200): String = text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith(">") }
        .filterNot { it == "--" || it == "-- " }
        .joinToString(" ")
        .replace(Regex("[\\s\\u00a0\\u200b-\\u200d\\u034f\\ufeff]+"), " ")
        .trim()
        .take(length)

    // ---- HTML to plain text ----

    private val BLOCKS = setOf(
        "p", "div", "section", "article", "header", "footer", "aside", "nav", "main", "table", "tr", "ul", "ol", "dl", "dt", "dd",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre", "address", "center", "form", "figure", "figcaption", "hr",
    )

    /** Readable plain text from HTML: paragraphs on their own lines, lists with bullets, quotes with "> ". */
    fun htmlToText(html: String): String {
        val body = Jsoup.parse(html).body()
        body.select("script, style, title, head, noscript, template").remove()
        val out = StringBuilder()
        render(body, out)
        return out.toString().lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun render(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> {
                val text = if (generateSequence(node.parent()) { it.parent() }.any { (it as? Element)?.normalName() == "pre" }) node.wholeText else node.text()
                if (text.isNotEmpty()) {
                    // Collapsed spaces, without doubling them across tags.
                    if (text.startsWith(" ") && (out.isEmpty() || out.last() == ' ' || out.last() == '\n')) out.append(text.trimStart()) else out.append(text)
                }
            }
            is Element -> when (val name = node.normalName()) {
                "br" -> out.append('\n')
                "hr" -> { newline(out); out.append("———\n") }
                "img" -> node.attr("alt").takeIf { it.isNotBlank() }?.let { out.append(it) }
                "blockquote" -> {
                    newline(out)
                    val inner = StringBuilder()
                    node.childNodes().forEach { render(it, inner) }
                    inner.toString().trim('\n').lines().forEach { out.append("> ").append(it).append('\n') }
                }
                "li" -> {
                    newline(out)
                    out.append("• ")
                    node.childNodes().forEach { render(it, out) }
                    newline(out)
                }
                "a" -> {
                    val before = out.length
                    node.childNodes().forEach { render(it, out) }
                    val label = out.substring(before).trim()
                    val href = node.attr("href")
                    if (href.startsWith("http") && href != label && !label.contains(href)) out.append(" <").append(href).append('>')
                }
                "td", "th" -> {
                    node.childNodes().forEach { render(it, out) }
                    if (out.isNotEmpty() && out.last() != '\n' && out.last() != ' ') out.append(' ')
                }
                else -> {
                    val block = name in BLOCKS
                    if (block) newline(out)
                    node.childNodes().forEach { render(it, out) }
                    if (block) newline(out)
                    if (name == "p" || name.matches(Regex("h[1-6]"))) out.append('\n')
                }
            }
            else -> Unit
        }
    }

    private fun newline(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }
}
