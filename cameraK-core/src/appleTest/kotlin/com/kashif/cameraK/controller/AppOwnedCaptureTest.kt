@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The engine writes a capture to app-owned storage only, a real file: Documents/CameraK, or the
 * configured output directory. A PICTURES or DCIM configuration no longer sends the photo to Photos,
 * whose ph:// identifier was no file at all; publishing it is the media saver's job (Decision 10).
 */
class AppOwnedCaptureTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    private suspend fun kotlinx.coroutines.test.TestScope.capture(config: CameraConfiguration): CapturedImage {
        val engine = driver.createEngine(config)
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val (capture, request) = captureReachingThePhotoOutput(driver, engine)
        val result = deliverPhoto(driver, request, capture, PHOTO)
        engine.close()
        return assertIs<CameraKResult.Success<CapturedImage>>(result, "the capture failed: $result").value
            .also { written += it.path }
    }

    @Test
    fun galleryConfiguredCaptureReturnsARealPathNotPhUri() = driver.runCase {
        for (directory in listOf(Directory.PICTURES, Directory.DCIM)) {
            val image = capture(CameraConfiguration(directory = directory, aspectRatio = AspectRatio.RATIO_4_3))

            assertFalse(image.path.startsWith("ph://"), "a $directory capture is a Photos identifier: ${image.path}")
            assertTrue(image.path.contains("/Documents/CameraK/"), "a $directory capture is not in Documents/CameraK")
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(image.path), "no file at the capture's path")
            assertContentEquals(PHOTO.toByteArray(), fileBytes(image.path), "the file is not the photo")
            assertEquals(ImageFormat.JPEG, image.format)
            assertEquals(8 to 6, image.width to image.height, "the size of the photo written")
        }
    }

    @Test
    fun captureWritesUnderTheConfiguredOutputDirectory() = driver.runCase {
        val directory = "${NSTemporaryDirectory()}cameraK-captures-${NSUUID().UUIDString}"
        written += directory

        val image = capture(CameraConfiguration(outputDirectory = directory, aspectRatio = AspectRatio.RATIO_4_3))

        assertTrue(image.path.startsWith("$directory/"), "the capture is not under the configured output directory")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(image.path), "no file at the capture's path")
    }

    private companion object {
        val PHOTO = orientedJpeg(width = 8, height = 6, orientation = 1)
    }
}
