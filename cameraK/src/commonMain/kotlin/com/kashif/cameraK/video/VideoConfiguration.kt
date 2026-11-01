package com.kashif.cameraK.video

import androidx.compose.runtime.Immutable

/**
 * Immutable configuration for video recording sessions.
 *
 * @property quality Resolution and bitrate preset.
 * @property enableAudio Whether to record microphone audio (AAC).
 * @property maxDurationMs Maximum recording duration in milliseconds. 0 means unlimited.
 * @property outputDirectory Absolute path to directory for saving the video file.
 *                           Null uses the platform default (DCIM/CameraK on Android, temp on iOS,
 *                           `~/Movies/CameraK` on macOS and `~/Videos/CameraK` on Linux and Windows).
 * @property filePrefix Prefix for the generated filename.
 */
@Immutable
data class VideoConfiguration(
    val quality: VideoQuality = VideoQuality.FHD,
    val enableAudio: Boolean = true,
    val maxDurationMs: Long = 0L,
    val outputDirectory: String? = null,
    val filePrefix: String = "VID",
)
