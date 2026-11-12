package com.email.server.auth

import com.email.server.util.fetchString
import com.email.server.ApiError
import com.email.server.OidcSettings
import com.email.server.util.base64Url
import com.email.server.util.sha256
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Who the identity provider says signed in. */
data class OidcIdentity(
    val issuer: String,
    val subject: String,
    val username: String,
    val displayName: String,
    val email: String?,
    val groups: Set<String>,
)

/**
 * Sign-in through an OpenID Connect provider (Authentik, Keycloak, Authelia…) with the
 * authorization code flow and PKCE. The provider's ID token is checked here: its signature
 * (the provider's published keys, or the client secret for HS256), issuer, audience, expiry
 * and nonce.
 */
class OidcClient(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Discovery(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val jwksUri: String?,
        val userinfoEndpoint: String?,
        val endSessionEndpoint: String?,
    )

    private val discoveries = ConcurrentHashMap<String, Pair<Long, Discovery>>()
    private val keySets = ConcurrentHashMap<String, Pair<Long, JWKSet>>()

    suspend fun discover(issuer: String): Discovery {
        val key = issuer.trim()
        discoveries[key]?.let { (at, found) -> if (clock() - at < CACHE_MS) return found }
        val url = key.trimEnd('/') + "/.well-known/openid-configuration"
        val json = try {
            Json.parseToJsonElement(http.fetchString(Request.Builder().url(url).build())).jsonObject
        } catch (e: Exception) {
            throw ApiError.upstream("Couldn't read the provider's settings at $url (${e.message ?: e.javaClass.simpleName})")
        }
        val found = Discovery(
            issuer = json.string("issuer") ?: throw ApiError.upstream("The provider's settings have no issuer"),
            authorizationEndpoint = json.string("authorization_endpoint") ?: throw ApiError.upstream("The provider's settings have no authorization endpoint"),
            tokenEndpoint = json.string("token_endpoint") ?: throw ApiError.upstream("The provider's settings have no token endpoint"),
            jwksUri = json.string("jwks_uri"),
            userinfoEndpoint = json.string("userinfo_endpoint"),
            endSessionEndpoint = json.string("end_session_endpoint"),
        )
        if (found.issuer.trimEnd('/') != key.trimEnd('/')) {
            throw ApiError.upstream("The provider calls itself ${found.issuer}, not $key: check the issuer address")
        }
        discoveries[key] = clock() to found
        return found
    }

    fun authorizationUrl(
        discovery: Discovery,
        settings: OidcSettings,
        redirectUri: String,
        state: String,
        nonce: String,
        verifier: String,
    ): String = discovery.authorizationEndpoint.toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", settings.clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", scopes(settings))
        .addQueryParameter("state", state)
        .addQueryParameter("nonce", nonce)
        .addQueryParameter("code_challenge", challengeOf(verifier))
        .addQueryParameter("code_challenge_method", "S256")
        .build().toString()

    /** Trades the code from the provider's redirect for the person's identity. */
    suspend fun signIn(
        discovery: Discovery,
        settings: OidcSettings,
        clientSecret: String,
        code: String,
        redirectUri: String,
        verifier: String,
        nonce: String,
    ): OidcIdentity {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", verifier)
            .add("client_id", settings.clientId)
            .build()
        val request = Request.Builder().url(discovery.tokenEndpoint).post(form).apply {
            if (clientSecret.isNotEmpty()) header("Authorization", Credentials.basic(settings.clientId, clientSecret))
        }.header("Accept", "application/json").build()
        val tokens = try {
            Json.parseToJsonElement(http.fetchString(request)).jsonObject
        } catch (e: Exception) {
            throw ApiError.upstream("The provider didn't accept the sign-in (${e.message ?: e.javaClass.simpleName})")
        }
        val idToken = tokens.string("id_token") ?: throw ApiError.upstream("The provider sent no ID token (is the openid scope on?)")
        val claims = verifyIdToken(discovery, settings, clientSecret, idToken, nonce)

        var groups = groupsOf(claims.getClaim(settings.groupsClaim))
        val needsGroups = settings.userGroup.isNotBlank() || settings.adminGroup.isNotBlank()
        var userinfo: JsonObject? = null
        if (groups == null && needsGroups) {
            userinfo = fetchUserinfo(discovery, tokens.string("access_token"))
            groups = groupsOf(userinfo?.get(settings.groupsClaim))
        }
        fun claim(name: String): String? = (claims.getClaim(name) as? String)?.takeIf { it.isNotBlank() }
            ?: userinfo?.string(name)?.takeIf { it.isNotBlank() }

        val username = claim(settings.usernameClaim) ?: claim("preferred_username") ?: claim("email") ?: claims.subject
        return OidcIdentity(
            issuer = claims.issuer,
            subject = claims.subject,
            username = username,
            displayName = claim("name") ?: username,
            email = claim("email"),
            groups = groups.orEmpty(),
        )
    }

    suspend fun verifyIdToken(discovery: Discovery, settings: OidcSettings, clientSecret: String, idToken: String, nonce: String): JWTClaimsSet {
        val jwt = try {
            SignedJWT.parse(idToken)
        } catch (e: Exception) {
            throw ApiError.upstream("The provider's ID token isn't a signed token")
        }
        val algorithm = jwt.header.algorithm
        val verifier: JWSVerifier = when {
            JWSAlgorithm.Family.HMAC_SHA.contains(algorithm) -> {
                if (clientSecret.length < 32) throw ApiError.upstream("The provider signs with the client secret, which must be at least 32 characters")
                MACVerifier(clientSecret.toByteArray())
            }
            JWSAlgorithm.Family.RSA.contains(algorithm) -> RSASSAVerifier(key(discovery, jwt) as? RSAKey ?: throw noKey())
            JWSAlgorithm.Family.EC.contains(algorithm) -> ECDSAVerifier(key(discovery, jwt) as? ECKey ?: throw noKey())
            else -> throw ApiError.upstream("The provider signs ID tokens with $algorithm, which isn't supported")
        }
        if (!jwt.verify(verifier)) throw ApiError.upstream("The ID token's signature is wrong")

        val claims = jwt.jwtClaimsSet
        val now = clock()
        when {
            claims.issuer?.trimEnd('/') != discovery.issuer.trimEnd('/') -> throw ApiError.upstream("The ID token is from another issuer")
            claims.audience?.contains(settings.clientId) != true -> throw ApiError.upstream("The ID token is for another application")
            claims.audience.size > 1 && claims.getClaim("azp") != settings.clientId -> throw ApiError.upstream("The ID token is for another application")
            claims.expirationTime == null || claims.expirationTime.time < now - CLOCK_SKEW_MS -> throw ApiError.upstream("The ID token has expired (check the server's clock)")
            claims.issueTime != null && claims.issueTime.time > now + CLOCK_SKEW_MS -> throw ApiError.upstream("The ID token is from the future (check the server's clock)")
            claims.getClaim("nonce") != nonce -> throw ApiError.upstream("The ID token doesn't belong to this sign-in")
            claims.subject.isNullOrBlank() -> throw ApiError.upstream("The ID token doesn't say who signed in")
        }
        return claims
    }

    private fun noKey() = ApiError.upstream("The provider's signing key wasn't found in its published keys")

    private suspend fun key(discovery: Discovery, jwt: SignedJWT): JWK? {
        val uri = discovery.jwksUri ?: throw ApiError.upstream("The provider publishes no signing keys")
        val kid = jwt.header.keyID
        fun pick(set: JWKSet): JWK? =
            if (kid != null) set.getKeyByKeyId(kid) else set.keys.singleOrNull { it.keyType.value == jwt.header.algorithm.name.take(2).let { p -> if (p == "ES") "EC" else "RSA" } }
        val cached = keySets[uri]
        if (cached != null && clock() - cached.first < CACHE_MS) {
            pick(cached.second)?.let { return it }
            // A key we haven't seen: the provider may have rotated keys. Look again, at most once a minute.
            if (clock() - cached.first < MIN_REFRESH_MS) return null
        }
        val set = try {
            JWKSet.parse(http.fetchString(Request.Builder().url(uri).build()))
        } catch (e: Exception) {
            throw ApiError.upstream("Couldn't read the provider's signing keys (${e.message ?: e.javaClass.simpleName})")
        }
        keySets[uri] = clock() to set
        return pick(set)
    }

    private suspend fun fetchUserinfo(discovery: Discovery, accessToken: String?): JsonObject? {
        val endpoint = discovery.userinfoEndpoint ?: return null
        if (accessToken == null) return null
        return runCatching {
            Json.parseToJsonElement(
                http.fetchString(Request.Builder().url(endpoint).header("Authorization", "Bearer $accessToken").build()),
            ).jsonObject
        }.getOrNull()
    }

    companion object {
        private const val CACHE_MS = 60L * 60 * 1000
        private const val MIN_REFRESH_MS = 60L * 1000
        private const val CLOCK_SKEW_MS = 2L * 60 * 1000

        fun challengeOf(verifier: String): String = sha256(verifier).base64Url()

        fun scopes(settings: OidcSettings): String {
            val scopes = settings.scopes.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if ("openid" !in scopes) scopes.add(0, "openid")
            return scopes.distinct().joinToString(" ")
        }

        /** Groups as a list of names (Authentik), or a single string. */
        fun groupsOf(value: Any?): Set<String>? = when (value) {
            null -> null
            is String -> setOf(value)
            is Collection<*> -> value.mapNotNull { it?.toString() }.toSet()
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            is JsonPrimitive -> value.contentOrNull?.let { setOf(it) }
            else -> null
        }

        private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

        @Suppress("unused")
        private fun JsonElement.asObject() = this as? JsonObject
    }
}
