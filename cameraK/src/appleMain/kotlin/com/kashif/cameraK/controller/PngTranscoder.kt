package com.kashif.cameraK.controller

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImageFromSource
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCreateWithData

/**
 * Writes the photo in [encoded] (the camera's JPEG or HEIC) to a PNG file at [path] with ImageIO,
 * which streams the encoded result to the file and carries the photo's metadata, orientation
 * included, over as metadata: the pixels stay as the sensor delivered them, with no redraw. Returns
 * false when ImageIO cannot read the photo or write the file.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writePng(encoded: NSData, path: String): Boolean {
    val data = CFBridgingRetain(encoded) as CFDataRef
    val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef
    val type = CFBridgingRetain("public.png") as CFStringRef
    val source = CGImageSourceCreateWithData(data, null)
    val destination = CGImageDestinationCreateWithURL(url, type, 1u, null)
    try {
        if (source == null || destination == null) return false
        CGImageDestinationAddImageFromSource(destination, source, 0u, null)
        return CGImageDestinationFinalize(destination)
    } finally {
        destination?.let(::CFRelease)
        source?.let(::CFRelease)
        CFRelease(type)
        CFRelease(url)
        CFRelease(data)
    }
}
