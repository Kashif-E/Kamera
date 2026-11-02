package com.kashif.imagesaverplugin

import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toNSData
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
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
import kotlinx.io.IOException
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.Photos.PHImageRequestID
import platform.Photos.PHImageRequestOptions
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
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
import kotlin.time.TimeSource

/**
 * The plugin's Photos bridge, driven through a stand-in for PhotoKit so the simulator needs no
 * photo-library permission. Each call runs on a background thread while the test holds the main
 * thread, as a busy UI would; a call that hangs, or that needs the main thread, fails the bound
 * instead of stalling the run. The stand-in follows PHImageManager's documented contract: a
 * synchronous request answers once on the calling thread before it returns and cannot be
 * cancelled; an asynchronous one answers once, later, on the main thread.
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
    fun resolvingAMissingAssetLogsTheFailureWithItsCauseAndNoIdentifier() {
        val logs = capturedLogs()
        val plugin = plugin(FakePhotoLibrary(assetExists = false))

        val resolved = completesWithin(BOUND) { plugin.getFilePathFromAsset("ph://$ASSET_ID") }

        assertNotNull(resolved, "getFilePathFromAsset did not return within $BOUND")
        assertNull(resolved.value, "a missing asset has no file")
        val errors = logs.value.filter { it.level == CameraKLogger.Level.ERROR }
        assertTrue(errors.isNotEmpty(), "the failed resolve was not logged")
        assertTrue(errors.all { it.throwable != null }, "the failure was logged without its throwable: $errors")
        assertNoAssetIdentifierIn(logs.value)
    }

    @Test
    fun readBytesReturnsTheDataPhotosDeliversOnTheMainThread() {
        val plugin = plugin(FakePhotoLibrary(imageData = ASSET_BYTES.toNSData()))

        val read = completesWhileMainRuns(BOUND) { plugin.readBytes("ph://$ASSET_ID") }

        assertNotNull(read, "readBytes did not return within $BOUND")
        assertContentEquals(ASSET_BYTES, read.value)
    }

    @Test
    fun readBytesOfAMissingAssetFails() {
        val plugin = plugin(FakePhotoLibrary(assetExists = false))

        val read = completesWhileMainRuns(BOUND) { runCatching { plugin.readBytes("ph://$ASSET_ID") } }

        assertNotNull(read, "readBytes did not return within $BOUND")
        assertFailsWith<IOException> { read.value.getOrThrow() }
    }

    @Test
    fun readBytesFailsWhenPhotosDeliversNoData() {
        val plugin = plugin(FakePhotoLibrary(imageData = null))

        val read = completesWhileMainRuns(BOUND) { runCatching { plugin.readBytes("ph://$ASSET_ID") } }

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

    @Test
    fun aSaveWhoseOnImageSavedThrowsStillReportsTheSavedLocation() {
        val failures = atomic(emptyList<String>())
        val plugin = plugin(
            FakePhotoLibrary(createdIdentifier = ASSET_ID),
            onImageSaved = { throw IllegalStateException("consumer bug") },
            onImageSavedFailed = { message -> failures.update { it + message } },
        )

        val location = completesWithin(BOUND) { runCatching { plugin.saveImage(pngBytes()) } }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertEquals("ph://$ASSET_ID", location.value.getOrThrow(), "Photos created the asset")
        assertEquals(emptyList(), failures.value, "a save Photos completed was reported as failed")
    }

    @Test
    fun aFailedSaveWhoseOnImageSavedFailedThrowsStillReturnsNull() {
        val plugin = plugin(
            FakePhotoLibrary(createdIdentifier = null, createError = photosError("The library is full")),
            onImageSavedFailed = { throw IllegalStateException("consumer bug") },
        )

        val location = completesWithin(BOUND) { runCatching { plugin.saveImage(pngBytes()) } }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertNull(location.value.getOrThrow(), "a failed write has no location")
    }

    @Test
    fun aSuccessfulSaveLogsNoAssetIdentifier() {
        val logs = capturedLogs()
        val plugin = plugin(FakePhotoLibrary(createdIdentifier = ASSET_ID))

        val location = completesWithin(BOUND) { plugin.saveImage(pngBytes()) }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertEquals("ph://$ASSET_ID", location.value)
        assertNoAssetIdentifierIn(logs.value)
    }

    @Test
    fun savingAJpegStoresItsBytesInPhotosUnchanged() {
        val jpeg = jpegBytes()
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)

        val location = completesWithin(BOUND) { plugin(photos).saveImage(jpeg) }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertEquals("ph://$ASSET_ID", location.value)
        assertStoredUnchanged(photos, jpeg, "public.jpeg")
    }

    @Test
    fun savingAPngStoresItsBytesInPhotosUnchanged() {
        val png = pngBytes()
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)

        val location = completesWithin(BOUND) { plugin(photos).saveImage(png) }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertStoredUnchanged(photos, png, "public.png")
    }

    /** Guard: bytes that are not an image are a reported failure that never reaches Photos (green before the fix). */
    @Test
    fun savingBytesThatAreNotAnImageFailsWithoutReachingPhotos() {
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)
        val failures = atomic(emptyList<String>())
        val plugin = plugin(photos, onImageSavedFailed = { message -> failures.update { it + message } })

        val location = completesWithin(BOUND) { plugin.saveImage(byteArrayOf(1, 2, 3, 4)) }

        assertNotNull(location, "saveImage did not return within $BOUND")
        assertNull(location.value, "bytes that are not an image have no location")
        assertEquals(1, failures.value.size, "the failure was not reported once")
        assertEquals(false, photos.creationStarted.isCompleted, "bytes that are not an image reached Photos")
    }

    private fun assertStoredUnchanged(photos: FakePhotoLibrary, bytes: ByteArray, type: String) {
        val (stored, storedType) = assertNotNull(photos.storedPhoto.value, "Photos was not given the image's bytes")
        assertContentEquals(bytes, stored, "Photos was not given the bytes saveImage received")
        assertEquals(type, storedType)
    }

    private fun plugin(
        photos: FakePhotoLibrary,
        onImageSaved: () -> Unit = {},
        onImageSavedFailed: (String) -> Unit = {},
    ) = IOSImageSaverPlugin(ImageSaverConfig(), onImageSaved, onImageSavedFailed, photos).also { fakes += photos }

    /**
     * Runs [block] on a background thread and waits at most [timeout] for it. Returns null on
     * timeout; a blocked thread is abandoned rather than joined, so a hang cannot stall the suite.
     */
    private fun <T> completesWithin(timeout: Duration, block: suspend () -> T): Completed<T>? = runBlocking {
        val result = background.async { Completed(block()) }
        withTimeoutOrNull(timeout) { result.await() }
    }

    /**
     * Like [completesWithin], but keeps the main run loop turning while it waits, so work Photos
     * delivers on the main thread can run.
     */
    private fun <T> completesWhileMainRuns(timeout: Duration, block: suspend () -> T): Completed<T>? {
        val result = background.async { Completed(block()) }
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!result.isCompleted && deadline.hasNotPassedNow()) {
            NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        return if (result.isCompleted) runBlocking { result.await() } else null
    }

    private class Completed<T>(val value: T)

    private class LogEntry(val level: CameraKLogger.Level, val message: String, val throwable: Throwable?) {
        override fun toString() = "$level $message (${throwable?.let { it::class.simpleName }})"
    }

    private fun capturedLogs(): kotlinx.atomicfu.AtomicRef<List<LogEntry>> {
        val logs = atomic(emptyList<LogEntry>())
        CameraKLogger.enabled = true
        CameraKLogger.sink =
            { level, _, message, throwable -> logs.update { it + LogEntry(level, message, throwable) } }
        return logs
    }

    private fun assertNoAssetIdentifierIn(logs: List<LogEntry>) {
        val leaking = logs.filter { ASSET_ID in it.message }
        assertTrue(leaking.isEmpty(), "log messages carry the asset identifier: $leaking")
    }

    private class FakePhotoLibrary(
        private val assetExists: Boolean = true,
        private val imageData: NSData? = null,
        private val createdIdentifier: String? = ASSET_ID,
        private val createError: NSError? = null,
        private val completesCreation: Boolean = true,
        private val answersReads: Boolean = true,
    ) : PhotoLibraryGateway {
        val requestedIdentifier = atomic<String?>(null)
        val createdOnMainThread = atomic<Boolean?>(null)

        /** The bytes and type of the photo resource stored byte for byte, if one was. */
        val storedPhoto = atomic<Pair<ByteArray, String>?>(null)
        val creationStarted = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<PHImageRequestID>()
        val cancelledRequests = atomic(emptyList<PHImageRequestID>())
        private val nextRequestId = atomic(1)

        // A synchronous read Photos has not answered blocks its caller, as PhotoKit does, until the
        // test ends.
        private val blockedReads = dispatch_semaphore_create(0)

        fun answerBlockedReads() {
            repeat(4) { dispatch_semaphore_signal(blockedReads) }
        }

        override fun createPhotoAsset(
            data: NSData,
            uniformTypeIdentifier: String,
            completion: (String?, NSError?) -> Unit,
        ) {
            storedPhoto.value = data.toByteArray() to uniformTypeIdentifier
            create(completion)
        }

        private fun create(completion: (String?, NSError?) -> Unit) {
            createdOnMainThread.value = NSThread.isMainThread
            creationStarted.complete(Unit)
            if (completesCreation) completion(createdIdentifier, createError)
        }

        override fun requestImageData(
            localIdentifier: String,
            options: PHImageRequestOptions,
            resultHandler: (NSData?) -> Unit,
        ): PHImageRequestID? {
            requestedIdentifier.value = localIdentifier
            if (!assetExists) return null
            val requestId = nextRequestId.getAndIncrement()
            readStarted.complete(requestId)
            if (options.synchronous) {
                if (!answersReads) {
                    dispatch_semaphore_wait(blockedReads, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC.toLong()))
                }
                resultHandler(if (answersReads) imageData else null)
            } else if (answersReads) {
                val data = imageData
                dispatch_async(dispatch_get_main_queue()) { resultHandler(data) }
            }
            // An unanswered asynchronous read never calls back; after a cancel PhotoKit may not either.
            return requestId
        }

        override fun cancelImageRequest(requestId: PHImageRequestID) {
            cancelledRequests.update { it + requestId }
        }
    }

    private fun photosError(description: String) = NSError.errorWithDomain(
        "PHPhotosErrorDomain",
        3305,
        mapOf<Any?, Any?>(NSLocalizedDescriptionKey to description),
    )

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
        const val ASSET_ID = "4F1C2A3B-0000-4000-8000-000000000001/L0/001"
        val ASSET_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val BOUND = 5.seconds
    }
}
