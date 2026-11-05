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
import kotlinx.coroutines.cancel
import platform.Foundation.NSFileManager
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

    @Test
    fun aRefusedResourceWriteIsAFailure() {
        val read = background.completesWithin(BOUND) {
            saver(FakePhotoLibrary(resourceError = photosError(3300))).readBytes(saved())
        }

        assertIs<CameraKResult.Failure>(assertNotNull(read, "readBytes did not return within $BOUND").value)
    }
}
