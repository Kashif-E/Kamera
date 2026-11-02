package com.kashif.imagesaverplugin

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.test.assertEquals

/**
 * A saved image's name and MIME type follow its bytes. Android captures are always JPEG, so an
 * auto-save configured for PNG used to store JPEG bytes as a `.png` registered as `image/png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidImageSaverPluginFormatTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val firstRow = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        1,
    )
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1)

    private val warnings = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    /**
     * Every Android capture is JPEG, so with PNG configured every auto-save differs from the
     * configuration; one warning says so, not one per photo.
     */
    @Test
    fun formatMismatch_isWarnedOncePerPlugin() = runTest {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
        val secondRow = ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            2,
        )
        for (row in listOf(firstRow, secondRow)) {
            shadowOf(context.contentResolver).registerOutputStreamSupplier(row, Supplier { ByteArrayOutputStream() })
        }
        val plugin = AndroidImageSaverPlugin(context, ImageSaverConfig(imageFormat = ImageFormat.PNG))

        plugin.save(jpeg, "first")
        plugin.save(jpeg, "second")

        assertEquals(2, mediaStore.rows.size, "both images should be saved")
        assertEquals(1, warnings.count { "not the configured" in it }, "format warnings: $warnings")
    }

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
