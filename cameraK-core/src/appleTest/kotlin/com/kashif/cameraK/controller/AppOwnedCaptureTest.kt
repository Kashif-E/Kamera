@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The engine writes a capture to a real file: Documents/CameraK (DefaultOutputDirectoryTest), or the
 * configured output directory. Publishing it to Photos is the media saver's job (Decision 10).
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
    fun captureWritesUnderTheConfiguredOutputDirectory() = driver.runCase {
        val directory = "${NSTemporaryDirectory()}cameraK-captures-${NSUUID().UUIDString}"
        written += directory

        val image = capture(
            CameraConfiguration {
                outputDirectory = directory
                aspectRatio = AspectRatio.RATIO_4_3
            },
        )

        assertTrue(image.path.startsWith("$directory/"), "the capture is not under the configured output directory")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(image.path), "no file at the capture's path")
    }

    private companion object {
        val PHOTO = orientedJpeg(width = 8, height = 6, orientation = 1)
    }
}
