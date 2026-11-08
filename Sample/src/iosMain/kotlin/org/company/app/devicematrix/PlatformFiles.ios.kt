@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.company.app.devicematrix

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.memcpy

internal actual val platformFiles: MatrixFiles = FoundationFiles

private object FoundationFiles : MatrixFiles {
    private val manager: NSFileManager get() = NSFileManager.defaultManager

    override fun exists(path: String): Boolean = manager.fileExistsAtPath(path)

    override fun size(path: String): Long? =
        (manager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue

    override fun readText(path: String): String? =
        if (exists(path)) NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) else null

    override fun writeText(path: String, text: String) {
        NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    override fun appendText(path: String, text: String) {
        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
        if (handle == null) {
            writeText(path, text)
            return
        }
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        handle.seekToEndOfFile()
        handle.writeData(data)
        handle.closeFile()
    }

    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return ByteArray(0)
        handle.seekToFileOffset(offset.toULong())
        val data = handle.readDataOfLength(length.toULong())
        handle.closeFile()
        return data.toByteArray()
    }

    override fun delete(path: String) {
        manager.removeItemAtPath(path, null)
    }

    override fun createDirectories(path: String) {
        manager.createDirectoryAtPath(path, true, null, null)
    }
}

private fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    if (count == 0) return ByteArray(0)
    return ByteArray(count).apply { usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
