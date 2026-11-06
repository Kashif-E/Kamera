package com.kashif.cameraK.enums

import com.kashif.cameraK.utils.InternalCameraKApi

/**
 * Closed: aspect ratios for camera preview and capture. A new ratio waits for a major release.
 *
 * - RATIO_4_3: Standard 4:3 aspect ratio (default on most devices)
 * - RATIO_16_9: Widescreen 16:9 aspect ratio
 * - RATIO_9_16: Portrait 9:16 aspect ratio (useful for stories, full-screen vertical content)
 * - RATIO_1_1: Square 1:1 aspect ratio. Costly on Android, see [RATIO_1_1].
 */
public enum class AspectRatio {
    RATIO_4_3,
    RATIO_16_9,
    RATIO_9_16,

    /**
     * Square 1:1 aspect ratio.
     *
     * On Android, CameraX has no 1:1 output size strategy, so CameraK binds 4:3 outputs and a 1:1
     * ViewPort crops them. For every photo, CameraX then decodes the full-resolution JPEG, crops it
     * and encodes it again before writing the file, which costs CPU time and memory between shutter
     * and file and re-compresses the image. The cost has not been measured yet. To avoid it, capture
     * [RATIO_4_3] and crop for display.
     */
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
@InternalCameraKApi
public fun AspectRatio.previewAspectRatio(orientation: DeviceOrientation): Float {
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
