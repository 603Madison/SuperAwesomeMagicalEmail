package com.email.server.auth

import com.email.server.db.Database
import com.email.server.util.Random
import com.email.server.util.hex
import com.email.server.util.sha256
import com.email.api.SessionInfo
import com.email.api.SessionKind
import com.email.api.User

/**
 * Sign-ins. Each is a random token given to the browser (as a cookie) or the app; only its hash
 * is stored, so the database can't be used to sign in. A sign-in lasts while it's used: a month
 * for browsers, a year for the app (which may spend weeks offline).
 */
class SessionStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun create(userId: String, kind: SessionKind, name: String): String {
        val token = Random.token()
        val now = clock()
        db.tx {
            update(
                "INSERT INTO sessions (id, user_id, kind, name, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                idOf(token), userId, kind, name.take(120).ifBlank { kind.name.lowercase() }, now, now, now + lifetime(kind),
            )
        }
        return token
    }

    /** The user and sign-in for [token], if it's valid; using it keeps it alive. */
    suspend fun resolve(token: String): Pair<User, SessionInfo>? {
        if (token.length !in 20..200) return null
        val id = idOf(token)
        val now = clock()
        return db.tx {
            val found = one(
                """SELECT s.id AS s_id, s.kind, s.name, s.created_at AS s_created, s.last_seen_at, s.expires_at, u.*
                   FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.id = ?""",
                id,
            ) { row ->
                UserStore.toUser(row) to SessionInfo(
                    id = row.string("s_id"),
                    kind = SessionKind.valueOf(row.string("kind")),
                    name = row.string("name"),
                    createdAt = row.long("s_created"),
                    lastSeenAt = row.long("last_seen_at"),
                    expiresAt = row.long("expires_at"),
                    current = true,
                )
            } ?: return@tx null
            val (user, session) = found
            if (session.expiresAt < now || user.disabled) {
                update("DELETE FROM sessions WHERE id = ?", id)
                return@tx null
            }
            // Writing on every request isn't needed: every few minutes is plenty.
            if (now - session.lastSeenAt > TOUCH_INTERVAL_MS) {
                update("UPDATE sessions SET last_seen_at = ?, expires_at = ? WHERE id = ?", now, now + lifetime(session.kind), id)
            }
            found
        }
    }

    suspend fun list(userId: String, currentId: String? = null): List<SessionInfo> = db.tx {
        query("SELECT * FROM sessions WHERE user_id = ? AND expires_at > ? ORDER BY last_seen_at DESC", userId, clock()) { row ->
            SessionInfo(
                id = row.string("id"),
                kind = SessionKind.valueOf(row.string("kind")),
                name = row.string("name"),
                createdAt = row.long("created_at"),
                lastSeenAt = row.long("last_seen_at"),
                expiresAt = row.long("expires_at"),
                current = row.string("id") == currentId,
            )
        }
    }

    suspend fun revoke(userId: String, sessionId: String): Boolean =
        db.tx { update("DELETE FROM sessions WHERE id = ? AND user_id = ?", sessionId, userId) } > 0

    suspend fun revokeToken(token: String) = db.tx { update("DELETE FROM sessions WHERE id = ?", idOf(token)) }

    suspend fun revokeAll(userId: String) = db.tx { update("DELETE FROM sessions WHERE user_id = ?", userId) }

    suspend fun purgeExpired() = db.tx { update("DELETE FROM sessions WHERE expires_at < ?", clock()) }

    companion object {
        const val WEB_LIFETIME_MS = 30L * 24 * 3600 * 1000
        const val APP_LIFETIME_MS = 365L * 24 * 3600 * 1000
        private const val TOUCH_INTERVAL_MS = 5L * 60 * 1000

        fun lifetime(kind: SessionKind) = if (kind == SessionKind.WEB) WEB_LIFETIME_MS else APP_LIFETIME_MS

        fun idOf(token: String): String = sha256(token).hex()
    }
}
