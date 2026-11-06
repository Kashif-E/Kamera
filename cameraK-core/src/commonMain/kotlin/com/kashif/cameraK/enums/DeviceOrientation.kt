package com.kashif.cameraK.enums

/**
 * Closed: the physical orientation of the device.
 *
 * @property degrees Clockwise rotation of the device from its natural (portrait) position.
 * @property compensationDegrees Rotation to apply to UI elements to keep them upright
 *                                when the app is locked to portrait orientation.
 */
public enum class DeviceOrientation(public val degrees: Int, public val compensationDegrees: Float) {
    PORTRAIT(0, 0f),
    LANDSCAPE_LEFT(90, -90f),
    PORTRAIT_UPSIDE_DOWN(180, 180f),
    LANDSCAPE_RIGHT(270, 90f),
}
