package com.kashif.cameraK.controller

import androidx.camera.core.CameraState
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason

/** What one CameraX camera-state report means for the engine. */
internal sealed interface CameraXSignal {
    /** The camera is open and streaming. */
    data object Open : CameraXSignal

    /** CameraX hit a recoverable error with [code] and reopens on its own. */
    data class Interrupted(val reason: InterruptionReason, val code: Int) : CameraXSignal

    /** The session cannot continue. */
    class Failed(val error: CameraKError) : CameraXSignal

    /** A step on the way to open or closed, which changes nothing the engine reports. */
    data object None : CameraXSignal
}

/**
 * Maps CameraX's camera state to the engine's (design §4.1), by error type: a recoverable error is an
 * interruption CameraX recovers from on its own, a critical one ends the session. Whether a
 * recoverable error lasts too long is the engine's decision, not the mapper's.
 */
internal object CameraXStateMapper {
    /** [bindingVideo]: the bind that failed included VideoCapture, which decides a stream-config error. */
    fun map(state: CameraState, bindingVideo: Boolean): CameraXSignal {
        val error =
            state.error ?: return if (state.type == CameraState.Type.OPEN) CameraXSignal.Open else CameraXSignal.None
        val code = error.code
        if (error.type == CameraState.ErrorType.RECOVERABLE) {
            val reason = when (code) {
                CameraState.ERROR_MAX_CAMERAS_IN_USE, CameraState.ERROR_CAMERA_IN_USE ->
                    InterruptionReason.IN_USE_BY_ANOTHER_CLIENT
                else -> InterruptionReason.OTHER
            }
            return CameraXSignal.Interrupted(reason, code)
        }
        val failure = when (code) {
            CameraState.ERROR_STREAM_CONFIG -> CameraKError.Unsupported(
                if (bindingVideo) CameraFeature.VIDEO_QUALITY else CameraFeature.ASPECT_RATIO,
                error.cause,
            )
            CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
                CameraKError.CameraDisabled(error.cause)
            else -> CameraKError.Hardware(code, error.cause)
        }
        return CameraXSignal.Failed(failure)
    }
}
