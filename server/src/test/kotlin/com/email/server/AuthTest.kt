package com.email.server

import com.email.server.auth.OidcClient
import com.email.api.Role
import com.email.server.util.Random
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    @Test
    fun `first start makes the break-glass admin`() = serverTest { services ->
        val admin = services.users.byUsername("admin")!!
        assertTrue(admin.breakGlass)
        assertEquals(Role.ADMIN, admin.role)

        // A second start changes nothing.
        services.bootstrap()
        assertEquals(1L, services.users.count())
    }

    @Test
    fun `password sign-in sets a cookie that works, and changes need the CSRF header`() = serverTest {
        val browser = browser()
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/me").status)

        val login = browser.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
        assertNull(login.obj()["token"]) // browsers get a cookie, not a token
        assertTrue(login.headers.getAll("Set-Cookie")!!.any { it.startsWith("same_session=") && "HttpOnly" in it && "SameSite=Strict" in it })

        val me = browser.get("/api/me").obj()
        assertEquals("admin", me["user"]!!.jsonObject.str("username"))

        // Another site's page can't add the header, so it can't make changes with the cookie.
        assertEquals(HttpStatusCode.Forbidden, browser.put("/api/accounts/order") { json("""{"ids":[]}""") }.status)
        assertEquals(HttpStatusCode.OK, browser.put("/api/accounts/order") { csrf(); json("""{"ids":[]}""") }.status)

        browser.post("/api/auth/logout") { csrf() }
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/me").status)
    }

    @Test
    fun `wrong passwords are refused and then throttled`() = serverTest {
        val client = createClient { }
        repeat(10) {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/auth/login") { json("""{"username":"admin","password":"nope-nope"}""") }.status)
        }
        // Even the right password waits now.
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }.status)
    }

    @Test
    fun `the app signs in with a token`() = serverTest {
        val client = createClient { }
        val login = client.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw","app":true,"deviceName":"Pixel"}""") }.obj()
        val token = login.str("token")
        val me = client.get("/api/me") { bearerAuth(token) }.obj()
        assertEquals("APP", me["session"]!!.jsonObject.str("kind"))
        // Bearer tokens aren't sent by other sites' pages, so no CSRF header is needed.
        assertEquals(HttpStatusCode.OK, client.put("/api/accounts/order") { bearerAuth(token); json("""{"ids":[]}""") }.status)
    }

    @Test
    fun `the app's browser sign-in hands over a one-time code checked with PKCE`() = serverTest {
        val browser = browser()
        browser.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        val verifier = Random.token(48)
        val challenge = OidcClient.challengeOf(verifier)
        val code = browser.post("/api/auth/app/code") { csrf(); json("""{"challenge":"$challenge"}""") }.obj().str("code")

        val app = createClient { }
        // The wrong verifier (an app that caught the code but didn't start the sign-in) gets nothing.
        assertEquals(HttpStatusCode.Unauthorized, app.post("/api/auth/app/token") { json("""{"code":"$code","verifier":"${Random.token(48)}"}""") }.status)

        val code2 = browser.post("/api/auth/app/code") { csrf(); json("""{"challenge":"$challenge"}""") }.obj().str("code")
        val token = app.post("/api/auth/app/token") { json("""{"code":"$code2","verifier":"$verifier","deviceName":"Phone"}""") }.obj().str("token")
        assertEquals(HttpStatusCode.OK, app.get("/api/me") { bearerAuth(token) }.status)
        // Codes work once.
        assertEquals(HttpStatusCode.Unauthorized, app.post("/api/auth/app/token") { json("""{"code":"$code2","verifier":"$verifier"}""") }.status)
    }

    @Test
    fun `admins manage accounts and users can't`() = serverTest { services ->
        val admin = browser()
        admin.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }
        val created = admin.post("/api/admin/users") { csrf(); json("""{"username":"maddie","password":"correct horse","role":"USER"}""") }
        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals(HttpStatusCode.Conflict, admin.post("/api/admin/users") { csrf(); json("""{"username":"Maddie","password":"correct horse"}""") }.status)

        val user = browser()
        user.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/admin/users").status)

        // Turning an account off signs it out.
        val id = created.obj().obj("user").str("id")
        assertEquals(HttpStatusCode.OK, admin.patchJson("/api/admin/users/$id", """{"disabled":true}""").status)
        assertEquals(HttpStatusCode.Unauthorized, user.get("/api/me").status)

        // The break-glass admin can't be turned off or deleted.
        val breakGlass = services.users.breakGlass()!!.id
        assertEquals(HttpStatusCode.BadRequest, admin.request("/api/admin/users/$breakGlass") { method = io.ktor.http.HttpMethod.Delete; csrf() }.status)
        assertEquals(2, admin.get("/api/admin/users").json().jsonArray.size)
    }

    @Test
    fun `with local sign-in off only the break-glass admin can use a password`() = serverTest { services ->
        services.users.create("maddie", password = "correct horse")
        services.settings.update { it.copy(localLogin = false, oidc = it.oidc.copy(enabled = true, issuer = "https://auth.example.com/", clientId = "x")) }
        val client = createClient { }
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/auth/login") { json("""{"username":"maddie","password":"correct horse"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/login") { json("""{"username":"admin","password":"break-glass-pw"}""") }.status)
        val config = client.get("/api/auth/config").obj()
        assertEquals("false", config.str("localLogin"))
        assertNotNull(config["oidc"])
    }

    @Test
    fun `behind a proxy, the visitor's address is the one the proxy saw, not one they wrote`() {
        val direct = "10.151.2.1"
        fun headers(vararg pairs: Pair<String, String>) = io.ktor.http.headersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray())
        val forged = headers("X-Forwarded-For" to "6.6.6.6, 203.0.113.7")
        // Not behind a proxy: the connection's address, whatever the headers say.
        assertEquals(direct, com.email.server.auth.clientAddress(direct, forged, ServerConfig(java.io.File("."))))
        // One proxy: the entry it added (the last), never the one the visitor wrote.
        val proxy = ServerConfig(java.io.File("."), trustProxy = true)
        assertEquals("203.0.113.7", com.email.server.auth.clientAddress(direct, forged, proxy))
        // Cloudflare, then the proxy: the proxy added Cloudflare's address; the visitor's is before it.
        val twoHops = headers("X-Forwarded-For" to "6.6.6.6, 203.0.113.7, 172.70.1.1")
        assertEquals("203.0.113.7", com.email.server.auth.clientAddress(direct, twoHops, proxy.copy(proxies = 2)))
        // Or Cloudflare's own header, which visitors can't set.
        val cloudflare = proxy.copy(clientIpHeader = "CF-Connecting-IP")
        val withHeader = headers("X-Forwarded-For" to "6.6.6.6, 203.0.113.7, 172.70.1.1", "CF-Connecting-IP" to "203.0.113.7")
        assertEquals("203.0.113.7", com.email.server.auth.clientAddress(direct, withHeader, cloudflare))
        // Fewer entries than proxies, or none: the earliest a proxy added, else the connection's.
        assertEquals("203.0.113.7", com.email.server.auth.clientAddress(direct, headers("X-Forwarded-For" to "203.0.113.7"), proxy.copy(proxies = 2)))
        assertEquals(direct, com.email.server.auth.clientAddress(direct, headers(), proxy))
        // From the environment.
        val env = ServerConfig.fromEnvironment(mapOf("SAME_TRUST_PROXY" to "true", "SAME_PROXIES" to "2", "SAME_CLIENT_IP_HEADER" to "CF-Connecting-IP"))
        assertEquals(2, env.proxies)
        assertEquals("CF-Connecting-IP", env.clientIpHeader)
        assertNull(ServerConfig.fromEnvironment(mapOf("SAME_CLIENT_IP_HEADER" to "bad header: x")).clientIpHeader)
    }
}
