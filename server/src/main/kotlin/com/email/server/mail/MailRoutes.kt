package com.email.server.mail

import com.email.api.AccountInput
import com.email.api.ComposeMode
import com.email.api.Draft
import com.email.api.MessageActions
import com.email.api.Ok
import com.email.api.OrderChange
import com.email.api.Provider
import com.email.api.Providers as ProvidersResponse
import com.email.api.SignatureChange
import com.email.api.Upload
import com.email.api.User
import com.email.server.ApiError
import com.email.server.Services
import com.email.server.auth.UserStore
import com.email.server.auth.requireUser
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.time.ZoneId

/** The settings an account input comes to, checked. */
data class AccountSettings(
    val provider: Provider,
    val email: String,
    val name: String,
    val label: String,
    val username: String,
    val password: String?,
    val imap: com.email.api.Endpoint,
    val smtp: com.email.api.Endpoint,
    val saveSent: Boolean,
)

/** Works out an account's settings from what was entered (and, when changing one, what's saved). */
fun resolveAccount(input: AccountInput, current: StoredAccount?, defaultName: String): AccountSettings {
    val provider = input.provider
    val preset = Providers.of(provider)
    val email = input.email.trim().ifBlank { current?.email.orEmpty() }
    UserStore.validEmail(email) ?: throw ApiError.badRequest("What's the email address?")
    fun endpoint(given: com.email.api.Endpoint?, saved: com.email.api.Endpoint?, presetOne: com.email.api.Endpoint, what: String): com.email.api.Endpoint {
        if (provider != Provider.OTHER) return presetOne
        val chosen = given ?: saved ?: throw ApiError.badRequest("What's the $what server?")
        val host = chosen.host.trim().lowercase()
        if (!host.matches(Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?"))) throw ApiError.badRequest("\"${chosen.host}\" isn't a server name")
        if (chosen.port !in 1..65535) throw ApiError.badRequest("${chosen.port} isn't a port")
        return chosen.copy(host = host)
    }
    val imap = endpoint(input.imap, current?.imap, preset.imap, "IMAP (incoming)")
    val smtp = endpoint(input.smtp, current?.smtp, preset.smtp, "SMTP (outgoing)")
    return AccountSettings(
        provider = provider,
        email = email,
        name = input.name.trim().take(100).ifBlank { current?.name ?: defaultName },
        label = input.label.trim().take(80).ifBlank { current?.label?.takeIf { current.email == email } ?: email },
        username = input.username.trim().ifBlank { current?.username?.takeIf { current.email == email && provider == Provider.OTHER } ?: email },
        password = input.password?.takeIf { it.isNotEmpty() },
        imap = imap,
        smtp = smtp,
        saveSent = input.saveSent ?: current?.saveSent?.takeIf { current.provider == provider } ?: preset.saveSent,
    )
}

/** Signs in to the account's servers, unless told not to; refuses settings that don't work. */
suspend fun checkOrRefuse(services: Services, settings: AccountSettings, password: String, skip: Boolean) {
    if (skip) return
    val check = services.connections.check(settings.username, password, settings.imap, settings.smtp)
    if (!check.ok) {
        val problems = listOfNotNull(
            check.imap.takeIf { !it.startsWith("Signed in") }?.let { "Incoming (IMAP): $it" },
            check.smtp.takeIf { !it.startsWith("Signed in") }?.let { "Outgoing (SMTP): $it" },
        )
        throw ApiError.badRequest(problems.joinToString(". "))
    }
}

fun Route.mailRoutes(services: Services) {
    get("/api/providers") {
        val user = call.requireUser(services)
        call.respond(ProvidersResponse(Providers.presets, services.settings.get().personalAccounts || user.isAdmin))
    }

    route("/api/accounts") {
        get {
            val user = call.requireUser(services)
            call.respond(services.accounts.forUser(user.id).map { it.account.view(user, it.signature) })
        }

        // A person's own account, from another mail service.
        post {
            val user = call.requireUser(services)
            if (!services.settings.get().personalAccounts && !user.isAdmin) throw ApiError.forbidden("This server's admins add the mail accounts")
            val input = call.receive<AccountInput>()
            val settings = resolveAccount(input, null, user.displayName)
            val password = settings.password ?: throw ApiError.badRequest("What's the password?")
            checkOrRefuse(services, settings, password, input.skipCheck)
            val created = services.accounts.create(
                managed = false, createdBy = user.id, provider = settings.provider, email = settings.email, name = settings.name,
                label = settings.label, username = settings.username, password = password, imap = settings.imap, smtp = settings.smtp,
                saveSent = settings.saveSent, members = listOf(user.id),
            )
            call.respond(created.view(user))
        }

        post("/check") {
            val user = call.requireUser(services)
            val input = call.receive<AccountInput>()
            val settings = resolveAccount(input, null, user.displayName)
            call.respond(services.connections.check(settings.username, settings.password ?: throw ApiError.badRequest("What's the password?"), settings.imap, settings.smtp))
        }

        put("/order") {
            val user = call.requireUser(services)
            services.accounts.setOrder(user.id, call.receive<OrderChange>().ids)
            call.respond(Ok())
        }

        route("/{id}") {
            patch {
                val user = call.requireUser(services)
                val member = services.mail.membership(user, call.parameters["id"].orEmpty())
                if (member.account.managed) throw ApiError.forbidden("An admin set this account up; ask them to change it")
                val input = call.receive<AccountInput>()
                val settings = resolveAccount(input, member.account, user.displayName)
                val changesSignIn = settings.password != null || settings.imap != member.account.imap || settings.smtp != member.account.smtp ||
                    settings.username != member.account.username
                if (changesSignIn) checkOrRefuse(services, settings, settings.password ?: member.account.password, input.skipCheck)
                val updated = services.accounts.update(
                    member.account.id, settings.provider, settings.email, settings.name, settings.label, settings.username, settings.password,
                    settings.imap, settings.smtp, settings.saveSent,
                )
                services.connections.forget(updated.id)
                call.respond(updated.view(user, member.signature))
            }

            delete {
                val user = call.requireUser(services)
                val member = services.mail.membership(user, call.parameters["id"].orEmpty())
                if (member.account.managed) throw ApiError.forbidden("An admin set this account up; ask them to take it away")
                services.accounts.delete(member.account.id)
                services.connections.forget(member.account.id)
                call.respond(Ok())
            }

            post("/check") {
                val user = call.requireUser(services)
                val member = services.mail.membership(user, call.parameters["id"].orEmpty())
                val account = member.account
                call.respond(services.connections.check(account.username, account.password, account.imap, account.smtp))
            }

            put("/signature") {
                val user = call.requireUser(services)
                val member = services.mail.membership(user, call.parameters["id"].orEmpty())
                val signature = call.receive<SignatureChange>().signature.take(10_000)
                services.accounts.setSignature(user.id, member.account.id, signature)
                call.respond(member.account.view(user, signature))
            }

            get("/folders") {
                val user = call.requireUser(services)
                call.respond(services.mail.folders(user, call.parameters["id"].orEmpty()))
            }
        }
    }

    route("/api/messages") {
        get {
            val user = call.requireUser(services)
            val params = call.request.queryParameters
            call.respond(
                services.mail.messages(user, params["account"] ?: MailService.ALL, params["folder"] ?: "INBOX", params["cursor"], params["q"]),
            )
        }

        post("/actions") {
            val user = call.requireUser(services)
            val request = call.receive<MessageActions>()
            if (request.ids.size > 1000) throw ApiError.badRequest("That's too many at once")
            call.respond(services.mail.act(user, request.ids, request.action, request.folder))
        }

        get("/{id}") {
            val user = call.requireUser(services)
            val params = call.request.queryParameters
            call.respond(services.mail.message(user, call.parameters["id"].orEmpty(), params["images"] == "1", params["read"] != "0"))
        }

        get("/{id}/compose") {
            val user = call.requireUser(services)
            val params = call.request.queryParameters
            val mode = ComposeMode.entries.firstOrNull { it.name.equals(params["mode"], ignoreCase = true) } ?: ComposeMode.REPLY
            val zone = params["tz"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            call.respond(services.composer.seed(user, call.parameters["id"].orEmpty(), mode, zone))
        }
    }

    post("/api/send") {
        val user = call.requireUser(services)
        call.respond(services.composer.send(user, call.receive<Draft>()))
    }

    post("/api/drafts") {
        val user = call.requireUser(services)
        call.respond(services.composer.saveDraft(user, call.receive<Draft>()))
    }

    route("/api/uploads") {
        post {
            val user = call.requireUser(services)
            call.respond(receiveUploads(services, call, user))
        }
        delete("/{id}") {
            val user = call.requireUser(services)
            services.uploads.delete(user.id, listOf(call.parameters["id"].orEmpty()))
            call.respond(Ok())
        }
    }

    get("/api/contacts") {
        val user = call.requireUser(services)
        val query = call.request.queryParameters["q"].orEmpty()
        call.respond(if (query.isBlank()) emptyList() else services.contacts.search(user.id, query))
    }

    get("/api/mail/new") {
        val user = call.requireUser(services)
        call.respond(services.mail.newMail(user, call.request.queryParameters["cursor"]))
    }

    // Signed links (no sign-in needed): attachments, images in messages, whole messages.
    get("/api/parts/{token}/{name?}") {
        val target = services.links.open(call.parameters["token"].orEmpty()) ?: throw ApiError.notFound("That link has expired; open the message again")
        val data = services.mail.part(target.ref, if (target.raw) "" else target.part)
        val filename = call.parameters["name"]?.takeIf { it.isNotBlank() } ?: data.filename
        val showable = data.contentType in INLINE_TYPES && call.request.queryParameters["download"] != "1"
        call.response.header(HttpHeaders.ContentDisposition, disposition(if (showable) "inline" else "attachment", filename))
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        val type = if (target.raw) ContentType.parse("message/rfc822") else runCatching { ContentType.parse(data.contentType) }.getOrDefault(ContentType.Application.OctetStream)
        call.respondBytes(data.bytes, if (showable || target.raw) type else ContentType.Application.OctetStream)
    }

    // Remote images in messages, fetched by the server so senders don't see who opened them, or from where.
    get("/api/proxy") {
        val params = call.request.queryParameters
        val url = services.links.proxied(params["u"].orEmpty(), params["s"].orEmpty()) ?: throw ApiError.notFound()
        val (type, bytes) = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; SuperAwesomeMagicalEmail image proxy)")
                .header("Accept", "image/avif,image/webp,image/png,image/jpeg,image/gif,image/*;q=0.8")
                .build()
            runCatching {
                services.imageHttp.newCall(request).execute().use { response ->
                    val type = response.body?.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase()
                    if (!response.isSuccessful || type == null || type !in INLINE_TYPES) return@use null
                    val body = response.body!!.byteStream().use { readLimited(it, Links.MAX_IMAGE_BYTES) } ?: return@use null
                    type to body
                }
            }.getOrNull()
        } ?: throw ApiError.notFound("That image couldn't be loaded")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        call.respondBytes(bytes, ContentType.parse(type), HttpStatusCode.OK)
    }
}

/** Kinds of file safe to show in the browser (raster images: never HTML or SVG, which can run scripts). */
private val INLINE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp")

private fun disposition(kind: String, filename: String): String {
    val ascii = filename.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
    return "$kind; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

private fun readLimited(input: InputStream, max: Long): ByteArray? {
    val bytes = input.readNBytes((max + 1).toInt())
    return bytes.takeIf { it.size <= max }
}

private suspend fun receiveUploads(services: Services, call: ApplicationCall, user: User): List<Upload> {
    val max = services.config.maxMessageBytes
    val saved = mutableListOf<Upload>()
    val multipart = call.receiveMultipart(formFieldLimit = max + 1024 * 1024)
    multipart.forEachPart { part ->
        try {
            if (part is PartData.FileItem) {
                val (id, file) = services.uploads.newFile()
                val size = withContext(Dispatchers.IO) { part.provider().toInputStream().use { copyLimited(it, file, max) } }
                if (size < 0) {
                    file.delete()
                    throw ApiError.tooLarge("\"${part.originalFileName ?: "That file"}\" is over ${max / (1024 * 1024)} MB, the most a message can carry")
                }
                saved += services.uploads.record(user.id, id, part.originalFileName ?: "attachment", part.contentType?.toString() ?: "application/octet-stream", size)
            }
        } finally {
            part.dispose()
        }
    }
    if (saved.isEmpty()) throw ApiError.badRequest("No file came with that")
    return saved
}

/** Copies up to [max] bytes; -1 if there was more. */
private fun copyLimited(input: InputStream, file: File, max: Long): Long {
    var total = 0L
    file.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) return -1
            out.write(buffer, 0, read)
        }
    }
    return total
}
