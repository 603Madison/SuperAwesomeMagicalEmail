package com.email.app

import com.email.server.ServerConfig
import com.email.server.Services
import com.email.server.db.Database
import com.email.server.db.Schema
import com.email.server.module
import com.email.server.util.Passwords
import com.email.server.util.Random
import com.email.server.util.SecretBox
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * The real server, in-process, with an in-memory database, and a real mail server (GreenMail)
 * holding maddie@603norton.org's mailbox, which an admin has given to maddie (password "correct horse").
 */
class TestServer : AutoCloseable {
    val mail: GreenMail = GreenMail(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP)).apply {
        start()
        setUser(MAILBOX, MAILBOX, "mailbox-pw")
        setUser(FRIEND, FRIEND, "friend-pw")
    }

    val services: Services
    private val server: EmbeddedServer<*, *>
    val base: String
    lateinit var accountId: String

    init {
        // Each Robolectric sandbox has its own class loader, and JDBC only lends a driver to the
        // loader that registered it: register SQLite's in this one.
        Class.forName("org.sqlite.JDBC")
        val config = ServerConfig(dataDir = Files.createTempDirectory("e2e").toFile(), adminPassword = "admin-password", allowPrivateMailHosts = true)
        services = Services(config, Database.inMemory().also(Schema::migrate), SecretBox(Random.bytes(32)), passwords = Passwords(1_000))
        runBlocking {
            services.bootstrap()
            val maddie = services.users.create("maddie", "Maddie Norton", password = "correct horse")
            accountId = services.accounts.create(
                managed = true, createdBy = null, provider = com.email.api.Provider.OTHER, email = MAILBOX, name = "Maddie Norton", label = "603norton.org",
                username = MAILBOX, password = "mailbox-pw",
                imap = com.email.api.Endpoint("127.0.0.1", mail.imap.port, com.email.api.Security.NONE),
                smtp = com.email.api.Endpoint("127.0.0.1", mail.smtp.port, com.email.api.Security.NONE),
                saveSent = true, members = listOf(maddie.id),
            ).id
        }
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(services) }.start(wait = false)
        base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
    }

    /** Puts a message in maddie's inbox. */
    fun deliver(subject: String, text: String, from: String = FRIEND) {
        val message = jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties())).apply {
            setFrom(jakarta.mail.internet.InternetAddress(from, "A Friend"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, MAILBOX)
            setSubject(subject)
            setText(text)
            sentDate = java.util.Date()
            saveChanges()
        }
        mail.userManager.getUserByEmail(MAILBOX).deliver(message)
    }

    override fun close() {
        server.stop(100, 1_000)
        mail.stop()
        services.close()
    }

    companion object {
        const val MAILBOX = "maddie@603norton.org"
        const val FRIEND = "friend@example.com"
    }
}
