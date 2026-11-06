package com.kashif.cameraK.utils

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** These bytes as NSData. For CameraK's own plugins (the media saver); not supported API. */
@InternalCameraKApi
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(
        bytes = allocArrayOf(this@toNSData),
        length = this@toNSData.size.toULong(),
    )
}

/**
 * Converts NSData to ByteArray with optional buffer reuse for better memory efficiency
 *
 * For CameraK's own plugins (the media saver, the analyzer); not supported API.
 *
 * @param reuseBuffer Optional pre-allocated buffer to use if large enough
 * @return ByteArray containing the data
 */
@InternalCameraKApi
@OptIn(ExperimentalForeignApi::class)
public fun NSData.toByteArray(reuseBuffer: ByteArray? = null): ByteArray {
    val length = this.length.toInt()

    val buffer =
        if (reuseBuffer != null && reuseBuffer.size >= length) {
            reuseBuffer
        } else {
            ByteArray(length)
        }

    buffer.usePinned {
        memcpy(it.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
    }

    return buffer
}

/** This data as a new ByteArray. For CameraK's own plugins; not supported API. */
@InternalCameraKApi
@OptIn(ExperimentalForeignApi::class)
public fun NSData.toByteArray(): ByteArray = toByteArray(null)
