package com.email.server

import io.ktor.http.HttpStatusCode

/** An error to show the person: [message] is safe to send to them as it is. */
class ApiError(val status: HttpStatusCode, message: String) : Exception(message) {
    companion object {
        fun badRequest(message: String) = ApiError(HttpStatusCode.BadRequest, message)
        fun unauthorized(message: String = "Sign in first") = ApiError(HttpStatusCode.Unauthorized, message)
        fun forbidden(message: String = "That needs an admin") = ApiError(HttpStatusCode.Forbidden, message)
        fun notFound(message: String = "Not found") = ApiError(HttpStatusCode.NotFound, message)
        fun conflict(message: String) = ApiError(HttpStatusCode.Conflict, message)
        fun gone(message: String) = ApiError(HttpStatusCode.Gone, message)
        fun tooLarge(message: String) = ApiError(HttpStatusCode.PayloadTooLarge, message)
        fun tooMany(message: String) = ApiError(HttpStatusCode.TooManyRequests, message)
        fun upstream(message: String) = ApiError(HttpStatusCode.BadGateway, message)
    }
}
