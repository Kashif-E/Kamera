// The hint names itself in its subclasses.
@file:Suppress("DEPRECATION_ERROR")

package com.kashif.cameraK.result

/**
 * The 1.x outcome of a capture. Nothing in 2.0 produces it: `capture()` returns a
 * `CameraKResult<CapturedImage>`. Kept, with its 1.x shape, only so the IDE can point 1.x code at the
 * replacement; removed in 2.1.
 */
@Deprecated(
    "Removed in CameraK 2.0: capture() returns a CameraKResult<CapturedImage>.",
    ReplaceWith("CameraKResult<CapturedImage>", "com.kashif.cameraK.capture.CapturedImage"),
    level = DeprecationLevel.ERROR,
)
public sealed class ImageCaptureResult {
    /**
     * A capture written to an app-owned file.
     *
     * @param filePath The absolute path to the captured image file.
     */
    public data class SuccessWithFile(val filePath: String) : ImageCaptureResult()

    /**
     * A capture published to the shared gallery, which has no file path the app can use.
     *
     * @param uri The gallery entry: a `content://` MediaStore URI on Android.
     */
    public class SuccessWithUri(public val uri: String) : ImageCaptureResult() {
        override fun equals(other: Any?): Boolean = other is SuccessWithUri && other.uri == uri

        override fun hashCode(): Int = uri.hashCode()

        override fun toString(): String = "SuccessWithUri(uri=$uri)"
    }

    /**
     * A failed capture.
     *
     * @param exception The exception that occurred during image capture.
     */
    public data class Error(val exception: Exception) : ImageCaptureResult()
}
