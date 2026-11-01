package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.QualityPrioritization
import kotlinx.atomicfu.atomic
import platform.AVFoundation.AVCaptureVideoDataOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plugins add and remove their outputs with queueConfigurationChange. A change has to reach the
 * session whether or not it is running: a plugin attaching before startRunning, or detaching while
 * the session is stopped or interrupted, must not wait for a start that may never come.
 */
class ConfigurationQueueTest {
    private fun setUpController(): Pair<CustomCameraController, RecordingSession> {
        val session = RecordingSession()
        val controller = CustomCameraController(
            qualityPrioritization = QualityPrioritization.NONE,
            sessionFactory = { session },
            inputConfigurator = { _, _ -> true },
        )
        val ready = atomic(false)
        controller.onSessionReady = { ready.value = true }
        controller.setupSession()
        awaitTrue("onSessionReady") { ready.value }
        return controller to session
    }

    @Test
    fun aChangeQueuedWhileTheSessionIsConfiguredButNotRunningIsApplied() {
        val (controller, session) = setUpController()
        val output = AVCaptureVideoDataOutput()

        controller.queueConfigurationChange { controller.safeAddOutput(output) }
        drainSessionThenMain(controller)

        assertTrue(output in session.outputs, "the change waited for a startSession that never came")
        assertFalse(session.calls.any { it.op == "startRunning" }, "applying a change must not start the session")
        assertEquals(0, session.openConfigurations, "a configuration transaction was left open")
    }

    @Test
    fun aRemovalQueuedWhileTheSessionIsStoppedIsApplied() {
        val (controller, session) = setUpController()
        val output = AVCaptureVideoDataOutput()
        controller.startSession()
        controller.safeAddOutput(output)
        drainSessionThenMain(controller)
        assertTrue(output in session.outputs, "precondition: the output joined the running session")

        controller.stopSession()
        controller.safeRemoveOutput(output)
        drainSessionThenMain(controller)

        assertFalse(output in session.outputs, "a removal queued while stopped stayed pending; the output is attached")
        val removal = session.calls.single { it.op == "removeOutput" }
        assertEquals(1, removal.openConfigurations, "the removal must run inside a configuration transaction")
    }

    @Test
    fun anOutputTheSessionRefusesIsReportedNotSilentlyDropped() {
        val (controller, _) = setUpController()
        val output = AVCaptureVideoDataOutput()
        controller.safeAddOutput(output)
        drainSessionThenMain(controller)

        capturingLogs { logs ->
            // RecordingSession refuses an output that is already attached, as AVCaptureSession does.
            controller.safeAddOutput(output)
            drainSessionThenMain(controller)

            assertTrue(
                logs().any { it.contains("output", ignoreCase = true) && it.contains("refused", ignoreCase = true) },
                "canAddOutput returned false and nothing was reported; logs: ${logs()}",
            )
        }
    }
}
