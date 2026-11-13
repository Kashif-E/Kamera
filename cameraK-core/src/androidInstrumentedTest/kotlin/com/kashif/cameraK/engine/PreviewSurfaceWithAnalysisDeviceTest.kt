@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.Manifest
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.Preview
import androidx.test.rule.GrantPermissionRule
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The capture session keeps a repeating request while some bound use case streams: the preview's
 * surface, an ImageAnalysis with an analyzer, or else the stand-in surface the engine gives the
 * preview once none is attached. An ImageAnalysis without an analyzer is inactive in CameraX 1.5.3
 * and streams nothing. Without a repeating request no capture result arrives, and a photo or an
 * exposure change never completes. Run on a phone:
 *
 * ```
 * ANDROID_SERIAL=<phone> ./gradlew :cameraK-core:connectedDebugAndroidTest \
 * -Pandroid.testInstrumentationRunnerArguments.class=com.kashif.cameraK.engine.PreviewSurfaceWithAnalysisDeviceTest
 * ```
 */
internal class PreviewSurfaceWithAnalysisDeviceTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val driver = DeviceEngineDriver()
    private val frames = HandlerThread("preview frames").apply { start() }
    private val surfaces = Executors.newSingleThreadExecutor()

    // A surface that takes the preview's frames and lets them go, so the stream never waits on it.
    private val drainingSurface = Preview.SurfaceProvider { request ->
        val size = request.resolution
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, MAX_IMAGES)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(frames.looper))
        request.provideSurface(reader.surface, surfaces) { reader.close() }
    }

    @After
    fun tearDown() {
        surfaces.shutdown()
        frames.quitSafely()
    }

    @Test
    fun aPhotoIsCapturedWithAnIdleAnalysisAndNoPreview() = driver.runCase {
        val engine = startedEngine()
        try {
            assertPhotoCaptured(engine, "with an idle analysis and no preview")
        } finally {
            engine.close()
        }
    }

    @Test
    fun anIdleAnalysisKeepsPhotosAndExposureAfterThePreviewGoes() = driver.runCase {
        val engine = startedEngine()
        try {
            closePreviewAndWaitOutTheGrace(engine)
            assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(INDEX))
            assertPhotoCaptured(engine, "with an idle analysis after the preview went")
        } finally {
            engine.close()
        }
    }

    @Test
    fun anAnalyzerKeepsPhotosAndExposureAfterThePreviewGoes() = driver.runCase {
        val engine = startedEngine()
        val registration = engine.frames.register(FrameSpec(synchronous = true)) { }
        try {
            driver.settle()
            closePreviewAndWaitOutTheGrace(engine)
            assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(INDEX))
            assertPhotoCaptured(engine, "with an analyzer after the preview went")
        } finally {
            registration.close()
            engine.close()
        }
    }

    @Test
    fun photosAndExposureWorkOnceTheLastAnalyzerLeavesWithNoPreview() = driver.runCase {
        val engine = startedEngine()
        val registration = engine.frames.register(FrameSpec(synchronous = true)) { }
        try {
            driver.settle()
            closePreviewAndWaitOutTheGrace(engine)
            registration.close()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(INDEX))
            assertPhotoCaptured(engine, "once the last analyzer left with no preview")
        } finally {
            engine.close()
        }
    }

    private suspend fun closePreviewAndWaitOutTheGrace(engine: CameraEngine) {
        val attachment = engine.preview.attach(drainingSurface, null)
        driver.settle()
        attachment.close()
        Thread.sleep(PAST_THE_GRACE_MS)
        driver.settle()
    }

    private suspend fun assertPhotoCaptured(engine: CameraEngine, case: String) {
        val captured = engine.capture()
        assertIs<CameraKResult.Success<*>>(captured, "the photo $case: $captured")
        (captured.value as? CapturedImage)?.path?.let { File(it).delete() }
    }

    private suspend fun startedEngine(): CameraEngine {
        val engine = driver.createEngine(CameraConfiguration { frameAnalysis = true })
        driver.settle()
        val back = DesiredHardware(
            selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            zoomRatio = 1f,
            targetOrientation = null,
            exposureCompensationIndex = 0,
        )
        assertEquals(CameraKResult.Success(Unit), engine.start(back))
        driver.settle()
        return engine
    }

    private companion object {
        /** Two steps, inside every phone's exposure compensation range. */
        const val INDEX = 2
        const val MAX_IMAGES = 4

        /** EnginePreviewSource's grace period before the stand-in surface, and half a second more. */
        const val PAST_THE_GRACE_MS = 1_500L
    }
}
