package com.kashif.mediasaverplugin

import com.kashif.cameraK.capture.localIdentifier
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSThread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The saver's Photos bridge, driven through a stand-in for PhotoKit. Each call runs on a background
 * thread while the test holds the main thread, as a busy UI would; a call that hangs, or that needs
 * the main thread, fails the bound instead of stalling the run.
 *
 * Retired with IS': the `getFilePathFromAsset` cases (AssetReadTest covers the streamed read that
 * replaces it) and the two throwing-callback cases. Retired with C1': the 1.x location reads and the
 * byte saves (AssetReadTest and PhotosSaveTest cover the 2.0 read and save).
 */
class IOSImageSaverPluginPhotosTest {
    private val background = CoroutineScope(Dispatchers.Default + Job())
    private val files = mutableListOf<String>()
    private val loggerWasEnabled = CameraKLogger.enabled
    private val previousSink = CameraKLogger.sink

    @AfterTest
    fun tearDown() {
        CameraKLogger.enabled = loggerWasEnabled
        CameraKLogger.sink = previousSink
        files.forEach(::deleteFile)
        background.cancel()
    }

    private fun capture() = FakeCaptures.image(
        path = appOwnedFile(ByteArray(16) { it.toByte() }, "jpg").also {
            files +=
                it
        },
    )

    @Test
    fun savingAnImageCompletesWithoutTheMainThread() {
        assertTrue(NSThread.isMainThread, "the test must hold the main thread for this check to mean anything")
        val photos = FakePhotoLibrary(createdIdentifier = ASSET_ID)
        val plugin = plugin(photos)

        val result = background.completesWithin(BOUND) { plugin.save(capture()) }

        assertNotNull(result, "save did not return within $BOUND while the main thread was busy")
        assertEquals(ASSET_ID, assertIs<CameraKResult.Success<SavedImage>>(result.value).value.asset.localIdentifier)
        assertEquals(false, photos.createdOnMainThread.value, "the Photos write ran on the main thread")
    }

    // Ported from the 1.x failure callback: the failure's cause is what Photos reported.
    @Test
    fun aFailedSaveReportsTheFailureWithoutTheMainThread() {
        val refusal = photosError(3305, "The library is full")
        val photos = FakePhotoLibrary(createdIdentifier = null, createError = refusal)
        val plugin = plugin(photos)

        val result = background.completesWithin(BOUND) { plugin.save(capture()) }

        assertNotNull(result, "save did not return within $BOUND while the main thread was busy")
        val failed = assertIs<CameraKResult.Failure>(result.value)
        assertSame(refusal, assertIs<PhotosException>(failed.error.cause).error)
    }

    @Test
    fun cancellingASaveThatPhotosNeverAnswersFinishesTheCall() {
        val photos = FakePhotoLibrary(completesCreation = false)
        val plugin = plugin(photos)

        val save = background.launch { plugin.save(capture()) }
        runBlocking {
            assertNotNull(
                withTimeoutOrNull(BOUND) { photos.creationStarted.await() },
                "save did not reach Photos within $BOUND while the main thread was busy",
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

        val result = background.completesWithin(BOUND) { plugin.save(capture()) }

        assertNotNull(result, "save did not return within $BOUND")
        assertIs<CameraKResult.Success<SavedImage>>(result.value)
        assertEquals(emptyList(), logs.value.filter { ASSET_ID in it }, "log messages carry the asset identifier")
    }

    private fun plugin(photos: FakePhotoLibrary) =
        MediaSaverPlugin(MediaSaverConfig(), AppleGalleryStore(photos), { PermissionStatus.Granted })

    private companion object {
        const val ASSET_ID = FakePhotoLibrary.ASSET_ID
    }
}
