package com.kashif.imagesaverplugin

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.Directory
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.function.Supplier
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Where a save lands for each [Directory].
 *
 * From API 29 MediaStore checks RELATIVE_PATH against the collection: `MediaStore.Images` accepts
 * only `DCIM/` and `Pictures/` and throws IllegalArgumentException for anything else, while
 * `MediaStore.Files` accepts `Download/` and `Documents/` (developer.android.com/training/data-storage/shared/media;
 * MediaProvider `ensureFileColumns`, unchanged from android10-release to main). [FakeMediaStore]
 * enforces that rule.
 *
 * Below API 29 there is no RELATIVE_PATH: MediaStore names the file itself under `Pictures/` unless
 * the row carries an absolute DATA path, so without one the directory and folder are ignored.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidImageSaverPluginDirectoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)

    @Test
    fun save_toDocuments_landsInTheDocumentsFolder() = runTest {
        val plugin = pluginFor(Directory.DOCUMENTS, customFolderName = "Scans")
        val row = firstRowOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        val written = writesTo(row)
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)

        val result = plugin.save(bytes, "doc")

        assertEquals(ImageSaveResult.Saved(row.toString()), result)
        assertContentEquals(bytes, written.toByteArray())
        val values = mediaStore.rows.getValue(row)
        assertEquals("Documents/Scans", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(0, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun save_toDcim_staysInTheImagesCollection() = runTest {
        val plugin = pluginFor(Directory.DCIM, customFolderName = null)
        val row = firstRowOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        writesTo(row)

        val result = plugin.save(byteArrayOf(1), "shot")

        assertEquals(ImageSaveResult.Saved(row.toString()), result)
        assertEquals("DCIM/CameraK", mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    @Config(sdk = [28])
    fun save_belowApi29_writesIntoTheConfiguredDirectoryAndFolder() = runTest {
        val plugin = pluginFor(Directory.DOCUMENTS, customFolderName = "Scans")
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesTo(row)

        val result = plugin.save(byteArrayOf(1), "doc")

        assertEquals(ImageSaveResult.Saved(row.toString()), result)
        val expected = File(publicDirectory(Environment.DIRECTORY_DOCUMENTS), "Scans/doc.jpg")
        assertEquals(expected.absolutePath, mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.DATA))
    }

    @Test
    @Config(sdk = [28])
    fun save_belowApi29_usesTheDefaultFolderLikeApi29AndAbove() = runTest {
        val plugin = pluginFor(Directory.PICTURES, customFolderName = null)
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesTo(row)

        plugin.save(byteArrayOf(1), "shot")

        val expected = File(publicDirectory(Environment.DIRECTORY_PICTURES), "CameraK/shot.jpg")
        assertEquals(expected.absolutePath, mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.DATA))
    }

    @Test
    @Config(sdk = [28])
    fun save_belowApi29_keepsAnExistingFileWithTheSameName() = runTest {
        val plugin = pluginFor(Directory.PICTURES, customFolderName = "Album")
        val existing = File(publicDirectory(Environment.DIRECTORY_PICTURES), "Album/shot.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9))
        }
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesTo(row)

        plugin.save(byteArrayOf(1), "shot")

        val expected = File(existing.parentFile, "shot_1.jpg")
        assertEquals(expected.absolutePath, mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.DATA))
        assertContentEquals(byteArrayOf(9, 9, 9), existing.readBytes())
    }

    private fun pluginFor(directory: Directory, customFolderName: String?) = AndroidImageSaverPlugin(
        context,
        ImageSaverConfig(directory = directory, customFolderName = customFolderName),
    )

    private fun firstRowOf(collection: Uri): Uri = ContentUris.withAppendedId(collection, 1)

    @Suppress("DEPRECATION") // The pre-29 public directories are what MediaStore writes to there.
    private fun publicDirectory(type: String): File = Environment.getExternalStoragePublicDirectory(type)

    private fun writesTo(uri: Uri): ByteArrayOutputStream {
        val written = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri, Supplier { written })
        return written
    }
}
