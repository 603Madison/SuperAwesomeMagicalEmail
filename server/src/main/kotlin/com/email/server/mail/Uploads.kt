package com.email.server.mail

import com.email.api.Address
import com.email.api.Contact
import com.email.api.Upload
import com.email.server.ApiError
import com.email.server.db.Database
import com.email.server.util.Random
import java.io.File

/**
 * Files uploaded to attach to a message being written. They're kept in the data folder until the
 * message is sent, or for a day (a draft that's kept has its own copy in the Drafts folder).
 */
class UploadStore(private val db: Database, private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class Stored(val upload: Upload, val file: File)

    fun newFile(): Pair<String, File> {
        dir.mkdirs()
        val id = Random.id()
        return id to File(dir, id)
    }

    suspend fun record(userId: String, id: String, filename: String, contentType: String, size: Long): Upload {
        val upload = Upload(id, BodyReader.cleanFilename(filename), contentType.ifBlank { "application/octet-stream" }, size)
        db.tx {
            update(
                "INSERT INTO uploads (id, user_id, filename, content_type, size, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, upload.filename, upload.contentType, size, clock(),
            )
        }
        return upload
    }

    /** [userId]'s uploads with these ids (anyone else's, or ones that have gone, are refused). */
    suspend fun get(userId: String, ids: List<String>): List<Stored> = ids.distinct().map { id ->
        val upload = db.tx {
            one("SELECT * FROM uploads WHERE id = ? AND user_id = ?", id, userId) {
                Upload(it.string("id"), it.string("filename"), it.string("content_type"), it.long("size"))
            }
        } ?: throw ApiError.gone("An attachment has expired; attach it again")
        val file = File(dir, upload.id)
        if (!file.isFile) throw ApiError.gone("An attachment has expired; attach it again")
        Stored(upload, file)
    }

    suspend fun delete(userId: String, ids: List<String>) {
        val owned = db.tx {
            ids.filter { id -> update("DELETE FROM uploads WHERE id = ? AND user_id = ?", id, userId) > 0 }
        }
        owned.forEach { File(dir, it).delete() }
    }

    /** Lets go of uploads older than a day, and files nothing refers to. */
    suspend fun purge(maxAgeMs: Long = 24L * 3600 * 1000) {
        val cutoff = clock() - maxAgeMs
        val old = db.tx {
            val ids = query("SELECT id FROM uploads WHERE created_at < ?", cutoff) { it.string("id") }
            update("DELETE FROM uploads WHERE created_at < ?", cutoff)
            ids
        }
        old.forEach { File(dir, it).delete() }
        val known = db.tx { query("SELECT id FROM uploads") { it.string("id") } }.toSet()
        dir.listFiles()?.filter { it.name !in known && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

/** The addresses each person writes to, for suggesting as they type. */
class ContactStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun used(userId: String, addresses: List<Address>) = db.tx {
        val now = clock()
        addresses.distinctBy { it.email.lowercase() }.forEach { address ->
            update(
                """INSERT INTO contacts (user_id, email, name, uses, last_used) VALUES (?, ?, ?, 1, ?)
                   ON CONFLICT (user_id, email) DO UPDATE SET uses = uses + 1, last_used = excluded.last_used,
                   name = COALESCE(excluded.name, contacts.name)""",
                userId, address.email.trim(), address.name?.trim()?.ifBlank { null }, now,
            )
        }
    }

    /** Addresses matching [query], those written to most and lately first. */
    suspend fun search(userId: String, query: String, limit: Int = 8): List<Contact> = db.tx {
        val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        query(
            """SELECT email, name FROM contacts WHERE user_id = ? AND (email LIKE ? ESCAPE '\' OR name LIKE ? ESCAPE '\')
               ORDER BY uses DESC, last_used DESC LIMIT ?""",
            userId, like, like, limit,
        ) { Contact(it.string("email"), it.stringOrNull("name")) }
    }
}
