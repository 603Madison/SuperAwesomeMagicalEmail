package com.email.server.auth

import com.email.server.ApiError
import com.email.server.Services
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import com.email.api.AppCodeRequest
import com.email.api.AppCodeResponse
import com.email.api.AppTokenRequest
import com.email.api.AuthConfig
import com.email.api.LoginRequest
import com.email.api.Me
import com.email.api.OidcButton
import com.email.api.Ok
import com.email.api.Role
import com.email.api.SessionKind
import com.email.api.SignedIn
import com.email.api.User
import org.slf4j.LoggerFactory

@Serializable
data class ProfileUpdate(val displayName: String)

@Serializable
data class PasswordChange(val current: String, val new: String)

private val log = LoggerFactory.getLogger("same.auth")

private const val OIDC_STATE_COOKIE = "same_oidc"

fun Route.authRoutes(services: Services) {
    route("/api/auth") {
        get("/config") {
            val settings = services.settings.get()
            val oidc = settings.oidc
            call.respond(
                AuthConfig(
                    serverName = settings.serverName,
                    version = Services.version,
                    localLogin = settings.localLogin || !oidc.enabled,
                    oidc = if (oidc.enabled && oidc.issuer.isNotBlank() && oidc.clientId.isNotBlank()) OidcButton(oidc.buttonLabel) else null,
                ),
            )
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            val address = "ip:${clientAddress(call.request.local.remoteAddress, call.request.headers, services.config)}"
            val account = "user:${request.username.trim().lowercase()}"
            services.throttle.check(address, account)
            val user = services.users.checkPassword(request.username, request.password)
            if (user == null) {
                services.throttle.failed(address, account)
                throw ApiError.unauthorized("Wrong username or password")
            }
            services.throttle.succeeded(account)
            if (user.disabled) throw ApiError.forbidden("This account is turned off")
            val settings = services.settings.get()
            if (!settings.localLogin && settings.oidc.enabled && !user.breakGlass) {
                throw ApiError.forbidden("Sign in with ${settings.oidc.buttonLabel.removePrefix("Sign in with ")} instead")
            }
            call.respond(signIn(services, call, user, request.app, request.deviceName))
        }

        post("/logout") {
            val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
                ?: call.request.cookies[SESSION_COOKIE]
            call.principal(services) // checks the CSRF header for cookie sign-ins
            token?.let { services.sessions.revokeToken(it) }
            call.response.cookies.append(call.clearedSessionCookie())
            call.respond(Ok())
        }

        // The browser goes to the identity provider…
        get("/oidc/start") {
            val settings = services.settings.get()
            if (!settings.oidc.enabled) throw ApiError.notFound("Single sign-on isn't set up")
            val returnTo = safeReturn(call.request.queryParameters["return"])
            val discovery = services.oidc.discover(settings.oidc.issuer)
            val (state, pending) = services.pending.startOidc(returnTo)
            call.response.cookies.append(
                Cookie(
                    OIDC_STATE_COOKIE, state, CookieEncoding.RAW, maxAge = 600, path = "/api/auth/oidc",
                    secure = call.isHttps, httpOnly = true, extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            call.respondRedirect(
                services.oidc.authorizationUrl(discovery, settings.oidc, redirectUri(services, call), state, pending.nonce, pending.verifier),
            )
        }

        // …and comes back here.
        get("/oidc/callback") {
            val params = call.request.queryParameters
            val state = params["state"].orEmpty()
            call.response.cookies.append(
                Cookie(OIDC_STATE_COOKIE, "", CookieEncoding.RAW, maxAge = 0, path = "/api/auth/oidc", secure = call.isHttps, httpOnly = true),
            )
            fun fail(message: String) = "/login?error=${message.encodeURLParameter()}"
            // The state must match the cookie set when this browser started, so nobody can sign
            // someone else's browser in to their own account.
            val pending = if (state.isNotEmpty() && call.request.cookies[OIDC_STATE_COOKIE] == state) services.pending.finishOidc(state) else null
            if (pending == null) return@get call.respondRedirect(fail("That sign-in expired or came from another browser. Try again."))
            params["error"]?.let { error ->
                return@get call.respondRedirect(fail(params["error_description"] ?: "The identity provider said: $error"))
            }
            val code = params["code"] ?: return@get call.respondRedirect(fail("The identity provider sent no sign-in code"))
            val target = try {
                val user = oidcUser(services, call, code, pending)
                services.users.touchLogin(user.id)
                val token = services.sessions.create(user.id, SessionKind.WEB, deviceName(call))
                call.response.cookies.append(call.sessionCookie(token, SessionStore.WEB_LIFETIME_MS / 1000))
                pending.returnTo
            } catch (e: ApiError) {
                log.warn("Single sign-on failed: {}", e.message)
                fail(e.message ?: "Sign-in failed")
            }
            call.respondRedirect(target)
        }

        // The app's sign-in: the person signs in to the web page in a browser tab the app opened,
        // agrees, and the page hands the app a one-time code for the app's own token.
        post("/app/code") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            if (!principal.viaCookie) throw ApiError.forbidden("Approve the app from a browser")
            val challenge = call.receive<AppCodeRequest>().challenge
            if (!challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) throw ApiError.badRequest("That isn't a PKCE challenge")
            call.respond(AppCodeResponse(services.pending.issueAppCode(principal.user.id, challenge)))
        }

        post("/app/token") {
            val request = call.receive<AppTokenRequest>()
            val userId = services.pending.redeemAppCode(request.code, request.verifier)
                ?: throw ApiError.unauthorized("That sign-in code expired; sign in again")
            val user = services.users.byId(userId)?.takeIf { !it.disabled } ?: throw ApiError.unauthorized("This account is turned off")
            call.respond(signIn(services, call, user, app = true, request.deviceName))
        }
    }

    route("/api/me") {
        get {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            call.respond(Me(principal.user, principal.session))
        }
        patch {
            val user = call.requireUser(services)
            call.respond(services.users.update(user.id, displayName = call.receive<ProfileUpdate>().displayName))
        }
        post("/password") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            val change = call.receive<PasswordChange>()
            val address = "ip:${clientAddress(call.request.local.remoteAddress, call.request.headers, services.config)}"
            val account = "user:${principal.user.username.lowercase()}"
            services.throttle.check(address, account)
            if (!principal.user.hasPassword) throw ApiError.badRequest("This account signs in through single sign-on")
            if (services.users.checkPassword(principal.user.username, change.current) == null) {
                services.throttle.failed(address, account)
                throw ApiError.badRequest("The current password is wrong")
            }
            services.users.setPassword(principal.user.id, change.new)
            // Signs out everywhere else.
            services.sessions.list(principal.user.id).filter { it.id != principal.session.id }.forEach { services.sessions.revoke(principal.user.id, it.id) }
            call.respond(Ok())
        }
        get("/sessions") {
            val principal = call.principal(services) ?: throw ApiError.unauthorized()
            call.respond(services.sessions.list(principal.user.id, principal.session.id))
        }
        delete("/sessions/{id}") {
            val user = call.requireUser(services)
            if (!services.sessions.revoke(user.id, call.parameters["id"].orEmpty())) throw ApiError.notFound()
            call.respond(Ok())
        }
    }
}

private suspend fun signIn(services: Services, call: ApplicationCall, user: User, app: Boolean, deviceName: String): SignedIn {
    services.users.touchLogin(user.id)
    return if (app) {
        val token = services.sessions.create(user.id, SessionKind.APP, deviceName.ifBlank { "SuperAwesomeMagicalEmail app" })
        SignedIn(user, token)
    } else {
        val token = services.sessions.create(user.id, SessionKind.WEB, deviceName(call))
        call.response.cookies.append(call.sessionCookie(token, SessionStore.WEB_LIFETIME_MS / 1000))
        SignedIn(user)
    }
}

/** Finds or makes the account for the person the provider signed in. */
private suspend fun oidcUser(services: Services, call: ApplicationCall, code: String, pending: PendingLogins.OidcState): User {
    val settings = services.settings.get().oidc
    val discovery = services.oidc.discover(settings.issuer)
    val identity = services.oidc.signIn(
        discovery, settings, services.settings.oidcClientSecret(), code, redirectUri(services, call), pending.verifier, pending.nonce,
    )
    if (settings.userGroup.isNotBlank() && settings.userGroup !in identity.groups && settings.adminGroup !in identity.groups) {
        throw ApiError.forbidden("Your account isn't in the group allowed to use this server (${settings.userGroup})")
    }
    val role = when {
        settings.adminGroup.isBlank() -> null
        settings.adminGroup in identity.groups -> Role.ADMIN
        else -> Role.USER
    }
    val existing = services.users.byOidc(identity.issuer, identity.subject)
    if (existing != null) {
        if (existing.disabled) throw ApiError.forbidden("This account is turned off")
        return if (role != null && role != existing.role) services.users.update(existing.id, role = role) else existing
    }
    // An admin may have added them already (by username or email), with their mailboxes ready.
    services.users.claimable(identity.username, identity.email)?.let { waiting ->
        if (waiting.disabled) throw ApiError.forbidden("This account is turned off")
        val linked = services.users.linkOidc(waiting.id, identity.issuer, identity.subject, identity.email, identity.displayName)
        log.info("Linked single sign-on for {} to the account {} an admin made", identity.username, linked.username)
        return if (role != null && role != linked.role) services.users.update(linked.id, role = role) else linked
    }
    if (!settings.autoCreateUsers) throw ApiError.forbidden("There's no account for you on this server yet; ask an admin")
    val user = services.users.create(
        username = services.users.freeUsername(identity.username),
        displayName = identity.displayName,
        role = role ?: Role.USER,
        oidcIssuer = identity.issuer,
        oidcSubject = identity.subject,
        email = identity.email?.takeIf { email -> runCatching { UserStore.validEmail(email) }.isSuccess && services.users.emailFree(email) },
    )
    log.info("Created account {} for {} from single sign-on", user.username, identity.username)
    return user
}

/** The address the identity provider sends people back to; the admin page shows it to register. */
suspend fun redirectUri(services: Services, call: ApplicationCall): String = "${publicBase(services, call)}/api/auth/oidc/callback"

suspend fun publicBase(services: Services, call: ApplicationCall): String {
    services.settings.get().publicUrl.trimEnd('/').takeIf { it.isNotBlank() }?.let { return it }
    val origin = call.request.origin
    val defaultPort = (origin.scheme == "https" && origin.serverPort == 443) || (origin.scheme == "http" && origin.serverPort == 80)
    return "${origin.scheme}://${origin.serverHost}${if (defaultPort) "" else ":${origin.serverPort}"}"
}

/** Only places on this server: never another site (an open redirect). */
private fun safeReturn(path: String?): String =
    path?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/\\") && !it.contains('\n') && !it.contains('\r') } ?: "/"

/** "Firefox on Windows" and the like, to tell sign-ins apart. */
fun deviceName(call: ApplicationCall): String {
    val agent = call.request.header(HttpHeaders.UserAgent).orEmpty()
    val browser = when {
        "Edg/" in agent -> "Edge"
        "OPR/" in agent -> "Opera"
        "Firefox/" in agent -> "Firefox"
        "Chrome/" in agent -> "Chrome"
        "Safari/" in agent -> "Safari"
        else -> "Browser"
    }
    val system = when {
        "Windows" in agent -> "Windows"
        "Android" in agent -> "Android"
        "iPhone" in agent || "iPad" in agent -> "iOS"
        "Mac OS" in agent -> "macOS"
        "CrOS" in agent -> "ChromeOS"
        "Linux" in agent -> "Linux"
        else -> null
    }
    return if (system != null) "$browser on $system" else browser
}
