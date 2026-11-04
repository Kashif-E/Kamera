package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.QualityPrioritization
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset3840x2160
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Setup picks the session preset from the aspect ratio first and the target resolution (in either
 * orientation) second, and only ever sets one the session says it can take: assigning any other raises
 * an NSException Kotlin cannot catch. A preset of another aspect ratio is never chosen (#161, PR #171):
 * 4:3 and 1:1 run the Photo preset, then 640x480; 16:9 runs 3840x2160, 1920x1080, then 1280x720, and
 * without a target the Photo preset, whose stills are cropped to 16:9 (w6 decision G1).
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
            presetsSetUp(aspectRatio = AspectRatio.RATIO_16_9, target = 1080 to 1920),
            "a 1080x1920 target fell back to another preset",
        )
    }

    @Test
    fun aTargetPresetTheSessionCannotTakeFallsBackToTheNextOneDown() {
        session.refusePreset(AVCaptureSessionPreset3840x2160)

        assertEquals(
            listOf<Any?>(AVCaptureSessionPreset1920x1080),
            presetsSetUp(aspectRatio = AspectRatio.RATIO_16_9, target = 3840 to 2160),
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

    /** #161: a 4:3 configuration with a 1920x1440 target ran the 16:9 1920x1080 preset. */
    @Test
    fun aspectRatioWinsOverTargetResolution() {
        assertEquals(
            listOf<Any?>(AVCaptureSessionPresetPhoto),
            presetsSetUp(aspectRatio = AspectRatio.RATIO_4_3, target = 1920 to 1440),
            "a 4:3 configuration with a 1920x1440 target ran a preset of another aspect ratio",
        )
    }

    @Test
    fun aSmallFourThreeTargetPicksTheSmallestFourThreePresetThatReachesIt() {
        assertEquals(
            listOf<Any?>(AVCaptureSessionPreset640x480),
            presetsSetUp(aspectRatio = AspectRatio.RATIO_4_3, target = 480 to 640),
        )
    }

    @Test
    fun aSquareConfigurationUsesTheFourThreePresets() {
        assertEquals(
            listOf<Any?>(AVCaptureSessionPresetPhoto),
            presetsSetUp(aspectRatio = AspectRatio.RATIO_1_1, target = 1920 to 1920),
        )
    }

    @Test
    fun sixteenNineWithoutATargetRunsThePhotoPreset() {
        for (ratio in listOf(AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16)) {
            assertEquals(
                listOf<Any?>(AVCaptureSessionPresetPhoto),
                presetsSetUp(aspectRatio = ratio),
                "$ratio without a target: the full-resolution Photo preset, cropped at capture",
            )
            tearDown()
            session.clearCalls()
        }
    }

    @Test
    fun aSixteenNineTargetPicksTheSmallestSixteenNinePresetThatReachesIt() {
        assertEquals(
            listOf<Any?>(AVCaptureSessionPreset1920x1080),
            presetsSetUp(aspectRatio = AspectRatio.RATIO_16_9, target = 1600 to 900),
        )
    }

    @Test
    fun aSixteenNineTargetAboveEveryPresetStartsAtTheLargest() {
        assertEquals(
            listOf<Any?>(AVCaptureSessionPreset3840x2160),
            presetsSetUp(aspectRatio = AspectRatio.RATIO_16_9, target = 7680 to 4320),
        )
    }
}
