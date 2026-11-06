package com.kashif.analyzerplugin

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
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
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.natural_tVar
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The iOS encoder over 420f frames: nothing kept per frame, and nothing moved onto the main queue. */
@OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
class AnalyzerFrameTest {
    @Test
    fun convertingFramesDoesNotKeepADecodedImagePerFrame() {
        val frame = BiPlanarFrame(FRAME_WIDTH, FRAME_HEIGHT, lumaLevel = 0x80)
        val encoder = CoreImageFrameEncoder(AnalyzerConfig { maxDimension = null }, copyingRenderer)
        var delivered = 0

        repeat(WARM_UP_FRAMES) { if (encoder.encode(frame) != null) delivered++ }
        val before = settledFootprint()
        repeat(MEASURED_FRAMES) { if (encoder.encode(frame) != null) delivered++ }
        val growth = settledFootprint() - before

        assertEquals(WARM_UP_FRAMES + MEASURED_FRAMES, delivered, "every frame should be encoded")
        // A leaked CGImage keeps one decoded frame alive, and a leaked pixel buffer most of one, so a
        // leak grows the footprint by about a frame per conversion (MEASURED_FRAMES frames in all).
        // Five frames of slack absorbs allocator and CoreImage cache noise and is far below that.
        val limit = SLACK_FRAMES * DECODED_FRAME_BYTES
        assertTrue(
            growth < limit,
            "footprint grew ${growth / MB} MB over $MEASURED_FRAMES frames (limit ${limit / MB} MB, " +
                "${DECODED_FRAME_BYTES / MB} MB per decoded frame)",
        )
    }

    @Test
    fun framesAreConvertedOnAPrivateQueueNotTheMainQueue() {
        val convertedOnMain = atomic<Boolean?>(null)
        val renderer = FrameRenderer { pixels, width, height ->
            convertedOnMain.value = NSThread.isMainThread
            copyingRenderer.render(pixels, width, height)
        }
        val encoder = CoreImageFrameEncoder(AnalyzerConfig(), renderer)
        // The frame source calls analyze on its own serial worker; this queue stands in for it.
        val worker = dispatch_queue_create("com.kashif.analyzerplugin.test.worker", null)
        val encoded = atomic<Result<EncodedFrame?>?>(null)

        val done = dispatch_semaphore_create(0)
        dispatch_async(worker) {
            // A throwable escaping a dispatch block would end the test process; it is rethrown below.
            encoded.value = runCatching { encoder.encode(BiPlanarFrame(64, 48, lumaLevel = 0x40)) }
            dispatch_semaphore_signal(done)
        }
        // Main waits here, so an encoder that hopped to the main queue would never finish.
        val timedOut = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC.toLong()))

        assertEquals(0L, timedOut, "encoding on the worker did not finish within 5 s")
        assertNotNull(assertNotNull(encoded.value).getOrThrow(), "the frame was not encoded")
        assertEquals(false, convertedOnMain.value, "a frame encoded on the worker was converted on main")
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
