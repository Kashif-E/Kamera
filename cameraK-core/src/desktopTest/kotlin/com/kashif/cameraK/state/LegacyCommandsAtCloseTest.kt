@file:OptIn(CameraKEngineApi::class)
@file:Suppress("DEPRECATION")

package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.FakeFrameGrabber
import com.kashif.cameraK.controller.FakeFrameRecorder
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameRecorder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 1.x command still running when the holder closes is cancelled by the holder's scope. That
 * cancellation is not the command failing (CC-18): it reports no failure and leaves no lastError.
 */
class LegacyCommandsAtCloseTest {
    private val home: File = Files.createTempDirectory("camerak-commands-home").toFile()
    private val errors = CopyOnWriteArrayList<Throwable?>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        home.deleteRecursively()
    }

    private fun captureErrorLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, _, throwable -> if (level == CameraKLogger.Level.ERROR) errors += throwable }
    }

    /** The fake engine with a 1.x facade, so the holder's 1.x commands run on [legacyController]. */
    private class FacadeEngine(fake: FakeCameraEngine, override val legacyController: CameraController) :
        CameraEngine by fake,
        LegacyControllerAccess

    private fun controller(
        grabber: FakeFrameGrabber = FakeFrameGrabber(),
        recorderFactory: (String, VideoConfiguration) -> FrameRecorder = { _, _ -> FakeFrameRecorder() },
    ): CameraController = CameraController(
        imageFormat = ImageFormat.JPEG,
        directory = Directory.PICTURES,
        customGrabber = grabber,
        sessionDispatcher = Dispatchers.IO,
        recorderFactory = recorderFactory,
        userHome = home,
    )

    private fun assertNoFailureReported(holder: CameraKStateHolder) {
        assertNull(holder.uiState.value.lastError, "the cancelled command wrote lastError")
        val cancellations = errors.filterIsInstance<CancellationException>()
        assertEquals(emptyList(), cancellations, "the cancelled command was logged as a failure")
    }

    @Test
    fun captureImageInFlightAtCloseReportsNoFailure() = runTest {
        captureErrorLogs()
        // The gate stays closed, so the grab loop delivers no frame and the capture waits for one.
        val grabber = FakeFrameGrabber()
        val controller = controller(grabber)
        controller.startSession()
        try {
            val holder = CameraKStateHolder(
                CameraConfiguration(),
                { _, _ -> FacadeEngine(FakeCameraEngine(), controller) },
                emptyList(),
                cameraKTestEnvironment(),
            )
            holder.start()
            runCurrent()
            assertIs<CameraKState.Ready>(holder.cameraState.value)

            holder.captureImage()
            runCurrent()
            assertTrue(holder.uiState.value.isCapturing, "the capture is not in flight")

            holder.closeAndJoin()

            assertNoFailureReported(holder)
            assertFalse(holder.uiState.value.isCapturing, "isCapturing stayed true after close")
        } finally {
            grabber.openGate()
            controller.cleanup()
        }
    }
}
