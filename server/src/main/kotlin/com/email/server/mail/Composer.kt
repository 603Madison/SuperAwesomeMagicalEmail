package com.email.server.mail

import com.email.api.Address
import com.email.api.AttachmentRef
import com.email.api.ComposeMode
import com.email.api.Draft
import com.email.api.DraftSaved
import com.email.api.FolderRole
import com.email.api.SendResult
import com.email.api.User
import com.email.server.ApiError
import com.email.server.util.Random
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Message.RecipientType
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.slf4j.LoggerFactory
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** A message being sent or saved, with its own Message-ID (on the sender's domain). */
private class OutgoingMessage(private val id: String) : MimeMessage(MailConnections.buildSession) {
    override fun updateMessageID() = setHeader("Message-ID", id)
}

/**
 * Writing mail: what a reply or forward starts with, sending (and keeping a copy in Sent), and
 * keeping drafts in the account's Drafts folder.
 */
class Composer(
    private val accounts: AccountStore,
    private val connections: MailConnections,
    private val mail: MailService,
    private val uploads: UploadStore,
    private val contacts: ContactStore,
    private val maxBytes: Long,
    private val version: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A reply, reply to all, forward, or a draft opened again, ready to edit. Dates are written in [zone]. */
    suspend fun seed(user: User, id: String, mode: ComposeMode, zone: ZoneId): Draft {
        val original = mail.original(user, id)
        val mine = accounts.forUser(user.id).map { it.account.email.lowercase() }.toSet() + original.account.email.lowercase()
        val sender = original.from.firstOrNull()
        val when_ = if (original.date > 0) DATE.withZone(zone).format(Instant.ofEpochMilli(original.date)) else null
        val attribution = listOfNotNull(when_?.let { "On $it" }, "${sender?.let(::shown) ?: "someone"} wrote:").joinToString(", ")
        return when (mode) {
            ComposeMode.REPLY, ComposeMode.REPLY_ALL -> {
                val answerTo = original.replyTo.ifEmpty { original.from }
                // Answering mail you sent yourself goes to the people you sent it to.
                val to = if (answerTo.all { it.email.lowercase() in mine }) original.to else answerTo
                val cc = if (mode == ComposeMode.REPLY_ALL) {
                    (original.to + original.cc).filter { a -> a.email.lowercase() !in mine && to.none { it.email.equals(a.email, true) } }
                        .distinctBy { it.email.lowercase() }
                } else {
                    emptyList()
                }
                Draft(
                    accountId = original.account.id,
                    to = to,
                    cc = cc,
                    subject = prefixed("Re:", original.subject, listOf("re:")),
                    text = "\n\n$attribution\n" + original.text.trimEnd().lines().joinToString("\n") { "> $it".trimEnd() },
                    html = "<p><br></p><p>${escape(attribution)}</p><blockquote type=\"cite\">${original.html ?: Sanitizer.textToHtml(original.text)}</blockquote>",
                    inReplyTo = id,
                )
            }
            ComposeMode.FORWARD -> {
                val header = listOfNotNull(
                    "---------- Forwarded message ----------",
                    sender?.let { "From: ${full(it)}" },
                    when_?.let { "Date: $it" },
                    "Subject: ${original.subject}",
                    original.to.takeIf { it.isNotEmpty() }?.let { "To: ${it.joinToString(", ", transform = ::full)}" },
                    original.cc.takeIf { it.isNotEmpty() }?.let { "Cc: ${it.joinToString(", ", transform = ::full)}" },
                )
                Draft(
                    accountId = original.account.id,
                    subject = prefixed("Fwd:", original.subject, listOf("fwd:", "fw:")),
                    text = "\n\n" + header.joinToString("\n") + "\n\n" + original.text,
                    html = "<p><br></p><p>" + header.joinToString("<br>") { escape(it) } + "</p>" + (original.html ?: Sanitizer.textToHtml(original.text)),
                    attachments = original.parts.filter { !it.inline || it.contentId == null }.map { AttachmentRef(id, it.path, it.filename, it.size) },
                    forwardOf = id,
                )
            }
            ComposeMode.DRAFT -> Draft(
                accountId = original.account.id,
                to = original.to,
                cc = original.cc,
                bcc = original.bcc,
                subject = original.subject,
                text = original.text,
                html = original.html,
                attachments = original.parts.filter { !it.inline }.map { AttachmentRef(id, it.path, it.filename, it.size) },
                inReplyTo = original.draftOf[MailService.DRAFT_REPLY_HEADER],
                forwardOf = original.draftOf[MailService.DRAFT_FORWARD_HEADER],
                draftId = id,
            )
        }
    }

    suspend fun send(user: User, draft: Draft): SendResult {
        val account = mail.membership(user, draft.accountId).account
        if (draft.to.isEmpty() && draft.cc.isEmpty() && draft.bcc.isEmpty()) throw ApiError.badRequest("Who's it to?")
        val message = build(user, account, draft, isDraft = false)
        connections.send(account, message)
        log.info("{} sent a message from {} to {} people", user.username, account.email, draft.to.size + draft.cc.size + draft.bcc.size)

        var savedToSent = false
        var note: String? = null
        if (account.saveSent) {
            try {
                mail.append(account, FolderRole.SENT, message, Flags(Flags.Flag.SEEN))
                savedToSent = true
            } catch (e: Exception) {
                log.warn("Sent, but couldn't put a copy in Sent for {}: {}", account.email, e.message)
                note = "Sent, but a copy couldn't be put in Sent (${e.message})"
            }
        }
        // Tidying up: none of this undoes the sending if it fails.
        draft.draftId?.let { runCatching { mail.deleteForGood(user, it) } }
        draft.inReplyTo?.let { runCatching { mail.mark(user, it, Flags(Flags.Flag.ANSWERED)) } }
        draft.forwardOf?.let { runCatching { mail.mark(user, it, Flags("\$Forwarded")) } }
        runCatching { contacts.used(user.id, draft.to + draft.cc + draft.bcc) }
        runCatching { uploads.delete(user.id, draft.uploads) }
        return SendResult(ok = true, savedToSent = savedToSent, note = note)
    }

    /** Keeps [draft] in the account's Drafts folder, in place of the draft it was before. */
    suspend fun saveDraft(user: User, draft: Draft): DraftSaved {
        val account = mail.membership(user, draft.accountId).account
        val message = build(user, account, draft, isDraft = true)
        val id = mail.append(account, FolderRole.DRAFTS, message, Flags(Flags.Flag.DRAFT).apply { add(Flags.Flag.SEEN) })
        draft.draftId?.takeIf { it != id }?.let { runCatching { mail.deleteForGood(user, it) } }
        return DraftSaved(id)
    }

    private suspend fun build(user: User, account: StoredAccount, draft: Draft, isDraft: Boolean): MimeMessage {
        val files = uploads.get(user.id, draft.uploads)
        val kept = draft.attachments.map { mail.partFor(user, it.messageId, it.part) }
        val answering = draft.inReplyTo?.let { runCatching { mail.original(user, it) }.getOrNull() }

        val domain = account.email.substringAfter('@').ifBlank { "localhost" }
        val message = OutgoingMessage("<${Random.token(18)}@$domain>")
        message.setFrom(InternetAddress(account.email, account.name.ifBlank { null }, "UTF-8"))
        message.setRecipients(RecipientType.TO, internet(draft.to))
        message.setRecipients(RecipientType.CC, internet(draft.cc))
        message.setRecipients(RecipientType.BCC, internet(draft.bcc))
        message.setSubject(draft.subject.replace(Regex("[\\r\\n]+"), " ").take(998), "UTF-8")
        message.sentDate = Date(clock())
        message.setHeader("User-Agent", "SuperAwesomeMagicalEmail/$version")
        answering?.messageId?.let { id ->
            message.setHeader("In-Reply-To", id)
            message.setHeader("References", (answering.references + id).takeLast(20).joinToString("\r\n "))
        }
        if (isDraft) {
            draft.inReplyTo?.let { message.setHeader(MailService.DRAFT_REPLY_HEADER, it) }
            draft.forwardOf?.let { message.setHeader(MailService.DRAFT_FORWARD_HEADER, it) }
        }

        val html = draft.html?.takeIf { it.isNotBlank() }?.let(::cleanOutgoing)
        val text = draft.text.ifBlank { html?.let(BodyReader::htmlToText).orEmpty() }
        fun alternative() = MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8", "plain") })
            addBodyPart(MimeBodyPart().apply { setText(wrapHtml(html!!), "UTF-8", "html") })
        }

        if (files.isEmpty() && kept.isEmpty()) {
            if (html != null) message.setContent(alternative()) else message.setText(text, "UTF-8", "plain")
        } else {
            val mixed = MimeMultipart("mixed")
            mixed.addBodyPart(MimeBodyPart().apply { if (html != null) setContent(alternative()) else setText(text, "UTF-8", "plain") })
            files.forEach { stored ->
                mixed.addBodyPart(attachment(stored.upload.filename, stored.upload.contentType, file = stored.file))
            }
            kept.forEach { part -> mixed.addBodyPart(attachment(part.filename, part.contentType, bytes = part.bytes)) }
            message.setContent(mixed)
        }
        message.saveChanges()

        val size = sizeOf(message)
        if (size > maxBytes) {
            throw ApiError.tooLarge("That's ${size / (1024 * 1024)} MB with its attachments; the most that can be sent is ${maxBytes / (1024 * 1024)} MB")
        }
        return message
    }

    private fun attachment(filename: String, contentType: String, file: File? = null, bytes: ByteArray? = null) = MimeBodyPart().apply {
        val type = contentType.ifBlank { "application/octet-stream" }
        dataHandler = DataHandler(ByteArrayDataSource(bytes ?: file!!.readBytes(), type))
        fileName = BodyReader.cleanFilename(filename)
        disposition = Part.ATTACHMENT
    }

    private fun internet(addresses: List<Address>): Array<InternetAddress> = addresses.map { address ->
        val email = address.email.trim()
        try {
            InternetAddress(email, true).also { it.setPersonal(address.name?.trim()?.ifBlank { null }, "UTF-8") }
        } catch (e: Exception) {
            throw ApiError.badRequest("\"$email\" isn't an email address")
        }
    }.toTypedArray()

    private fun sizeOf(message: MimeMessage): Long {
        var count = 0L
        message.writeTo(
            object : OutputStream() {
                override fun write(b: Int) {
                    count++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    count += len
                }
            },
        )
        return count
    }

    companion object {
        private val log = LoggerFactory.getLogger("same.send")
        private val DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm", Locale.ENGLISH)

        /** What's written, made tidy for sending: the same allow-list as for showing mail, images kept. */
        private val outgoing: Safelist = Safelist.relaxed()
            .addTags("font", "center", "s", "strike", "hr", "span", "div")
            .addAttributes(":all", "style", "dir", "align")
            .addAttributes("blockquote", "type")
            .addAttributes("font", "color", "face", "size")
            .addProtocols("img", "src", "http", "https", "data", "cid")

        fun cleanOutgoing(html: String): String =
            Cleaner(outgoing).clean(Jsoup.parseBodyFragment(html)).body().html()

        private fun wrapHtml(body: String) =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                "<body style=\"font-family: Lexend, -apple-system, 'Segoe UI', Roboto, Arial, sans-serif; font-size: 15px; line-height: 1.5;\">$body</body></html>"

        /** "Re: Lunch", without piling up "Re: Re: Re:". */
        fun prefixed(prefix: String, subject: String, already: List<String>): String {
            val trimmed = subject.trim()
            return if (already.any { trimmed.lowercase().startsWith(it) }) trimmed else "$prefix $trimmed".trimEnd()
        }

        private fun shown(a: Address) = a.name?.takeIf { it.isNotBlank() } ?: a.email
        private fun full(a: Address) = a.name?.takeIf { it.isNotBlank() }?.let { "$it <${a.email}>" } ?: a.email
        private fun escape(text: String) = org.jsoup.nodes.Entities.escape(text)
    }
}
