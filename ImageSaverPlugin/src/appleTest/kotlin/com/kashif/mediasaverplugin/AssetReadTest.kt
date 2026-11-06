@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.GalleryAsset
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSThread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `readBytes(saved)` streams the asset's resource through PHAssetResourceManager into
 * `Library/Caches/CameraK`, off the calling thread, and deletes that file once read. Replaces the
 * 1.x `getFilePathFromAsset` cases (ios-asset-temp-files), whose files the app had to delete.
 */
@OptIn(InternalCameraKApi::class)
class AssetReadTest {
    private val background = CoroutineScope(Dispatchers.Default + Job())
    private val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        background.cancel()
    }

    private fun saved() = SavedImage("IMG.jpg", GalleryAsset("IMG.jpg", localIdentifier = FakePhotoLibrary.ASSET_ID))

    private fun saver(photos: FakePhotoLibrary) =
        ImageSaverPlugin(MediaSaverConfig(), AppleGalleryStore(photos), { PermissionStatus.Granted })

    private fun setPermissions(path: String, mode: Int) {
        check(
            NSFileManager.defaultManager.setAttributes(mapOf<Any?, Any?>(NSFilePosixPermissions to mode), path, null),
        ) {
            "could not change the folder's permissions"
        }
    }

    @Test
    fun readStreamsThroughResourceManagerOffTheCallingThread() {
        assertTrue(NSThread.isMainThread, "the test must hold the main thread for this check to mean anything")
        val photos = FakePhotoLibrary(resourceBytes = bytes)

        val read = background.completesWithin(BOUND) { saver(photos).readBytes(saved()) }

        val result = assertNotNull(read, "readBytes did not return within $BOUND while the main thread was busy").value
        assertContentEquals(bytes, assertIs<CameraKResult.Success<ByteArray>>(result).value)
        assertEquals(FakePhotoLibrary.ASSET_ID, photos.requestedIdentifier.value)
        assertEquals(false, photos.wroteOnMainThread.value, "the resource was requested on the main thread")
        assertTrue(
            photos.writtenTo.value.orEmpty().contains("/Library/Caches/CameraK/"),
            "the resource went outside Caches/CameraK",
        )
    }

    @Test
    fun aReadLeavesNoFileInCaches() {
        val photos = FakePhotoLibrary(resourceBytes = bytes)

        background.completesWithin(BOUND) { saver(photos).readBytes(saved()) }

        val written = assertNotNull(photos.writtenTo.value, "nothing was streamed")
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(written), "the streamed file was left behind")
    }

    @Test
    fun aReadOfAMissingAssetFailsAndLogsNoIdentifier() {
        val messages = atomic(emptyList<String>())
        val throwables = atomic(emptyList<Throwable?>())
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            messages.update { it + message }
            if (level == CameraKLogger.Level.ERROR) throwables.update { it + throwable }
        }

        val read = background.completesWithin(BOUND) { saver(FakePhotoLibrary(assetExists = false)).readBytes(saved()) }

        assertIs<CameraKResult.Failure>(assertNotNull(read, "readBytes did not return within $BOUND").value)
        assertTrue(
            throwables.value.isNotEmpty() &&
                throwables.value.all {
                    it != null
                },
            "the failure was not logged with its cause",
        )
        assertEquals(
            emptyList(),
            messages.value.filter {
                FakePhotoLibrary.ASSET_ID in it
            },
            "log messages carry the asset identifier",
        )
    }

    /**
     * PHAssetResourceManager cannot cancel a write, so a read cancelled while Photos is still writing
     * must not delete the file before it exists: the file is deleted once Photos has written it.
     */
    @Test
    fun aCancelledReadLeavesNoFileOncePhotosHasWrittenIt() {
        val photos = FakePhotoLibrary(resourceBytes = bytes, holdsResourceWrites = true)
        val read = background.async { saver(photos).readBytes(saved()) }
        val written = runBlocking { withTimeout(BOUND) { photos.resourceWriteStarted.await() } }

        runBlocking { withTimeout(BOUND) { read.cancelAndJoin() } }
        photos.releaseResourceWrite()
        runBlocking { withTimeout(BOUND) { photos.resourceWriteFinished.await() } }

        assertTrue(read.isCancelled, "the read was not cancelled")
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(written),
            "a cancelled read left Photos' file in Caches/CameraK",
        )
    }

    /** A temporary file that cannot be deleted is logged, with the file manager's error and no path. */
    @Test
    fun aTempFileThatCannotBeDeletedIsLogged() {
        val errors = atomic(emptyList<Pair<String, Throwable?>>())
        val messages = atomic(emptyList<String>())
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            messages.update { it + message }
            if (level == CameraKLogger.Level.ERROR) errors.update { it + (message to throwable) }
        }
        val locked = atomic<String?>(null)
        val photos = FakePhotoLibrary(resourceBytes = bytes, afterResourceWrite = { path ->
            val folder = path.substringBeforeLast('/')
            locked.value = folder
            setPermissions(folder, READ_AND_EXECUTE)
        })
        try {
            val read = background.completesWithin(BOUND) { saver(photos).readBytes(saved()) }

            val result = assertNotNull(read, "readBytes did not return within $BOUND").value
            assertContentEquals(bytes, assertIs<CameraKResult.Success<ByteArray>>(result).value)
            val written = assertNotNull(photos.writtenTo.value)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(written), "the test's premise: the delete fails")
            val logged = errors.value.singleOrNull { (_, throwable) -> throwable != null }
            assertNotNull(logged, "the failed delete was not logged with an error: ${errors.value}")
            val fileName = written.substringAfterLast('/')
            assertEquals(emptyList(), messages.value.filter { fileName in it }, "log text names the file")
            assertFalse(fileName in logged.second?.message.orEmpty(), "the logged error names the file")
        } finally {
            locked.value?.let { setPermissions(it, OWNER_WRITABLE) }
            photos.writtenTo.value?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }

    @Test
    fun aRefusedResourceWriteIsAFailure() {
        val read = background.completesWithin(BOUND) {
            saver(FakePhotoLibrary(resourceError = photosError(3300))).readBytes(saved())
        }

        assertIs<CameraKResult.Failure>(assertNotNull(read, "readBytes did not return within $BOUND").value)
    }
}

// r-x for everyone: nothing in the folder can be deleted.
private const val READ_AND_EXECUTE = 0b101_101_101

// rwxr-xr-x, as the store creates it.
private const val OWNER_WRITABLE = 0b111_101_101
