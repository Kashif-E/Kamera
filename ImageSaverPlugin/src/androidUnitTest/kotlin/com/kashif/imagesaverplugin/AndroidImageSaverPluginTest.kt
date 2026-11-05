@file:Suppress("DEPRECATION")

package com.kashif.imagesaverplugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class AndroidImageSaverPluginTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val plugin = androidSaver(context)

    @Test
    fun getByteArrayFrom_readsAPlainFilePath() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)
        val file = File(context.cacheDir, "capture.jpg").apply { writeBytes(bytes) }

        assertContentEquals(bytes, plugin.getByteArrayFrom(file.absolutePath))
    }

    // The message used to name the URI; the logging rule (BLD) keeps paths and URIs out of it.
    @Test
    fun getByteArrayFrom_unreadableUri_throwsIOExceptionWithoutIt() {
        val uri = "content://com.kashif.missing/images/404"

        val error = assertFailsWith<IOException> { plugin.getByteArrayFrom(uri) }

        assertEquals("Failed to read the image", error.message)
    }

    @Test
    fun readBytes_readsAPlainFilePath() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 8)
        val file = File(context.cacheDir, "read.jpg").apply { writeBytes(bytes) }

        assertContentEquals(bytes, plugin.readBytes(file.absolutePath))
    }

    @Test
    fun readBytes_unreadableUri_throwsIOExceptionWithoutIt() = runTest {
        val uri = "content://com.kashif.missing/images/405"

        val error = assertFailsWith<IOException> { plugin.readBytes(uri) }

        assertEquals("Failed to read the image", error.message)
    }
}
