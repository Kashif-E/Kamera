package com.kashif.cameraK.video

import kotlin.time.Duration

/**
 * Immutable configuration for video recording sessions.
 *
 * @property quality Resolution and bitrate preset.
 * @property enableAudio Whether to record microphone audio (AAC). Desktop records no audio and logs a
 *                        warning when this is true.
 * @property maxDuration The longest the recording may run; null means unlimited. The platform stops
 *                        the recording at it and keeps the file.
 * @property outputDirectory Absolute path to directory for saving the video file.
 *                           Null uses the platform default:
 *                           - Android: `filesDir/CameraK` for [com.kashif.cameraK.enums.Directory.DOCUMENTS],
 *                             public `Movies/CameraK` for PICTURES and DCIM.
 *                           - iOS: `Documents/CameraK` for DOCUMENTS; for PICTURES and DCIM, a temporary
 *                             file that is imported into Photos.
 *                           - Desktop: `~/Movies/CameraK` on macOS, `~/Videos/CameraK` on Linux and Windows.
 * @property filePrefix Prefix for the generated filename.
 * @property maxFileSizeBytes The largest the file may grow; null means unlimited. On Android the
 *                           recording stops when the file reaches it, and stopRecording returns
 *                           the file; other platforms do not apply it yet.
 * @throws IllegalArgumentException if [maxDuration] or [maxFileSizeBytes] is negative.
 */
data class VideoConfiguration(
    val quality: VideoQuality = VideoQuality.FHD,
    val enableAudio: Boolean = true,
    val maxDuration: Duration? = null,
    val outputDirectory: String? = null,
    val filePrefix: String = "VID",
    val maxFileSizeBytes: Long? = null,
) {
    init {
        require(maxDuration == null || !maxDuration.isNegative()) {
            "maxDuration must be null (unlimited) or not negative, was $maxDuration"
        }
        require(maxFileSizeBytes == null || maxFileSizeBytes >= 0L) {
            "maxFileSizeBytes must be null (unlimited) or not negative, was $maxFileSizeBytes"
        }
    }
}
