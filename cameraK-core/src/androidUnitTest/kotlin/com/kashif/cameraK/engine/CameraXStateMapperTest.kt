@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.CameraState
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.CameraXSignal
import com.kashif.cameraK.controller.CameraXStateMapper
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/**
 * CameraX's camera state, mapped as design §4.1 lists it. The kit checks only that a platform
 * interruption is Interrupted; these rows pin which InterruptionReason each CameraX code gives.
 */
@RunWith(RobolectricTestRunner::class)
class CameraXStateMapperTest {
    @Test
    fun recoverableErrorsMapToInterrupted() {
        val reasons = mapOf(
            CameraState.ERROR_MAX_CAMERAS_IN_USE to InterruptionReason.IN_USE_BY_ANOTHER_CLIENT,
            CameraState.ERROR_CAMERA_IN_USE to InterruptionReason.IN_USE_BY_ANOTHER_CLIENT,
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR to InterruptionReason.OTHER,
        )
        for ((code, reason) in reasons) {
            for (type in listOf(CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING, CameraState.Type.OPEN)) {
                assertEquals(
                    CameraXSignal.Interrupted(reason, code),
                    CameraXStateMapper.map(stateWithError(type, code), bindingVideo = false),
                    "code $code while $type",
                )
            }
        }
    }

    @Test
    fun criticalErrorsMapToFailed() {
        val cause = IllegalStateException("from CameraX")
        fun failed(code: Int, bindingVideo: Boolean = false): CameraKError {
            val state = CameraState.create(CameraState.Type.CLOSING, CameraState.StateError.create(code, cause))
            return assertIs<CameraXSignal.Failed>(CameraXStateMapper.map(state, bindingVideo), "code $code").error
        }

        assertEquals(CameraFeature.ASPECT_RATIO, assertIs<CameraKError.Unsupported>(failed(STREAM_CONFIG)).feature)
        assertEquals(
            CameraFeature.VIDEO_QUALITY,
            assertIs<CameraKError.Unsupported>(failed(STREAM_CONFIG, bindingVideo = true)).feature,
        )
        assertIs<CameraKError.CameraDisabled>(failed(CameraState.ERROR_CAMERA_DISABLED))
        assertIs<CameraKError.CameraDisabled>(failed(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED))
        for (code in listOf(CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_CAMERA_REMOVED)) {
            val hardware = assertIs<CameraKError.Hardware>(failed(code))
            assertEquals(code, hardware.platformCode)
            assertSame(cause, hardware.cause, "the CameraX cause is kept")
        }
    }

    @Test
    fun statesWithoutAnErrorAreOpenOrNothing() {
        assertEquals(CameraXSignal.Open, CameraXStateMapper.map(CameraState.create(CameraState.Type.OPEN), false))
        for (type in listOf(
            CameraState.Type.PENDING_OPEN,
            CameraState.Type.OPENING,
            CameraState.Type.CLOSING,
            CameraState.Type.CLOSED,
        )) {
            assertEquals(CameraXSignal.None, CameraXStateMapper.map(CameraState.create(type), false), "$type")
        }
    }

    @Test
    fun recoverableNotReopenedWithinTimeoutFails() = runTest {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        platform.reportError(CameraState.Type.OPENING, CameraState.ERROR_OTHER_RECOVERABLE_ERROR)
        platform.settle(testScheduler)
        testScheduler.advanceTimeBy(AndroidCameraEngine.RECOVERY_TIMEOUT / 2)
        platform.reportOpen()
        platform.settle(testScheduler)
        testScheduler.advanceTimeBy(AndroidCameraEngine.RECOVERY_TIMEOUT)
        assertEquals(EngineState.Open, engine.state.value, "a camera CameraX reopened in time failed later")

        platform.reportError(CameraState.Type.OPENING, CameraState.ERROR_CAMERA_IN_USE)
        platform.settle(testScheduler)
        testScheduler.advanceTimeBy(AndroidCameraEngine.RECOVERY_TIMEOUT - 1.milliseconds)
        assertEquals(
            InterruptionReason.IN_USE_BY_ANOTHER_CLIENT,
            assertIs<EngineState.Interrupted>(engine.state.value, "before the timeout").reason,
        )

        testScheduler.advanceTimeBy(2.milliseconds)
        val failed = assertIs<EngineState.Failed>(engine.state.value, "after the timeout")
        assertEquals(CameraState.ERROR_CAMERA_IN_USE, assertIs<CameraKError.Hardware>(failed.error).platformCode)

        platform.reportOpen()
        platform.settle(testScheduler)
        assertSame(failed, engine.state.value, "CameraX's late reopen changed a failed generation")
        engine.closeAndJoin()
    }

    private fun stateWithError(type: CameraState.Type, code: Int) =
        CameraState.create(type, CameraState.StateError.create(code))

    private companion object {
        const val STREAM_CONFIG = CameraState.ERROR_STREAM_CONFIG
    }
}
