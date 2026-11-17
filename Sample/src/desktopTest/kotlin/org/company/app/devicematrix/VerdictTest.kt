package org.company.app.devicematrix

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.video.VideoQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PASS, UNSUPPORTED-OK, FAIL and BLOCKED, as the maintainer defined them. */
class VerdictTest {
    private val heicUnsupported = Prediction(unsupported = setOf(CameraFeature.IMAGE_FORMAT))
    private val unsupportedHeic = Outcome.Refused("start", CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT))

    @Test
    fun aSuccessPasses_onlyWhenEveryCheckHolds() {
        assertEquals(Verdict.PASS, judge(Outcome.Completed, Prediction.NONE, emptyList()).verdict)
        val failed = judge(Outcome.Completed, Prediction.NONE, listOf("format PNG, asked JPEG", "ratio 1.33"))
        assertEquals(Verdict.FAIL, failed.verdict)
        assertEquals("format PNG, asked JPEG; ratio 1.33", failed.reason)
    }

    @Test
    fun aSuccessTheCapabilitiesSaidWasUnsupported_fails() {
        val judged = judge(Outcome.Completed, heicUnsupported, emptyList())
        assertEquals(Verdict.FAIL, judged.verdict)
        assertTrue("IMAGE_FORMAT" in judged.reason.orEmpty(), judged.reason)
        // Unless a success is as acceptable, as for a device type the lens lacks.
        assertEquals(
            Verdict.PASS,
            judge(Outcome.Completed, Prediction(noCamera = true, mayStillSucceed = true), emptyList()).verdict,
        )
    }

    @Test
    fun aPredictedRefusal_isUnsupportedOk_andAnUnpredictedOneFails() {
        assertEquals(Verdict.UNSUPPORTED_OK, judge(unsupportedHeic, heicUnsupported, emptyList()).verdict)
        assertEquals(Verdict.FAIL, judge(unsupportedHeic, Prediction.NONE, emptyList()).verdict)
        val noFront = Outcome.Refused("start", CameraKError.NoCamera(CameraLens.FRONT))
        assertEquals(Verdict.UNSUPPORTED_OK, judge(noFront, Prediction(noCamera = true), emptyList()).verdict)
        assertEquals(Verdict.FAIL, judge(noFront, heicUnsupported, emptyList()).verdict)
    }

    // Desktop capabilities list no microphone: refusing one is the machine's answer, and a success is
    // as right. A targetResolution is a target, never refused.
    @Test
    fun anUndecidableRefusal_isUnsupportedOk_andItsSuccessPasses() {
        val undecidable = desktopUndecidable("desktop", audio = true)
        val noMicrophone = Outcome.Refused("startRecording", CameraKError.Unsupported(CameraFeature.AUDIO))
        assertEquals(Verdict.UNSUPPORTED_OK, judge(noMicrophone, undecidable, emptyList()).verdict)
        assertEquals(Verdict.PASS, judge(Outcome.Completed, undecidable, emptyList()).verdict)
        assertEquals(
            Verdict.FAIL,
            judge(noMicrophone, desktopUndecidable("android", audio = true), emptyList()).verdict,
        )
        assertEquals(
            Verdict.FAIL,
            judge(noMicrophone, desktopUndecidable("desktop", audio = false), emptyList()).verdict,
        )
        val noTarget = Outcome.Refused("start", CameraKError.Unsupported(CameraFeature.TARGET_RESOLUTION))
        assertEquals(Verdict.FAIL, judge(noTarget, undecidable, emptyList()).verdict)
    }

    @Test
    fun timeoutsExceptionsAndOtherErrorsFail_andAMissingPermissionBlocks() {
        val timeout = judge(Outcome.TimedOut("capture"), Prediction.NONE, emptyList())
        assertEquals(Verdict.FAIL, timeout.verdict)
        assertEquals("timeout at capture", timeout.reason)
        assertEquals(Verdict.FAIL, judge(Outcome.Threw("boom"), Prediction.NONE, emptyList()).verdict)
        val timedOut = Outcome.Refused("capture", CameraKError.Timeout(CameraOperation.CAPTURE))
        assertEquals("capture: Timeout(CAPTURE)", judge(timedOut, heicUnsupported, emptyList()).reason)
        val denied = CameraKError.Permission(CameraKPermission.MICROPHONE, PermissionStatus.Denied(false))
        assertEquals(
            Verdict.BLOCKED,
            judge(Outcome.Refused("startRecording", denied), Prediction.NONE, emptyList()).verdict,
        )
    }

    @Test
    fun errorsAreDescribedWithTheirTypedFields() {
        assertEquals("Unsupported(IMAGE_FORMAT)", describe(CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT)))
        assertEquals("NoCamera(FRONT)", describe(CameraKError.NoCamera(CameraLens.FRONT)))
        assertEquals("Busy(LENS)", describe(CameraKError.Busy(CameraOperation.LENS)))
        assertEquals("Closed", describe(CameraKError.Closed))
        assertEquals("Hardware(3)", describe(CameraKError.Hardware(3)))
        assertEquals("Internal(IllegalStateException: x)", describe(CameraKError.Internal(IllegalStateException("x"))))
    }

    @Test
    fun thePredictionsComeFromTheCapabilities() {
        val caps = FakeCapabilities.backAndFront(imageFormats = setOf(ImageFormat.JPEG, ImageFormat.PNG))
        val heic = PhotoSpec(
            CameraLens.BACK,
            CameraDeviceType.WIDE_ANGLE,
            AspectRatio.RATIO_4_3,
            null,
            ImageFormat.HEIC,
            QualityPrioritization.BALANCED,
            false,
            FlashMode.OFF,
        )
        assertEquals(heicUnsupported, photoStartPrediction(caps, heic))
        assertEquals(Prediction.NONE, photoStartPrediction(caps, heic.copy(imageFormat = ImageFormat.JPEG)))
        val telephoto =
            photoStartPrediction(
                caps,
                heic.copy(imageFormat = ImageFormat.JPEG, deviceType = CameraDeviceType.TELEPHOTO),
            )
        assertEquals(Prediction(noCamera = true, mayStillSucceed = true), telephoto, "the fake has no telephoto")
        assertEquals(
            Prediction.NONE,
            photoStartPrediction(
                caps,
                heic.copy(imageFormat = ImageFormat.JPEG, deviceType = CameraDeviceType.DEFAULT),
            ),
        )

        val front = FakeCapabilities.FRONT_ID
        assertEquals(
            Prediction(unsupported = setOf(CameraFeature.FLASH_ON)),
            flashPrediction(caps, front, FlashMode.ON),
        )
        assertEquals(
            Prediction(unsupported = setOf(CameraFeature.FLASH_AUTO)),
            flashPrediction(caps, front, FlashMode.AUTO),
        )
        assertEquals(Prediction.NONE, flashPrediction(caps, front, FlashMode.OFF))
        assertEquals(Prediction(unsupported = setOf(CameraFeature.TORCH)), torchPrediction(caps, front, TorchMode.ON))
        assertEquals(Prediction(unsupported = setOf(CameraFeature.FOCUS_POINT)), focusPrediction(caps, front))
        assertEquals(Prediction.NONE, exposurePrediction(caps, front))

        val bare = FakeCapabilities.backAndFront(focusAndExposure = false)
        assertEquals(
            Prediction(unsupported = setOf(CameraFeature.EXPOSURE_COMPENSATION)),
            exposurePrediction(bare, FakeCapabilities.BACK_ID),
        )
        assertEquals(
            Prediction.NONE,
            videoPrediction(caps, VideoSpec(CameraLens.BACK, AspectRatio.RATIO_16_9, VideoQuality.UHD, true)),
        )
        assertEquals(
            Prediction.NONE,
            analysisPrediction(caps, AnalysisSpec(CameraLens.BACK, FrameFormat.RGBA_8888, null)),
        )
    }
}
