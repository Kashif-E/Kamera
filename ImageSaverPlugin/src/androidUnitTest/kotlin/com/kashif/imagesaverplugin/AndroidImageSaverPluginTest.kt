package com.kashif.imagesaverplugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    private val plugin = AndroidImageSaverPlugin(context, ImageSaverConfig())

    @Test
    fun getByteArrayFrom_readsAPlainFilePath() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)
        val file = File(context.cacheDir, "capture.jpg").apply { writeBytes(bytes) }

        assertContentEquals(bytes, plugin.getByteArrayFrom(file.absolutePath))
    }

    @Test
    fun getByteArrayFrom_unreadableUri_throwsIOExceptionNamingIt() {
        val uri = "content://com.kashif.missing/images/404"

        val error = assertFailsWith<IOException> { plugin.getByteArrayFrom(uri) }

        assertEquals("Failed to read image from path/URI: $uri", error.message)
    }
}
