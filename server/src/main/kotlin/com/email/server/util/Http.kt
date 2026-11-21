package com.email.server.util

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The body as text, at most [max] bytes: past that, an [IOException]. */
fun Response.boundedString(max: Int): String {
    val body = body ?: return ""
    val bytes = body.byteStream().use { it.readNBytes(max + 1) }
    if (bytes.size > max) throw IOException("The answer was too big")
    return String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
}

/** A failed request to another service, with its HTTP status. */
class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    /** The server's one HTTP client, for identity providers and remote images. */
    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Only for SuperAwesomeMagicalOrchestrator's published keys. Its address is the admin's own
     * choice (the Admin page or SAME_ORCHESTRATOR_URL, never anything a stranger sends), and like
     * Authentik it usually lives on the home network, so private addresses are reached, as the
     * identity provider's are. No redirects, so the answer comes from the address the admin set.
     * (Nothing to do with SAME_ALLOW_PRIVATE_MAIL_HOSTS, which is about mail servers people name.)
     */
    fun orchestratorClient(): OkHttpClient = createClient().newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/** Runs [request] without blocking a thread, handing the response to [read] (on OkHttp's thread). */
suspend fun <T> OkHttpClient.call(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use(read) }
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        },
    )
}

suspend fun OkHttpClient.fetchString(request: Request): String = call(request) { response ->
    val body = response.body?.string().orEmpty()
    if (!response.isSuccessful) {
        throw HttpException(response.code, "${request.method} ${request.url.encodedPath} failed: HTTP ${response.code} ${body.take(200)}")
    }
    body
}

/**
 * Another service's answer as text, at most [max] bytes once unzipped (OkHttp unzips gzip itself, so
 * the size on the wire says nothing): a bigger one is refused rather than filling the memory.
 */
suspend fun OkHttpClient.fetchString(request: Request, max: Int): String = call(request) { response ->
    val body = response.boundedString(max)
    if (!response.isSuccessful) {
        throw HttpException(response.code, "${request.method} ${request.url.encodedPath} failed: HTTP ${response.code} ${body.take(200)}")
    }
    body
}
