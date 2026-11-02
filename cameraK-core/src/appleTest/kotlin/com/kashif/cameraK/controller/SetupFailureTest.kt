package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.QualityPrioritization
import kotlinx.atomicfu.atomic
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCapturePhotoOutput
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A Kotlin exception escaping a GCD block aborts the process, and an open beginConfiguration leaves
 * the session unusable. Setup failures have to be caught inside the session-queue block, close
 * the transaction, and reach onError on main.
 */
class SetupFailureTest {
    private class RefusesPhotoOutput(val recording: RecordingSession = RecordingSession()) :
        CaptureSessionOps by recording {
        override fun canAddOutput(output: AVCaptureOutput): Boolean =
            output !is AVCapturePhotoOutput && recording.canAddOutput(output)
    }

    private fun runFailingSetup(
        session: CaptureSessionOps,
        recording: RecordingSession,
        inputsReady: Boolean,
    ): CustomCameraController.CameraException {
        val controller = CustomCameraController(
            qualityPrioritization = QualityPrioritization.NONE,
            sessionFactory = { session },
            inputConfigurator = { _, _ -> inputsReady },
        )
        val ready = atomic(false)
        val error = atomic<CustomCameraController.CameraException?>(null)
        val errorOnMain = atomic(false)
        controller.onSessionReady = { ready.value = true }
        controller.onError = {
            errorOnMain.value = NSThread.isMainThread
            error.value = it
        }

        controller.setupSession()
        awaitTrue("the setup failure to be reported") { error.value != null }
        drainSessionThenMain(controller)

        assertTrue(errorOnMain.value, "the setup failure was reported off the main thread")
        assertFalse(ready.value, "onSessionReady fired for a setup that failed")
        assertEquals(0, recording.openConfigurations, "the failed setup left beginConfiguration open")
        assertFalse(recording.isRunning, "a session whose setup failed was started")
        return error.value!!
    }

    @Test
    fun aCameraThatCannotBeAddedIsReportedAndTheTransactionClosed() {
        val recording = RecordingSession()
        val error = runFailingSetup(recording, recording, inputsReady = false)
        assertIs<CustomCameraController.CameraException.DeviceNotAvailable>(error)
    }

    @Test
    fun aRefusedPhotoOutputIsReportedWithoutEscapingTheSessionQueueBlock() {
        val session = RefusesPhotoOutput()
        val error = runFailingSetup(session, session.recording, inputsReady = true)
        assertIs<CustomCameraController.CameraException.ConfigurationError>(error)
    }
}
