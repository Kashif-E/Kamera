@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class, CameraKPlatformApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.controller.backCamera
import com.kashif.cameraK.controller.sampleBuffer420f
import com.kashif.cameraK.controller.spinUntil
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.testing.TestFrame
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A frame's pixel buffer has its base address locked for the length of analyze(), and a retained
 * frame keeps its own lock until it is closed (design §8). The `pixelBuffer` escape hatch is null on
 * a TestFrame and on an asynchronous registration's frame (§8, moved here from F0').
 */
class PixelBufferLockTest {
    /** Locks through Core Video, as production does, and counts the locks held per pixel buffer. */
    private class CountingLocker : PixelBufferLocker {
        private val lock = NSLock()
        private val held = mutableMapOf<Long, Int>()

        fun locks(buffer: CVPixelBufferRef): Int = locked { held[buffer.rawValue.toLong()] ?: 0 }

        override fun lock(buffer: CVPixelBufferRef): Boolean {
            CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
            locked { held[buffer.rawValue.toLong()] = (held[buffer.rawValue.toLong()] ?: 0) + 1 }
            return true
        }

        override fun unlock(buffer: CVPixelBufferRef) {
            locked { held[buffer.rawValue.toLong()] = (held[buffer.rawValue.toLong()] ?: 0) - 1 }
            CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
        }

        private fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }

    private val locker = CountingLocker()
    private val driver = AppleEngineDriver(pixelBufferLocker = locker)

    @Test
    fun baseAddressLockedForAnalyzeAndRetainedFrame() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val buffer = sampleBuffer420f()
        val pixels = checkNotNull(CMSampleBufferGetImageBuffer(buffer))
        val locksDuringAnalyze = atomic(-1)
        val kept = atomic<RetainedFrame?>(null)
        val registration = engine.frames.register(FrameSpec()) { frame ->
            locksDuringAnalyze.value = locker.locks(pixels)
            kept.value = frame.retain()
        }
        driver.settle()

        assertTrue(driver.session.deliverSampleBuffer(buffer, driver.back.input), "no frame output took the buffer")
        assertTrue(spinUntil(5.0) { kept.value != null }, "the asynchronous analyzer never ran")
        assertEquals(1, locksDuringAnalyze.value, "base-address locks while analyze ran")
        assertTrue(spinUntil(5.0) { locker.locks(pixels) == 1 }, "locks once analyze returned: ${locker.locks(pixels)}")
        assertEquals(1, locker.locks(pixels), "the retained frame's lock after analyze returned")

        kept.value!!.close()
        assertEquals(0, locker.locks(pixels), "locks once the retained frame closed")
        CFRelease(buffer)
        registration.close()
        engine.close()
    }

    @Test
    fun pixelBufferNullOnATestFrame() {
        val frame = TestFrame.luma(width = 4, height = 2, bytes = ByteArray(8))
        assertNull(frame.pixelBuffer, "a TestFrame's pixel buffer")
        assertNull(frame.retain().pixelBuffer, "a retained TestFrame's pixel buffer")
    }

    @Test
    fun pixelBufferOnlyOnASynchronousRegistrationsFrame() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val buffer = sampleBuffer420f()
        val pixels = checkNotNull(CMSampleBufferGetImageBuffer(buffer))
        val synchronousSaw = atomic<Long?>(null)
        val asynchronousRan = atomic(false)
        val asynchronousSaw = atomic<Long?>(null)
        val synchronous = engine.frames.register(FrameSpec(synchronous = true)) { frame ->
            synchronousSaw.value = frame.pixelBuffer?.rawValue?.toLong()
        }
        val asynchronous = engine.frames.register(FrameSpec()) { frame ->
            asynchronousSaw.value = frame.pixelBuffer?.rawValue?.toLong()
            asynchronousRan.value = true
        }
        driver.settle()

        assertTrue(driver.session.deliverSampleBuffer(buffer, driver.back.input), "no frame output took the buffer")
        assertTrue(spinUntil(5.0) { asynchronousRan.value }, "the asynchronous analyzer never ran")
        assertEquals(pixels.rawValue.toLong(), synchronousSaw.value, "the synchronous registration's pixel buffer")
        assertNull(asynchronousSaw.value, "an asynchronous registration's frame exposed its pixel buffer")
        CFRelease(buffer)
        synchronous.close()
        asynchronous.close()
        engine.close()
    }
}
