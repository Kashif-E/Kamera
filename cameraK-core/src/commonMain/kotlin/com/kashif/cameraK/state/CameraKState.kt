package com.kashif.cameraK.state

import com.kashif.cameraK.controller.CameraController

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
    data class Ready(val controller: CameraController, val uiState: CameraUIState) : CameraKState()

    /**
     * Camera initialization or operation failed.
     *
     * @property exception The exception that caused the failure.
     * @property message User-friendly error message.
     * @property isRetryable Whether the operation can be retried.
     */
    data class Error(val exception: Exception, val message: String, val isRetryable: Boolean = true) : CameraKState()
}
