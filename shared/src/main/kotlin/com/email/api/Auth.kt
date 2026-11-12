package com.email.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How the server and apps read and write JSON: unknown fields are skipped, so either can be newer. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
enum class Role { ADMIN, USER }

@Serializable
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    /** Used, with the username, to find the account an admin made for someone before they first sign in. */
    val email: String? = null,
    val role: Role,
    /** The local admin made at first start, which can always sign in with its password. */
    val breakGlass: Boolean = false,
    val disabled: Boolean = false,
    /** Signs in through the identity provider (Authentik etc.). */
    val oidc: Boolean = false,
    val hasPassword: Boolean = false,
    val createdAt: Long,
    val lastLoginAt: Long? = null,
    /** Added by an admin to sign in through single sign-on, and hasn't yet. */
    val waitingForSignIn: Boolean = false,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN
}

/** A browser, or one of the apps (phone or desktop). */
@Serializable
enum class SessionKind { WEB, APP }

/** A signed-in browser or app, as shown in the account's list of devices. */
@Serializable
data class SessionInfo(
    val id: String,
    val kind: SessionKind,
    val name: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val expiresAt: Long,
    val current: Boolean = false,
)

@Serializable
data class OidcButton(val label: String)

@Serializable
data class AuthConfig(
    val serverName: String,
    val version: String,
    /** Whether the password form is offered (the break-glass admin can always use it). */
    val localLogin: Boolean,
    val oidc: OidcButton? = null,
)

@Serializable
data class LoginRequest(val username: String, val password: String, val app: Boolean = false, val deviceName: String = "")

/** A sign-in: browsers get a cookie, the apps get [token]. */
@Serializable
data class SignedIn(val user: User, val token: String? = null)

@Serializable
data class AppCodeRequest(val challenge: String)

@Serializable
data class AppCodeResponse(val code: String)

@Serializable
data class AppTokenRequest(val code: String, val verifier: String, val deviceName: String = "")

@Serializable
data class Me(val user: User, val session: SessionInfo)

@Serializable
data class ErrorBody(val error: String)

@Serializable
data class Ok(val ok: Boolean = true)
