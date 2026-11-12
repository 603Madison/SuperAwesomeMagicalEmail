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

/** A failed request to another service, with its HTTP status. */
class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    /** The server's one HTTP client, for identity providers and remote images. */
    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
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
