@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, InternalCameraKApi::class)

package com.kashif.mediasaverplugin

import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal val BOUND = 5.seconds

internal class Completed<T>(val value: T)

/**
 * Runs [block] on a background thread of [scope] and waits at most [timeout] for it while this
 * thread, the main one, stays busy. Returns null on timeout; a blocked call is abandoned, not joined.
 */
internal fun <T> CoroutineScope.completesWithin(timeout: Duration, block: suspend () -> T): Completed<T>? {
    // Started in this scope, not runBlocking's: a block left waiting must not keep runBlocking from returning.
    val result = async { Completed(block()) }
    return runBlocking { withTimeoutOrNull(timeout) { result.await() } }
}

/** Like [completesWithin], but turns the main run loop while it waits, so work delivered on the main thread runs. */
internal fun <T> CoroutineScope.completesWhileMainRuns(timeout: Duration, block: suspend () -> T): Completed<T>? {
    val result = async { Completed(block()) }
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!result.isCompleted && deadline.hasNotPassedNow()) {
        NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow(0.01))
    }
    return if (result.isCompleted) runBlocking { result.await() } else null
}

/** A new app-owned file in the temporary directory holding [bytes]; the caller deletes it. */
internal fun appOwnedFile(bytes: ByteArray, extension: String): String {
    val path = "${NSTemporaryDirectory()}camerak-saver-${NSUUID().UUIDString}.$extension"
    check(NSFileManager.defaultManager.createFileAtPath(path, bytes.toNSData(), null)) {
        "could not write the test file"
    }
    return path
}

internal fun fileBytes(path: String): ByteArray? = NSFileManager.defaultManager.contentsAtPath(path)?.toByteArray()

internal fun deleteFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}
