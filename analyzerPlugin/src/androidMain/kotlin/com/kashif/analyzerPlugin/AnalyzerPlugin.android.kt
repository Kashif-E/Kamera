package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame

internal actual fun platformFrameEncoder(config: AnalyzerConfig): FrameEncoder = BitmapFrameEncoder(config)

/** Encodes through an ARGB Bitmap and `Bitmap.compress`, from the frame's copied planes. */
internal class BitmapFrameEncoder(private val config: AnalyzerConfig) : FrameEncoder {
    override fun encode(frame: CameraFrame): EncodedFrame? = TODO("AN': BitmapFrameEncoder")
}
