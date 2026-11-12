package com.email.server.mail

import java.util.Base64

/**
 * Where a message is: its account, folder, and IMAP UID. The UID validity is part of it because
 * a folder's UIDs only mean the same thing while it stays the same (a server that rebuilds a
 * folder gives it a new validity, and old ids then rightly find nothing).
 */
data class MessageRef(val accountId: String, val folder: String, val uidValidity: Long, val uid: Long) {
    /** The id clients use: opaque, URL-safe. */
    val id: String get() = encode(this)

    companion object {
        private const val SEP = '\u0000'
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun encode(ref: MessageRef): String =
            encoder.encodeToString("m1$SEP${ref.accountId}$SEP${ref.folder}$SEP${ref.uidValidity}$SEP${ref.uid}".toByteArray())

        /** The message [id] names, or null if it isn't one of ours. */
        fun decode(id: String): MessageRef? {
            if (id.length > 2_000) return null
            val parts = runCatching { String(decoder.decode(id)) }.getOrNull()?.split(SEP) ?: return null
            if (parts.size != 5 || parts[0] != "m1") return null
            return MessageRef(
                accountId = parts[1].takeIf { it.isNotEmpty() } ?: return null,
                folder = parts[2].takeIf { it.isNotEmpty() } ?: return null,
                uidValidity = parts[3].toLongOrNull() ?: return null,
                uid = parts[4].toLongOrNull()?.takeIf { it > 0 } ?: return null,
            )
        }
    }
}
