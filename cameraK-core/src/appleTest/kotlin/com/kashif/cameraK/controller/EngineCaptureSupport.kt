@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetType
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Starts a capture on [engine] and settles [driver] until its request reaches the driver's photo
 * output. Fails when the capture returns without asking for a photo.
 */
internal suspend fun TestScope.captureReachingThePhotoOutput(
    driver: AppleEngineDriver,
    engine: CameraEngine,
): Pair<Deferred<CameraKResult<CapturedImage>>, FakePhotoCapturer.Request> {
    val before = driver.photoCapturer.requests.size
    val capture = async { engine.capture() }
    repeat(50) {
        driver.settle()
        if (driver.photoCapturer.requests.size > before) return capture to driver.photoCapturer.requests[before]
        if (capture.isCompleted) fail("capture returned ${capture.await()} without asking the photo output for a photo")
    }
    fail("the capture's request never reached the photo output")
}

/** Hands [request] AVFoundation's photo and last callback, then settles [driver] until [capture] returns. */
internal suspend fun <T> deliverPhoto(
    driver: AppleEngineDriver,
    request: FakePhotoCapturer.Request,
    capture: Deferred<T>,
    photo: NSData,
): T {
    request.callbacks.photoProcessed(photo, null)
    request.callbacks.captureFinished(null)
    repeat(50) {
        driver.settle()
        if (capture.isCompleted) return capture.await()
    }
    fail("the capture did not return after AVFoundation delivered its photo")
}

/** What ImageIO reads from the image file at [path]: its type, pixel size and EXIF orientation. */
internal class ImageFileProperties(val type: String?, val width: Int?, val height: Int?, val orientation: Int?)

internal fun imageFileProperties(path: String): ImageFileProperties {
    val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef
    val source = assertNotNull(CGImageSourceCreateWithURL(url, null), "the file is not an image")
    try {
        val type = CGImageSourceGetType(source)?.let { CFBridgingRelease(CFRetain(it)) as String }
        val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null)
            ?.let { CFBridgingRelease(it) as Map<*, *> }
            .orEmpty()
        return ImageFileProperties(
            type,
            properties["PixelWidth"].asInt(),
            properties["PixelHeight"].asInt(),
            properties["Orientation"].asInt(),
        )
    } finally {
        CFRelease(source)
        CFRelease(url)
    }
}

private fun Any?.asInt(): Int? = when (this) {
    is NSNumber -> intValue
    is Number -> toInt()
    else -> null
}

/** Removes the files a capture or recording test wrote. */
internal fun removeFiles(paths: Iterable<String>) {
    paths.forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
}
