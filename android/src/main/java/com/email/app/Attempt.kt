package com.email.app

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but a cancelled coroutine stays cancelled: when a screen closes, its work is
 * stopped on purpose, which is never a failure to show, and the work must stop rather than carry
 * on as if it had failed (as in SuperAwesomeMagicalHealth).
 */
inline fun <R> attempt(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
