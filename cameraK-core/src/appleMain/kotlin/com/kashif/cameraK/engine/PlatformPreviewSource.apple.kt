package com.kashif.cameraK.engine

import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureVideoPreviewLayer

@CameraKEngineApi
public actual interface PlatformPreviewSource {
    /** A preview layer bound to the session the engine created at construction. */
    public fun createPreviewLayer(): AVCaptureVideoPreviewLayer
}
