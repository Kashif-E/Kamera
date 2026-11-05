package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRef
import platform.CoreVideo.CVPixelBufferRef

internal actual fun platformFrameEncoder(config: AnalyzerConfig): FrameEncoder = CoreImageFrameEncoder(config)

/** Renders a pixel buffer to a CGImage of the given size. The caller owns the returned image (Create rule, +1). */
internal fun interface FrameRenderer {
    @OptIn(ExperimentalForeignApi::class)
    fun render(pixels: CVPixelBufferRef, width: Int, height: Int): CGImageRef?
}

/**
 * Encodes through Core Image: the frame's copied planes are written into a CVPixelBuffer of its
 * own (420f for YUV_420, 32BGRA for RGBA_8888), which [renderer] scales and converts, and UIKit
 * encodes the JPEG. It never reads the frame's own pixel buffer.
 */
internal class CoreImageFrameEncoder(
    private val config: AnalyzerConfig,
    private val renderer: FrameRenderer = CoreImageFrameRenderer,
) : FrameEncoder {
    override fun encode(frame: CameraFrame): EncodedFrame? = TODO("AN': CoreImageFrameEncoder")
}

/** Renders through one CIContext shared by every encoder: a context is too heavy to build per frame or per session. */
internal object CoreImageFrameRenderer : FrameRenderer {
    @OptIn(ExperimentalForeignApi::class)
    override fun render(pixels: CVPixelBufferRef, width: Int, height: Int): CGImageRef? =
        TODO("AN': CoreImageFrameRenderer")
}
