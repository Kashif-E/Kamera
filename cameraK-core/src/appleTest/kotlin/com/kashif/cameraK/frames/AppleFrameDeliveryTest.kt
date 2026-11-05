@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.controller.backCamera
import com.kashif.cameraK.controller.lumaBytes
import com.kashif.cameraK.controller.spinUntil
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The iOS frame source's delivery (design §8): one 420f video data output fans each sample buffer out
 * to every registration, synchronous ones on the output's queue and the others on their own serial
 * queue, as YUV_420 frames of two planes (luma, then interleaved chroma) whose rotation turns the
 * back camera's landscape buffer upright in portrait. The output is attached only for a registration
 * or `frameAnalysis`, one configuration transaction each way, and a registration that cannot read
 * YUV_420 is rejected.
 */
class AppleFrameDeliveryTest {
    private val driver = AppleEngineDriver()

    /** What an analyzer read from the frame it was given. */
    private class Seen(
        val width: Int,
        val height: Int,
        val format: FrameFormat,
        val rotation: Int,
        val timestamp: Long,
        val crop: List<Int>,
        val pixelStrides: List<Int>,
        val luma: ByteArray,
    )

    private fun CameraFrame.seen(): Seen {
        val luma = ByteArray(width * height)
        copyLuma(luma)
        return Seen(
            width,
            height,
            format,
            rotationDegrees,
            timestampNanos,
            listOf(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom),
            planes.map { it.pixelStride },
            luma,
        )
    }

    @Test
    fun aFrameReachesEveryRegistrationWithItsLumaAndGeometry() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration { frameAnalysis = true })
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val synchronous = atomic<Seen?>(null)
        val asynchronous = atomic<Seen?>(null)
        val first = engine.frames.register(FrameSpec(synchronous = true)) { synchronous.value = it.seen() }
        val second = engine.frames.register(FrameSpec()) { asynchronous.value = it.seen() }
        driver.settle()

        assertTrue(driver.deliverFrame(width = 8, height = 4, timestampNanos = 123_456_789), "no frame output")
        assertTrue(spinUntil(5.0) { asynchronous.value != null }, "the asynchronous analyzer never ran")
        for ((who, seen) in listOf("synchronous" to synchronous.value!!, "asynchronous" to asynchronous.value!!)) {
            assertEquals(8 to 4, seen.width to seen.height, "$who: size")
            assertEquals(FrameFormat.YUV_420, seen.format, "$who: format")
            assertEquals(listOf(1, 2), seen.pixelStrides, "$who: luma then interleaved chroma")
            assertEquals(90, seen.rotation, "$who: rotation of a back-camera frame in portrait")
            assertEquals(123_456_789L, seen.timestamp, "$who: timestamp")
            assertEquals(listOf(0, 0, 8, 4), seen.crop, "$who: crop")
            assertContentEquals(lumaBytes(8, 4), seen.luma, "$who: luma")
        }
        first.close()
        second.close()
        engine.close()
    }

    @Test
    fun aRegistrationThatCannotReadYuvIsRejectedAndNeverActive() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration { frameAnalysis = true })
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val called = atomic(false)
        val registration =
            engine.frames.register(FrameSpec(acceptedFormats = setOf(FrameFormat.RGBA_8888))) { called.value = true }
        driver.settle()

        val rejection = assertIs<CameraKError.Unsupported>(registration.rejection, "the rejection")
        assertEquals(CameraFeature.ANALYSIS_FORMAT, rejection.feature)
        assertFalse(registration.active.value, "a rejected registration became active")
        assertTrue(driver.deliverFrame(), "no frame output took the sample buffer")
        assertFalse(called.value, "a rejected registration's analyzer ran")
        registration.close()
        engine.close()
    }

    @Test
    fun theFrameOutputIsAttachedOnlyForARegistrationOneTransactionEachWay() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertTrue(driver.session.videoDataOutputs.isEmpty(), "a frame output with nothing registered")
        val begun = driver.session.configurationsBegun

        val registration = engine.frames.register(FrameSpec()) { }
        driver.settle()
        assertEquals(1, driver.session.videoDataOutputs.size, "frame outputs for one registration")
        assertTrue(registration.active.value, "the registration once its output was attached")
        assertEquals(begun + 1, driver.session.configurationsBegun, "transactions to attach the frame output")

        registration.close()
        driver.settle()
        assertTrue(driver.session.videoDataOutputs.isEmpty(), "the frame output after the last registration closed")
        assertFalse(registration.active.value, "a closed registration is still active")
        assertEquals(begun + 2, driver.session.configurationsBegun, "transactions to remove the frame output")
        engine.close()
    }
}
