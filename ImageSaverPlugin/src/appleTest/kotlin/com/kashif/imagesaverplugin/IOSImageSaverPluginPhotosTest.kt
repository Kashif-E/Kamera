package com.kashif.imagesaverplugin

import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSThread
import platform.Photos.PHImageRequestOptions
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The plugin's Photos bridge, driven through a stand-in for PhotoKit so the simulator needs no
 * photo-library permission. Each call runs on a background thread while the test holds the main
 * thread, as a busy UI would; a call that hangs, or that needs the main thread, fails the bound
 * instead of stalling the run.
 */
@OptIn(ExperimentalForeignApi::class)
class IOSImageSaverPluginPhotosTest {
    private val background = CoroutineScope(Dispatchers.Default + Job())

    @AfterTest
    fun tearDown() {
        background.cancel()
    }

    @Test
    fun readingAnAssetReturnsTheDataPhotosDeliversDuringTheRequest() {
        // With synchronous = true, PHImageManager runs the result handler on the calling thread
        // before requestImageDataAndOrientation returns, which is what this stand-in does.
        val photos = FakePhotoLibrary(imageData = ASSET_BYTES.toNSData())
        val plugin = plugin(photos)

        val read = completesWithin(BOUND) { plugin.getByteArrayFrom("ph://$ASSET_ID") }

        assertNotNull(read, "getByteArrayFrom did not return within $BOUND")
        assertContentEquals(ASSET_BYTES, read.value)
        assertEquals(ASSET_ID, photos.requestedIdentifier.value)
    }

    @Test
    fun readingAMissingAssetFailsInsteadOfWaiting() {
        val plugin = plugin(FakePhotoLibrary(assetExists = false))

        val read = completesWithin(BOUND) { runCatching { plugin.getByteArrayFrom("ph://$ASSET_ID") } }

        assertNotNull(read, "getByteArrayFrom did not return within $BOUND")
        assertFailsWith<Exception> { read.value.getOrThrow() }
    }

    @Test
    fun resolvingAnAssetToAFileWritesTheAssetsBytes() {
        val plugin = plugin(FakePhotoLibrary(imageData = ASSET_BYTES.toNSData()))

        val resolved = completesWithin(BOUND) { plugin.getFilePathFromAsset("ph://$ASSET_ID") }

        assertNotNull(resolved, "getFilePathFromAsset did not return within $BOUND")
        val path = assertNotNull(resolved.value, "the asset should resolve to a file")
        try {
            val written = assertNotNull(NSFileManager.defaultManager.contentsAtPath(path))
            assertContentEquals(ASSET_BYTES, written.toByteArray())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun savingAnImageCompletesWithoutTheMainThread() {
        assertTrue(NSThread.isMainThread, "the test must hold the main thread for this check to mean anything")
        // Photos may call the completion before performChanges returns; the bridge must not lose it.
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)
        val saved = atomic(0)
        val plugin = plugin(photos, onImageSaved = { saved.incrementAndGet() })

        val location = completesWithin(BOUND) { plugin.saveImage(pngBytes()) }

        assertNotNull(location, "saveImage did not return within $BOUND while the main thread was busy")
        assertEquals("ph://$ASSET_ID", location.value)
        assertEquals(1, saved.value, "onImageSaved should run once")
        assertEquals(false, photos.createdOnMainThread.value, "the Photos write ran on the main thread")
    }

    @Test
    fun aFailedSaveReportsTheFailureWithoutTheMainThread() {
        val photos = FakePhotoLibrary(createdIdentifier = null, createError = photosError("The library is full"))
        val failures = mutableListOf<String>()
        val plugin = plugin(photos, onImageSavedFailed = { failures += it })

        val location = completesWithin(BOUND) { plugin.saveImage(pngBytes()) }

        assertNotNull(location, "saveImage did not return within $BOUND while the main thread was busy")
        assertNull(location.value, "a failed write has no location")
        assertEquals(listOf("The library is full"), failures)
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

    private fun plugin(
        photos: PhotoLibraryGateway,
        onImageSaved: () -> Unit = {},
        onImageSavedFailed: (String) -> Unit = {},
    ) = IOSImageSaverPlugin(ImageSaverConfig(), onImageSaved, onImageSavedFailed, photos)

    /**
     * Runs [block] on a background thread and waits at most [timeout] for it. Returns null on
     * timeout; a blocked thread is abandoned rather than joined, so a hang cannot stall the suite.
     */
    private fun <T> completesWithin(timeout: Duration, block: suspend () -> T): Completed<T>? = runBlocking {
        val result = background.async { Completed(block()) }
        withTimeoutOrNull(timeout) { result.await() }
    }

    private class Completed<T>(val value: T)

    private class FakePhotoLibrary(
        private val assetExists: Boolean = true,
        private val imageData: NSData? = null,
        private val createdIdentifier: String? = ASSET_ID,
        private val createError: NSError? = null,
        private val completesCreation: Boolean = true,
    ) : PhotoLibraryGateway {
        val requestedIdentifier = atomic<String?>(null)
        val createdOnMainThread = atomic<Boolean?>(null)
        val creationStarted = CompletableDeferred<Unit>()

        override fun createImageAsset(image: UIImage, completion: (String?, NSError?) -> Unit) {
            createdOnMainThread.value = NSThread.isMainThread
            creationStarted.complete(Unit)
            if (completesCreation) completion(createdIdentifier, createError)
        }

        override fun requestImageData(
            localIdentifier: String,
            options: PHImageRequestOptions,
            resultHandler: (NSData?) -> Unit,
        ): Boolean {
            requestedIdentifier.value = localIdentifier
            if (!assetExists) return false
            resultHandler(imageData)
            return true
        }
    }

    private fun photosError(description: String) = NSError.errorWithDomain(
        "PHPhotosErrorDomain",
        3305,
        mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description),
    )

    private fun pngBytes(): ByteArray {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(4.0, 4.0), true, 1.0)
        val image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return assertNotNull(UIImagePNGRepresentation(assertNotNull(image))).toByteArray()
    }

    private companion object {
        const val ASSET_ID = "4F1C2A3B-0000-4000-8000-000000000001/L0/001"
        val ASSET_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val BOUND = 5.seconds
    }
}
