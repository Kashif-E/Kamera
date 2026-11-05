@file:OptIn(CameraKEngineApi::class, CameraKPlatformApi::class)

package com.kashif.imagesaverplugin

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.capture.uri
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `save(image)` and `save(video)` copy an app-owned file into MediaStore: from API 29 a pending row
 * with a RELATIVE_PATH in the destination's collection, below it a file under the public directory
 * named in the row's DATA. The saver is the only gallery writer, so one capture is one row.
 */
@RunWith(RobolectricTestRunner::class)
class GallerySaveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9, 8, 7)
    private val photo = File(context.filesDir, "CameraK/IMG_0001.jpg").apply {
        parentFile!!.mkdirs()
        writeBytes(photoBytes)
    }
    private val videoBytes = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte())
    private val video = File(context.filesDir, "CameraK/VID_0001.mp4").apply { writeBytes(videoBytes) }
    private val image = CapturedImage(photo.absolutePath, ImageFormat.JPEG, 4, 3)
    private val recording = RecordedVideo(video.absolutePath, 1.seconds, 4, 3, null, false)

    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    @Config(sdk = [35])
    fun oneCaptureCreatesExactlyOneMediaStoreRow() = runTest {
        writesToAnyRow(imagesCollection())
        val plugin = androidSaver(context, MediaSaverConfig { autoSave = true }, StandardTestDispatcher(testScheduler))
        val engine = FakeCameraEngine().apply { captureResult = { CameraKResult.Success(image) } }
        val holder =
            CameraKStateHolder(CameraConfiguration(), { _, _ -> engine }, listOf(plugin), cameraKTestEnvironment())
        val results = mutableListOf<CameraKResult<SavedImage>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { plugin.results.collect { results += it } }
        holder.start()
        runCurrent()

        holder.capture()
        runCurrent()

        assertEquals(1, mediaStore.rows.size, "rows after one capture: ${mediaStore.rows.keys}")
        assertIs<CameraKResult.Success<SavedImage>>(results.single())
        assertContentEquals(photoBytes, photo.readBytes(), "the capture itself changed")
    }

    /** logging-pii-and-noise: one failed auto-save is one error log line, carrying its cause. */
    @Test
    @Config(sdk = [35])
    fun aFailedAutoSaveIsLoggedOnce() = runTest {
        writesToAnyRow(imagesCollection())
        val errors = CopyOnWriteArrayList<Throwable?>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, _, throwable -> if (level == CameraKLogger.Level.ERROR) errors += throwable }
        val missing =
            CapturedImage(File(context.filesDir, "CameraK/IMG_missing.jpg").absolutePath, ImageFormat.JPEG, 4, 3)
        val plugin = androidSaver(context, MediaSaverConfig { autoSave = true }, StandardTestDispatcher(testScheduler))
        val engine = FakeCameraEngine().apply { captureResult = { CameraKResult.Success(missing) } }
        val holder =
            CameraKStateHolder(CameraConfiguration(), { _, _ -> engine }, listOf(plugin), cameraKTestEnvironment())
        val results = mutableListOf<CameraKResult<SavedImage>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { plugin.results.collect { results += it } }
        holder.start()
        runCurrent()

        holder.capture()
        runCurrent()

        val failure = assertIs<CameraKResult.Failure>(results.single(), "the auto-save of a missing file")
        val cause = failure.error.cause
        assertEquals(1, errors.size, "error log lines for one failed auto-save: $errors")
        assertTrue(cause != null && errors.single() === cause, "the failed auto-save was not logged with its cause")
    }

    @Test
    @Config(sdk = [29])
    fun api29VideoSaveSucceeds() = runTest {
        val row = ContentUris.withAppendedId(videoCollection(), 1)
        val written = writesTo(row)

        val result = androidSaver(context, io = StandardTestDispatcher(testScheduler)).save(recording, "clip")

        val saved = assertIs<CameraKResult.Success<SavedVideo>>(result, "a video save at API 29").value
        assertEquals(row, saved.asset.uri)
        assertContentEquals(videoBytes, written.toByteArray())
        val values = mediaStore.rows.getValue(row)
        assertEquals("Movies/CameraK", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("clip.mp4", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("video/mp4", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(0, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    @Config(sdk = [35])
    fun failedSaveDeletesThePendingRow() = runTest {
        val refusal = IOException("disk full")
        writesTo(ContentUris.withAppendedId(imagesCollection(), 1)) { throw refusal }

        val result = androidSaver(context, io = StandardTestDispatcher(testScheduler)).save(image, "shot")

        val error = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(refusal, error.cause)
        assertEquals(emptyMap(), mediaStore.rows, "the IS_PENDING row was left in MediaStore")
        assertTrue(photo.isFile, "a failed save touched the capture")
    }

    @Test
    @Config(sdk = [35])
    fun savedImageCarriesContentUri() = runTest {
        val row = ContentUris.withAppendedId(imagesCollection(), 1)
        val written = writesTo(row)

        val result = androidSaver(context, io = StandardTestDispatcher(testScheduler)).save(image, "shot")

        val saved = assertIs<CameraKResult.Success<SavedImage>>(result).value
        assertEquals(row, saved.asset.uri)
        assertEquals("shot.jpg", saved.displayName)
        assertContentEquals(photoBytes, written.toByteArray(), "the saved row does not hold the capture's bytes")
        assertEquals("image/jpeg", mediaStore.rows.getValue(row).getAsString(MediaStore.MediaColumns.MIME_TYPE))
    }

    @Test
    @Config(sdk = [28])
    fun failedSaveBelowApi29LeavesNoRowOrFile() = runTest {
        writesTo(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1)) {
            throw IOException("disk full")
        }

        val result = androidSaver(
            context,
            MediaSaverConfig {
                folderName = "Failed"
            },
            StandardTestDispatcher(testScheduler),
        )
            .save(image, "shot")

        assertIs<CameraKResult.Failure>(result)
        assertEquals(emptyMap(), mediaStore.rows)
        assertEquals(emptyList(), File(publicDir(Environment.DIRECTORY_PICTURES), "Failed").list().orEmpty().toList())
    }

    @Test
    @Config(sdk = [28])
    fun twoSavesInFlightGetDifferentNames() = runTest {
        val bothChoseAName = CyclicBarrier(2)
        mediaStore.beforeInsert = { bothChoseAName.await(10, TimeUnit.SECONDS) }
        listOf(1L, 2L).forEach {
            writesToItsFile(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it))
        }
        val plugin = androidSaver(context, MediaSaverConfig { folderName = "Burst" })

        val saved = List(2) { async { plugin.save(image, "shot") } }.awaitAll()
            .map { assertIs<CameraKResult.Success<SavedImage>>(it, "a save failed: $it").value }

        val files = saved.map { mediaStore.rows.getValue(it.asset.uri).getAsString(MediaStore.MediaColumns.DATA) }
        assertEquals(2, files.toSet().size, "both saves were given $files")
        files.forEach { assertContentEquals(photoBytes, File(it).readBytes(), "$it does not hold the capture") }
    }

    /** Each destination for a still and a video, per the destination table. */
    @Test
    @Config(sdk = [28, 35])
    fun destinationMapsToCollectionAndRelativePath() = runTest {
        val scoped = Build.VERSION.SDK_INT >= 29
        val expected = listOf(
            Triple(GalleryDestination.PICTURES, MediaKind.IMAGE, "Pictures"),
            Triple(GalleryDestination.PICTURES, MediaKind.VIDEO, "Movies"),
            Triple(GalleryDestination.DCIM, MediaKind.IMAGE, "DCIM"),
            Triple(GalleryDestination.DCIM, MediaKind.VIDEO, "DCIM"),
            Triple(GalleryDestination.DOCUMENTS, MediaKind.IMAGE, "Documents"),
            Triple(GalleryDestination.DOCUMENTS, MediaKind.VIDEO, "Documents"),
        )
        for ((destination, kind, directory) in expected) {
            mediaStore.rows.clear()
            val collection = when {
                !scoped && kind == MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                !scoped -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                destination == GalleryDestination.DOCUMENTS -> MediaStore.Files.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                )
                kind == MediaKind.IMAGE -> imagesCollection()
                else -> videoCollection()
            }
            writesToAnyRow(collection, toItsFile = !scoped)
            val plugin =
                androidSaver(
                    context,
                    MediaSaverConfig {
                        this.destination = destination
                    },
                    StandardTestDispatcher(testScheduler),
                )

            val result = if (kind == MediaKind.IMAGE) plugin.save(image, "m") else plugin.save(recording, "m")

            assertFalse(result is CameraKResult.Failure, "$destination $kind failed: $result")
            val (row, values) = mediaStore.rows.entries.single()
            assertEquals(
                collection.toString(),
                row.toString().substringBeforeLast('/'),
                "$destination $kind collection",
            )
            if (scoped) {
                assertEquals(
                    "$directory/CameraK",
                    values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH),
                    "$destination $kind",
                )
            } else {
                val file = File(values.getAsString(MediaStore.MediaColumns.DATA))
                assertEquals(
                    File(publicDir(directory), "CameraK").canonicalFile,
                    file.canonicalFile.parentFile,
                    "$destination $kind",
                )
                file.delete()
            }
        }
    }

    /** Folded from BLD: a failed read and a failed save log and throw text without the path or the URI. */
    @Test
    @Config(sdk = [35])
    fun failureTextNamesNoPathOrUri() = runTest {
        val texts = CopyOnWriteArrayList<String>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ -> texts += message }
        val plugin = androidSaver(context, io = StandardTestDispatcher(testScheduler))
        val missingFile = File(context.filesDir, "CameraK/IMG_missing.jpg").absolutePath
        val missingUri = "content://media/external_primary/images/media/404"

        val saveResult = plugin.save(CapturedImage(missingFile, ImageFormat.JPEG, 4, 3), "shot")
        val legacyRead = runCatching {
            @Suppress("DEPRECATION")
            plugin.readBytes(missingUri)
        }.exceptionOrNull()
        val legacyFileRead = runCatching {
            @Suppress("DEPRECATION")
            plugin.getByteArrayFrom(missingFile)
        }.exceptionOrNull()

        assertIs<CameraKResult.Failure>(saveResult)
        texts += listOfNotNull(legacyRead?.message, legacyFileRead?.message)
        assertTrue(texts.isNotEmpty(), "the failures were not logged")
        val leaking = texts.filter { missingFile in it || missingUri in it || "IMG_missing" in it || "/404" in it }
        assertEquals(emptyList(), leaking, "log or exception text holds a path or URI")
    }

    @Test
    @Config(sdk = [28])
    fun belowApi29WithoutWriteExternalStorageIsPermissionNotDetermined() = runTest {
        val config = MediaSaverConfig { folderName = "NoPermission" }
        val plugin = ImageSaverPlugin(config, CameraKPlatformContext(context))

        val result = plugin.save(image, "shot")

        val error = assertIs<CameraKError.Permission>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraKPermission.GALLERY_ADD, error.permission)
        assertEquals(PermissionStatus.NotDetermined, error.status)
        assertEquals(CameraKPermission.GALLERY_ADD, plugin.galleryPermission)
        assertEquals(emptyMap(), mediaStore.rows)
        assertEquals(
            emptyList(),
            File(publicDir(Environment.DIRECTORY_PICTURES), "NoPermission").list().orEmpty().toList(),
        )
    }

    @Test
    @Config(sdk = [29])
    fun api29NeedsNoPermission() = runTest {
        writesToAnyRow(imagesCollection())
        var reads = 0
        val plugin = androidSaver(context, io = StandardTestDispatcher(testScheduler), permission = {
            reads++
            PermissionStatus.PermanentlyDenied
        })

        val result = plugin.save(image, "shot")

        assertIs<CameraKResult.Success<SavedImage>>(result)
        assertEquals(null, plugin.galleryPermission)
        assertEquals(0, reads, "the permission was read at API 29")
    }

    private fun imagesCollection(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun videoCollection(): Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Suppress("DEPRECATION") // The pre-29 public directories are what MediaStore writes to there.
    private fun publicDir(type: String): File = Environment.getExternalStoragePublicDirectory(type)

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

    // FakeMediaStore numbers rows from 1 for the provider's lifetime; register the first few.
    private fun writesToAnyRow(collection: Uri, toItsFile: Boolean = false) {
        (1L..12L).forEach {
            val row = ContentUris.withAppendedId(collection, it)
            if (toItsFile) writesToItsFile(row) else writesTo(row)
        }
    }
}
