package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A PNG capture is transcoded from the camera's JPEG with ImageIO, which keeps the photo's pixels as
 * the sensor delivered them and carries its orientation over as metadata. Redrawing the photo
 * upright through UIKit instead holds two more full-resolution bitmaps (over 400 MB at 48 MP).
 */
@OptIn(ExperimentalForeignApi::class)
class PngCaptureTest {
    private val rig = CaptureRig(imageFormat = ImageFormat.PNG)

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun aPngCaptureKeepsTheSensorsPixelsAndCarriesTheOrientationAsMetadata() {
        rig.start()
        val (capture, request) = rig.capture()
        request.callbacks.photoProcessed(orientedJpeg(width = 8, height = 4, orientation = RIGHT_TOP), null)
        request.callbacks.captureFinished(null)

        val result = assertIs<CameraKResult.Success<CapturedImage>>(capture.awaitResult()).value

        val png = imageProperties(result.path)
        assertEquals("public.png", png.type, "a PNG capture is not a PNG")
        assertEquals(
            Triple(8, 4, RIGHT_TOP),
            Triple(png.width, png.height, png.orientation),
            "the PNG was redrawn upright instead of carrying the orientation (width, height, orientation)",
        )
    }

    private class ImageProperties(val type: String?, val width: Int?, val height: Int?, val orientation: Int?)

    private fun imageProperties(path: String): ImageProperties {
        val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef
        val source = assertNotNull(CGImageSourceCreateWithURL(url, null), "the file is not an image")
        try {
            val type = CGImageSourceGetType(source)?.let { CFBridgingRelease(CFRetain(it)) as String }
            val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null)
                ?.let { CFBridgingRelease(it) as Map<*, *> }
                .orEmpty()
            return ImageProperties(
                type,
                properties["PixelWidth"].toIntOrNull(),
                properties["PixelHeight"].toIntOrNull(),
                properties["Orientation"].toIntOrNull(),
            )
        } finally {
            CFRelease(source)
            CFRelease(url)
        }
    }

    private fun Any?.toIntOrNull(): Int? = when (this) {
        is NSNumber -> intValue
        is Number -> toInt()
        else -> null
    }

    private companion object {
        /** EXIF orientation 6: the sensor's pixels must be turned 90 degrees clockwise to display. */
        const val RIGHT_TOP = 6
    }
}

/** A JPEG of [width] x [height] pixels whose EXIF orientation is [orientation], as a camera writes it. */
@OptIn(ExperimentalForeignApi::class)
internal fun orientedJpeg(width: Int, height: Int, orientation: Int): NSData {
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    val context = CGBitmapContextCreate(
        null,
        width.toULong(),
        height.toULong(),
        8u,
        0u,
        colorSpace,
        CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
    )
    CGContextSetRGBFillColor(context, 1.0, 0.0, 0.0, 1.0)
    CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    val image = assertNotNull(CGBitmapContextCreateImage(context))
    CFRelease(context)
    CFRelease(colorSpace)

    val jpeg = NSMutableData()
    val output = CFBridgingRetain(jpeg) as CFMutableDataRef
    val type = CFBridgingRetain("public.jpeg") as CFStringRef
    val properties = CFBridgingRetain(
        mapOf<Any?, Any?>("Orientation" to NSNumber(int = orientation)),
    ) as CFDictionaryRef
    try {
        val destination = assertNotNull(CGImageDestinationCreateWithData(output, type, 1u, null))
        CGImageDestinationAddImage(destination, image, properties)
        check(CGImageDestinationFinalize(destination)) { "the JPEG was not written" }
        CFRelease(destination)
    } finally {
        CFRelease(properties)
        CFRelease(type)
        CFRelease(output)
        CGImageRelease(image)
    }
    return jpeg
}
