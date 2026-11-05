@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.CMVideoFormatDescriptionRefVar
import platform.CoreMedia.kCMTimeFlags_Valid
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange

/** The luma a [sampleBuffer420f] carries at (x, y) unless told otherwise: distinct for every pixel of a small frame. */
internal fun defaultLuma(width: Int): (Int, Int) -> Int = { x, y -> (y * width + x + 16) and 0xFF }

/** The luma bytes of a [width] x [height] frame, without row padding, as [luma] gives them. */
internal fun lumaBytes(width: Int, height: Int, luma: (Int, Int) -> Int = defaultLuma(width)): ByteArray =
    ByteArray(width * height) { luma(it % width, it / width).toByte() }

/**
 * A 420f (full-range biplanar YUV) sample buffer, as AVCaptureVideoDataOutput delivers in CameraK's
 * format: [width] x [height], luma [luma] at (x, y), neutral chroma, presented at [timestampNanos].
 * The caller releases it with CFRelease.
 */
internal fun sampleBuffer420f(
    width: Int = 8,
    height: Int = 4,
    timestampNanos: Long = 1_000_000,
    luma: (Int, Int) -> Int = defaultLuma(width),
): CMSampleBufferRef = memScoped {
    val made = alloc<CVPixelBufferRefVar>()
    val created = CVPixelBufferCreate(
        null,
        width.convert(),
        height.convert(),
        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        null,
        made.ptr,
    )
    check(created == 0) { "CVPixelBufferCreate failed: $created" }
    val pixels = checkNotNull(made.value)
    try {
        CVPixelBufferLockBaseAddress(pixels, 0u)
        val y = checkNotNull(CVPixelBufferGetBaseAddressOfPlane(pixels, 0u)).reinterpret<UByteVar>()
        val yStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0u).toInt()
        for (row in 0 until height) {
            for (column in 0 until width) y[row * yStride + column] = luma(column, row).toUByte()
        }
        val chroma = checkNotNull(CVPixelBufferGetBaseAddressOfPlane(pixels, 1u)).reinterpret<UByteVar>()
        val chromaStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1u).toInt()
        val chromaRows = CVPixelBufferGetHeightOfPlane(pixels, 1u).toInt()
        for (index in 0 until chromaStride * chromaRows) chroma[index] = NEUTRAL_CHROMA
        CVPixelBufferUnlockBaseAddress(pixels, 0u)

        val format = alloc<CMVideoFormatDescriptionRefVar>()
        val described = CMVideoFormatDescriptionCreateForImageBuffer(null, pixels, format.ptr)
        check(described == 0) { "CMVideoFormatDescriptionCreateForImageBuffer failed: $described" }
        val timing = alloc<CMSampleTimingInfo>()
        timing.duration.flags = 0u
        timing.decodeTimeStamp.flags = 0u
        timing.presentationTimeStamp.value = timestampNanos
        timing.presentationTimeStamp.timescale = NANOS_PER_SECOND
        timing.presentationTimeStamp.flags = kCMTimeFlags_Valid
        timing.presentationTimeStamp.epoch = 0
        val sample = alloc<CMSampleBufferRefVar>()
        try {
            val wrapped = CMSampleBufferCreateReadyWithImageBuffer(null, pixels, format.value, timing.ptr, sample.ptr)
            check(wrapped == 0) { "CMSampleBufferCreateReadyWithImageBuffer failed: $wrapped" }
        } finally {
            CFRelease(format.value)
        }
        checkNotNull(sample.value)
    } finally {
        CVPixelBufferRelease(pixels)
    }
}

private val NEUTRAL_CHROMA: UByte = 128u
private const val NANOS_PER_SECOND = 1_000_000_000
