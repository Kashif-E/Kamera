package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoPreviewLayer

/**
 * What a [CustomCameraController] uses in place of AVFoundation in tests; null takes the platform's.
 * The controller's internal constructor documents each one.
 */
internal class ControllerSeams(
    val sessionFactory: () -> CaptureSessionOps = { AVCaptureSessionOps() },
    val inputConfigurator: ((CaptureSessionOps, CameraDeviceType) -> Boolean)? = null,
    val lensSwitcher: ((CaptureSessionOps, Boolean) -> Unit)? = null,
    val previewMirroring: ((AVCaptureVideoPreviewLayer?, Boolean) -> Unit)? = null,
    cameraDevices: CameraDeviceProvider? = null,
    photoCapturer: PhotoCapturer? = null,
    val previewOrienting: ((AVCaptureVideoPreviewLayer?, AVCaptureVideoOrientation) -> Unit)? = null,
    val previewRotating: ((AVCaptureVideoPreviewLayer, Double) -> Unit)? = null,
) {
    val cameraDevices: CameraDeviceProvider = cameraDevices ?: DiscoveryCameraDeviceProvider()
    val photoCapturer: PhotoCapturer = photoCapturer ?: AVPhotoCapturer
}
