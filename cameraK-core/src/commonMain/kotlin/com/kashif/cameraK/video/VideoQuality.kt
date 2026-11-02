package com.kashif.cameraK.video

/**
 * Video quality presets mapping to platform-native resolution selectors.
 *
 * @property width Target frame width in pixels.
 * @property height Target frame height in pixels.
 * @property bitrateBps Target video bitrate in bits per second.
 */
enum class VideoQuality(val width: Int, val height: Int, val bitrateBps: Int) {
    SD(640, 480, 1_500_000),
    HD(1280, 720, 5_000_000),
    FHD(1920, 1080, 10_000_000),
    UHD(3840, 2160, 50_000_000),
}
