package com.email.server

import com.email.api.ApiJson
import com.email.api.ErrorBody
import com.email.server.admin.adminRoutes
import com.email.server.auth.authRoutes
import com.email.server.mail.MailProblem
import com.email.server.mail.mailRoutes
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

@Serializable
data class Health(val ok: Boolean, val version: String)

private val log = LoggerFactory.getLogger("same")

/** The server's web app: the API under /api, and the web pages everywhere else. */
fun Application.module(services: Services) {
    install(ContentNegotiation) { json(ApiJson) }
    install(Compression) {
        gzip {
            minimumSize(1024)
            matchContentType(ContentType.Application.Json, ContentType.Text.Any, ContentType.Application.JavaScript)
        }
        deflate {
            minimumSize(1024)
            matchContentType(ContentType.Application.Json, ContentType.Text.Any, ContentType.Application.JavaScript)
        }
    }
    if (services.config.trustProxy) install(XForwardedHeaders)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "same-origin")
        header("X-Frame-Options", "SAMEORIGIN")
        // Messages are shown in a frame of this page (srcdoc, no scripts), which takes on this policy:
        // nothing loads from elsewhere; remote images come through the server's proxy.
        header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; " +
                "connect-src 'self'; font-src 'self' data:; frame-src 'self' about:; object-src 'none'; " +
                "frame-ancestors 'self'; base-uri 'self'; form-action 'self'",
        )
    }
    install(CallLogging) {
        level = Level.INFO
        // Part and image links carry tokens; they're not worth a line each anyway.
        filter { call ->
            val path = call.request.path()
            path.startsWith("/api/") && path != "/api/health" && !path.startsWith("/api/parts/") && !path.startsWith("/api/proxy")
        }
    }
    install(StatusPages) {
        exception<ApiError> { call, error -> call.respond(error.status, ErrorBody(error.message ?: "Error")) }
        exception<MailProblem> { call, error -> call.respond(HttpStatusCode.BadGateway, ErrorBody(error.message ?: "The mail server had a problem")) }
        exception<BadRequestException> { call, error ->
            val cause = generateSequence(error as Throwable) { it.cause }.firstOrNull { it is SerializationException }
            call.respond(HttpStatusCode.BadRequest, ErrorBody(cause?.message?.substringBefore('\n')?.let { "That request isn't right: $it" } ?: "That request isn't right"))
        }
        exception<Throwable> { call, error ->
            // A connection that ended (a closed tab, a phone going out of range) isn't a fault.
            if (error is kotlinx.coroutines.CancellationException) return@exception
            log.error("Failed: ${call.request.path()}", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("Something went wrong on the server"))
        }
    }

    routing {
        get("/api/health") { call.respond(Health(true, Services.version)) }
        authRoutes(services)
        adminRoutes(services)
        mailRoutes(services)
        route("/api/{...}") {
            handle { call.respond(HttpStatusCode.NotFound, ErrorBody("No such API")) }
        }

        // The web app. Its files carry hashes in their names, so they're cached for good; the
        // page itself is always checked, so an updated server is picked up at once.
        if (Services::class.java.getResource("/web/index.html") != null) {
            staticResources("/", "web") {
                default("index.html")
                cacheControl { url ->
                    if (url.path.contains("/assets/")) listOf(CacheControl.MaxAge(31_536_000, visibility = CacheControl.Visibility.Public))
                    else listOf(CacheControl.NoCache(null))
                }
            }
        } else {
            get("/") {
                call.respondText(
                    "The SuperAwesomeMagicalEmail server is running, but this build has no web app (build web/ first).",
                    ContentType.Text.Plain,
                )
            }
        }
    }
}
