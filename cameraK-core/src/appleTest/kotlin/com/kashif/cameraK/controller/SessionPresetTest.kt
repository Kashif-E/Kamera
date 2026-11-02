package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.QualityPrioritization
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset3840x2160
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Setup picks the session preset from the target resolution (in either orientation) or the aspect
 * ratio, and only ever sets one the session says it can take: assigning any other raises an
 * NSException Kotlin cannot catch.
 */
class SessionPresetTest {
    private val session = RecordingSession()
    private var controller: CustomCameraController? = null

    private fun presetsSetUp(
        aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
        target: Pair<Int, Int>? = null,
    ): List<Any?> {
        val controller = CustomCameraController(
            qualityPrioritization = QualityPrioritization.NONE,
            aspectRatio = aspectRatio,
            targetResolution = target,
            sessionFactory = { session },
            cameraDevices = { _, _ -> FakeDevice() },
        )
        this.controller = controller
        controller.setupSession()
        drainSessionThenMain(controller)
        assertEquals(0, session.openConfigurations, "setup left a configuration open")
        return session.calls.filter { it.op == "setSessionPreset" }.map { it.subject }
    }

    @AfterTest
    fun tearDown() {
        controller?.let {
            it.cleanupSession()
            drainSessionThenMain(it)
        }
    }

    @Test
    fun aPortraitTargetResolutionPicksTheSamePresetAsItsLandscapeForm() {
        assertEquals(
            listOf<Any?>(AVCaptureSessionPreset1920x1080),
            presetsSetUp(target = 1080 to 1920),
            "a 1080x1920 target fell back to another preset",
        )
    }

    @Test
    fun aTargetPresetTheSessionCannotTakeFallsBackToTheNextOneDown() {
        session.refusePreset(AVCaptureSessionPreset3840x2160)

        assertEquals(
            listOf<Any?>(AVCaptureSessionPreset1920x1080),
            presetsSetUp(target = 3840 to 2160),
            "setup set a preset the session refused, or skipped the next one down",
        )
    }

    @Test
    fun anAspectRatioPresetTheSessionCannotTakeFallsBackToHigh() {
        session.refusePreset(AVCaptureSessionPresetPhoto)

        assertEquals(
            listOf<Any?>(AVCaptureSessionPresetHigh),
            presetsSetUp(aspectRatio = AspectRatio.RATIO_4_3),
            "setup set the Photo preset the session refused",
        )
    }
}
