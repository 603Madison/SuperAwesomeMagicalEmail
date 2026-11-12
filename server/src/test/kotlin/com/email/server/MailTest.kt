package com.email.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.ApplicationTestBuilder
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message.RecipientType
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/** Reading and sending mail through the server, against a real IMAP and SMTP server (GreenMail). */
class MailTest {
    private lateinit var mail: TestMailServer

    private val maddie = "maddie@603norton.org"
    private val pat = "pat@603norton.org"
    private val friend = "friend@example.com"

    @Before
    fun start() {
        mail = TestMailServer()
        mail.user(maddie)
        mail.user(pat)
        mail.user(friend)
    }

    @After
    fun stop() = mail.close()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4)

    private fun message(
        to: String,
        subject: String,
        text: String? = null,
        html: String? = null,
        from: String = friend,
        attachment: Pair<String, ByteArray>? = null,
        inline: Pair<String, ByteArray>? = null,
        date: Date = Date(),
    ): MimeMessage = MimeMessage(mail.session).apply {
        setFrom(InternetAddress(from, "A Friend"))
        setRecipients(RecipientType.TO, to)
        setSubject(subject, "UTF-8")
        sentDate = date
        val body: Multipart = MimeMultipart("alternative").apply {
            text?.let { addBodyPart(MimeBodyPart().apply { setText(it, "UTF-8", "plain") }) }
            if (html != null) {
                val htmlPart = MimeBodyPart().apply { setText(html, "UTF-8", "html") }
                if (inline != null) {
                    addBodyPart(
                        MimeBodyPart().apply {
                            setContent(
                                MimeMultipart("related").apply {
                                    addBodyPart(htmlPart)
                                    addBodyPart(
                                        MimeBodyPart().apply {
                                            dataHandler = DataHandler(ByteArrayDataSource(inline.second, "image/png"))
                                            contentID = "<${inline.first}>"
                                            disposition = Part.INLINE
                                            fileName = "logo.png"
                                        },
                                    )
                                },
                            )
                        },
                    )
                } else {
                    addBodyPart(htmlPart)
                }
            }
        }
        if (attachment != null) {
            setContent(
                MimeMultipart("mixed").apply {
                    addBodyPart(MimeBodyPart().apply { setContent(body) })
                    addBodyPart(
                        MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(attachment.second, "application/pdf"))
                            fileName = attachment.first
                            disposition = Part.ATTACHMENT
                        },
                    )
                },
            )
        } else {
            setContent(body)
        }
        saveChanges()
    }

    /** The admin makes [username] (with a password, so tests can sign in) and gives them a mailbox for [email]. */
    private suspend fun ApplicationTestBuilder.mailboxFor(username: String, email: String): Pair<HttpClient, String> {
        val admin = signedIn()
        val person = admin.postJson("/api/admin/users", """{"username":"$username","password":"password-$username"}""").obj().obj("user")
        val created = admin.postJson("/api/admin/mailboxes", mail.account(email, extra = ""","members":["${person.str("id")}"]"""))
        assertEquals(created.bodyAsText(), HttpStatusCode.OK, created.status)
        val id = created.obj().obj("account").str("id")
        return signedIn(username, "password-$username") to id
    }

    private suspend fun HttpClient.inbox(account: String = "all", extra: String = ""): JsonObject =
        get("/api/messages?account=$account&folder=INBOX$extra").also { assertEquals(it.bodyAsText(), HttpStatusCode.OK, it.status) }.obj()

    private fun JsonObject.subjects() = arr("messages").map { it.jsonObject.str("subject") }

    @Test
    fun `an admin's mailbox is ready for the person it's given to, who can't change or remove it`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        val accounts = me.get("/api/accounts").arr()
        assertEquals(1, accounts.size)
        val account = accounts[0].jsonObject
        assertEquals(maddie, account.str("email"))
        assertEquals("true", account.str("managed"))
        assertEquals("false", account.str("editable"))
        assertNull(account["password"])
        assertEquals(HttpStatusCode.Forbidden, me.patchJson("/api/accounts/$accountId", """{"label":"Mine"}""").status)
        assertEquals(HttpStatusCode.Forbidden, me.delete("/api/accounts/$accountId") { csrf() }.status)
        // They can sign their mail, though.
        assertEquals(HttpStatusCode.OK, me.putJson("/api/accounts/$accountId/signature", """{"signature":"Maddie"}""").status)
        assertEquals("Maddie", me.get("/api/accounts").arr()[0].jsonObject.str("signature"))

        // Someone else sees none of it.
        val admin = signedIn()
        admin.postJson("/api/admin/users", """{"username":"pat","password":"password-pat"}""")
        val other = signedIn("pat", "password-pat")
        assertEquals(0, other.get("/api/accounts").arr().size)
        assertEquals(HttpStatusCode.NotFound, other.get("/api/accounts/$accountId/folders").status)
        assertEquals(HttpStatusCode.NotFound, other.get("/api/messages?account=$accountId&folder=INBOX").status)
    }

    @Test
    fun `a mailbox whose password is wrong isn't saved, unless asked`() = serverTest { _ ->
        val admin = signedIn()
        val refused = admin.postJson("/api/admin/mailboxes", mail.account(maddie, password = "wrong"))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText(), refused.bodyAsText().contains("didn't accept"))
        val check = admin.postJson("/api/admin/mailboxes/check", mail.account(maddie)).obj()
        assertEquals("true", check.str("ok"))
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/admin/mailboxes", mail.account(maddie, password = "wrong", extra = ""","skipCheck":true""")).status)
    }

    @Test
    fun `the inbox lists newest first, with previews, a page at a time`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        for (i in 1..60) mail.deliver(maddie, message(maddie, "Message $i", text = "Hello number $i, how are you?", date = Date(1_700_000_000_000 + i * 60_000L)))
        val first = me.inbox(accountId)
        assertEquals(50, first.arr("messages").size)
        assertEquals("Message 60", first.subjects().first())
        assertEquals("Hello number 60, how are you?", first.arr("messages")[0].jsonObject.str("preview"))
        assertEquals("true", first.arr("messages")[0].jsonObject.str("unread"))
        assertEquals("60", first.str("total"))
        val second = me.inbox(accountId, "&cursor=${first.str("next")}")
        assertEquals((10 downTo 1).map { "Message $it" }, second.subjects())
        assertNull(second["next"])
    }

    @Test
    fun `previews come from the text, or the HTML, and attachments are noticed`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        mail.deliver(maddie, message(maddie, "Plain", text = "> quoted line\nThe café is open — see you there"))
        mail.deliver(maddie, message(maddie, "Html only", html = "<html><body><style>p{color:red}</style><p>Big <b>news</b> today</p></body></html>"))
        mail.deliver(maddie, message(maddie, "With a file", text = "Report attached", attachment = "report.pdf" to "%PDF-1.4".toByteArray()))
        val messages = me.inbox(accountId).arr("messages").map { it.jsonObject }
        val bySubject = messages.associateBy { it.str("subject") }
        assertEquals("The café is open — see you there", bySubject["Plain"]!!.str("preview"))
        assertEquals("Big news today", bySubject["Html only"]!!.str("preview"))
        assertEquals("true", bySubject["With a file"]!!.str("hasAttachments"))
        assertEquals("false", bySubject["Plain"]!!.str("hasAttachments"))
        // A second look comes from what was kept.
        assertEquals(bySubject["Plain"]!!.str("preview"), me.inbox(accountId).arr("messages").map { it.jsonObject }.first { it.str("subject") == "Plain" }.str("preview"))
    }

    @Test
    fun `opening a message shows it safely, with its own images, and remote ones only when asked`() = serverTest { services ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        val html = """<html><head><script>alert(1)</script><style>body{background:url(https://tracker.example/bg.png)}</style></head><body>
            <p onclick="alert(2)">Hi <a href="javascript:alert(3)">there</a> <a href="https://example.com/x">link</a></p>
            <img src="cid:logo@x"><img src="https://tracker.example/pixel.gif" width="1" height="1">
            <iframe src="https://evil.example/"></iframe><form action="https://evil.example/"><input name="pw"></form></body></html>"""
        mail.deliver(maddie, message(maddie, "Pretty", text = "Hi there", html = html, inline = "logo@x" to png, attachment = "terms.pdf" to "%PDF".toByteArray()))
        val id = me.inbox(accountId).arr("messages")[0].jsonObject.str("id")

        val opened = me.get("/api/messages/$id").obj()
        val shown = opened.str("html")
        listOf("<script", "onclick", "javascript:", "<iframe", "<form", "<input", "tracker.example").forEach {
            assertFalse("$it in $shown", shown.contains(it, ignoreCase = true))
        }
        assertTrue(shown.contains("href=\"https://example.com/x\""))
        assertTrue(shown.contains("target=\"_blank\""))
        assertEquals("true", opened.str("remoteImages"))
        assertEquals("Hi there", opened.str("text"))
        // Opening it marked it read.
        assertEquals("false", opened.obj("summary").str("unread"))
        assertEquals("false", me.inbox(accountId).arr("messages")[0].jsonObject.str("unread"))

        // Its own image comes from a signed link that works without signing in; the PDF is listed to download.
        val attachments = opened.arr("attachments").map { it.jsonObject }
        val logo = attachments.first { it.str("filename") == "logo.png" }
        assertEquals("true", logo.str("inline"))
        assertTrue(shown.contains(logo.str("url")))
        val anonymous = createClient { }
        val image = anonymous.get(logo.str("url"))
        assertEquals(HttpStatusCode.OK, image.status)
        assertTrue(image.bodyAsBytes().contentEquals(png))
        assertTrue(image.headers[HttpHeaders.ContentDisposition]!!.startsWith("inline"))
        val pdf = attachments.first { it.str("filename") == "terms.pdf" }
        assertEquals("false", pdf.str("inline"))
        val download = anonymous.get(pdf.str("url"))
        assertEquals("%PDF", download.bodyAsText())
        assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
        // Links can't be made up or changed.
        assertEquals(HttpStatusCode.NotFound, anonymous.get(pdf.str("url").replaceAfter("/api/parts/", "x.y/terms.pdf")).status)
        val token = pdf.str("url").substringAfter("/api/parts/").substringBefore('/')
        assertEquals(HttpStatusCode.NotFound, anonymous.get("/api/parts/${token.dropLast(2)}aa/terms.pdf").status)
        // The whole message, as an .eml file.
        assertTrue(anonymous.get(opened.str("rawUrl")).bodyAsText().contains("Subject: Pretty"))

        // Asking for remote images routes them through the server's proxy.
        val withImages = me.get("/api/messages/$id?images=1").obj()
        assertTrue(withImages.str("html").contains("/api/proxy?u="))
        assertFalse(withImages.str("html").contains("https://tracker.example"))
        // The proxy only fetches what the server signed, and never from private addresses.
        val proxied = Regex("/api/proxy\\?u=[^\"']+").find(withImages.str("html").replace("&amp;", "&"))!!.value
        assertEquals(HttpStatusCode.NotFound, anonymous.get(proxied.replace("&s=", "&s=x")).status)
        assertEquals(HttpStatusCode.NotFound, anonymous.get(services.links.proxy("http://127.0.0.1:${mail.imapPort}/x.png")).status)
    }

    @Test
    fun `messages are flagged, marked, archived, moved, and deleted`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        repeat(4) { mail.deliver(maddie, message(maddie, "Note ${it + 1}", text = "Body ${it + 1}")) }
        val ids = me.inbox(accountId).arr("messages").map { it.jsonObject.str("id") }

        assertEquals("2", me.postJson("/api/messages/actions", """{"ids":["${ids[0]}","${ids[1]}"],"action":"FLAG"}""").obj().str("done"))
        assertEquals("true", me.inbox(accountId).arr("messages")[0].jsonObject.str("flagged"))
        me.postJson("/api/messages/actions", """{"ids":["${ids[0]}"],"action":"READ"}""")
        assertEquals("false", me.inbox(accountId).arr("messages")[0].jsonObject.str("unread"))

        // Archiving makes the Archive folder when there isn't one.
        me.postJson("/api/messages/actions", """{"ids":["${ids[0]}"],"action":"ARCHIVE"}""")
        assertEquals(listOf("Note 3", "Note 2", "Note 1"), me.inbox(accountId).subjects())
        val folders = me.get("/api/accounts/$accountId/folders").arr().map { it.jsonObject }
        val archive = folders.first { it.str("role") == "ARCHIVE" }
        assertEquals("1", archive.str("total"))
        assertEquals("INBOX", folders[0].str("path"))
        assertEquals("3", folders[0].str("total"))

        // To the Trash, then from the Trash for good.
        me.postJson("/api/messages/actions", """{"ids":["${ids[1]}"],"action":"TRASH"}""")
        val trash = me.get("/api/accounts/$accountId/folders").arr().map { it.jsonObject }.first { it.str("role") == "TRASH" }
        val trashed = me.get("/api/messages?account=$accountId&folder=${trash.str("path").encodeURLParameter()}").obj()
        assertEquals(listOf("Note 3"), trashed.subjects())
        me.postJson("/api/messages/actions", """{"ids":["${trashed.arr("messages")[0].jsonObject.str("id")}"],"action":"TRASH"}""")
        assertEquals(0, me.get("/api/messages?account=$accountId&folder=${trash.str("path").encodeURLParameter()}").obj().arr("messages").size)

        // Moving to a folder by name.
        val moved = me.postJson("/api/messages/actions", """{"ids":["${ids[2]}"],"action":"MOVE","folder":"${archive.str("path")}"}""").obj()
        assertEquals("1", moved.str("done"))
        assertEquals(listOf("Note 1"), me.inbox(accountId).subjects())
        // A message that's moved away isn't found where it was.
        assertEquals(HttpStatusCode.NotFound, me.get("/api/messages/${ids[2]}").status)
    }

    @Test
    fun `search finds messages by subject, sender and text`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        mail.deliver(maddie, message(maddie, "Lunch on Friday", text = "Tacos?"))
        mail.deliver(maddie, message(maddie, "Invoice", text = "Your gardening invoice"))
        mail.deliver(maddie, message(maddie, "Hello", text = "Nothing much", from = "grandma@example.com"))
        assertEquals(listOf("Lunch on Friday"), me.inbox(accountId, "&q=lunch").subjects())
        assertEquals(listOf("Invoice"), me.inbox(accountId, "&q=gardening").subjects())
        // (GreenMail matches FROM against whole addresses; IMAP servers generally match any part.)
        assertEquals(listOf("Hello"), me.inbox(accountId, "&q=grandma@example.com").subjects())
    }

    @Test
    fun `sending delivers the mail, attachments and all, and keeps a copy in Sent`() = serverTest { services ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        val upload = me.submitFormWithBinaryData(
            "/api/uploads",
            formData {
                append("file", "Plan for the garden.".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"plan.txt\"")
                })
            },
        ) { header("X-SAME", "1") }
        assertEquals(upload.bodyAsText(), HttpStatusCode.OK, upload.status)
        val uploadId = upload.arr()[0].jsonObject.str("id")

        val sent = me.postJson(
            "/api/send",
            """{"accountId":"$accountId","to":[{"email":"$friend","name":"Friend"}],"cc":[{"email":"$pat"}],"subject":"The garden",
               "text":"Here's the plan.","html":"<p>Here's the <b>plan</b>.</p><script>x()</script>","uploads":["$uploadId"]}""",
        ).obj()
        assertEquals("true", sent.str("ok"))
        assertEquals("true", sent.str("savedToSent"))

        assertTrue(mail.greenMail.waitForIncomingEmail(5_000, 2))
        val received = mail.inbox(friend).single()
        assertEquals("The garden", mail.inbox(pat).single().subject)
        assertEquals("The garden", received.subject)
        val from = received.from[0] as InternetAddress
        assertEquals("Test $maddie", from.personal)
        assertEquals(maddie, from.address)
        assertTrue(received.messageID.endsWith("@603norton.org>"))
        val raw = java.io.ByteArrayOutputStream().also { received.writeTo(it) }.toString()
        assertTrue(raw.contains("plan.txt"))
        assertTrue(raw.contains("Plan for the garden."))
        assertFalse(raw.contains("<script"))
        assertTrue(raw.contains("<b>plan</b>"))

        // In Sent, and the upload is gone.
        val sentFolder = me.get("/api/accounts/$accountId/folders").arr().map { it.jsonObject }.first { it.str("role") == "SENT" }
        assertEquals(listOf("The garden"), me.get("/api/messages?account=$accountId&folder=${sentFolder.str("path").encodeURLParameter()}").obj().subjects())
        assertEquals(HttpStatusCode.Gone, me.postJson("/api/send", """{"accountId":"$accountId","to":[{"email":"$friend"}],"uploads":["$uploadId"]}""").status)

        // The address comes up next time.
        assertEquals(friend, me.get("/api/contacts?q=frie").arr()[0].jsonObject.str("email"))
        // Nobody to send to, or a bad address, is refused.
        assertEquals(HttpStatusCode.BadRequest, me.postJson("/api/send", """{"accountId":"$accountId","subject":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, me.postJson("/api/send", """{"accountId":"$accountId","to":[{"email":"not an address"}]}""").status)
        assertTrue(services.contacts.search("nobody", "frie").isEmpty())
    }

    @Test
    fun `replies quote the message, go to the right people, and join its thread`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        val original = message(maddie, "Dinner", text = "Are you free Saturday?\nBring cake.").apply {
            setRecipients(RecipientType.TO, "$maddie, other@example.com")
            setRecipients(RecipientType.CC, "cousin@example.com")
            saveChanges()
        }
        mail.deliver(maddie, original)
        val id = me.inbox(accountId).arr("messages")[0].jsonObject.str("id")

        val reply = me.get("/api/messages/$id/compose?mode=reply&tz=UTC").obj()
        assertEquals("Re: Dinner", reply.str("subject"))
        assertEquals(listOf(friend), reply.arr("to").map { it.jsonObject.str("email") })
        assertEquals(0, reply.arr("cc").size)
        assertTrue(reply.str("text").contains("> Are you free Saturday?\n> Bring cake."))
        assertTrue(reply.str("text").contains("A Friend wrote:"))
        assertEquals(id, reply.str("inReplyTo"))

        val all = me.get("/api/messages/$id/compose?mode=reply_all").obj()
        assertEquals(listOf("other@example.com", "cousin@example.com"), all.arr("cc").map { it.jsonObject.str("email") })

        val sent = me.postJson(
            "/api/send",
            """{"accountId":"$accountId","to":[{"email":"$friend"}],"subject":"Re: Dinner","text":"Yes!","inReplyTo":"$id"}""",
        )
        assertEquals(HttpStatusCode.OK, sent.status)
        assertTrue(mail.greenMail.waitForIncomingEmail(5_000, 1))
        val received = mail.inbox(friend).single { it.subject == "Re: Dinner" }
        assertEquals(original.messageID, received.getHeader("In-Reply-To")[0])
        assertTrue(received.getHeader("References")[0].contains(original.messageID))
        // The original shows it was answered.
        val store = mail.greenMail.imap.createStore()
        store.connect(maddie, "pw-$maddie")
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        assertTrue(inbox.messages.single { it.subject == "Dinner" }.isSet(Flags.Flag.ANSWERED))
        store.close()
    }

    @Test
    fun `forwarding takes the attachments along`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        mail.deliver(maddie, message(maddie, "Tickets", text = "Here you go", attachment = "tickets.pdf" to "%PDF tickets".toByteArray()))
        val id = me.inbox(accountId).arr("messages")[0].jsonObject.str("id")
        val forward = me.get("/api/messages/$id/compose?mode=forward").obj()
        assertEquals("Fwd: Tickets", forward.str("subject"))
        assertTrue(forward.str("text").contains("Forwarded message"))
        val kept = forward.arr("attachments")
        assertEquals("tickets.pdf", kept[0].jsonObject.str("filename"))
        val sent = me.postJson(
            "/api/send",
            """{"accountId":"$accountId","to":[{"email":"$pat"}],"subject":"Fwd: Tickets","text":"FYI","attachments":$kept,"forwardOf":"$id"}""",
        )
        assertEquals(sent.bodyAsText(), HttpStatusCode.OK, sent.status)
        assertTrue(mail.greenMail.waitForIncomingEmail(5_000, 1))
        val received = mail.inbox(pat).single { it.subject == "Fwd: Tickets" }
        val raw = java.io.ByteArrayOutputStream().also { received.writeTo(it) }.toString()
        assertTrue(raw.contains("tickets.pdf"))
    }

    @Test
    fun `drafts are kept in Drafts, replaced when saved again, and gone once sent`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        val first = me.postJson("/api/drafts", """{"accountId":"$accountId","to":[{"email":"$friend"}],"subject":"Thoughts","text":"Half a thought"}""").obj()
        val draftId = first.str("id")
        val drafts = me.get("/api/accounts/$accountId/folders").arr().map { it.jsonObject }.first { it.str("role") == "DRAFTS" }
        val path = drafts.str("path").encodeURLParameter()
        assertEquals(listOf("Thoughts"), me.get("/api/messages?account=$accountId&folder=$path").obj().subjects())

        val reopened = me.get("/api/messages/$draftId/compose?mode=draft").obj()
        assertEquals("Thoughts", reopened.str("subject"))
        assertEquals("Half a thought", reopened.str("text").trim())
        assertEquals(friend, reopened.arr("to")[0].jsonObject.str("email"))
        assertEquals(draftId, reopened.str("draftId"))

        val second = me.postJson("/api/drafts", """{"accountId":"$accountId","to":[{"email":"$friend"}],"subject":"Thoughts","text":"A whole thought","draftId":"$draftId"}""").obj()
        val list = me.get("/api/messages?account=$accountId&folder=$path").obj()
        assertEquals(1, list.arr("messages").size)
        assertEquals("true", list.arr("messages")[0].jsonObject.str("draft"))

        me.postJson("/api/send", """{"accountId":"$accountId","to":[{"email":"$friend"}],"subject":"Thoughts","text":"A whole thought","draftId":"${second.str("id")}"}""")
        assertEquals(0, me.get("/api/messages?account=$accountId&folder=$path").obj().arr("messages").size)
    }

    @Test
    fun `new mail is reported once, with unread counts`() = serverTest { _ ->
        val (me, accountId) = mailboxFor("maddie", maddie)
        mail.deliver(maddie, message(maddie, "Before", text = "old"))
        val start = me.get("/api/mail/new").obj()
        assertEquals(0, start.arr("messages").size)
        assertEquals("1", start.obj("unread").str(accountId))

        mail.deliver(maddie, message(maddie, "Fresh", text = "new"))
        val next = me.get("/api/mail/new?cursor=${start.str("cursor")}").obj()
        assertEquals(listOf("Fresh"), next.subjects())
        assertEquals("2", next.obj("unread").str(accountId))
        val again = me.get("/api/mail/new?cursor=${next.str("cursor")}").obj()
        assertEquals(0, again.arr("messages").size)
    }

    @Test
    fun `all inboxes shows every account's mail together, newest first, and people add their own accounts`() = serverTest { services ->
        val (me, work) = mailboxFor("maddie", maddie)
        // Their own account, from another service.
        val own = me.postJson("/api/accounts", mail.account(pat))
        assertEquals(own.bodyAsText(), HttpStatusCode.OK, own.status)
        val personal = own.obj()
        assertEquals("false", personal.str("managed"))
        assertEquals("true", personal.str("editable"))
        mail.deliver(maddie, message(maddie, "Work 1", text = "w1", date = Date(1_000_000)))
        mail.deliver(pat, message(pat, "Home 1", text = "h1", date = Date(2_000_000)))
        mail.deliver(maddie, message(maddie, "Work 2", text = "w2", date = Date(3_000_000)))
        val all = me.inbox()
        assertEquals(listOf("Work 2", "Home 1", "Work 1"), all.subjects())
        assertEquals(setOf(work, personal.str("id")), all.arr("messages").map { it.jsonObject.str("accountId") }.toSet())

        // They can change and remove their own.
        assertEquals(HttpStatusCode.OK, me.patchJson("/api/accounts/${personal.str("id")}", """{"provider":"OTHER","label":"Home"}""").status)
        assertEquals("Home", me.get("/api/accounts").arr().map { it.jsonObject }.first { it.str("id") == personal.str("id") }.str("label"))
        assertEquals(HttpStatusCode.OK, me.delete("/api/accounts/${personal.str("id")}") { csrf() }.status)
        assertEquals(listOf("Work 2", "Work 1"), me.inbox().subjects())

        // Unless the server doesn't allow it.
        services.settings.update { it.copy(personalAccounts = false) }
        assertEquals(HttpStatusCode.Forbidden, me.postJson("/api/accounts", mail.account(pat)).status)
        assertEquals("false", me.get("/api/providers").obj().str("canAddPersonal"))
    }

    @Test
    fun `admins give one mailbox to several people, and take it away`() = serverTest { services ->
        val admin = signedIn()
        val a = admin.postJson("/api/admin/users", """{"username":"maddie","password":"password-maddie"}""").obj().obj("user").str("id")
        val b = admin.postJson("/api/admin/users", """{"username":"pat","displayName":"Pat","email":"pat@603norton.org"}""").obj().obj("user")
        assertEquals("true", b.str("waitingForSignIn"))
        val box = admin.postJson("/api/admin/mailboxes", mail.account("hello@603norton.org".also { mail.user(it) }, extra = ""","members":["$a","${b.str("id")}"]""")).obj()
        assertEquals(setOf("maddie", "pat"), box.arr("members").map { it.jsonObject.str("username") }.toSet())
        assertEquals(listOf(box.obj("account").str("id")), admin.get("/api/admin/users").arr().map { it.jsonObject }.first { it.obj("user").str("username") == "pat" }.arr("mailboxes").map { it.jsonPrimitive.content })

        val me = signedIn("maddie", "password-maddie")
        assertEquals(1, me.get("/api/accounts").arr().size)
        admin.putJson("/api/admin/mailboxes/${box.obj("account").str("id")}/members", """{"userIds":["${b.str("id")}"]}""")
        assertEquals(0, me.get("/api/accounts").arr().size)

        // Deleting a person keeps the admins' mailbox; deleting the mailbox takes it from everyone.
        admin.delete("/api/admin/users/${b.str("id")}") { csrf() }
        assertNotNull(services.accounts.get(box.obj("account").str("id")))
        assertEquals(HttpStatusCode.OK, admin.delete("/api/admin/mailboxes/${box.obj("account").str("id")}") { csrf() }.status)
        assertEquals(0, admin.get("/api/admin/mailboxes").arr().size)
    }

    @Test
    fun `mail servers on private addresses are refused unless allowed`() {
        val services = testServices(ServerConfig(dataDir = java.nio.file.Files.createTempDirectory("same-test").toFile(), adminPassword = "break-glass-pw"))
        serverTest(services) {
            val admin = signedIn()
            val refused = admin.postJson("/api/admin/mailboxes", mail.account(maddie))
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.bodyAsText(), refused.bodyAsText().contains("private network"))
        }
    }
}
