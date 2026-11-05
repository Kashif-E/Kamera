@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreVideo.CVPixelBufferRef

/**
 * The frame's Core Video pixel buffer, for zero-copy decoders (Vision). Non-null only on a frame
 * delivered to a [FrameSpec.synchronous] registration, while that frame is valid; null on a
 * [TestFrame][CameraFrame] and on every frame an asynchronous registration gets.
 */
@CameraKPlatformApi
public val CameraFrame.pixelBuffer: CVPixelBufferRef?
    get() = TODO("I4'")
