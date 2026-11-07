package org.company.app.devicematrix

import java.io.File
import java.io.RandomAccessFile

internal actual object PlatformFiles : MatrixFiles {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun size(path: String): Long? = File(path).takeIf { it.isFile }?.length()

    override fun readText(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    override fun writeText(path: String, text: String) = File(path).writeText(text)

    override fun appendText(path: String, text: String) = File(path).appendText(text)

    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val file = File(path)
        if (!file.isFile || offset >= file.length()) return ByteArray(0)
        return RandomAccessFile(file, "r").use { access ->
            val bytes = ByteArray(minOf(length.toLong(), access.length() - offset).toInt())
            access.seek(offset)
            access.readFully(bytes)
            bytes
        }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun createDirectories(path: String) {
        File(path).mkdirs()
    }
}
