package com.kashif.cameraK.error

import kotlin.jvm.JvmInline

/**
 * Open: new values can be added in a minor release, so a `when` over it needs an `else` branch.
 * A capability a configuration or command can ask for. When a platform or device lacks it, the request fails with [CameraKError.Unsupported].
 */
@JvmInline
public value class CameraFeature private constructor(public val name: String) {
    override fun toString(): String = TODO("S1' stub")

    public companion object {
        public val FLASH_AUTO: CameraFeature = CameraFeature("FLASH_AUTO")
        public val FLASH_ON: CameraFeature = CameraFeature("FLASH_ON")
        public val TORCH: CameraFeature = CameraFeature("TORCH")
        public val ZOOM: CameraFeature = CameraFeature("ZOOM")
        public val LENS_SWITCH: CameraFeature = CameraFeature("LENS_SWITCH")
        public val DEVICE_TYPE: CameraFeature = CameraFeature("DEVICE_TYPE")
        public val ASPECT_RATIO: CameraFeature = CameraFeature("ASPECT_RATIO")
        public val TARGET_RESOLUTION: CameraFeature = CameraFeature("TARGET_RESOLUTION")
        public val IMAGE_FORMAT: CameraFeature = CameraFeature("IMAGE_FORMAT")
        public val VIDEO_QUALITY: CameraFeature = CameraFeature("VIDEO_QUALITY")
        public val VIDEO_BITRATE: CameraFeature = CameraFeature("VIDEO_BITRATE")
        public val PAUSE_RESUME: CameraFeature = CameraFeature("PAUSE_RESUME")
        public val AUDIO: CameraFeature = CameraFeature("AUDIO")
        public val TARGET_ORIENTATION: CameraFeature = CameraFeature("TARGET_ORIENTATION")
        public val ANALYSIS_FORMAT: CameraFeature = CameraFeature("ANALYSIS_FORMAT")

        /** The operating system is older than CameraK's minimum. */
        public val PLATFORM_VERSION: CameraFeature = CameraFeature("PLATFORM_VERSION")
    }
}
