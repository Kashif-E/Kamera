@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeHEVC
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.Foundation.NSLock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * iOS honours `imageFormat = HEIC` (design §6.1, I3'): where the photo output can encode HEVC the
 * capture asks it for an HEVC photo and writes AVFoundation's HEIC file as it is. Where it cannot, the
 * capture fails with Unsupported(IMAGE_FORMAT) before the photo output is asked for anything, and the
 * refusal is logged once.
 */
class HeicEngineCaptureTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        removeFiles(written)
    }

    private val heic = CameraConfiguration(imageFormat = ImageFormat.HEIC, aspectRatio = AspectRatio.RATIO_4_3)

    @Test
    fun heicConfiguredProducesHeicFile() = driver.runCase {
        driver.photoCapturer.codecTypes = listOf(AVVideoCodecTypeHEVC!!, AVVideoCodecTypeJPEG!!)
        val engine = driver.createEngine(heic)
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        val (capture, request) = captureReachingThePhotoOutput(driver, engine)
        assertEquals(
            AVVideoCodecTypeHEVC,
            request.settings.format?.get(AVVideoCodecKey),
            "the codec the capture asked for",
        )
        val photo = orientedJpeg(width = 8, height = 6, orientation = 1)
        val image = assertIs<CameraKResult.Success<CapturedImage>>(deliverPhoto(driver, request, capture, photo)).value
        written += image.path

        assertEquals(ImageFormat.HEIC, image.format)
        assertTrue(image.path.endsWith(".heic"), "a HEIC capture's file is not a .heic file")
        assertContentEquals(photo.toByteArray(), fileBytes(image.path), "the photo AVFoundation encoded was rewritten")
        engine.close()
    }

    @Test
    fun aHeicCaptureWithoutHevcFailsWithoutAskingThePhotoOutput() = driver.runCase {
        val lock = NSLock()
        val logged = mutableListOf<String>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ ->
            if ("HEIC" in message) {
                lock.lock()
                logged += message
                lock.unlock()
            }
        }
        val engine = driver.createEngine(heic)
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        repeat(2) {
            val refused = assertIs<CameraKResult.Failure>(engine.capture(), "a HEIC capture without HEVC")
            assertEquals(CameraFeature.IMAGE_FORMAT, assertIs<CameraKError.Unsupported>(refused.error).feature)
        }

        assertEquals(0, driver.photoCapturer.requests.size, "a HEIC capture without HEVC reached the photo output")
        lock.lock()
        val lines = logged.toList()
        lock.unlock()
        assertEquals(1, lines.size, "the refusal is logged once: $lines")
        engine.close()
    }
}
