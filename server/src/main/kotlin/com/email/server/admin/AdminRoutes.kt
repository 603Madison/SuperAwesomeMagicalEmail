package com.email.server.admin

import com.email.api.AccountInput
import com.email.api.Mailbox
import com.email.api.MembersChange
import com.email.api.Ok
import com.email.api.Role
import com.email.api.User
import com.email.server.ApiError
import com.email.server.OidcSettings
import com.email.server.ServerConfig
import com.email.server.ServerSettings
import com.email.server.Services
import com.email.server.auth.redirectUri
import com.email.server.auth.requireAdmin
import com.email.server.mail.StoredAccount
import com.email.server.mail.checkOrRefuse
import com.email.server.mail.resolveAccount
import com.email.server.util.RecentLog
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Someone to add. With a [password] they sign in with it; without one, through single sign-on,
 * and the account waits for them: their first sign-in with that username (or email) is theirs.
 */
@Serializable
data class NewUser(val username: String, val displayName: String = "", val email: String = "", val password: String? = null, val role: Role = Role.USER)

@Serializable
data class UserChange(val displayName: String? = null, val role: Role? = null, val disabled: Boolean? = null, val email: String? = null)

@Serializable
data class PasswordReset(val password: String)

/** A person, with the admins' mailboxes they read. */
@Serializable
data class Person(val user: User, val mailboxes: List<String>)

@Serializable
data class AdminSettings(
    val settings: ServerSettings,
    val oidcClientSecretSet: Boolean,
    /** The address to register with the identity provider as the redirect URI. */
    val redirectUri: String,
    /** Settings fixed by environment variables, which win at every start. */
    val fromEnvironment: List<String>,
    val version: String,
)

@Serializable
data class SettingsChange(
    val settings: ServerSettings,
    /** Null keeps the saved secret; empty removes it. */
    val oidcClientSecret: String? = null,
)

@Serializable
data class OidcTest(val ok: Boolean, val message: String)

fun Route.adminRoutes(services: Services) {
    route("/api/admin") {
        route("/users") {
            get {
                call.requireAdmin(services)
                val mailboxes = services.accounts.managed().associate { it.id to services.accounts.members(it.id).map { m -> m.userId } }
                call.respond(services.users.list().map { user -> Person(user, mailboxes.filterValues { user.id in it }.keys.toList()) })
            }
            post {
                call.requireAdmin(services)
                val request = call.receive<NewUser>()
                val user = services.users.create(
                    username = request.username,
                    displayName = request.displayName,
                    role = request.role,
                    password = request.password?.takeIf { it.isNotEmpty() },
                    email = request.email,
                )
                call.respond(Person(user, emptyList()))
            }
            route("/{id}") {
                patch {
                    val admin = call.requireAdmin(services)
                    val id = call.parameters["id"].orEmpty()
                    val change = call.receive<UserChange>()
                    if (id == admin.id && (change.role == Role.USER || change.disabled == true)) {
                        throw ApiError.badRequest("You can't demote or turn off your own account")
                    }
                    val updated = services.users.update(
                        id, change.displayName, change.role, change.disabled,
                        email = change.email?.takeIf { it.isNotBlank() }, clearEmail = change.email?.isBlank() == true,
                    )
                    if (updated.disabled) services.sessions.revokeAll(updated.id)
                    call.respond(updated)
                }
                delete {
                    val admin = call.requireAdmin(services)
                    val id = call.parameters["id"].orEmpty()
                    if (id == admin.id) throw ApiError.badRequest("You can't delete your own account")
                    services.users.delete(id)
                    call.respond(Ok())
                }
                post("/password") {
                    call.requireAdmin(services)
                    val id = call.parameters["id"].orEmpty()
                    val user = services.users.byId(id) ?: throw ApiError.notFound("No such person")
                    if (user.oidc && !user.hasPassword) throw ApiError.badRequest("${user.username} signs in through single sign-on")
                    services.users.setPassword(id, call.receive<PasswordReset>().password)
                    services.sessions.revokeAll(id)
                    call.respond(Ok())
                }
                delete("/sessions") {
                    call.requireAdmin(services)
                    services.sessions.revokeAll(call.parameters["id"].orEmpty())
                    call.respond(Ok())
                }
            }
        }

        // Mailboxes the admins set up (at PurelyMail, say) and give to people.
        route("/mailboxes") {
            get {
                val admin = call.requireAdmin(services)
                call.respond(services.accounts.managed().map { mailbox(services, admin, it) })
            }
            post {
                val admin = call.requireAdmin(services)
                val input = call.receive<AccountInput>()
                val settings = resolveAccount(input, null, "")
                val password = settings.password ?: throw ApiError.badRequest("What's the mailbox's password?")
                checkOrRefuse(services, settings, password, input.skipCheck)
                val created = services.accounts.create(
                    managed = true, createdBy = admin.id, provider = settings.provider, email = settings.email, name = settings.name,
                    label = settings.label, username = settings.username, password = password, imap = settings.imap, smtp = settings.smtp,
                    saveSent = settings.saveSent, members = input.members.orEmpty(),
                )
                call.respond(mailbox(services, admin, created))
            }
            post("/check") {
                call.requireAdmin(services)
                val settings = resolveAccount(call.receive<AccountInput>(), null, "")
                call.respond(services.connections.check(settings.username, settings.password ?: throw ApiError.badRequest("What's the password?"), settings.imap, settings.smtp))
            }
            route("/{id}") {
                patch {
                    val admin = call.requireAdmin(services)
                    val current = managed(services, call)
                    val input = call.receive<AccountInput>()
                    val settings = resolveAccount(input, current, "")
                    val changesSignIn = settings.password != null || settings.imap != current.imap || settings.smtp != current.smtp ||
                        settings.username != current.username
                    if (changesSignIn) checkOrRefuse(services, settings, settings.password ?: current.password, input.skipCheck)
                    val updated = services.accounts.update(
                        current.id, settings.provider, settings.email, settings.name, settings.label, settings.username, settings.password,
                        settings.imap, settings.smtp, settings.saveSent,
                    )
                    input.members?.let { services.accounts.setMembers(current.id, it) }
                    services.connections.forget(current.id)
                    call.respond(mailbox(services, admin, updated))
                }
                put("/members") {
                    val admin = call.requireAdmin(services)
                    val current = managed(services, call)
                    services.accounts.setMembers(current.id, call.receive<MembersChange>().userIds)
                    call.respond(mailbox(services, admin, current))
                }
                post("/check") {
                    call.requireAdmin(services)
                    val current = managed(services, call)
                    call.respond(services.connections.check(current.username, current.password, current.imap, current.smtp))
                }
                delete {
                    call.requireAdmin(services)
                    val current = managed(services, call)
                    services.accounts.delete(current.id)
                    services.connections.forget(current.id)
                    call.respond(Ok())
                }
            }
        }

        get("/settings") {
            call.requireAdmin(services)
            call.respond(adminSettings(services, call))
        }

        put("/settings") {
            call.requireAdmin(services)
            val change = call.receive<SettingsChange>()
            val next = validated(change.settings)
            services.settings.update { next }
            change.oidcClientSecret?.let { services.settings.setOidcClientSecret(it.trim()) }
            call.respond(adminSettings(services, call))
        }

        post("/oidc/test") {
            call.requireAdmin(services)
            val oidc = call.receive<OidcSettings>()
            val result = try {
                val discovery = services.oidc.discover(oidc.issuer)
                OidcTest(true, "Found ${discovery.issuer}. Register this redirect URI with it: ${redirectUri(services, call)}")
            } catch (e: ApiError) {
                OidcTest(false, e.message ?: "Couldn't reach the provider")
            }
            call.respond(result)
        }

        // What the server logged lately (mail servers refusing, sign-ins failing), newest last.
        get("/log") {
            call.requireAdmin(services)
            call.respond(RecentLog.latest(call.request.queryParameters["warnings"] == "1").takeLast(500))
        }
    }
}

private suspend fun managed(services: Services, call: ApplicationCall): StoredAccount =
    services.accounts.get(call.parameters["id"].orEmpty())?.takeIf { it.managed } ?: throw ApiError.notFound("No such mailbox")

private suspend fun mailbox(services: Services, admin: User, account: StoredAccount) =
    Mailbox(account.view(admin), services.accounts.members(account.id), account.createdAt)

private suspend fun adminSettings(services: Services, call: ApplicationCall) = AdminSettings(
    settings = services.settings.get(),
    oidcClientSecretSet = services.settings.oidcClientSecret().isNotEmpty(),
    redirectUri = redirectUri(services, call),
    fromEnvironment = services.config.overrides.keys.filter { it in ServerConfig.OVERRIDES }.sorted(),
    version = Services.version,
)

private fun validated(settings: ServerSettings): ServerSettings {
    val publicUrl = settings.publicUrl.trim().trimEnd('/')
    if (publicUrl.isNotEmpty() && !publicUrl.startsWith("https://") && !publicUrl.startsWith("http://")) {
        throw ApiError.badRequest("The public address must start with https:// (or http://)")
    }
    val oidc = settings.oidc
    if (oidc.enabled && (oidc.issuer.isBlank() || oidc.clientId.isBlank())) {
        throw ApiError.badRequest("Single sign-on needs the issuer address and client ID")
    }
    if (oidc.issuer.isNotBlank() && !oidc.issuer.trim().startsWith("https://") && !oidc.issuer.trim().startsWith("http://")) {
        throw ApiError.badRequest("The issuer must be a web address")
    }
    return settings.copy(
        serverName = settings.serverName.trim().take(80).ifBlank { "SuperAwesomeMagicalEmail" },
        publicUrl = publicUrl,
        oidc = oidc.copy(
            issuer = oidc.issuer.trim(),
            clientId = oidc.clientId.trim(),
            buttonLabel = oidc.buttonLabel.trim().take(60).ifBlank { "Sign in with single sign-on" },
            usernameClaim = oidc.usernameClaim.trim().ifBlank { "preferred_username" },
            groupsClaim = oidc.groupsClaim.trim().ifBlank { "groups" },
            userGroup = oidc.userGroup.trim(),
            adminGroup = oidc.adminGroup.trim(),
        ),
    )
}
