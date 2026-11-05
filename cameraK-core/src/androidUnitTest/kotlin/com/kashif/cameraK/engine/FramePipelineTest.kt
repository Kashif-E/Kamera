@file:OptIn(CameraKEngineApi::class, CameraKPlatformApi::class)

package com.kashif.cameraK.engine

import android.graphics.Rect
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.video.VideoCapture
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.RetainedFrame
import com.kashif.cameraK.frames.imageProxy
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.testing.TestFrame
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Android FrameSource (§8, §12.4 A4'): one ImageAnalysis bound per Decision 14, a multiplexer
 * that hands the ImageProxy to synchronous registrations, copies it once into a pooled slot for the
 * asynchronous ones, and closes it before analyze returns unless a 1.x consumer of the facade still
 * holds its share. Frames carry the size, rotation and crop rect the frame's metadata states (#168).
 *
 * Every asynchronous registration's worker is a dispatcher on the test scheduler, so a frame reaches
 * it on the next settle.
 */
@RunWith(RobolectricTestRunner::class)
class FramePipelineTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun analysisBoundAtStartWhenFlagSet() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration(frameAnalysis = true))

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        assertEquals(1, platform.backend.binding.bindCalls)
        assertEquals(
            1,
            platform.backend.binding.bound.filterIsInstance<ImageAnalysis>().size,
            "the first bind left out the declared ImageAnalysis",
        )
        assertTrue(platform.backend.analyzers.isEmpty(), "frames are delivered with no consumer registered")
        engine.closeAndJoin()
    }

    @Test
    fun retainingAnalyzerDoesNotReduceFramesToASecondAnalyzer() = runTest {
        val engine = openEngine()
        val held = mutableListOf<RetainedFrame>()
        var seenBySecond = 0
        engine.frames.register(FrameSpec(maxRetained = 1)) { held += it.retain() }
        engine.frames.register { seenBySecond++ }
        platform.settle(testScheduler)

        repeat(FRAMES) {
            deliver(FakeImageProxy.yuv())
            platform.settle(testScheduler)
        }

        assertEquals(1, held.size, "the retaining analyzer was not skipped at its maxRetained")
        assertEquals(FRAMES, seenBySecond, "the retaining analyzer cost the second analyzer frames")
        held.forEach { it.close() }
        engine.closeAndJoin()
    }

    @Test
    fun imageProxyClosedBeforeAnalyzeReturns() = runTest {
        val engine = openEngine()
        val copied = mutableListOf<ByteArray>()
        engine.frames.register { frame -> copied += frame.firstPlaneBytes() }
        platform.settle(testScheduler)
        val frame = FakeImageProxy.yuv()

        deliver(frame)

        assertEquals(1, frame.closes, "the ImageProxy was still open when analyze returned")
        assertTrue(copied.isEmpty(), "setup: the asynchronous analyzer ran before its worker")
        platform.settle(testScheduler)
        assertContentEquals(frame.firstPlane, copied.single(), "the analyzer's frame lost its pixels with the proxy")
        engine.closeAndJoin()
    }

    @Test
    fun withoutLegacyConsumersTheProxyIsClosedBeforeAnalyzeReturns() = runTest {
        val engine = openEngine()
        val held = mutableListOf<RetainedFrame>()
        val proxies = mutableListOf<ImageProxy?>()
        engine.frames.register { held += it.retain() }
        engine.frames.register(FrameSpec(synchronous = true)) { proxies += it.imageProxy }
        platform.settle(testScheduler)
        val frame = FakeImageProxy.yuv()

        deliver(frame)

        assertEquals(1, frame.closes, "with only FrameSource registrations the proxy outlived analyze")
        assertNotNull(proxies.single(), "setup: the synchronous registration had the proxy")
        platform.settle(testScheduler)
        assertEquals(1, held.size, "setup: the retaining analyzer kept its frame")
        assertContentEquals(frame.firstPlane, held.single().firstPlaneBytes(), "the retained frame's pixels")
        assertEquals(1, frame.closes, "the retained frame closed the proxy again")
        held.single().close()
        engine.closeAndJoin()
    }

    @Test
    fun registerAndUnregisterNeverRebind() = runTest {
        val engine = openEngine()
        val binds = platform.backend.binding.bindCalls

        val pooled = engine.frames.register { }
        val synchronous = engine.frames.register(FrameSpec(synchronous = true)) { }
        platform.settle(testScheduler)
        assertTrue(platform.backend.analyzers.isNotEmpty(), "the registrations' frames were never wired")
        pooled.close()
        synchronous.close()
        platform.settle(testScheduler)

        assertEquals(binds, platform.backend.binding.bindCalls, "registering or unregistering rebound the camera")
        assertTrue(platform.backend.analyzers.isEmpty(), "with no registration left, frames are still delivered")
        engine.closeAndJoin()
    }

    @Test
    fun lateRegistrationBindsOnceAndNeverDuringRecording() = runTest {
        // Without frameAnalysis the first bind has no ImageAnalysis: the first registration binds it, once.
        val engine = openEngine(CameraConfiguration())
        val binds = platform.backend.binding.bindCalls
        assertTrue(boundAnalyses().isEmpty(), "setup: the first bind had an ImageAnalysis")

        val first = engine.frames.register { }
        val second = engine.frames.register(FrameSpec(synchronous = true)) { }
        platform.settle(testScheduler)

        assertEquals(binds + 1, platform.backend.binding.bindCalls, "the late registrations did not bind once")
        assertEquals(1, boundAnalyses().size, "the late bind left out the ImageAnalysis")
        assertTrue(first.active.value && second.active.value, "a registration on the bound output is not active")
        engine.closeAndJoin()

        // During a recording a registration waits: a rebind would end the recording.
        val recordingPlatform = FakeAndroidPlatform()
        val recordingEngine = recordingPlatform.engine(testScheduler, runtime = testWorkers())
        open(recordingPlatform, recordingEngine)
        val (recording, _) = startRecording(recordingPlatform, recordingEngine)
        val bindsWhileRecording = recordingPlatform.backend.binding.bindCalls

        val waiting = recordingEngine.frames.register { }
        recordingPlatform.settle(testScheduler)

        assertEquals(
            bindsWhileRecording,
            recordingPlatform.backend.binding.bindCalls,
            "a registration rebound the camera during a recording",
        )
        assertFalse(waiting.active.value, "a registration waiting for the frame output is active")
        assertNull(waiting.rejection, "a waiting registration is not a rejected one")

        val stop = async { recordingEngine.stopRecording() }
        recordingPlatform.settle(testScheduler)
        recording.finalize()
        recordingPlatform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(stop.await(), "setup: the stop")

        assertEquals(
            bindsWhileRecording + 1,
            recordingPlatform.backend.binding.bindCalls,
            "the recording's end did not bind the ImageAnalysis exactly once",
        )
        assertEquals(1, recordingPlatform.backend.binding.bound.filterIsInstance<ImageAnalysis>().size)
        assertTrue(waiting.active.value, "the waiting registration did not become active once bound")
        recordingEngine.closeAndJoin()
    }

    @Test
    fun analysisResolutionAndFormatApplied() = runTest {
        val engine = openEngine(
            CameraConfiguration(
                frameAnalysis = true,
                analysisResolution = Resolution(640, 480),
                analysisFormat = FrameFormat.RGBA_8888,
            ),
        )
        val analysis = boundAnalyses().single()
        assertEquals(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888, analysis.outputImageFormat, "the output format")
        assertEquals(
            Size(640, 480),
            analysis.resolutionSelector?.resolutionStrategy?.boundSize,
            "the analysis resolution",
        )
        val formats = mutableListOf<FrameFormat>()
        engine.frames.register { formats += it.format }
        platform.settle(testScheduler)
        deliver(FakeImageProxy.rgba())
        platform.settle(testScheduler)
        assertEquals(listOf(FrameFormat.RGBA_8888), formats, "the frames' format")
        engine.closeAndJoin()

        // Without a resolution the analysis follows the preview's aspect ratio, in YUV_420, at CameraX's
        // own analysis size: a selector with no resolution strategy would make CameraX pick the
        // largest size the camera has, for every frame.
        val defaults = FakeAndroidPlatform()
        val defaultEngine = defaults.engine(
            testScheduler,
            CameraConfiguration(frameAnalysis = true, aspectRatio = AspectRatio.RATIO_16_9),
            runtime = testWorkers(),
        )
        open(defaults, defaultEngine)
        val defaultAnalysis = defaults.backend.binding.bound.filterIsInstance<ImageAnalysis>().single()
        assertEquals(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888, defaultAnalysis.outputImageFormat)
        assertEquals(
            Size(640, 480),
            defaultAnalysis.resolutionSelector?.resolutionStrategy?.boundSize,
            "with none configured the analysis is not at CameraX's VGA default",
        )
        assertEquals(
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
            defaultAnalysis.resolutionSelector?.aspectRatioStrategy,
            "the analysis does not follow the preview's aspect ratio",
        )
        defaultEngine.closeAndJoin()
    }

    @Test
    fun cropRectAndRotationReported() = runTest {
        val engine = openEngine()
        val seen = mutableListOf<String>()
        engine.frames.register { seen += "pooled ${it.describe()}" }
        engine.frames.register(FrameSpec(synchronous = true)) { seen += "synchronous ${it.describe()}" }
        platform.settle(testScheduler)

        deliver(
            FakeImageProxy.yuv(
                width = 640,
                height = 480,
                rotationDegrees = 270,
                cropRect = Rect(16, 8, 624, 472),
                timestampNanos = 123_456,
            ),
        )
        platform.settle(testScheduler)

        val expected = "640x480 at 123456, rotation 270, crop 16,8-624,472"
        assertEquals(listOf("synchronous $expected", "pooled $expected"), seen)
        engine.closeAndJoin()
    }

    /** Issue #168: a portrait frame's rotation reaches every consumer, so a 1D barcode decoder can turn it. */
    @Test
    fun portraitFrameRotationReachesTheConsumer() = runTest {
        val engine = openEngine()
        val rotations = mutableListOf<String>()
        engine.frames.register(FrameSpec(synchronous = true)) {
            rotations += "synchronous ${it.rotationDegrees}"
            rotations += "proxy ${it.imageProxy?.imageInfo?.rotationDegrees}"
        }
        engine.frames.register { frame ->
            rotations += "pooled ${frame.rotationDegrees}"
            frame.retain().use { rotations += "retained ${it.rotationDegrees}" }
        }
        engine.legacyController.registerImageAnalyzer { proxy ->
            rotations += "legacy ${proxy.imageInfo.rotationDegrees}"
            proxy.close()
        }
        platform.settle(testScheduler)

        deliver(FakeImageProxy.yuv(width = 640, height = 480, rotationDegrees = 90))
        platform.settle(testScheduler)

        assertEquals(
            listOf("synchronous 90", "proxy 90", "legacy 90", "pooled 90", "retained 90"),
            rotations,
            "a consumer lost the portrait frame's rotation",
        )
        engine.closeAndJoin()
    }

    @Test
    fun imageProxyNullForAsynchronousRegistration() = runTest {
        val engine = openEngine()
        val proxies = mutableListOf<String>()
        engine.frames.register { frame ->
            proxies += "frame ${frame.imageProxy}"
            frame.retain().use { proxies += "retained ${it.imageProxy}" }
        }
        platform.settle(testScheduler)

        deliver(FakeImageProxy.yuv())
        platform.settle(testScheduler)

        assertEquals(listOf("frame null", "retained null"), proxies)
        engine.closeAndJoin()
    }

    @Test
    fun synchronousRegistrationGetsTheProxy() = runTest {
        val engine = openEngine()
        val read = mutableListOf<String>()
        var pixels: ByteArray? = null
        engine.frames.register(FrameSpec(synchronous = true)) { frame ->
            val proxy = frame.imageProxy
            read += "${proxy?.width}x${proxy?.height} rotation ${proxy?.imageInfo?.rotationDegrees}"
            pixels = proxy?.planes?.get(0)?.buffer?.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
        }
        platform.settle(testScheduler)
        val frame = FakeImageProxy.yuv(width = 640, height = 480, rotationDegrees = 90)

        deliver(frame)

        assertEquals(listOf("640x480 rotation 90"), read, "the synchronous registration's proxy")
        assertContentEquals(frame.firstPlane, pixels, "the proxy's luma")
        assertEquals(1, frame.closes, "the proxy was not closed once after the synchronous analyzer")
        engine.closeAndJoin()
    }

    @Test
    fun imageProxyNullOnATestFrame() {
        assertNull(TestFrame.luma(2, 2, ByteArray(4)).imageProxy, "a TestFrame has no ImageProxy")
        assertNull(TestFrame.luma(2, 2, ByteArray(4)).retain().imageProxy, "nor does its retained frame")
    }

    @Test
    fun legacyConsumerClosingAfterAnalyzeReturnsReadsAnOpenImage() = runTest {
        val engine = openEngine()
        val kept = mutableListOf<ImageProxy>()
        // As the 1.x live OCR analyzer does: it hands the image on and closes the proxy later.
        engine.legacyController.registerImageAnalyzer { kept += it }
        platform.settle(testScheduler)
        val frame = FakeImageProxy.yuv()

        deliver(frame)

        val share = kept.single()
        assertEquals(0, frame.closes, "the real proxy closed while a 1.x consumer still holds it")
        val buffer = share.planes[0].buffer
        assertContentEquals(frame.firstPlane, ByteArray(buffer.remaining()).also(buffer::get), "the held share's luma")
        share.close()
        assertEquals(1, frame.closes, "the 1.x consumer's close did not close the real proxy")
        share.close()
        assertEquals(1, frame.closes, "a second close of the share closed the real proxy again")
        engine.closeAndJoin()
    }

    @Test
    fun legacyAndFrameSourceConsumersShareOneFrame() = runTest {
        val engine = openEngine()
        val shares = mutableListOf<ImageProxy>()
        val copied = mutableListOf<ByteArray>()
        engine.legacyController.registerImageAnalyzer { shares += it }
        engine.frames.register { copied += it.firstPlaneBytes() }
        platform.settle(testScheduler)
        val frame = FakeImageProxy.yuv()

        deliver(frame)
        platform.settle(testScheduler)

        assertContentEquals(frame.firstPlane, copied.single(), "the FrameSource registration's copy")
        val share = shares.single()
        val buffer = share.planes[0].buffer
        assertContentEquals(frame.firstPlane, ByteArray(buffer.remaining()).also(buffer::get), "the 1.x share")
        assertEquals(0, frame.closes, "the proxy closed under the 1.x consumer")
        share.close()
        assertEquals(1, frame.closes, "one ImageProxy, closed once after its last share")
        engine.closeAndJoin()
    }

    @Test
    fun unacceptedFormatIsRejectedAndNeverActive() = runTest {
        val engine = openEngine(CameraConfiguration(frameAnalysis = true, analysisFormat = FrameFormat.RGBA_8888))
        var rejectedFrames = 0
        var acceptedFrames = 0
        val rejected = engine.frames.register(FrameSpec(acceptedFormats = setOf(FrameFormat.YUV_420))) {
            rejectedFrames++
        }
        val accepted = engine.frames.register { acceptedFrames++ }
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.Unsupported>(rejected.rejection, "the YUV-only registration's rejection")
        assertEquals(CameraFeature.ANALYSIS_FORMAT, error.feature)
        assertFalse(rejected.active.value, "a rejected registration is active")
        assertTrue(accepted.active.value, "setup: the accepted registration is active")

        deliver(FakeImageProxy.rgba())
        platform.settle(testScheduler)

        assertEquals(0, rejectedFrames, "a rejected registration received a frame")
        assertEquals(1, acceptedFrames, "setup: the accepted registration received the frame")
        assertFalse(rejected.active.value, "a rejected registration became active")
        engine.closeAndJoin()
    }

    /**
     * Frame isolation (W7b must-fix 1): a pooled slot goes back to the pool only at its last release,
     * so the frames delivered after it cannot write over a frame another registration still retains.
     */
    @Test
    fun aRetainedFrameKeepsItsBytesAfterAnotherRegistrationReleasesItsSlot() = runTest {
        val engine = openEngine()
        var retained: RetainedFrame? = null
        val seenBySecond = mutableListOf<ByteArray>()
        engine.frames.register(FrameSpec(maxRetained = 1)) { frame -> if (retained == null) retained = frame.retain() }
        engine.frames.register { seenBySecond += it.firstPlaneBytes() }
        platform.settle(testScheduler)
        val first = FakeImageProxy.yuv(seed = 1)

        deliver(first)
        platform.settle(testScheduler)
        val later = (2..FRAMES).map { FakeImageProxy.yuv(seed = it) }
        later.forEach {
            deliver(it)
            platform.settle(testScheduler)
        }

        assertEquals(
            (listOf(first) + later).map { it.firstPlane.toList() },
            seenBySecond.map { it.toList() },
            "setup: the releasing registration saw every frame's own bytes",
        )
        val kept = assertNotNull(retained, "setup: the retaining registration kept the first frame")
        assertContentEquals(
            first.firstPlane,
            kept.firstPlaneBytes(),
            "the retained frame's slot went back to the pool at the other registration's release",
        )
        kept.close()
        engine.closeAndJoin()
    }

    /** W7b must-fix 2: a start on the same camera binds the ImageAnalysis a registration made while stopped. */
    @Test
    fun aStartOnTheSameCameraBindsTheAnalysisARegistrationMadeWhileStoppedAskedFor() = runTest {
        val engine = openEngine(CameraConfiguration())
        assertTrue(boundAnalyses().isEmpty(), "setup: the first bind had an ImageAnalysis")
        engine.stop()
        platform.settle(testScheduler)
        var frames = 0
        val registration = engine.frames.register { frames++ }
        platform.settle(testScheduler)
        assertFalse(registration.active.value, "setup: a registration while stopped is active with no ImageAnalysis")

        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        assertEquals(1, boundAnalyses().size, "the start on the same camera left out the ImageAnalysis")
        assertTrue(registration.active.value, "the registration made while stopped is not active after the start")
        deliver(FakeImageProxy.yuv())
        platform.settle(testScheduler)
        assertEquals(1, frames, "the registration made while stopped got no frame after the start")
        engine.closeAndJoin()
    }

    /**
     * W7b must-fix 3: a prepared VideoCapture stays bound when its recording ends, so no rebind of the
     * stop's includes the ImageAnalysis a registration made during the recording asked for: the end of
     * the recording binds it, once.
     */
    @Test
    fun theEndOfAPreparedRecordingBindsTheAnalysisARegistrationMadeDuringItAskedFor() = runTest {
        val engine = openEngine(CameraConfiguration())
        assertEquals(CameraKResult.Success(Unit), engine.prepareRecording(VideoConfiguration()))
        platform.settle(testScheduler)
        val prepared = platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>().single()
        val (recording, _) = startRecording(platform, engine)
        val binds = platform.backend.binding.bindCalls
        val waiting = engine.frames.register { }
        platform.settle(testScheduler)
        assertEquals(binds, platform.backend.binding.bindCalls, "setup: a registration rebound during the recording")

        val stop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        recording.finalize()
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(stop.await(), "setup: the stop")

        assertEquals(binds + 1, platform.backend.binding.bindCalls, "the prepared recording's end did not bind once")
        assertEquals(1, boundAnalyses().size, "the prepared recording's end left out the ImageAnalysis")
        assertTrue(waiting.active.value, "the registration made during the recording is not active once bound")
        assertSame(
            prepared,
            platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>().single(),
            "setup: the prepared VideoCapture stays bound",
        )
        engine.closeAndJoin()
    }

    /**
     * W7b must-fix 4: a bind that finishes on Main while a registration is being made, between its
     * read of whether the output is bound and its publish, does not leave the registration inactive.
     */
    @Test
    fun aBindFinishingDuringARegistrationLeavesItActive() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration(frameAnalysis = true), runtime = testWorkers())
        val pipeline = engine.framePipeline
        pipeline.beforePublishingActive = { pipeline.onBound(true) }

        val registration = engine.frames.register { }

        assertTrue(registration.active.value, "a bind that finished during the registration left it inactive")
        pipeline.beforePublishingActive = {}
        engine.closeAndJoin()
    }

    /** W7b must-fix 5: the engine's close unbinds the output, so a registration made after it is inactive. */
    @Test
    fun aRegistrationMadeAfterCloseIsInactive() = runTest {
        val engine = openEngine()
        assertTrue(engine.frames.register { }.active.value, "setup: a registration on the bound output is active")
        engine.closeAndJoin()

        val late = engine.frames.register { }
        platform.settle(testScheduler)

        assertFalse(late.active.value, "a registration made after close reports the closed engine's output as bound")
        late.close()
    }

    private suspend fun TestScope.openEngine(
        config: CameraConfiguration = CameraConfiguration(frameAnalysis = true),
    ): AndroidCameraEngine {
        val engine = platform.engine(testScheduler, config, runtime = testWorkers())
        open(platform, engine)
        return engine
    }

    // Each asynchronous registration's worker runs on the test scheduler.
    private fun TestScope.testWorkers() =
        EngineRuntime(newWorker = { WorkerDispatcher(StandardTestDispatcher(testScheduler)) })

    private fun boundAnalyses(): List<ImageAnalysis> = platform.backend.binding.bound.filterIsInstance<ImageAnalysis>()

    /** CameraX delivers [frame] to the bound ImageAnalysis's analyzer, on the calling thread. */
    private fun deliver(frame: FakeImageProxy) {
        val analysis = boundAnalyses().single()
        val analyzer = assertNotNull(platform.backend.analyzers[analysis], "no analyzer on the bound ImageAnalysis")
        analyzer.analyze(frame.proxy)
    }

    private fun CameraFrame.firstPlaneBytes(): ByteArray = ByteArray(planes[0].size).also { planes[0].copyTo(it) }

    private fun CameraFrame.describe(): String = "${width}x$height at $timestampNanos, rotation $rotationDegrees, " +
        "crop ${cropRect.left},${cropRect.top}-${cropRect.right},${cropRect.bottom}"

    private companion object {
        const val FRAMES = 5
    }
}
