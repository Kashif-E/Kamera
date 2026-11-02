package com.kashif.cameraK.enums

/**
 * Enum representing different types of camera devices/lenses available on a device.
 *
 * Note: Availability varies by device hardware and platform:
 * - iOS: All types supported via AVFoundation device types
 * - Android: Support depends on device hardware; resolved against a Camera2-based lens
 *   snapshot (see `CameraController.getCameraCapabilities().availableDeviceTypes(...)`),
 *   falling back to the default camera when the requested type isn't present
 * - Desktop: Not supported (single camera only)
 */
enum class CameraDeviceType {
    /**
     * Standard wide-angle camera (main camera).
     * Available on most devices.
     */
    WIDE_ANGLE,

    /**
     * Telephoto camera for optical zoom.
     * Typically 2x-3x optical zoom.
     * May not be available on all devices.
     */
    TELEPHOTO,

    /**
     * Ultra-wide camera for wider field of view.
     * Typically 0.5x zoom level.
     * May not be available on all devices.
     */
    ULTRA_WIDE,

    /**
     * Macro camera for extreme close-up photography.
     * Only available on select devices.
     */
    MACRO,

    /**
     * Default/automatic camera selection.
     * Platform will choose the most appropriate camera.
     */
    DEFAULT,
}
