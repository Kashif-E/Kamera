@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.TestScope
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.Foundation.NSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A still has the configured aspect ratio (w6 decision G1). 1:1 is a centred square of the Photo
 * preset's 4:3 photo (true 1:1); 16:9 without a target runs the Photo preset and keeps a centred 16:9
 * crop of the full-resolution photo, so it loses height, never width. The crop is of the sensor's
 * pixels, and the photo's EXIF orientation is kept, so a portrait photo is a tall crop.
 */
class AspectRatioTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    private suspend fun TestScope.capture(ratio: AspectRatio, photo: NSData): CapturedImage {
        val engine = driver.createEngine(CameraConfiguration(aspectRatio = ratio))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val (capture, request) = captureReachingThePhotoOutput(driver, engine)
        val result = deliverPhoto(driver, request, capture, photo)
        engine.close()
        return assertIs<CameraKResult.Success<CapturedImage>>(result, "the $ratio capture failed: $result").value
            .also { written += it.path }
    }

    @Test
    fun oneToOneProducesSquareOutput() = driver.runCase {
        val image = capture(AspectRatio.RATIO_1_1, orientedJpeg(width = 64, height = 48, orientation = 1))

        val file = imageFileProperties(image.path)
        assertEquals(48 to 48, file.width to file.height, "the 1:1 file's pixels")
        assertEquals(48 to 48, image.width to image.height, "the 1:1 capture's reported size")
    }

    @Test
    fun sixteenNineUsesFullResolutionFormat() = driver.runCase {
        val image = capture(AspectRatio.RATIO_16_9, orientedJpeg(width = 64, height = 48, orientation = 1))

        assertEquals(listOf(AVCaptureSessionPresetPhoto), driver.session.presets, "the preset a 16:9 still runs at")
        val file = imageFileProperties(image.path)
        assertEquals(64 to 36, file.width to file.height, "the 16:9 file keeps the photo's full width")
        assertEquals(64 to 36, image.width to image.height, "the 16:9 capture's reported size")
    }

    @Test
    fun aPortraitPhotoIsCroppedAlongItsSensorsLongSideAndKeepsItsOrientation() = driver.runCase {
        val image = capture(AspectRatio.RATIO_9_16, orientedJpeg(width = 64, height = 48, orientation = RIGHT_TOP))

        val file = imageFileProperties(image.path)
        assertEquals(Triple(64, 36, RIGHT_TOP), Triple(file.width, file.height, file.orientation), "the file")
        assertEquals(36 to 64, image.width to image.height, "the size the portrait capture displays at")
    }

    @Test
    fun aFourThreePhotoIsWrittenAsTheCameraDeliveredIt() = driver.runCase {
        val photo = orientedJpeg(width = 64, height = 48, orientation = 1)

        val image = capture(AspectRatio.RATIO_4_3, photo)

        assertContentEquals(photo.toByteArray(), fileBytes(image.path), "a 4:3 photo was re-encoded")
        assertEquals(64 to 48, image.width to image.height)
    }

    private companion object {
        /** EXIF orientation 6: the sensor's pixels must be turned 90 degrees clockwise to display. */
        const val RIGHT_TOP = 6
    }
}
