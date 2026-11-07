package com.kashif.cameraK.controller

import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.enums.AspectRatio
import platform.AVFoundation.AVCaptureVideoPreviewLayer

/**
 * A point of interest as AVFoundation takes it: (0, 0) is the top left of the sensor's landscape
 * buffer and (1, 1) its bottom right, whatever the device's orientation.
 */
internal data class PointOfInterest(val x: Double, val y: Double)

/**
 * Converts a point of a preview layer, in its own coordinates, to the point of interest under it, or
 * null when the layer cannot (it has no connection). Production asks the layer
 * (`captureDevicePointOfInterestForPoint:`); tests see the layer point they are handed.
 */
internal fun interface PreviewPointConverter {
    fun convert(layer: AVCaptureVideoPreviewLayer, x: Double, y: Double): PointOfInterest?
}

/** The centre, where continuous focus and exposure meter. */
internal val CENTRE_OF_INTEREST = PointOfInterest(0.5, 0.5)

/**
 * The point of interest under [point] of the preview's content rectangle when no preview layer can
 * convert it (design §12.4 "W13"). The content rectangle is the frame as the frame API delivers it
 * upright, cropped to [aspectRatio]: the point is unmirrored when [mirrored] (the front camera),
 * turned back by [rotationDegrees] (the clockwise turn that makes the buffer upright), then widened
 * out of the crop of [format], the active format's dimensions; null takes the crop as the whole frame.
 */
internal fun contentPointToDevicePoint(
    point: NormalizedPoint,
    mirrored: Boolean,
    rotationDegrees: Int,
    aspectRatio: AspectRatio,
    format: PhotoDimensions?,
): PointOfInterest = TODO("Z-focus-ios")

/** One step of CameraK's iOS exposure index, in EV: iOS has no index, so CameraK defines it. */
internal const val EXPOSURE_STEP_EV: Float = 1f / 3

/**
 * The exposure indexes a camera whose `exposureTargetBias` runs from [minBias] to [maxBias] takes;
 * null when that is one index or none.
 */
internal fun exposureIndexRange(minBias: Float, maxBias: Float): IntRange? = TODO("Z-focus-ios")

/** The index whose bias is nearest to [bias]. */
internal fun exposureIndexOf(bias: Float): Int = TODO("Z-focus-ios")

/** The `exposureTargetBias` of [index]. */
internal fun exposureBiasOf(index: Int): Float = TODO("Z-focus-ios")
