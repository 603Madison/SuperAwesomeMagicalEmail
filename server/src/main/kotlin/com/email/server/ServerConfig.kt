package com.email.server

import java.io.File

/** Start-up settings, from environment variables (see README.md). */
data class ServerConfig(
    val dataDir: File,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Trust X-Forwarded-* headers from a reverse proxy in front of the server. */
    val trustProxy: Boolean = false,
    /** How many proxies in a row are in front (each adds the address it saw to X-Forwarded-For). */
    val proxies: Int = 1,
    /** A header the proxy in front sets to the visitor's address (Cloudflare's CF-Connecting-IP). */
    val clientIpHeader: String? = null,
    val adminUsername: String = "admin",
    /** The break-glass admin's password for a new server; generated (and logged once) if empty. */
    val adminPassword: String? = null,
    /** The largest message that can be sent, attachments included (most mail services take 25 MB). */
    val maxMessageBytes: Long = 25L * 1024 * 1024,
    /**
     * Mail servers on these hosts may be used even though they're on a private network (for a mail
     * server on your own network). Otherwise only public addresses are reached, so nobody can use
     * the server to look around the network it's on.
     */
    val allowPrivateMailHosts: Boolean = false,
    /** Settings applied at every start, overriding the admin page (for configuration as code). */
    val overrides: Map<String, String> = emptyMap(),
) {
    val databaseFile: File get() = File(dataDir, "same.db")
    val secretKeyFile: File get() = File(dataDir, "secret.key")
    val uploadsDir: File get() = File(dataDir, "uploads")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            dataDir = File(env["SAME_DATA_DIR"]?.ifBlank { null } ?: "/data"),
            host = env["SAME_HOST"]?.ifBlank { null } ?: "0.0.0.0",
            port = env["SAME_PORT"]?.toIntOrNull() ?: 8080,
            trustProxy = flag(env["SAME_TRUST_PROXY"]),
            proxies = env["SAME_PROXIES"]?.trim()?.toIntOrNull()?.coerceIn(1, 10) ?: 1,
            clientIpHeader = env["SAME_CLIENT_IP_HEADER"]?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) },
            adminUsername = env["SAME_ADMIN_USERNAME"]?.ifBlank { null } ?: "admin",
            adminPassword = env["SAME_ADMIN_PASSWORD"]?.ifBlank { null },
            maxMessageBytes = (env["SAME_MAX_MESSAGE_MB"]?.toLongOrNull()?.coerceIn(1, 200) ?: 25) * 1024 * 1024,
            allowPrivateMailHosts = flag(env["SAME_ALLOW_PRIVATE_MAIL_HOSTS"]),
            overrides = env.filterKeys { it in OVERRIDES }.filterValues { it.isNotBlank() },
        )

        private fun flag(value: String?) = value?.trim()?.lowercase() in setOf("1", "true", "yes")

        val OVERRIDES = setOf(
            "SAME_PUBLIC_URL",
            "SAME_OIDC_ISSUER",
            "SAME_OIDC_CLIENT_ID",
            "SAME_OIDC_CLIENT_SECRET",
            "SAME_OIDC_BUTTON_LABEL",
            "SAME_OIDC_USER_GROUP",
            "SAME_OIDC_ADMIN_GROUP",
            "SAME_OIDC_SCOPES",
            "SAME_LOCAL_LOGIN",
            "SAME_PERSONAL_ACCOUNTS",
        )
    }
}
