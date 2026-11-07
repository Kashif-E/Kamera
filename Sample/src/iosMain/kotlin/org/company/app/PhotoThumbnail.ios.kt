package org.company.app

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

// Skia applies the EXIF orientation AVFoundation writes when it decodes.
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
internal actual fun decodeThumbnail(path: String, maxPx: Int): ImageBitmap? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val length = data.length.toInt()
    if (length == 0) return null
    val bytes = ByteArray(length)
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes.decodeToImageBitmap().scaledDown(maxPx)
}
