package com.email.server.auth

import com.email.api.Role
import com.email.api.User
import com.email.server.ApiError
import com.email.server.db.Database
import com.email.server.db.Row
import com.email.server.util.Passwords
import com.email.server.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Accounts: local ones with passwords, ones from the identity provider, and ones an admin made
 * for someone who'll sign in through the identity provider but hasn't yet (so their mailboxes can
 * be ready for them). Those are found by username or email at that first sign-in ([claimable]).
 */
class UserStore(
    private val db: Database,
    private val passwords: Passwords,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun count(): Long = db.tx { long("SELECT COUNT(*) FROM users") }

    suspend fun list(): List<User> = db.tx { query("SELECT * FROM users ORDER BY display_name COLLATE NOCASE, username COLLATE NOCASE", map = ::toUser) }

    suspend fun byId(id: String): User? = db.tx { one("SELECT * FROM users WHERE id = ?", id, map = ::toUser) }

    suspend fun byUsername(username: String): User? =
        db.tx { one("SELECT * FROM users WHERE username = ?", username.trim(), map = ::toUser) }

    suspend fun byOidc(issuer: String, subject: String): User? =
        db.tx { one("SELECT * FROM users WHERE oidc_issuer = ? AND oidc_subject = ?", issuer, subject, map = ::toUser) }

    /**
     * The account an admin made for the person the identity provider signed in, if there is one
     * waiting: one with no password and no sign-in linked yet, whose username or email is theirs.
     * The break-glass admin, and accounts with passwords, are never handed over this way.
     */
    suspend fun claimable(username: String, email: String?): User? = db.tx {
        val waiting = "oidc_subject IS NULL AND password_hash IS NULL AND break_glass = 0"
        one("SELECT * FROM users WHERE $waiting AND username = ? COLLATE NOCASE", username.trim(), map = ::toUser)
            ?: email?.trim()?.takeIf { it.contains('@') }?.let { mail ->
                one("SELECT * FROM users WHERE $waiting AND email = ? COLLATE NOCASE", mail, map = ::toUser)
            }
    }

    /** Links the identity provider's sign-in to [id] (an account waiting for its first sign-in). */
    suspend fun linkOidc(id: String, issuer: String, subject: String, email: String?, displayName: String?): User {
        db.tx {
            update(
                """UPDATE users SET oidc_issuer = ?, oidc_subject = ?,
                   email = COALESCE(email, ?),
                   display_name = CASE WHEN display_name = username AND ? IS NOT NULL THEN ? ELSE display_name END
                   WHERE id = ?""",
                issuer, subject, email?.trim()?.ifBlank { null }, displayName?.trim()?.ifBlank { null },
                displayName?.trim()?.take(MAX_NAME), id,
            )
        }
        return byId(id)!!
    }

    suspend fun emailFree(email: String): Boolean =
        db.tx { one("SELECT 1 FROM users WHERE email = ? COLLATE NOCASE", email.trim()) { true } } == null

    /** The account for [username] if [password] is right (and it may sign in with one). */
    suspend fun checkPassword(username: String, password: String): User? {
        val found = db.tx {
            one("SELECT * FROM users WHERE username = ?", username.trim()) { toUser(it) to it.stringOrNull("password_hash") }
        }
        val ok = withContext(Dispatchers.Default) { passwords.verify(password, found?.second) }
        return found?.first?.takeIf { ok }
    }

    suspend fun create(
        username: String,
        displayName: String = username,
        role: Role = Role.USER,
        password: String? = null,
        breakGlass: Boolean = false,
        oidcIssuer: String? = null,
        oidcSubject: String? = null,
        email: String? = null,
    ): User {
        val name = validUsername(username)
        password?.let(::validPassword)
        val mail = validEmail(email)
        val hash = password?.let { withContext(Dispatchers.Default) { passwords.hash(it) } }
        val id = Random.id()
        db.tx {
            if (one("SELECT 1 FROM users WHERE username = ?", name) { true } == true) {
                throw ApiError.conflict("The username \"$name\" is taken")
            }
            if (mail != null && one("SELECT 1 FROM users WHERE email = ? COLLATE NOCASE", mail) { true } == true) {
                throw ApiError.conflict("Someone already has the email $mail")
            }
            update(
                """INSERT INTO users (id, username, display_name, email, password_hash, role, break_glass, oidc_issuer, oidc_subject, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, name, displayName.trim().ifBlank { name }.take(MAX_NAME), mail, hash, role, breakGlass, oidcIssuer, oidcSubject, clock(),
            )
        }
        return byId(id)!!
    }

    /** A username based on [wanted] that isn't taken yet ("maddie", then "maddie-2"…). */
    suspend fun freeUsername(wanted: String): String {
        val base = wanted.trim().replace(Regex("[^A-Za-z0-9._@-]"), "-").trim('-').take(MAX_NAME - 4).ifBlank { "user" }
        return db.tx {
            generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
                .first { candidate -> one("SELECT 1 FROM users WHERE username = ?", candidate) { true } == null }
        }
    }

    suspend fun update(
        id: String,
        displayName: String? = null,
        role: Role? = null,
        disabled: Boolean? = null,
        email: String? = null,
        clearEmail: Boolean = false,
    ): User {
        val user = byId(id) ?: throw ApiError.notFound("No such person")
        if (user.breakGlass && (role == Role.USER || disabled == true)) {
            throw ApiError.badRequest("The break-glass admin can't be demoted or turned off")
        }
        val mail = if (clearEmail) null else validEmail(email) ?: user.email
        db.tx {
            if (mail != null && one("SELECT 1 FROM users WHERE email = ? COLLATE NOCASE AND id != ?", mail, id) { true } == true) {
                throw ApiError.conflict("Someone already has the email $mail")
            }
            update(
                "UPDATE users SET display_name = ?, role = ?, disabled = ?, email = ? WHERE id = ?",
                (displayName?.trim()?.ifBlank { null } ?: user.displayName).take(MAX_NAME),
                role ?: user.role,
                disabled ?: user.disabled,
                mail,
                id,
            )
        }
        return byId(id)!!
    }

    suspend fun setPassword(id: String, password: String) {
        validPassword(password)
        val hash = withContext(Dispatchers.Default) { passwords.hash(password) }
        if (db.tx { update("UPDATE users SET password_hash = ? WHERE id = ?", hash, id) } == 0) throw ApiError.notFound("No such person")
    }

    suspend fun delete(id: String) {
        val user = byId(id) ?: throw ApiError.notFound("No such person")
        if (user.breakGlass) throw ApiError.badRequest("The break-glass admin can't be deleted")
        db.tx {
            update("DELETE FROM users WHERE id = ?", id)
            // Their own accounts go with them; mailboxes an admin set up stay, to give to someone else.
            update("DELETE FROM mail_accounts WHERE managed = 0 AND id NOT IN (SELECT account_id FROM mail_account_members)")
        }
    }

    suspend fun touchLogin(id: String) = db.tx { update("UPDATE users SET last_login_at = ? WHERE id = ?", clock(), id) }

    suspend fun breakGlass(): User? = db.tx { one("SELECT * FROM users WHERE break_glass = 1", map = ::toUser) }

    companion object {
        const val MAX_NAME = 64
        const val MIN_PASSWORD = 8
        private val USERNAME = Regex("[A-Za-z0-9._@-]{1,$MAX_NAME}")
        private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")

        fun validUsername(username: String): String {
            val name = username.trim()
            if (!USERNAME.matches(name)) {
                throw ApiError.badRequest("Usernames are 1 to $MAX_NAME letters, digits and . _ @ -")
            }
            return name
        }

        fun validEmail(email: String?): String? {
            val mail = email?.trim()?.ifBlank { null } ?: return null
            if (!EMAIL.matches(mail) || mail.length > 254) throw ApiError.badRequest("\"$mail\" isn't an email address")
            return mail
        }

        fun validPassword(password: String) {
            if (password.length < MIN_PASSWORD) throw ApiError.badRequest("Passwords need at least $MIN_PASSWORD characters")
            if (password.length > 1024) throw ApiError.badRequest("That password is too long")
        }

        fun toUser(row: Row) = User(
            id = row.string("id"),
            username = row.string("username"),
            displayName = row.string("display_name"),
            email = row.stringOrNull("email"),
            role = Role.valueOf(row.string("role")),
            breakGlass = row.bool("break_glass"),
            disabled = row.bool("disabled"),
            oidc = row.stringOrNull("oidc_subject") != null,
            hasPassword = row.stringOrNull("password_hash") != null,
            createdAt = row.long("created_at"),
            lastLoginAt = row.longOrNull("last_login_at"),
            waitingForSignIn = row.stringOrNull("oidc_subject") == null && row.stringOrNull("password_hash") == null && !row.bool("break_glass"),
        )
    }
}
