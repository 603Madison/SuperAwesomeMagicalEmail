package com.email.server.mail

import com.email.server.util.SecretBox
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Signed links the server hands out: to a message's parts (attachments, and the images its HTML
 * shows) and to the image proxy. They work without a sign-in, so the apps' message views and
 * the browser's download manager can open them, but only the server can make them, and part
 * links stop working after a week.
 */
class Links(private val box: SecretBox, private val clock: () -> Long = System::currentTimeMillis) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Where to download part [part] of [ref] (with its [filename] at the end, for browsers). */
    fun part(ref: MessageRef, part: String, filename: String): String =
        "/api/parts/${token(PART, ref.id, part)}/${URLEncoder.encode(filename, "UTF-8").replace("+", "%20")}"

    /** Where to download the whole message, as an .eml file. */
    fun raw(ref: MessageRef, subject: String): String {
        val name = subject.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifBlank { "message" }
        return "/api/parts/${token(RAW, ref.id, "")}/${URLEncoder.encode("$name.eml", "UTF-8").replace("+", "%20")}"
    }

    /** What a part link points at: the message, and the part ("" for the whole message). */
    data class Target(val raw: Boolean, val ref: MessageRef, val part: String)

    fun open(token: String): Target? {
        val dot = token.lastIndexOf('.')
        if (dot <= 0) return null
        val payload = token.substring(0, dot)
        if (!box.verifySignature("link|$payload", token.substring(dot + 1))) return null
        val fields = runCatching { String(decoder.decode(payload)) }.getOrNull()?.split('|') ?: return null
        if (fields.size != 4) return null
        val (kind, id, part, expiry) = fields
        if ((expiry.toLongOrNull() ?: 0) < clock()) return null
        val ref = MessageRef.decode(id) ?: return null
        return Target(kind == RAW, ref, part)
    }

    private fun token(kind: String, id: String, part: String): String {
        val payload = encoder.encodeToString("$kind|$id|$part|${clock() + LIFETIME_MS}".toByteArray())
        return "$payload.${box.sign("link|$payload")}"
    }

    /** Where to load a remote image from, through the server. */
    fun proxy(url: String): String = "/api/proxy?u=${encoder.encodeToString(url.toByteArray())}&s=${box.sign("image|$url")}"

    /** The remote address a proxy link stands for, if it's one the server made. */
    fun proxied(u: String, s: String): String? {
        val url = runCatching { String(decoder.decode(u)) }.getOrNull() ?: return null
        return url.takeIf { box.verifySignature("image|$url", s) && (url.startsWith("https://") || url.startsWith("http://")) }
    }

    companion object {
        private const val PART = "p"
        private const val RAW = "r"
        private const val LIFETIME_MS = 7L * 24 * 3600 * 1000

        /**
         * The client the image proxy fetches with: never to private addresses (a message mustn't
         * be able to make the server reach into its own network), small images only, quickly.
         */
        fun proxyClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        val found = Dns.SYSTEM.lookup(hostname).filterNot(MailConnections::isPrivate)
                        if (found.isEmpty()) throw UnknownHostException("$hostname isn't a public address")
                        return found
                    }
                },
            )
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    }
}
