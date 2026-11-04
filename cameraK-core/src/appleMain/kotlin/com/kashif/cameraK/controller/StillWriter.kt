@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The still a capture wrote: its size as it displays, its EXIF orientation applied (0 when ImageIO
 * cannot read it), and whether the file holds the photo's bytes as the camera delivered them.
 */
internal class WrittenStill(val width: Int, val height: Int, val asDelivered: Boolean = false)

/** The photo's pixel size and EXIF orientation, as ImageIO reads them; null when it cannot. */
private class PhotoGeometry(val width: Int, val height: Int, val orientation: Int)

/**
 * Writes [photo], the photo output's encoded photo (JPEG or HEIC), to [path] in [format] with the
 * [aspectRatio] the configuration asks for (w6 decision G1). 4:3 is what the Photo preset delivers and
 * is written as it came, byte for byte. 16:9 and 9:16 keep a centred crop of the sensor's long side at
 * 16:9, and 1:1 a centred square, of the full-resolution photo; the crop is of the sensor's pixels, and
 * the EXIF orientation and the rest of the photo's metadata are kept, so a portrait photo is a tall
 * crop. PNG is transcoded with ImageIO ([writePng]) when there is nothing to crop.
 *
 * A crop decodes and re-encodes the photo once. Measured on the iOS simulator (iOS 27, Apple
 * M-series Mac), median of ten runs over a 4032x3024 JPEG (12 MP, a flat test image of 190 KB): 18 ms
 * for 16:9 and 20 ms for 1:1, against 0.3 ms to write a 4:3 photo as delivered. A real photo has more
 * detail to decode and encode, so it costs more; the device figure is B1's to measure. An app that
 * wants no re-encode configures RATIO_4_3 and crops itself.
 *
 * A photo ImageIO cannot read is written as it came, uncropped, with a warning: it is the user's
 * photo. Fails when the file cannot be written; the caller removes what was left.
 */
internal fun writeStill(
    photo: NSData,
    format: ImageFormat,
    aspectRatio: AspectRatio,
    path: String,
): Result<WrittenStill> {
    val geometry = photoGeometry(photo)
    val crop = geometry?.let { cropRect(it.width, it.height, aspectRatio) }
    val needsCrop = crop != null && (crop.width != geometry.width || crop.height != geometry.height)
    if (aspectRatio != AspectRatio.RATIO_4_3 && geometry == null) {
        // Keep the user's photo rather than lose it: written as the camera delivered it, uncropped.
        CameraKLogger.w(
            "CameraK",
            "CameraK: ImageIO could not read the photo to crop it to $aspectRatio; keeping it uncropped",
        )
    }
    return when {
        needsCrop -> encodeCropped(photo, format, crop!!, path).map { displayed(crop.width, crop.height, geometry!!) }
        format == ImageFormat.PNG -> if (writePng(photo, path)) {
            Result.success(geometry?.let { displayed(it.width, it.height, it) } ?: WrittenStill(0, 0))
        } else {
            Result.failure(IllegalStateException("ImageIO could not write the photo as PNG"))
        }
        else -> writeDataToFile(photo, path).map {
            val size = geometry?.let { displayed(it.width, it.height, it) } ?: WrittenStill(0, 0)
            WrittenStill(size.width, size.height, asDelivered = true)
        }
    }
}

/** A centred crop of a [width] x [height] photo at [aspectRatio], along the sensor's long side; null for 4:3. */
internal fun cropRect(width: Int, height: Int, aspectRatio: AspectRatio): CropRect? {
    val ratio = when (aspectRatio) {
        AspectRatio.RATIO_4_3 -> return null
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> 16.0 / 9.0
        AspectRatio.RATIO_1_1 -> 1.0
    }
    val landscape = width >= height
    val long = maxOf(width, height)
    val short = minOf(width, height)
    val (cropLong, cropShort) = when {
        abs(long.toDouble() / short - ratio) < 1.0 / short -> long to short
        long.toDouble() / short < ratio -> long to (long / ratio).roundToInt()
        else -> (short * ratio).roundToInt() to short
    }
    val w = if (landscape) cropLong else cropShort
    val h = if (landscape) cropShort else cropLong
    return CropRect((width - w) / 2, (height - h) / 2, w, h)
}

internal data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** EXIF orientations 5 to 8 turn the pixels a quarter, so the photo displays with its sides swapped. */
private fun displayed(width: Int, height: Int, geometry: PhotoGeometry): WrittenStill =
    if (geometry.orientation in 5..8) WrittenStill(height, width) else WrittenStill(width, height)

private fun photoGeometry(photo: NSData): PhotoGeometry? {
    val data = CFBridgingRetain(photo) as CFDataRef
    val source = CGImageSourceCreateWithData(data, null)
    try {
        if (source == null) return null
        val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null)
            ?.let { CFBridgingRelease(it) as? Map<*, *> }
            ?: return null
        val width = (properties["PixelWidth"] as? NSNumber)?.intValue ?: return null
        val height = (properties["PixelHeight"] as? NSNumber)?.intValue ?: return null
        val orientation = (properties["Orientation"] as? NSNumber)?.intValue ?: 1
        return PhotoGeometry(width, height, orientation)
    } finally {
        source?.let(::CFRelease)
        CFRelease(data)
    }
}

/** Decodes [photo], keeps [crop] of its pixels and writes it to [path] in [format], with the photo's metadata. */
private fun encodeCropped(photo: NSData, format: ImageFormat, crop: CropRect, path: String): Result<Unit> {
    val data = CFBridgingRetain(photo) as CFDataRef
    val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef
    val type = CFBridgingRetain(format.uniformType) as CFStringRef
    val source = CGImageSourceCreateWithData(data, null)
    val image = source?.let { CGImageSourceCreateImageAtIndex(it, 0u, null) }
    val cropped = image?.let {
        CGImageCreateWithImageInRect(
            it,
            CGRectMake(crop.x.toDouble(), crop.y.toDouble(), crop.width.toDouble(), crop.height.toDouble()),
        )
    }
    // The photo's own metadata (EXIF, orientation) goes with the cropped pixels.
    val metadata = source?.let { CGImageSourceCopyPropertiesAtIndex(it, 0u, null) }
    val destination = CGImageDestinationCreateWithURL(url, type, 1u, null)
    try {
        if (cropped == null) return Result.failure(IllegalStateException("ImageIO could not crop the photo"))
        if (destination == null) return Result.failure(IllegalStateException("ImageIO could not create the file"))
        CGImageDestinationAddImage(destination, cropped, metadata as CFDictionaryRef?)
        if (!CGImageDestinationFinalize(destination)) {
            return Result.failure(IllegalStateException("ImageIO could not write the cropped photo"))
        }
        return Result.success(Unit)
    } finally {
        destination?.let(::CFRelease)
        metadata?.let(::CFRelease)
        cropped?.let(::CGImageRelease)
        image?.let(::CGImageRelease)
        source?.let(::CFRelease)
        CFRelease(type)
        CFRelease(url)
        CFRelease(data)
    }
}

private val ImageFormat.uniformType: String
    get() = when (this) {
        ImageFormat.JPEG -> "public.jpeg"
        ImageFormat.PNG -> "public.png"
        ImageFormat.HEIC -> "public.heic"
    }
