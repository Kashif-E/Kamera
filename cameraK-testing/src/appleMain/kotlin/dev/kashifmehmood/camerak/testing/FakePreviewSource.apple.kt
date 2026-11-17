@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.testing

import dev.kashifmehmood.camerak.engine.PlatformPreviewSource
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureVideoPreviewLayer

internal actual fun fakePreviewSource(): PlatformPreviewSource = object : PlatformPreviewSource {
    override fun createPreviewLayer(): AVCaptureVideoPreviewLayer = AVCaptureVideoPreviewLayer()
}
