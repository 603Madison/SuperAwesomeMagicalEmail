package com.email.server.db

/**
 * The database's tables, as a list of migrations. Each runs once, in order, and the database
 * remembers how many have run (SQLite's user_version); never edit one that has been released,
 * add another.
 */
object Schema {
    private val migrations: List<String> = listOf(
        // 1: accounts, sign-ins and settings; mail accounts and who reads them; addresses people
        // write to; files uploaded to attach; message previews.
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            display_name TEXT NOT NULL,
            email TEXT COLLATE NOCASE,
            password_hash TEXT,
            role TEXT NOT NULL,
            break_glass INTEGER NOT NULL DEFAULT 0,
            disabled INTEGER NOT NULL DEFAULT 0,
            oidc_issuer TEXT,
            oidc_subject TEXT,
            created_at INTEGER NOT NULL,
            last_login_at INTEGER,
            UNIQUE (oidc_issuer, oidc_subject)
        );
        CREATE UNIQUE INDEX users_email ON users(email) WHERE email IS NOT NULL;
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE INDEX sessions_user ON sessions(user_id);
        CREATE TABLE settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE mail_accounts (
            id TEXT PRIMARY KEY,
            managed INTEGER NOT NULL,
            created_by TEXT,
            provider TEXT NOT NULL,
            email TEXT NOT NULL,
            name TEXT NOT NULL,
            label TEXT NOT NULL,
            username TEXT NOT NULL,
            imap_host TEXT NOT NULL,
            imap_port INTEGER NOT NULL,
            imap_security TEXT NOT NULL,
            smtp_host TEXT NOT NULL,
            smtp_port INTEGER NOT NULL,
            smtp_security TEXT NOT NULL,
            save_sent INTEGER NOT NULL,
            secrets TEXT,
            problem TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE mail_account_members (
            account_id TEXT NOT NULL REFERENCES mail_accounts(id) ON DELETE CASCADE,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            position INTEGER NOT NULL DEFAULT 0,
            signature TEXT NOT NULL DEFAULT '',
            added_at INTEGER NOT NULL,
            PRIMARY KEY (account_id, user_id)
        );
        CREATE INDEX members_user ON mail_account_members(user_id);
        CREATE TABLE contacts (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            email TEXT NOT NULL COLLATE NOCASE,
            name TEXT,
            uses INTEGER NOT NULL DEFAULT 0,
            last_used INTEGER NOT NULL,
            PRIMARY KEY (user_id, email)
        );
        CREATE TABLE uploads (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            filename TEXT NOT NULL,
            content_type TEXT NOT NULL,
            size INTEGER NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE previews (
            account_id TEXT NOT NULL REFERENCES mail_accounts(id) ON DELETE CASCADE,
            folder TEXT NOT NULL,
            uid_validity INTEGER NOT NULL,
            uid INTEGER NOT NULL,
            preview TEXT NOT NULL,
            attachments INTEGER NOT NULL,
            PRIMARY KEY (account_id, folder, uid_validity, uid)
        )
        """,
    )

    fun migrate(db: Database) = db.blockingTx {
        val current = long("PRAGMA user_version").toInt()
        require(current <= migrations.size) {
            "This database was made by a newer SuperAwesomeMagicalEmail server (schema $current); update the server"
        }
        migrations.drop(current).forEach { migration ->
            migration.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(::execute)
        }
        execute("PRAGMA user_version = ${migrations.size}")
    }
}
