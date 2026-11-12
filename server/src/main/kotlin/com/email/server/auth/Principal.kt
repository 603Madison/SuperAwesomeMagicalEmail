package com.email.server.auth

import com.email.server.ApiError
import com.email.server.ServerConfig
import com.email.server.Services
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.util.AttributeKey
import com.email.api.SessionInfo
import com.email.api.User
import java.util.concurrent.ConcurrentHashMap

/** The signed-in person making a request, and the sign-in they used. */
data class Principal(val user: User, val session: SessionInfo, val viaCookie: Boolean)

private class Resolved(val principal: Principal?)

private val PrincipalKey = AttributeKey<Resolved>("same.principal")

const val SESSION_COOKIE = "same_session"

/** The header the web app sends with every change, which other sites' pages can't send. */
const val CSRF_HEADER = "X-SAME"

/**
 * Who's asking: from the session cookie (the web app) or an `Authorization: Bearer` token
 * (the phone and desktop apps). Changes made with a cookie must carry [CSRF_HEADER], which a page on another site
 * can't add, so another site can't act for a signed-in person.
 */
suspend fun ApplicationCall.principal(services: Services): Principal? {
    attributes.getOrNull(PrincipalKey)?.let { return it.principal }
    val bearer = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    val cookie = request.cookies[SESSION_COOKIE]
    val token = bearer ?: cookie
    val found = token?.let { services.sessions.resolve(it) }
        ?.let { (user, session) -> Principal(user, session, viaCookie = bearer == null) }
    if (found != null && found.viaCookie && request.local.method !in SAFE_METHODS && request.header(CSRF_HEADER) == null) {
        throw ApiError.forbidden("Missing $CSRF_HEADER header")
    }
    attributes.put(PrincipalKey, Resolved(found))
    return found
}

suspend fun ApplicationCall.requireUser(services: Services): User =
    principal(services)?.user ?: throw ApiError.unauthorized()

suspend fun ApplicationCall.requireAdmin(services: Services): User =
    requireUser(services).also { if (!it.isAdmin) throw ApiError.forbidden() }

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/** Whether the person reached the server over HTTPS (directly, or through a trusted proxy). */
val ApplicationCall.isHttps: Boolean get() = request.origin.scheme == "https"

fun ApplicationCall.sessionCookie(token: String, maxAgeSeconds: Long) = Cookie(
    name = SESSION_COOKIE,
    value = token,
    encoding = CookieEncoding.RAW,
    maxAge = maxAgeSeconds.toInt(),
    path = "/",
    secure = isHttps,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

fun ApplicationCall.clearedSessionCookie() = Cookie(
    name = SESSION_COOKIE,
    value = "",
    encoding = CookieEncoding.RAW,
    maxAge = 0,
    path = "/",
    secure = isHttps,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

/**
 * Where a request came from, for slowing down password guessing. Behind a reverse proxy, the
 * address the proxy saw: the header it sets ([ServerConfig.clientIpHeader], like Cloudflare's
 * CF-Connecting-IP), else the X-Forwarded-For entry added by the first of [ServerConfig.proxies]
 * proxies, counted from the end. Never simply the first entry: whoever sends the request can
 * write that one, and so get round the limit or use up someone else's.
 */
fun clientAddress(direct: String, headers: Headers, config: ServerConfig): String {
    if (!config.trustProxy) return direct
    config.clientIpHeader?.let { name -> headers[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it } }
    val chain = headers.getAll("X-Forwarded-For").orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    return chain.getOrNull((chain.size - config.proxies).coerceAtLeast(0)) ?: direct
}

/**
 * Slows down password guessing: after [maxFailures] wrong passwords for an account, or
 * [maxFailuresPerAddress] from one address (a reverse proxy can make everyone one address),
 * within [windowMs], more attempts are refused until the window passes.
 */
class LoginThrottle(
    private val maxFailures: Int = 10,
    private val maxFailuresPerAddress: Int = 50,
    private val windowMs: Long = 15L * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failures = ConcurrentHashMap<String, List<Long>>()

    fun check(vararg keys: String) {
        val now = clock()
        if (keys.any { key -> recent(key, now) >= if (key.startsWith("ip:")) maxFailuresPerAddress else maxFailures }) {
            throw ApiError.tooMany("Too many wrong passwords. Try again in a few minutes.")
        }
    }

    fun failed(vararg keys: String) {
        val now = clock()
        keys.forEach { key -> failures.compute(key) { _, list -> list.orEmpty().filter { now - it <= windowMs } + now } }
        if (failures.size > 10_000) failures.entries.removeIf { (_, list) -> list.all { now - it > windowMs } }
    }

    fun succeeded(vararg keys: String) = keys.forEach { failures.remove(it) }

    private fun recent(key: String, now: Long): Int = failures[key]?.count { now - it <= windowMs } ?: 0
}
