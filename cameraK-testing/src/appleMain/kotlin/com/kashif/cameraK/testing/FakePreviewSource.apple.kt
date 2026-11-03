@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureVideoPreviewLayer

internal actual fun fakePreviewSource(): PlatformPreviewSource = object : PlatformPreviewSource {
    override fun createPreviewLayer(): AVCaptureVideoPreviewLayer = AVCaptureVideoPreviewLayer()
}
