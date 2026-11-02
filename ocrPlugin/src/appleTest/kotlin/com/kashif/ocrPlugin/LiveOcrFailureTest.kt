package com.kashif.ocrPlugin

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.CMVideoFormatDescriptionRefVar
import platform.CoreMedia.kCMTimeFlags_Valid
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSError
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import kotlin.concurrent.AtomicInt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * When Vision fails on a live frame, the failure is logged once with its cause, and the frame is
 * released so live OCR reads the next one.
 */
@OptIn(ExperimentalForeignApi::class)
class LiveOcrFailureTest {
    private val errors = mutableListOf<Pair<String, Throwable?>>()
    private val queue = dispatch_queue_create("com.kashif.ocrPlugin.test.liveOcr", null)
    private var nowNanos = 0L

    @BeforeTest
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            if (level == CameraKLogger.Level.ERROR) errors += message to throwable
        }
    }

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun aVisionErrorReportedToTheRequest_isLoggedOnceWithItsCause() {
        val delegate = delegate { _, request ->
            request.completionHandler?.invoke(request, visionError())
            null
        }

        withFrame { frame -> feed(delegate, frame) }

        assertEquals(1, errors.size, "error lines for one failed frame: $errors")
        assertNotNull(errors.single().second, "the failure was logged without its cause")
    }

    @Test
    fun visionFailingWithoutCallingBack_isLoggedAndTheNextFrameIsRead() {
        val framesHandedToVision = AtomicInt(0)
        val delegate = delegate { _, _ ->
            framesHandedToVision.incrementAndGet()
            visionError()
        }

        withFrame { frame ->
            feed(delegate, frame)
            nowNanos += SECOND
            feed(delegate, frame)
        }

        assertEquals(
            2,
            framesHandedToVision.value,
            "frames handed to Vision after the first failed without calling back",
        )
        assertEquals(2, errors.size, "error lines for two failed frames: $errors")
        assertTrue(errors.all { it.second != null }, "a failure was logged without its cause: $errors")
    }

    @Test
    fun anExceptionWhileReadingAFrame_isLoggedWithItsCause() {
        val failure = IllegalStateException("Vision could not run")
        val delegate = delegate { _, _ -> throw failure }

        withFrame { frame -> feed(delegate, frame) }

        assertEquals(1, errors.size, "error lines for one failed frame: $errors")
        assertSame(failure, errors.single().second)
    }

    private fun delegate(performer: LiveVisionPerformer) =
        VideoDataDelegate({}, DEFAULT_LIVE_OCR_MAX_FPS, performer, { nowNanos }, queue)

    /** Hands [frame] to [delegate] and waits until the delegate's queue has finished with it. */
    private fun feed(delegate: VideoDataDelegate, frame: CMSampleBufferRef) {
        delegate.onFrame(frame)
        dispatch_sync(queue) {}
    }

    private fun visionError() = NSError.errorWithDomain("com.apple.Vision", 9, null)

    /** Runs [block] with a 16x16 BGRA sample buffer that is released afterwards. */
    private fun withFrame(block: (CMSampleBufferRef) -> Unit) {
        val frame = memScoped {
            val pixels = alloc<CVPixelBufferRefVar>()
            check(CVPixelBufferCreate(kCFAllocatorDefault, 16u, 16u, kCVPixelFormatType_32BGRA, null, pixels.ptr) == 0)
            val format = alloc<CMVideoFormatDescriptionRefVar>()
            val timing = alloc<CMSampleTimingInfo>()
            timing.presentationTimeStamp.value = 0
            timing.presentationTimeStamp.timescale = 600
            timing.presentationTimeStamp.flags = kCMTimeFlags_Valid
            val sampleBuffer = alloc<CMSampleBufferRefVar>()
            try {
                check(CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pixels.value, format.ptr) == 0)
                check(
                    CMSampleBufferCreateReadyWithImageBuffer(
                        kCFAllocatorDefault,
                        pixels.value,
                        format.value,
                        timing.ptr,
                        sampleBuffer.ptr,
                    ) == 0,
                )
            } finally {
                format.value?.let(::CFRelease)
                CFRelease(pixels.value)
            }
            checkNotNull(sampleBuffer.value)
        }
        try {
            block(frame)
        } finally {
            CFRelease(frame)
        }
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
