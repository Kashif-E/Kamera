package com.kashif.imagesaverplugin

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.ImageFormat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.util.function.Supplier
import kotlin.test.assertEquals

/**
 * A saved image's name and MIME type follow its bytes. Android captures are always JPEG, so an
 * auto-save configured for PNG used to store JPEG bytes as a `.png` registered as `image/png`.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidImageSaverPluginFormatTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val firstRow = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        1,
    )
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1)

    @Test
    fun jpegBytes_withPngConfigured_areSavedAsJpeg() = runTest {
        shadowOf(context.contentResolver).registerOutputStreamSupplier(firstRow, Supplier { ByteArrayOutputStream() })

        AndroidImageSaverPlugin(context, ImageSaverConfig(imageFormat = ImageFormat.PNG)).save(jpeg, "shot")

        val values = mediaStore.rows.getValue(firstRow)
        assertEquals("image/jpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("shot.jpg", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
    }

    @Test
    fun pngBytes_withPngConfigured_areSavedAsPng() = runTest {
        shadowOf(context.contentResolver).registerOutputStreamSupplier(firstRow, Supplier { ByteArrayOutputStream() })

        AndroidImageSaverPlugin(context, ImageSaverConfig(imageFormat = ImageFormat.PNG)).save(png, "shot")

        val values = mediaStore.rows.getValue(firstRow)
        assertEquals("image/png", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("shot.png", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
    }
}
