package com.kashif.cameraK.controller

import android.graphics.Rect
import android.util.Size
import com.kashif.cameraK.controls.NormalizedPoint

/** How CameraX draws the preview (`Preview.getResolutionInfo()`): its buffer size, crop and rotation to the display. */
internal class PreviewGeometry(val resolution: Size, val cropRect: Rect, val rotationDegrees: Int)

/** A pixel of the camera's output buffer, in its own orientation. */
internal data class SurfacePoint(val x: Float, val y: Float)

/**
 * The buffer pixel under [point], a point of the preview's content rectangle normalized to [0, 1]
 * over the visible crop, as the display shows it.
 */
internal fun contentPointToSurfacePoint(
    point: NormalizedPoint,
    cropRect: Rect,
    resolution: Size,
    rotationDegrees: Int,
    mirrored: Boolean,
): SurfacePoint = TODO("Z-focus-android")
