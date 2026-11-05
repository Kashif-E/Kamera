@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreVideo.CVPixelBufferRef

/**
 * The frame's Core Video pixel buffer, for zero-copy decoders (Vision). Non-null only on a frame
 * delivered to a [FrameSpec.synchronous] registration, and on that registration's retained frames that
 * hold the sample buffer, while the frame is valid; null on a test frame, on every frame an
 * asynchronous registration gets, and on a retained frame that copied its luma. Its base address is
 * locked read-only while non-null.
 */
@CameraKPlatformApi
public val CameraFrame.pixelBuffer: CVPixelBufferRef?
    get() = (this as? PixelBufferFrame)?.exposedPixelBuffer
