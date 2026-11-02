package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.DeviceOrientation
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown

/*
 * The one place iOS orientations are translated. A device turned to its left (LandscapeLeft, home
 * button on the right) shows the interface in LandscapeRight, and AVFoundation's video orientations
 * name the interface side, so device landscape maps to the opposite video landscape.
 */

/** The video orientation for a device held this way; null for face up, face down and unknown. */
internal fun UIDeviceOrientation.toVideoOrientation(): AVCaptureVideoOrientation? = when (this) {
    UIDeviceOrientation.UIDeviceOrientationPortrait -> AVCaptureVideoOrientationPortrait
    UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
    UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeRight
    UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeLeft
    else -> null
}

/** The library's orientation for a device held this way; null for face up, face down and unknown. */
internal fun UIDeviceOrientation.toDeviceOrientation(): DeviceOrientation? = when (this) {
    UIDeviceOrientation.UIDeviceOrientationPortrait -> DeviceOrientation.PORTRAIT
    UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> DeviceOrientation.LANDSCAPE_LEFT
    UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
    UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> DeviceOrientation.LANDSCAPE_RIGHT
    else -> null
}

/** The video orientation for a [DeviceOrientation], which names how the device is held. */
internal fun DeviceOrientation.toVideoOrientation(): AVCaptureVideoOrientation = when (this) {
    DeviceOrientation.PORTRAIT -> AVCaptureVideoOrientationPortrait
    DeviceOrientation.LANDSCAPE_LEFT -> AVCaptureVideoOrientationLandscapeRight
    DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> AVCaptureVideoOrientationPortraitUpsideDown
    DeviceOrientation.LANDSCAPE_RIGHT -> AVCaptureVideoOrientationLandscapeLeft
}

/** The video orientation that matches an interface orientation; null when the interface has none yet. */
internal fun UIInterfaceOrientation.toVideoOrientation(): AVCaptureVideoOrientation? = when (this) {
    UIInterfaceOrientationPortrait -> AVCaptureVideoOrientationPortrait
    UIInterfaceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
    UIInterfaceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeLeft
    UIInterfaceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeRight
    else -> null
}
