package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A PICTURES or DCIM capture ends with an import into Photos, which may never finish: Photos holds
 * the import while its permission prompt goes unanswered. The capture waits a bounded time for it
 * and then reports the failure, instead of parking a thread until Photos answers.
 */
@OptIn(ExperimentalForeignApi::class)
class PhotosImportTest {
    private val rig = CaptureRig(directory = Directory.PICTURES)
    private var photos = FakePhotosLibrary()

    @AfterTest
    fun tearDown() {
        rig.tearDown()
        photos.imports.forEach {
            it.url.path?.let { path -> NSFileManager.defaultManager.removeItemAtPath(path, null) }
        }
    }

    private fun usePhotos(answer: FakePhotosLibrary.Answer) {
        photos = FakePhotosLibrary(answer)
        rig.controller.photosLibrary = photos
    }

    /** Takes a picture whose photo AVFoundation delivers, and returns what takePictureToFile returned. */
    private fun takePicture(): ImageCaptureResult {
        rig.start()
        val (capture, request) = rig.capture()
        request.callbacks.photoProcessed(PHOTO.toNSData(), null)
        request.callbacks.captureFinished(null)
        return capture.awaitResult()
    }

    @Test
    fun aCaptureWhosePhotosImportNeverFinishesFailsOnceTheImportTimesOut() {
        usePhotos(FakePhotosLibrary.Answer.Never)
        rig.controller.photosImportTimeoutMs = 200

        val result = takePicture()

        assertIs<ImageCaptureResult.Error>(result, "a capture Photos never imported was reported as $result")
        assertEquals(1, photos.imports.size, "the photo was not handed to Photos once")
    }

    private companion object {
        val PHOTO = ByteArray(64) { (it * 5).toByte() }
    }
}
