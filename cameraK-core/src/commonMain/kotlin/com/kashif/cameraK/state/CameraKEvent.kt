package com.kashif.cameraK.state

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.video.VideoCaptureResult

/**
 * An unsolicited notice from the camera, such as a plugin that failed. Events are lossy and not
 * replayed; the authoritative value is always a state flow, and a command's outcome is its result,
 * never an event.
 *
 * Open: new event classes can be added in a minor release, so a `when` over an event needs an
 * `else` branch. Only CameraK creates events.
 *
 * @example
 * ```kotlin
 * LaunchedEffect(holder) {
 *     holder.events.collect { event ->
 *         when (event) {
 *             is CameraKEvent.PluginFailed -> showPluginError(event.pluginId)
 *             else -> Unit
 *         }
 *     }
 * }
 * ```
 */
abstract class CameraKEvent internal constructor() {
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
    @Deprecated("Emitted only by the 1.x captureImage(). Use the result of capture(), or collect captures.")
    data class ImageCaptured(val result: ImageCaptureResult) : CameraKEvent()

    /**
     * Image capture failed.
     *
     * @property exception The exception that caused the failure.
     */
    @Deprecated("Emitted only by the 1.x captureImage(). A failed capture is the Failure that capture() returns.")
    data class CaptureFailed(val exception: Exception) : CameraKEvent()

    /**
     * QR code detected and scanned.
     *
     * @property qrCode The decoded QR code content.
     */
    @Deprecated("Collect the codes from the QR scanner plugin's own flow.")
    data class QRCodeScanned(val qrCode: String) : CameraKEvent()

    /**
     * OCR text recognition completed.
     *
     * @property text The recognized text.
     */
    @Deprecated("Collect the text from the OCR plugin's own flow.")
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
    @Deprecated(
        "Emitted beside the 2.0 recording state until the next major release. Read the Recording that " +
            "startRecording returns, or recordingState.",
    )
    data class RecordingStarted(val filePath: String) : CameraKEvent()

    /**
     * Video recording stopped (manually or due to error).
     *
     * @property result The result of the recording with file path and duration.
     */
    @Deprecated(
        "Emitted beside the 2.0 recording state until the next major release. Read the result of " +
            "stopRecording, or RecordingEnded for an end the app did not ask for.",
    )
    data class RecordingStopped(val result: VideoCaptureResult) : CameraKEvent()

    /**
     * Video recording failed.
     *
     * @property exception The exception that caused the failure.
     */
    @Deprecated(
        "Emitted beside the 2.0 recording state until the next major release. Read the Failure that " +
            "the recording command returns, or RecordingEnded for an end the app did not ask for.",
    )
    data class RecordingFailed(val exception: Exception) : CameraKEvent()

    /**
     * Video recording reached its configured max duration and auto-stopped.
     *
     * @property filePath The output file path.
     * @property durationMs The actual duration in milliseconds.
     */
    @Deprecated(
        "Emitted beside the 2.0 recording state until the next major release. Read RecordingEnded with " +
            "reason MAX_DURATION.",
    )
    data class RecordingMaxDurationReached(val filePath: String, val durationMs: Long) : CameraKEvent()

    /**
     * A recording the caller did not stop ended: a duration or size limit, an interruption, a
     * platform error, a host stop, or [CameraKStateHolder.close] (SHUTDOWN). The same outcome is in
     * `recordingState` as Finished, with the same [recordingId]; a stop the caller asked for returns
     * its outcome from `stopRecording` instead.
     *
     * @property recordingId The id of the recording that ended, as in `RecordingState.Recording`.
     * @property result The finished file, or why there is none.
     * @property reason Why it ended.
     */
    class RecordingEnded internal constructor(
        val recordingId: Long,
        val result: CameraKResult<RecordedVideo>,
        val reason: RecordingEndReason,
    ) : CameraKEvent() {
        override fun toString(): String = "RecordingEnded(recordingId=$recordingId, reason=$reason)"
    }

    /**
     * A plugin failed. The failure is contained: the camera and the other plugins keep running. A
     * thrown exception arrives as [CameraKError.Internal] with the throwable as its cause, whose
     * message may contain paths or URIs.
     *
     * The constructor is public only so CameraK's own plugins can build the event.
     *
     * @property pluginId Which plugin failed: its [com.kashif.cameraK.plugin.CameraKPlugin.id], by
     *   default the plugin class's simple name. A failure in a coroutine launched in the shared
     *   [CameraKStateHolder.pluginScope] has the fixed id `"pluginScope"`, because that scope is
     *   shared by every plugin and cannot tell which one launched it.
     * @property error What failed.
     */
    class PluginFailed @InternalCameraKApi constructor(val pluginId: String, val error: CameraKError) :
        CameraKEvent() {
        override fun toString(): String = "PluginFailed(pluginId=$pluginId, error=${error::class.simpleName})"
    }
}
