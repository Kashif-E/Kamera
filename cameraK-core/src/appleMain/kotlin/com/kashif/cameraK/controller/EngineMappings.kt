@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal fun CaptureDeviceOps.lens(): CameraLens = if (isFront) CameraLens.FRONT else CameraLens.BACK

internal fun CameraSelection.lens(): CameraLens? = when (this) {
    is CameraSelection.ByLens -> lens
    is CameraSelection.Default -> CameraLens.BACK
    is CameraSelection.ById -> null
}

internal fun CameraSelection.deviceType(): CameraDeviceType = when (this) {
    is CameraSelection.ByLens -> deviceType
    is CameraSelection.Default -> deviceType
    is CameraSelection.ById -> CameraDeviceType.DEFAULT
}

internal fun FlashMode.toAVCaptureFlashMode() = when (this) {
    FlashMode.ON -> AVCaptureFlashModeOn
    FlashMode.OFF -> AVCaptureFlashModeOff
    FlashMode.AUTO -> AVCaptureFlashModeAuto
}

internal fun AVCaptureFlashMode.toFlashMode(): FlashMode = when (this) {
    AVCaptureFlashModeOn -> FlashMode.ON
    AVCaptureFlashModeAuto -> FlashMode.AUTO
    else -> FlashMode.OFF
}

internal fun TorchMode.toAVCaptureTorchMode(): AVCaptureTorchMode = when (this) {
    TorchMode.ON -> AVCaptureTorchModeOn
    TorchMode.OFF -> AVCaptureTorchModeOff
    TorchMode.AUTO -> AVCaptureTorchModeAuto
}

/** The engine's error for a controller [failure]; [lens] is the one a missing camera was asked for. */
internal fun engineErrorFor(failure: CustomCameraController.CameraException, lens: CameraLens?): CameraKError {
    val platform = (failure.cause as? NSErrorException)?.error
    return when {
        platform != null -> cameraKErrorFor(platform)
        failure is CustomCameraController.CameraException.DeviceNotAvailable -> CameraKError.NoCamera(lens, failure)
        else -> CameraKError.Hardware(null, failure)
    }
}

/** Whether the main bundle's Info.plist has `key`. */
internal fun hasInfoPlistKey(key: String): Boolean = NSBundle.mainBundle.objectForInfoDictionaryKey(key) != null

/** UIDevice's orientation notifications, begun or ended on main, where UIKit wants them. */
internal fun generateDeviceOrientationNotifications(on: Boolean) {
    dispatch_async(dispatch_get_main_queue()) {
        if (on) {
            UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        } else {
            UIDevice.currentDevice.endGeneratingDeviceOrientationNotifications()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun runningIosMajorVersion(): Int =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion.toInt() }
