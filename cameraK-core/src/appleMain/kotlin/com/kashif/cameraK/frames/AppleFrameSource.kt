@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.frames

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureInput
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferRef
import platform.darwin.NSObject

/** Locks and unlocks a pixel buffer's base address; tests count the calls. */
internal interface PixelBufferLocker {
    /** Locks [buffer] read-only; false when Core Video refused. */
    fun lock(buffer: CVPixelBufferRef): Boolean

    fun unlock(buffer: CVPixelBufferRef)
}

internal object CoreVideoPixelBufferLocker : PixelBufferLocker {
    override fun lock(buffer: CVPixelBufferRef): Boolean = TODO("I4'")

    override fun unlock(buffer: CVPixelBufferRef): Unit = TODO("I4'")
}

/** The video data output's sample-buffer delegate. */
internal class FrameOutputDelegate : NSObject() {
    fun deliver(sampleBuffer: CMSampleBufferRef?, input: AVCaptureInput?): Unit = TODO("I4'")

    fun drop(): Unit = TODO("I4'")
}

/** An iOS registration: what it counts beyond FrameRegistration. */
internal interface AppleFrameRegistration {
    /** Sample buffers AVFoundation dropped while this registration was attached. */
    val droppedFrames: Int
}

/** An iOS retained frame: whether it holds the sample buffer itself or a copy of its luma. */
internal interface AppleRetainedFrame {
    val holdsSampleBuffer: Boolean
}
