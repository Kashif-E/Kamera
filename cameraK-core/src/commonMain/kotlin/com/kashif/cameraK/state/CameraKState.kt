package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason

/**
 * Represents the lifecycle state of the camera system.
 * This sealed class provides compile-time type safety for handling different camera states.
 *
 * Use this in Compose to react to camera initialization status without callbacks.
 *
 * @example
 * ```kotlin
 * val cameraState by rememberCameraKState(configuration)
 * when (cameraState) {
 *     is CameraKState.Loading -> LoadingIndicator()
 *     is CameraKState.Ready -> CameraPreviewUI(cameraState.controller)
 *     is CameraKState.Error -> ErrorScreen(cameraState.exception)
 * }
 * ```
 */
sealed class CameraKState {
    /**
     * Camera is initializing. No controller available yet.
     * Show loading UI or progress indicator in this state.
     */
    data object Initializing : CameraKState()

    /**
     * Camera is ready for use. Controller is available.
     *
     * @property controller The initialized [CameraController] instance.
     * @property uiState Current UI state of the camera (zoom, flash, lens, etc.).
     */
    data class Ready(val controller: CameraController, val uiState: CameraUIState) : CameraKState() {
        internal constructor(
            legacy: LegacyControllerAccess?,
        ) : this(TODO("H1' red stub: Ready(legacy)"), CameraUIState())
    }

    /**
     * Camera initialization or operation failed.
     *
     * @property exception The exception that caused the failure.
     * @property message User-friendly error message.
     * @property isRetryable Whether the operation can be retried.
     */
    data class Error(val exception: Exception, val message: String, val isRetryable: Boolean = true) : CameraKState() {
        constructor(error: CameraKError) : this(TODO("H1' red stub: Error(error)"), "")

        val error: CameraKError get() = TODO("H1' red stub: Error.error")
    }

    // H1' red stubs: the 2.0 subtypes, declared so the H1' tests compile on the 1.x holder.
    data object Idle : CameraKState()

    data object Starting : CameraKState()

    class Interrupted(val reason: InterruptionReason) : CameraKState()

    data object Closed : CameraKState()
}
