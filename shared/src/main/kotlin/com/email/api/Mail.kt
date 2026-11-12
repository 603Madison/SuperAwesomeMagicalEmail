package com.email.api

import kotlinx.serialization.Serializable

/** How a connection to a mail server is secured. */
@Serializable
enum class Security { SSL, STARTTLS, NONE }

@Serializable
data class Endpoint(val host: String, val port: Int, val security: Security = Security.SSL)

/** Mail services with known settings, so adding an account is an address and a password. */
@Serializable
enum class Provider { PURELYMAIL, GMAIL, ICLOUD, FASTMAIL, YAHOO, OTHER }

@Serializable
data class ProviderPreset(
    val provider: Provider,
    val label: String,
    val description: String,
    val imap: Endpoint,
    val smtp: Endpoint,
    /** Whether the server should put a copy of sent mail in Sent (some services do it themselves). */
    val saveSent: Boolean,
    /** What to type as the password, e.g. an app password. */
    val passwordHelp: String = "",
)

@Serializable
data class Providers(
    val presets: List<ProviderPreset>,
    /** Whether this person may add their own accounts (admins always can). */
    val canAddPersonal: Boolean,
)

/** A mail account as a person sees it: never with its password. */
@Serializable
data class MailAccount(
    val id: String,
    /** The address mail is sent from. */
    val email: String,
    /** The name mail is sent with ("Maddie Norton"). */
    val name: String,
    /** How it's shown in the list of accounts. */
    val label: String,
    val provider: Provider,
    val username: String,
    val imap: Endpoint,
    val smtp: Endpoint,
    val saveSent: Boolean,
    /** Set up by an admin: only admins change its settings and password, or remove it. */
    val managed: Boolean,
    /** Whether the person asking may change its settings. */
    val editable: Boolean,
    /** This person's signature for it. */
    val signature: String = "",
    /** The last time the server couldn't use it, and why (cleared when it works again). */
    val problem: String? = null,
)

/** Who an admin's mailbox is attached to. */
@Serializable
data class Member(val userId: String, val username: String, val displayName: String, val waitingForSignIn: Boolean = false)

/** A mailbox as admins see it. */
@Serializable
data class Mailbox(val account: MailAccount, val members: List<Member>, val createdAt: Long)

/** What's entered to add or change an account. Null fields keep what's saved (a null password keeps the saved one). */
@Serializable
data class AccountInput(
    val provider: Provider = Provider.OTHER,
    val email: String = "",
    val name: String = "",
    val label: String = "",
    /** Blank: the email address. */
    val username: String = "",
    val password: String? = null,
    val imap: Endpoint? = null,
    val smtp: Endpoint? = null,
    val saveSent: Boolean? = null,
    /** Admins' mailboxes: who reads it. */
    val members: List<String>? = null,
    /** Save it even if signing in to it fails right now. */
    val skipCheck: Boolean = false,
)

@Serializable
data class ConnectionCheck(val ok: Boolean, val imap: String, val smtp: String)

@Serializable
data class SignatureChange(val signature: String)

@Serializable
data class MembersChange(val userIds: List<String>)

@Serializable
data class OrderChange(val ids: List<String>)

/** What a folder is for, from the server's special-use flags or, failing those, its name. */
@Serializable
enum class FolderRole { INBOX, DRAFTS, SENT, ARCHIVE, JUNK, TRASH, ALL, FLAGGED, OTHER }

@Serializable
data class Folder(
    /** The folder's full name on the server, which identifies it. */
    val path: String,
    /** The last part of its name, for showing. */
    val name: String,
    val role: FolderRole,
    val unread: Int,
    val total: Int,
    /** How deep it's nested (0 at the top). */
    val depth: Int = 0,
    /** Whether it can hold messages (some are only parents of others). */
    val selectable: Boolean = true,
)

@Serializable
data class Address(val email: String, val name: String? = null) {
    /** "Name" if there is one, else the address. */
    val shown: String get() = name?.takeIf { it.isNotBlank() } ?: email
}

/** A message in a list. [id] identifies it everywhere (the account, folder and message). */
@Serializable
data class MessageSummary(
    val id: String,
    val accountId: String,
    val folder: String,
    val from: Address? = null,
    val to: List<Address> = emptyList(),
    val subject: String = "",
    /** The start of the text, for the list. */
    val preview: String = "",
    /** When it was sent (or, failing that, received), in ms. */
    val date: Long = 0,
    val unread: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val draft: Boolean = false,
    val hasAttachments: Boolean = false,
    val size: Int = 0,
)

@Serializable
data class MessagePage(
    val messages: List<MessageSummary>,
    /** For the next (older) page; null at the end. */
    val next: String? = null,
    /** How many messages there are, when it's known. */
    val total: Int? = null,
    /** Accounts that couldn't be read, with why (for "all inboxes"). */
    val problems: Map<String, String> = emptyMap(),
)

@Serializable
data class Attachment(
    /** The part of the message it is, e.g. "2" or "1.2". */
    val part: String,
    val filename: String,
    val contentType: String,
    val size: Int,
    /** Shown inside the message (an image it refers to) rather than attached to it. */
    val inline: Boolean = false,
    /** Where to download it (signed, so it works without a sign-in for a while). */
    val url: String,
)

@Serializable
data class Message(
    val summary: MessageSummary,
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val replyTo: List<Address> = emptyList(),
    /** The HTML body, made safe to show: no scripts, and remote images only if [imagesShown]. */
    val html: String? = null,
    /** The plain-text body (from the HTML when there's no text part). */
    val text: String = "",
    /** The HTML refers to images on the web, not shown unless asked for (they can tell the sender it was read). */
    val remoteImages: Boolean = false,
    val imagesShown: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    /** Where to download the whole message (.eml). */
    val rawUrl: String = "",
    /** The List-Unsubscribe address, if the sender gave one. */
    val unsubscribe: String? = null,
)

@Serializable
enum class Action { READ, UNREAD, FLAG, UNFLAG, ARCHIVE, TRASH, DELETE, JUNK, NOT_JUNK, MOVE }

@Serializable
data class MessageActions(val ids: List<String>, val action: Action, /** For [Action.MOVE]. */ val folder: String? = null)

@Serializable
data class ActionResult(val done: Int, val failed: Map<String, String> = emptyMap())

/** An attachment of a message already on the server (forwarding it, or keeping a draft's). */
@Serializable
data class AttachmentRef(val messageId: String, val part: String, val filename: String = "", val size: Int = 0)

/** A message being written: to send, or to keep as a draft. */
@Serializable
data class Draft(
    val accountId: String,
    val to: List<Address> = emptyList(),
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val subject: String = "",
    /** The text; with [html], the plain version of it (made from the HTML when blank). */
    val text: String = "",
    val html: String? = null,
    /** Files uploaded to attach (see [Upload]). */
    val uploads: List<String> = emptyList(),
    /** Attachments of messages on the server to include. */
    val attachments: List<AttachmentRef> = emptyList(),
    /** The message this answers (it's marked as answered, and the reply joins its thread). */
    val inReplyTo: String? = null,
    /** The message this forwards (marked as forwarded). */
    val forwardOf: String? = null,
    /** The draft this replaces, removed once this is sent or saved. */
    val draftId: String? = null,
)

@Serializable
enum class ComposeMode { REPLY, REPLY_ALL, FORWARD, DRAFT }

@Serializable
data class Upload(val id: String, val filename: String, val contentType: String, val size: Long)

@Serializable
data class SendResult(val ok: Boolean, val savedToSent: Boolean, val note: String? = null)

@Serializable
data class DraftSaved(val id: String?)

@Serializable
data class Contact(val email: String, val name: String? = null)

/** New mail since the cursor asked with: the next cursor, what's arrived, and each inbox's unread count. */
@Serializable
data class NewMail(
    val cursor: String,
    val messages: List<MessageSummary> = emptyList(),
    val unread: Map<String, Int> = emptyMap(),
)
