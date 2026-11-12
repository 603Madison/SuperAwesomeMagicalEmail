package com.email.server.mail

import com.email.api.Endpoint
import com.email.api.MailAccount
import com.email.api.Member
import com.email.api.Provider
import com.email.api.Security
import com.email.api.User
import com.email.server.ApiError
import com.email.server.db.Database
import com.email.server.db.Row
import com.email.server.util.Random
import com.email.server.util.SecretBox

/** A mail account as saved, with its password (decrypted only in memory). */
data class StoredAccount(
    val id: String,
    /** Set up by an admin (who alone changes or removes it), rather than by the person who reads it. */
    val managed: Boolean,
    val createdBy: String?,
    val provider: Provider,
    val email: String,
    val name: String,
    val label: String,
    val username: String,
    val password: String,
    val imap: Endpoint,
    val smtp: Endpoint,
    val saveSent: Boolean,
    val problem: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Changes whenever its settings or password do, so connections made with old ones are let go. */
    val version: String get() = "$id@$updatedAt"

    fun view(viewer: User, signature: String = ""): MailAccount = MailAccount(
        id = id,
        email = email,
        name = name,
        label = label,
        provider = provider,
        username = username,
        imap = imap,
        smtp = smtp,
        saveSent = saveSent,
        managed = managed,
        editable = if (managed) viewer.isAdmin else true,
        signature = signature,
        problem = problem,
    )
}

/** An account someone reads, with their own settings for it. */
data class Membership(val account: StoredAccount, val position: Int, val signature: String)

/** Mail accounts, and who reads each: one person for their own, any number for an admin's mailbox. */
class AccountStore(
    private val db: Database,
    private val box: SecretBox,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The accounts [userId] reads, in their order. */
    suspend fun forUser(userId: String): List<Membership> = db.tx {
        query(
            """SELECT a.*, m.position AS m_position, m.signature AS m_signature FROM mail_accounts a
               JOIN mail_account_members m ON m.account_id = a.id WHERE m.user_id = ?
               ORDER BY m.position, a.created_at""",
            userId,
        ) { Membership(toAccount(it), it.int("m_position"), it.string("m_signature")) }
    }

    /** [accountId] if [userId] reads it. */
    suspend fun membership(userId: String, accountId: String): Membership? = db.tx {
        one(
            """SELECT a.*, m.position AS m_position, m.signature AS m_signature FROM mail_accounts a
               JOIN mail_account_members m ON m.account_id = a.id WHERE m.user_id = ? AND a.id = ?""",
            userId, accountId,
        ) { Membership(toAccount(it), it.int("m_position"), it.string("m_signature")) }
    }

    suspend fun get(id: String): StoredAccount? = db.tx { one("SELECT * FROM mail_accounts WHERE id = ?", id) { toAccount(it) } }

    /** The mailboxes admins set up, everyone's. */
    suspend fun managed(): List<StoredAccount> = db.tx { query("SELECT * FROM mail_accounts WHERE managed = 1 ORDER BY email COLLATE NOCASE") { toAccount(it) } }

    suspend fun members(accountId: String): List<Member> = db.tx {
        query(
            """SELECT u.id, u.username, u.display_name, u.password_hash, u.oidc_subject, u.break_glass FROM users u
               JOIN mail_account_members m ON m.user_id = u.id WHERE m.account_id = ? ORDER BY u.display_name COLLATE NOCASE""",
            accountId,
        ) {
            Member(
                userId = it.string("id"),
                username = it.string("username"),
                displayName = it.string("display_name"),
                waitingForSignIn = it.stringOrNull("password_hash") == null && it.stringOrNull("oidc_subject") == null && !it.bool("break_glass"),
            )
        }
    }

    suspend fun create(
        managed: Boolean,
        createdBy: String?,
        provider: Provider,
        email: String,
        name: String,
        label: String,
        username: String,
        password: String,
        imap: Endpoint,
        smtp: Endpoint,
        saveSent: Boolean,
        members: List<String>,
    ): StoredAccount {
        val id = Random.id()
        val now = clock()
        db.tx {
            update(
                """INSERT INTO mail_accounts (id, managed, created_by, provider, email, name, label, username,
                   imap_host, imap_port, imap_security, smtp_host, smtp_port, smtp_security, save_sent, secrets, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, managed, createdBy, provider, email, name, label, username,
                imap.host, imap.port, imap.security, smtp.host, smtp.port, smtp.security, saveSent, seal(id, password), now, now,
            )
            members.distinct().forEach { addMember(id, it, now) }
        }
        return get(id)!!
    }

    suspend fun update(
        id: String,
        provider: Provider? = null,
        email: String? = null,
        name: String? = null,
        label: String? = null,
        username: String? = null,
        password: String? = null,
        imap: Endpoint? = null,
        smtp: Endpoint? = null,
        saveSent: Boolean? = null,
    ): StoredAccount {
        val current = get(id) ?: throw ApiError.notFound("No such account")
        db.tx {
            update(
                """UPDATE mail_accounts SET provider = ?, email = ?, name = ?, label = ?, username = ?, imap_host = ?, imap_port = ?,
                   imap_security = ?, smtp_host = ?, smtp_port = ?, smtp_security = ?, save_sent = ?, secrets = ?, problem = NULL, updated_at = ?
                   WHERE id = ?""",
                provider ?: current.provider,
                email ?: current.email,
                name ?: current.name,
                label ?: current.label,
                username ?: current.username,
                (imap ?: current.imap).host, (imap ?: current.imap).port, (imap ?: current.imap).security,
                (smtp ?: current.smtp).host, (smtp ?: current.smtp).port, (smtp ?: current.smtp).security,
                saveSent ?: current.saveSent,
                seal(id, password ?: current.password),
                // Strictly increasing, so a change always lets go of connections made with the old settings.
                maxOf(clock(), current.updatedAt + 1),
                id,
            )
        }
        return get(id)!!
    }

    suspend fun delete(id: String) = db.tx { update("DELETE FROM mail_accounts WHERE id = ?", id) }

    /** Who reads an admin's mailbox: exactly [userIds] from now on (each keeps their signature). */
    suspend fun setMembers(accountId: String, userIds: List<String>) = db.tx {
        val now = clock()
        val wanted = userIds.distinct()
        val existing = query("SELECT user_id FROM mail_account_members WHERE account_id = ?", accountId) { it.string("user_id") }
        existing.filter { it !in wanted }.forEach { update("DELETE FROM mail_account_members WHERE account_id = ? AND user_id = ?", accountId, it) }
        wanted.filter { it !in existing }.forEach { addMember(accountId, it, now) }
    }

    suspend fun setSignature(userId: String, accountId: String, signature: String) = db.tx {
        update("UPDATE mail_account_members SET signature = ? WHERE user_id = ? AND account_id = ?", signature.take(10_000), userId, accountId)
    }

    /** [userId]'s accounts in the order of [ids] (any left out keep their place after them). */
    suspend fun setOrder(userId: String, ids: List<String>) = db.tx {
        val all = query("SELECT account_id FROM mail_account_members WHERE user_id = ? ORDER BY position", userId) { it.string("account_id") }
        val ordered = ids.filter { it in all } + all.filter { it !in ids }
        ordered.forEachIndexed { index, id -> update("UPDATE mail_account_members SET position = ? WHERE user_id = ? AND account_id = ?", index, userId, id) }
    }

    /** Notes that the server couldn't use the account (null: it works again). */
    suspend fun setProblem(id: String, problem: String?) = db.tx {
        update("UPDATE mail_accounts SET problem = ? WHERE id = ? AND problem IS NOT ?", problem?.take(500), id, problem?.take(500))
    }

    private fun com.email.server.db.Tx.addMember(accountId: String, userId: String, now: Long) {
        if (one("SELECT 1 FROM users WHERE id = ?", userId) { true } == null) throw ApiError.badRequest("There's no such person")
        val position = long("SELECT COALESCE(MAX(position), -1) + 1 FROM mail_account_members WHERE user_id = ?", userId).toInt()
        update(
            "INSERT OR IGNORE INTO mail_account_members (account_id, user_id, position, added_at) VALUES (?, ?, ?, ?)",
            accountId, userId, position, now,
        )
    }

    private fun seal(id: String, password: String) = box.seal(password, "account:$id")

    private fun toAccount(row: Row): StoredAccount {
        val id = row.string("id")
        return StoredAccount(
            id = id,
            managed = row.bool("managed"),
            createdBy = row.stringOrNull("created_by"),
            provider = Provider.entries.firstOrNull { it.name == row.string("provider") } ?: Provider.OTHER,
            email = row.string("email"),
            name = row.string("name"),
            label = row.string("label"),
            username = row.string("username"),
            password = row.stringOrNull("secrets")?.let { runCatching { box.open(it, "account:$id") }.getOrNull() }.orEmpty(),
            imap = Endpoint(row.string("imap_host"), row.int("imap_port"), Security.valueOf(row.string("imap_security"))),
            smtp = Endpoint(row.string("smtp_host"), row.int("smtp_port"), Security.valueOf(row.string("smtp_security"))),
            saveSent = row.bool("save_sent"),
            problem = row.stringOrNull("problem"),
            createdAt = row.long("created_at"),
            updatedAt = row.long("updated_at"),
        )
    }
}
