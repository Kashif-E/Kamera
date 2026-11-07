package com.kashif.cameraK.controller

import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.enums.AspectRatio
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
): PointOfInterest {
    val x = if (mirrored) 1.0 - point.x else point.x.toDouble()
    val y = point.y.toDouble()
    var (bufferX, bufferY) = when (val degrees = (rotationDegrees % 360 + 360) % 360) {
        0 -> x to y
        90 -> y to 1 - x
        180 -> 1 - x to 1 - y
        270 -> 1 - y to x
        else -> throw IllegalArgumentException("rotation $degrees is not a multiple of 90")
    }

    @Suppress("DEPRECATION")
    val configured = when (aspectRatio) {
        AspectRatio.RATIO_4_3 -> 4.0 / 3
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> 16.0 / 9
        AspectRatio.RATIO_1_1 -> 1.0
    }
    val active = format?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { max(it.width, it.height).toDouble() / min(it.width, it.height) }
        ?: configured
    // The crop keeps the configured ratio's share of the long side, or of the short side.
    if (configured >= active) {
        bufferY = 0.5 + (bufferY - 0.5) * active / configured
    } else {
        bufferX = 0.5 + (bufferX - 0.5) * configured / active
    }
    return PointOfInterest(bufferX, bufferY)
}

private const val EXPOSURE_STEPS_PER_EV = 3

/** One step of CameraK's iOS exposure index, in EV: iOS has no index, so CameraK defines it. */
internal const val EXPOSURE_STEP_EV: Float = 1f / EXPOSURE_STEPS_PER_EV

/**
 * The widest compensation CameraK offers on iOS, each way: ±2 EV matches Android's typical range and
 * the system Camera app's, where a device's own bias range runs to ±8 EV.
 */
private const val MAX_EXPOSURE_EV = 2f

// The device's bias bounds are floats: a bound a rounding error short of an index still reaches it.
private const val BOUND_TOLERANCE = 1e-3f

/**
 * The exposure indexes a camera whose `exposureTargetBias` runs from [minBias] to [maxBias] takes;
 * null when that is one index or none.
 */
internal fun exposureIndexRange(minBias: Float, maxBias: Float): IntRange? {
    val lowest = ceil(max(minBias, -MAX_EXPOSURE_EV) * EXPOSURE_STEPS_PER_EV - BOUND_TOLERANCE).toInt()
    val highest = floor(min(maxBias, MAX_EXPOSURE_EV) * EXPOSURE_STEPS_PER_EV + BOUND_TOLERANCE).toInt()
    return (lowest..highest).takeIf { lowest < highest }
}

/** The index whose bias is nearest to [bias]. */
internal fun exposureIndexOf(bias: Float): Int = (bias * EXPOSURE_STEPS_PER_EV).roundToInt()

/** The `exposureTargetBias` of [index]. */
internal fun exposureBiasOf(index: Int): Float = index.toFloat() / EXPOSURE_STEPS_PER_EV

/** [this] camera's exposure indexes; null without compensation. */
internal val CaptureDeviceOps.exposureIndexes: IntRange?
    get() = exposureIndexRange(minExposureTargetBias, maxExposureTargetBias)

/** Whether [this] camera meters focus or exposure at a point: CameraK's FOCUS_POINT. */
internal val CaptureDeviceOps.metersAtAPoint: Boolean
    get() = isFocusPointOfInterestSupported || isExposurePointOfInterestSupported

/** The index [this] camera's bias reads back as; 0 on a camera without compensation. */
internal val CaptureDeviceOps.exposureIndex: Int
    get() = if (exposureIndexes == null) 0 else exposureIndexOf(exposureTargetBias)
