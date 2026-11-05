package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame

internal actual fun platformFrameEncoder(config: AnalyzerConfig): FrameEncoder = ImageIoFrameEncoder(config)

/** Encodes through a BufferedImage and ImageIO's JPEG writer, from the frame's copied planes. */
internal class ImageIoFrameEncoder(private val config: AnalyzerConfig) : FrameEncoder {
    override fun encode(frame: CameraFrame): EncodedFrame? = TODO("AN': ImageIoFrameEncoder")
}
