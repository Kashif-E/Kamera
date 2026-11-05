package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame

/**
 * Encodes borrowed frames as JPEGs, reading only the frame's copied planes. One encoder serves one
 * registration, so its reusable buffers are touched by one worker at a time.
 */
internal fun interface FrameEncoder {
    /** [frame] as an owned JPEG, or null when the platform could not encode it. */
    fun encode(frame: CameraFrame): EncodedFrame?
}

/** The platform's encoder for [config]: Bitmap on Android, ImageIO on desktop, Core Image on iOS. */
internal expect fun platformFrameEncoder(config: AnalyzerConfig): FrameEncoder

/**
 * The size a [width] × [height] frame is encoded at: scaled down so its longest side is
 * [maxDimension], its aspect ratio kept, never scaled up, and at least one pixel each way.
 */
internal fun encodedSize(width: Int, height: Int, maxDimension: Int?): Pair<Int, Int> = TODO("AN': encodedSize")
