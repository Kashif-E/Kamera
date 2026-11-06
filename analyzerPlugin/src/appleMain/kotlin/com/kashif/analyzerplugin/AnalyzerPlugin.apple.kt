@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.analyzerplugin

import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVReturnSuccess
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import platform.posix.memset

internal actual fun platformFrameEncoder(config: AnalyzerConfig): FrameEncoder = CoreImageFrameEncoder(config)

/** Renders a pixel buffer to a CGImage of the given size. The caller owns the returned image (Create rule, +1). */
internal fun interface FrameRenderer {
    fun render(pixels: CVPixelBufferRef, width: Int, height: Int): CGImageRef?
}

/**
 * Encodes through Core Image: the frame's copied planes are written into a CVPixelBuffer of its
 * own (420f for YUV_420, 32BGRA for RGBA_8888), which [renderer] scales and converts, and UIKit
 * encodes the JPEG. It never reads the frame's own pixel buffer. The pixel buffer and the rendered
 * image are released before [encode] returns, inside an autorelease pool, so nothing is kept per
 * frame, and nothing here touches the main queue: it runs on the frame source's worker.
 */
internal class CoreImageFrameEncoder(
    private val config: AnalyzerConfig,
    private val renderer: FrameRenderer = CoreImageFrameRenderer,
) : FrameEncoder {
    private val planeCopies = arrayOfNulls<ByteArray>(MAX_PLANES)
    private var row = ByteArray(0)

    override fun encode(frame: CameraFrame): EncodedFrame? {
        val (width, height) = encodedSize(frame.width, frame.height, config.maxDimension)
        val pixels = pixelBufferOf(frame) ?: return null
        try {
            return autoreleasepool {
                val image = renderer.render(pixels, width, height) ?: return@autoreleasepool null
                try {
                    val quality = config.jpegQuality / MAX_QUALITY
                    UIImageJPEGRepresentation(UIImage.imageWithCGImage(image), quality)?.toByteArray()?.let {
                        EncodedFrame(
                            it,
                            CGImageGetWidth(image).toInt(),
                            CGImageGetHeight(image).toInt(),
                            frame.rotationDegrees,
                            frame.timestampNanos,
                        )
                    }
                } finally {
                    CGImageRelease(image)
                }
            }
        } finally {
            CVPixelBufferRelease(pixels)
        }
    }

    // A +1 pixel buffer holding the frame's pixels, or null (logged) when Core Video refuses one.
    private fun pixelBufferOf(frame: CameraFrame): CVPixelBufferRef? {
        val rgba = frame.format == FrameFormat.RGBA_8888
        val pixelFormat = if (rgba) kCVPixelFormatType_32BGRA else kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        val buffer = memScoped {
            val out = alloc<CVPixelBufferRefVar>()
            val status = CVPixelBufferCreate(
                kCFAllocatorDefault,
                frame.width.convert(),
                frame.height.convert(),
                pixelFormat,
                null,
                out.ptr,
            )
            if (status != kCVReturnSuccess) {
                CameraKLogger.w(
                    TAG,
                    "Core Video could not create a pixel buffer for an analysis frame (status $status)",
                )
                return null
            }
            out.value
        } ?: return null
        CVPixelBufferLockBaseAddress(buffer, 0u)
        try {
            if (rgba) writeBgra(frame, buffer) else writeYCbCr(frame, buffer)
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, 0u)
        }
        return buffer
    }

    private fun writeYCbCr(frame: CameraFrame, buffer: CVPixelBufferRef) {
        val planes = frame.planes
        val luma = copyOf(frame, 0)
        val lumaBase = CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)!!.reinterpret<ByteVar>()
        val lumaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
        luma.usePinned { source ->
            for (y in 0 until frame.height) {
                memcpy(lumaBase + y * lumaStride, source.addressOf(y * planes[0].rowStride), frame.width.convert())
            }
        }

        val chromaBase = CVPixelBufferGetBaseAddressOfPlane(buffer, 1u)!!.reinterpret<ByteVar>()
        val chromaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1u).toInt()
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        if (planes.size == 1) {
            memset(chromaBase, NEUTRAL_CHROMA, (chromaStride * chromaHeight).convert())
            return
        }
        val rowBytes = chromaWidth * 2
        if (row.size < rowBytes) row = ByteArray(rowBytes)
        val u = copyOf(frame, 1)
        // [Y, CbCr] is already 420f's layout when its pixel stride is 2; [Y, U, V] is interleaved here.
        val v = if (planes.size == 2) u else copyOf(frame, 2)
        val uStride = planes[1].pixelStride
        val vStride = if (planes.size == 2) uStride else planes[2].pixelStride
        val vOffset = if (planes.size == 2) 1 else 0
        for (y in 0 until chromaHeight) {
            val uRow = y * planes[1].rowStride
            val vRow = y * (if (planes.size == 2) planes[1].rowStride else planes[2].rowStride) + vOffset
            for (x in 0 until chromaWidth) {
                row[2 * x] = u[uRow + x * uStride]
                row[2 * x + 1] = v[vRow + x * vStride]
            }
            row.usePinned { memcpy(chromaBase + y * chromaStride, it.addressOf(0), rowBytes.convert()) }
        }
    }

    private fun writeBgra(frame: CameraFrame, buffer: CVPixelBufferRef) {
        val rgba = copyOf(frame, 0)
        val base = CVPixelBufferGetBaseAddress(buffer)!!.reinterpret<ByteVar>()
        val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
        val rowBytes = frame.width * BYTES_PER_PIXEL
        if (row.size < rowBytes) row = ByteArray(rowBytes)
        for (y in 0 until frame.height) {
            val sourceRow = y * frame.planes[0].rowStride
            for (at in 0 until rowBytes step BYTES_PER_PIXEL) {
                row[at] = rgba[sourceRow + at + 2]
                row[at + 1] = rgba[sourceRow + at + 1]
                row[at + 2] = rgba[sourceRow + at]
                row[at + 3] = OPAQUE
            }
            row.usePinned { memcpy(base + y * stride, it.addressOf(0), rowBytes.convert()) }
        }
    }

    private fun copyOf(frame: CameraFrame, index: Int): ByteArray {
        val plane = frame.planes[index]
        val copy = planeCopies[index]?.takeIf { it.size >= plane.size } ?: ByteArray(plane.size).also {
            planeCopies[index] = it
        }
        plane.copyTo(copy)
        return copy
    }

    private companion object {
        const val TAG = "AnalyzerPlugin"
        const val MAX_PLANES = 3
        const val MAX_QUALITY = 100.0
        const val BYTES_PER_PIXEL = 4
        const val NEUTRAL_CHROMA = 128
        const val OPAQUE: Byte = -1
    }
}

/**
 * Renders through one CIContext shared by every encoder: a context is too heavy to build per frame
 * or per session. Core Image converts 420f to RGB and scales on the GPU.
 */
internal object CoreImageFrameRenderer : FrameRenderer {
    private val context: CIContext by lazy { CIContext.contextWithOptions(null) }

    override fun render(pixels: CVPixelBufferRef, width: Int, height: Int): CGImageRef? {
        val sourceWidth = CVPixelBufferGetWidth(pixels).toDouble()
        val sourceHeight = CVPixelBufferGetHeight(pixels).toDouble()
        var image = CIImage.imageWithCVPixelBuffer(pixels)
        if (width.toDouble() != sourceWidth || height.toDouble() != sourceHeight) {
            image =
                image.imageByApplyingTransform(CGAffineTransformMakeScale(width / sourceWidth, height / sourceHeight))
        }
        return context.createCGImage(image, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    }
}
