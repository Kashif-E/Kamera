package org.company.app.smoke

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop smoke run CI launches on each OS: `-cameraKSmoke` opens the camera in the Sample's
 * window and passes when the camera reaches what `-cameraKSmokeExpect` names, and when Ready, a photo
 * is written.
 */
@OptIn(CameraKEngineApi::class)
class SmokeVerdictTest {
    private val photo = CameraKResult.Success(CapturedImage("/tmp/p.jpg", ImageFormat.JPEG, 1280, 720))

    @Test
    fun withoutTheFlagThereIsNoSmokeRun() {
        assertNull(smokeArgs(listOf("-somethingElse")))
    }

    @Test
    fun theFlagsNameTheExpectationAndTheTimeout() {
        assertEquals(SmokeArgs(SmokeExpectation.READY, 60), smokeArgs(listOf("-cameraKSmoke")))
        assertEquals(
            SmokeArgs(SmokeExpectation.NO_CAMERA, 20),
            smokeArgs(listOf("-cameraKSmoke", "-cameraKSmokeExpect", "nocamera", "-cameraKSmokeTimeout", "20")),
        )
    }

    @Test
    fun readyWithAWrittenPhotoPasses() {
        val verdict = smokeVerdict(SmokeExpectation.READY, CameraKState.Ready, photo, photoBytes = 40_000)
        assertTrue(verdict.passed, verdict.line)
    }

    @Test
    fun readyWithAFailedOrEmptyPhotoFails() {
        val failed = CameraKResult.Failure(CameraKError.Closed)
        assertFalse(smokeVerdict(SmokeExpectation.READY, CameraKState.Ready, failed, photoBytes = null).passed)
        assertFalse(smokeVerdict(SmokeExpectation.READY, CameraKState.Ready, photo, photoBytes = 0).passed)
    }

    @Test
    fun noStateInTimeFails() {
        assertFalse(smokeVerdict(SmokeExpectation.READY, null, null, null).passed)
        assertFalse(smokeVerdict(SmokeExpectation.NO_CAMERA, null, null, null).passed)
    }

    @Test
    fun anExpectedCameraThatErrsFails() {
        val error = CameraKState.Error(CameraKError.NoCamera(null))
        assertFalse(smokeVerdict(SmokeExpectation.READY, error, null, null).passed)
    }

    @Test
    fun noCameraPassesOnlyWhenNoCameraWasExpected() {
        val error = CameraKState.Error(CameraKError.NoCamera(null))
        assertTrue(smokeVerdict(SmokeExpectation.NO_CAMERA, error, null, null).passed)
        assertFalse(smokeVerdict(SmokeExpectation.NO_CAMERA, CameraKState.Ready, photo, 40_000).passed)
        val other = CameraKState.Error(CameraKError.Closed)
        assertFalse(smokeVerdict(SmokeExpectation.NO_CAMERA, other, null, null).passed)
    }
}
