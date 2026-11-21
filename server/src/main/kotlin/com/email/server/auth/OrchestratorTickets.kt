package com.email.server.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks SuperAwesomeMagicalOrchestrator's sign-in tickets. This file is the same in every one of
 * the family's servers (only the package line differs): it's kept in SAM-DeviceOrchestrator,
 * `server/src/main/kotlin/com/orchestrator/server/kit/`, with its tests; change it there and copy.
 *
 * A ticket is a JWT (ES256) the orchestrator signs for a device where someone is signed in. It's
 * accepted when it's signed by a key the orchestrator at [orchestratorUrl] publishes, was made
 * for this server ([audience]: its public address) and this app ([app]), is in date (two minutes
 * at most), and hasn't been used here before.
 *
 * [fetch] gets a URL's text (use the server's own HTTP client, with its usual limits).
 */
class OrchestratorTickets(
    private val fetch: suspend (url: String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Who a ticket says is signed in. */
    data class Claims(
        /** The person's id on the orchestrator. */
        val subject: String,
        val username: String,
        val displayName: String,
        val email: String?,
        /** Who they are at the identity provider the orchestrator uses (null for a password account there). */
        val oidcIssuer: String?,
        val oidcSubject: String?,
        val groups: Set<String>,
        val deviceName: String,
    )

    class Refused(message: String) : Exception(message)

    private class Keys(val set: JWKSet, val fetchedAt: Long)

    private val keys = ConcurrentHashMap<String, Keys>()
    private val used = ConcurrentHashMap<String, Long>()

    /**
     * Who [ticket] is about, or [Refused] with why not (in words for a log, not for the person).
     * [audiences]: every address this server answers to (its public address; the one the request
     * came to).
     */
    suspend fun verify(ticket: String, orchestratorUrl: String, audiences: Collection<String>, app: String): Claims {
        val issuer = normal(orchestratorUrl)
        if (issuer.isEmpty()) throw Refused("No orchestrator is set up")
        if (ticket.length > MAX_TICKET) throw Refused("That isn't a ticket")
        val jwt = try {
            SignedJWT.parse(ticket)
        } catch (e: java.text.ParseException) {
            throw Refused("That isn't a ticket")
        }
        // Only the algorithm the orchestrator signs with: never "none", never a shared-secret one.
        if (jwt.header.algorithm != JWSAlgorithm.ES256) throw Refused("The ticket isn't signed the way the orchestrator signs")
        if (jwt.header.type?.type != TYPE) throw Refused("That isn't a sign-in ticket")
        val keyId = jwt.header.keyID ?: throw Refused("The ticket doesn't say which key signed it")
        val key = key(issuer, keyId) ?: throw Refused("The orchestrator doesn't publish the key that signed the ticket")
        val verified = try {
            jwt.verify(ECDSAVerifier(key))
        } catch (e: com.nimbusds.jose.JOSEException) {
            false
        }
        if (!verified) throw Refused("The ticket's signature is wrong")

        val claims = try {
            jwt.jwtClaimsSet
        } catch (e: java.text.ParseException) {
            throw Refused("That isn't a ticket")
        }
        val now = clock()
        if (normal(claims.issuer.orEmpty()) != issuer) throw Refused("The ticket is from another orchestrator")
        val mine = audiences.map(::normal).filter { it.isNotEmpty() }.toSet()
        if (claims.audience.orEmpty().none { normal(it) in mine }) throw Refused("The ticket was made for another server (${claims.audience.orEmpty().joinToString()}), not ${mine.joinToString()}")
        if (claims.getClaim("app") != app) throw Refused("The ticket was made for another app")
        val expires = claims.expirationTime?.time ?: throw Refused("The ticket has no end")
        val issued = claims.issueTime?.time ?: throw Refused("The ticket has no start")
        if (now > expires + SKEW_MS) throw Refused("The ticket has expired")
        if (issued > now + SKEW_MS || (claims.notBeforeTime?.time ?: 0) > now + SKEW_MS) throw Refused("The ticket is from the future (check both servers' clocks)")
        // However long the orchestrator says: a ticket is for signing in now.
        if (expires - issued > MAX_LIFETIME_MS) throw Refused("The ticket lasts too long")
        val id = claims.jwtid?.takeIf { it.isNotBlank() } ?: throw Refused("The ticket has no id")
        val subject = claims.subject?.takeIf { it.isNotBlank() } ?: throw Refused("The ticket doesn't say who it's for")
        val username = string(claims.getClaim("preferred_username")) ?: throw Refused("The ticket doesn't say who it's for")

        // Once only: whoever overhears a ticket can't use it again.
        synchronized(used) {
            used.entries.removeIf { it.value < now }
            if (used.size >= MAX_USED) throw Refused("Too many sign-ins at once")
            if (used.putIfAbsent("$issuer $id", expires + SKEW_MS) != null) throw Refused("The ticket was already used")
        }
        return Claims(
            subject = subject,
            username = username,
            displayName = string(claims.getClaim("name")) ?: username,
            email = string(claims.getClaim("email")),
            oidcIssuer = string(claims.getClaim("oidc_iss")),
            oidcSubject = string(claims.getClaim("oidc_sub")),
            groups = (claims.getClaim("groups") as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
            deviceName = string(claims.getClaim("device_name")).orEmpty().take(64),
        )
    }

    /** Whether the orchestrator at [orchestratorUrl] answers and publishes keys: for an admin page's "Test" button. */
    suspend fun check(orchestratorUrl: String): String {
        val issuer = normal(orchestratorUrl)
        val set = load(issuer) ?: throw Refused("No orchestrator answered at $issuer")
        return "Found the orchestrator at $issuer (${set.keys.size} signing key${if (set.keys.size == 1) "" else "s"})"
    }

    private suspend fun key(issuer: String, keyId: String): ECKey? {
        val now = clock()
        val cached = keys[issuer]
        cached?.takeIf { now - it.fetchedAt < KEYS_FRESH_MS }?.set?.getKeyByKeyId(keyId)?.let { return it as? ECKey }
        // An unknown key: the orchestrator may have a new one. Not more than every few seconds, so
        // made-up key ids can't make this server hammer it.
        if (cached != null && now - cached.fetchedAt < KEYS_RETRY_MS) return null
        return load(issuer)?.getKeyByKeyId(keyId) as? ECKey
    }

    private suspend fun load(issuer: String): JWKSet? {
        val text = try {
            fetch("$issuer/api/orchestrator/keys")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val set = try {
            JWKSet.parse(text).toPublicJWKSet()
        } catch (e: java.text.ParseException) {
            return null
        }
        keys[issuer] = Keys(set, clock())
        return set
    }

    companion object {
        const val TYPE = "samo-ticket+jwt"
        const val SKEW_MS = 30_000L
        const val MAX_LIFETIME_MS = 5L * 60 * 1000
        const val MAX_TICKET = 8192
        private const val MAX_USED = 100_000
        private const val KEYS_FRESH_MS = 10L * 60 * 1000
        private const val KEYS_RETRY_MS = 10_000L

        /** An address without what doesn't matter: case of the host, a slash at the end. */
        fun normal(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val scheme = trimmed.substringBefore("://", "")
            if (scheme.isEmpty()) return ""
            val rest = trimmed.substringAfter("://")
            val host = rest.substringBefore('/')
            val path = rest.substringAfter('/', "")
            return scheme.lowercase() + "://" + host.lowercase() + if (path.isEmpty()) "" else "/$path"
        }

        /**
         * Whether two identity-provider addresses are the same provider: the same scheme, host and
         * port. Authentik gives each application its own issuer address under one host, and the same
         * person the same subject in all of them.
         */
        fun sameProvider(a: String?, b: String?): Boolean {
            fun origin(url: String?) = url?.let(::normal)?.takeIf { it.isNotEmpty() }?.let { it.substringBefore("://") + "://" + it.substringAfter("://").substringBefore('/') }
            val first = origin(a) ?: return false
            return first == origin(b)
        }

        private fun string(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }
    }
}
