package com.email.server.mail

import com.email.api.Folder
import com.email.api.FolderRole
import jakarta.mail.Folder as MailFolder
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.Status

/** Working out what folders are for, and listing them. */
object Folders {
    private val ATTRIBUTE_ROLES = mapOf(
        "\\sent" to FolderRole.SENT,
        "\\drafts" to FolderRole.DRAFTS,
        "\\trash" to FolderRole.TRASH,
        "\\junk" to FolderRole.JUNK,
        "\\archive" to FolderRole.ARCHIVE,
        "\\all" to FolderRole.ALL,
        "\\flagged" to FolderRole.FLAGGED,
    )

    /** Names services give their folders, for servers that don't say what each is for. */
    private val NAME_ROLES = mapOf(
        "sent" to FolderRole.SENT, "sent items" to FolderRole.SENT, "sent messages" to FolderRole.SENT, "sent mail" to FolderRole.SENT,
        "drafts" to FolderRole.DRAFTS, "draft" to FolderRole.DRAFTS,
        "trash" to FolderRole.TRASH, "deleted items" to FolderRole.TRASH, "deleted messages" to FolderRole.TRASH, "bin" to FolderRole.TRASH,
        "junk" to FolderRole.JUNK, "spam" to FolderRole.JUNK, "junk e-mail" to FolderRole.JUNK, "junk email" to FolderRole.JUNK, "bulk mail" to FolderRole.JUNK,
        "archive" to FolderRole.ARCHIVE, "archives" to FolderRole.ARCHIVE,
        "all mail" to FolderRole.ALL,
    )

    /** What to call a folder made for [role] when the account has none. */
    val DEFAULT_NAMES = mapOf(
        FolderRole.SENT to "Sent",
        FolderRole.DRAFTS to "Drafts",
        FolderRole.TRASH to "Trash",
        FolderRole.JUNK to "Junk",
        FolderRole.ARCHIVE to "Archive",
    )

    private val ORDER = listOf(FolderRole.INBOX, FolderRole.DRAFTS, FolderRole.SENT, FolderRole.ARCHIVE, FolderRole.ALL, FolderRole.FLAGGED, FolderRole.JUNK, FolderRole.TRASH)

    fun roleOf(path: String, name: String, attributes: Array<String>): FolderRole {
        if (path.equals("INBOX", ignoreCase = true)) return FolderRole.INBOX
        attributes.firstNotNullOfOrNull { ATTRIBUTE_ROLES[it.lowercase()] }?.let { return it }
        return NAME_ROLES[name.lowercase()] ?: FolderRole.OTHER
    }

    /**
     * Every folder in the account, with how many messages each has and how many are unread,
     * the special ones first (Inbox, Drafts, Sent, Archive, Junk, Trash) and the rest by name.
     * Only one folder per role keeps it (the first found, preferring the server's own flag).
     */
    fun list(store: IMAPStore, counts: Boolean = true): List<Folder> {
        val separator = runCatching { store.defaultFolder.separator }.getOrDefault('/')
        val found = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>().map { folder ->
            val attributes = runCatching { folder.attributes }.getOrDefault(emptyArray())
            val path = folder.fullName
            val name = path.substringAfterLast(separator)
            val selectable = attributes.none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) } &&
                (folder.type and MailFolder.HOLDS_MESSAGES) != 0
            Triple(folder, attributes, Folder(path, name, roleOf(path, name, attributes), 0, 0, path.count { it == separator }, selectable))
        }
        // One folder per role: one flagged by the server beats one that's only named like it.
        val taken = mutableSetOf<FolderRole>()
        val withRoles = found.sortedByDescending { (_, attributes, _) -> attributes.any { it.lowercase() in ATTRIBUTE_ROLES } }.map { (folder, attributes, info) ->
            val role = if (info.role != FolderRole.OTHER && taken.add(info.role)) info.role else FolderRole.OTHER
            Triple(folder, attributes, info.copy(role = role))
        }
        val counted = withRoles.map { (folder, _, info) ->
            if (!info.selectable || !counts) return@map info
            val status = runCatching { status(folder) }.getOrNull()
            info.copy(unread = status?.unseen?.coerceAtLeast(0) ?: 0, total = status?.total?.coerceAtLeast(0) ?: 0)
        }
        return counted.sortedWith(
            compareBy<Folder>({ ORDER.indexOf(it.role).let { i -> if (i < 0) ORDER.size else i } }, { it.path.lowercase() }),
        )
    }

    /** The folder's message count, unread count, next UID and UID validity, in one go (without opening it). */
    fun status(folder: IMAPFolder): Status = folder.doCommand { protocol: IMAPProtocol ->
        protocol.status(folder.fullName, arrayOf("MESSAGES", "UNSEEN", "UIDNEXT", "UIDVALIDITY"))
    } as Status

    /** [store]'s folder for [role], made if [create] and there's none. */
    fun forRole(store: IMAPStore, role: FolderRole, create: Boolean): IMAPFolder? {
        if (role == FolderRole.INBOX) return store.getFolder("INBOX") as IMAPFolder
        list(store, counts = false).firstOrNull { it.role == role }?.let { return store.getFolder(it.path) as IMAPFolder }
        if (!create) return null
        val name = DEFAULT_NAMES[role] ?: return null
        val folder = store.getFolder(name) as IMAPFolder
        if (!folder.exists()) folder.create(MailFolder.HOLDS_MESSAGES)
        return folder
    }
}
