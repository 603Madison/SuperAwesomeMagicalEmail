package com.email.app.net

import com.email.api.AccountInput
import com.email.api.Action
import com.email.api.ActionResult
import com.email.api.ApiJson
import com.email.api.AppTokenRequest
import com.email.api.AuthConfig
import com.email.api.ComposeMode
import com.email.api.Contact
import com.email.api.Draft
import com.email.api.DraftSaved
import com.email.api.ErrorBody
import com.email.api.Folder
import com.email.api.LoginRequest
import com.email.api.MailAccount
import com.email.api.Me
import com.email.api.Message
import com.email.api.MessageActions
import com.email.api.MessagePage
import com.email.api.NewMail
import com.email.api.Providers
import com.email.api.SendResult
import com.email.api.SignatureChange
import com.email.api.SignedIn
import com.email.api.Upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.TimeZone

/** Something the server said no to, with what it said (meant for people). */
class ServerException(val code: Int, message: String) : IOException(message)

/** The server's API (server/src/main/kotlin), as the app uses it, with the app's sign-in token. */
class ServerApi(
    private val http: OkHttpClient,
    private val base: () -> String,
    private val token: () -> String?,
    /** Told when the server says the sign-in is gone (revoked from the server's Settings, say). */
    private val onSignedOut: () -> Unit = {},
) {
    suspend fun authConfig(server: String = base()): AuthConfig = get("$server/api/auth/config", AuthConfig.serializer(), auth = false)

    suspend fun login(server: String, username: String, password: String, deviceName: String): SignedIn =
        post("$server/api/auth/login", ApiJson.encodeToString(LoginRequest.serializer(), LoginRequest(username, password, true, deviceName)), SignedIn.serializer(), auth = false)

    suspend fun exchangeCode(server: String, code: String, verifier: String, deviceName: String): SignedIn =
        post("$server/api/auth/app/token", ApiJson.encodeToString(AppTokenRequest.serializer(), AppTokenRequest(code, verifier, deviceName)), SignedIn.serializer(), auth = false)

    suspend fun logout() = runCatching { post("${base()}/api/auth/logout", "{}", JsonElement.serializer()) }

    suspend fun me(): Me = get("${base()}/api/me", Me.serializer())

    suspend fun providers(): Providers = get("${base()}/api/providers", Providers.serializer())

    suspend fun accounts(): List<MailAccount> = get("${base()}/api/accounts", ListSerializer(MailAccount.serializer()))

    suspend fun addAccount(input: AccountInput): MailAccount =
        post("${base()}/api/accounts", ApiJson.encodeToString(AccountInput.serializer(), input), MailAccount.serializer())

    suspend fun setSignature(accountId: String, signature: String): MailAccount =
        send("PUT", "${base()}/api/accounts/${enc(accountId)}/signature", ApiJson.encodeToString(SignatureChange.serializer(), SignatureChange(signature)), MailAccount.serializer())

    suspend fun folders(accountId: String): List<Folder> = get("${base()}/api/accounts/${enc(accountId)}/folders", ListSerializer(Folder.serializer()))

    suspend fun messages(account: String, folder: String, cursor: String? = null, query: String? = null): MessagePage =
        get(
            "${base()}/api/messages?account=${enc(account)}&folder=${enc(folder)}" +
                (cursor?.let { "&cursor=${enc(it)}" } ?: "") + (query?.takeIf { it.isNotBlank() }?.let { "&q=${enc(it)}" } ?: ""),
            MessagePage.serializer(),
        )

    suspend fun message(id: String, images: Boolean, markRead: Boolean = true): Message =
        get("${base()}/api/messages/${enc(id)}?images=${if (images) 1 else 0}&read=${if (markRead) 1 else 0}", Message.serializer())

    suspend fun act(ids: List<String>, action: Action, folder: String? = null): ActionResult =
        post("${base()}/api/messages/actions", ApiJson.encodeToString(MessageActions.serializer(), MessageActions(ids, action, folder)), ActionResult.serializer())

    suspend fun compose(id: String, mode: ComposeMode): Draft =
        get("${base()}/api/messages/${enc(id)}/compose?mode=${mode.name}&tz=${enc(TimeZone.getDefault().id)}", Draft.serializer())

    suspend fun send(draft: Draft): SendResult = post("${base()}/api/send", ApiJson.encodeToString(Draft.serializer(), draft), SendResult.serializer())

    suspend fun saveDraft(draft: Draft): DraftSaved = post("${base()}/api/drafts", ApiJson.encodeToString(Draft.serializer(), draft), DraftSaved.serializer())

    suspend fun contacts(query: String): List<Contact> = get("${base()}/api/contacts?q=${enc(query)}", ListSerializer(Contact.serializer()))

    suspend fun newMail(cursor: String?): NewMail = get("${base()}/api/mail/new" + (cursor?.let { "?cursor=${enc(it)}" } ?: ""), NewMail.serializer())

    /** Uploads a file to attach, streaming it from [open]. */
    suspend fun upload(filename: String, contentType: String, size: Long, open: () -> InputStream): Upload = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType() = contentType.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().source().use { sink.writeAll(it) }
            }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", filename, body).build()
        val request = Request.Builder().url("${base()}/api/uploads").post(form).apply { token()?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(request).execute().use { parse(it, ListSerializer(Upload.serializer())) }.first()
    }

    /** A signed link from the server ("/api/parts/…") made absolute, for the browser or the download manager. */
    fun absolute(path: String): String = if (path.startsWith("http")) path else base() + path

    private suspend fun <T> get(url: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("GET", url, null, serializer, auth)

    private suspend fun <T> post(url: String, body: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("POST", url, body, serializer, auth)

    private suspend fun <T> send(method: String, url: String, body: String?, serializer: KSerializer<T>, auth: Boolean = true): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).method(method, body?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON))
                .apply { if (auth) token()?.let { header("Authorization", "Bearer $it") } }
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401 && auth) onSignedOut()
                parse(response, serializer)
            }
        }

    private fun <T> parse(response: okhttp3.Response, serializer: KSerializer<T>): T {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            throw ServerException(response.code, message ?: "The server answered HTTP ${response.code}")
        }
        return ApiJson.decodeFromString(serializer, text)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Accepts "mail.example.com", "http://host:8080/" and the like. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            return "${url.scheme}://${url.host}${if (url.port != okhttp3.HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""}"
        }

        /** Adds the app's sign-in to requests for the server (and nothing else). */
        fun authInterceptor(base: () -> String, token: () -> String?) = Interceptor { chain ->
            val request = chain.request()
            val server = base().toHttpUrlOrNull()
            val sameServer = server != null && request.url.host == server.host && request.url.port == server.port
            val tokenValue = token()
            if (sameServer && tokenValue != null && request.header("Authorization") == null) {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $tokenValue").build())
            } else {
                chain.proceed(request)
            }
        }
    }
}
