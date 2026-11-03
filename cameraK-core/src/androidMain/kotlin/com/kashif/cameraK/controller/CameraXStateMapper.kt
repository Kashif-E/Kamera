package com.kashif.cameraK.controller

import androidx.camera.core.CameraState
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

/** Maps CameraX's camera state to the engine's (design §4.1). Red stub: A1' implements it. */
internal object CameraXStateMapper {
    /** [bindingVideo]: the bind that failed included VideoCapture, which decides a stream-config error. */
    fun map(state: CameraState, bindingVideo: Boolean): CameraXSignal = TODO("A1'")
}
