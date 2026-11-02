package com.kashif.cameraK.video

/**
 * Immutable configuration for video recording sessions.
 *
 * @property quality Resolution and bitrate preset.
 * @property enableAudio Whether to record microphone audio (AAC). Desktop records no audio and logs a
 *                        warning when this is true.
 * @property maxDurationMs Maximum recording duration in milliseconds. 0 means unlimited. On Android
 *                        CameraX also stops the recording at this limit and keeps the file.
 * @property outputDirectory Absolute path to directory for saving the video file.
 *                           Null uses the platform default:
 *                           - Android: `filesDir/CameraK` for [com.kashif.cameraK.enums.Directory.DOCUMENTS],
 *                             public `Movies/CameraK` for PICTURES and DCIM.
 *                           - iOS: `Documents/CameraK` for DOCUMENTS; for PICTURES and DCIM, a temporary
 *                             file that is imported into Photos.
 *                           - Desktop: `~/Movies/CameraK` on macOS, `~/Videos/CameraK` on Linux and Windows.
 * @property filePrefix Prefix for the generated filename.
 * @property maxFileSizeBytes Maximum size of the video file in bytes. 0 means unlimited. On Android the
 *                           recording stops when the file reaches it, and stopRecording returns
 *                           the file; other platforms do not apply it yet.
 * @throws IllegalArgumentException if [maxDurationMs] or [maxFileSizeBytes] is negative.
 */
data class VideoConfiguration(
    val quality: VideoQuality = VideoQuality.FHD,
    val enableAudio: Boolean = true,
    val maxDurationMs: Long = 0L,
    val outputDirectory: String? = null,
    val filePrefix: String = "VID",
    val maxFileSizeBytes: Long = 0L,
) {
    init {
        require(maxDurationMs >= 0L) { "maxDurationMs must be 0 (unlimited) or positive, was $maxDurationMs" }
        require(maxFileSizeBytes >= 0L) { "maxFileSizeBytes must be 0 (unlimited) or positive, was $maxFileSizeBytes" }
    }
}
