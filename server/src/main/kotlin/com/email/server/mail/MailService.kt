package com.email.server.mail

import com.email.api.Action
import com.email.api.ActionResult
import com.email.api.Address
import com.email.api.Attachment
import com.email.api.Folder
import com.email.api.FolderRole
import com.email.api.Message
import com.email.api.MessagePage
import com.email.api.MessageSummary
import com.email.api.NewMail
import com.email.api.User
import com.email.server.ApiError
import com.email.server.db.Database
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder as JFolder
import jakarta.mail.Message as JMessage
import jakarta.mail.MethodNotSupportedException
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.HeaderTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.protocol.BODY
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.MessageSet
import java.io.ByteArrayOutputStream
import java.util.Base64

/** A message's headers and content, for answering or forwarding it, or opening it as a draft. */
data class Original(
    val ref: MessageRef,
    val account: StoredAccount,
    val from: List<Address>,
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,
    val replyTo: List<Address>,
    val subject: String,
    val date: Long,
    val messageId: String?,
    val references: List<String>,
    val text: String,
    /** The HTML, made safe and without images, for quoting. */
    val html: String?,
    val parts: List<PartInfo>,
    /** Our own headers on drafts (what they answer or forward). */
    val draftOf: Map<String, String>,
)

/** A part of a message, read whole (an attachment being downloaded or forwarded). */
class PartData(val filename: String, val contentType: String, val bytes: ByteArray)

/**
 * Reading mail: folders, pages of messages (with previews), single messages made safe to show,
 * changing them (read, flagged, moved, deleted), and what's new since a client last looked.
 * Everything goes through [connections], one request at a time per account.
 */
class MailService(
    private val accounts: AccountStore,
    private val connections: MailConnections,
    private val db: Database,
    private val links: Links,
) {
    suspend fun membership(user: User, accountId: String): Membership =
        accounts.membership(user.id, accountId) ?: throw ApiError.notFound("No such account")

    suspend fun folders(user: User, accountId: String): List<Folder> {
        val account = membership(user, accountId).account
        return connections.imap(account) { store -> Folders.list(store) }
    }

    /**
     * A page of [folder]'s messages, newest first: from the top, or before [cursor] (from the
     * previous page). [accountId] "all" is every account's inbox together.
     */
    suspend fun messages(user: User, accountId: String, folder: String, cursor: String?, query: String?): MessagePage {
        if (accountId == ALL) return allInboxes(user, cursor, query)
        val account = membership(user, accountId).account
        val before = cursor?.removePrefix("u")?.toLongOrNull()
        val page = connections.imap(account) { store -> page(store, account, folder, before, query?.trim()?.ifBlank { null }, PAGE) }
        return MessagePage(page.messages, page.next?.let { "u$it" }, page.total)
    }

    private class Page(val messages: List<MessageSummary>, val next: Long?, val total: Int?)

    private fun page(store: IMAPStore, account: StoredAccount, path: String, before: Long?, query: String?, limit: Int): Page {
        val folder = store.getFolder(path) as IMAPFolder
        if (!folder.exists()) throw ApiError.notFound("There's no folder called $path")
        folder.open(JFolder.READ_ONLY)
        try {
            val total = folder.messageCount
            val (chosen, more) = when {
                query != null -> {
                    val found = folder.search(searchTerm(query))
                    folder.fetch(found, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
                    val eligible = found.filter { before == null || folder.getUID(it) < before }.sortedBy { folder.getUID(it) }
                    eligible.takeLast(limit) to (eligible.size > limit)
                }
                before == null -> {
                    if (total == 0) emptyList<JMessage>() to false
                    else folder.getMessages(maxOf(1, total - limit + 1), total).toList() to (total > limit)
                }
                else -> {
                    // The page before [before]: by where it is, when it's still there; else by UIDs.
                    val anchor = runCatching { folder.getMessageByUID(before) }.getOrNull()
                    if (anchor != null) {
                        val end = anchor.messageNumber - 1
                        if (end < 1) emptyList<JMessage>() to false else folder.getMessages(maxOf(1, end - limit + 1), end).toList() to (end > limit)
                    } else {
                        val older = if (before <= 1) emptyArray() else folder.getMessagesByUID(1, before - 1).filterNotNull().toTypedArray()
                        older.takeLast(limit) to (older.size > limit)
                    }
                }
            }
            val summaries = summarize(account, folder, chosen.toTypedArray())
            val next = if (more) summaries.minOfOrNull { MessageRef.decode(it.id)!!.uid } else null
            return Page(summaries, next, if (query == null) total else null)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** Every account's inbox together, newest first, each account's messages in its own order. */
    private suspend fun allInboxes(user: User, cursor: String?, query: String?): MessagePage {
        val members = accounts.forUser(user.id)
        val positions: Map<String, Long?> = cursor?.let(::decodeCursor) ?: members.associate { it.account.id to null }
        val results = coroutineScope {
            members.filter { it.account.id in positions }.map { member ->
                async {
                    member.account to runCatching {
                        connections.imap(member.account) { store -> page(store, member.account, "INBOX", positions[member.account.id], query?.trim()?.ifBlank { null }, PAGE) }
                    }
                }
            }.awaitAll()
        }
        val problems = results.mapNotNull { (account, result) -> result.exceptionOrNull()?.let { account.id to (it.message ?: "Couldn't read it") } }.toMap()
        // Merge, taking from whichever account's next message is newest, so each account's pages stay in order.
        val queues = results.mapNotNull { (account, result) -> result.getOrNull()?.let { account.id to ArrayDeque(it.messages) } }.toMap()
        val taken = mutableListOf<MessageSummary>()
        while (taken.size < PAGE) {
            val queue = queues.values.filter { it.isNotEmpty() }.maxByOrNull { it.first().date } ?: break
            taken += queue.removeFirst()
        }
        val next = mutableMapOf<String, Long?>()
        results.forEach { (account, result) ->
            val page = result.getOrNull() ?: return@forEach
            val left = queues[account.id].orEmpty()
            val lowestTaken = taken.filter { it.accountId == account.id }.minOfOrNull { MessageRef.decode(it.id)!!.uid }
            when {
                left.isNotEmpty() -> next[account.id] = lowestTaken ?: positions[account.id]
                page.next != null -> next[account.id] = page.next
            }
        }
        return MessagePage(taken, if (next.isEmpty()) null else encodeCursor(next), null, problems)
    }

    /** Summaries of [messages] (newest first), with previews worked out once and kept. */
    private fun summarize(account: StoredAccount, folder: IMAPFolder, messages: Array<JMessage>): List<MessageSummary> {
        if (messages.isEmpty()) return emptyList()
        folder.fetch(
            messages,
            FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(FetchProfile.Item.SIZE)
                add(UIDFolder.FetchProfileItem.UID)
            },
        )
        val validity = folder.uidValidity
        val uids = messages.associate { it.messageNumber to folder.getUID(it) }
        val cached = db.blockingTx {
            uids.values.chunked(400).flatMap { chunk ->
                query(
                    "SELECT uid, preview, attachments FROM previews WHERE account_id = ? AND folder = ? AND uid_validity = ? AND uid IN (${chunk.joinToString(",")})",
                    account.id, folder.fullName, validity,
                ) { it.long("uid") to (it.string("preview") to it.bool("attachments")) }
            }.toMap()
        }
        val fresh = runCatching { previews(folder, messages.filter { uids[it.messageNumber] !in cached }) }.getOrDefault(emptyMap())
        if (fresh.isNotEmpty()) {
            db.blockingTx {
                fresh.forEach { (number, value) ->
                    update(
                        "INSERT OR REPLACE INTO previews (account_id, folder, uid_validity, uid, preview, attachments) VALUES (?, ?, ?, ?, ?, ?)",
                        account.id, folder.fullName, validity, uids[number], value.first, value.second,
                    )
                }
            }
        }
        return messages.map { message ->
            val uid = uids[message.messageNumber]!!
            val (preview, attachments) = cached[uid] ?: fresh[message.messageNumber] ?: ("" to false)
            summary(MessageRef(account.id, folder.fullName, validity, uid), message, preview, attachments)
        }.sortedByDescending { MessageRef.decode(it.id)!!.uid }
    }

    /**
     * The first words of each message, and whether it has attachments: its structure first, then
     * the start of its text part, asked for together for all messages whose text is the same part.
     */
    private fun previews(folder: IMAPFolder, messages: List<JMessage>): Map<Int, Pair<String, Boolean>> {
        if (messages.isEmpty()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val structures = folder.doCommand { protocol: IMAPProtocol ->
            val responses = protocol.fetch(MessageSet.createMessageSets(messages.map { it.messageNumber }.toIntArray()), "BODYSTRUCTURE")
            protocol.handleResult(responses.last())
            responses.filterIsInstance<FetchResponse>().mapNotNull { r -> r.getItem(BODYSTRUCTURE::class.java)?.let { r.number to it } }.toMap()
        } as Map<Int, BODYSTRUCTURE>
        val result = mutableMapOf<Int, Pair<String, Boolean>>()
        val wanted = structures.mapNotNull { (number, structure) -> BodyReader.previewPart(structure)?.let { Triple(number, structure, it) } }
        structures.forEach { (number, structure) -> result[number] = "" to BodyReader.hasAttachments(structure) }
        wanted.groupBy { it.third.section }.forEach { (section, group) ->
            @Suppress("UNCHECKED_CAST")
            val bodies = folder.doCommand { protocol: IMAPProtocol ->
                val responses = protocol.fetch(MessageSet.createMessageSets(group.map { it.first }.toIntArray()), "BODY.PEEK[$section]<0.$PREVIEW_BYTES>")
                protocol.handleResult(responses.last())
                responses.filterIsInstance<FetchResponse>().mapNotNull { r -> r.getItem(BODY::class.java)?.let { r.number to it.byteArray.newBytes } }.toMap()
            } as Map<Int, ByteArray>
            group.forEach { (number, structure, part) ->
                val text = bodies[number]?.let { runCatching { BodyReader.previewOf(it, part) }.getOrNull() }.orEmpty()
                result[number] = text to BodyReader.hasAttachments(structure)
            }
        }
        return result
    }

    private fun summary(ref: MessageRef, message: JMessage, preview: String, attachments: Boolean): MessageSummary {
        val mime = message as MimeMessage
        val flags = runCatching { message.flags }.getOrNull()
        return MessageSummary(
            id = ref.id,
            accountId = ref.accountId,
            folder = ref.folder,
            from = runCatching { mime.from?.firstOrNull() }.getOrNull()?.let(::address),
            to = runCatching { mime.getRecipients(JMessage.RecipientType.TO) }.getOrNull().orEmpty().mapNotNull(::address),
            subject = runCatching { mime.subject }.getOrNull().orEmpty(),
            preview = preview,
            date = (runCatching { mime.sentDate }.getOrNull() ?: runCatching { mime.receivedDate }.getOrNull())?.time ?: 0,
            unread = flags?.contains(Flags.Flag.SEEN) == false,
            flagged = flags?.contains(Flags.Flag.FLAGGED) == true,
            answered = flags?.contains(Flags.Flag.ANSWERED) == true,
            draft = flags?.contains(Flags.Flag.DRAFT) == true,
            hasAttachments = attachments,
            size = runCatching { message.size }.getOrDefault(0).coerceAtLeast(0),
        )
    }

    /** One message, made safe to show; remote images only if [images]. Opening it marks it read if [markRead]. */
    suspend fun message(user: User, id: String, images: Boolean, markRead: Boolean): Message {
        val ref = MessageRef.decode(id) ?: throw ApiError.notFound("No such message")
        val account = membership(user, ref.accountId).account
        return connections.imap(account) { store ->
            withMessage(store, ref, write = markRead) { _, message ->
                val bodies = BodyReader.read(message)
                val byCid = bodies.parts.filter { it.contentId != null }.associateBy { it.contentId!! }
                val urls = bodies.parts.associate { it.path to links.part(ref, it.path, it.filename) }
                val usedCids = mutableSetOf<String>()
                val clean = bodies.html?.let { html ->
                    Sanitizer.clean(
                        html,
                        cidUrl = { cid -> byCid[cid]?.let { usedCids += cid; urls[it.path] } },
                        proxy = if (images) links::proxy else null,
                    )
                }
                if (markRead && !message.isSet(Flags.Flag.SEEN)) runCatching { message.setFlag(Flags.Flag.SEEN, true) }
                val summary = summary(ref, message, "", bodies.parts.any { it.contentId !in usedCids })
                Message(
                    summary = summary.copy(unread = if (markRead) false else summary.unread, preview = BodyReader.preview(bodies.text ?: "")),
                    cc = addresses { message.getRecipients(JMessage.RecipientType.CC) },
                    bcc = addresses { message.getRecipients(JMessage.RecipientType.BCC) },
                    replyTo = addresses { message.replyTo }.filter { r -> summary.from?.email?.equals(r.email, true) != true },
                    html = clean?.html,
                    text = bodies.text ?: bodies.html?.let(BodyReader::htmlToText).orEmpty(),
                    remoteImages = clean?.remoteImages == true,
                    imagesShown = images,
                    attachments = bodies.parts.map { part ->
                        Attachment(part.path, part.filename, part.contentType, part.size, part.contentId in usedCids, urls[part.path]!!)
                    },
                    rawUrl = links.raw(ref, summary.subject),
                    unsubscribe = runCatching { message.getHeader("List-Unsubscribe")?.firstOrNull() }.getOrNull()
                        ?.let { Regex("<((?:https|mailto):[^>]+)>").find(it)?.groupValues?.get(1) },
                )
            }
        }
    }

    /** What's needed to answer, forward, or reopen a message (as [user] may). */
    suspend fun original(user: User, id: String): Original {
        val ref = MessageRef.decode(id) ?: throw ApiError.notFound("No such message")
        val account = membership(user, ref.accountId).account
        return connections.imap(account) { store ->
            withMessage(store, ref, write = false) { _, message ->
                val bodies = BodyReader.read(message)
                val clean = bodies.html?.let { Sanitizer.forQuote(Sanitizer.clean(it, cidUrl = { null }, proxy = null).html) }
                Original(
                    ref = ref,
                    account = account,
                    from = addresses { message.from },
                    to = addresses { message.getRecipients(JMessage.RecipientType.TO) },
                    cc = addresses { message.getRecipients(JMessage.RecipientType.CC) },
                    bcc = addresses { message.getRecipients(JMessage.RecipientType.BCC) },
                    replyTo = addresses { message.replyTo },
                    subject = runCatching { message.subject }.getOrNull().orEmpty(),
                    date = (runCatching { message.sentDate }.getOrNull() ?: runCatching { message.receivedDate }.getOrNull())?.time ?: 0,
                    messageId = runCatching { message.messageID }.getOrNull(),
                    references = runCatching { message.getHeader("References")?.firstOrNull() }.getOrNull()
                        ?.let { Regex("<[^>]+>").findAll(it).map { m -> m.value }.toList() }.orEmpty(),
                    text = bodies.text ?: bodies.html?.let(BodyReader::htmlToText).orEmpty(),
                    html = clean,
                    parts = bodies.parts,
                    draftOf = listOf(DRAFT_REPLY_HEADER, DRAFT_FORWARD_HEADER).mapNotNull { name ->
                        runCatching { message.getHeader(name)?.firstOrNull() }.getOrNull()?.let { name to it.trim() }
                    }.toMap(),
                )
            }
        }
    }

    /** A part of a message, or the whole message (as .eml) when [part] is empty. Signed links come here. */
    suspend fun part(ref: MessageRef, part: String): PartData {
        val account = accounts.get(ref.accountId) ?: throw ApiError.notFound("That account has been removed")
        return connections.imap(account) { store ->
            withMessage(store, ref, write = false) { _, message ->
                if (part.isEmpty()) {
                    val out = ByteArrayOutputStream()
                    message.writeTo(out)
                    PartData("message.eml", "message/rfc822", out.toByteArray())
                } else {
                    val found = BodyReader.find(message, part) ?: throw ApiError.notFound("That attachment isn't in the message")
                    val filename = BodyReader.cleanFilename(runCatching { found.fileName }.getOrNull() ?: "attachment")
                    PartData(filename, BodyReader.baseType(found.contentType), found.inputStream.readBytes())
                }
            }
        }
    }

    /** A part of a message [user] may read (forwarding it, or keeping a draft's). */
    suspend fun partFor(user: User, messageId: String, part: String): PartData {
        val ref = MessageRef.decode(messageId) ?: throw ApiError.notFound("No such message")
        membership(user, ref.accountId)
        return part(ref, part)
    }

    private fun <T> withMessage(store: IMAPStore, ref: MessageRef, write: Boolean, block: (IMAPFolder, IMAPMessage) -> T): T {
        val folder = store.getFolder(ref.folder) as IMAPFolder
        if (!folder.exists()) throw ApiError.notFound("That folder isn't there any more")
        folder.open(if (write) JFolder.READ_WRITE else JFolder.READ_ONLY)
        try {
            if (folder.uidValidity != ref.uidValidity) throw ApiError.gone("This message has moved; reload the folder")
            val message = folder.getMessageByUID(ref.uid) as? IMAPMessage ?: throw ApiError.notFound("This message has been moved or deleted")
            return block(folder, message)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /** Does [actions] to messages [user] may change, folder by folder. */
    suspend fun act(user: User, ids: List<String>, action: Action, target: String?): ActionResult {
        val refs = ids.distinct().associateWith { MessageRef.decode(it) }
        val failed = refs.filterValues { it == null }.mapValues { "No such message" }.toMutableMap()
        var done = 0
        refs.values.filterNotNull().groupBy { Triple(it.accountId, it.folder, it.uidValidity) }.forEach { (key, group) ->
            val account = accounts.membership(user.id, key.first)?.account
            if (account == null) {
                group.forEach { failed[it.id] = "No such account" }
                return@forEach
            }
            try {
                done += connections.imap(account) { store -> apply(store, key.second, key.third, group.map { it.uid }, action, target) }
            } catch (e: Exception) {
                val message = e.message ?: "Couldn't do that"
                group.forEach { failed[it.id] = message }
            }
        }
        return ActionResult(done, failed)
    }

    private fun apply(store: IMAPStore, path: String, validity: Long, uids: List<Long>, action: Action, target: String?): Int {
        val folder = store.getFolder(path) as IMAPFolder
        folder.open(JFolder.READ_WRITE)
        try {
            if (folder.uidValidity != validity) throw ApiError.gone("These messages have moved; reload the folder")
            val messages = folder.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            if (messages.isEmpty()) return 0
            val here = Folders.list(store, counts = false).firstOrNull { it.path == path }?.role ?: FolderRole.OTHER
            fun moveTo(role: FolderRole) {
                val destination = Folders.forRole(store, role, create = true) ?: throw ApiError.badRequest("There's no folder to move them to")
                move(folder, messages, destination)
            }
            when (action) {
                Action.READ -> folder.setFlags(messages, Flags(Flags.Flag.SEEN), true)
                Action.UNREAD -> folder.setFlags(messages, Flags(Flags.Flag.SEEN), false)
                Action.FLAG -> folder.setFlags(messages, Flags(Flags.Flag.FLAGGED), true)
                Action.UNFLAG -> folder.setFlags(messages, Flags(Flags.Flag.FLAGGED), false)
                Action.ARCHIVE -> if (here != FolderRole.ARCHIVE) moveTo(FolderRole.ARCHIVE)
                Action.JUNK -> if (here != FolderRole.JUNK) moveTo(FolderRole.JUNK)
                Action.NOT_JUNK -> moveTo(FolderRole.INBOX)
                // In the Trash already: gone for good.
                Action.TRASH -> if (here == FolderRole.TRASH) expunge(folder, messages) else moveTo(FolderRole.TRASH)
                Action.DELETE -> expunge(folder, messages)
                Action.MOVE -> {
                    val name = target?.takeIf { it.isNotBlank() } ?: throw ApiError.badRequest("Move them where?")
                    if (name == path) return messages.size
                    val destination = store.getFolder(name) as IMAPFolder
                    if (!destination.exists()) throw ApiError.notFound("There's no folder called $name")
                    move(folder, messages, destination)
                }
            }
            return messages.size
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun move(from: IMAPFolder, messages: Array<JMessage>, to: IMAPFolder) {
        try {
            from.moveMessages(messages, to)
        } catch (e: MethodNotSupportedException) {
            // No MOVE on this server: copy, then delete the originals.
            from.copyMessages(messages, to)
            expunge(from, messages)
        }
    }

    private fun expunge(folder: IMAPFolder, messages: Array<JMessage>) {
        folder.setFlags(messages, Flags(Flags.Flag.DELETED), true)
        try {
            folder.expunge(messages)
        } catch (e: MethodNotSupportedException) {
            folder.expunge()
        }
    }

    /** Puts [message] in [account]'s folder for [role] (made if there's none): its id there, if the server says. */
    suspend fun append(account: StoredAccount, role: FolderRole, message: MimeMessage, flags: Flags): String? =
        connections.imap(account) { store ->
            val folder = Folders.forRole(store, role, create = true) ?: throw ApiError.badRequest("There's no ${role.name.lowercase()} folder")
            message.setFlags(flags, true)
            val appended = runCatching { folder.appendUIDMessages(arrayOf(message)).firstOrNull() }.getOrElse {
                folder.appendMessages(arrayOf(message))
                null
            }
            if (appended != null) return@imap MessageRef(account.id, folder.fullName, appended.uidvalidity, appended.uid).id
            // The server doesn't say where it put it: find it by its Message-ID.
            val messageId = message.messageID ?: return@imap null
            folder.open(JFolder.READ_ONLY)
            try {
                val found = folder.search(HeaderTerm("Message-ID", messageId)).lastOrNull() ?: return@imap null
                MessageRef(account.id, folder.fullName, folder.uidValidity, folder.getUID(found)).id
            } finally {
                runCatching { folder.close(false) }
            }
        }

    /** Sets or clears a flag on a message [user] may change (answered, forwarded). */
    suspend fun mark(user: User, id: String, flag: Flags) {
        val ref = MessageRef.decode(id) ?: return
        val account = membership(user, ref.accountId).account
        connections.imap(account) { store ->
            withMessage(store, ref, write = true) { _, message -> message.setFlags(flag, true) }
        }
    }

    /** Deletes a message for good (a draft that's been sent, or saved again). */
    suspend fun deleteForGood(user: User, id: String) {
        act(user, listOf(id), Action.DELETE, null)
    }

    /**
     * What's arrived in [user]'s inboxes since [cursor] (a cursor from the last call; none, the
     * first time, which gives only the cursor and unread counts), unread messages only.
     */
    suspend fun newMail(user: User, cursor: String?): NewMail {
        val known = cursor?.let(::decodeInboxCursor).orEmpty()
        val members = accounts.forUser(user.id)
        val results = coroutineScope {
            members.map { member ->
                async {
                    runCatching {
                        connections.imap(member.account) { store ->
                            val inbox = store.getFolder("INBOX") as IMAPFolder
                            val status = Folders.status(inbox)
                            val before = known[member.account.id]
                            val fresh = if (before != null && before.validity == status.uidvalidity && status.uidnext > before.next) {
                                inbox.open(JFolder.READ_ONLY)
                                try {
                                    val arrived = inbox.getMessagesByUID(before.next, status.uidnext - 1).filterNotNull().takeLast(NEW_LIMIT)
                                    summarize(member.account, inbox, arrived.toTypedArray()).filter { it.unread }
                                } finally {
                                    runCatching { inbox.close(false) }
                                }
                            } else {
                                emptyList()
                            }
                            Triple(member.account.id, InboxPosition(status.uidvalidity, status.uidnext, status.unseen), fresh)
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        // Accounts that couldn't be reached keep their old place, so nothing is missed when they're back.
        val positions = known.filterKeys { id -> members.any { it.account.id == id } } + results.associate { it.first to it.second }
        return NewMail(
            cursor = encodeInboxCursor(positions),
            messages = results.flatMap { it.third }.sortedByDescending { it.date },
            unread = results.associate { it.first to it.second.unseen.coerceAtLeast(0) },
        )
    }

    @Serializable
    private data class InboxPosition(val validity: Long, val next: Long, val unseen: Int = 0)

    private fun encodeInboxCursor(positions: Map<String, InboxPosition>): String =
        b64e.encodeToString(cursorJson.encodeToString(positions.mapValues { it.value.copy(unseen = 0) }).toByteArray())

    private fun decodeInboxCursor(cursor: String): Map<String, InboxPosition>? =
        runCatching { cursorJson.decodeFromString<Map<String, InboxPosition>>(String(b64d.decode(cursor))) }.getOrNull()

    private fun encodeCursor(positions: Map<String, Long?>): String = b64e.encodeToString(cursorJson.encodeToString(positions).toByteArray())

    private fun decodeCursor(cursor: String): Map<String, Long?>? =
        runCatching { cursorJson.decodeFromString<Map<String, Long?>>(String(b64d.decode(cursor))) }.getOrNull()

    companion object {
        const val ALL = "all"
        const val PAGE = 50
        private const val PREVIEW_BYTES = 2048
        private const val NEW_LIMIT = 20
        const val DRAFT_REPLY_HEADER = "X-SAME-Reply-To-Message"
        const val DRAFT_FORWARD_HEADER = "X-SAME-Forward-Of-Message"
        private val b64e = Base64.getUrlEncoder().withoutPadding()
        private val b64d = Base64.getUrlDecoder()
        private val cursorJson = Json { ignoreUnknownKeys = true }

        fun address(address: jakarta.mail.Address): Address? = (address as? InternetAddress)?.let {
            Address(it.address ?: return null, it.personal?.trim()?.trim('"')?.ifBlank { null })
        }

        fun addresses(read: () -> Array<jakarta.mail.Address>?): List<Address> =
            runCatching { read() }.getOrNull().orEmpty().mapNotNull(::address)

        /** Each word in the subject, the sender, the recipients or the text. */
        fun searchTerm(query: String): SearchTerm {
            val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }.take(8)
            val terms = words.map { word ->
                OrTerm(arrayOf(SubjectTerm(word), FromStringTerm(word), RecipientStringTerm(JMessage.RecipientType.TO, word), BodyTerm(word)))
            }
            return if (terms.size == 1) terms.single() else AndTerm(terms.toTypedArray())
        }
    }
}
