package com.kashif.cameraK.video

import androidx.compose.runtime.Immutable

/**
 * Immutable configuration for video recording sessions.
 *
 * @property quality Resolution and bitrate preset.
 * @property enableAudio Whether to record microphone audio (AAC). Desktop records no audio and logs a
 *                        warning when this is true.
 * @property maxDurationMs Maximum recording duration in milliseconds. 0 means unlimited. On Android
 *                        CameraX also stops the recording at this limit and keeps the file.
 * @property outputDirectory Absolute path to directory for saving the video file.
 *                           Null uses the platform default: on Android `filesDir/CameraK` for
 *                           [com.kashif.cameraK.enums.Directory.DOCUMENTS] and public
 *                           `Movies/CameraK` for PICTURES and DCIM; temp on iOS; `~/Movies/CameraK`
 *                           on macOS and `~/Videos/CameraK` on Linux and Windows.
 * @property filePrefix Prefix for the generated filename.
 * @property maxFileSizeBytes Maximum size of the video file in bytes. 0 means unlimited. On Android the
 *                           recording stops when the file reaches it, and stopRecording returns
 *                           the file; other platforms do not apply it yet.
 */
@Immutable
data class VideoConfiguration(
    val quality: VideoQuality = VideoQuality.FHD,
    val enableAudio: Boolean = true,
    val maxDurationMs: Long = 0L,
    val outputDirectory: String? = null,
    val filePrefix: String = "VID",
    val maxFileSizeBytes: Long = 0L,
)
