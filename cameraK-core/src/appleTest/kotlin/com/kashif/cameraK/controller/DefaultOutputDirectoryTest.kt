@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import platform.Foundation.NSFileManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * With no output directory configured, a still and a recording land in the app-owned Documents/CameraK
 * as real files, never as a Photos identifier (Decision 10, design §6.1). A configuration can no longer
 * name a gallery folder (CF'), so this is the only default there is; publishing is the media saver's.
 */
class DefaultOutputDirectoryTest {
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun defaultIsTheDocumentsLocation() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine(CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 })
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val (capture, request) = captureReachingThePhotoOutput(driver, engine)
            val result = deliverPhoto(driver, request, capture, PHOTO)
            engine.close()
            val image = assertIs<CameraKResult.Success<CapturedImage>>(result, "the capture failed: $result").value
            written += image.path

            assertFalse(image.path.startsWith("ph://"), "the capture is a Photos identifier: ${image.path}")
            assertTrue(image.path.contains("/Documents/CameraK/"), "the capture is not in Documents/CameraK")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(image.path), "no file at the capture's path")
            assertContentEquals(PHOTO.toByteArray(), fileBytes(image.path), "the file is not the photo")
            assertEquals(ImageFormat.JPEG, image.format)
            assertEquals(8 to 6, image.width to image.height, "the size of the photo written")
        }
    }

    @Test
    fun aRecordingDefaultsToTheDocumentsLocation() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine(CameraConfiguration())
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            val recording = startEngineRecording(driver, engine).also { written += it.path }
            assertTrue(recording.path.contains("/Documents/CameraK/"), "the recording is not in Documents/CameraK")
            val stop = async { engine.stopRecording() }
            driver.settle()
            finishRecording(driver, recording)
            repeat(20) { if (!stop.isCompleted) driver.settle() }

            val video = assertIs<CameraKResult.Success<RecordedVideo>>(stop.await()).value
            assertEquals(recording.path, video.path, "the recording's result is its file")
            assertFalse(video.path.startsWith("ph://"), "a Photos identifier")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(video.path), "the file is gone")
            engine.close()
        }
    }

    private companion object {
        val PHOTO = orientedJpeg(width = 8, height = 6, orientation = 1)
    }
}
