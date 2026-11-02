package com.kashif.cameraK.enums

/**
 * Enum class representing the quality prioritization options for image capture.
 */
enum class QualityPrioritization {
    QUALITY,
    SPEED,
    BALANCED,

    /**
     * No preference. Android captures at maximum quality (`CAPTURE_MODE_MAXIMIZE_QUALITY`) and iOS
     * keeps the photo output's default prioritization.
     */
    NONE,
}
