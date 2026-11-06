package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown

/**
 * The frame of a preview layer in a host of [bounds] (w6 decision G1): the largest centred box of
 * [aspectRatio]. The box is upright under a portrait [interfaceOrientation] and wide under a landscape
 * one, the way the frame shows; before the host has a window scene it takes the host's shape. Filled
 * with ResizeAspectFill, the box shows the frame cropped as the still is, so the preview shows what a
 * capture keeps.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal fun previewFrame(
    bounds: CValue<CGRect>,
    aspectRatio: AspectRatio,
    interfaceOrientation: UIInterfaceOrientation?,
): CValue<CGRect> = bounds.useContents {
    val width = size.width
    val height = size.height
    val portrait = when (interfaceOrientation) {
        UIInterfaceOrientationPortrait, UIInterfaceOrientationPortraitUpsideDown -> true
        UIInterfaceOrientationLandscapeLeft, UIInterfaceOrientationLandscapeRight -> false
        else -> height > width
    }
    if (width <= 0.0 || height <= 0.0) return@useContents bounds
    // The box's sides as a ratio of whole numbers, so a 16:9 box in 360 points is exactly 640.
    val (long, short) = when (aspectRatio) {
        AspectRatio.RATIO_4_3 -> 4.0 to 3.0
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> 16.0 to 9.0
        AspectRatio.RATIO_1_1 -> 1.0 to 1.0
    }
    val (across, down) = if (portrait) short to long else long to short
    val (boxWidth, boxHeight) =
        if (width * down > height * across) height * across / down to height else width to width * down / across
    CGRectMake(
        origin.x + (width - boxWidth) / 2,
        origin.y + (height - boxHeight) / 2,
        boxWidth,
        boxHeight,
    )
}
