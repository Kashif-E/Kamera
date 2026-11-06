package com.kashif.mediasaverplugin

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capture.uri
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Where a saved capture lands for each [GalleryDestination], down to minSdk; GallerySaveTest covers the rest
 * of `save(image)`.
 *
 * From API 29 MediaStore checks RELATIVE_PATH against the collection: `MediaStore.Images` accepts
 * only `DCIM/` and `Pictures/` and throws IllegalArgumentException for anything else, while
 * `MediaStore.Files` accepts `Download/` and `Documents/` (developer.android.com/training/data-storage/shared/media;
 * MediaProvider `ensureFileColumns`, unchanged from android10-release to main). [FakeMediaStore]
 * enforces that rule.
 *
 * Below API 29 there is no RELATIVE_PATH: MediaStore names the file itself under `Pictures/` unless
 * the row carries an absolute DATA path, so without one the directory and folder are ignored. With a
 * DATA path, MediaStore opens that file for the write; [writesToItsFile] does the same.
 *
 * The API 29+ cases pin SDK 35. The below-29 cases run at 28 and at the library's minSdk
 * (OLDEST_SDK), the oldest Android this path has to work on.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidImageSaverPluginDirectoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val loggerWasEnabled = CameraKLogger.enabled
    private val previousSink = CameraKLogger.sink

    @After
    fun restoreLogger() {
        CameraKLogger.enabled = loggerWasEnabled
        CameraKLogger.sink = previousSink
    }

    @Test
    @Config(sdk = [35])
    fun save_toDocuments_landsInTheDocumentsFolder() = runTest {
        val plugin = pluginFor(GalleryDestination.DOCUMENTS, customFolderName = "Scans")
        val row = firstRowOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        val written = writesTo(row)
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)

        val result = plugin.save(captured(bytes), "doc")

        assertEquals(row, savedUri(result))
        assertContentEquals(bytes, written.toByteArray())
        val values = mediaStore.rows.getValue(row)
        assertEquals("Documents/Scans", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(0, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    @Config(sdk = [35])
    fun save_toDcim_staysInTheImagesCollection() = runTest {
        val plugin = pluginFor(GalleryDestination.DCIM, customFolderName = null)
        val row = firstRowOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        writesTo(row)

        val result = plugin.save(captured(byteArrayOf(1)), "shot")

        assertEquals(row, savedUri(result))
        assertEquals("DCIM/CameraK", mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_writesIntoTheConfiguredDirectoryAndFolder() = runTest {
        val plugin = pluginFor(GalleryDestination.DOCUMENTS, customFolderName = "Scans")
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesTo(row)

        val result = plugin.save(captured(byteArrayOf(1)), "doc")

        assertEquals(row, savedUri(result))
        val expected = File(publicDirectory(Environment.DIRECTORY_DOCUMENTS), "Scans/doc.jpg")
        assertEquals(expected.absolutePath, mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.DATA))
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_usesTheDefaultFolderLikeApi29AndAbove() = runTest {
        val plugin = pluginFor(GalleryDestination.PICTURES, customFolderName = null)
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesTo(row)

        plugin.save(captured(byteArrayOf(1)), "shot")

        val expected = File(publicDirectory(Environment.DIRECTORY_PICTURES), "CameraK/shot.jpg")
        assertEquals(expected.absolutePath, mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.DATA))
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_keepsAnExistingFileWithTheSameName() = runTest {
        val plugin = pluginFor(GalleryDestination.PICTURES, customFolderName = "Album")
        val existing = File(publicDirectory(Environment.DIRECTORY_PICTURES), "Album/shot.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9))
        }
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesTo(row)

        plugin.save(captured(byteArrayOf(1)), "shot")

        val expected = File(existing.parentFile, "shot_1.jpg")
        assertEquals(expected.absolutePath, mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.DATA))
        assertContentEquals(byteArrayOf(9, 9, 9), existing.readBytes())
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_writesTheBytesIntoTheFileItNames() = runTest {
        val plugin = pluginFor(GalleryDestination.DCIM, customFolderName = "Trip")
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesToItsFile(row)
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)

        plugin.save(captured(bytes), "shot")

        val file = File(publicDirectory(Environment.DIRECTORY_DCIM), "Trip/shot.jpg")
        assertContentEquals(bytes, file.readBytes())
    }

    /**
     * Auto-save launches one coroutine per capture and the default name changes once a second, so
     * two saves of the same name can run at once. Each must end up in its own file.
     */
    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_concurrentSavesWithTheSameName_neverShareAFile() = runTest {
        File(publicDirectory(Environment.DIRECTORY_PICTURES), "Burst").mkdirs()

        val paths = saveConcurrently(pluginFor(GalleryDestination.PICTURES, customFolderName = "Burst"))

        assertEquals(2, paths.toSet().size, "both saves were given $paths")
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_concurrentSavesIntoAMissingFolder_bothSucceed() = runTest {
        val paths = saveConcurrently(pluginFor(GalleryDestination.PICTURES, customFolderName = "NewAlbum"))

        assertEquals(2, paths.toSet().size, "both saves were given $paths")
    }

    /**
     * Runs two saves named "shot" at once, holding both inserts until each save has chosen its path,
     * and checks that each one's bytes are in the file its row names. Returns the two paths.
     */
    private suspend fun CoroutineScope.saveConcurrently(plugin: MediaSaverPlugin): List<String> {
        val bothChoseAPath = CyclicBarrier(2)
        mediaStore.beforeInsert = { bothChoseAPath.await(10, TimeUnit.SECONDS) }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        listOf(1L, 2L).forEach { writesToItsFile(ContentUris.withAppendedId(collection, it)) }

        val outcomes = listOf(byteArrayOf(1, 1), byteArrayOf(2, 2))
            .map { bytes -> async { bytes to plugin.save(captured(bytes), "shot") } }
            .awaitAll()

        return outcomes.map { (bytes, result) ->
            val saved =
                assertIs<CameraKResult.Success<SavedImage>>(result, "a save failed: $result").value
            val path = mediaStore.rows.getValue(saved.asset.uri).getAsString(MediaStore.MediaColumns.DATA)
            assertContentEquals(bytes, File(path).readBytes(), "the other save overwrote $path")
            path
        }
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_whenTheFolderCannotBeCreated_failsLogsAndInsertsNothing() = runTest {
        // A plain file where the folder should be makes mkdirs fail, as a missing permission does.
        File(publicDirectory(Environment.DIRECTORY_PICTURES), "Blocked").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0))
        }
        val logged = loggedErrors()

        val result = pluginFor(
            GalleryDestination.PICTURES,
            customFolderName = "Blocked",
        ).save(captured(byteArrayOf(1)), "shot")

        val failure = assertIs<CameraKResult.Failure>(result)
        assertEquals(emptyMap(), mediaStore.rows)
        assertTrue(logged.any { it === failure.error.cause }, "the failure was not logged")
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_whenTheInsertIsRejected_leavesNoFileBehind() = runTest {
        mediaStore.insertFailure = IllegalArgumentException("rejected")

        val result = pluginFor(
            GalleryDestination.PICTURES,
            customFolderName = "Rejected",
        ).save(captured(byteArrayOf(1)), "shot")

        assertIs<CameraKResult.Failure>(result)
        val folder = File(publicDirectory(Environment.DIRECTORY_PICTURES), "Rejected")
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    @Test
    @Config(sdk = [Config.OLDEST_SDK, 28])
    fun save_belowApi29_whenTheWriteFails_leavesNoFileAndNoRow() = runTest {
        val row = firstRowOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        writesTo(row) { throw IOException("disk full") }

        val result = pluginFor(
            GalleryDestination.PICTURES,
            customFolderName = "Unwritten",
        ).save(captured(byteArrayOf(1)), "shot")

        assertIs<CameraKResult.Failure>(result)
        assertEquals(emptyMap(), mediaStore.rows)
        val folder = File(publicDirectory(Environment.DIRECTORY_PICTURES), "Unwritten")
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    private fun pluginFor(destination: GalleryDestination, customFolderName: String?) = androidSaver(
        context,
        MediaSaverConfig {
            this.destination = destination
            folderName = customFolderName
        },
    )

    private fun firstRowOf(collection: Uri): Uri = ContentUris.withAppendedId(collection, 1)

    @Suppress("DEPRECATION") // The pre-29 public directories are what MediaStore writes to there.
    private fun publicDirectory(type: String): File = Environment.getExternalStoragePublicDirectory(type)

    private fun writesTo(uri: Uri): ByteArrayOutputStream {
        val written = ByteArrayOutputStream()
        writesTo(uri) { written }
        return written
    }

    private fun writesTo(uri: Uri, open: () -> OutputStream) {
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri, Supplier { open() })
    }

    /** Opens the row's DATA file for the write, as MediaStore does below API 29. */
    private fun writesToItsFile(uri: Uri) = writesTo(uri) {
        FileOutputStream(mediaStore.rows.getValue(uri).getAsString(MediaStore.MediaColumns.DATA))
    }

    private fun loggedErrors(): List<Throwable?> {
        val logged = CopyOnWriteArrayList<Throwable?>()
        CameraKLogger.sink = { level, _, _, throwable ->
            if (level == CameraKLogger.Level.ERROR) logged += throwable
        }
        CameraKLogger.enabled = true
        return logged
    }
}
