package com.kashif.cameraK.permissions

import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVMediaType
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAccessLevel
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.fail

/**
 * AVFoundation and Photos as the checker sees them: statuses by media type, one photo status, and
 * requests that answer [grant] from a background queue, as the system's completion handlers do.
 * [photoAccessLevels] false is iOS 13, which has no add-only level.
 */
internal class FakeApplePermissionPlatform(
    val capture: MutableMap<AVMediaType, AVAuthorizationStatus> = mutableMapOf(),
    var photo: PHAuthorizationStatus = PHAuthorizationStatusNotDetermined,
    private val grant: Boolean = true,
    override val photoAccessLevels: Boolean = true,
) : ApplePermissionPlatform {
    val captureReads = mutableListOf<AVMediaType>()
    val photoReads = mutableListOf<PHAccessLevel>()
    val captureRequests = mutableListOf<AVMediaType>()

    override fun captureStatus(mediaType: AVMediaType): AVAuthorizationStatus {
        captureReads += mediaType
        return capture[mediaType] ?: AVAuthorizationStatusNotDetermined
    }

    override fun photoStatus(accessLevel: PHAccessLevel): PHAuthorizationStatus {
        photoReads += accessLevel
        return photo
    }

    override fun requestCapture(mediaType: AVMediaType, completion: (granted: Boolean) -> Unit) {
        captureRequests += mediaType
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            capture[mediaType] = if (grant) AVAuthorizationStatusAuthorized else AVAuthorizationStatusDenied
            completion(grant)
        }
    }

    override fun requestPhoto(accessLevel: PHAccessLevel, completion: (PHAuthorizationStatus) -> Unit) {
        fail("no photo request expected")
    }
}

/** Runs [block] with [platform] as the checker's platform, then puts the system's back. */
internal fun <T> withPlatform(platform: ApplePermissionPlatform, block: () -> T): T {
    val previous = applePermissionPlatform
    applePermissionPlatform = platform
    try {
        return block()
    } finally {
        applePermissionPlatform = previous
    }
}

/** Waits until [condition] holds, servicing the main queue meanwhile. */
internal fun awaitOnMain(what: String, seconds: Double = 10.0, condition: () -> Boolean) {
    val deadline = NSDate().timeIntervalSince1970 + seconds
    while (!condition()) {
        if (NSDate().timeIntervalSince1970 > deadline) fail("Timed out after ${seconds}s waiting for $what")
        if (NSThread.isMainThread) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        } else {
            NSThread.sleepForTimeInterval(0.01)
        }
    }
}
