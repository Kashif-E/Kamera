// The hint names itself in its subclasses.
@file:Suppress("DEPRECATION_ERROR")

package com.kashif.cameraK.video

/**
 * The 1.x outcome of a recording. Nothing in 2.0 produces it: `stopRecording()` returns a
 * `CameraKResult<RecordedVideo>`. Kept, with its 1.x shape, only so the IDE can point 1.x code at the
 * replacement; removed in 2.1.
 */
@Deprecated(
    "Removed in CameraK 2.0: stopRecording() returns a CameraKResult<RecordedVideo>.",
    ReplaceWith("CameraKResult<RecordedVideo>", "com.kashif.cameraK.capture.RecordedVideo"),
    level = DeprecationLevel.ERROR,
)
sealed class VideoCaptureResult {

    /**
     * Recording completed successfully.
     *
     * @property filePath Absolute path to the saved video file.
     * @property durationMs Actual recorded duration in milliseconds.
     */
    data class Success(val filePath: String, val durationMs: Long) : VideoCaptureResult()

    /**
     * Recording failed or was cancelled.
     *
     * @property exception The underlying cause.
     */
    data class Error(val exception: Exception) : VideoCaptureResult()
}
