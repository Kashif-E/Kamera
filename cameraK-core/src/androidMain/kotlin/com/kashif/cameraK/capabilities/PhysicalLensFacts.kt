package com.kashif.cameraK.capabilities

import android.util.Size

/**
 * What a physical lens of a logical multi-camera can do on its own, read from its own characteristics.
 * A bind that pins the lens binds its logical camera, and CameraX describes and sizes that one: its
 * output sizes and its focus. The engine holds each use case and each metering action to these.
 *
 * @property outputs The sizes the lens outputs for each use case's format; null when they could not be read.
 * @property metering What the lens meters at a point.
 */
internal data class PhysicalLensFacts(val outputs: LensOutputSizes?, val metering: PhysicalMetering)

/**
 * The sizes a lens's `SCALER_STREAM_CONFIGURATION_MAP` outputs: [still] for JPEG (ImageCapture),
 * [preview] for a SurfaceTexture (Preview, PRIVATE) and [analysis] for YUV_420_888 (ImageAnalysis,
 * whose RGBA output CameraX converts from YUV).
 */
internal data class LensOutputSizes(val still: List<Size>, val preview: List<Size>, val analysis: List<Size>)

/**
 * What a lens meters at a point: [autoFocus] where it focuses (a minimum focus distance above 0) and
 * takes an AF region, [autoExposure] and [autoWhiteBalance] where it takes a region of each. A fact
 * its characteristics leave out is not held against it.
 */
internal data class PhysicalMetering(val autoFocus: Boolean, val autoExposure: Boolean, val autoWhiteBalance: Boolean) {
    companion object {
        /** A lens whose characteristics hold nothing against any metering. */
        val UNRESTRICTED = PhysicalMetering(autoFocus = true, autoExposure = true, autoWhiteBalance = true)
    }
}
