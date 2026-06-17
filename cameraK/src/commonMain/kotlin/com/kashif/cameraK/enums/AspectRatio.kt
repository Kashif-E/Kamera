package com.kashif.cameraK.enums

/**
 * Enum representing aspect ratios for camera preview and capture.
 *
 * - RATIO_4_3: Standard 4:3 aspect ratio (default on most devices)
 * - RATIO_16_9: Widescreen 16:9 aspect ratio
 * - RATIO_9_16: Portrait 9:16 aspect ratio (useful for stories, full-screen vertical content)
 * - RATIO_1_1: Square 1:1 aspect ratio
 */
enum class AspectRatio {
    RATIO_4_3,
    RATIO_16_9,
    RATIO_9_16,
    RATIO_1_1,
}

/**
 * The preview's width:height ratio for the given [orientation].
 *
 * The camera sensor's field of view is fixed (e.g. 4:3); this returns how the matching preview box
 * should be proportioned so the displayed frame equals the captured frame. In portrait the long
 * edge runs vertically, so the ratio is inverted. Used to letterbox the preview to match the capture.
 *
 * Note: cameras expose 4:3 or 16:9 sensor outputs only, so [AspectRatio.RATIO_9_16] resolves to the
 * same 16:9 magnitude as [AspectRatio.RATIO_16_9]; the [orientation] then decides portrait vs landscape.
 */
fun AspectRatio.previewAspectRatio(orientation: DeviceOrientation): Float {
    val landscapeRatio = when (this) {
        AspectRatio.RATIO_4_3 -> 4f / 3f
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> 16f / 9f
        AspectRatio.RATIO_1_1 -> 1f
    }
    val isPortrait = when (orientation) {
        DeviceOrientation.PORTRAIT, DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> true
        DeviceOrientation.LANDSCAPE_LEFT, DeviceOrientation.LANDSCAPE_RIGHT -> false
    }
    return if (isPortrait) 1f / landscapeRatio else landscapeRatio
}
