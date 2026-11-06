@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.analyzerplugin

import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FramePlane
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.RetainedFrame
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferIsPlanar
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly

/**
 * A CPU stand-in for the Core Image renderer, which cannot run here because the simulator test
 * process has no Metal device. Like CIContext.createCGImage it returns a +1 CGImage of four bytes a
 * pixel that owns its own copy of the frame's pixels, so whoever leaks the image leaks one decoded
 * frame. A 420f buffer renders as the grey of its luma plane. It renders at the buffer's size.
 */
internal val copyingRenderer = FrameRenderer { buffer, _, _ ->
    CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
    try {
        val width = CVPixelBufferGetWidth(buffer).toInt()
        val height = CVPixelBufferGetHeight(buffer).toInt()
        val bgrx = ByteArray(width * height * 4)
        if (CVPixelBufferIsPlanar(buffer)) {
            val luma = CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)!!.reinterpret<ByteVar>()
            val stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val level = luma[y * stride + x]
                    val at = (y * width + x) * 4
                    bgrx[at] = level
                    bgrx[at + 1] = level
                    bgrx[at + 2] = level
                }
            }
        } else {
            val bgra = CVPixelBufferGetBaseAddress(buffer)!!.reinterpret<ByteVar>()
            val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
            for (y in 0 until height) for (x in 0 until width * 4) bgrx[y * width * 4 + x] = bgra[y * stride + x]
        }
        cgImageOf(bgrx, width, height)
    } finally {
        CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
    }
}

private fun cgImageOf(bgrx: ByteArray, width: Int, height: Int): CGImageRef? {
    val pixels = bgrx.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bgrx.size.toLong()) }
    val provider = CGDataProviderCreateWithCFData(pixels)
    CFRelease(pixels)
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    val image = CGImageCreate(
        width.toULong(),
        height.toULong(),
        8u,
        32u,
        (width * 4).toULong(),
        colorSpace,
        kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value,
        provider,
        null,
        false,
        CGColorRenderingIntent.kCGRenderingIntentDefault,
    )
    CGColorSpaceRelease(colorSpace)
    CGDataProviderRelease(provider)
    return image
}

/** A frame laid out as iOS delivers 420f: plane 0 the luma, plane 1 CbCr interleaved, rows padded. */
internal class BiPlanarFrame(
    override val width: Int,
    override val height: Int,
    lumaLevel: Int,
    cb: Int = 128,
    cr: Int = 128,
) : CameraFrame {
    private val lumaStride = width + 16
    private val chromaStride = ((width + 1) / 2) * 2 + 16

    override val rotationDegrees: Int get() = 90
    override val timestampNanos: Long get() = 5L
    override val format: FrameFormat get() = FrameFormat.YUV_420
    override val cropRect: FrameRect get() = FrameRect(0, 0, width, height)
    override val planes: List<FramePlane> = listOf(
        BytesPlane(ByteArray(lumaStride * height) { lumaLevel.toByte() }, lumaStride, 1),
        BytesPlane(
            ByteArray(chromaStride * ((height + 1) / 2)) { if (it % 2 == 0) cb.toByte() else cr.toByte() },
            chromaStride,
            2,
        ),
    )

    override fun retain(): RetainedFrame = error("the analyzer never retains a frame")

    private class BytesPlane(private val bytes: ByteArray, override val rowStride: Int, override val pixelStride: Int) :
        FramePlane {
        override val size: Int get() = bytes.size

        override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
            bytes.copyInto(destination, destinationOffset)
            return bytes.size
        }

        override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
            bytes.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
        }
    }
}
