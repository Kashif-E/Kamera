@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.frames

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetPlaneCount
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferRef
import platform.posix.memcpy

/** What every iOS frame exposes through the `pixelBuffer` escape hatch. */
internal interface PixelBufferFrame {
    /** The pixel buffer, only for a synchronous registration's frame while it is valid. */
    val exposedPixelBuffer: CVPixelBufferRef?
}

/** A 420f frame's planes, read from its pixel buffer while [readable] says its base address is locked. */
private fun planesOf(pixels: CVPixelBufferRef, readable: () -> Boolean): List<FramePlane> =
    List(CVPixelBufferGetPlaneCount(pixels).toInt().coerceAtLeast(1)) { PixelPlane(pixels, it, readable) }

/** The frame one registration got: borrowed, valid until its analyze() returns. */
internal class AppleFrame(internal val data: FrameData) :
    CameraFrame,
    PixelBufferFrame {
    private val borrowed = atomic(true)

    override val width: Int = CVPixelBufferGetWidth(data.pixels).toInt()
    override val height: Int = CVPixelBufferGetHeight(data.pixels).toInt()
    override val rotationDegrees: Int get() = data.rotation
    override val timestampNanos: Long get() = data.timestamp
    override val format: FrameFormat get() = FrameFormat.YUV_420
    override val cropRect: FrameRect = FrameRect(0, 0, width, height)
    override val planes: List<FramePlane> = planesOf(data.pixels) { borrowed.value }

    override val exposedPixelBuffer: CVPixelBufferRef?
        get() = data.pixels.takeIf { data.registration.synchronous && borrowed.value }

    override fun retain(): RetainedFrame {
        check(borrowed.value) { "retain() after analyze() returned: the frame is no longer valid" }
        return data.source.retain(data, data.lease.retain()) { lumaOf(this) }
    }

    /** analyze() returned: the frame is invalid from here on. */
    fun end() {
        borrowed.value = false
    }
}

/** A retained frame that holds the sample buffer and its own base-address lock until [close]. */
internal class HeldFrame(private val data: FrameData, private val handle: AnalyzerRetain) :
    RetainedFrame,
    AppleRetainedFrame,
    PixelBufferFrame {
    private val open = atomic(true)

    override val holdsSampleBuffer: Boolean get() = true
    override val width: Int = CVPixelBufferGetWidth(data.pixels).toInt()
    override val height: Int = CVPixelBufferGetHeight(data.pixels).toInt()
    override val rotationDegrees: Int get() = data.rotation
    override val timestampNanos: Long get() = data.timestamp
    override val format: FrameFormat get() = FrameFormat.YUV_420
    override val cropRect: FrameRect = FrameRect(0, 0, width, height)
    override val planes: List<FramePlane> = planesOf(data.pixels) { open.value }

    override val exposedPixelBuffer: CVPixelBufferRef?
        get() = data.pixels.takeIf { data.registration.synchronous && open.value }

    override fun retain(): RetainedFrame {
        check(open.value) { "retain() on a closed frame" }
        return data.source.retain(data, data.lease.retain()) { lumaOf(this) }
    }

    override fun close() {
        if (!open.compareAndSet(expect = true, update = false)) return
        data.source.releaseHeld(data, wasLocked = true)
        handle.release()
    }
}

/** A retained frame past the cap: a copy of the luma plane only, rows without padding. */
internal class LumaCopyFrame(
    private val data: FrameData,
    private val luma: ByteArray,
    private val handle: AnalyzerRetain,
) : RetainedFrame,
    AppleRetainedFrame,
    PixelBufferFrame {
    private val open = atomic(true)

    override val holdsSampleBuffer: Boolean get() = false
    override val width: Int = CVPixelBufferGetWidth(data.pixels).toInt()
    override val height: Int = CVPixelBufferGetHeight(data.pixels).toInt()
    override val rotationDegrees: Int get() = data.rotation
    override val timestampNanos: Long get() = data.timestamp
    override val format: FrameFormat get() = FrameFormat.YUV_420
    override val cropRect: FrameRect = FrameRect(0, 0, width, height)
    override val planes: List<FramePlane> = listOf(BytesPlane(luma, rowStride = width))
    override val exposedPixelBuffer: CVPixelBufferRef? get() = null

    override fun retain(): RetainedFrame {
        check(open.value) { "retain() on a closed frame" }
        return LumaCopyFrame(data, luma, data.lease.retain())
    }

    override fun close() {
        if (open.compareAndSet(expect = true, update = false)) handle.release()
    }
}

private fun lumaOf(frame: CameraFrame): ByteArray = ByteArray(frame.width * frame.height).also { frame.copyLuma(it) }

/** One plane of a locked pixel buffer. */
private class PixelPlane(
    private val pixels: CVPixelBufferRef,
    private val index: Int,
    private val readable: () -> Boolean,
) : FramePlane {
    override val rowStride: Int = CVPixelBufferGetBytesPerRowOfPlane(pixels, index.convert()).toInt()
    override val pixelStride: Int = if (index == 0) 1 else CHROMA_PIXEL_STRIDE
    override val size: Int = rowStride * CVPixelBufferGetHeightOfPlane(pixels, index.convert()).toInt()

    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
        copyTo(destination, destinationOffset, 0, size)
        return size
    }

    override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
        require(sourceOffset >= 0 && length >= 0 && sourceOffset + length <= size) {
            "plane $index holds $size bytes; asked for $length from $sourceOffset"
        }
        require(destinationOffset >= 0 && destinationOffset + length <= destination.size) {
            "the destination holds ${destination.size} bytes; asked for $length at $destinationOffset"
        }
        check(readable()) { "the frame is no longer valid: read it inside analyze(), or retain() it" }
        if (length == 0) return
        val base = checkNotNull(CVPixelBufferGetBaseAddressOfPlane(pixels, index.convert())) {
            "the pixel buffer's base address is not locked"
        }.reinterpret<ByteVar>()
        destination.usePinned { pinned ->
            memcpy(pinned.addressOf(destinationOffset), base + sourceOffset, length.convert())
        }
    }

    private companion object {
        const val CHROMA_PIXEL_STRIDE = 2
    }
}

/** A plane copied out of a frame. */
private class BytesPlane(private val bytes: ByteArray, override val rowStride: Int) : FramePlane {
    override val pixelStride: Int get() = 1
    override val size: Int get() = bytes.size

    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }

    override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
        bytes.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
    }
}
