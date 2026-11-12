package com.email.server

import com.email.server.db.Database
import com.email.server.db.Schema
import com.email.server.util.Passwords
import com.email.server.util.Random
import com.email.server.util.SecretBox
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Properties

/** A server with an in-memory database and quick password hashing, for tests (mail servers on this machine allowed). */
fun testServices(
    config: ServerConfig = ServerConfig(
        dataDir = Files.createTempDirectory("same-test").toFile(),
        adminPassword = "break-glass-pw",
        allowPrivateMailHosts = true,
    ),
    clock: () -> Long = System::currentTimeMillis,
): Services {
    val db = Database.inMemory()
    Schema.migrate(db)
    return Services(config, db, SecretBox(Random.bytes(32)), passwords = Passwords(iterations = 1_000), clock = clock)
}

fun serverTest(services: Services = testServices(), block: suspend ApplicationTestBuilder.(Services) -> Unit) = testApplication {
    services.bootstrap()
    application { module(services) }
    block(services)
}

fun ApplicationTestBuilder.browser(): HttpClient = createClient { install(HttpCookies) }

/** A browser signed in as [username]. */
suspend fun ApplicationTestBuilder.signedIn(username: String = "admin", password: String = "break-glass-pw"): HttpClient =
    browser().also { it.post("/api/auth/login") { json("""{"username":"$username","password":"$password"}""") } }

fun HttpRequestBuilder.json(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** What the web app sends with every change. */
fun HttpRequestBuilder.csrf() = header("X-SAME", "1")

suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

suspend fun HttpResponse.obj(): JsonObject = json().jsonObject

suspend fun HttpResponse.arr(): JsonArray = json().jsonArray

fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

fun JsonObject.arr(key: String): JsonArray = this[key]!!.jsonArray

suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) { csrf(); json(body) }

suspend fun HttpClient.patchJson(path: String, body: String): HttpResponse = patch(path) { csrf(); json(body) }

suspend fun HttpClient.putJson(path: String, body: String): HttpResponse = put(path) { csrf(); json(body) }

/** A real IMAP and SMTP server (GreenMail), on free ports on this machine. */
class TestMailServer : AutoCloseable {
    val greenMail = GreenMail(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP)).apply { start() }
    val imapPort: Int get() = greenMail.imap.port
    val smtpPort: Int get() = greenMail.smtp.port
    val session: Session = Session.getInstance(Properties())

    fun user(email: String, password: String = "pw-$email") = greenMail.setUser(email, email, password)

    /** Puts [message] straight into [email]'s inbox. */
    fun deliver(email: String, message: MimeMessage) {
        greenMail.userManager.getUserByEmail(email).deliver(message)
    }

    /** The JSON for a mail account on this server. */
    fun account(email: String, password: String = "pw-$email", extra: String = ""): String =
        """{"provider":"OTHER","email":"$email","password":"$password","name":"Test $email",
           "imap":{"host":"127.0.0.1","port":$imapPort,"security":"NONE"},
           "smtp":{"host":"127.0.0.1","port":$smtpPort,"security":"NONE"}$extra}"""

    /** What's in [email]'s inbox, read over IMAP. */
    fun inbox(email: String, password: String = "pw-$email"): List<MimeMessage> {
        val store = greenMail.imap.createStore()
        store.connect(email, password)
        try {
            val inbox = store.getFolder("INBOX").apply { open(jakarta.mail.Folder.READ_ONLY) }
            return inbox.messages.map { MimeMessage(it as MimeMessage) }
        } finally {
            store.close()
        }
    }

    override fun close() = greenMail.stop()
}
