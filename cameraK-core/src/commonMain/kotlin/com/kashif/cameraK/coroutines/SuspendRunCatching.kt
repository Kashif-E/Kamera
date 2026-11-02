package com.kashif.cameraK.coroutines

/**
 * The one broad catch for CameraK's holder and engines: [block]'s value, or its failure as a
 * [Result]. Cancellation is rethrown, never turned into an error, and so is a fatal throwable the
 * process cannot recover from ([isFatal]).
 */
internal suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> = TODO("S1' stub")
