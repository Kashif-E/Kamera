package com.kashif.cameraK.frames

import androidx.camera.core.ImageProxy
import com.kashif.cameraK.controller.ProxyFrame
import com.kashif.cameraK.utils.CameraKPlatformApi

/**
 * The CameraX proxy behind this frame, for a zero-copy decoder: non-null only on a frame delivered to
 * a [FrameSpec.synchronous] registration, valid until its `analyze` returns. Null on a frame delivered
 * to an asynchronous registration, on a retained frame and on a test frame; never a ClassCastException.
 * Its planes are read-only views of the registration's own, and closing it does nothing: CameraK
 * closes the frame when the synchronous registrations are done.
 */
@CameraKPlatformApi
public val CameraFrame.imageProxy: ImageProxy?
    get() = (this as? ProxyFrame)?.proxy
