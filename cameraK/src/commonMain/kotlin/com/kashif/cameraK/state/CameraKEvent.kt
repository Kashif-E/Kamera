package com.kashif.cameraK.state

import androidx.compose.runtime.Immutable
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
@Immutable
sealed class CameraKEvent {
    /**
     * No event (initial state).
     *
     * Never emitted: [CameraKStateHolder.events] is a SharedFlow with no initial value.
     */
    @Deprecated("Never emitted: events has no initial value. Will be removed in the next major release.")
    @Immutable
    data object None : CameraKEvent()

    /**
     * Image capture completed successfully.
     *
     * @property result The captured image result (file path or byte array).
     */
    @Immutable
    data class ImageCaptured(val result: ImageCaptureResult) : CameraKEvent()

    /**
     * Image capture failed.
     *
     * @property exception The exception that caused the failure.
     */
    @Immutable
    data class CaptureFailed(val exception: Exception) : CameraKEvent()

    /**
     * QR code detected and scanned.
     *
     * @property qrCode The decoded QR code content.
     */
    @Immutable
    data class QRCodeScanned(val qrCode: String) : CameraKEvent()

    /**
     * OCR text recognition completed.
     *
     * @property text The recognized text.
     */
    @Immutable
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
    @Immutable
    data class PermissionDenied(val permission: String) : CameraKEvent()

    /**
     * Video recording started.
     *
     * @property filePath The output file path where the video is being recorded.
     */
    @Immutable
    data class RecordingStarted(val filePath: String) : CameraKEvent()

    /**
     * Video recording stopped (manually or due to error).
     *
     * @property result The result of the recording with file path and duration.
     */
    @Immutable
    data class RecordingStopped(val result: VideoCaptureResult) : CameraKEvent()

    /**
     * Video recording failed.
     *
     * @property exception The exception that caused the failure.
     */
    @Immutable
    data class RecordingFailed(val exception: Exception) : CameraKEvent()

    /**
     * Video recording reached its configured max duration and auto-stopped.
     *
     * @property filePath The output file path.
     * @property durationMs The actual duration in milliseconds.
     */
    @Immutable
    data class RecordingMaxDurationReached(val filePath: String, val durationMs: Long) : CameraKEvent()

    /**
     * A coroutine a plugin launched in [CameraKStateHolder.pluginScope] failed. The failure is
     * contained: the camera and the other plugins keep running.
     *
     * @property error What the plugin's coroutine threw. Its message may contain paths or URIs.
     */
    @Immutable
    data class PluginFailed(val error: Throwable) : CameraKEvent()
}
