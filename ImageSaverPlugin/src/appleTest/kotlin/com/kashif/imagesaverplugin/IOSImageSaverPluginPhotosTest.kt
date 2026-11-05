@file:Suppress("DEPRECATION")

package com.kashif.imagesaverplugin

import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSThread
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The deprecated byte API's Photos bridge, kept until C1', driven through a stand-in for PhotoKit.
 * Each call runs on a background thread while the test holds the main thread, as a busy UI would; a
 * call that hangs, or that needs the main thread, fails the bound instead of stalling the run.
 *
 * Retired with IS': the `getFilePathFromAsset` cases (the method is gone; AssetReadTest covers the
 * streamed read that replaces it) and the two throwing-callback cases (the 2.0 saver takes no
 * callbacks, so no app code runs inside a save).
 */
@OptIn(ExperimentalForeignApi::class)
class IOSImageSaverPluginPhotosTest {
    private val background = CoroutineScope(Dispatchers.Default + Job())
    private val fakes = mutableListOf<FakePhotoLibrary>()
    private val loggerWasEnabled = CameraKLogger.enabled
    private val previousSink = CameraKLogger.sink

    @AfterTest
    fun tearDown() {
        CameraKLogger.enabled = loggerWasEnabled
        CameraKLogger.sink = previousSink
        fakes.forEach { it.answerBlockedReads() }
        background.cancel()
    }

    @Test
    fun readingAnAssetReturnsTheDataPhotosDeliversDuringTheRequest() {
        // The main thread is held, so only a synchronous request can deliver the data in time;
        // an asynchronous one would answer on the main thread after the call gave up.
        val photos = FakePhotoLibrary(imageData = ASSET_BYTES.toNSData())
        val plugin = plugin(photos)

        val read = background.completesWithin(BOUND) { plugin.getByteArrayFrom("ph://$ASSET_ID") }

        assertNotNull(read, "getByteArrayFrom did not return within $BOUND")
        assertContentEquals(ASSET_BYTES, read.value)
        assertEquals(ASSET_ID, photos.requestedIdentifier.value)
    }

    @Test
    fun readingAMissingAssetFailsInsteadOfWaiting() {
        val plugin = plugin(FakePhotoLibrary(assetExists = false))

        val read = background.completesWithin(BOUND) { runCatching { plugin.getByteArrayFrom("ph://$ASSET_ID") } }

        assertNotNull(read, "getByteArrayFrom did not return within $BOUND")
        assertFailsWith<Exception> { read.value.getOrThrow() }
    }

    @Test
    fun readBytesReturnsTheDataPhotosDeliversOnTheMainThread() {
        val plugin = plugin(FakePhotoLibrary(imageData = ASSET_BYTES.toNSData()))

        val read = background.completesWhileMainRuns(BOUND) { plugin.readBytes("ph://$ASSET_ID") }

        assertNotNull(read, "readBytes did not return within $BOUND")
        assertContentEquals(ASSET_BYTES, read.value)
    }

    @Test
    fun readBytesOfAMissingAssetFails() {
        val plugin = plugin(FakePhotoLibrary(assetExists = false))

        val read = background.completesWhileMainRuns(BOUND) { runCatching { plugin.readBytes("ph://$ASSET_ID") } }

        assertNotNull(read, "readBytes did not return within $BOUND")
        assertFailsWith<IOException> { read.value.getOrThrow() }
    }

    @Test
    fun readBytesFailsWhenPhotosDeliversNoData() {
        val plugin = plugin(FakePhotoLibrary(imageData = null))

        val read = background.completesWhileMainRuns(BOUND) { runCatching { plugin.readBytes("ph://$ASSET_ID") } }

        assertNotNull(read, "readBytes did not return within $BOUND")
        assertFailsWith<IOException> { read.value.getOrThrow() }
    }

    @Test
    fun cancellingAReadThatPhotosHasNotAnsweredFinishesTheCallAndCancelsTheRequest() {
        val photos = FakePhotoLibrary(imageData = ASSET_BYTES.toNSData(), answersReads = false)
        val plugin = plugin(photos)

        // A read that outlives the test may still fail once Photos answers; that must not escape.
        val read = background.launch { runCatching { plugin.readBytes("ph://$ASSET_ID") } }
        runBlocking {
            val requestId = assertNotNull(
                withTimeoutOrNull(BOUND) { photos.readStarted.await() },
                "readBytes did not reach Photos within $BOUND",
            )
            read.cancel()
            assertNotNull(withTimeoutOrNull(BOUND) { read.join() }, "the cancelled read was still running after $BOUND")
            assertEquals(listOf(requestId), photos.cancelledRequests.value, "the Photos request was left running")
        }
    }

    @Test
    fun savingAnImageCompletesWithoutTheMainThread() {
        assertTrue(NSThread.isMainThread, "the test must hold the main thread for this check to mean anything")
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)
        val plugin = plugin(photos)

        val location = background.completesWithin(BOUND) { plugin.saveImage(pngBytes()) }

        assertNotNull(location, "saveImage did not return within $BOUND while the main thread was busy")
        assertEquals("ph://$ASSET_ID", location.value)
        assertEquals(false, photos.createdOnMainThread.value, "the Photos write ran on the main thread")
    }

    // Ported from the 1.x failure callback: the failure's cause is now what Photos reported.
    @Test
    fun aFailedSaveReportsTheFailureWithoutTheMainThread() {
        val refusal = photosError(3305, "The library is full")
        val photos = FakePhotoLibrary(createdIdentifier = null, createError = refusal)
        val plugin = plugin(photos)

        val result = background.completesWithin(BOUND) { plugin.save(pngBytes()) }
        val location = background.completesWithin(BOUND) { plugin.saveImage(pngBytes()) }

        assertNotNull(result, "save did not return within $BOUND while the main thread was busy")
        assertNull(assertNotNull(location).value, "a failed write has no location")
        val failed = assertIs<ImageSaveResult.Failed>(result.value)
        assertSame(refusal, assertIs<PhotosException>(failed.error).error)
    }

    @Test
    fun cancellingASaveThatPhotosNeverAnswersFinishesTheCall() {
        val photos = FakePhotoLibrary(completesCreation = false)
        val plugin = plugin(photos)

        val save = background.launch { plugin.saveImage(pngBytes()) }
        runBlocking {
            assertNotNull(
                withTimeoutOrNull(BOUND) { photos.creationStarted.await() },
                "saveImage did not reach Photos within $BOUND while the main thread was busy",
            )
            save.cancel()
            assertNotNull(withTimeoutOrNull(BOUND) { save.join() }, "the cancelled save was still running after $BOUND")
        }
    }

    @Test
    fun aSuccessfulSaveLogsNoAssetIdentifier() {
        val logs = atomic(emptyList<String>())
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ -> logs.update { it + message } }
        val plugin = plugin(FakePhotoLibrary(createdIdentifier = ASSET_ID))

        val location = background.completesWithin(BOUND) { plugin.saveImage(pngBytes()) }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertEquals("ph://$ASSET_ID", location.value)
        assertEquals(emptyList(), logs.value.filter { ASSET_ID in it }, "log messages carry the asset identifier")
    }

    @Test
    fun savingAJpegStoresItsBytesInPhotosUnchanged() {
        val jpeg = jpegBytes()
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)

        val location = background.completesWithin(BOUND) { plugin(photos).saveImage(jpeg) }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertEquals("ph://$ASSET_ID", location.value)
        assertStoredUnchanged(photos, jpeg, "public.jpeg")
    }

    @Test
    fun savingAPngStoresItsBytesInPhotosUnchanged() {
        val png = pngBytes()
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)

        val location = background.completesWithin(BOUND) { plugin(photos).saveImage(png) }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertStoredUnchanged(photos, png, "public.png")
    }

    /** Guard: bytes that are not an image are a reported failure that never reaches Photos. */
    @Test
    fun savingBytesThatAreNotAnImageFailsWithoutReachingPhotos() {
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)
        val plugin = plugin(photos)

        val result = background.completesWithin(BOUND) { plugin.save(byteArrayOf(1, 2, 3, 4)) }

        assertNotNull(result, "save did not return within $BOUND")
        assertIs<ImageSaveResult.Failed>(result.value, "bytes that are not an image were saved")
        assertEquals(false, photos.creationStarted.isCompleted, "bytes that are not an image reached Photos")
    }

    private fun assertStoredUnchanged(photos: FakePhotoLibrary, bytes: ByteArray, type: String) {
        val (stored, storedType) = assertNotNull(photos.storedPhoto.value, "Photos was not given the image's bytes")
        assertContentEquals(bytes, stored, "Photos was not given the bytes saveImage received")
        assertEquals(type, storedType)
    }

    private fun plugin(photos: FakePhotoLibrary) =
        ImageSaverPlugin(MediaSaverConfig(), AppleGalleryStore(photos), { PermissionStatus.Granted }).also {
            fakes +=
                photos
        }

    private fun jpegBytes(): ByteArray {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(4.0, 4.0), true, 1.0)
        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return assertNotNull(UIImageJPEGRepresentation(assertNotNull(image), 0.9)).toByteArray()
    }

    private fun pngBytes(): ByteArray {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(4.0, 4.0), true, 1.0)
        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return assertNotNull(UIImagePNGRepresentation(assertNotNull(image))).toByteArray()
    }

    private companion object {
        const val ASSET_ID = FakePhotoLibrary.ASSET_ID
        val ASSET_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    }
}
