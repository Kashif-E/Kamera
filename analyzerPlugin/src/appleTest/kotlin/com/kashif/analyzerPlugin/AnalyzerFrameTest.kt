package com.kashif.analyzerPlugin

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetDataSize
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.KERN_SUCCESS
import platform.darwin.NSEC_PER_SEC
import platform.darwin.TASK_VM_INFO
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.natural_tVar
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.memset
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
class AnalyzerFrameTest {
    @Test
    fun convertingFramesDoesNotKeepADecodedImagePerFrame() {
        val buffer = pixelBuffer(FRAME_WIDTH, FRAME_HEIGHT)
        try {
            var delivered = 0
            val analyzer = CameraAnalyzer(onFrameAvailable = { delivered++ }, renderer = copyingRenderer)

            repeat(WARM_UP_FRAMES) { analyzer.analyze(buffer) }
            val before = settledFootprint()
            repeat(MEASURED_FRAMES) { analyzer.analyze(buffer) }
            val growth = settledFootprint() - before

            assertEquals(WARM_UP_FRAMES + MEASURED_FRAMES, delivered, "every frame should reach the consumer")
            // A leaked CGImage keeps one decoded frame alive, so the leak grows the footprint by
            // about one frame per conversion (MEASURED_FRAMES frames in all). Five frames of slack
            // absorbs allocator and CoreImage cache noise and is far below that.
            val limit = SLACK_FRAMES * DECODED_FRAME_BYTES
            assertTrue(
                growth < limit,
                "footprint grew ${growth / MB} MB over $MEASURED_FRAMES frames (limit ${limit / MB} MB, " +
                    "${DECODED_FRAME_BYTES / MB} MB per decoded frame)",
            )
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    @Test
    fun framesAreConvertedOnAPrivateQueueNotTheMainQueue() {
        var added: AVCaptureOutput? = null
        val convertedOnMain = atomic<Boolean?>(null)
        val handle = startAnalyzer(
            addOutput = { added = it },
            removeOutput = {},
            onFrameAvailable = { convertedOnMain.value = NSThread.isMainThread },
            renderer = copyingRenderer,
        )
        val output = assertIs<AVCaptureVideoDataOutput>(added)
        val queue = assertNotNull(output.sampleBufferCallbackQueue, "the output needs a delegate queue")
        assertNotEquals<Any?>(dispatch_get_main_queue(), queue, "frames must not be delivered on the main queue")

        val buffer = pixelBuffer(64, 48)
        try {
            val analyzer = assertIs<CameraAnalyzer>(output.sampleBufferDelegate)
            val done = dispatch_semaphore_create(0)
            dispatch_async(queue) {
                analyzer.analyze(buffer)
                dispatch_semaphore_signal(done)
            }
            val timedOut = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC.toLong()))
            assertEquals(0L, timedOut, "conversion on the delegate queue did not finish within 5 s")
            assertEquals(false, convertedOnMain.value, "a frame delivered on the delegate queue was converted on main")
        } finally {
            CVPixelBufferRelease(buffer)
            handle.stop()
        }
    }

    @Test
    fun theDelegateStaysRegisteredUntilTheHandleIsStopped() {
        var added: AVCaptureOutput? = null
        var removed: AVCaptureOutput? = null
        val handle = startAnalyzer(
            addOutput = { added = it },
            removeOutput = { removed = it },
            onFrameAvailable = {},
            renderer = copyingRenderer,
        )
        val output = assertIs<AVCaptureVideoDataOutput>(added)

        // AVCaptureVideoDataOutput holds its delegate weakly; only the handle can keep it alive.
        settledFootprint()
        assertNotNull(output.sampleBufferDelegate, "the delegate was collected while the analyzer was running")

        handle.stop()
        assertNull(output.sampleBufferDelegate, "stop must detach the delegate")
        assertSame(output, removed, "stop must remove the output it added")
    }

    /**
     * A CPU stand-in for the CoreImage renderer, which cannot run here because the simulator test
     * process has no Metal device. Like CIContext.createCGImage it returns a +1 CGImage that owns
     * its own copy of the frame's pixels, so whoever leaks the image leaks one decoded frame.
     */
    private val copyingRenderer = FrameRenderer { buffer ->
        CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
        try {
            val height = CVPixelBufferGetHeight(buffer)
            val bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
            val pixels = CFDataCreate(
                null,
                CVPixelBufferGetBaseAddress(buffer)?.reinterpret(),
                (bytesPerRow * height).toLong(),
            )
            val provider = CGDataProviderCreateWithCFData(pixels)
            CFRelease(pixels)
            val colorSpace = CGColorSpaceCreateDeviceRGB()
            val image = CGImageCreate(
                CVPixelBufferGetWidth(buffer),
                height,
                8u,
                32u,
                bytesPerRow,
                colorSpace,
                kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value,
                provider,
                null,
                false,
                CGColorRenderingIntent.kCGRenderingIntentDefault,
            )
            CGColorSpaceRelease(colorSpace)
            CGDataProviderRelease(provider)
            image
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
        }
    }

    /** Collects garbage, lets deferred Objective-C releases run, and reads the process footprint. */
    private fun settledFootprint(): Long {
        repeat(3) {
            GC.collect()
            NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
        }
        return physFootprint()
    }

    private fun physFootprint(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<natural_tVar>()).toUInt()
        val result = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        check(result == KERN_SUCCESS) { "task_info failed: $result" }
        info.phys_footprint.toLong()
    }

    private fun pixelBuffer(width: Int, height: Int): CVPixelBufferRef = memScoped {
        val out = alloc<CVPixelBufferRefVar>()
        val status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width.toULong(),
            height.toULong(),
            kCVPixelFormatType_32BGRA,
            null,
            out.ptr,
        )
        check(status == kCVReturnSuccess) { "CVPixelBufferCreate failed: $status" }
        val buffer = checkNotNull(out.value)
        CVPixelBufferLockBaseAddress(buffer, 0u)
        memset(CVPixelBufferGetBaseAddress(buffer), 0x80, CVPixelBufferGetDataSize(buffer))
        CVPixelBufferUnlockBaseAddress(buffer, 0u)
        buffer
    }

    private companion object {
        const val FRAME_WIDTH = 1920
        const val FRAME_HEIGHT = 1440
        const val DECODED_FRAME_BYTES = FRAME_WIDTH.toLong() * FRAME_HEIGHT * 4
        const val WARM_UP_FRAMES = 3
        const val MEASURED_FRAMES = 30
        const val SLACK_FRAMES = 5
        const val MB = 1024L * 1024
    }
}
