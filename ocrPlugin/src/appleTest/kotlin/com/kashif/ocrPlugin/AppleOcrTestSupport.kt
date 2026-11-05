package com.kashif.ocrPlugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile

/** Writes [bitmap] as a PNG to a new file in the temporary directory and returns its path. */
@OptIn(ExperimentalForeignApi::class)
internal fun pngFile(bitmap: Bitmap): String {
    val bytes = checkNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)) {
        "PNG encoding failed"
    }.bytes
    val path = NSTemporaryDirectory() + "ocr-" + NSUUID().UUIDString + ".png"
    val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
    check(data.writeToFile(path, true)) { "the test image could not be written" }
    return path
}

/** Deletes a file [pngFile] wrote. */
@OptIn(ExperimentalForeignApi::class)
internal fun deleteFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

/** A [width] x [height] PNG of one colour. */
internal fun plainPng(width: Int = 8, height: Int = 8): String =
    pngFile(Bitmap().apply { allocN32Pixels(width, height) })
