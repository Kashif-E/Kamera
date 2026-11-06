package com.kashif.mediasaverplugin

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** readBytes reads an image the saver saved back from MediaStore, by its row. */
@RunWith(RobolectricTestRunner::class)
class AndroidImageSaverPluginTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val plugin = androidSaver(context)

    @OptIn(InternalCameraKApi::class)
    private fun saved(uri: Uri) = SavedImage("read.jpg", GalleryAsset("read.jpg", uri))

    @Test
    fun readBytes_readsTheSavedImage() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 8)
        val uri = Uri.parse("content://media/external/images/media/8")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        assertContentEquals(bytes, assertIs<CameraKResult.Success<ByteArray>>(plugin.readBytes(saved(uri))).value)
    }

    // The message used to name the URI; the logging rule (BLD) keeps paths and URIs out of it.
    @Test
    fun readBytes_unreadableUri_failsWithoutIt() = runTest {
        val uri = Uri.parse("content://com.kashif.missing/images/405")

        val failure = assertIs<CameraKResult.Failure>(plugin.readBytes(saved(uri)))

        val error = assertIs<CameraKError.Storage>(failure.error)
        assertFalse("/405" in error.cause?.message.orEmpty(), "the failure's text holds the URI: ${error.cause}")
    }
}
