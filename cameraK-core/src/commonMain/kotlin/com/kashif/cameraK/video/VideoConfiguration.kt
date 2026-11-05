package com.kashif.cameraK.video

import kotlin.time.Duration

/**
 * Immutable configuration for video recording sessions.
 *
 * @property quality Resolution and bitrate preset.
 * @property enableAudio Whether to record microphone audio (AAC). Off unless the app asks: with it on, a
 *                        recording needs the microphone permission. Desktop records no audio and logs a
 *                        warning when this is true.
 * @property maxDuration The longest the recording may run; null means unlimited. The platform stops
 *                        the recording at it and keeps the file.
 * @property outputDirectory Absolute path to directory for saving the video file.
 *                           Null uses `CameraConfiguration.outputDirectory`, else the platform's
 *                           app-owned default, whatever the [com.kashif.cameraK.enums.Directory]:
 *                           - Android: `filesDir/CameraK`.
 *                           - iOS: `Documents/CameraK`.
 *                           - Desktop: `~/Movies/CameraK` on macOS, `~/Videos/CameraK` on Linux and Windows.
 *                           No recording reaches MediaStore, the media scanner or Photos.
 * @property filePrefix Prefix for the generated filename: 1 to 64 characters from `A-Z a-z 0-9 . _ -`,
 *                     and not `.` or `..`, so it can never name another directory.
 * @property maxFileSizeBytes The largest the file may grow; null means unlimited. The platform stops
 *                           the recording when the file reaches it and keeps the file.
 * @throws IllegalArgumentException if [maxDuration] or [maxFileSizeBytes] is negative, or [filePrefix]
 *   is not a valid prefix.
 */
data class VideoConfiguration(
    val quality: VideoQuality = VideoQuality.FHD,
    val enableAudio: Boolean = false,
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
        require(FILE_PREFIX.matches(filePrefix) && filePrefix != "." && filePrefix != "..") {
            "filePrefix must be 1 to 64 characters from A-Z a-z 0-9 . _ - and not . or .."
        }
    }

    private companion object {
        val FILE_PREFIX = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
