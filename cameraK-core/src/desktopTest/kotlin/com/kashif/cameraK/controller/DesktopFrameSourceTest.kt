@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.RetainedFrame
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop `FrameSource` (§8 desktop, D4'), over the engine rig's scripted camera: the single
 * producer publishes leased frames, converts them to the session's analysis format only while a
 * registration takes them, and hands each analyzer the latest frame on its own conflated worker
 * through the common `AnalyzerGate`. The desktop counterparts of A4''s and I4''s delivery cases.
 */
class DesktopFrameSourceTest {
    @Test
    fun noYuvConversionWithoutARegistration() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            repeat(3) { steps.frameAt(1_000L + it * 40) }

            assertEquals(nanos(1_080).single(), sut.preview.frames.value?.timestampNanos, "the preview's last frame")
            assertEquals(0L, sut.grabber.yuvConversions.get(), "YUV conversions with no registration")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun registerStartsAndUnregisterStopsYuvConversion() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        rig.camera.fill { _, _ -> 0x646464 }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            assertEquals(0L, sut.grabber.yuvConversions.get(), "YUV conversions before the registration")

            val seen = CopyOnWriteArrayList<String>()
            val registration = sut.frames.register(FrameSpec()) { frame ->
                val luma = ByteArray(frame.width * frame.height)
                frame.planes[0].copyTo(luma, 0, 0, luma.size)
                val chroma = ByteArray(2)
                frame.planes[1].copyTo(chroma, 0, 0, 1)
                frame.planes[2].copyTo(chroma, 1, 0, 1)
                seen += "${frame.format} ${frame.width}x${frame.height} " +
                    "y=${luma.map { it.toInt() and 0xFF }.toSet()} uv=${chroma.map { it.toInt() and 0xFF }}"
            }
            assertTrue(registration.active.value, "a desktop registration is active at once")
            assertNull(registration.rejection)
            steps.frameAt(1_000)

            assertEquals(listOf("YUV_420 64x36 y=[100] uv=[128, 128]"), seen.toList(), "the analyzed frame")
            assertEquals(1L, sut.grabber.yuvConversions.get(), "YUV conversions with one registration")

            registration.close()
            assertFalse(registration.active.value, "a closed registration is active")
            repeat(2) { steps.frameAt(1_040L + it * 40) }

            assertEquals(1, seen.size, "frames after the registration closed")
            assertEquals(1L, sut.grabber.yuvConversions.get(), "YUV conversions after the registration closed")
            assertEquals(1, rig.camera.startCount, "registering or unregistering restarted the camera")
            assertEquals(0, rig.camera.releaseCount, "registering or unregistering released the camera")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun busyAnalyzerMissesFramesAndNeverQueues() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val proceed = CountDownLatch(1)
        val parked = AtomicInteger(0)
        rig.parked += { parked.get() }
        val slow = CopyOnWriteArrayList<Long>()
        val fast = CopyOnWriteArrayList<Long>()
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec()) { frame ->
                slow += frame.timestampNanos
                if (slow.size == 1) {
                    parked.set(1)
                    rig.workers.bump()
                    proceed.await()
                    parked.set(0)
                    rig.workers.bump()
                }
            }
            sut.frames.register(FrameSpec()) { frame -> fast += frame.timestampNanos }

            for (millis in listOf(1_000L, 1_040L, 1_080L, 1_120L, 1_160L)) steps.frameAt(millis)
            assertEquals(nanos(1_000, 1_040, 1_080, 1_120, 1_160), fast.toList(), "the other analyzer's frames")
            assertEquals(nanos(1_000), slow.toList(), "the busy analyzer's frames while busy")

            proceed.countDown()
            rig.settle()
            steps.frameAt(1_200)

            assertEquals(nanos(1_000, 1_200), slow.toList(), "a busy analyzer gets the next frame, never a backlog")
            assertEquals(6, fast.size, "the other analyzer's frames")
        } finally {
            proceed.countDown()
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun throwingAnalyzerIsContainedAndItsLeaseReleased() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val thrown = AtomicInteger(0)
        val other = AtomicInteger(0)
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec()) {
                thrown.incrementAndGet()
                throw IllegalStateException("scripted analyzer failure")
            }
            sut.frames.register(FrameSpec()) { other.incrementAndGet() }

            repeat(FRAMES) { steps.frameAt(1_000L + it * 40) }

            assertEquals(FRAMES, thrown.get(), "the throwing analyzer is idle again after each throw")
            assertEquals(FRAMES, other.get(), "the other analyzer's frames")
            // The two the producer holds for the preview and the one it converts into: every frame
            // the throwing analyzer was handed came back.
            assertTrue(sut.grabber.slotsAllocated <= 3, "slots after $FRAMES frames: ${sut.grabber.slotsAllocated}")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun retainBeyondMaxRetainedSkipsOnlyThatAnalyzer() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(CameraConfiguration(analysisFormat = FrameFormat.RGBA_8888))
        val kept = CopyOnWriteArrayList<RetainedFrame>()
        val keptPixels = CopyOnWriteArrayList<ByteArray>()
        val other = AtomicInteger(0)
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec(maxRetained = 1)) { frame ->
                kept += frame.retain()
                keptPixels += frame.pixels()
            }
            sut.frames.register(FrameSpec()) { other.incrementAndGet() }

            for (value in 1..4) {
                rig.camera.fill { _, _ -> value * 0x010101 }
                steps.frameAt(1_000L + value * 40)
            }

            assertEquals(1, kept.size, "frames reaching the analyzer at its maxRetained")
            assertEquals(4, other.get(), "the other analyzer's frames")
            assertContentEquals(keptPixels.single(), kept.single().pixels(), "the retained frame was overwritten")

            kept.single().close()
            rig.camera.fill { _, _ -> 0x050505 }
            steps.frameAt(1_200)

            assertEquals(2, kept.size, "frames reaching the analyzer once it let go")
        } finally {
            kept.forEach { it.close() }
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun unacceptedFormatIsRejectedAndNeverActive() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(CameraConfiguration(analysisFormat = FrameFormat.RGBA_8888))
        val delivered = AtomicInteger(0)
        try {
            steps.open(sut)
            val registration = sut.frames.register(FrameSpec(acceptedFormats = setOf(FrameFormat.YUV_420))) {
                delivered.incrementAndGet()
            }

            val rejection = assertIs<CameraKError.Unsupported>(registration.rejection, "the rejection")
            assertEquals(CameraFeature.ANALYSIS_FORMAT, rejection.feature)
            assertFalse(registration.active.value, "a rejected registration is active")
            repeat(3) { steps.frameAt(1_000L + it * 40) }

            assertEquals(0, delivered.get(), "frames delivered to a rejected registration")
            assertFalse(registration.active.value, "a rejected registration became active")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun analysisResolutionSizesYuvFramesWithoutStretching() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        // The default 16:9 crop leaves 64 x 36 of the 64 x 48 camera; a square analysis frame is the
        // centred 36 x 36 of that, columns 14 to 49, so only the grey band reaches it.
        rig.camera.fill { x, _ -> if (x in 14 until 50) 0xC8C8C8 else 0x000000 }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(CameraConfiguration(analysisResolution = Resolution(20, 20)))
        val seen = CopyOnWriteArrayList<String>()
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec()) { frame -> seen += frame.describe() }
            steps.frameAt(1_000)

            assertEquals(
                listOf("YUV_420 20x20 crop=0,0,20,20 planes=[20/1/400, 10/1/100, 10/1/100] y=[200] uv=[128, 128]"),
                seen.toList(),
                "the analysis frame at analysisResolution 20 x 20",
            )
            assertEquals(64 to 36, sut.grabber.frameSize, "the preview's frame size")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun analysisResolutionSizesRgbaFrames() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        rig.camera.fill { _, _ -> 0x102030 }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(
            CameraConfiguration(analysisFormat = FrameFormat.RGBA_8888, analysisResolution = Resolution(32, 18)),
        )
        val seen = CopyOnWriteArrayList<String>()
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec(synchronous = true)) { frame ->
                val pixels = frame.pixels()
                val distinct = (pixels.indices step 4).map { pixels.sliceArray(it until it + 4).toList() }.toSet()
                seen += "${frame.format} ${frame.width}x${frame.height} " +
                    "planes=${frame.planes.map { "${it.rowStride}/${it.pixelStride}/${it.size}" }} " +
                    "rgba=${distinct.map { pixel -> pixel.map { it.toInt() and 0xFF } }}"
            }
            steps.frameAt(1_000)

            assertEquals(
                listOf("RGBA_8888 32x18 planes=[128/4/2304] rgba=[[16, 32, 48, 255]]"),
                seen.toList(),
                "the analysis frame at analysisResolution 32 x 18",
            )
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aFrameThatCannotBeConvertedIsSkippedAndItsLeaseReleased() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        // No image has a 0 x 0 size, so every analysis conversion throws.
        val sut = rig.engine(CameraConfiguration(analysisResolution = Resolution(0, 0)))
        val delivered = AtomicInteger(0)
        try {
            steps.open(sut)
            sut.frames.register(FrameSpec()) { delivered.incrementAndGet() }

            repeat(FRAMES) { steps.frameAt(1_000L + it * 40) }

            assertEquals(0, delivered.get(), "frames delivered that could not be converted")
            assertEquals(
                nanos(1_000L + (FRAMES - 1) * 40).single(),
                sut.preview.frames.value?.timestampNanos,
                "the preview's last frame",
            )
            assertTrue(sut.grabber.slotsAllocated <= 3, "slots after $FRAMES frames: ${sut.grabber.slotsAllocated}")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    private fun CameraFrame.describe(): String {
        val luma = ByteArray(width * height)
        planes[0].copyTo(luma, 0, 0, luma.size)
        val chroma = ByteArray(2)
        planes[1].copyTo(chroma, 0, 0, 1)
        planes[2].copyTo(chroma, 1, 0, 1)
        return "$format ${width}x$height crop=${cropRect.left},${cropRect.top},${cropRect.right},${cropRect.bottom} " +
            "planes=${planes.map { "${it.rowStride}/${it.pixelStride}/${it.size}" }} " +
            "y=${luma.map { it.toInt() and 0xFF }.toSet()} uv=${chroma.map { it.toInt() and 0xFF }}"
    }

    private fun CameraFrame.pixels(): ByteArray = ByteArray(planes[0].size).also { planes[0].copyTo(it) }

    private fun nanos(vararg millis: Long): List<Long> = millis.map { it * DesktopRecordingSteps.NANOS_PER_MILLI }

    private companion object {
        const val FRAMES = 20
    }
}
