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
 * A preview surface attached to a running camera, or taken away, makes CameraX configure a new capture
 * session on the camera it keeps open. An exposure index sent then reaches the capture results:
 * CameraX dropped one sent while it closed and reopened the camera to configure a session, and the
 * command waited for a frame carrying the index for good. The engine answers once a frame carries
 * the index, so a dropped request fails the case's timeout. Run on a phone:
 *
 * ```
 * ANDROID_SERIAL=<phone> ./gradlew :cameraK-core:connectedDebugAndroidTest \
 * -Pandroid.testInstrumentationRunnerArguments.class=com.kashif.cameraK.engine.PreviewSurfaceExposureDeviceTest
 * ```
 */
internal class PreviewSurfaceExposureDeviceTest {
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
    fun anExposureSentAsAPreviewSurfaceAttachesReachesTheCaptureResults() = driver.runCase {
        val engine = startedEngine()

        val attachment = engine.preview.attach(drainingSurface, null)
        try {
            assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(INDEX))
        } finally {
            attachment.close()
            driver.settle()
            engine.close()
        }
    }

    @Test
    fun anExposureSentAsThePreviewSurfaceGoesReachesTheCaptureResults() = driver.runCase {
        val engine = startedEngine()
        val attachment = engine.preview.attach(drainingSurface, null)
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(-INDEX))

        attachment.close()
        try {
            assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(INDEX))
        } finally {
            driver.settle()
            engine.close()
        }
    }

    @Test
    fun aPhotoTakenAfterThePreviewSurfaceGoesIsCaptured() = driver.runCase {
        val engine = startedEngine()
        val attachment = engine.preview.attach(drainingSurface, null)
        driver.settle()
        attachment.close()
        driver.settle()
        try {
            val captured = engine.capture()
            assertIs<CameraKResult.Success<*>>(captured, "the photo after the preview surface went: $captured")
            (captured.value as? CapturedImage)?.path?.let { File(it).delete() }
        } finally {
            engine.close()
        }
    }

    private suspend fun startedEngine(): CameraEngine {
        val engine = driver.createEngine(CameraConfiguration())
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
    }
}
