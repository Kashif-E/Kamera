package com.kashif.cameraK.state

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.video.VideoCaptureResult

/**
 * One-shot events emitted by the camera system.
 * Unlike state, events are not persisted and should be handled once.
 *
 * Use SharedFlow to collect these events.
 *
 * @example
 * ```kotlin
 * LaunchedEffect(stateHolder) {
 *     stateHolder.events.collect { event ->
 *         when (event) {
 *             is CameraKEvent.ImageCaptured -> showSuccessToast(event.result)
 *             is CameraKEvent.CaptureFailed -> showErrorDialog(event.exception)
 *             is CameraKEvent.QRCodeScanned -> navigateToDetails(event.qrCode)
 *             else -> {}
 *         }
 *     }
 * }
 * ```
 */
sealed class CameraKEvent {
    /**
     * No event (initial state).
     *
     * Never emitted: [CameraKStateHolder.events] is a SharedFlow with no initial value.
     */
    @Deprecated("Never emitted: events has no initial value. Will be removed in the next major release.")
    data object None : CameraKEvent()

    /**
     * Image capture completed successfully.
     *
     * @property result The captured image result (file path or byte array).
     */
    data class ImageCaptured(val result: ImageCaptureResult) : CameraKEvent()

    /**
     * Image capture failed.
     *
     * @property exception The exception that caused the failure.
     */
    data class CaptureFailed(val exception: Exception) : CameraKEvent()

    /**
     * QR code detected and scanned.
     *
     * @property qrCode The decoded QR code content.
     */
    data class QRCodeScanned(val qrCode: String) : CameraKEvent()

    /**
     * OCR text recognition completed.
     *
     * @property text The recognized text.
     */
    data class TextRecognized(val text: String) : CameraKEvent()

    /**
     * Camera permission denied by user.
     *
     * Never emitted: the state holder does not request permissions. Handle denial in the
     * `onDenied` callback of [com.kashif.cameraK.permissions.Permissions.RequestCameraPermission].
     *
     * @property permission The denied permission name.
     */
    @Deprecated(
        "Never emitted: the state holder does not request permissions. Handle denial in the onDenied " +
            "callback of Permissions.RequestCameraPermission. Will be removed in the next major release.",
    )
    data class PermissionDenied(val permission: String) : CameraKEvent()

    /**
     * Video recording started.
     *
     * @property filePath The output file path where the video is being recorded.
     */
    data class RecordingStarted(val filePath: String) : CameraKEvent()

    /**
     * Video recording stopped (manually or due to error).
     *
     * @property result The result of the recording with file path and duration.
     */
    data class RecordingStopped(val result: VideoCaptureResult) : CameraKEvent()

    /**
     * Video recording failed.
     *
     * @property exception The exception that caused the failure.
     */
    data class RecordingFailed(val exception: Exception) : CameraKEvent()

    /**
     * Video recording reached its configured max duration and auto-stopped.
     *
     * @property filePath The output file path.
     * @property durationMs The actual duration in milliseconds.
     */
    data class RecordingMaxDurationReached(val filePath: String, val durationMs: Long) : CameraKEvent()

    /** Red stub: the 2.0 shape with a placeholder id. */
    class PluginFailed(val pluginId: String, val error: CameraKError) : CameraKEvent() {
        constructor(error: Throwable) : this("", CameraKError.Internal(error))
    }
}
