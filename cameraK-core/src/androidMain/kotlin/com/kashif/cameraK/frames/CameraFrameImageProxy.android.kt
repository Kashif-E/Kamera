package com.kashif.cameraK.frames

import androidx.camera.core.ImageProxy
import com.kashif.cameraK.utils.CameraKPlatformApi

/**
 * The CameraX proxy behind this frame, for a zero-copy decoder: non-null only on a frame delivered to
 * a [FrameSpec.synchronous] registration, valid until its `analyze` returns. Null on a frame delivered
 * to an asynchronous registration, on a retained frame and on a test frame; never a ClassCastException.
 */
@CameraKPlatformApi
public val CameraFrame.imageProxy: ImageProxy?
    get() = TODO("A4'")
