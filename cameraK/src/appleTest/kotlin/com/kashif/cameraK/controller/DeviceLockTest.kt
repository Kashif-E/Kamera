package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.QualityPrioritization
import kotlinx.atomicfu.atomic
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Setting torchMode or videoZoomFactor without holding lockForConfiguration, or a torch mode the
 * device does not support, raises an NSException that Kotlin cannot catch, which aborts the app.
 */
class DeviceLockTest {
    private fun setUp(device: FakeDevice): CustomCameraController {
        val controller = CustomCameraController(
            qualityPrioritization = QualityPrioritization.NONE,
            sessionFactory = { RecordingSession() },
            cameraDevices = { lens, _ -> device.takeIf { lens == null || lens == CameraLens.BACK } },
        )
        val ready = atomic(false)
        controller.onSessionReady = { ready.value = true }
        controller.setupSession()
        awaitTrue("onSessionReady") { ready.value }
        return controller
    }

    @Test
    fun torchAndZoomAreLeftAloneWhenTheDeviceRefusesItsConfigurationLock() {
        val device = FakeDevice(lockSucceeds = false)
        val controller = setUp(device)

        capturingLogs { logs ->
            controller.setTorchMode(AVCaptureTorchModeOn)
            controller.setZoom(2f)
            drainSessionThenMain(controller)

            assertEquals(emptyList(), device.callsOf("setTorchMode"), "torch set without the configuration lock")
            assertEquals(emptyList(), device.callsOf("setVideoZoomFactor"), "zoom set without the configuration lock")
            assertEquals(emptyList(), device.callsOf("unlockForConfiguration"), "unlocked a lock that was never held")
            assertTrue(
                logs().count { it.contains("lock", ignoreCase = true) } >= 2,
                "the refused lock was not reported for both torch and zoom; logs: ${logs()}",
            )
        }
    }

    @Test
    fun aTorchModeTheDeviceDoesNotSupportIsNeverApplied() {
        val device = FakeDevice(supportedTorchModes = setOf(AVCaptureTorchModeOff, AVCaptureTorchModeOn))
        val controller = setUp(device)

        controller.setTorchMode(AVCaptureTorchModeAuto)
        drainSessionThenMain(controller)

        assertEquals(emptyList(), device.callsOf("setTorchMode"), "applied a torch mode the device does not support")
        assertFalse(device.isLocked, "the configuration lock was left held")
    }

    @Test
    fun torchAndZoomRunOnTheSessionQueueUnderTheLockAndReleaseIt() {
        val device = FakeDevice(maxVideoZoomFactor = 4.0)
        val controller = setUp(device)

        controller.setTorchMode(AVCaptureTorchModeOn)
        controller.setZoom(9f)
        controller.setZoom(0.2f)
        drainSessionThenMain(controller)

        val changes = device.calls.filter { it.op == "setTorchMode" || it.op == "setVideoZoomFactor" }
        assertEquals(
            listOf<Any?>(AVCaptureTorchModeOn, 4.0, 1.0),
            changes.map { it.value },
            "torch, then zoom clamped to [1, max]; calls: ${device.calls}",
        )
        assertEquals(emptyList(), changes.filter { !it.locked }, "changed the device without the configuration lock")
        assertEquals(emptyList(), changes.filter { it.onMainThread }, "configured the device on the main thread")
        assertEquals(
            setOf("com.kashif.cameraK.session"),
            changes.map { it.queue }.toSet(),
            "configured the device off the session queue",
        )
        assertEquals(
            device.callsOf("lockForConfiguration").size,
            device.callsOf("unlockForConfiguration").size,
            "every lock is released",
        )
        assertFalse(device.isLocked, "the configuration lock was left held")
        assertEquals(1f, controller.getZoom(), "getZoom reports the last applied zoom")
    }
}
