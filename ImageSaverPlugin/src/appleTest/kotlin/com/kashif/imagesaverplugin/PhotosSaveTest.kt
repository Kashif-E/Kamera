@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, CameraKPlatformApi::class)

package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.localIdentifier
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.CameraKPlatformApi
import com.kashif.cameraK.utils.toNSData
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `save(image)` and `save(video)` import the app-owned file into Photos off the main thread. A failed
 * or unfinished import is `Storage(GALLERY_IMPORT_FAILED)` whose `keptFilePath` is that file, which
 * stays as it was, with Photos' error as the cause. Ports PhotosImportTest's five cases and I3''s four
 * video cases from core, which no longer imports into Photos.
 */
class PhotosSaveTest {
    private val background = CoroutineScope(Dispatchers.Default + Job())
    private val photoBytes = ByteArray(64) { (it * 5).toByte() }
    private val files = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        files.forEach(::deleteFile)
        background.cancel()
    }

    private fun file(extension: String = "jpg"): String = appOwnedFile(photoBytes, extension).also { files += it }

    private fun saver(photos: FakePhotoLibrary) = ImageSaverPlugin(
        MediaSaverConfig(),
        AppleGalleryStore(photos, importTimeout = 200.milliseconds),
        { PermissionStatus.Granted },
    )

    private fun saveImage(photos: FakePhotoLibrary, path: String): CameraKResult<SavedImage> {
        val done = background.completesWithin(BOUND) { saver(photos).save(FakeCaptures.image(path = path)) }
        return assertNotNull(done, "save did not return within $BOUND while the main thread was busy").value
    }

    private fun saveVideo(photos: FakePhotoLibrary, path: String): CameraKResult<SavedVideo> {
        val done = background.completesWithin(BOUND) { saver(photos).save(FakeCaptures.video(path = path)) }
        return assertNotNull(done, "save did not return within $BOUND while the main thread was busy").value
    }

    private fun assertKept(result: CameraKResult<*>, path: String): CameraKError.Storage {
        val error = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(result, "result: $result").error)
        assertEquals(StorageFailure.GALLERY_IMPORT_FAILED, error.kind)
        assertEquals(path, error.keptFilePath, "the failure does not name the kept file")
        assertContentEquals(photoBytes, fileBytes(path), "the app-owned file was deleted or changed")
        return error
    }

    @Test
    fun savedImageCarriesLocalIdentifier() {
        val photos = FakePhotoLibrary(createdIdentifier = FakePhotoLibrary.ASSET_ID)
        val path = file()

        val saved = assertIs<CameraKResult.Success<SavedImage>>(saveImage(photos, path)).value

        assertEquals(FakePhotoLibrary.ASSET_ID, saved.asset.localIdentifier)
        val import = photos.imports.value.single()
        assertEquals(MediaKind.IMAGE, import.kind)
        assertEquals(path, import.url.path)
        assertEquals(false, photos.createdOnMainThread.value, "the import ran on the main thread")
    }

    @Test
    fun failedImportYieldsKeptFilePathAndKeepsTheFile() {
        val path = file()

        assertKept(saveImage(FakePhotoLibrary(createdIdentifier = null, createError = photosError()), path), path)
    }

    @Test
    fun anImportThatNeverFinishesFailsOnceItTimesOut() {
        val photos = FakePhotoLibrary(completesCreation = false)

        val result = saveImage(photos, file())

        assertIs<CameraKResult.Failure>(result, "an import Photos never finished was $result")
        assertEquals(1, photos.imports.value.size, "the photo was not handed to Photos once")
    }

    @Test
    fun aRefusedImportNamesWhatPhotosReportedAndKeepsTheFile() {
        val path = file()

        val error =
            assertKept(
                saveImage(FakePhotoLibrary(createdIdentifier = null, createError = photosError(3311)), path),
                path,
            )

        val message = error.cause?.message.orEmpty()
        assertTrue(
            "PHPhotosErrorDomain" in message && "3311" in message,
            "the cause does not say what Photos reported: $message",
        )
    }

    @Test
    fun aRefusedImportKeepsPhotosErrorAsItsCause() {
        val refusal = photosError(3311)

        val result = saveImage(FakePhotoLibrary(createdIdentifier = null, createError = refusal), file())

        val error = assertIs<CameraKResult.Failure>(result).error
        assertSame(refusal, assertIs<PhotosException>(error.cause).error, "the error does not carry Photos' NSError")
    }

    @Test
    fun anImportThatTimesOutKeepsTheFile() {
        val path = file()

        assertKept(saveImage(FakePhotoLibrary(completesCreation = false), path), path)
    }

    /** Ported from aCaptureThatPhotosImportsRemovesItsTemporaryCopy: the file is now the app's, so it stays. */
    @Test
    fun anImportedCaptureLeavesTheAppOwnedFileInPlace() {
        val path = file()

        assertIs<CameraKResult.Success<SavedImage>>(saveImage(FakePhotoLibrary(), path))

        assertContentEquals(photoBytes, fileBytes(path), "the saver touched the app-owned file")
    }

    @Test
    fun failedVideoImportYieldsKeptFilePathAndKeepsTheFile() {
        val path = file("mov")

        assertKept(saveVideo(FakePhotoLibrary(createdIdentifier = null, createError = photosError()), path), path)
    }

    @Test
    fun failedVideoImportNamesWhatPhotosReported() {
        val path = file("mov")

        val error =
            assertKept(
                saveVideo(FakePhotoLibrary(createdIdentifier = null, createError = photosError(3305)), path),
                path,
            )

        val message = error.cause?.message.orEmpty()
        assertTrue(
            "PHPhotosErrorDomain" in message && "3305" in message,
            "the cause does not say what Photos reported: $message",
        )
    }

    @Test
    fun failedVideoImportKeepsPhotosErrorAsItsCause() {
        val refusal = photosError(3305)

        val result = saveVideo(FakePhotoLibrary(createdIdentifier = null, createError = refusal), file("mov"))

        assertSame(refusal, assertIs<PhotosException>(assertIs<CameraKResult.Failure>(result).error.cause).error)
    }

    @Test
    fun videoImportThatNeverFinishesTimesOutAndKeepsTheFile() {
        val path = file("mov")
        val photos = FakePhotoLibrary(completesCreation = false)

        assertKept(saveVideo(photos, path), path)
        assertEquals(MediaKind.VIDEO, photos.imports.value.single().kind)
    }

    /** PR #183: a capture's path may be a plain file-system path, with no scheme. */
    @Test
    fun aCaptureAtAPlainPathIsImportedFromThatFile() {
        val photos = FakePhotoLibrary()
        val path = file()

        assertIs<CameraKResult.Success<SavedImage>>(saveImage(photos, path))

        val url = photos.imports.value.single().url
        assertTrue(url.isFileURL(), "a plain path was not imported as a file URL")
        assertEquals(path, url.path)
    }

    @Test
    fun aCaptureAtAFileUrlIsImportedFromThatFile() {
        val photos = FakePhotoLibrary()
        val path = file()

        assertIs<CameraKResult.Success<SavedImage>>(saveImage(photos, "file://$path"))

        assertEquals(path, photos.imports.value.single().url.path)
    }

    /** logging-pii-and-noise: one failed auto-save is one error log line, carrying its cause. */
    @Test
    fun aFailedAutoSaveIsLoggedOnce() = runTest {
        val errors = atomic(emptyList<Throwable?>())
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, _, throwable ->
            if (level == CameraKLogger.Level.ERROR) errors.update { it + throwable }
        }
        val photos = FakePhotoLibrary(createdIdentifier = null, createError = photosError())
        val plugin = ImageSaverPlugin(
            MediaSaverConfig { autoSave = true },
            AppleGalleryStore(photos, importTimeout = 200.milliseconds),
            { PermissionStatus.Granted },
        )
        val outcome = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { plugin.results.first() }
        val session = saverSession(plugin)
        startReady(session)

        session.holder.capture()
        val failure = assertIs<CameraKResult.Failure>(outcome.await(), "the auto-save Photos refused")

        val cause = assertNotNull(failure.error.cause)
        assertEquals(1, errors.value.size, "error log lines for one failed auto-save: ${errors.value}")
        assertSame(cause, errors.value.single(), "the failed auto-save was not logged with its cause")
    }

    /** PR #183: the deprecated read takes a capture's plain path as well as a `ph://` location. */
    @Test
    fun theDeprecatedReadReadsAPlainFilePath() {
        val path = file()

        val read = background.completesWithin(BOUND) {
            @Suppress("DEPRECATION")
            saver(FakePhotoLibrary()).readBytes(path)
        }

        assertContentEquals(photoBytes, assertNotNull(read, "readBytes did not return within $BOUND").value)
    }

    /** PR #183: the deprecated blocking read takes a plain path too, and reads the file, not Photos. */
    @Test
    fun theDeprecatedBlockingReadReadsAPlainFilePath() {
        val path = file()
        val photos = FakePhotoLibrary(imageData = byteArrayOf(1, 2, 3).toNSData())

        @Suppress("DEPRECATION")
        val bytes = saver(photos).getByteArrayFrom(path)

        assertContentEquals(photoBytes, bytes)
        assertEquals(null, photos.requestedIdentifier.value, "a plain path was looked up in Photos")
    }
}
