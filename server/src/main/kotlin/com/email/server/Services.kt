package com.email.server

import com.email.api.Role
import com.email.server.auth.LoginThrottle
import com.email.server.auth.OidcClient
import com.email.server.auth.PendingLogins
import com.email.server.auth.SessionStore
import com.email.server.auth.UserStore
import com.email.server.db.Database
import com.email.server.db.Schema
import com.email.server.mail.AccountStore
import com.email.server.mail.Composer
import com.email.server.mail.ContactStore
import com.email.server.mail.Links
import com.email.server.mail.MailConnections
import com.email.server.mail.MailService
import com.email.server.mail.UploadStore
import com.email.server.util.Http
import com.email.server.util.Passwords
import com.email.server.util.Random
import com.email.server.util.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.Properties

/** Everything the server is made of, built once at start-up. */
class Services(
    val config: ServerConfig,
    val db: Database,
    val box: SecretBox,
    val http: OkHttpClient = Http.createClient(),
    passwords: Passwords = Passwords(),
    val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val users = UserStore(db, passwords, clock)
    val sessions = SessionStore(db, clock)
    val settings = SettingsStore(db, box)
    val oidc = OidcClient(http, clock)
    val pending = PendingLogins(clock)
    val throttle = LoginThrottle(clock = clock)
    val accounts = AccountStore(db, box, clock)
    val connections = MailConnections(config.allowPrivateMailHosts, scope, clock) { id, problem -> accounts.setProblem(id, problem) }
    val links = Links(box, clock)
    val mail = MailService(accounts, connections, db, links)
    val uploads = UploadStore(db, config.uploadsDir, clock)
    val contacts = ContactStore(db, clock)
    val composer = Composer(accounts, connections, mail, uploads, contacts, config.maxMessageBytes, version, clock)
    val imageHttp: OkHttpClient = Links.proxyClient(http)

    override fun close() {
        scope.cancel()
        connections.closeAll()
        db.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("same")

        val version: String by lazy {
            runCatching {
                Properties().apply { Services::class.java.getResourceAsStream("/same-version.properties")?.use(::load) }.getProperty("version")
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("\$") } ?: "dev"
        }

        init {
            // Attachments named in other alphabets: read the way mail programs write them, and written so they read them.
            System.setProperty("mail.mime.decodefilename", "true")
            System.setProperty("mail.mime.encodefilename", "true")
            System.setProperty("mail.mime.decodeparameters", "true")
            System.setProperty("mail.mime.encodeparameters", "true")
        }

        /** Opens the data folder and database. */
        fun open(config: ServerConfig): Services {
            config.dataDir.mkdirs()
            val db = Database.file(config.databaseFile.path)
            Schema.migrate(db)
            return Services(config, db, SecretBox.fromFile(config.secretKeyFile))
        }
    }

    /** First start: makes the break-glass admin. Every start: applies settings given as environment variables. */
    suspend fun bootstrap() {
        if (users.count() == 0L) {
            val password = config.adminPassword ?: Random.password()
            users.create(config.adminUsername, "Administrator", Role.ADMIN, password, breakGlass = true)
            if (config.adminPassword == null) {
                log.warn(
                    "\n\n  Created the break-glass admin account.\n  Username: {}\n  Password: {}\n" +
                        "  This is shown once. Sign in and keep it somewhere safe; reset it with `same-server reset-admin-password`.\n",
                    config.adminUsername, password,
                )
            } else {
                log.info("Created the break-glass admin account {} with the password from SAME_ADMIN_PASSWORD", config.adminUsername)
            }
        }
        val env = config.overrides
        if (env.isNotEmpty()) {
            fun flag(name: String): Boolean? = env[name]?.let { it.lowercase() in setOf("1", "true", "yes") }
            settings.update { current ->
                current.copy(
                    publicUrl = env["SAME_PUBLIC_URL"]?.trimEnd('/') ?: current.publicUrl,
                    localLogin = flag("SAME_LOCAL_LOGIN") ?: current.localLogin,
                    personalAccounts = flag("SAME_PERSONAL_ACCOUNTS") ?: current.personalAccounts,
                    oidc = current.oidc.copy(
                        enabled = if (env["SAME_OIDC_ISSUER"] != null && env["SAME_OIDC_CLIENT_ID"] != null) true else current.oidc.enabled,
                        issuer = env["SAME_OIDC_ISSUER"] ?: current.oidc.issuer,
                        clientId = env["SAME_OIDC_CLIENT_ID"] ?: current.oidc.clientId,
                        buttonLabel = env["SAME_OIDC_BUTTON_LABEL"] ?: current.oidc.buttonLabel,
                        userGroup = env["SAME_OIDC_USER_GROUP"] ?: current.oidc.userGroup,
                        adminGroup = env["SAME_OIDC_ADMIN_GROUP"] ?: current.oidc.adminGroup,
                        scopes = env["SAME_OIDC_SCOPES"] ?: current.oidc.scopes,
                    ),
                )
            }
            env["SAME_OIDC_CLIENT_SECRET"]?.let { settings.setOidcClientSecret(it) }
        }
        sessions.purgeExpired()
        uploads.purge()
    }

    /** Housekeeping while the server runs: idle mail connections closed, old uploads and sign-ins cleared. */
    fun startHousekeeping() {
        scope.launch {
            var tick = 0L
            while (isActive) {
                delay(60_000)
                runCatching { connections.closeIdle() }
                if (++tick % 60 == 0L) {
                    runCatching { uploads.purge() }
                    runCatching { sessions.purgeExpired() }
                }
            }
        }
    }

    /** Sets a new password for the break-glass admin (from the command line) and returns it. */
    suspend fun resetBreakGlass(password: String?): Pair<String, String> {
        val admin = users.breakGlass() ?: users.create(config.adminUsername, "Administrator", Role.ADMIN, Random.password(), breakGlass = true)
        val newPassword = password ?: Random.password()
        users.setPassword(admin.id, newPassword)
        users.update(admin.id, role = Role.ADMIN, disabled = false)
        sessions.revokeAll(admin.id)
        return admin.username to newPassword
    }
}
