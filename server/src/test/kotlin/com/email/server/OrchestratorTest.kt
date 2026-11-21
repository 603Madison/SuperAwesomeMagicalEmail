package com.email.server

import com.email.api.Role
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID

/** Signing in with SuperAwesomeMagicalOrchestrator's tickets, minted here as the orchestrator would. */
class OrchestratorTest {
    private val address = "https://orchestrator.example.com"
    private val provider = "https://auth.example.com/application/o/"
    private val key: ECKey = ECKeyGenerator(Curve.P_256).keyID("ticket-1").generate()

    /** How often the server asked for the orchestrator's keys, and what it's given. */
    @Volatile private var fetches = 0
    @Volatile private var published: String? = JWKSet(key.toPublicJWK()).toString()

    private fun services() = testServices(orchestratorFetch = { url ->
        fetches++
        assertEquals("$address/api/orchestrator/keys", url)
        published ?: throw java.io.IOException("unreachable")
    })

    /** The tests' server answers at http://localhost. */
    private fun ticket(
        username: String = "maddie",
        oidcSub: String? = "sub-123",
        oidcIss: String? = "${provider}orchestrator/",
        groups: List<String> = listOf("mail"),
        audience: String = "http://localhost",
        app: String = "email",
        issuer: String = address,
        signedBy: ECKey = key,
        id: String = UUID.randomUUID().toString(),
        deviceName: String = "Maddie's Pixel",
        issuedAt: Long = System.currentTimeMillis(),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer).audience(audience).subject("orch-user-1").jwtID(id)
            .issueTime(Date(issuedAt)).notBeforeTime(Date(issuedAt)).expirationTime(Date(issuedAt + 120_000))
            .claim("app", app).claim("preferred_username", username).claim("name", "Maddie").claim("email", "maddie@example.com")
            .claim("groups", groups).claim("device_id", "device-1").claim("device_name", deviceName)
            .apply {
                oidcIss?.let { claim("oidc_iss", it) }
                oidcSub?.let { claim("oidc_sub", it) }
            }.build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signedBy.keyID).type(JOSEObjectType("samo-ticket+jwt")).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(signedBy)) }.serialize()
    }

    private suspend fun Services.configure(
        sso: Boolean = true, userGroup: String = "mail", adminGroup: String = "mail-admins", autoCreate: Boolean = true, publicUrl: String = "",
    ) {
        settings.update {
            it.copy(
                orchestratorUrl = address, publicUrl = publicUrl,
                oidc = it.oidc.copy(enabled = sso, issuer = if (sso) "${provider}same/" else "", clientId = if (sso) "same-client" else "",
                    userGroup = userGroup, adminGroup = adminGroup, autoCreateUsers = autoCreate),
            )
        }
    }

    private suspend fun ApplicationTestBuilder.send(ticket: String, deviceName: String = "A phone"): HttpResponse =
        createClient { }.post("/api/auth/orchestrator") { json("""{"ticket":"$ticket","deviceName":"$deviceName"}""") }

    private suspend fun HttpResponse.refused() {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals("That sign-in didn't work here. Sign in the usual way.", obj().str("error"))
    }

    @Test
    fun `nothing changes where no orchestrator is set`() = serverTest(services()) {
        val client = createClient { }
        assertFalse(client.get("/api/auth/config").obj()["orchestrator"]!!.jsonPrimitive.boolean)
        val answer = send(ticket())
        assertEquals(HttpStatusCode.NotFound, answer.status)
        assertEquals("No orchestrator is set up", answer.obj().str("error"))
        assertEquals(0, fetches)
    }

    @Test
    fun `the sign-in settings say when tickets are taken`() = serverTest(services()) { services ->
        services.configure()
        assertTrue(createClient { }.get("/api/auth/config").obj()["orchestrator"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a ticket signs in the account with the same subject, as an app named after the device`() = serverTest(services()) { services ->
        services.configure()
        // The account was made by signing in through Authentik here: under this server's issuer, as its tokens spell it.
        val existing = services.users.create("maddie", "Maddie", oidcIssuer = "${provider}same/", oidcSubject = "sub-123")
        val answer = send(ticket(username = "another-name"))
        assertEquals(HttpStatusCode.OK, answer.status)
        val signedIn = answer.obj()
        assertEquals(existing.id, signedIn["user"]!!.jsonObject.str("id"))
        val client = createClient { }
        val me = client.get("/api/me") { bearerAuth(signedIn.str("token")) }.obj()
        assertEquals("APP", me["session"]!!.jsonObject.str("kind"))
        assertEquals("Maddie's Pixel", me["session"]!!.jsonObject.str("name"))
        assertEquals(1L + 1, services.users.count()) // nobody new
        assertNotNull(services.users.byId(existing.id)!!.lastLoginAt)
    }

    @Test
    fun `the issuer's last slash doesn't make a second account`() = serverTest(services()) { services ->
        services.configure()
        val existing = services.users.create("maddie", oidcIssuer = "${provider}same", oidcSubject = "sub-123")
        assertEquals(existing.id, send(ticket()).obj()["user"]!!.jsonObject.str("id"))
    }

    @Test
    fun `the request's device name is used when the ticket has none`() = serverTest(services()) { services ->
        services.configure()
        val token = send(ticket(deviceName = ""), deviceName = "Pixel 9").obj().str("token")
        assertEquals("Pixel 9", createClient { }.get("/api/me") { bearerAuth(token) }.obj()["session"]!!.jsonObject.str("name"))
    }

    @Test
    fun `someone new gets an account by the single sign-on rules`() = serverTest(services()) { services ->
        services.configure()
        // A password account that happens to have the name isn't taken over.
        val local = services.users.create("maddie", password = "local-password")
        val user = send(ticket(groups = listOf("mail", "mail-admins"))).obj()["user"]!!.jsonObject
        assertEquals("maddie-2", user.str("username"))
        assertEquals("Maddie", user.str("displayName"))
        assertEquals("ADMIN", user.str("role"))
        val made = services.users.byOidc("${provider}same/", "sub-123")!!
        assertTrue(made.oidc && made.id != local.id)

        // The role follows the groups at each sign-in, as with single sign-on.
        assertEquals("USER", send(ticket()).obj()["user"]!!.jsonObject.str("role"))
    }

    @Test
    fun `nobody new when accounts aren't made on first sign-in`() = serverTest(services()) { services ->
        services.configure(autoCreate = false)
        send(ticket()).refused()
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `people outside the allowed group are turned away`() = serverTest(services()) { services ->
        services.configure()
        services.users.create("maddie", oidcIssuer = "${provider}same/", oidcSubject = "sub-123")
        send(ticket(groups = listOf("music"))).refused()
        // The admin group is allowed in too.
        assertEquals(HttpStatusCode.OK, send(ticket(groups = listOf("mail-admins"))).status)
    }

    @Test
    fun `without single sign-on here, the username finds an ordinary account`() = serverTest(services()) { services ->
        services.configure(sso = false)
        val maddie = services.users.create("maddie", password = "local-password")
        // Whether or not the orchestrator knows the person through an identity provider.
        assertEquals(maddie.id, send(ticket()).obj()["user"]!!.jsonObject.str("id"))
        assertEquals(maddie.id, send(ticket(oidcSub = null, oidcIss = null)).obj()["user"]!!.jsonObject.str("id"))
        // Nobody is made this way.
        send(ticket(username = "someone-else", oidcSub = null, oidcIss = null)).refused()
        assertNull(services.users.byUsername("someone-else"))
    }

    @Test
    fun `another identity provider's subject is not this server's`() = serverTest(services()) { services ->
        services.configure()
        services.users.create("maddie", oidcIssuer = "${provider}same/", oidcSubject = "sub-123")
        // Same subject, said by a provider on another host: the single sign-on account stays shut.
        send(ticket(oidcIss = "https://auth.elsewhere.example/application/o/orchestrator/")).refused()
        // A password account by that name is still found by its username.
        val local = services.users.create("robin", password = "local-password")
        assertEquals(local.id, send(ticket(username = "robin", oidcIss = "https://auth.elsewhere.example/o/")).obj()["user"]!!.jsonObject.str("id"))
    }

    @Test
    fun `never the break-glass admin`() = serverTest(services()) { services ->
        services.configure(sso = false)
        send(ticket(username = "admin", oidcSub = null, oidcIss = null)).refused()
        send(ticket(username = "admin")).refused()
        services.configure(sso = true, userGroup = "", adminGroup = "")
        // With single sign-on, "admin" at the provider becomes a separate, ordinary account.
        val user = send(ticket(username = "admin")).obj()["user"]!!.jsonObject
        assertEquals("admin-2", user.str("username"))
        assertEquals("USER", user.str("role"))
        assertFalse(user["breakGlass"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an account that's turned off stays refused`() = serverTest(services()) { services ->
        services.configure()
        val sso = services.users.create("maddie", oidcIssuer = "${provider}same/", oidcSubject = "sub-123")
        services.users.update(sso.id, disabled = true)
        send(ticket()).refused()

        services.configure(sso = false)
        val local = services.users.create("robin", password = "local-password")
        services.users.update(local.id, disabled = true)
        send(ticket(username = "robin")).refused()
    }

    @Test
    fun `an account an admin made ahead is claimed by the ticket, as a first single sign-on would`() = serverTest(services()) { services ->
        services.configure(autoCreate = false)
        // By username (whatever its case), and tied to the subject from then on.
        val waiting = services.users.create("Maddie", email = "m@elsewhere.example")
        assertTrue(waiting.waitingForSignIn)
        val user = send(ticket()).obj()["user"]!!.jsonObject
        assertEquals(waiting.id, user.str("id"))
        assertTrue(user["oidc"]!!.jsonPrimitive.boolean)
        assertFalse(user["waitingForSignIn"]!!.jsonPrimitive.boolean)
        assertEquals(waiting.id, services.users.byOidc("${provider}same/", "sub-123")!!.id)
        // The next ticket finds it by the subject, whatever the username says now.
        assertEquals(waiting.id, send(ticket(username = "renamed")).obj()["user"]!!.jsonObject.str("id"))
        // Signing in through Authentik itself would find the same account: nobody new was made.
        assertEquals(2L, services.users.count())
    }

    @Test
    fun `an account made ahead is claimed by the ticket's email too`() = serverTest(services()) { services ->
        services.configure(autoCreate = false)
        val waiting = services.users.create("m.norton", email = "Maddie@example.com")
        val user = send(ticket()).obj()["user"]!!.jsonObject
        assertEquals(waiting.id, user.str("id"))
        assertEquals("m.norton", user.str("username"))
        // Someone else's subject can't claim it again by the same name or email.
        send(ticket(oidcSub = "sub-999", username = "m.norton")).refused()
    }

    @Test
    fun `claiming keeps the single sign-on rules - the group, turned off, never an account with a password`() = serverTest(services()) { services ->
        services.configure(autoCreate = false)
        val waiting = services.users.create("maddie")
        // Outside the allowed group: not claimed.
        send(ticket(groups = listOf("music"))).refused()
        assertTrue(services.users.byId(waiting.id)!!.waitingForSignIn)
        // Turned off: not claimed.
        services.users.update(waiting.id, disabled = true)
        send(ticket()).refused()
        assertTrue(services.users.byId(waiting.id)!!.waitingForSignIn)
        // An account with a password is never handed over by its name.
        services.users.create("robin", password = "local-password")
        send(ticket(username = "robin", oidcSub = "sub-456")).refused()
        assertFalse(services.users.byUsername("robin")!!.oidc)
    }

    @Test
    fun `an account still waiting for single sign-on isn't handed out by its username alone`() = serverTest(services()) { services ->
        // This server signs people in through one provider; the ticket names another, or none.
        services.configure(autoCreate = false)
        val waiting = services.users.create("maddie", email = "maddie@example.com")
        send(ticket(oidcIss = "https://auth.elsewhere.example/application/o/orchestrator/")).refused()
        send(ticket(oidcSub = null, oidcIss = null)).refused()
        // And where this server has no single sign-on at all.
        services.configure(sso = false)
        send(ticket()).refused()
        send(ticket(oidcSub = null, oidcIss = null)).refused()
        val still = services.users.byId(waiting.id)!!
        assertTrue(still.waitingForSignIn && !still.oidc)
        assertNull(still.lastLoginAt)
        // The provider's own word still claims it afterwards.
        services.configure(autoCreate = false)
        assertEquals(waiting.id, send(ticket()).obj()["user"]!!.jsonObject.str("id"))
    }

    @Test
    fun `a ticket made for another server or another app is refused`() = serverTest(services()) { services ->
        services.configure()
        send(ticket(audience = "https://music.example.com")).refused()
        send(ticket(app = "music")).refused()
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `the public address counts as this server, and so does the address the request came to`() = serverTest(services()) { services ->
        services.configure(publicUrl = "https://mail.example.com")
        assertEquals(HttpStatusCode.OK, send(ticket(audience = "https://mail.example.com/")).status)
        assertEquals(HttpStatusCode.OK, send(ticket(audience = "http://localhost")).status)
    }

    @Test
    fun `a ticket works once`() = serverTest(services()) { services ->
        services.configure()
        val once = ticket()
        assertEquals(HttpStatusCode.OK, send(once).status)
        send(once).refused()
    }

    @Test
    fun `forged, stale and foreign tickets are refused`() = serverTest(services()) { services ->
        services.configure()
        val other = ECKeyGenerator(Curve.P_256).keyID("ticket-1").generate()
        send(ticket(signedBy = other)).refused()
        send(ticket(issuer = "https://orchestrator.elsewhere.example")).refused()
        send(ticket(issuedAt = System.currentTimeMillis() - 10 * 60_000)).refused()
        send("not-a-ticket").refused()
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `refused tickets count against the address like wrong passwords`() = serverTest(services()) { services ->
        services.configure()
        repeat(10) { send(ticket(app = "music")).refused() }
        // Even a good ticket waits now.
        assertEquals(HttpStatusCode.TooManyRequests, send(ticket()).status)
    }

    @Test
    fun `tickets that work don't use up the limit`() = serverTest(services()) { services ->
        services.configure()
        repeat(12) { assertEquals(HttpStatusCode.OK, send(ticket()).status) }
    }

    @Test
    fun `an orchestrator that can't be reached refuses, without an error page`() = serverTest(services()) { services ->
        services.configure()
        published = null
        send(ticket()).refused()
    }

    @Test
    fun `the admin sets the address, and can test it`() = serverTest(services()) { services ->
        val admin = signedIn()
        val current = admin.get("/api/admin/settings").obj()["settings"]!!.jsonObject
        fun with(url: String) = kotlinx.serialization.json.JsonObject(current + ("orchestratorUrl" to kotlinx.serialization.json.JsonPrimitive(url))).toString()
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/admin/settings", """{"settings":${with("orchestrator.example.com")}}""").status)
        val saved = admin.putJson("/api/admin/settings", """{"settings":${with("  $address/ ")}}""").obj()
        assertEquals(address, saved["settings"]!!.jsonObject.str("orchestratorUrl"))
        assertEquals(address, services.settings.get().orchestratorUrl)

        val found = admin.postJson("/api/admin/orchestrator/test", """{"url":"$address/"}""").obj()
        assertTrue(found["ok"]!!.jsonPrimitive.boolean)
        assertEquals("Found the orchestrator at $address (1 signing key)", found.str("message"))
        published = null
        assertFalse(admin.postJson("/api/admin/orchestrator/test", """{"url":"$address"}""").obj()["ok"]!!.jsonPrimitive.boolean)
        assertFalse(admin.postJson("/api/admin/orchestrator/test", """{"url":""}""").obj()["ok"]!!.jsonPrimitive.boolean)
        // Only admins.
        assertEquals(HttpStatusCode.Unauthorized, createClient { }.post("/api/admin/orchestrator/test") { json("""{"url":"$address"}""") }.status)
    }

    @Test
    fun `the address can come from the environment`() {
        val config = ServerConfig.fromEnvironment(mapOf("SAME_DATA_DIR" to "/tmp/x", "SAME_ORCHESTRATOR_URL" to "$address/"))
        val services = testServices(config.copy(dataDir = java.nio.file.Files.createTempDirectory("same-test").toFile(), adminPassword = "break-glass-pw"))
        kotlinx.coroutines.runBlocking { services.bootstrap() }
        assertEquals(address, kotlinx.coroutines.runBlocking { services.settings.get() }.orchestratorUrl)
        assertEquals(Role.ADMIN, kotlinx.coroutines.runBlocking { services.users.byUsername("admin") }!!.role)
    }
}
