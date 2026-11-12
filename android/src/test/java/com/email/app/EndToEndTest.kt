package com.email.app

import com.email.api.Action
import com.email.api.Address
import com.email.api.ComposeMode
import com.email.api.Draft
import com.email.app.net.ServerApi
import com.email.app.net.ServerException
import com.email.app.net.SignIn
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's API against the real server (in-process) and a real mail server: the app's side of everything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EndToEndTest {
    private lateinit var server: TestServer
    private var token: String? = null
    private val http = OkHttpClient()
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server = TestServer()
        api = ServerApi(http, { server.base }, { token })
    }

    @After
    fun stop() = server.close()

    @Test
    fun `signs in with a password, reads the inbox, opens, answers and archives a message`() = runBlocking {
        assertEquals("SuperAwesomeMagicalEmail", api.authConfig(server.base).serverName)
        token = api.login(server.base, "maddie", "correct horse", "Pixel 9").token
        assertTrue(token!!.length > 20)

        val accounts = api.accounts()
        assertEquals(listOf(TestServer.MAILBOX), accounts.map { it.email })
        assertTrue(accounts[0].managed)

        server.deliver("Dinner on Friday", "Are you free?\nBring dessert.")
        val inbox = api.messages(server.accountId, "INBOX")
        assertEquals(listOf("Dinner on Friday"), inbox.messages.map { it.subject })
        assertEquals("Are you free? Bring dessert.", inbox.messages[0].preview)
        assertTrue(inbox.messages[0].unread)

        val id = inbox.messages[0].id
        val message = api.message(id, images = false)
        assertEquals("Are you free?\nBring dessert.", message.text.trim())
        assertTrue(!api.messages(server.accountId, "INBOX").messages[0].unread)

        // A reply, as the app starts it (plain text, quoting the message).
        val reply = api.compose(id, ComposeMode.REPLY)
        assertEquals("Re: Dinner on Friday", reply.subject)
        assertTrue(reply.text.contains("> Bring dessert."))
        val result = api.send(reply.copy(text = "Yes, with pie.\n" + reply.text, html = null))
        assertTrue(result.ok)
        assertTrue(server.mail.waitForIncomingEmail(5_000, 1))

        api.act(listOf(id), Action.ARCHIVE)
        assertTrue(api.messages(server.accountId, "INBOX").messages.isEmpty())
        val folders = api.folders(server.accountId).map { it.name }
        assertTrue(folders.toString(), "Archive" in folders && "Sent" in folders)
    }

    @Test
    fun `attachments upload from a stream and go with the message`() = runBlocking {
        token = api.login(server.base, "maddie", "correct horse", "Pixel 9").token
        val bytes = "A list of things to bring".toByteArray()
        val upload = api.upload("list.txt", "text/plain", bytes.size.toLong()) { bytes.inputStream() }
        assertEquals("list.txt", upload.filename)
        api.send(Draft(server.accountId, to = listOf(Address(TestServer.FRIEND)), subject = "The list", text = "Attached.", uploads = listOf(upload.id)))
        assertTrue(server.mail.waitForIncomingEmail(5_000, 1))
        val raw = java.io.ByteArrayOutputStream().also { out -> server.mail.receivedMessages.first { it.subject == "The list" }.writeTo(out) }.toString()
        assertTrue(raw.contains("list.txt"))
        assertEquals(TestServer.FRIEND, api.contacts("friend").first().email)
    }

    @Test
    fun `new mail is noticed once, for notifications`() = runBlocking {
        token = api.login(server.base, "maddie", "correct horse", "Pixel 9").token
        val first = api.newMail(null)
        assertTrue(first.messages.isEmpty())
        server.deliver("Your parcel", "It's on the porch")
        val next = api.newMail(first.cursor)
        assertEquals(listOf("Your parcel"), next.messages.map { it.subject })
        assertEquals(1, next.unread[server.accountId])
        assertTrue(api.newMail(next.cursor).messages.isEmpty())
    }

    @Test
    fun `the browser sign-in's one-time code works only with this app's secret`() = runBlocking {
        // The person signs in to the server's page in the browser (a password here; Authentik in life)…
        val browser = OkHttpClient.Builder().cookieJar(
            object : CookieJar {
                private val store = mutableListOf<Cookie>()

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    store.removeAll { kept -> cookies.any { it.name == kept.name } }
                    store.addAll(cookies)
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> = store.toList()
            },
        ).build()
        val json = "application/json".toMediaType()
        browser.newCall(Request.Builder().url("${server.base}/api/auth/login").post("""{"username":"maddie","password":"correct horse"}""".toRequestBody(json)).build()).execute().close()
        // …and agrees to sign in the app, which made [verifier].
        val verifier = SignIn.token(48)
        val codeResponse = browser.newCall(
            Request.Builder().url("${server.base}/api/auth/app/code").header("X-SAME", "1")
                .post("""{"challenge":"${SignIn.challenge(verifier)}"}""".toRequestBody(json)).build(),
        ).execute().use { it.body!!.string() }
        val code = Regex("\"code\":\"([^\"]+)\"").find(codeResponse)!!.groupValues[1]

        try {
            api.exchangeCode(server.base, code, SignIn.token(48), "Thief")
            fail("A code must not work with another secret")
        } catch (e: ServerException) {
            assertEquals(401, e.code)
        }
        val code2 = browser.newCall(
            Request.Builder().url("${server.base}/api/auth/app/code").header("X-SAME", "1")
                .post("""{"challenge":"${SignIn.challenge(verifier)}"}""".toRequestBody(json)).build(),
        ).execute().use { Regex("\"code\":\"([^\"]+)\"").find(it.body!!.string())!!.groupValues[1] }
        token = api.exchangeCode(server.base, code2, verifier, "Pixel 9").token
        assertEquals("maddie", api.me().user.username)
        assertEquals(com.email.api.SessionKind.APP, api.me().session.kind)
    }

    @Test
    fun `addresses typed in become server addresses`() {
        assertEquals("https://mail.603norton.org", ServerApi.normalize("mail.603norton.org"))
        assertEquals("https://mail.603norton.org", ServerApi.normalize(" https://mail.603norton.org/ "))
        assertEquals("http://192.168.1.5:8095", ServerApi.normalize("http://192.168.1.5:8095/settings"))
        assertNull(ServerApi.normalize("   "))
        assertNull(ServerApi.normalize("not a server"))
    }
}
