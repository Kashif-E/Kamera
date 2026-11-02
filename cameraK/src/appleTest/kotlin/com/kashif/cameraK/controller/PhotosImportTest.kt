package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A PICTURES or DCIM capture ends with an import into Photos, which may never finish: Photos holds
 * the import while its permission prompt goes unanswered. The capture waits a bounded time for it
 * and then reports the failure, instead of parking a thread until Photos answers. A failed or
 * unfinished import keeps the captured file, the only copy of the photo, and the error says what
 * Photos reported; the temporary copy goes only once Photos has the photo.
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

    @Test
    fun aCaptureThatPhotosRefusesIsAnErrorThatNamesPhotosReasonAndKeepsThePhoto() {
        usePhotos(FakePhotosLibrary.Answer.Failed(photosError(3311)))

        val result = takePicture()

        val error = assertIs<ImageCaptureResult.Error>(result, "a capture Photos refused was reported as $result")
        val message = error.exception.message.orEmpty()
        assertTrue(
            "PHPhotosErrorDomain" in message && "3311" in message,
            "the error does not say what Photos reported: $message",
        )
        assertPhotoKept()
    }

    @Test
    fun aCaptureThatPhotosRefusesKeepsPhotosErrorAsItsCause() {
        val refusal = photosError(3311)
        usePhotos(FakePhotosLibrary.Answer.Failed(refusal))

        val result = takePicture()

        val error = assertIs<ImageCaptureResult.Error>(result, "a capture Photos refused was reported as $result")
        assertSame(refusal, error.exception.platformError(), "the error does not carry Photos' NSError")
    }

    @Test
    fun aCaptureWhosePhotosImportTimesOutKeepsThePhoto() {
        usePhotos(FakePhotosLibrary.Answer.Never)
        rig.controller.photosImportTimeoutMs = 200

        val result = takePicture()

        assertIs<ImageCaptureResult.Error>(result, "a capture Photos never imported was reported as $result")
        assertPhotoKept()
    }

    /** Guard: once Photos has the photo, its temporary copy goes (green before the fix). */
    @Test
    fun aCaptureThatPhotosImportsRemovesItsTemporaryCopy() {
        usePhotos(FakePhotosLibrary.Answer.Imported("4F1C2A3B/L0/001"))

        val result = takePicture()

        assertEquals(ImageCaptureResult.SuccessWithFile("ph://4F1C2A3B/L0/001"), result)
        val path = assertNotNull(photos.imports.single().url.path)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(path), "the temporary copy was left behind")
    }

    private fun assertPhotoKept() {
        val path = assertNotNull(photos.imports.single().url.path)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path), "the only copy of the photo was deleted")
        assertContentEquals(PHOTO, fileBytes(path), "the kept file is not the photo")
    }

    private companion object {
        val PHOTO = ByteArray(64) { (it * 5).toByte() }
    }
}
