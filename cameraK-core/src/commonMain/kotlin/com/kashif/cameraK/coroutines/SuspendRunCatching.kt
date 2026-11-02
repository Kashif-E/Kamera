package com.kashif.cameraK.coroutines

import kotlin.coroutines.cancellation.CancellationException

/**
 * The one broad catch for CameraK's holder and engines: [block]'s value, or its failure as a
 * [Result]. Cancellation is rethrown, never turned into an error, and so is a fatal throwable the
 * process cannot recover from ([isFatal]).
 */
internal suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    if (e.isFatal()) throw e
    Result.failure(e)
}
