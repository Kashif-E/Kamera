package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A CameraController an app builds itself (CustomCameraController, the builder) owns an engine of
 * its own: nothing opens before startSession, startSession starts that engine's session with no
 * view, and cleanup tears it down once, however often it is called.
 */
class FacadeConstructionTest {
    @Test
    fun directlyConstructedControllerOwnsItsEngine() {
        val session = FakeSessionOps()
        val custom = CustomCameraController(
            qualityPrioritization = QualityPrioritization.NONE,
            sessionFactory = { session },
            cameraDevices = { _, _ -> FakeDevice() },
            photoCapturer = FakePhotoCapturer(),
        )
        val controller = CameraController(
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            cameraLens = CameraLens.BACK,
            imageFormat = ImageFormat.JPEG,
            qualityPriority = QualityPrioritization.NONE,
            directory = Directory.DOCUMENTS,
            cameraDeviceType = CameraDeviceType.DEFAULT,
            aspectRatio = AspectRatio.RATIO_4_3,
            targetResolution = null,
            mirrorFrontCamera = false,
            customController = custom,
        )
        drainSessionThenMain(custom)
        assertEquals(0, session.startCount, "the camera started before startSession")
        assertEquals(emptyList(), session.inputs, "a camera was bound before startSession")

        controller.startSession()
        awaitTrue("startSession to start the controller's own session") { session.isRunning }

        controller.cleanup()
        awaitTrue("cleanup to tear the session down") { session.tearDownCount > 0 }
        controller.cleanup()
        drainSessionThenMain(custom)
        drainSessionThenMain(custom)
        assertEquals(1, session.tearDownCount, "session teardowns after two cleanups")
        assertEquals(1, session.startCount, "startRunning calls")
    }
}
